package com.jarvis.control

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The pointer tuning the phone can change, with no Android in it so it can be tested on the computer.
 *
 * The numbers are the laptop's own (jarvis_track_settings.py). The laptop checks every change and keeps the saved
 * values, so the screen shows what the laptop reports, not these copies. tools/test_tuning_ui.py checks that these
 * numbers match the laptop's file; the laptop's test_phone_tuning_match.py checks the same from its side.
 */
object Tuning {
    const val SENS_MIN = 0.5          // steadier and slower
    const val SENS_MAX = 2.0          // quicker and less steady
    const val SENS_DEFAULT = 1.0
    const val SENS_STEP = 0.1

    const val DWELL_MIN = 0.4         // seconds held still over a card to pick it
    const val DWELL_MAX = 2.0
    const val DWELL_DEFAULT = 0.8
    const val DWELL_STEP = 0.1

    const val REACH_LO = 0.1          // the default reach: the middle 80% of the camera picture
    const val REACH_HI = 0.9

    /** Two decimal places. Repeated steps of 0.1 drift in binary floating point (0.1 + 0.2 is not 0.3), so each step is rounded. */
    fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    fun sensitivityStep(v: Double, up: Boolean): Double =
        round2(clamp(v + (if (up) SENS_STEP else -SENS_STEP), SENS_MIN, SENS_MAX))

    fun dwellStep(v: Double, up: Boolean): Double =
        round2(clamp(v + (if (up) DWELL_STEP else -DWELL_STEP), DWELL_MIN, DWELL_MAX))

    /** 0.37 -> "37%". Always a full stop, never a locale's comma. */
    fun percent(v: Double): String = "${(v * 100.0).roundToInt()}%"

    /** One decimal place for the screen, with a dot whatever the phone's language. */
    fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)
}
