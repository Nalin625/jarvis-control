package com.jarvis.control

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.net.NetworkInterface
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Second batch of phone tools started from the home grid (the "Phone tools 2" tiles).
 * Settings screens and app launches use plain intents. Only Settings.System writes need
 * WRITE_SETTINGS, and when it is not granted the permission screen is opened instead.
 */
object PhoneTools2 {
    private const val NO_APP = "No app on this phone can open that."
    private const val MAPS = "com.google.android.apps.maps"
    private const val YOUTUBE = "com.google.android.youtube"
    private const val WHATSAPP = "com.whatsapp"
    private const val GMAIL = "com.google.android.gm"
    private const val KEEP = "com.google.android.keep"

    private val handler = Handler(Looper.getMainLooper())

    // ---------- shared helpers ----------

    /** Starts an activity. Returns null on success, or a friendly error message. */
    private fun go(ctx: Context, intent: Intent): String? = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        NO_APP
    } catch (e: Exception) {
        "Could not open it: ${e.message}"
    }

    /** Opens a launcher app that declares an APP_* category, e.g. calendar or music. */
    private fun category(ctx: Context, cat: String, what: String): String =
        go(ctx, Intent(Intent.ACTION_MAIN).addCategory(cat)) ?: "Opened $what."

    /** Opens a settings screen by action. */
    private fun settings(ctx: Context, action: String, what: String): String =
        go(ctx, Intent(action)) ?: "Opened $what."

    private fun launchIntentFor(ctx: Context, packageName: String): Intent? = try {
        ctx.packageManager.getLaunchIntentForPackage(packageName)
    } catch (e: Exception) {
        null
    }

    /** Opens an installed app by package name. Needs a <queries> entry in the manifest on Android 11+. */
    private fun openPackage(ctx: Context, packageName: String, what: String): String {
        val intent = launchIntentFor(ctx, packageName)
        if (intent == null) return "$what is not installed on this phone."
        return go(ctx, intent) ?: "Opened $what."
    }

    /** Copies text to the clipboard. Returns null on success, or an error message. */
    private fun copyText(ctx: Context, label: String, text: String): String? = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        null
    } catch (e: Exception) {
        "Could not copy: ${e.message}"
    }

    /** Current clipboard text, or an empty string. */
    private fun clipText(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
    } catch (e: Exception) {
        ""
    }

    // ---------- display and system settings ----------

    /** Opens the screen that lets Jarvis change system settings. */
    private fun askWriteSettings(ctx: Context): String {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        return go(ctx, intent) ?: "Turn on 'Allow modifying system settings' for Jarvis, then tap again."
    }

    private fun nudgeBrightness(ctx: Context, delta: Int): String {
        if (!Settings.System.canWrite(ctx)) return askWriteSettings(ctx)
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = (cur + delta).coerceIn(10, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            "Brightness ${next * 100 / 255}%."
        } catch (e: Exception) {
            "Could not change brightness: ${e.message}"
        }
    }

    fun brightnessUp(ctx: Context): String = nudgeBrightness(ctx, 26)

    fun brightnessDown(ctx: Context): String = nudgeBrightness(ctx, -26)

    /** Flips the system auto-rotate setting. */
    fun autoRotate(ctx: Context): String {
        if (!Settings.System.canWrite(ctx)) return askWriteSettings(ctx)
        return try {
            val cr = ctx.contentResolver
            val on = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
            if (on) "Auto-rotate off." else "Auto-rotate on."
        } catch (e: Exception) {
            "Could not change auto-rotate: ${e.message}"
        }
    }

    fun displaySettings(ctx: Context): String = settings(ctx, Settings.ACTION_DISPLAY_SETTINGS, "display settings")

    fun soundSettings(ctx: Context): String = settings(ctx, Settings.ACTION_SOUND_SETTINGS, "sound settings")

    fun airplaneMode(ctx: Context): String =
        settings(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "airplane mode settings")

    /** Hotspot lives under a tethering screen on most phones, else under general network settings. */
    fun hotspot(ctx: Context): String =
        if (go(ctx, Intent("android.settings.TETHER_SETTINGS")) == null) "Opened hotspot settings."
        else settings(ctx, Settings.ACTION_WIRELESS_SETTINGS, "network settings (hotspot is in there)")

    fun locationSettings(ctx: Context): String =
        settings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location settings")

    fun accessibilitySettings(ctx: Context): String =
        settings(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility settings")

    fun dateTimeSettings(ctx: Context): String = settings(ctx, Settings.ACTION_DATE_SETTINGS, "date and time settings")

    /** This app's own page in Android settings (permissions, storage, force stop). */
    fun appInfo(ctx: Context): String =
        go(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            ?: "Opened Jarvis app info."

    /** Keeps the screen awake while this app is on screen. Toggles on each tap. */
    fun keepScreenOn(act: Activity): String {
        val on = (act.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0
        if (on) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return if (on) "Screen stays on while Jarvis is open." else "Screen can sleep again."
    }

    // ---------- apps and launchers ----------

    fun openClock(ctx: Context): String = go(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)) ?: "Opened the clock."

    fun openTimers(ctx: Context): String = go(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS)) ?: "Opened the timers."

    /** Starts a 1 minute timer in the clock app without showing its screen. */
    fun oneMinuteTimer(ctx: Context): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis 1 minute")
            .putExtra(AlarmClock.EXTRA_LENGTH, 60)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return go(ctx, intent) ?: "1-minute timer started."
    }

    fun openCalendar(ctx: Context): String = category(ctx, "android.intent.category.APP_CALENDAR", "calendar")

    fun openKeep(ctx: Context): String = openPackage(ctx, KEEP, "Google Keep notes")

    fun openContacts(ctx: Context): String = category(ctx, "android.intent.category.APP_CONTACTS", "contacts")

    fun openGallery(ctx: Context): String = category(ctx, "android.intent.category.APP_GALLERY", "gallery")

    /** Google Maps if installed, otherwise whatever app handles map locations. */
    fun openMaps(ctx: Context): String {
        val intent = launchIntentFor(ctx, MAPS) ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        return go(ctx, intent) ?: "Opened maps."
    }

    fun openYouTube(ctx: Context): String = openPackage(ctx, YOUTUBE, "YouTube")

    fun openWhatsApp(ctx: Context): String = openPackage(ctx, WHATSAPP, "WhatsApp")

    fun openGmail(ctx: Context): String = openPackage(ctx, GMAIL, "Gmail")

    fun openMusic(ctx: Context): String = category(ctx, "android.intent.category.APP_MUSIC", "music app")

    fun openCalculator(ctx: Context): String =
        category(ctx, "android.intent.category.APP_CALCULATOR", "calculator")

    fun openFiles(ctx: Context): String = category(ctx, "android.intent.category.APP_FILES", "file manager")

    // ---------- calls and messages ----------

    fun openDialer(ctx: Context): String = go(ctx, Intent(Intent.ACTION_DIAL)) ?: "Opened the dialer."

    /** Opens the messaging app with an empty text draft. */
    fun newText(ctx: Context): String =
        go(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) ?: "Opened a blank text."

    /** Opens the email composer with an empty draft. */
    fun newEmail(ctx: Context): String =
        go(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) ?: "Opened a blank email."

    /** Web search for whatever text is on the clipboard. */
    fun searchClipboard(ctx: Context): String {
        val text = clipText(ctx)
        if (text.isBlank()) return "The clipboard is empty."
        val q = URLEncoder.encode(text.take(200), "UTF-8")
        return go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
            ?: "Searching for the clipboard text."
    }

    /** Opens the web link on the clipboard in the browser. */
    fun openClipLink(ctx: Context): String {
        val text = clipText(ctx)
        val isLink = (text.startsWith("http://") || text.startsWith("https://")) && !text.contains(' ')
        if (!isLink) return "The clipboard does not hold a web link."
        return go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(text))) ?: "Opened the link."
    }

    // ---------- network and device info ----------

    @Suppress("DEPRECATION")
    fun wifiName(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            "Not on Wi-Fi right now."
        } else {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.trim('"').orEmpty()
            if (ssid.isBlank() || ssid == "<unknown ssid>") "Wi-Fi name hidden. Turn on Location and try again."
            else "Wi-Fi: $ssid"
        }
    } catch (e: SecurityException) {
        "Wi-Fi name hidden. Turn on Location and try again."
    } catch (e: Exception) {
        "Could not read the Wi-Fi name."
    }

    /** First non-loopback IPv4 address of the phone, or null. */
    fun localIp(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    fun phoneIp(ctx: Context): String {
        val ip = localIp() ?: return "No Wi-Fi or mobile address found."
        return "Phone IP: $ip"
    }

    fun copyIp(ctx: Context): String {
        val ip = localIp() ?: return "No network address to copy."
        return copyText(ctx, "Phone IP", ip) ?: "Copied $ip."
    }

    fun networkType(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "No network connection."
        val label = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "another type"
        }
        return "Connected over $label."
    }

    /** Time since the phone was last restarted. */
    fun uptime(): String {
        val total = SystemClock.elapsedRealtime() / 1000
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        return "Phone up ${d}d ${h}h ${m}m since its last restart."
    }

    /** Number of apps Android lets Jarvis see. Android hides the rest from apps like this one. */
    fun appCount(ctx: Context): String {
        val n = try {
            ctx.packageManager.getInstalledApplications(0).size
        } catch (e: Exception) {
            -1
        }
        return if (n < 0) "Could not count the apps."
        else "Jarvis can see $n apps. Android hides the other apps from it."
    }

    /** Whether Jarvis can read notifications, and whether it is mirroring them to the laptop. */
    fun notifStatus(ctx: Context): String {
        val access = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_NOTIFICATION_LISTENERS)
                .orEmpty().contains(ctx.packageName)
        } catch (e: Exception) {
            false
        }
        val mirror = ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
            .getBoolean("notif_forward_enabled", false)
        return "Notification access: " + (if (access) "on" else "off") +
            ".\nMirror to laptop: " + (if (mirror) "on" else "off") + "."
    }

    // ---------- time ----------

    /** Copies the current date and time to the clipboard. */
    fun copyDateTime(ctx: Context): String {
        val stamp = SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss zzz", Locale.getDefault()).format(Date())
        return copyText(ctx, "Date and time", stamp) ?: "Copied: $stamp"
    }

    /** The time now in a fixed list of cities. */
    fun worldClock(): String {
        val zones = listOf(
            "UTC" to "UTC",
            "Europe/London" to "London",
            "America/New_York" to "New York",
            "America/Los_Angeles" to "Los Angeles",
            "Asia/Dubai" to "Dubai",
            "Asia/Kolkata" to "Kolkata",
            "Asia/Singapore" to "Singapore",
            "Asia/Tokyo" to "Tokyo",
            "Australia/Sydney" to "Sydney"
        )
        val now = Date()
        return zones.joinToString("\n") { (id, name) ->
            val fmt = SimpleDateFormat("EEE HH:mm", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone(id)
            "$name: " + fmt.format(now)
        }
    }

    // ---------- fun and utility ----------

    fun randomNumber(): String = "Random number: ${Random.nextInt(1, 101)}."

    fun coinFlip(): String = if (Random.nextBoolean()) "Heads." else "Tails."

    fun rollDice(): String = "Dice: ${Random.nextInt(1, 7)}."

    private var swRunning = false
    private var swStart = 0L
    private var swLapStart = 0L

    private fun fmtMs(ms: Long): String {
        val centis = (ms / 10) % 100
        val secs = (ms / 1000) % 60
        val mins = ms / 60000
        return String.format(Locale.US, "%02d:%02d.%02d", mins, secs, centis)
    }

    /** Starts the stopwatch, or stops it and reports the total. */
    fun stopwatch(): String {
        val now = SystemClock.elapsedRealtime()
        return if (!swRunning) {
            swRunning = true
            swStart = now
            swLapStart = now
            "Stopwatch started."
        } else {
            swRunning = false
            "Stopped at ${fmtMs(now - swStart)}."
        }
    }

    /** Records a lap time while the stopwatch runs. */
    fun stopwatchLap(): String {
        if (!swRunning) return "The stopwatch is not running. Start it first."
        val now = SystemClock.elapsedRealtime()
        val lap = now - swLapStart
        swLapStart = now
        return "Lap ${fmtMs(lap)}, total ${fmtMs(now - swStart)}."
    }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** A lub-dub heartbeat buzz, repeated three times. */
    fun heartbeat(ctx: Context): String {
        val timings = longArrayOf(0, 90, 120, 90, 620, 90, 120, 90, 620, 90, 120, 90, 620)
        vibrator(ctx).vibrate(VibrationEffect.createWaveform(timings, -1))
        return "Heartbeat buzz."
    }

    // SOS in Morse: three short, three long, three short. Each step is (torch on?, milliseconds).
    private fun buildSos(): List<Pair<Boolean, Long>> {
        val out = ArrayList<Pair<Boolean, Long>>()
        repeat(3) { out.add(true to 150L); out.add(false to 150L) }
        out.add(false to 300L)
        repeat(3) { out.add(true to 450L); out.add(false to 150L) }
        out.add(false to 300L)
        repeat(3) { out.add(true to 150L); out.add(false to 150L) }
        out.add(false to 1500L)
        return out
    }

    private val sosSteps: List<Pair<Boolean, Long>> = buildSos()
    private var sosActive = false
    private var sosIndex = 0
    private var sosCm: CameraManager? = null
    private var sosCamId: String? = null

    private val sosRunner = object : Runnable {
        override fun run() {
            val cm = sosCm ?: return
            val id = sosCamId ?: return
            if (!sosActive) return
            if (sosIndex >= sosSteps.size * 3) {
                stopSos()
                return
            }
            val step = sosSteps[sosIndex % sosSteps.size]
            sosIndex++
            try {
                cm.setTorchMode(id, step.first)
            } catch (e: Exception) {
                // torch busy or gone; keep the timing going anyway
            }
            handler.postDelayed(this, step.second)
        }
    }

    /** Flashes SOS on the rear torch for three rounds. Tap again to stop early. */
    fun flashSos(ctx: Context): String {
        if (sosActive) {
            stopSos()
            return "SOS stopped."
        }
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no flash."
            sosCm = cm
            sosCamId = id
            sosIndex = 0
            sosActive = true
            handler.post(sosRunner)
            "Flashing SOS for 3 rounds. Tap again to stop."
        } catch (e: Exception) {
            "SOS failed: ${e.message}"
        }
    }

    private fun stopSos() {
        sosActive = false
        handler.removeCallbacks(sosRunner)
        try {
            val cm = sosCm
            val id = sosCamId
            if (cm != null && id != null) cm.setTorchMode(id, false)
        } catch (e: Exception) {
            // already off
        }
        sosCm = null
        sosCamId = null
    }

    /** Adds a line to a quick-notes text file in the app's private storage. */
    fun quickNote(act: Activity) {
        val file = File(act.filesDir, "quick_notes.txt")
        val last = try {
            file.takeIf { it.exists() }?.readLines()?.lastOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        val input = EditText(act).apply {
            hint = "Type a note"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(act)
            .setTitle("Quick note")
            .setMessage(if (last != null) "Last saved: $last" else "No notes saved yet.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val msg = try {
                    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                    file.appendText("$stamp  ${text.replace('\n', ' ')}\n")
                    "Note saved."
                } catch (e: Exception) {
                    "Could not save the note."
                }
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- sharing and the app store ----------

    private fun storeLink(ctx: Context): String = "https://play.google.com/store/apps/details?id=${ctx.packageName}"

    /** Opens the share sheet with a short text and the app's store link. */
    fun shareApp(ctx: Context): String {
        val text = "Jarvis Control runs my phone and laptop from one app.\n" + storeLink(ctx)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return go(ctx, Intent.createChooser(send, "Share Jarvis Control")) ?: "Opened the share menu."
    }

    /** Opens the Jarvis app page in the app store (falls back to the web page). */
    fun appStore(ctx: Context): String {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ctx.packageName}"))
        if (go(ctx, market) == null) return "Opened the app store."
        return go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(storeLink(ctx))))
            ?: "Opened the app store page."
    }
}
