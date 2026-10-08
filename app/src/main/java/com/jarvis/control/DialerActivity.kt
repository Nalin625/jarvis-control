package com.jarvis.control

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal number pad so Jarvis can be the phone's call manager. Calls started from Contacts or other apps go through Android as usual. */
class DialerActivity : Activity() {
    private lateinit var num: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0b1220"))
            setPadding((20 * d).toInt(), (60 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        }
        root.addView(TextView(this).apply { text = "Jarvis call manager"; textSize = 22f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply {
            text = "Type a number to call. For contacts, open your Contacts app and tap a number as usual."
            setTextColor(Color.parseColor("#9fb4c9")); setPadding(0, (8 * d).toInt(), 0, (16 * d).toInt())
        })
        num = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; textSize = 24f; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "Phone number" }
        root.addView(num)
        root.addView(Button(this).apply {
            text = "CALL"; textSize = 18f
            setOnClickListener { place() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (60 * d).toInt()))
        setContentView(root)
        intent?.data?.schemeSpecificPart?.let { num.setText(it) }
    }

    private fun place() {
        val n = num.text.toString().trim()
        if (n.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 5); return
        }
        try { (getSystemService(TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel", n, null), Bundle()); finish() } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) place() }
}
