package com.jarvis.control

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** A real stopwatch and a 1-minute countdown timer, in the app's own style. */
class TimerActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var readout: TextView
    private lateinit var modeButton: Button
    private lateinit var startStopButton: Button

    private var countdown = false
    private var running = false
    private var startedAt = 0L
    private var baseMs = 0L
    private var stopwatchMs = 0L
    private var countdownMs = COUNTDOWN_MS

    private val tick = object : Runnable {
        override fun run() {
            val delta = SystemClock.elapsedRealtime() - startedAt
            if (countdown) {
                val left = (baseMs - delta).coerceAtLeast(0L)
                readout.text = format(left)
                if (left == 0L) {
                    stop()
                    countdownMs = COUNTDOWN_MS
                    readout.text = "Time's up"
                    return
                }
            } else {
                readout.text = format(baseMs + delta)
            }
            handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = 0xFF202124.toInt()
        val light = 0xFFEAEAEA.toInt()

        val bar = TextView(this).apply {
            text = "Timer"
            textSize = 16f
            setTextColor(light)
            setBackgroundColor(dark)
            setPadding(48, 40, 48, 40)
        }
        modeButton = Button(this).apply { setOnClickListener { switchMode() } }
        readout = TextView(this).apply {
            textSize = 48f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        startStopButton = Button(this).apply { setOnClickListener { if (running) stop() else start() } }
        val resetButton = Button(this).apply { text = "RESET"; setOnClickListener { reset() } }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(startStopButton)
            addView(resetButton)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(dark)
            setPadding(48, 48, 48, 48)
            addView(modeButton)
            addView(readout)
            addView(buttons)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
            addView(bar)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        showIdle()
    }

    private fun switchMode() {
        stop()
        countdown = !countdown
        reset()
    }

    private fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        baseMs = if (countdown) countdownMs else stopwatchMs
        startStopButton.text = "STOP"
        handler.post(tick)
    }

    private fun stop() {
        if (!running) return
        val delta = SystemClock.elapsedRealtime() - startedAt
        if (countdown) countdownMs = (baseMs - delta).coerceAtLeast(0L)
        else stopwatchMs = baseMs + delta
        running = false
        handler.removeCallbacks(tick)
        startStopButton.text = "START"
        showIdle()
    }

    private fun reset() {
        stop()
        stopwatchMs = 0L
        countdownMs = COUNTDOWN_MS
        showIdle()
    }

    private fun showIdle() {
        modeButton.text = if (countdown) "Countdown: 1 min (tap for stopwatch)" else "Stopwatch (tap for countdown)"
        readout.text = format(if (countdown) countdownMs else stopwatchMs)
        if (!running) startStopButton.text = "START"
    }

    private fun format(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    companion object {
        private const val COUNTDOWN_MS = 60_000L
    }
}
