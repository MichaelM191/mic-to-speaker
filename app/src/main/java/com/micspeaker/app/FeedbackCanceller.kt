package com.micspeaker.app

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Speaker-echo canceller (the kind hearing aids use).
 *
 * We know exactly what we sent to the speaker. The mic hears your fresh sound PLUS that speaker
 * sound coming back a little later. This finds how much later (by matching the two), learns how
 * the speaker sounds once it reaches the mic, and subtracts only that copy. Your fresh voice or
 * instrument isn't in the speaker yet, so it isn't removed.
 *
 * Call [cancel] with each mic sample, then [push] with the sample you finally send to the speaker.
 */
class FeedbackCanceller(private val rate: Int) {
    private val size = 1 shl 17           // ~2.7 s of history at 48 kHz
    private val mask = size - 1
    private val out = FloatArray(size)
    private val mic = FloatArray(size)
    private var n = 0

    private val taps = 256                // ~5 ms of speaker-to-mic sound
    private val pre = 48                  // start the window a bit before the measured delay
    private val w = FloatArray(taps)
    private val mu = 0.004f

    @Volatile private var found = -1      // window start in samples, -1 = not measured yet
    private var cur = -1
    private var pow = 0f
    private var micAvg = 0f
    private var errAvg = 0f

    // Delay measuring, done off the audio thread.
    private val step = 8                  // work at rate/8 (6 kHz): plenty for speech and music
    private val win = 1024                // ~170 ms window to match
    private val maxLag = (0.45 * rate / step).toInt()
    private val minLag = (0.004 * rate / step).toInt().coerceAtLeast(2)
    private val every = rate / 2
    private var since = 0
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "echo-measure").apply { isDaemon = true } }
    private var lastLag = -1
    private var agree = 0

    /** Measured round-trip delay (mic → speaker → mic) in ms, or null if not found. */
    val delayMs: Int? get() = found.takeIf { it >= 0 }?.let { (it + pre) * 1000 / rate }

    fun cancel(x: Float): Float {
        val i = n and mask
        mic[i] = x
        val d = found
        if (d != cur) {
            cur = d; w.fill(0f); pow = 0f
            if (d > 0) for (j in 0 until taps) { val v = out[(i - d - j) and mask]; pow += v * v }
        }
        if (d <= 0) return x

        var k = (i - d) and mask
        var yh = 0f
        for (j in 0 until taps) { yh += w[j] * out[k]; k = (k - 1) and mask }
        val e = x - yh

        val inc = out[(i - d) and mask]
        val dec = out[(i - d - taps) and mask]
        pow = (pow + inc * inc - dec * dec).coerceAtLeast(0f)
        val g = mu * e / (pow + taps * 1.0e4f)
        k = (i - d) and mask
        for (j in 0 until taps) { w[j] += g * out[k]; k = (k - 1) and mask }

        // If it ever makes things worse instead of better, start learning again.
        micAvg += (abs(x) - micAvg) * 0.0005f
        errAvg += (abs(e) - errAvg) * 0.0005f
        if (errAvg > micAvg * 1.6f + 50f) { w.fill(0f); errAvg = micAvg; return x }
        return e
    }

    /** Speaker sample from one echo-delay ago, lined up with the current mic sample (0 if not found yet). */
    fun ref(): Float { val d = found; return if (d < 0) 0f else out[(n - d - pre) and mask] }
    val locked: Boolean get() = found >= 0

    fun push(y: Float) {
        out[n and mask] = y
        n++
        if (++since >= every) { since = 0; measure() }
    }

    private fun measure() {
        if (!busy.compareAndSet(false, true)) return
        val end = n
        val outLen = (win + maxLag) * step
        val micLen = win * step
        val o = FloatArray(outLen) { out[(end - outLen + it) and mask] }
        val m = FloatArray(micLen) { mic[(end - micLen + it) and mask] }
        worker.execute {
            try { estimate(o, m) } finally { busy.set(false) }
        }
    }

    private fun decimate(src: FloatArray): FloatArray {
        val d = FloatArray(src.size / step)
        for (k in d.indices) { var s = 0f; for (j in 0 until step) s += src[k * step + j]; d[k] = s / step }
        // First difference flattens the spectrum so the match locks onto the echo, not the voice's own rhythm.
        for (k in d.size - 1 downTo 1) d[k] -= d[k - 1]
        d[0] = 0f
        return d
    }

    private fun estimate(oRaw: FloatArray, mRaw: FloatArray) {
        val o = decimate(oRaw)
        val m = decimate(mRaw)
        var eo = 0f; for (v in o) eo += v * v
        var em = 0f; for (v in m) em += v * v
        if (eo / o.size < 400f || em / m.size < 100f) return // too quiet to tell

        var best = 0f; var bestLag = -1; var sum = 0f; var count = 0
        for (lag in minLag..maxLag) {
            var c = 0f
            val off = maxLag - lag
            for (k in 0 until win) c += m[k] * o[k + off]
            val a = abs(c)
            sum += a; count++
            if (a > best) { best = a; bestLag = lag }
        }
        if (bestLag < 0 || count == 0) return
        val confidence = best / (sum / count)
        if (confidence < 6f) return

        if (lastLag >= 0 && abs(bestLag - lastLag) <= 1) agree++ else agree = 1
        lastLag = bestLag
        if (agree < 2) return
        val start = (bestLag * step - pre).coerceAtLeast(1)
        if (found < 0 || abs(start - found) > pre / 2) found = start
    }

    fun close() { worker.shutdownNow() }
}
