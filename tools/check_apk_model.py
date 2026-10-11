#!/usr/bin/env python3
"""Check that built APKs actually contain the MediaPipe hand-tracking model.

    python3 tools/check_apk_model.py app/build/outputs/apk/debug/*.apk

For each APK this requires:
  * the file is a zip (APK) that can be opened,
  * it contains assets/hand_landmarker.task,
  * that entry is at least MIN_MODEL_BYTES (a truncated or empty download fails),
  * that entry is itself a zip bundle (MediaPipe .task files are zip archives).

A bundle with no .tflite inside is reported as a warning, not a failure.

Exit status: 0 when every APK passes, 1 when any fails, 2 when no APK is given.
Without a usable model the phone's hand tracking cannot start, so the CI build
must fail rather than ship an APK that only shows "model missing" at runtime.
"""
import io
import os
import sys
import zipfile

MODEL_ENTRY = "assets/hand_landmarker.task"
# The real float16 hand_landmarker.task is several megabytes; 1 MB is a loose floor
# that still rejects empty files, HTML error pages and truncated downloads.
MIN_MODEL_BYTES = 1_000_000


class Result:
    """Outcome for one APK: problems fail the check, warnings do not."""

    def __init__(self):
        self.ok_line = None
        self.problems = []
        self.warnings = []


def inspect_apk(path, min_bytes=MIN_MODEL_BYTES):
    result = Result()
    try:
        apk = zipfile.ZipFile(path)
    except FileNotFoundError:
        result.problems.append(f"{path}: file not found")
        return result
    except zipfile.BadZipFile:
        result.problems.append(f"{path}: not a valid APK (zip) file")
        return result

    with apk:
        try:
            size = apk.getinfo(MODEL_ENTRY).file_size
        except KeyError:
            result.problems.append(f"{path}: {MODEL_ENTRY} is missing, so the model is not packaged")
            return result
        if size < min_bytes:
            result.problems.append(
                f"{path}: {MODEL_ENTRY} is {size} bytes, expected at least {min_bytes} "
                "(empty or truncated download)"
            )
            return result
        data = apk.read(MODEL_ENTRY)

    try:
        with zipfile.ZipFile(io.BytesIO(data)) as bundle:
            names = bundle.namelist()
    except zipfile.BadZipFile:
        result.problems.append(f"{path}: {MODEL_ENTRY} is not a zip bundle (wrong or corrupt file)")
        return result

    if not any(name.endswith(".tflite") for name in names):
        result.warnings.append(f"{path}: no .tflite model found inside {MODEL_ENTRY}")
    result.ok_line = f"OK  {path}: {MODEL_ENTRY} present ({size} bytes)"
    return result


def main(argv):
    if not argv:
        print("usage: check_apk_model.py APK [APK ...]", file=sys.stderr)
        return 2
    in_actions = os.environ.get("GITHUB_ACTIONS") == "true"
    failed = False
    for path in argv:
        result = inspect_apk(path)
        if result.ok_line:
            print(result.ok_line)
        for message in result.warnings:
            print(f"::warning::{message}" if in_actions else f"WARNING: {message}")
        for message in result.problems:
            failed = True
            print(f"::error::{message}" if in_actions else f"ERROR: {message}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
