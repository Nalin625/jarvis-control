"""Checks the phone's tuning and calibration code: the laptop's limits, the settings route, and the safety rules.

These read the source files as text. They cannot prove the screen looks right on a phone, that the numbers suit a
real hand, or that the app compiles. The JUnit tests (TuningTest.kt, CalibrationBoxTest.kt) check the arithmetic when
Gradle runs. The real-camera checks are in REAL_CAMERA_TEST.md and are not replaced by any of this.

Run from the repo root:  python3 -m unittest discover -s tools -p "test_*.py" -v
"""
import os
import re
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join("app", "src", "main", "java", "com", "jarvis", "control")
TESTS = os.path.join("app", "src", "test", "java", "com", "jarvis", "control")


def read(*parts):
    with open(os.path.join(REPO, *parts), encoding="utf-8") as f:
        return f.read()


TUNING = read(JAVA, "Tuning.kt")
CALIB = read(JAVA, "CalibrationBox.kt")
ACTIVITY = read(JAVA, "GestureActivity.kt")
TUNING_TEST = read(TESTS, "TuningTest.kt")
CALIB_TEST = read(TESTS, "CalibrationBoxTest.kt")

# The laptop's own numbers (jarvis_track_settings.py DEFAULTS and RANGES). test_phone_tuning_match.py on the
# laptop checks these against the laptop's file, so a change on one side fails the other side's tests.
LAPTOP = {
    "SENS_MIN": 0.5, "SENS_MAX": 2.0, "SENS_DEFAULT": 1.0,
    "DWELL_MIN": 0.4, "DWELL_MAX": 2.0, "DWELL_DEFAULT": 0.8,
    "REACH_LO": 0.1, "REACH_HI": 0.9,
}


def constants(src):
    return {name: float(value) for name, value in re.findall(r"const val (\w+) = ([0-9.]+)\b", src)}


class TuningNumbers(unittest.TestCase):

    def test_the_limits_and_defaults_are_the_laptops(self):
        consts = constants(TUNING)
        for name, value in LAPTOP.items():
            with self.subTest(name=name):
                self.assertIn(name, consts)
                self.assertEqual(consts[name], value)

    def test_the_steps_are_a_tenth(self):
        consts = constants(TUNING)
        self.assertEqual(consts["SENS_STEP"], 0.1)
        self.assertEqual(consts["DWELL_STEP"], 0.1)

    def test_the_default_reach_is_inside_the_picture_with_room(self):
        consts = constants(TUNING)
        self.assertTrue(0.0 <= consts["REACH_LO"] < consts["REACH_HI"] <= 1.0)
        self.assertGreaterEqual(consts["REACH_HI"] - consts["REACH_LO"], 0.2)   # the laptop's minimum width


class CalibrationRules(unittest.TestCase):

    def test_the_phone_asks_for_at_least_the_laptops_minimum_width(self):
        laptop_min = 0.2                                          # jarvis_track_settings.REACH_MIN_WIDTH
        self.assertGreaterEqual(constants(CALIB)["MIN_WIDTH"], laptop_min)

    def test_the_margin_is_the_one_the_tests_expect(self):
        self.assertEqual(constants(CALIB)["MARGIN"], 0.03)

    def test_calibration_mirrors_x_like_the_laptop_does(self):
        self.assertIn("fun fromCorners(points: List<Point>, mirror: Boolean = true)", CALIB)
        self.assertIn("mirror = true", ACTIVITY)

    def test_the_fingertip_is_landmark_eight(self):
        self.assertIn("hand[8]", ACTIVITY)

    def test_nothing_is_saved_unless_the_box_passed_the_checks(self):
        self.assertIn("val ok = calibOk ?: return", ACTIVITY)
        self.assertIn("is CalibrationBox.Result.Refused", ACTIVITY)


class SettingsRoute(unittest.TestCase):

    def test_the_screen_reads_and_changes_the_laptops_settings(self):
        self.assertIn('"GET", "/gesture/settings"', ACTIVITY)
        self.assertIn('"POST", "/gesture/settings"', ACTIVITY)

    def test_saved_is_only_shown_after_the_laptop_says_ok(self):
        ok_check = ACTIVITY.index('reply.optBoolean("ok", false)')
        saved = ACTIVITY.index('"Saved on the laptop."')
        self.assertLess(ok_check, saved)

    def test_a_refusal_shows_the_laptops_reasons(self):
        self.assertIn('reply.optJSONArray("errors")', ACTIVITY)

    def test_one_change_at_a_time(self):
        self.assertIn("if (tuneBusy) return", ACTIVITY)

    def test_the_lock_is_shown_but_not_changed_here(self):
        self.assertIn('optBoolean("locked")', ACTIVITY)
        self.assertNotIn('put("locked"', ACTIVITY)


class Screen(unittest.TestCase):

    def test_the_tune_card_is_on_the_screen(self):
        self.assertIn('card("TUNE THE POINTER")', ACTIVITY)

    def test_the_buttons_are_disabled_until_the_laptop_is_read(self):
        self.assertIn("laptopTuning != null && !tuneBusy", ACTIVITY)


class NewTestsExist(unittest.TestCase):

    def test_the_arithmetic_has_junit_tests(self):
        self.assertGreaterEqual(TUNING_TEST.count("@Test"), 8)
        self.assertGreaterEqual(CALIB_TEST.count("@Test"), 10)


if __name__ == "__main__":
    unittest.main()
