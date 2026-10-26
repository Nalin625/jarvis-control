package com.jarvis.control

import kotlin.math.exp
import kotlin.math.pow

/** Highlight recovery and shadow lift from one picture (exposure fusion, after Mertens).
 *  Makes a darker copy (keeps highlights) and a brighter copy (lifts shadows), then per pixel blends
 *  them, favouring whichever copy is well exposed there. Simplified: no contrast or saturation weights. */
object ToneMap {
    private const val SIGMA = 0.2

    /** How well exposed a value is, 0..1: peaks at mid-grey, falls off towards black and white. */
    private fun wellExposed(v: Double): Double {
        val d = v - 0.5
        return exp(-(d * d) / (2 * SIGMA * SIGMA)) + 1e-6
    }

    fun fuse(px: IntArray): IntArray {
        val out = IntArray(px.size)
        val dr = DoubleArray(3)
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255.0
            val g = ((c shr 8) and 0xFF) / 255.0
            val b = (c and 0xFF) / 255.0
            // three versions of the same pixel: darker, original, brighter (gamma lifts shadows)
            val versions = arrayOf(
                doubleArrayOf(r * 0.5, g * 0.5, b * 0.5),
                doubleArrayOf(r, g, b),
                doubleArrayOf(r.pow(0.6), g.pow(0.6), b.pow(0.6))
            )
            var wSum = 0.0
            dr[0] = 0.0; dr[1] = 0.0; dr[2] = 0.0
            for (v in versions) {
                val lum = 0.299 * v[0] + 0.587 * v[1] + 0.114 * v[2]
                val w = wellExposed(lum)
                wSum += w
                dr[0] += w * v[0]; dr[1] += w * v[1]; dr[2] += w * v[2]
            }
            val rr = (dr[0] / wSum * 255).toInt().coerceIn(0, 255)
            val gg = (dr[1] / wSum * 255).toInt().coerceIn(0, 255)
            val bb = (dr[2] / wSum * 255).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return out
    }
}
