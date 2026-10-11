package com.jarvis.control

import com.jarvis.control.CalibrationBox.Capture
import com.jarvis.control.CalibrationBox.Point
import com.jarvis.control.CalibrationBox.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CalibrationBox. Pure Kotlin: no camera, phone or laptop is needed.
 * These are simulated checks of the arithmetic. They are not proof that a real hand calibrates well; see REAL_CAMERA_TEST.md.
 *
 * Not compiled or run in the sandbox that wrote this (no Kotlin compiler or Gradle dependency access there).
 * Run with:  ./gradlew testDebugUnitTest
 */
class CalibrationBoxTest {

    private val eps = 1e-6

    private fun p(x: Double, y: Double) = Point(x, y)

    private fun assertOk(r: Result, xLo: Double, xHi: Double, yLo: Double, yHi: Double) {
        assertTrue("expected a box but got $r", r is Result.Ok)
        val ok = r as Result.Ok
        assertEquals("x low", xLo, ok.xLo, eps)
        assertEquals("x high", xHi, ok.xHi, eps)
        assertEquals("y low", yLo, ok.yLo, eps)
        assertEquals("y high", yHi, ok.yHi, eps)
    }

    private fun reasonsOf(r: Result): List<String> {
        assertTrue("expected a refusal but got $r", r is Result.Refused)
        return (r as Result.Refused).reasons
    }

    // ----- the box -----

    @Test
    fun the_four_corners_make_a_box_with_a_margin() {
        val r = CalibrationBox.fromCorners(listOf(p(0.2, 0.2), p(0.8, 0.2), p(0.8, 0.7), p(0.2, 0.7)), mirror = false)
        assertOk(r, 0.17, 0.83, 0.17, 0.73)
    }

    @Test
    fun x_is_mirrored_before_the_box_like_the_laptop_does() {
        // Mirrored x of 0.1 is 0.9 and of 0.6 is 0.4, so the x box is 0.4..0.9 before the margin.
        val r = CalibrationBox.fromCorners(listOf(p(0.1, 0.3), p(0.6, 0.3), p(0.6, 0.6), p(0.1, 0.6)))
        assertOk(r, 0.37, 0.93, 0.27, 0.63)
    }

    @Test
    fun the_box_stays_inside_the_picture() {
        val r = CalibrationBox.fromCorners(listOf(p(0.0, 0.2), p(0.6, 0.2), p(0.6, 0.7), p(0.0, 0.7)), mirror = false)
        assertOk(r, 0.0, 0.63, 0.17, 0.73)
    }

    @Test
    fun the_numbers_are_rounded_to_four_places() {
        val r = CalibrationBox.fromCorners(listOf(p(0.123456, 0.2), p(0.7, 0.2), p(0.7, 0.7), p(0.123456, 0.7)), mirror = false)
        assertOk(r, 0.0935, 0.73, 0.17, 0.73)
    }

    @Test
    fun a_box_too_narrow_side_to_side_is_refused_with_the_reason() {
        // 0.4..0.5 with the margin is 0.16 wide, under the 0.3 the phone asks for. The up-and-down axis is fine.
        val r = CalibrationBox.fromCorners(listOf(p(0.4, 0.2), p(0.5, 0.2), p(0.5, 0.7), p(0.4, 0.7)), mirror = false)
        val reasons = reasonsOf(r)
        assertEquals(1, reasons.size)
        assertTrue(reasons[0], reasons[0].contains("left and right"))
        assertTrue(reasons[0], reasons[0].contains("16%"))
    }

    @Test
    fun a_box_too_short_up_and_down_is_refused_with_the_reason() {
        val r = CalibrationBox.fromCorners(listOf(p(0.2, 0.5), p(0.8, 0.5), p(0.8, 0.55), p(0.2, 0.55)), mirror = false)
        val reasons = reasonsOf(r)
        assertEquals(1, reasons.size)
        assertTrue(reasons[0], reasons[0].contains("up and down"))
    }

    @Test
    fun pointing_at_nearly_the_whole_picture_is_refused() {
        val r = CalibrationBox.fromCorners(listOf(p(0.0, 0.0), p(1.0, 0.0), p(1.0, 1.0), p(0.0, 1.0)), mirror = false)
        assertEquals(2, reasonsOf(r).size)
    }

    @Test
    fun three_corners_are_refused() {
        val r = CalibrationBox.fromCorners(listOf(p(0.2, 0.2), p(0.8, 0.2), p(0.8, 0.7)))
        assertEquals(1, reasonsOf(r).size)
    }

    @Test
    fun a_corner_that_is_not_a_number_is_refused() {
        val r = CalibrationBox.fromCorners(listOf(p(Double.NaN, 0.2), p(0.8, 0.2), p(0.8, 0.7), p(0.2, 0.7)))
        assertTrue(r is Result.Refused)
    }

    // ----- one corner from the fingertip samples -----

    @Test
    fun too_few_samples_do_not_make_a_corner() {
        val samples = List(3) { p(0.5, 0.5) }
        assertEquals(Capture.TooFew, CalibrationBox.capture(samples))
    }

    @Test
    fun still_samples_give_their_average() {
        val samples = List(10) { i -> p(0.5 + (if (i % 2 == 0) 0.005 else -0.005), 0.4) }
        val got = CalibrationBox.capture(samples)
        assertTrue("expected a corner but got $got", got is Capture.Got)
        val at = (got as Capture.Got).at
        assertEquals(0.5, at.x, eps)
        assertEquals(0.4, at.y, eps)
    }

    @Test
    fun a_wandering_fingertip_is_not_captured() {
        val samples = List(10) { i -> p(if (i % 2 == 0) 0.3 else 0.7, 0.5) }
        assertEquals(Capture.Moving, CalibrationBox.capture(samples))
    }
}
