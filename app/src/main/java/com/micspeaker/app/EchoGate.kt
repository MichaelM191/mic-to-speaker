package com.micspeaker.app

import kotlin.math.abs
import kotlin.math.exp

/**
 * Echo-tail cutter.
 *
 * When the phone's speaker plays into its own mic, each sound comes back again a little
 * quieter, over and over: that's the trailing echo. Real sound (your voice, an instrument)
 * is always louder than those repeats. This follows how loud things have been lately and
 * quickly fades out only what sits well below that (more than 20 dB down), so the repeats
 * die instead of circling round. Anything loud enough to be real passes untouched.
 */
class EchoGate(rate: Int) {
    private val attack = 1f - exp(-1.0 / (0.001 * rate)).toFloat()   // level rises in ~1 ms
    private val release = exp(-1.0 / (0.06 * rate)).toFloat()        // level falls in ~60 ms
    private val holdDecay = exp(-1.0 / (1.2 * rate)).toFloat()       // "recent loudness" fades over ~1 s
    private val gainUp = 1f - exp(-1.0 / (0.002 * rate)).toFloat()   // reopen in ~2 ms (no clipped starts)
    private val gainDown = 1f - exp(-1.0 / (0.035 * rate)).toFloat() // close in ~35 ms (no clicks)

    private var env = 0f
    private var recent = 0f
    private var gain = 1f

    fun process(x: Float): Float {
        val a = abs(x)
        env = if (a > env) env + (a - env) * attack else env * release
        recent = maxOf(env, recent * holdDecay)
        val threshold = maxOf(recent * 0.1f, 120f)
        val target = if (env >= threshold) 1f else {
            val r = env / threshold
            r * r // 3:1 downward expander below the threshold
        }
        gain += (target - gain) * (if (target > gain) gainUp else gainDown)
        return x * gain
    }
}
