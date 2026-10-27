package com.jarvis.control

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** Live-picture helpers: brightness of each frame, focus peaking edges. Pure maths plus a small analyzer. */
object Peaking {
    private const val EDGE_RED = 0xFFFF2020.toInt()

    /** Copies a luminance plane, turned clockwise by [deg] (0, 90, 180 or 270) so it is upright. */
    fun rotate(src: IntArray, w: Int, h: Int, deg: Int): Triple<IntArray, Int, Int> {
        val turns = ((deg % 360) + 360) % 360 / 90
        if (turns == 0) return Triple(src, w, h)
        val dw = if (turns % 2 == 1) h else w
        val dh = if (turns % 2 == 1) w else h
        val dst = IntArray(dw * dh)
        for (y in 0 until h) for (x in 0 until w) {
            val v = src[y * w + x]
            val dx: Int
            val dy: Int
            when (turns) {
                1 -> { dx = h - 1 - y; dy = x }
                2 -> { dx = w - 1 - x; dy = h - 1 - y }
                else -> { dx = y; dy = w - 1 - x }
            }
            dst[dy * dw + dx] = v
        }
        return Triple(dst, dw, dh)
    }

    /** Marks pixels with a strong local edge in red; the rest is clear. */
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

    /** Analyzer for each live frame: hands over the upright brightness of the picture. */
    class LiveAnalyzer(private val onFrame: (IntArray, Int, Int) -> Unit) : ImageAnalysis.Analyzer {
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
                onFrame(upright, uw, uh)
            } finally {
                image.close()
            }
        }
    }

    /** Analysis size for the live reading (4:3, same as the preview). */
    val ANALYSIS_SIZE = Size(640, 480)
}
