package com.micspeaker.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
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

    @Volatile var volume = 0.8f
    /** Mic sensitivity: how strongly the input is picked up, before the output volume. */
    @Volatile var micGain = 1f
    @Volatile var boost = false
    var echo = true
    var noise = false
    var input: Choice? = null
    var output: Choice? = null
    var onEnded: (() -> Unit)? = null

    @Volatile private var go = false
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

            rec = AudioRecord(inp.source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("That mic can't be opened right now. Try another one.")
            inp.device?.let { rec.setPreferredDevice(it) }

            val session = rec.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = echo; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = noise; effects += it }

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
                .setBufferSizeInBytes(minOut * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            out?.device?.let { track.setPreferredDevice(it) }

            rec.startRecording()
            track.play()
            running.value = true

            val chunk = (minIn / 4).coerceAtLeast(128)
            val buf = ShortArray(chunk)
            var shown = 0f
            while (go) {
                val n = rec.read(buf, 0, chunk)
                if (n <= 0) continue
                val inGain = micGain
                val outGain = volume * (if (boost) 2f else 1f)
                var peak = 0f
                for (i in 0 until n) {
                    val s = buf[i] * inGain
                    val a = abs(s)
                    if (a > peak) peak = a
                    buf[i] = (s * outGain).toInt().coerceIn(-32768, 32767).toShort()
                }
                track.write(buf, 0, n)
                // The meter shows the mic after sensitivity, so you can see what the slider does.
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
            running.value = false
            level.value = 0f
            // Ended by an error (not by stop()): tell the service so it can close.
            if (go) {
                go = false
                main.post { onEnded?.invoke() }
            }
        }
    }
}
