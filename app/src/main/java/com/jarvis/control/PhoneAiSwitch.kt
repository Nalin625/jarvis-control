package com.jarvis.control

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home screen row: shows whether the phone's AI (Ollama in Termux) is running, and turns it on or off.
 * It uses the same Termux start and stop commands the laptop uses.
 */
class PhoneAiSwitch(private val act: Activity, private val dp: (Int) -> Int) {
    val view = LinearLayout(act)
    private val status = TextView(act)
    private val button = TextView(act)
    private var running = false

    private val cyan = 0xFF4FE3FF.toInt()
    private val coral = 0xFFFF7A5C.toInt()
    private val white = 0xFFEAF7FB.toInt()
    private val panel = 0xFF0B1A22.toInt()

    init {
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        view.setPadding(dp(4), dp(6), dp(4), dp(6))
        status.textSize = 12f
        status.typeface = Typeface.DEFAULT_BOLD
        view.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        button.textSize = 12f
        button.gravity = Gravity.CENTER
        button.setPadding(dp(12), dp(6), dp(12), dp(6))
        button.setOnClickListener { toggle() }
        view.addView(button)
        refresh()
    }

    private fun paint(on: Boolean?) {
        running = on == true
        status.setTextColor(white)
        status.text = when (on) {
            null -> "PHONE AI: checking..."
            true -> "PHONE AI: ON"
            false -> "PHONE AI: OFF"
        }
        val color = if (running) coral else cyan
        button.text = if (running) "TURN OFF" else "TURN ON"
        button.setTextColor(color)
        button.background = GradientDrawable().apply {
            setStroke(dp(1), color)
            cornerRadius = dp(8).toFloat()
            setColor(panel)
        }
    }

    /** Asks Ollama on this phone whether it answers. Runs in the background. */
    fun refresh() {
        paint(null)
        Thread {
            val up = try {
                val c = URL("http://127.0.0.1:11434/api/tags").openConnection() as HttpURLConnection
                c.connectTimeout = 2000
                c.readTimeout = 2000
                val ok = c.responseCode == 200
                c.disconnect()
                ok
            } catch (e: Exception) {
                false
            }
            act.runOnUiThread { paint(up) }
        }.start()
    }

    private fun toggle() {
        val turnOn = !running
        val err = TermuxLauncher.run(
            act.applicationContext,
            if (turnOn) TermuxLauncher.DEFAULT_COMMAND else "pkill -f 'ollama serve' || true"
        )
        val msg = when {
            err != null -> "Could not change it: $err"
            turnOn -> "Starting the phone AI..."
            else -> "Phone AI turned off."
        }
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
        // Ollama needs a few seconds to start before it answers
        view.postDelayed({ refresh() }, if (turnOn) 4000L else 1000L)
    }
}
