package com.jarvis.control

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Send to Jarvis": invisible activity that shows up in Android's Share sheet and in the
 * text-selection menu. It posts the text to the laptop's /clipboard, then closes at once.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            else -> ""
        }

        val prefs = getSharedPreferences("jarvis_control", MODE_PRIVATE)
        val ip = prefs.getString("laptop_ip", "").orEmpty()
        val port = prefs.getString("laptop_port", "8899").orEmpty().ifEmpty { "8899" }
        val token = prefs.getString("laptop_token", "").orEmpty()

        if (text.isBlank()) { toast("Nothing to send"); finish(); return }
        if (ip.isEmpty()) { toast("Set the laptop IP in Jarvis Control first"); finish(); return }

        Thread {
            val ok = try {
                val conn = URL("http://$ip:$port/clipboard").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Jarvis-Token", token)
                conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
                val good = conn.responseCode in 200..299
                conn.disconnect()
                good
            } catch (e: Exception) { false }
            runOnUiThread {
                toast(if (ok) "Sent to Jarvis" else "Couldn't reach Jarvis (is sync on?)")
                finish()
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
