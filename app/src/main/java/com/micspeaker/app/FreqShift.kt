package com.micspeaker.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moves every pitch up by a tiny fixed amount (2 Hz). You can't really hear it, but it stops the
 * speaker sound from lining up with itself each time round the loop, so echoes and howls
 * can't build up, and it helps the echo canceller tell the echo apart from you.
 */
class FreqShift(rate: Int, hz: Double = 2.0) {
    // Two all-pass chains whose outputs are 90° apart across the audio band (Hilbert pair).
    private val ca = floatArrayOf(0.6923878f, 0.9360654f, 0.9882295f, 0.9987488f).map { it * it }.toFloatArray()
    private val cb = floatArrayOf(0.4021921f, 0.8561711f, 0.9722910f, 0.9952885f).map { it * it }.toFloatArray()
    private val ax1 = FloatArray(4); private val ax2 = FloatArray(4); private val ay1 = FloatArray(4); private val ay2 = FloatArray(4)
    private val bx1 = FloatArray(4); private val bx2 = FloatArray(4); private val by1 = FloatArray(4); private val by2 = FloatArray(4)
    private var bDelay = 0f
    private val dPhi = 2 * PI * hz / rate
    private var phi = 0.0
    private var c = 1f; private var s = 0f
    private var count = 0

    private fun chain(x: Float, k: FloatArray, x1: FloatArray, x2: FloatArray, y1: FloatArray, y2: FloatArray): Float {
        var v = x
        for (i in 0 until 4) {
            val y = k[i] * (v + y2[i]) - x2[i]
            x2[i] = x1[i]; x1[i] = v
            y2[i] = y1[i]; y1[i] = y
            v = y
        }
        return v
    }

    fun process(x: Float): Float {
        val i = chain(x, ca, ax1, ax2, ay1, ay2)
        val qNow = chain(x, cb, bx1, bx2, by1, by2)
        val q = bDelay; bDelay = qNow
        phi += dPhi
        if (phi > 2 * PI) phi -= 2 * PI
        if (count++ and 15 == 0) { c = cos(phi).toFloat(); s = sin(phi).toFloat() } // 2 Hz moves slowly
        return i * c - q * s
    }
}
