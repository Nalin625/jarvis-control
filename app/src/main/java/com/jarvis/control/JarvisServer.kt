package com.jarvis.control

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Serves the same API shape as the laptop-side jarvis_phone_server.py,
 * so the laptop's commands ("phone battery", "vibrate phone", etc.) work
 * identically whether this is a real app or the old Termux script.
 *
 * Every request except /ping must include header: X-Jarvis-Token: <token>
 */
class JarvisServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    var token: String = ""

    private fun json(body: JSONObject, status: Response.Status = Response.Status.OK): Response {
        return newFixedLengthResponse(status, "application/json", body.toString())
    }

    private fun unauthorized(): Response {
        return json(JSONObject().put("error", "missing or incorrect X-Jarvis-Token"), Response.Status.UNAUTHORIZED)
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri

        if (path != "/ping") {
            val providedToken = session.headers["x-jarvis-token"]
            if (token.isNotEmpty() && providedToken != token) {
                return unauthorized()
            }
        }

        return try {
            when (path) {
                "/ping" -> handlePing()
                "/battery" -> handleBattery()
                "/notify" -> handleNotify(session)
                "/vibrate" -> handleVibrate()
                "/open" -> handleOpen(session)
                "/volume" -> handleVolume(session)
                "/torch" -> handleTorch(session)
                "/clipboard" -> handleClipboard()
                else -> json(JSONObject().put("error", "unknown endpoint"), Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "unknown error"), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun handlePing(): Response {
        return json(JSONObject().put("ok", true).put("message", "Jarvis Control app is alive."))
    }

    private fun handleBattery(): Response {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val statusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return json(
            JSONObject()
                .put("percentage", pct)
                .put("status", if (charging) "charging" else "discharging")
        )
    }

    private fun handleNotify(session: IHTTPSession): Response {
        val body = readBody(session)
        val message = body.optString("message", "")

        val channelId = "jarvis_notifications"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Jarvis")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)

        return json(JSONObject().put("ok", true))
    }

    private fun handleVibrate(): Response {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        return json(JSONObject().put("ok", true))
    }

    private fun handleOpen(session: IHTTPSession): Response {
        val body = readBody(session)
        val target = body.optString("target", "")

        val intent: Intent = if (target.startsWith("http://") || target.startsWith("https://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(target))
        } else {
            // Treat as an Android package name, e.g. "com.whatsapp"
            context.packageManager.getLaunchIntentForPackage(target)
                ?: return json(JSONObject().put("error", "No app found for '$target' (expected a URL or an exact package name)"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Android 10+ blocks apps from launching a new screen directly from a
        // background service — that's a real platform security restriction,
        // not a bug. The standard, correct workaround is a tap-to-open
        // notification: launching from a notification tap always counts as
        // a real user action, so it's allowed.
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, target.hashCode(), intent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, JarvisService.NOTIFY_CHANNEL_ID)
            .setContentTitle("Jarvis wants to open something")
            .setContentText("Tap to open: $target")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)

        return json(JSONObject().put("ok", true).put("note", "Posted as a tap-to-open notification (Android blocks direct background launches)."))
    }

    private fun handleVolume(session: IHTTPSession): Response {
        val body = readBody(session)
        val direction = body.optString("direction", "up")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val adjust = if (direction == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return json(JSONObject().put("ok", true).put("message", "Volume: $current/$max"))
    }

    private fun handleTorch(session: IHTTPSession): Response {
        val body = readBody(session)
        val state = body.optString("state", "on")

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return json(JSONObject().put("error", "Camera permission not granted — open the app once and allow it."))
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return json(JSONObject().put("error", "No flash-capable camera found on this device."))

        cameraManager.setTorchMode(camId, state == "on")
        return json(JSONObject().put("ok", true))
    }

    private fun handleClipboard(): Response {
        // Honest limitation: Android 10+ blocks background apps (including a
        // foreground service like this one) from reading clipboard contents
        // unless the app currently has focus. This will often return empty
        // on modern Android — there is no way around that from a background service.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = if (clipboard.hasPrimaryClip()) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } else ""
        return json(JSONObject().put("text", text))
    }
}
