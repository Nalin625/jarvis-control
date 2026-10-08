JARVIS GESTURE APP - read this first

WHAT THIS IS
This is your Jarvis Control Android project with the gesture camera added.
Your phone camera reads your hand. Only the gesture name goes to the laptop.

INSTALL (same way as before)
1. Replace the contents of your Jarvis Control GitHub project with this folder.
   (Or copy it into your project folder and push.)
2. GitHub Actions builds the APK. It downloads the hand-tracking model automatically.
3. Install the APK on your phone.
4. Open the NEW app icon: "Jarvis Gestures".

USE IT
1. Allow the camera when asked.
2. Type a gesture name, e.g. pinch. Press "Record 10 examples" and hold the sign still.
3. Press "Test my gestures" and check it says "I see: pinch".
4. Turn on "Camera on". A notification stays up while the camera is on.
5. Turn on "Send recognised gestures to the laptop".

LAPTOP
Replace jarvis_server.py and add jarvis_gestures.py (both are in the laptop update zip).
Restart JARVIS.

IF THE BUILD FAILS
The build has NOT been compiled yet. Copy the first error lines from the GitHub run
page and send them to me. I'll fix them.
