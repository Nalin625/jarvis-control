package com.jarvis.control

import kotlin.math.abs

/** Burst stacking (the idea behind HDR+): line every frame up with the sharpest one, then average them.
 *  Averaging several aligned frames cuts noise. Pure maths on ARGB pixel arrays, so it can be tested alone. */
object FrameStack {
    private fun gray(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private fun grayArray(px: IntArray): IntArray = IntArray(px.size) { gray(px[it]) }

    /** Best whole-pixel shift (dx, dy) of [frame] to match [ref], searched within +-[search].
     *  Compares every [step]-th pixel to keep it fast on a phone. Ties keep the smaller shift. */
    fun findShift(refG: IntArray, frameG: IntArray, w: Int, h: Int, search: Int, step: Int = 4): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestErr = Long.MAX_VALUE
        for (dy in -search..search) for (dx in -search..search) {
            var err = 0L
            var n = 0L
            var y = maxOf(0, -dy)
            val yEnd = minOf(h, h - dy)
            while (y < yEnd) {
                var x = maxOf(0, -dx)
                val xEnd = minOf(w, w - dx)
                while (x < xEnd) {
                    err += abs(refG[y * w + x] - frameG[(y + dy) * w + (x + dx)])
                    n++
                    x += step
                }
                y += step
            }
            if (n == 0L) continue
            val score = err * 1000L / n
            if (score < bestErr || (score == bestErr && abs(dx) + abs(dy) < abs(bestDx) + abs(bestDy))) {
                bestErr = score
                bestDx = dx
                bestDy = dy
            }
        }
        return Pair(bestDx, bestDy)
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
            val (dx, dy) = if (k == ref) Pair(0, 0) else findShift(refG, grayArray(f), w, h, search)
            for (y in 0 until h) {
                val sy = y + dy
                if (sy < 0 || sy >= h) continue
                for (x in 0 until w) {
                    val sx = x + dx
                    if (sx < 0 || sx >= w) continue
                    val c = f[sy * w + sx]
                    val i = y * w + x
                    sumR[i] += (c shr 16) and 0xFF
                    sumG[i] += (c shr 8) and 0xFF
                    sumB[i] += c and 0xFF
                    cnt[i]++
                }
            }
        }
        return IntArray(n) { i ->
            val c = cnt[i]
            if (c == 0) frames[ref][i]
            else (0xFF shl 24) or ((sumR[i] / c).toInt() shl 16) or ((sumG[i] / c).toInt() shl 8) or (sumB[i] / c).toInt()
        }
    }
}
