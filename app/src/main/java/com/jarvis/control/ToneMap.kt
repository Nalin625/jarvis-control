package com.jarvis.control

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
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

    /** Edge sharpening (unsharp mask, 3x3) plus light denoising in the shadows, where noise shows most. */
    fun refine(px: IntArray, w: Int, h: Int, sharpen: Double = 0.5): IntArray {
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                val xx = (x + dx).coerceIn(0, w - 1)
                val c = px[yy * w + xx]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            val br = sr / 9.0; val bg = sg / 9.0; val bb = sb / 9.0
            val c = px[y * w + x]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            // shadows get up to half their detail replaced by the blur (denoise); lights keep full sharpness
            val shadow = ((0.25 - lum) / 0.25).coerceIn(0.0, 1.0) * 0.5
            val fr = r + sharpen * (r - br)
            val fg = g + sharpen * (g - bg)
            val fb = b + sharpen * (b - bb)
            val rr = (fr * (1 - shadow) + br * shadow).toInt().coerceIn(0, 255)
            val gg = (fg * (1 - shadow) + bg * shadow).toInt().coerceIn(0, 255)
            val bq = (fb * (1 - shadow) + bb * shadow).toInt().coerceIn(0, 255)
            out[y * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bq
        }
        return out
    }

    /** Brightness of each pixel, 0..255. */
    fun gray(px: IntArray): IntArray = IntArray(px.size) {
        val c = px[it]
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    }

    private fun pack(r: Double, g: Double, b: Double): Int {
        val rr = (r * 255).toInt().coerceIn(0, 255)
        val gg = (g * 255).toInt().coerceIn(0, 255)
        val bb = (b * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    /** Night look: lifts the dark detail of the sky and trees, and rolls off the brightest light (the moon) so it keeps its shape. */
    fun nightLook(px: IntArray): IntArray = IntArray(px.size) { i ->
        val c = px[i]
        fun curve(v8: Int): Double {
            val lifted = (v8 / 255.0).pow(0.8)
            return if (lifted > 0.92) 0.92 + (lifted - 0.92) * 0.4 else lifted
        }
        pack(curve((c shr 16) and 0xFF), curve((c shr 8) and 0xFF), curve(c and 0xFF))
    }

    /** Day look: richer colour (sky blue, grass green, flowers) with a little more saturation. Highlights are left alone so bright sky keeps its clouds. */
    fun dayLook(px: IntArray): IntArray = IntArray(px.size) { i ->
        val c = px[i]
        val r = ((c shr 16) and 0xFF) / 255.0
        val g = ((c shr 8) and 0xFF) / 255.0
        val b = (c and 0xFF) / 255.0
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        val sat = 1.2
        pack(lum + (r - lum) * sat, lum + (g - lum) * sat, lum + (b - lum) * sat)
    }
}
