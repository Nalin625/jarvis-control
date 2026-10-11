package com.jarvis.control

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Tuning: the steps, the limits and the text shown on the screen. Pure Kotlin.
 * Simulated checks of the arithmetic only; the laptop's own limits are checked by the laptop's tests.
 *
 * Not compiled or run in the sandbox that wrote this (no Kotlin compiler or Gradle dependency access there).
 * Run with:  ./gradlew testDebugUnitTest
 */
class TuningTest {

    private val eps = 1e-9

    @Test
    fun sensitivity_stops_at_its_limits() {
        assertEquals(Tuning.SENS_MAX, Tuning.sensitivityStep(Tuning.SENS_MAX, up = true), eps)
        assertEquals(Tuning.SENS_MIN, Tuning.sensitivityStep(Tuning.SENS_MIN, up = false), eps)
    }

    @Test
    fun ten_steps_up_from_the_lowest_give_exactly_one_and_a_half() {
        var v = Tuning.SENS_MIN
        repeat(10) { v = Tuning.sensitivityStep(v, up = true) }
        assertEquals(1.5, v, eps)
    }

    @Test
    fun a_step_back_undoes_a_step_forward() {
        val up = Tuning.sensitivityStep(Tuning.SENS_DEFAULT, up = true)
        assertEquals(Tuning.SENS_DEFAULT, Tuning.sensitivityStep(up, up = false), eps)
    }

    @Test
    fun dwell_stops_at_its_limits() {
        assertEquals(Tuning.DWELL_MAX, Tuning.dwellStep(Tuning.DWELL_MAX, up = true), eps)
        assertEquals(Tuning.DWELL_MIN, Tuning.dwellStep(Tuning.DWELL_MIN, up = false), eps)
    }

    @Test
    fun dwell_steps_from_the_default_are_tenths() {
        assertEquals(0.9, Tuning.dwellStep(Tuning.DWELL_DEFAULT, up = true), eps)
        assertEquals(0.7, Tuning.dwellStep(Tuning.DWELL_DEFAULT, up = false), eps)
    }

    @Test
    fun round2_removes_binary_noise() {
        assertEquals(0.3, Tuning.round2(0.1 + 0.2), eps)
    }

    @Test
    fun percent_is_a_whole_number_with_a_percent_sign() {
        assertEquals("37%", Tuning.percent(0.37))
        assertEquals("100%", Tuning.percent(1.0))
    }

    @Test
    fun one_decimal_always_uses_a_dot() {
        assertEquals("0.8", Tuning.oneDecimal(0.8))
        assertEquals("1.0", Tuning.oneDecimal(1.0))
    }

    @Test
    fun the_defaults_are_the_laptop_defaults() {
        assertEquals(1.0, Tuning.SENS_DEFAULT, eps)
        assertEquals(0.8, Tuning.DWELL_DEFAULT, eps)
        assertEquals(0.1, Tuning.REACH_LO, eps)
        assertEquals(0.9, Tuning.REACH_HI, eps)
    }
}
