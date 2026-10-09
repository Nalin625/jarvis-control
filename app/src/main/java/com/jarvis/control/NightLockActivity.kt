package com.jarvis.control

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Turn the night lock on and set its hours. Once the night starts, the lock's settings
 * cannot be changed until it ends.
 */
class NightLockActivity : AppCompatActivity() {

    private lateinit var blockerLine: TextView
    private lateinit var lockLine: TextView
    private lateinit var toggleBtn: Button
    private lateinit var startBtn: Button
    private lateinit var endBtn: Button
    private lateinit var batteryLine: TextView
    private lateinit var batteryBtn: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun line(size: Float, color: String, mono: Boolean = false): TextView =
        TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor(color))
            if (mono) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    private fun button(onClick: () -> Unit): Button =
        Button(this).apply {
            isAllCaps = false
            textSize = 15f
            setTextColor(getColor(R.color.on_accent))
            setBackgroundResource(R.drawable.btn_primary)
            backgroundTintList = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#050A10"))
        }

        column.addView(line(24f, "#E8F7FF", mono = true).apply { text = "NIGHT LOCK" })
        column.addView(line(14f, "#8FB7C9").apply {
            text = "Between the start and end times, only the Phone and WhatsApp open. " +
                "Every other app shows the Jarvis lock screen. Emergency calls always work."
        })

        blockerLine = line(15f, "#E8F7FF", mono = true)
        column.addView(blockerLine)
        column.addView(button {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.apply { text = "1. TURN ON THE BLOCKER" })
        column.addView(line(13f, "#8FB7C9").apply {
            text = "Open Accessibility, find Jarvis night lock, and turn it on. " +
                "It has to be on for the lock to work. Do this before the night starts."
        })

        column.addView(button { toggleLock() }.also { toggleBtn = it })
        startBtn = button { pickTime(isStart = true) }
        endBtn = button { pickTime(isStart = false) }
        column.addView(startBtn)
        column.addView(endBtn)

        lockLine = line(15f, "#E8F7FF")
        column.addView(lockLine)

        column.addView(line(14f, "#8FB7C9").apply {
            text = "KEEP IT RUNNING  //  do this during the day"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        batteryLine = line(14f, "#E8F7FF")
        column.addView(batteryLine)
        batteryBtn = button { askBatteryUnrestricted() }.apply { text = "ALLOW UNRESTRICTED BATTERY" }
        column.addView(batteryBtn)
        column.addView(line(13f, "#8FB7C9").apply {
            text = "Also set Autostart on for Jarvis Control in your phone's Security app, " +
                "and lock Jarvis in recent apps. Phones still close apps without these."
        })

        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val locked = NightLock.locked(this)
        val on = NightLock.enabled(this)
        val s = NightLock.hhmm(NightLock.startMin(this))
        val e = NightLock.hhmm(NightLock.endMin(this))

        blockerLine.text = "Blocker: " + (if (NightLockService.alive) "ON" else "OFF - turn it on below")

        toggleBtn.text = if (on) "2. NIGHT LOCK: ON" else "2. NIGHT LOCK: OFF"
        toggleBtn.isEnabled = !locked
        startBtn.text = "FROM $s"
        startBtn.isEnabled = !locked
        endBtn.text = "UNTIL $e"
        endBtn.isEnabled = !locked

        lockLine.text = when {
            locked -> "LOCKED until $e. The lock settings can't be changed until then."
            on -> "On. Starts at $s and ends at $e."
            else -> "Off."
        }

        val unrestricted = (getSystemService(android.os.PowerManager::class.java))
            ?.isIgnoringBatteryOptimizations(packageName) == true
        batteryLine.text = if (unrestricted) {
            "Battery: unrestricted. Good."
        } else {
            "Battery: restricted. Android may stop the lock in the background."
        }
        batteryBtn.isEnabled = !unrestricted
    }

    /** Asks Android to let Jarvis run without battery limits. Opens a system dialog, so do it during the day. */
    private fun askBatteryUnrestricted() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toggleLock() {
        if (NightLock.locked(this)) {
            Toast.makeText(this, "The lock is on. It can't be changed until morning.", Toast.LENGTH_LONG).show()
            return
        }
        val turningOn = !NightLock.enabled(this)
        if (turningOn && !NightLockService.alive) {
            Toast.makeText(this, "Turn on the blocker first (step 1).", Toast.LENGTH_LONG).show()
            return
        }
        NightLock.setSchedule(this, turningOn, NightLock.startMin(this), NightLock.endMin(this))
        refresh()
    }

    private fun pickTime(isStart: Boolean) {
        if (NightLock.locked(this)) {
            Toast.makeText(this, "The lock is on. It can't be changed until morning.", Toast.LENGTH_LONG).show()
            return
        }
        val current = if (isStart) NightLock.startMin(this) else NightLock.endMin(this)
        TimePickerDialog(
            this,
            TimePickerDialog.OnTimeSetListener { _, hour, minute ->
                val value = hour * 60 + minute
                if (isStart) {
                    NightLock.setSchedule(this, NightLock.enabled(this), value, NightLock.endMin(this))
                } else {
                    NightLock.setSchedule(this, NightLock.enabled(this), NightLock.startMin(this), value)
                }
                refresh()
            },
            current / 60,
            current % 60,
            true
        ).show()
    }
}
