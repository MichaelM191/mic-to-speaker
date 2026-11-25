package com.micspeaker.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smart echo blocker. Works pitch-by-pitch (128 frequency bands, ~5 ms at a time).
 *
 * For every band it compares what the mic hears now with what the speaker played a moment ago
 * (the echo source), and learns how loud that speaker sound is when it gets back to the mic.
 * Bands where the mic is mostly hearing the speaker again are turned down; bands with new sound
 * in them (your voice, an instrument, a fresh note starting) are left alone. So it focuses on
 * live sound and blocks the copy coming out of the speaker.
 */
class SmartFocus(rate: Int) {
    private val n = 256
    private val hop = n / 2
    private val bins = n / 2 + 1
    private val win = FloatArray(n) { sqrt(0.5f - 0.5f * cos(2 * PI * it / n).toFloat()) } // sqrt-Hann, perfect rebuild at 50% overlap

    private val inBuf = FloatArray(n)
    private val refBuf = FloatArray(n)
    private val olBuf = FloatArray(n)
    private val outQ = FloatArray(hop)
    private var fill = 0
    private var outPos = 0

    private val xr = FloatArray(n); private val xi = FloatArray(n)
    private val rr = FloatArray(n); private val ri = FloatArray(n)

    private val tail = FloatArray(bins)                 // speaker energy incl. room ring-out
    private val h = FloatArray(bins) { 0.05f }         // learned speaker→mic loudness per band
    private val g = FloatArray(bins) { 1f }
    private val gs = FloatArray(bins)
    private val prevX = FloatArray(bins)
    private val framesPerSec = rate.toFloat() / hop
    private val tailDecay = exp(-1.0 / (0.15 * framesPerSec)).toFloat()    // room ring-out ~150 ms

    /** Strength 0..1. */
    @Volatile var strength = 1f

    /** [x] = mic sample, [ref] = speaker sample from one echo-delay ago (0 if unknown), [on] = active. */
    fun process(x: Float, ref: Float, on: Boolean): Float {
        inBuf[fill] = x
        refBuf[fill] = ref
        fill++
        val y = outQ[outPos++]
        if (fill == n) {
            frame(on)
            // Keep the second half as the first half of the next frame.
            System.arraycopy(inBuf, hop, inBuf, 0, hop)
            System.arraycopy(refBuf, hop, refBuf, 0, hop)
            fill = hop
            outPos = 0
        }
        return y
    }

    private fun frame(on: Boolean) {
        for (i in 0 until n) { xr[i] = inBuf[i] * win[i]; xi[i] = 0f }
        fft(xr, xi)
        if (on) {
            for (i in 0 until n) { rr[i] = refBuf[i] * win[i]; ri[i] = 0f }
            fft(rr, ri)
            val over = 1.4f * strength
            val floor = 1f - 0.88f * strength            // never below about -18 dB
            for (k in 0 until bins) {
                val px = xr[k] * xr[k] + xi[k] * xi[k]
                val pr = rr[k] * rr[k] + ri[k] * ri[k]
                tail[k] = maxOf(pr, tail[k] * tailDecay)
                if (tail[k] > 1e7f) {
                    // Learn the speaker→mic loudness from the quietest moments (when only echo is there).
                    val ratio = (px / tail[k]).coerceIn(1e-4f, 1f)
                    h[k] += (ratio - h[k]) * (if (ratio < h[k]) 0.08f else 0.0015f)
                }
                val echo = h[k] * tail[k]
                var gain = (1f - over * echo / (px + 1f)).coerceIn(floor, 1f)
                // A sudden jump of new energy in this band is live sound starting: let it through.
                if (px > prevX[k] * 4f && px > echo * 3f) gain = 1f
                prevX[k] = px
                // Open instantly, close smoothly so nothing sounds watery.
                g[k] = if (gain > g[k]) gain else g[k] * 0.6f + gain * 0.4f
            }
            // Smooth across neighbouring bands.
            for (k in 0 until bins) {
                val a = g[maxOf(0, k - 1)]; val b = g[k]; val c = g[minOf(bins - 1, k + 1)]
                gs[k] = 0.25f * a + 0.5f * b + 0.25f * c
            }
            for (k in 0 until bins) {
                xr[k] *= gs[k]; xi[k] *= gs[k]
                if (k in 1 until n / 2) { xr[n - k] = xr[k]; xi[n - k] = -xi[k] }
            }
        } else {
            for (k in 0 until bins) { g[k] = 1f; prevX[k] = xr[k] * xr[k] + xi[k] * xi[k] }
        }
        // Inverse FFT by conjugation.
        for (i in 0 until n) xi[i] = -xi[i]
        fft(xr, xi)
        val inv = 1f / n
        for (i in 0 until n) olBuf[i] += xr[i] * inv * win[i]
        System.arraycopy(olBuf, 0, outQ, 0, hop)
        System.arraycopy(olBuf, hop, olBuf, 0, hop)
        java.util.Arrays.fill(olBuf, hop, n, 0f)
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < size) {
                var cr = 1f; var ci = 0f
                for (k in 0 until half) {
                    val a = i + k; val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
