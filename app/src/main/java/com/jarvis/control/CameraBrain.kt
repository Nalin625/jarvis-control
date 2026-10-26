package com.jarvis.control

import kotlin.math.roundToInt

/** The camera's own rules: how far to move the exposure, and the ISO and shutter a scene suggests.
 *  Pure maths, no Android code, so it can be tested alone. */
object CameraBrain {
    /** Average brightness (0..255) the pictures should land near. */
    const val TARGET_LUMA = 118.0

    /** Mean brightness of a grayscale image, 0..255. */
    fun meanLuma(gray: IntArray): Double {
        if (gray.isEmpty()) return 0.0
        var total = 0L
        for (v in gray) total += v
        return total.toDouble() / gray.size
    }

    /** Exposure steps to move by (-2..2) so the next picture lands near the target brightness. */
    fun evStep(meanLuma: Double): Int {
        val err = TARGET_LUMA - meanLuma
        return when {
            err > 40 -> 2
            err > 15 -> 1
            err < -40 -> -2
            err < -15 -> -1
            else -> 0
        }
    }

    /** ISO a scene suggests: brighter scene, lower ISO (less grain). */
    fun isoFor(meanLuma: Double): Int = when {
        meanLuma > 150 -> 100
        meanLuma > 110 -> 200
        meanLuma > 80 -> 400
        else -> 800
    }

    /** Share of pixels that are nearly pure white (blown-out highlights), 0..1. */
    fun clippedFraction(gray: IntArray): Double {
        if (gray.isEmpty()) return 0.0
        var n = 0
        for (v in gray) if (v >= 250) n++
        return n.toDouble() / gray.size
    }

    /** Share of pixels in deep shadow, 0..1. */
    fun shadowFraction(gray: IntArray): Double {
        if (gray.isEmpty()) return 0.0
        var n = 0
        for (v in gray) if (v <= 20) n++
        return n.toDouble() / gray.size
    }

    /** What kind of scene this is, from brightness only (no object recognition): NIGHT, BACKLIT, BRIGHT or NORMAL. */
    fun classify(gray: IntArray): String {
        val mean = meanLuma(gray)
        val clipped = clippedFraction(gray)
        val shadow = shadowFraction(gray)
        return when {
            mean < 60 -> "NIGHT"
            clipped > 0.02 && shadow > 0.25 -> "BACKLIT"
            mean > 170 -> "BRIGHT"
            else -> "NORMAL"
        }
    }

    /** The brightness each scene should land near. Night stays darker so it keeps its mood; backlit and bright are pulled down. */
    fun targetFor(scene: String): Double = when (scene) {
        "NIGHT" -> 90.0
        "BACKLIT" -> 110.0
        "BRIGHT" -> 130.0
        else -> TARGET_LUMA
    }

    /** Gentle contrast curve (smoothstep blended 25%): deepens the mids a little without crushing the shadows. */
    fun toneLut(): IntArray = IntArray(256) { v ->
        val t = v / 255.0
        val s = t * t * (3 - 2 * t)
        ((0.75 * t + 0.25 * s) * 255.0).roundToInt().coerceIn(0, 255)
    }

    /** Moves ISO toward the target brightness: too dark doubles it, too bright halves it, always inside lo..hi. */
    fun adjustIso(current: Int, meanLuma: Double, lo: Int, hi: Int, target: Double = TARGET_LUMA): Int {
        val err = target - meanLuma
        val next = when {
            err > 25 -> current * 2
            err < -25 -> current / 2
            else -> current
        }
        return next.coerceIn(lo, hi)
    }

    /** Shutter time in nanoseconds: bright scenes get fast shutters, dark scenes slower, never longer than maxNs. */
    fun shutterNsFor(meanLuma: Double, maxNs: Long): Long {
        val t = ((150.0 - meanLuma) / 150.0).coerceIn(0.0, 1.0)
        val fast = 2_000_000L        // 1/500 s
        val slow = 16_666_667L       // 1/60 s
        return (fast + (slow - fast) * t).toLong().coerceAtMost(maxNs)
    }
}
