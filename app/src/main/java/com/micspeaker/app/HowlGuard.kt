package com.micspeaker.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Feedback (squeal) suppressor.
 *
 * A howl is one pure tone that keeps getting louder. A voice is many tones that keep moving.
 * Every ~10 ms this looks at the sound's spectrum. When one pitch towers far above everything
 * else and stays put for a moment, it puts a very narrow notch filter on exactly that pitch.
 * The rest of the sound, including your voice, passes through untouched.
 */
class HowlGuard(private val rate: Int) {
    private val n = 1024
    private val hop = n / 2
    private val ring = FloatArray(n)
    private var pos = 0
    private var sinceHop = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
    private val notches = ArrayList<Notch>()
    private var candBin = -1
    private var candCount = 0

    private val lo = (150f * n / rate).toInt().coerceAtLeast(2)
    private val hi = (9000f * n / rate).toInt().coerceAtMost(n / 2 - 2)

    fun process(x: Float): Float {
        ring[pos] = x
        pos = (pos + 1) % n
        if (++sinceHop >= hop) {
            sinceHop = 0
            analyze()
        }
        var y = x
        for (f in notches) y = f.run(y)
        return y
    }

    private fun analyze() {
        for (i in 0 until n) {
            re[i] = ring[(pos + i) % n] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        var maxBin = -1
        var maxPow = 0f
        var sum = 0f
        for (k in lo..hi) {
            val p = re[k] * re[k] + im[k] * im[k]
            sum += p
            if (p > maxPow) { maxPow = p; maxBin = k }
        }
        val mean = sum / (hi - lo + 1)
        // Tonal = one pitch at least ~35 dB above average, and loud enough to matter.
        val tonal = maxBin > 0 && maxPow > mean * 3000f && maxPow > 1.5e11f
        if (tonal && candBin >= 0 && abs(maxBin - candBin) <= 1) {
            candCount++
        } else {
            candBin = if (tonal) maxBin else -1
            candCount = if (tonal) 1 else 0
        }
        // Same pitch for ~12 looks in a row (about an eighth of a second): that's a howl.
        if (candCount >= 12) {
            addNotch(refine(maxBin))
            candBin = -1
            candCount = 0
        }
    }

    /** Sharpens the pitch estimate between two FFT bins. */
    private fun refine(k: Int): Float {
        fun mag(i: Int) = re[i] * re[i] + im[i] * im[i]
        val a = mag(k - 1); val b = mag(k); val c = mag(k + 1)
        val d = a - 2 * b + c
        val shift = if (d != 0f) (0.5f * (a - c) / d).coerceIn(-0.5f, 0.5f) else 0f
        return (k + shift) * rate / n
    }

    private fun addNotch(freq: Float) {
        val binHz = rate.toFloat() / n
        if (notches.any { abs(it.freq - freq) < binHz * 1.5f }) return
        if (notches.size >= 8) notches.removeAt(0)
        notches += Notch(freq, rate, q = 30f)
    }

    private class Notch(val freq: Float, rate: Int, q: Float) {
        private val b0: Float; private val b1: Float; private val b2: Float
        private val a1: Float; private val a2: Float
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        init {
            val w0 = 2 * PI * freq / rate
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val a0 = 1 + alpha
            b0 = (1 / a0).toFloat()
            b1 = (-2 * cw / a0).toFloat()
            b2 = (1 / a0).toFloat()
            a1 = (-2 * cw / a0).toFloat()
            a2 = ((1 - alpha) / a0).toFloat()
        }

        fun run(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
