package com.micspeaker.app

import kotlin.math.abs
import kotlin.math.exp

/**
 * Gentle echo-tail softener.
 *
 * Trailing repeats from the speaker sit far below the sound that caused them. This only turns
 * down what is more than ~30 dB under recent loudness, never by more than ~16 dB, and opens and
 * closes slowly so speech between syllables is never chopped.
 */
class EchoGate(rate: Int) {
    private val attack = 1f - exp(-1.0 / (0.002 * rate)).toFloat()   // level rises in ~2 ms
    private val release = exp(-1.0 / (0.15 * rate)).toFloat()        // level falls in ~150 ms
    private val holdDecay = exp(-1.0 / (1.5 * rate)).toFloat()       // "recent loudness" fades over ~1.5 s
    private val gainUp = 1f - exp(-1.0 / (0.003 * rate)).toFloat()   // reopen in ~3 ms
    private val gainDown = 1f - exp(-1.0 / (0.25 * rate)).toFloat()  // close slowly, ~250 ms

    private var env = 0f
    private var recent = 0f
    private var gain = 1f

    fun process(x: Float): Float {
        val a = abs(x)
        env = if (a > env) env + (a - env) * attack else env * release
        recent = maxOf(env, recent * holdDecay)
        val threshold = maxOf(recent * 0.03f, 60f)
        val target = if (env >= threshold) 1f else maxOf(env / threshold, 0.16f) // 2:1, floor -16 dB
        gain += (target - gain) * (if (target > gain) gainUp else gainDown)
        return x * gain
    }
}
