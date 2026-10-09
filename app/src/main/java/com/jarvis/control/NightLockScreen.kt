package com.jarvis.control

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown over any app that is blocked by the night lock. Only the phone app, WhatsApp and the
 * home screen can be opened from here. Back goes to the home screen, and the lock stays on.
 */
class NightLockScreen : AppCompatActivity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun appName(pkg: String?): String? {
        if (pkg == null) return null
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openPackage(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, "That app is not installed.", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(intent)
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(getColor(R.color.on_accent))
            setBackgroundResource(R.drawable.btn_primary)
            backgroundTintList = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        val blocked = appName(intent.getStringExtra("blocked"))
        val until = NightLock.hhmm(NightLock.endMin(this))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#050A10"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        column.addView(TextView(this).apply {
            text = "NIGHT LOCK"
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#E8F7FF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        column.addView(TextView(this).apply {
            text = (if (blocked != null) "$blocked is blocked" else "This app is blocked") +
                " until $until."
            textSize = 17f
            setTextColor(Color.parseColor("#E8F7FF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })

        column.addView(TextView(this).apply {
            text = "Only the Phone and WhatsApp are open. Emergency calls always work."
            textSize = 14f
            setTextColor(Color.parseColor("#8FB7C9"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(28) }
        })

        column.addView(button("PHONE") { startActivity(Intent(Intent.ACTION_DIAL)) })
        column.addView(button("WHATSAPP") { openPackage("com.whatsapp") })
        column.addView(button("HOME SCREEN") { goHome() })

        setContentView(column)
    }
}
