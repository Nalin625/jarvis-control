package com.jarvis.control

import android.app.Notification
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mirrors phone notifications to the laptop's Jarvis server (POST /phone_notification),
 * which queues them for the native app to display in its own notification bell.
 *
 * Requires the user to grant Notification Access in Android system settings (see
 * MainActivity's "Grant Notification Access" button) — this can't be requested as a
 * normal runtime permission, only enabled/disabled from that dedicated settings screen.
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private val queueLock = Any()
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("jarvis_control", MODE_PRIVATE)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = prefs()
        if (!p.getBoolean("notif_forward_enabled", false)) return

        // never mirror our own app's notifications (the foreground-service status bar entry) — avoids a loop
        if (sbn.packageName == packageName) return

        // group summary entries duplicate the individual notifications underneath them
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val ip = p.getString("laptop_ip", "")?.trim().orEmpty()
        val port = p.getString("laptop_port", "8899")?.trim()?.ifEmpty { "8899" } ?: "8899"
        val token = p.getString("laptop_token", "")?.trim().orEmpty()

        val payload = JSONObject()
            .put("app", appName)
            .put("title", title)
            .put("text", text)
            .put("ts", sbn.postTime / 1000.0)
            .put("key", sbn.key)

        Thread { sendOrQueue(payload, ip, port, token) }.start()
    }

    // ---- never lose a notification: if the laptop is asleep/unreachable, keep it on the
    // phone in a small offline queue and flush it the next time a send succeeds. ----
    private fun post(payload: JSONObject, ip: String, port: String, token: String): Boolean {
        return try {
            val conn = URL("http://$ip:$port/phone_notification").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Jarvis-Token", token)
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun sendOrQueue(payload: JSONObject, ip: String, port: String, token: String) {
        synchronized(queueLock) {
            val p = prefs()
            val queue = try { JSONArray(p.getString("notif_queue", "[]")) } catch (e: Exception) { JSONArray() }
            queue.put(payload)
            val remaining = JSONArray()
            var offline = false
            for (i in 0 until queue.length()) {
                val item = queue.getJSONObject(i)
                if (offline || !post(item, ip, port, token)) {
                    offline = true
                    remaining.put(item)
                }
            }
            // cap the offline backlog so it can't grow forever (keeps the newest 300)
            val trimmed = JSONArray()
            val from = maxOf(0, remaining.length() - 300)
            for (i in from until remaining.length()) trimmed.put(remaining.get(i))
            p.edit().putString("notif_queue", trimmed.toString()).apply()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // intentionally not mirrored: dismissing a notification on the phone must NOT remove it
        // from Jarvis. The laptop keeps its own permanent archive.
    }
}
