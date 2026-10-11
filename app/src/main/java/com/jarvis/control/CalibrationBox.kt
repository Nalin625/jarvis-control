package com.jarvis.control

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Calibration math, with no Android in it, so it can be tested on the computer.
 *
 * The user points the index finger at the corners of the space they can reach comfortably. Each corner is the
 * average of a short run of still fingertip frames. The four corners become the reach box the laptop uses.
 * The laptop mirrors x before it applies the box (jarvis_hands.py), so this does the same. A margin is added so
 * the edges of the box are reachable. The result is checked here before it is sent, so a poor calibration is
 * refused with a reason and nothing is saved.
 *
 * Coordinates are the camera image's, 0..1, as MediaPipe gives the index fingertip (landmark 8).
 * The thresholds below are starting values, not measured ones: the real-camera test is what should set them.
 */
object CalibrationBox {
    const val CORNERS = 4
    const val MARGIN = 0.03        // added on each side of the pointed area
    const val MIN_WIDTH = 0.3      // each axis must cover at least this much of the picture
    const val MAX_WIDTH = 0.95     // and not nearly all of it: a finger at the picture's edge leaves the view
    const val MIN_SAMPLES = 8      // still fingertip frames needed for one corner
    const val MAX_JITTER = 0.04    // the fingertip may wander this far from its average and still count as still
    private const val EPS = 1e-9

    data class Point(val x: Double, val y: Double)

    sealed class Capture {
        data class Got(val at: Point) : Capture()
        object TooFew : Capture()
        object Moving : Capture()
    }

    sealed class Result {
        data class Ok(val xLo: Double, val xHi: Double, val yLo: Double, val yHi: Double) : Result()
        data class Refused(val reasons: List<String>) : Result()
    }

    /** One corner, from the fingertip samples taken while the user held still. */
    fun capture(samples: List<Point>): Capture {
        if (samples.size < MIN_SAMPLES) return Capture.TooFew
        val mx = samples.map { it.x }.average()
        val my = samples.map { it.y }.average()
        val wobble = samples.maxOf { max(abs(it.x - mx), abs(it.y - my)) }
        if (wobble > MAX_JITTER) return Capture.Moving
        return Capture.Got(Point(mx, my))
    }

    /** The reach box from the four corners. [mirror] matches the laptop's mirrored camera image (the default). */
    fun fromCorners(points: List<Point>, mirror: Boolean = true): Result {
        if (points.size != CORNERS) {
            return Result.Refused(listOf("Point at all four corners, then calibrate again."))
        }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return Result.Refused(listOf("One corner was not read properly. Calibrate again."))
        }
        val xs = points.map { if (mirror) 1.0 - it.x else it.x }
        val ys = points.map { it.y }
        val xLo = max(0.0, xs.reduce { a, b -> min(a, b) } - MARGIN)
        val xHi = min(1.0, xs.reduce { a, b -> max(a, b) } + MARGIN)
        val yLo = max(0.0, ys.reduce { a, b -> min(a, b) } - MARGIN)
        val yHi = min(1.0, ys.reduce { a, b -> max(a, b) } + MARGIN)

        val reasons = ArrayList<String>()
        val wx = xHi - xLo
        if (wx < MIN_WIDTH - EPS) {
            reasons += "Point further to the left and right. Your pointing covers only ${Tuning.percent(wx)} of the " +
                "picture side to side; it needs at least ${Tuning.percent(MIN_WIDTH)}."
        } else if (wx > MAX_WIDTH + EPS) {
            reasons += "Point in a smaller space. Your pointing covers ${Tuning.percent(wx)} of the picture side to " +
                "side, nearly all of it. Keep your hand clear of the picture's edges."
        }
        val wy = yHi - yLo
        if (wy < MIN_WIDTH - EPS) {
            reasons += "Point further up and down. Your pointing covers only ${Tuning.percent(wy)} of the picture " +
                "up and down; it needs at least ${Tuning.percent(MIN_WIDTH)}."
        } else if (wy > MAX_WIDTH + EPS) {
            reasons += "Point in a smaller space. Your pointing covers ${Tuning.percent(wy)} of the picture up and " +
                "down, nearly all of it. Keep your hand clear of the picture's edges."
        }
        if (reasons.isNotEmpty()) return Result.Refused(reasons)
        return Result.Ok(round4(xLo), round4(xHi), round4(yLo), round4(yHi))
    }

    /** Four places, the same precision the laptop stores. */
    private fun round4(v: Double): Double = (v * 10000.0).roundToInt() / 10000.0
}
