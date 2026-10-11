"""Tests for tools/check_apk_model.py using synthetic APKs (no real model is needed).

Run from the repo root:  python3 -m unittest discover -s tools -p "test_*.py" -v
"""
import contextlib
import io
import os
import tempfile
import unittest
import zipfile
from unittest import mock

import check_apk_model as cam

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
WORKFLOW = os.path.join(REPO, ".github", "workflows", "build.yml")


def make_bundle(payload_size=16, names=("hand_detector.tflite", "hand_landmarks_detector.tflite")):
    """A .task-style zip bundle. Stored (not deflated) so payload_size is the real size."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for name in names:
            z.writestr(name, b"\0" * payload_size)
    return buf.getvalue()


def make_apk(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return path


class InspectApkTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def path(self, name="app-debug.apk"):
        return os.path.join(self.dir, name)

    def test_apk_with_usable_model_passes(self):
        apk = make_apk(self.path(), {
            "classes.dex": b"dex",
            cam.MODEL_ENTRY: make_bundle(),
        })
        result = cam.inspect_apk(apk, min_bytes=10)
        self.assertEqual(result.problems, [])
        self.assertEqual(result.warnings, [])
        self.assertIn("present", result.ok_line)

    def test_missing_model_fails(self):
        apk = make_apk(self.path(), {"classes.dex": b"dex"})
        result = cam.inspect_apk(apk, min_bytes=10)
        self.assertEqual(len(result.problems), 1)
        self.assertIn("missing", result.problems[0])
        self.assertIsNone(result.ok_line)

    def test_model_under_a_wrong_path_counts_as_missing(self):
        # Only assets/hand_landmarker.task is loaded by the app; a copy elsewhere is no use.
        apk = make_apk(self.path(), {"app/src/main/assets/hand_landmarker.task": make_bundle()})
        result = cam.inspect_apk(apk, min_bytes=10)
        self.assertTrue(result.problems)
        self.assertIn("missing", result.problems[0])

    def test_empty_or_truncated_model_fails(self):
        apk = make_apk(self.path(), {cam.MODEL_ENTRY: b"x" * 10})
        result = cam.inspect_apk(apk, min_bytes=1000)
        self.assertEqual(len(result.problems), 1)
        self.assertIn("expected at least 1000", result.problems[0])

    def test_html_error_page_as_model_fails(self):
        apk = make_apk(self.path(), {cam.MODEL_ENTRY: b"<html>403 Forbidden</html>" * 50})
        result = cam.inspect_apk(apk, min_bytes=10)
        self.assertEqual(len(result.problems), 1)
        self.assertIn("not a zip bundle", result.problems[0])

    def test_bundle_without_tflite_only_warns(self):
        apk = make_apk(self.path(), {cam.MODEL_ENTRY: make_bundle(names=("readme.txt",))})
        result = cam.inspect_apk(apk, min_bytes=10)
        self.assertEqual(result.problems, [])
        self.assertEqual(len(result.warnings), 1)
        self.assertIn(".tflite", result.warnings[0])
        self.assertIsNotNone(result.ok_line)

    def test_file_that_is_not_a_zip_fails(self):
        path = self.path()
        with open(path, "wb") as f:
            f.write(b"this is not an apk")
        result = cam.inspect_apk(path)
        self.assertEqual(len(result.problems), 1)
        self.assertIn("not a valid APK", result.problems[0])

    def test_missing_file_fails(self):
        result = cam.inspect_apk(self.path("does-not-exist.apk"))
        self.assertEqual(len(result.problems), 1)
        self.assertIn("file not found", result.problems[0])


class MainTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def run_main(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = cam.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_good_apk_with_default_threshold_exits_zero(self):
        # Default threshold is 1 MB, so the stored bundle payload must be larger than that.
        apk = make_apk(os.path.join(self.dir, "ok.apk"),
                       {cam.MODEL_ENTRY: make_bundle(payload_size=1_100_000)})
        code, out, err = self.run_main([apk])
        self.assertEqual(code, 0, err)
        self.assertIn("present", out)

    def test_apk_without_model_exits_one(self):
        apk = make_apk(os.path.join(self.dir, "bad.apk"), {"classes.dex": b"dex"})
        code, out, err = self.run_main([apk])
        self.assertEqual(code, 1)
        self.assertIn("ERROR", err)
        self.assertIn("missing", err)

    def test_unmatched_glob_exits_one(self):
        # The shell passes the literal pattern when no APK was built.
        code, _, err = self.run_main([os.path.join(self.dir, "*.apk")])
        self.assertEqual(code, 1)
        self.assertIn("file not found", err)

    def test_one_bad_apk_fails_the_whole_run(self):
        good = make_apk(os.path.join(self.dir, "good.apk"),
                        {cam.MODEL_ENTRY: make_bundle(payload_size=1_100_000)})
        bad = make_apk(os.path.join(self.dir, "bad.apk"), {"classes.dex": b"dex"})
        code, _, _ = self.run_main([good, bad])
        self.assertEqual(code, 1)

    def test_no_arguments_exits_two(self):
        code, _, err = self.run_main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", err)

    def test_github_actions_prints_error_annotations(self):
        apk = make_apk(os.path.join(self.dir, "bad.apk"), {"classes.dex": b"dex"})
        with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            code, _, err = self.run_main([apk])
        self.assertEqual(code, 1)
        self.assertIn("::error::", err)


class WorkflowRegressionTests(unittest.TestCase):
    """Locks in the CI fixes so the model path bug cannot quietly come back."""

    @classmethod
    def setUpClass(cls):
        with open(WORKFLOW, encoding="utf-8") as f:
            cls.text = f.read()

    def test_model_downloads_to_the_gradle_assets_folder(self):
        # Match the curl output target exactly; a bare substring check also matches the old
        # nested path app/app/src/main/assets/... and so proves nothing.
        self.assertIn("-o app/src/main/assets/hand_landmarker.task", self.text)
        self.assertIn("mkdir -p app/src/main/assets\n", self.text)

    def test_old_nested_path_is_gone(self):
        self.assertNotIn("app/app/", self.text)

    def test_build_runs_the_apk_model_check(self):
        self.assertIn("python3 tools/check_apk_model.py", self.text)
        self.assertIn("app/build/outputs/apk/debug/*.apk", self.text)


if __name__ == "__main__":
    unittest.main()
