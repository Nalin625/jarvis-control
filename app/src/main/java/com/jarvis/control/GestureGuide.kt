package com.jarvis.control

/**
 * The hand gestures the laptop's spatial workspace understands, in the order the workspace tutorial teaches them.
 *
 * These must match the laptop side (gestures.py, whose docstring lists the same gestures, and jarvis_spatial.py).
 * tools/test_gesture_guide.py checks that every gesture name below is still in this file.
 */
object GestureGuide {

    data class Item(val name: String, val how: String)

    val intro = "These gestures move the cards on the laptop screen. Keep your hand in the camera's view, about an arm's length away."

    val items: List<Item> = listOf(
        Item("Point", "Hold your index and middle fingers up, the others folded. The tip of your index finger is the cursor. Hold still over a card for about a second to select it."),
        Item("Grab", "Make a fist over a card and hold it for a moment. Move the fist to carry the card. Open your hand to drop it."),
        Item("Pause and resume", "Hold an open palm still for about half a second to pause. Hold it again to resume."),
        Item("Back and next", "Sweep an open palm sideways. Sweep left to go back. While a card is open, sweep right for the next card."),
        Item("Open a card", "Hold a thumbs up for about half a second to open the card in view."),
        Item("Undo", "Hold the rock sign (index and little finger up, the others folded) for about a second to undo the last change."),
        Item("Overview", "Spread both hands apart to open the overview. Bring them together again to close it."),
        Item("Cancel a drag", "While you are carrying a card, pinch with your other hand for a moment. The card goes back where it was."),
    )

    /** What the phone sends, in one place, so the screen and the README say the same thing. */
    val laptopControlOn = "On. Your hand's joint positions are sent to the laptop, which moves the workspace pointer."
    val laptopControlOff = "Off. Your hand's joint positions are not sent to the laptop."
}
