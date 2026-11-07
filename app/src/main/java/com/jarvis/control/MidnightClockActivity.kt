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
import java.util.Calendar
import java.util.Locale

/**
 * A clock that runs from 11:59:50 pm on 7 Nov to 12:00:10 am on 8 Nov, one second per second.
 * It is a stylised app clock for a video; it does not copy any website.
 */
class MidnightClockActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.ENGLISH)
    private val dateFormat = SimpleDateFormat("d MMM", Locale.ENGLISH)
    private val shown = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 7, 23, 59, 50)
        set(Calendar.MILLISECOND, 0)
    }
    private val stopAt = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 8, 0, 0, 10)
        set(Calendar.MILLISECOND, 0)
    }
    private lateinit var dateText: TextView
    private lateinit var clockText: TextView

    private val tick = object : Runnable {
        override fun run() {
            clockText.text = timeFormat.format(shown.time)
            dateText.text = dateFormat.format(shown.time)
            if (shown.before(stopAt)) {
                shown.add(Calendar.SECOND, 1)
                handler.postDelayed(this, 1000L)
            }
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
            textSize = 52f
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
