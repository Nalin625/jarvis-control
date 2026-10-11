JARVIS GESTURES - READ THIS FIRST

WHAT THE PHONE DOES
Your phone camera watches your hand. With "Laptop control" ON, the positions of your hand's joints are sent
to the laptop, and the laptop's workspace turns them into pointing and gestures. Your picture stays on the phone.

GESTURES FOR THE LAPTOP WORKSPACE
Keep your hand in the camera's view, about an arm's length away.
- Point: index and middle fingers up, others folded. The tip of your index finger is the cursor.
  Hold still over a card until it is picked. The hold time is the Dwell setting (0.8 seconds by default).
- Grab: make a fist over a card and hold it for a moment. Move the fist to carry the card. Open your hand to drop it.
  Carry the card to the right edge of the screen and hold it there for about 0.6 seconds to send it to the TV.
- Pause and resume: hold an open palm still for about half a second. Hold it again to resume.
- Back and next: sweep an open palm sideways. Left goes back. While a card is open, right goes to the next card.
- Open a card: hold a thumbs up for about half a second.
- Undo: hold the rock sign (index and little finger up) for about a second.
- Overview: spread both hands apart to open it. Bring them together to close it.
- Cancel a drag: while carrying a card, pinch with your other hand for a moment.

TUNE THE POINTER (THE LAPTOP KEEPS THESE)
The card called TUNE THE POINTER changes how the laptop reads your hand. The laptop saves the values and reads them
back, so the screen always shows what the laptop has.
- Sensitivity: how far the pointer moves for a hand movement. 0.5 is steadier and slower; 2.0 is quicker and less
  steady. Use the - and + buttons.
- Dwell: how long you hold still before a card is picked, from 0.4 to 2.0 seconds. Use the - and + buttons.
- Reset: puts sensitivity, dwell and the reach back to the defaults.
- Calibrate the reach: this teaches the laptop how far your hand must move to reach the whole screen.
  Press CALIBRATE THE REACH. Point with your index finger at each of four corners of the space you can reach
  comfortably (top left, top right, bottom right, bottom left). Hold each one still, then press CAPTURE.
  When all four are captured, the app checks the box. If it is too narrow or too wide, it says why and saves
  nothing. If it passes, press SAVE THE REACH TO THE LAPTOP.
- A change is shown as saved only after the laptop answers. If the laptop does not answer, the screen says so and
  nothing changes.
- If the laptop's workspace is LOCKED, the card says so. Tuning still saves while it is locked.

CUSTOM SIGNS (OPTIONAL)
The CUSTOM SIGNS card teaches your own signs for the laptop's older gesture map. The workspace gestures above do not
use them. "Send gestures to the laptop" must be ON for custom signs to reach the laptop.

LAPTOP
- The workspace must be open for the gestures to work.
- Lock and unlock: press L on the laptop's workspace screen. While locked, the hand gestures do nothing and LOCKED
  shows on the laptop and the TV. Press L again to unlock. The phone cannot lock or unlock the laptop.
- The laptop's mouse moves only when its workspace is closed AND its "mouse when closed" setting is on (off by
  default). There is no switch for it in the app yet. The setting is called mouse_when_closed in the file
  ~/.jarvis_gesture.json on the laptop. To turn it on, put this in that file, then restart the laptop server:

      {"mouse_when_closed": true}

  That mouse path needs the helper program:  sudo apt install xdotool

THE LINK LINE
The Link line on this screen says how the phone's packets to the laptop are doing: how many a second get there,
how many were lost on the way, how long the laptop takes to answer, and how many times it did not answer in time.
The laptop shows the same kind of figures in the header of its workspace screen.

BUILD (PHONE)
1. Put this project into your Jarvis Control repository and push it to GitHub.
2. On GitHub open Actions > Build APK and wait for the green tick.
   The build fails if the hand-tracking model is not inside the APK, so a green tick means the model is included.
3. Install the APK from that run (artifact JarvisControl-build). Open "Jarvis Gestures".

USE IT
1. Press START CAMERA and allow the camera. "Camera: On" means it is watching.
2. Press "Laptop control" to turn it ON. "Laptop control" says what it is doing.
3. Press CHECK THE LAPTOP. It says whether the laptop answered, and shows the laptop's tuning.
4. If the pointer is too slow, too jumpy, or a card is picked too soon or too late, adjust TUNE THE POINTER.

TESTING
The automated tests (tools/ and the JUnit tests in app/src/test) check the logic only. They do not show how the
camera behaves with your hand. The laptop folder's REAL_CAMERA_TEST.md describes the tests that need a real phone,
a real hand and a real laptop. Keep those results separate from the automated tests.

IF SOMETHING DOES NOT RESPOND
Look at the RIGHT NOW box. It says what is wrong, for example "Can't reach the laptop" or "Camera is off".
If the build is red, copy the first error lines from the run page and send them.
