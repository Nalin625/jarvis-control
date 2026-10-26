package com.jarvis.control

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Burst stacking (the idea behind HDR+): line every frame up with the sharpest one, then average them.
 *  Alignment allows a whole-pixel shift plus a small rotation about the centre, which covers handheld drift.
 *  Averaging several aligned frames cuts noise. Pure maths on ARGB pixel arrays, so it can be tested alone. */
object FrameStack {
    /** Rotations to try, in degrees. */
    private val ANGLES = doubleArrayOf(-1.0, 0.0, 1.0)

    data class Transform(val dx: Int, val dy: Int, val deg: Double)

    private fun gray(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private fun grayArray(px: IntArray): IntArray = IntArray(px.size) { gray(px[it]) }

    /** Where a reference pixel (x, y) sits in the frame under transform t. */
    private fun sourceX(x: Int, y: Int, w: Int, h: Int, t: Transform): Int {
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val rad = Math.toRadians(t.deg)
        return ((x - cx) * cos(rad) - (y - cy) * sin(rad) + cx + t.dx).roundToInt()
    }

    private fun sourceY(x: Int, y: Int, w: Int, h: Int, t: Transform): Int {
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val rad = Math.toRadians(t.deg)
        return ((x - cx) * sin(rad) + (y - cy) * cos(rad) + cy + t.dy).roundToInt()
    }

    /** Best transform of [frameG] onto [refG]: a whole-pixel shift within +-[search] and a rotation from [ANGLES].
     *  Compares every [step]-th pixel to keep it fast on a phone. Ties keep the smaller move. */
    fun findTransform(refG: IntArray, frameG: IntArray, w: Int, h: Int, search: Int, step: Int = 4): Transform {
        var best = Transform(0, 0, 0.0)
        var bestScore = Long.MAX_VALUE
        var bestMove = Int.MAX_VALUE
        for (deg in ANGLES) for (dy in -search..search) for (dx in -search..search) {
            val t = Transform(dx, dy, deg)
            var err = 0L
            var n = 0L
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val ix = sourceX(x, y, w, h, t)
                    val iy = sourceY(x, y, w, h, t)
                    if (ix in 0 until w && iy in 0 until h) {
                        err += abs(refG[y * w + x] - frameG[iy * w + ix])
                        n++
                    }
                    x += step
                }
                y += step
            }
            if (n == 0L) continue
            val score = err * 1000L / n
            val move = abs(dx) + abs(dy) + (abs(deg) * 10).roundToInt()
            if (score < bestScore || (score == bestScore && move < bestMove)) {
                bestScore = score
                bestMove = move
                best = t
            }
        }
        return best
    }

    /** Aligns every frame to frames[ref] and averages the colours. All frames must be w x h. */
    fun alignAndAverage(frames: List<IntArray>, w: Int, h: Int, ref: Int, search: Int = 6): IntArray {
        val refG = grayArray(frames[ref])
        val n = w * h
        val sumR = LongArray(n)
        val sumG = LongArray(n)
        val sumB = LongArray(n)
        val cnt = IntArray(n)
        for (k in frames.indices) {
            val f = frames[k]
            val t = if (k == ref) Transform(0, 0, 0.0) else findTransform(refG, grayArray(f), w, h, search)
            for (y in 0 until h) for (x in 0 until w) {
                val sx = sourceX(x, y, w, h, t)
                val sy = sourceY(x, y, w, h, t)
                if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue
                val c = f[sy * w + sx]
                val i = y * w + x
                sumR[i] = sumR[i] + ((c shr 16) and 0xFF).toLong()
                sumG[i] = sumG[i] + ((c shr 8) and 0xFF).toLong()
                sumB[i] = sumB[i] + (c and 0xFF).toLong()
                cnt[i]++
            }
        }
        return IntArray(n) { i ->
            val c = cnt[i]
            if (c == 0) frames[ref][i]
            else (0xFF shl 24) or ((sumR[i] / c).toInt() shl 16) or ((sumG[i] / c).toInt() shl 8) or (sumB[i] / c).toInt()
        }
    }
}
