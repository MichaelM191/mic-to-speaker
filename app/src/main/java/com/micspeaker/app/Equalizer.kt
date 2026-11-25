package com.micspeaker.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 5-band equaliser: bass shelf, three bells, treble shelf. Gains in dB (-12..+12). */
class Equalizer(private val rate: Int) {
    companion object {
        val BANDS = listOf("Bass" to 80f, "Low" to 250f, "Mid" to 1000f, "Presence" to 3000f, "Treble" to 8000f)
        val PRESETS = listOf(
            "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
            "Voice" to listOf(-4f, -1f, 2f, 4f, 1f),
            "Music" to listOf(4f, 1f, -1f, 2f, 4f),
            "Bass" to listOf(8f, 4f, 0f, 0f, 0f),
        )
    }

    private val b0 = FloatArray(5); private val b1 = FloatArray(5); private val b2 = FloatArray(5)
    private val a1 = FloatArray(5); private val a2 = FloatArray(5)
    private val z1 = FloatArray(5); private val z2 = FloatArray(5)
    private val active = BooleanArray(5)
    private var last: FloatArray? = null

    /** Call once per chunk; recomputes filters only when the gains changed. */
    fun set(gains: FloatArray) {
        val prev = last
        if (prev != null && prev.contentEquals(gains)) return
        last = gains.copyOf()
        for (i in 0 until 5) {
            val g = gains[i]
            active[i] = g > 0.05f || g < -0.05f
            if (!active[i]) { z1[i] = 0f; z2[i] = 0f; continue }
            val f = BANDS[i].second.coerceAtMost(rate * 0.45f)
            val a = 10.0.pow(g / 40.0)
            val w = 2 * PI * f / rate
            val cw = cos(w); val sw = sin(w)
            val q = 0.9
            val alpha = sw / (2 * q)
            val nb0: Double; val nb1: Double; val nb2: Double; val na0: Double; val na1: Double; val na2: Double
            when (i) {
                0, 4 -> {
                    val sa = 2 * sqrt(a) * sw / (2 * 0.707)
                    if (i == 0) { // low shelf
                        nb0 = a * ((a + 1) - (a - 1) * cw + sa); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - sa)
                        na0 = (a + 1) + (a - 1) * cw + sa; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - sa
                    } else { // high shelf
                        nb0 = a * ((a + 1) + (a - 1) * cw + sa); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - sa)
                        na0 = (a + 1) - (a - 1) * cw + sa; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - sa
                    }
                }
                else -> { // bell
                    nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a
                    na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a
                }
            }
            b0[i] = (nb0 / na0).toFloat(); b1[i] = (nb1 / na0).toFloat(); b2[i] = (nb2 / na0).toFloat()
            a1[i] = (na1 / na0).toFloat(); a2[i] = (na2 / na0).toFloat()
        }
    }

    fun process(x: Float): Float {
        var y = x
        for (i in 0 until 5) {
            if (!active[i]) continue
            val out = b0[i] * y + z1[i]
            z1[i] = b1[i] * y - a1[i] * out + z2[i]
            z2[i] = b2[i] * y - a2[i] * out
            y = out
        }
        return y
    }
}
