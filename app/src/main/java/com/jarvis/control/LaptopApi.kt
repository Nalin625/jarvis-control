package com.jarvis.control

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tiny blocking client for the laptop's Flask backend (always call from a background thread). */
object LaptopApi {
    fun paired(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
        return !p.getString("laptop_ip", "").isNullOrEmpty() && !p.getString("laptop_token", "").isNullOrEmpty()
    }

    /** Returns the JSON reply, or {"error": "..."} - never throws. */
    fun call(ctx: Context, method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 4000): JSONObject {
        val p = ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
        val ip = p.getString("laptop_ip", "") ?: ""
        val port = p.getString("laptop_port", "8899") ?: "8899"
        val tok = p.getString("laptop_token", "") ?: ""
        if (ip.isEmpty() || tok.isEmpty()) return JSONObject().put("error", "Not connected. Tap Find my laptop first.")
        return try {
            val c = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("X-Jarvis-Token", tok)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.readText() ?: "{}"
            c.disconnect()
            try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", "bad reply from laptop") }
        } catch (e: Exception) {
            JSONObject().put("error", "Can't reach the laptop ($ip). Same WiFi? Laptop on?")
        }
    }

    /** Sends raw PCM audio to the laptop's /mic/push. Returns true when the laptop accepted it. */
    fun pushAudio(ctx: Context, pcm: ByteArray): Boolean {
        val p = ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
        val ip = p.getString("laptop_ip", "") ?: ""
        val port = p.getString("laptop_port", "8899") ?: "8899"
        val tok = p.getString("laptop_token", "") ?: ""
        if (ip.isEmpty() || tok.isEmpty()) return false
        return try {
            val c = URL("http://$ip:$port/mic/push").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 2000
            c.readTimeout = 2000
            c.doOutput = true
            c.setRequestProperty("X-Jarvis-Token", tok)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(pcm) }
            val ok = c.responseCode in 200..299
            c.inputStream?.close()
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** POSTs raw bytes (e.g. a WAV file) to a laptop route. True when accepted. */
    fun postBytes(ctx: Context, path: String, bytes: ByteArray, timeoutMs: Int = 5000): Boolean {
        val p = ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
        val ip = p.getString("laptop_ip", "") ?: ""
        val port = p.getString("laptop_port", "8899") ?: "8899"
        val tok = p.getString("laptop_token", "") ?: ""
        if (ip.isEmpty() || tok.isEmpty()) return false
        return try {
            val c = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("X-Jarvis-Token", tok)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(bytes) }
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }
}
