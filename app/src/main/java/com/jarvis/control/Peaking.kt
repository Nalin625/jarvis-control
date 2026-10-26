package com.jarvis.control

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** Focus peaking: marks the sharp edges in the live picture, so you can see what is in focus. Pure maths plus a small analyzer. */
object Peaking {
    private const val EDGE_RED = 0xFFFF2020.toInt()

    /** Copies a luminance plane into a flat array, turned clockwise by [deg] (0, 90, 180 or 270) so it is upright. */
    fun rotate(src: IntArray, w: Int, h: Int, deg: Int): Triple<IntArray, Int, Int> {
        val turns = ((deg % 360) + 360) % 360 / 90
        if (turns == 0) return Triple(src, w, h)
        val dw = if (turns % 2 == 1) h else w
        val dh = if (turns % 2 == 1) w else h
        val dst = IntArray(dw * dh)
        for (y in 0 until h) for (x in 0 until w) {
            val v = src[y * w + x]
            val (dx, dy) = when (turns) {
                1 -> Pair(h - 1 - y, x)
                2 -> Pair(w - 1 - x, h - 1 - y)
                else -> Pair(y, w - 1 - x)
            }
            dst[dy * dw + dx] = v
        }
        return Triple(dst, dw, dh)
    }

    /** Marks pixels with a strong local edge (sum of horizontal and vertical brightness change) in red; the rest is clear. */
    fun edges(gray: IntArray, w: Int, h: Int, threshold: Int = 40): IntArray {
        val out = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = gray[i + 1] - gray[i - 1]
            val gy = gray[i + w] - gray[i - w]
            if (abs(gx) + abs(gy) > threshold) out[i] = EDGE_RED
        }
        return out
    }

    /** Analyzer that reads the picture's brightness, rotates it upright and hands back the edge overlay. */
    class EdgeAnalyzer(private val onOverlay: (IntArray, Int, Int) -> Unit) : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            try {
                val plane = image.planes[0]
                val w = image.width
                val h = image.height
                val buf = plane.buffer
                val rs = plane.rowStride
                val ps = plane.pixelStride
                val gray = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    gray[y * w + x] = buf.get(y * rs + x * ps).toInt() and 0xFF
                }
                val (upright, uw, uh) = rotate(gray, w, h, image.imageInfo.rotationDegrees)
                onOverlay(edges(upright, uw, uh), uw, uh)
            } finally {
                image.close()
            }
        }
    }

    /** Analysis size for the peaking overlay (4:3, same as the preview). */
    val ANALYSIS_SIZE = Size(640, 480)
}
