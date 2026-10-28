package com.jarvis.control

import android.content.Context

/** Learns from your feedback after each photo, per kind of light: brighter or darker (ISO), and slower or faster (shutter).
 *  Stored only on the phone, in shared preferences. Each feedback moves the settings by a third of a stop, up to two stops. */
class PhotoTuning(context: Context) {
    enum class Feedback { GOOD, DARK, BRIGHT, NOISY, BLURRY }

    private val prefs = context.getSharedPreferences("jarvis_photo_tune", Context.MODE_PRIVATE)

    fun isoStops(scene: String): Double = prefs.getFloat("$scene.iso", 0f).toDouble()
    fun shutterStops(scene: String): Double = prefs.getFloat("$scene.shutter", 0f).toDouble()

    fun learn(scene: String, feedback: Feedback) {
        val step = 0.33f
        var iso = prefs.getFloat("$scene.iso", 0f)
        var shut = prefs.getFloat("$scene.shutter", 0f)
        when (feedback) {
            Feedback.DARK -> iso += step
            Feedback.BRIGHT -> iso -= step
            Feedback.NOISY -> {
                iso -= step        // less ISO means less grain...
                shut += step       // ...so use a slower shutter to keep the brightness
            }
            Feedback.BLURRY -> {
                shut -= step       // a faster shutter stops the blur...
                iso += step        // ...so raise ISO to keep the brightness
            }
            Feedback.GOOD -> {}
        }
        iso = iso.coerceIn(-2f, 2f)
        shut = shut.coerceIn(-2f, 2f)
        prefs.edit().putFloat("$scene.iso", iso).putFloat("$scene.shutter", shut).apply()
    }
}
