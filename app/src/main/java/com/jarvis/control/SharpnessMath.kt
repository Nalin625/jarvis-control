package com.jarvis.control

/** Pure maths for the zoom camera: how sharp a frame is, and which frame of a burst is the sharpest. */
object SharpnessMath {
    /** Variance of a 3x3 Laplacian over a grayscale image (row-major, width * height values). Higher = sharper. */
    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3 || gray.size < width * height) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Index of the highest score, or -1 when the list is empty. */
    fun bestIndex(scores: List<Double>): Int {
        if (scores.isEmpty()) return -1
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[best]) best = i
        return best
    }
}
