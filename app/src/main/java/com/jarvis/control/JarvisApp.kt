package com.jarvis.control

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Applies the saved colour theme (cyan = day resources, black = night resources) before any screen opens. */
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val black = getSharedPreferences("jarvis_control", MODE_PRIVATE).getBoolean("theme_black", false)
        AppCompatDelegate.setDefaultNightMode(
            if (black) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
