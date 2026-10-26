package com.jarvis.control

import android.content.Context

/** Learning mode: remembers the exposure you set by hand and starts the next session from it. Stays on this phone. */
class CameraLearning(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_camera_learn", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    /** Learned exposure preference, in exposure steps, -2..2. */
    val bias: Float
        get() = prefs.getFloat("ev_bias", 0f)

    /** Call when you set the exposure by hand. Newer choices count more than older ones. */
    fun learnFromUser(evIndex: Int) {
        if (!enabled) return
        val updated = (bias * 0.8f + evIndex * 0.2f).coerceIn(-2f, 2f)
        prefs.edit().putFloat("ev_bias", updated).apply()
    }

    fun forget() {
        prefs.edit().clear().apply()
    }
}
