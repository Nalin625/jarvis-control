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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
                "/status" -> handleStatus()
                "/ai/start" -> handleAiStart()
                "/mic/start" -> handleMicStart()
                "/mic/stop" -> handleMicStop()
                "/mic/once" -> handleMicOnce()
                "/call/answer" -> handleCallAnswer()
                "/call/mode" -> handleCallMode(session)
                "/call/test" -> handleCallTest()
                "/find" -> handleFind(session)
                "/speak" -> handleSpeak(session)
                "/media" -> handleMedia(session)
                "/ringer" -> handleRinger(session)
                "/location" -> handleLocation()
                "/notify" -> handleNotify(session)
                "/vibrate" -> handleVibrate()
                "/open" -> handleOpen(session)
                "/volume" -> handleVolume(session)
                "/torch" -> handleTorch(session)
                "/clipboard" -> handleClipboard(session)
                "/screen.jpg" -> handleScreenFrame(session)
                "/screen/status" -> handleScreenStatus()
                "/lock" -> handleLock()
                "/power" -> handlePower()
                "/apps" -> handleApps()
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

    /** One-call device summary for the laptop's live phone card. */
    private fun handleStatus(): Response {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val st = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        @Suppress("DEPRECATION")
        val rssi = try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wm.connectionInfo?.rssi ?: 0
        } catch (e: Exception) { 0 }

        val sf = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        return json(
            JSONObject()
                .put("ok", true)
                .put("name", Build.MODEL)
                .put("battery", pct)
                .put("charging", charging)
                .put("temp_c", tempC)
                .put("wifi_dbm", rssi)
                .put("storage_free_gb", sf.availableBytes / 1_000_000_000.0)
                .put("storage_total_gb", sf.totalBytes / 1_000_000_000.0)
                .put("ram_free_mb", mi.availMem / 1_000_000)
                .put("ram_total_mb", mi.totalMem / 1_000_000)
        )
    }

    /** The laptop asks for the phone AI when Gemini is exhausted: start Ollama in Termux. */
    private fun micDenied(): Response =
        json(JSONObject().put("error", "Open Jarvis Control once and tap ALLOW MICROPHONE."), Response.Status.INTERNAL_ERROR)

    private fun handleMicStart(): Response {
        if (!MicService.hasPermission(context)) return micDenied()
        MicService.setListening(context.applicationContext, true)
        return json(JSONObject().put("ok", true).put("listening", true))
    }

    private fun handleMicStop(): Response {
        MicService.setListening(context.applicationContext, false)
        return json(JSONObject().put("ok", true).put("listening", false))
    }

    private fun handleMicOnce(): Response {
        if (!MicService.hasPermission(context)) return micDenied()
        MicService.listenOnce(context.applicationContext)
        return json(JSONObject().put("ok", true))
    }

    private fun handleCallAnswer(): Response =
        if (CallAssistService.answerNow()) json(JSONObject().put("ok", true).put("message", "Jarvis is answering the call."))
        else json(JSONObject().put("error", "No call is ringing, or the call assistant is off."))

    private fun handleCallMode(session: IHTTPSession): Response {
        val m = readBody(session).optString("mode", "").lowercase()
        if (m != "ask" && m != "auto" && m != "off") return json(JSONObject().put("error", "mode must be ask, auto or off"))
        val ok = CallAssistService.setMode(context.applicationContext, m)
        return if (ok) json(JSONObject().put("ok", true).put("message", "Call assistant: $m"))
        else json(JSONObject().put("error", "Open the Jarvis Control app and allow the phone permissions first, then try again."))
    }

    private fun handleCallTest(): Response =
        if (CallAssistService.test()) json(JSONObject().put("ok", true).put("message", "Test call started. Talk to the phone."))
        else json(JSONObject().put("error", "Call assistant is off. Turn it on in the phone app first."))

    private fun handleAiStart(): Response {
        val p = context.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
        val cmd = p.getString("termux_command", null)?.takeIf { it.isNotBlank() } ?: TermuxLauncher.DEFAULT_COMMAND
        val err = TermuxLauncher.run(context.applicationContext, cmd)
        return if (err == null) json(JSONObject().put("ok", true))
        else json(JSONObject().put("error", err), Response.Status.INTERNAL_ERROR)
    }

    private fun handleFind(session: IHTTPSession): Response {
        val on = readBody(session).optBoolean("on", true)
        if (on) FindPhone.start(context) else FindPhone.stop(context)
        return json(JSONObject().put("ok", true).put("ringing", FindPhone.ringing))
    }

    private fun handleSpeak(session: IHTTPSession): Response {
        val text = readBody(session).optString("text", "").take(500)
        if (text.isBlank()) return json(JSONObject().put("error", "no text"), Response.Status.BAD_REQUEST)
        PhoneTts.speak(context, text)
        return json(JSONObject().put("ok", true))
    }

    private fun handleMedia(session: IHTTPSession): Response {
        val key = readBody(session).optString("key", "")
        return if (PhoneMedia.send(context, key)) json(JSONObject().put("ok", true))
        else json(JSONObject().put("error", "unknown key"), Response.Status.BAD_REQUEST)
    }

    private fun handleRinger(session: IHTTPSession): Response {
        val err = PhoneRinger.set(context, readBody(session).optString("mode", ""))
        return if (err == null) json(JSONObject().put("ok", true))
        else json(JSONObject().put("error", err), Response.Status.BAD_REQUEST)
    }

    private fun handleLocation(): Response {
        val (loc, err) = PhoneLocation.lastFix(context)
        if (loc == null) return json(JSONObject().put("error", err ?: "no location"), Response.Status.BAD_REQUEST)
        return json(
            JSONObject()
                .put("ok", true)
                .put("lat", loc.latitude)
                .put("lon", loc.longitude)
                .put("accuracy_m", loc.accuracy.toInt())
                .put("age_s", (System.currentTimeMillis() - loc.time) / 1000)
                .put("maps", "https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
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
        context.startActivity(intent)
        return json(JSONObject().put("ok", true))
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

    private fun handleClipboard(session: IHTTPSession): Response {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        // POST {"text": "..."} -> put text on this phone's clipboard (laptop -> phone sync).
        // Writing the clipboard from the background is allowed on Android.
        if (session.method == Method.POST) {
            val text = readBody(session).optString("text", "")
            if (text.isEmpty()) return json(JSONObject().put("error", "empty text"), Response.Status.BAD_REQUEST)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis", text))
            }
            return json(JSONObject().put("ok", true))
        }

        // GET: honest limitation — Android 10+ blocks background apps (including a
        // foreground service like this one) from READING the clipboard unless the app
        // has focus, so this often returns empty. Phone -> laptop sync therefore uses the
        // "Send to Jarvis" share / text-selection action instead (ShareReceiverActivity).
        val text = if (clipboard.hasPrimaryClip()) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } else ""
        return json(JSONObject().put("text", text))
    }

    private fun handleScreenStatus(): Response {
        return json(
            JSONObject()
                .put("sharing", ScreenShareState.sharing)
                .put("width", ScreenShareState.width)
                .put("height", ScreenShareState.height)
        )
    }

    private val needsAccessibility =
        "The phone's accessibility blocker is off. Turn on Jarvis night lock in Accessibility (Night Lock section) first."

    private fun handleLock(): Response {
        val svc = NightLockService.instance ?: return json(JSONObject().put("error", needsAccessibility))
        return if (svc.lockScreen()) {
            json(JSONObject().put("ok", true).put("message", "Phone screen locked."))
        } else {
            json(JSONObject().put("error", "This phone couldn't lock the screen. Android 9 or newer is needed."))
        }
    }

    private fun handlePower(): Response {
        val svc = NightLockService.instance ?: return json(JSONObject().put("error", needsAccessibility))
        return if (svc.powerMenu()) {
            json(JSONObject().put("ok", true).put("message", "Power menu opened on your phone. Tap Power off there."))
        } else {
            json(JSONObject().put("error", "This phone couldn't open the power menu. Android 9 or newer is needed."))
        }
    }

    /** Every app that has an icon on the home screen, sorted by name. */
    private fun handleApps(): Response {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val list = JSONArray()
        for ((name, pkg) in apps) {
            list.put(JSONObject().put("name", name).put("package", pkg))
        }
        return json(JSONObject().put("count", apps.size).put("apps", list))
    }

    /**
     * Long-poll: the laptop asks for a frame newer than ?after=<id>, we hold the request
     * (up to 2s) until one exists. Only ever runs while the laptop's viewer window is open.
     */
    private fun handleScreenFrame(session: IHTTPSession): Response {
        if (!ScreenShareState.sharing) {
            return json(
                JSONObject().put("error", "Screen sharing isn't started on the phone."),
                Response.Status.BAD_REQUEST
            )
        }
        val params = session.parms
        params["q"]?.toIntOrNull()?.let { ScreenShareState.quality = it.coerceIn(20, 90) }
        params["fps"]?.toIntOrNull()?.let { ScreenShareState.fps = it.coerceIn(1, 20) }
        val after = params["after"]?.toLongOrNull() ?: -1L

        ScreenShareState.lastRequestMs = SystemClock.elapsedRealtime()
        val frame = ScreenShareState.awaitFrame(after, 2000L)
        ScreenShareState.lastRequestMs = SystemClock.elapsedRealtime()

        if (frame == null) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        }
        val bytes = frame.second
        val response = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        response.addHeader("X-Frame-Id", frame.first.toString())
        response.addHeader("Cache-Control", "no-store")
        return response
    }
}
