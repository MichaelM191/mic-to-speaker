package com.micspeaker.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/** Reads the chosen mic and writes it straight to the chosen output. */
object Engine {
    val running = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)
    /** Measured delay from mic, through the speaker, back to the mic (ms). Null when no echo is heard. */
    val delayMs = MutableStateFlow<Int?>(null)

    @Volatile var volume = 0.8f
    /** Mic sensitivity: how strongly the input is picked up, before the output volume. */
    @Volatile var micGain = 1f
    @Volatile var boost = false
    /** Anti-squeal: finds feedback howls and cuts out just that pitch. Your voice passes through. */
    @Volatile var antiSqueal = true
    /** Equaliser gains in dB for Bass, Low, Mid, Presence, Treble. Replace the whole array to change it. */
    @Volatile var eq = FloatArray(5)
    var noise = false
    var input: Choice? = null
    var output: Choice? = null
    var onEnded: (() -> Unit)? = null

    @Volatile private var go = false
    private var canceller: FeedbackCanceller? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val ctx = context.applicationContext
        val inp = input ?: return
        val out = output
        error.value = null
        go = true
        thread = Thread({ loop(ctx, inp, out) }, "mic-loop").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        go = false
        thread?.join(1500)
        thread = null
    }

    fun restartIfRunning(context: Context) {
        if (running.value || thread?.isAlive == true) {
            stop()
            start(context)
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop(ctx: Context, inp: Choice, out: Choice?) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val btMic = inp.device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || inp.device?.type == 26
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        val effects = mutableListOf<AudioEffect>()
        try {
            if (btMic) {
                if (Build.VERSION.SDK_INT >= 31) {
                    am.setCommunicationDevice(inp.device!!)
                } else {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION") am.startBluetoothSco()
                    @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                }
            }
            val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
            val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minIn <= 0) throw IllegalStateException("This phone can't record at $rate Hz.")

            // For the normal phone mic, ask for the live-performance mode (Android 10+): lowest delay, no processing.
            if (inp.source == MediaRecorder.AudioSource.MIC && Build.VERSION.SDK_INT >= 29) {
                rec = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_PERFORMANCE, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn * 2)
                }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
            }
            if (rec == null) rec = AudioRecord(inp.source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("That mic can't be opened right now. Try another one.")
            inp.device?.let { rec.setPreferredDevice(it) }

            // No echo canceller on purpose: here the speaker plays your own voice, so an echo
            // canceller would erase you. Anti-squeal below handles feedback instead.
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = noise; effects += it }

            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, rate / 5 * 2)) // room to grow up to ~200 ms if needed
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            out?.device?.let { track.setPreferredDevice(it) }

            // Start with a small delay; if the phone can't keep up (a gap in the sound),
            // grow the buffer a step so the sound stays smooth.
            val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.coerceIn(64, 1024) ?: 256
            var bufFrames = maxOf(burst * 4, minOut / 2)
            runCatching { bufFrames = track.setBufferSizeInFrames(bufFrames) }
            var underruns = track.underrunCount

            rec.startRecording()
            track.play()
            running.value = true

            val guard = HowlGuard(rate)
            val gate = EchoGate(rate)
            val eqf = Equalizer(rate)
            val fc = FeedbackCanceller(rate).also { canceller = it }
            val shift = FreqShift(rate)
            val smart = SmartFocus(rate)
            val chunk = burst.coerceAtMost(minIn / 2)
            val buf = ShortArray(chunk)
            var shown = 0f
            while (go) {
                val n = rec.read(buf, 0, chunk)
                if (n <= 0) continue
                val inGain = micGain
                val outGain = volume * (if (boost) 2f else 1f)
                val guardOn = antiSqueal
                eqf.set(eq)
                var peak = 0f
                for (i in 0 until n) {
                    val s = buf[i] * inGain
                    val a = abs(s)
                    if (a > peak) peak = a
                    var y = if (guardOn) guard.process(gate.process(fc.cancel(s).let { e -> smart.process(e, fc.ref(), fc.locked) })) else s
                    y = eqf.process(y) * outGain
                    if (guardOn) y = shift.process(y)
                    y = softLimit(y)
                    val q = y.toInt().coerceIn(-32768, 32767)
                    buf[i] = q.toShort()
                    fc.push(q.toFloat())
                }
                track.write(buf, 0, n)
                val u = track.underrunCount
                if (u > underruns) {
                    underruns = u
                    if (bufFrames < track.bufferCapacityInFrames) {
                        runCatching { bufFrames = track.setBufferSizeInFrames(bufFrames + burst) }
                    }
                }
                // The meter shows the mic after sensitivity, so you can see what the slider does.
                delayMs.value = if (guardOn) fc.delayMs else null
                shown = maxOf((peak / 32768f).coerceAtMost(1f), shown * 0.85f)
                level.value = shown
            }
        } catch (e: Exception) {
            error.value = e.message ?: "Couldn't start the audio."
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            effects.forEach { runCatching { it.release() } }
            if (btMic) runCatching {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else {
                    @Suppress("DEPRECATION") am.stopBluetoothSco()
                    @Suppress("DEPRECATION") am.isBluetoothScoOn = false
                }
                am.mode = AudioManager.MODE_NORMAL
            }
            canceller?.close(); canceller = null
            running.value = false
            level.value = 0f
            delayMs.value = null
            // Ended by an error (not by stop()): tell the service so it can close.
            if (go) {
                go = false
                main.post { onEnded?.invoke() }
            }
        }
    }

    /** Rounds off peaks instead of hard clipping, so loud bits don't crackle. */
    private fun softLimit(y: Float): Float {
        val knee = 24000f
        val a = abs(y)
        if (a <= knee) return y
        val over = (a - knee) * 0.25f
        return (if (y < 0) -1f else 1f) * minOf(32000f, knee + over)
    }
}
