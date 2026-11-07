package com.jarvis.control

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Midnight Simulator: a clearly labelled SIMULATED TIME clock. It starts at 11:59 PM on 7 Nov 2026 and runs at
 * normal speed into 12:00 AM on 8 Nov 2026. It never changes the device clock, the status bar or the real date.
 */
class MidnightSimulatorActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("midnight_simulator", Context.MODE_PRIVATE) }
    private val simStart: Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 7, 23, 59, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
    private val dateFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.ENGLISH)

    /** Simulated time already run before the current running stretch, in ms. */
    private var offsetMs = 0L
    private var running = false
    /** SystemClock.elapsedRealtime() when the current running stretch started. Not affected by wall-clock changes. */
    private var anchorElapsed = 0L

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var playButton: Button

    private fun elapsedNow(): Long = SystemClock.elapsedRealtime()

    private fun simNow(): Long =
        simStart + offsetMs + if (running) elapsedNow() - anchorElapsed else 0L

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L - (elapsedNow() % 1000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offsetMs = prefs.getLong("offset_ms", 0L)
        val black = 0xFF000000.toInt()

        val label = TextView(this).apply {
            text = "SIMULATED TIME"
            textSize = 14f
            letterSpacing = 0.2f
            setTextColor(0xFFFFB74D.toInt())
            gravity = Gravity.CENTER
        }
        clockText = TextView(this).apply {
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            // Grow the time to fill the screen width so it is easy to read and film.
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 48, 120, 2, TypedValue.COMPLEX_UNIT_SP)
        }
        dateText = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF9E9E9E.toInt())
            gravity = Gravity.CENTER
        }
        playButton = Button(this).apply { setOnClickListener { if (running) pause() else resume() } }
        val resetButton = Button(this).apply { text = "RESET"; setOnClickListener { reset() } }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(playButton)
            addView(resetButton)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(label)
            addView(clockText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(dateText)
            addView(buttons)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        })
        render()
    }

    private fun resume() {
        if (running) return
        anchorElapsed = elapsedNow()
        running = true
        handler.post(tick)
        render()
    }

    private fun pause() {
        if (!running) return
        offsetMs += elapsedNow() - anchorElapsed
        running = false
        handler.removeCallbacks(tick)
        save()
        render()
    }

    private fun reset() {
        handler.removeCallbacks(tick)
        running = false
        offsetMs = 0L
        save()
        render()
    }

    private fun save() {
        prefs.edit().putLong("offset_ms", offsetMs).apply()
    }

    private fun render() {
        val now = Date(simNow())
        clockText.text = timeFormat.format(now)
        dateText.text = dateFormat.format(now)
        playButton.text = if (running) "PAUSE" else "START"
    }

    override fun onPause() {
        super.onPause()
        // Stop the simulated clock when the screen is hidden; it resumes from the same point.
        pause()
    }
}
