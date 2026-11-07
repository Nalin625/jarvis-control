package com.jarvis.control

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A full-screen clock: the date and the time with seconds, updated every second. Runs on the phone only. */
class TimeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())
    private lateinit var dateText: TextView
    private lateinit var clockText: TextView

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockText.text = timeFormat.format(now)
            dateText.text = dateFormat.format(now)
            // Wake up right at the next second so the seconds never skip or lag.
            handler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = 0xFF202124.toInt()
        val light = 0xFFEAEAEA.toInt()

        val bar = TextView(this).apply {
            text = "Time"
            textSize = 16f
            setTextColor(light)
            setBackgroundColor(dark)
            setPadding(48, 40, 48, 40)
        }
        dateText = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFF9AA0A6.toInt())
            gravity = Gravity.CENTER
        }
        clockText = TextView(this).apply {
            textSize = 64f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(dark)
            addView(dateText)
            addView(clockText)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
            addView(bar)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }
}
