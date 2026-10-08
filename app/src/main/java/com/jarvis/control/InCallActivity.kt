package com.jarvis.control

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small in-call screen so you can still answer, decline, hang up and take over from Jarvis. */
class InCallActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var who: TextView
    private lateinit var state: TextView
    private lateinit var row: LinearLayout
    private var shown = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0b1220")); setPadding((24 * d).toInt(), (80 * d).toInt(), (24 * d).toInt(), (40 * d).toInt())
        }
        who = TextView(this).apply { textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        state = TextView(this).apply { textSize = 16f; setTextColor(Color.parseColor("#4cc9f0")); gravity = Gravity.CENTER; setPadding(0, (8 * d).toInt(), 0, (40 * d).toInt()) }
        row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(who); root.addView(state); root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        tick()
    }

    private fun btn(text: String, color: String, f: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 17f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
        setOnClickListener { f() }
    }

    private fun tick() {
        val c = JarvisInCallService.current
        if (c == null) { finish(); return }
        val num = c.details?.handle?.schemeSpecificPart.orEmpty()
        val name = CallAssistService.appCtx?.let { try { lookup(num) } catch (e: Exception) { "" } }.orEmpty()
        who.text = if (name.isNotBlank()) name else if (num.isNotBlank()) num else "Unknown"
        val key = "${c.state}|${CallAssistService.inSession}"
        if (key != shown) {
            shown = key
            row.removeAllViews()
            when {
                c.state == Call.STATE_RINGING -> {
                    state.text = "INCOMING CALL"
                    row.addView(btn("TALK MYSELF  (answer)", "#2e7d32") { CallAssistService.mineNow(); c.answer(0) })
                    row.addView(btn("LET JARVIS HANDLE", "#1f6f8b") { CallAssistService.answerNow() })
                    row.addView(btn("DECLINE", "#c62828") { c.reject(false, null) })
                }
                c.state == Call.STATE_ACTIVE || c.state == Call.STATE_DIALING || c.state == Call.STATE_CONNECTING -> {
                    if (CallAssistService.inSession) {
                        state.text = "JARVIS IS HANDLING THE CALL"
                        row.addView(btn("TAKE OVER", "#ef6c00") { CallAssistService.takeOverNow() })
                    } else state.text = if (c.state == Call.STATE_ACTIVE) "In call" else "Calling..."
                    row.addView(btn("SPEAKER ON / OFF", "#37474f") {
                        val on = JarvisInCallService.instance?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
                        JarvisInCallService.instance?.setAudioRoute(if (on) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER)
                    })
                    row.addView(btn("HANG UP", "#c62828") { c.disconnect() })
                }
                else -> state.text = "..."
            }
        }
        h.postDelayed({ tick() }, 500)
    }

    private fun lookup(num: String): String {
        if (num.isBlank()) return ""
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return ""
        val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(num))
        return contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
