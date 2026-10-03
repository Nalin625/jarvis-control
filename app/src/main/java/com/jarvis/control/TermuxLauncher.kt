package com.jarvis.control

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts a command inside Termux in the background (no terminal window) through Termux's
 * RUN_COMMAND intent. One-time setup, run inside Termux:
 *
 *   mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
 *   termux-reload-settings
 *
 * and allow the "Run commands in Termux" permission for this app when Android asks.
 */
object TermuxLauncher {

    const val DEFAULT_COMMAND =
        "termux-wake-lock; pgrep -x ollama >/dev/null || (OLLAMA_HOST=0.0.0.0 nohup ollama serve >\$HOME/ollama.log 2>&1 &)"

    /** Returns null on success, or a short human-readable reason it failed. */
    fun run(context: Context, command: String): String? {
        return try {
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            ContextCompat.startForegroundService(context, intent)
            null
        } catch (e: SecurityException) {
            "Termux refused. Run the one-time setup in Termux and allow the permission."
        } catch (e: Exception) {
            "Couldn't reach Termux (is it installed from F-Droid/GitHub?): ${e.message}"
        }
    }
}
