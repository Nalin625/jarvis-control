"""Checks that the phone's gesture instructions match the laptop workspace and no longer carry the old wording.

These read the source files as text. They cannot prove the app looks right on a phone; they stop the old
one-finger instructions from creeping back.

Run from the repo root:  python3 -m unittest discover -s tools -p "test_*.py" -v
"""
import os
import re
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(REPO, "app", "src", "main", "java", "com", "jarvis", "control")


def read(*parts):
    with open(os.path.join(REPO, *parts), encoding="utf-8") as f:
        return f.read()


GUIDE = read("app", "src", "main", "java", "com", "jarvis", "control", "GestureGuide.kt")
ACTIVITY = read("app", "src", "main", "java", "com", "jarvis", "control", "GestureActivity.kt")
SERVICE = read("app", "src", "main", "java", "com", "jarvis", "control", "GestureService.kt")
README = read("README.txt")

# Every gesture the laptop workspace understands (gestures.py docstring and jarvis_spatial tutorial).
WORKSPACE_GESTURES = {
    "Point": "two fingers",          # index + middle up
    "Grab": "fist",
    "Pause and resume": "open palm",
    "Back and next": "sweep",
    "Open a card": "thumbs up",
    "Undo": "rock sign",
    "Overview": "spread",
    "Cancel a drag": "pinch",
}

# Wording from the old one-finger instructions, and the old training flow. None may appear again.
OLD_WORDING = [
    "move the laptop mouse",
    "Your index finger moves",
    "Make a fist to pause",
    "Pinch to click",
    "Laptop pointer (index finger)",
    "RECORD 10 EXAMPLES",
    "TEST MY GESTURES",
    "no mouse helper",
    "Only the gesture name goes to the laptop",
]


class GuideCoversWorkspaceTests(unittest.TestCase):
    def test_every_workspace_gesture_is_named_in_the_guide(self):
        for name, shape in WORKSPACE_GESTURES.items():
            with self.subTest(gesture=name):
                self.assertIn(f'Item("{name}"', GUIDE)

    def test_each_gesture_description_names_its_hand_shape(self):
        # The sentence for a gesture should say the hand shape the user has to make.
        items = re.findall(r'Item\("([^"]+)", "([^"]+)"\)', GUIDE)
        self.assertEqual(len(items), len(WORKSPACE_GESTURES))
        shapes = {
            "Point": ["index", "middle"],
            "Grab": ["fist"],
            "Pause and resume": ["open palm"],
            "Back and next": ["open palm", "sweep"],
            "Open a card": ["thumbs up"],
            "Undo": ["rock sign"],
            "Overview": ["both hands"],
            "Cancel a drag": ["pinch"],
        }
        for name, text in items:
            with self.subTest(gesture=name):
                self.assertTrue(
                    any(word.lower() in text.lower() for word in shapes[name]),
                    f"{name!r} does not name its hand shape: {text!r}",
                )

    def test_point_says_the_index_tip_is_the_cursor(self):
        point = re.search(r'Item\("Point", "([^"]+)"\)', GUIDE).group(1)
        self.assertIn("index finger", point)
        self.assertIn("cursor", point)


class ActivityWordingTests(unittest.TestCase):
    def test_guide_card_is_shown_on_the_screen(self):
        self.assertIn("GestureGuide.items", ACTIVITY)
        self.assertIn("GESTURES FOR THE LAPTOP WORKSPACE", ACTIVITY)

    def test_custom_signs_are_labelled_optional_and_separate(self):
        self.assertIn("CUSTOM SIGNS (OPTIONAL)", ACTIVITY)
        self.assertIn("The workspace gestures above do not use them", ACTIVITY)

    def test_old_wording_is_gone_from_the_screen_and_service(self):
        for text, label in ((ACTIVITY, "GestureActivity.kt"), (SERVICE, "GestureService.kt"), (GUIDE, "GestureGuide.kt")):
            for phrase in OLD_WORDING:
                with self.subTest(file=label, phrase=phrase):
                    self.assertNotIn(phrase, text)

    def test_xdotool_warning_is_not_shown_as_a_fault(self):
        self.assertNotIn("has no mouse helper", ACTIVITY)
        self.assertIn("The workspace still works", ACTIVITY)


class ReadmeWordingTests(unittest.TestCase):
    def test_readme_lists_each_workspace_gesture(self):
        for name in WORKSPACE_GESTURES:
            with self.subTest(gesture=name):
                self.assertIn(name, README)

    def test_readme_has_no_old_wording(self):
        for phrase in OLD_WORDING:
            with self.subTest(phrase=phrase):
                self.assertNotIn(phrase, README)

    def test_readme_says_the_mouse_is_only_for_closed_workspace(self):
        self.assertIn("workspace is closed", README)
        self.assertIn("mouse when closed", README)


if __name__ == "__main__":
    unittest.main()
