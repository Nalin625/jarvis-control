package com.jarvis.control

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The new Home of Jarvis Control. Nothing is crammed on one screen:
 *  - tap the CORE and the main controls burst out around it
 *  - slide your finger left/right and the DIAL under it spins through the other features
 *  - swipe UP for the app index (Niagara-style A-Z list), swipe DOWN for the laptop deck, a spinning drum
 * Every control still runs the original button of the app (it presses the same tile), so all the
 * features of the old Home and Deck pages keep working. Start AI is not on this screen.
 */
class HoloHome private constructor(private val act: Activity, private val pageHome: View) {

    class Item(val label: String, val icon: String, val tile: String?, val risky: Boolean = false, val run: (() -> String)? = null)

    private val accent = act.getColor(R.color.accent)
    private val dim = act.getColor(R.color.text_dim)
    private val edge = act.getColor(R.color.accent_dim)
    private val bgc = act.getColor(R.color.bg_root)
    private val textMain = act.getColor(R.color.text_primary)
    private val warn = act.getColor(R.color.warning)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var stage: Stage
    private lateinit var deck: DeckView
    private var backCb: OnBackPressedCallback? = null
    private lateinit var rootLp: FrameLayout.LayoutParams
    private var navHeight = 0
    private var navView: View? = null
    /** true = Jarvis is the phone's home screen (launcher look). false = the normal Jarvis Control app. */
    var launcher = false
        private set
    private var deckOpen = false
    private var inboxOpen = false
    private lateinit var inbox: InboxPanel

    // the core opens exactly these three switches
    private val burst = listOf(
        Item("SERVER", "bolt", null), Item("MIC", "mic", null), Item("NOTIFY", "bell", null)
    )
    private val dial = listOf(
        Item("PHONE DECK", "phone", null), Item("NOTIFICATIONS", "bell", null), Item("LAPTOP DECK", "laptop", null),
        Item("FIND LAPTOP", "radar", "FIND LAPTOP"), Item("REMOTE", "remote", "REMOTE"),
        Item("SHARE SCREEN", "share", "SHARE SCREEN"), Item("PASTE TO LAPTOP", "paste", "PASTE TO LAPTOP"),
        Item("PAIR", "link", "PAIR"), Item("THEME", "theme", "THEME"), Item("SETUP", "sliders", "SETUP")
    )

    private fun volDown(): String {
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return "VOLUME " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + " / " + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    private fun media(k: String, say: String): String = if (PhoneMedia.send(act, k)) say else "NO PLAYER ACTIVE"

    /** Phone-only controls (they run on this phone and do not need the laptop). */
    private val phoneGroups = listOf(
        "SOUND" to listOf(
            Item("VOLUME UP", "volup", null, false) { PhoneTools.musicVolumeUp(act).uppercase() },
            Item("VOLUME DOWN", "voldn", null, false) { volDown() },
            Item("PLAY / PAUSE", "play", null, false) { media("play_pause", "PLAY / PAUSE") },
            Item("NEXT SONG", "next", null, false) { media("next", "NEXT SONG") },
            Item("PREVIOUS SONG", "prev", null, false) { media("prev", "PREVIOUS SONG") },
            Item("RING MODE", "bell", null, false) { PhoneRinger.set(act, "normal") ?: "RINGER ON" },
            Item("VIBRATE ONLY", "buzz", null, false) { PhoneRinger.set(act, "vibrate") ?: "VIBRATE ONLY" },
            Item("SILENT", "mute", null, false) { PhoneRinger.set(act, "silent") ?: "SILENT" }),
        "LIGHT" to listOf(
            Item("FLASHLIGHT", "torch", null, false) { PhoneTools.flashlight(act).uppercase() },
            Item("BRIGHTER", "sun", null, false) { PhoneTools2.brightnessUp(act).uppercase() },
            Item("DIMMER", "moon", null, false) { PhoneTools2.brightnessDown(act).uppercase() },
            Item("AUTO-ROTATE", "refresh", null, false) { PhoneTools2.autoRotate(act).uppercase() },
            Item("KEEP SCREEN ON", "eye", null, false) { PhoneTools2.keepScreenOn(act).uppercase() },
            Item("SOS FLASH", "alert", null, false) { PhoneTools2.flashSos(act).uppercase() },
            Item("DISPLAY SETTINGS", "sliders", null, false) { PhoneTools2.displaySettings(act).uppercase() }),
        "CONNECT" to listOf(
            Item("WI-FI", "wifi", null, false) { PhoneTools.openWifi(act).uppercase() },
            Item("BLUETOOTH", "bt", null, false) { PhoneTools.openBluetooth(act).uppercase() },
            Item("HOTSPOT", "hotspot", null, false) { PhoneTools2.hotspot(act).uppercase() },
            Item("AIRPLANE MODE", "plane", null, false) { PhoneTools2.airplaneMode(act).uppercase() },
            Item("DO NOT DISTURB", "moon", null, false) { PhoneTools.openDnd(act).uppercase() },
            Item("LOCATION", "pin", null, false) { PhoneTools2.locationSettings(act).uppercase() }),
        "INFO" to listOf(
            Item("BATTERY", "battery", null, false) { PhoneTools.battery(act).uppercase() },
            Item("STORAGE", "disk", null, false) { PhoneTools.storage(act).uppercase() },
            Item("PHONE INFO", "info", null, false) { PhoneTools.info(act).uppercase() },
            Item("BUZZ", "buzz", null, false) { PhoneTools.buzz(act).uppercase() },
            Item("HEARTBEAT", "heart", null, false) { PhoneTools2.heartbeat(act).uppercase() },
            Item("LAST CALL", "phone", null, false) { PhoneTools.lastCall(act).take(60).uppercase() }),
        "OPEN" to listOf(
            Item("CAMERA", "camera", null, false) { PhoneTools.openCamera(act).uppercase() },
            Item("CLOCK", "clock", null, false) { PhoneTools2.openClock(act).uppercase() },
            Item("TIMER", "timer", null, false) { PhoneTools2.oneMinuteTimer(act).uppercase() },
            Item("CALENDAR", "cal", null, false) { PhoneTools2.openCalendar(act).uppercase() },
            Item("CONTACTS", "people", null, false) { PhoneTools2.openContacts(act).uppercase() },
            Item("GALLERY", "image", null, false) { PhoneTools2.openGallery(act).uppercase() },
            Item("MAPS", "pin", null, false) { PhoneTools2.openMaps(act).uppercase() },
            Item("CALCULATOR", "calc", null, false) { PhoneTools2.openCalculator(act).uppercase() },
            Item("WHATSAPP", "chat", null, false) { PhoneTools2.openWhatsApp(act).uppercase() },
            Item("GMAIL", "mail", null, false) { PhoneTools2.openGmail(act).uppercase() },
            Item("YOUTUBE", "play", null, false) { PhoneTools2.openYouTube(act).uppercase() },
            Item("MUSIC", "music", null, false) { PhoneTools2.openMusic(act).uppercase() })
    )

    private val laptopGroups = listOf(
        "AUDIO" to listOf(Item("VOLUME UP", "volup", "VOL UP"), Item("VOLUME DOWN", "voldn", "VOL DOWN"),
            Item("MUTE", "mute", "MUTE"), Item("UNMUTE", "unmute", "UNMUTE")),
        "DISPLAY" to listOf(Item("BRIGHTER", "sun", "BRIGHT +"), Item("DIMMER", "moon", "BRIGHT -"),
            Item("SCREENSHOT", "shot", "SCREENSHOT"), Item("LOCK", "lock", "LOCK")),
        "INFO" to listOf(Item("BATTERY", "battery", "BATTERY"), Item("RAM", "chip", "RAM"), Item("WIFI", "wifi", "WIFI"),
            Item("IP ADDRESS", "globe", "IP"), Item("NEWS", "news", "NEWS"), Item("WEATHER", "cloud", "WEATHER"),
            Item("DIAGNOSE", "pulse", "DIAGNOSE"), Item("STATUS REPORT", "bars", "STATUS")),
        "POWER" to listOf(Item("SLEEP", "sleep", "SLEEP", true), Item("AWAY MODE", "away", "AWAY MODE"),
            Item("STAY AWAKE", "eye", "STAY AWAKE"), Item("OFF IN...", "timer", "OFF IN..."),
            Item("CANCEL TIMER", "cancel", "CANCEL TIMER"), Item("RESTART", "restart", "RESTART", true),
            Item("SHUT DOWN", "power", "SHUT DOWN", true))
    )

    private fun dp(v: Float): Float = v * act.resources.displayMetrics.density

    // ------------------------------------------------------------------ build

    private fun build() {
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        val navId = act.resources.getIdentifier("navBar", "id", act.packageName)
        val nav = if (navId != 0) act.findViewById<View>(navId) else null
        val navH = if (nav != null && nav.height > 0) nav.height else dp(62f).toInt()

        root = FrameLayout(act)
        root.tag = "holo"
        root.isClickable = true
        root.setBackgroundColor(bgc)
        stage = Stage(act)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        deck = DeckView(act)
        deck.visibility = View.GONE
        root.addView(deck, FrameLayout.LayoutParams(-1, -1))
        rootLp = FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = navH }
        navHeight = navH
        navView = nav
        inbox = InboxPanel(act)
        inbox.visibility = View.GONE
        root.addView(inbox, FrameLayout.LayoutParams(-1, -1))
        content.addView(root, rootLp)

        (act as? ComponentActivity)?.let { ca ->
            val cb = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (AppDrawer.closeIfOpen(act)) return
                    if (inboxOpen) { closeInbox(); return }
                    if (deckOpen) closeDeck() else stage.closeBurst()
                    // as the home screen, Back does nothing (stays on Home)
                }
            }
            ca.onBackPressedDispatcher.addCallback(ca, cb)
            backCb = cb
        }
        applyMode()
    }

    /** Launcher look only when Jarvis is the home screen; the normal app screens are untouched. */
    private fun applyMode() {
        val want = if (launcher) View.VISIBLE else View.GONE
        if (root.visibility != want) root.visibility = want
        if (!launcher) { stage.closeBurst(); if (deckOpen) closeDeck(); if (inboxOpen) closeInbox() }
        rootLp.bottomMargin = if (launcher) 0 else navHeight
        root.layoutParams = rootLp
        navView?.visibility = if (launcher) View.GONE else View.VISIBLE
        updateBack()
        if (launcher) root.bringToFront()
    }

    private fun updateBack() { backCb?.isEnabled = launcher || deckOpen || inboxOpen || stage.burstOpen }

    fun setLauncher(on: Boolean) {
        if (launcher == on) return
        launcher = on
        applyMode()
    }

    fun openDeck(phone: Boolean = true) {
        if (deckOpen) return
        deckOpen = true
        deck.visibility = View.VISIBLE
        if (phone) deck.prepare("PHONE DECK", phoneGroups) else deck.prepare("LAPTOP DECK", laptopGroups)
        deck.translationY = -root.height.toFloat()
        deck.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
        updateBack()
    }

    fun openInbox() {
        if (inboxOpen) return
        inboxOpen = true
        inbox.visibility = View.VISIBLE
        inbox.translationY = root.height.toFloat()
        inbox.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(1.6f)).start()
        inbox.load()
        updateBack()
    }

    fun closeInbox() {
        if (!inboxOpen) return
        inboxOpen = false
        inbox.animate().translationY(root.height.toFloat()).setDuration(240).withEndAction {
            if (!inboxOpen) inbox.visibility = View.GONE
        }.start()
        updateBack()
    }

    fun closeDeck() {
        if (!deckOpen) return
        deckOpen = false
        deck.animate().translationY(-root.height.toFloat()).setDuration(240).withEndAction {
            if (!deckOpen) deck.visibility = View.GONE
        }.start()
        updateBack()
    }

    /** Presses the original tile of the app with this label. Returns false if this version of the app has no such tile. */
    private fun press(label: String, say: String): Boolean {
        val ok = findAndClick(act.window.decorView, label)
        if (ok) stage.flash(say) else Toast.makeText(act, "Not available in this version of the app", Toast.LENGTH_SHORT).show()
        return ok
    }

    private fun findAndClick(v: View, label: String): Boolean {
        if (v === root) return false
        if (v is TextView && v.text.toString().trim().equals(label, ignoreCase = true)) {
            var p: View? = v
            while (p != null && !p.isClickable) p = p.parent as? View
            if (p != null) { p.performClick(); return true }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (findAndClick(v.getChildAt(i), label)) return true
        return false
    }

    private fun fire(it: Item) {
        when (it.label) {
            "PHONE DECK" -> openDeck(true)
            "LAPTOP DECK" -> openDeck(false)
            "NOTIFICATIONS" -> openInbox()
            else -> it.tile?.let { t ->
                press(t, "▶ " + it.label)
                // these open a page of the normal app, so leave the launcher look
                if (t == "PAIR" || t == "SETUP" || t == "LAPTOP STATUS") setLauncher(false)
            }
        }
    }

    // ------------------------------------------------------------------ SERVER / MIC / NOTIFS switches

    private val prefs by lazy { act.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE) }

    private fun notifAccessGranted(): Boolean =
        (android.provider.Settings.Secure.getString(act.contentResolver, "enabled_notification_listeners") ?: "").contains(act.packageName)

    private fun serverOn() = JarvisService.running
    private fun micOn() = prefs.getBoolean("wake_listen", false) && MicService.hasPermission(act)
    private fun notifOn() = prefs.getBoolean("notif_forward_enabled", false) && notifAccessGranted()

    private fun say(s: String) = stage.flash(s)

    private fun toggleServer() {
        if (JarvisService.running) {
            act.stopService(android.content.Intent(act, JarvisService::class.java)); say("SERVER STOPPED")
        } else {
            val token = prefs.getString("token", "") ?: ""
            if (token.isEmpty()) { Toast.makeText(act, "Open the Jarvis app once (CORE page) to create the server token", Toast.LENGTH_LONG).show(); return }
            act.startForegroundService(android.content.Intent(act, JarvisService::class.java).putExtra(JarvisService.ACTION_TOKEN_EXTRA, token))
            say("SERVER STARTED")
        }
        ui.postDelayed({ stage.readSwitches(); stage.invalidate() }, 700L)
    }

    private fun toggleMic() {
        if (!MicService.hasPermission(act)) {
            act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4411)
            say("ALLOW THE MIC, THEN TAP AGAIN"); return
        }
        if (prefs.getBoolean("wake_listen", false)) { MicService.setListening(act, false); say("MIC OFF") }
        else { MicService.start(act); MicService.setListening(act, true); say("LISTENING FOR THE WAKE WORD") }
        stage.readSwitches(); stage.invalidate()
    }

    private fun toggleNotif() {
        val granted = notifAccessGranted()
        when {
            notifOn() -> { prefs.edit().putBoolean("notif_forward_enabled", false).apply(); say("NOTIFICATIONS STAY ON THE PHONE") }
            !granted -> {
                say("ALLOW JARVIS IN THE LIST")
                act.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            else -> { prefs.edit().putBoolean("notif_forward_enabled", true).apply(); say("NOTIFICATIONS GO TO THE LAPTOP") }
        }
        stage.readSwitches(); stage.invalidate()
    }

    // ------------------------------------------------------------------ shared drawing helpers

    private fun hex(p: Path, cx: Float, cy: Float, r: Float) {
        p.reset()
        for (i in 0..5) {
            val a = (PI / 3 * i - PI / 2).toFloat()
            val x = cx + r * cos(a); val y = cy + r * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    private fun faded(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private abstract inner class Ticking(ctx: Context) : View(ctx) {
        private var last = 0L
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                acc += dt
                if (acc >= 0.033f) { frame(acc); acc = 0f; invalidate() }
            }
        }
        private var acc = 0f
        abstract fun frame(dt: Float)
        private fun sync() { if (isAttachedToWindow && isShown) { if (!anim.isRunning) { last = 0L; anim.start() } } else anim.cancel() }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
        override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
    }

    private fun drawHexIcon(c: Canvas, item: Item, cx: Float, cy: Float, r: Float, on: Boolean, a: Int, hexPath: Path,
                            line: Paint, fill: Paint, ic: Paint, danger: Boolean = false) {
        val col = if (danger) warn else accent
        hex(hexPath, cx, cy, r)
        fill.color = faded(col, if (on) 70 * a / 255 else 26 * a / 255)
        c.drawPath(hexPath, fill)
        line.color = faded(if (on) col else edge, a)
        line.strokeWidth = (if (on) 2f else 1.2f) * act.resources.displayMetrics.density
        c.drawPath(hexPath, line)
        ic.color = faded(if (on) col else dim, a)
        Ico.draw(c, item.icon, cx, cy, r * 0.95f, ic)
    }

    // ================================================================== STAGE (core, burst, dial)

    private inner class Stage(ctx: Context) : Ticking(ctx) {
        private val d = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val hfill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ic = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 1.7f * d }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hexP = Path()
        private val oval = RectF()
        private var glow: RadialGradient? = null
        private var core: RadialGradient? = null
        private var sweep: SweepGradient? = null

        private var cx = 0f; private var cy = 0f; private var cr = 0f
        private var dTop = 0f; private var dR = 0f
        private var rot = 0f; private var pulse = 0f
        private val px = FloatArray(34); private val py = FloatArray(34); private val ps = FloatArray(34); private val pv = FloatArray(34)
        private var timeText = ""; private var dateText = ""; private var tick = 0f

        var burstOpen = false
        private var sv = false; private var mc = false; private var nt = false
        private val okCol = act.getColor(R.color.success)
        // live info: battery, network, laptop reachability, unread laptop notifications
        private var bat = -1; private var charging = false; private var netText = "--"
        private var lap = -1; private var unread = -1; private var lastPoll = 0L
        fun readSwitches() {
            try { sv = serverOn(); mc = micOn(); nt = notifOn() } catch (e: Exception) { }
            try {
                val b = act.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                if (b != null) {
                    val lv = b.getIntExtra("level", -1); val sc = b.getIntExtra("scale", 100)
                    bat = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
                    val st = b.getIntExtra("status", -1)
                    charging = st == 2 || st == 5
                }
                val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                netText = when {
                    caps == null -> "OFFLINE"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    else -> "ONLINE"
                }
            } catch (e: Exception) { }
            val now = System.currentTimeMillis()
            if (now - lastPoll > 45_000L) {
                lastPoll = now
                Thread {
                    if (!LaptopApi.paired(act)) { lap = -1; unread = -1 } else {
                        val r = LaptopApi.call(act, "GET", "/phone_notifications?limit=1", null, 3000)
                        if (r.has("error")) { lap = 0 } else { lap = 1; unread = r.optInt("unread", 0) }
                    }
                }.start()
            }
        }
        private var burstP = 0f
        private var burstAnim: ValueAnimator? = null
        private var pos = 0f
        private var posAnim: ValueAnimator? = null
        private var lastDetent = 0
        private var toast = ""; private var toastA = 0f

        // touch
        private var x0 = 0f; private var y0 = 0f; private var mode = 0   // 0 none, 1 dial drag, 2 vertical
        private var pos0 = 0f
        private var vt: VelocityTracker? = null

        init { for (i in px.indices) { px[i] = (Math.random()).toFloat(); py[i] = (Math.random()).toFloat(); ps[i] = 0.6f + Math.random().toFloat() * 1.6f; pv[i] = 0.004f + Math.random().toFloat() * 0.012f } }

        private fun step() = dR * sin(DIAL_STEP)

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            readSwitches()
            cx = w / 2f
            cr = min(w * 0.27f, h * 0.17f)
            cy = h * 0.30f
            dTop = h * 0.66f
            dR = w * 0.95f
            glow = RadialGradient(cx, cy, cr * 1.25f, faded(accent, 110), faded(accent, 0), Shader.TileMode.CLAMP)
            core = RadialGradient(cx, cy, cr * 0.34f, intArrayOf(Color.WHITE, accent, faded(accent, 40)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            sweep = SweepGradient(cx, cy, intArrayOf(faded(accent, 0), faded(accent, 0), faded(accent, 90)), floatArrayOf(0f, 0.6f, 1f))
        }

        override fun frame(dt: Float) {
            rot = (rot + dt * 34f) % 3600f
            pulse += dt * 2.2f
            for (i in px.indices) { py[i] -= pv[i] * dt * 6f; if (py[i] < 0f) { py[i] = 1f; px[i] = Math.random().toFloat() } }
            tick += dt
            if (tick > 1f || timeText.isEmpty()) {
                tick = 0f
                val cal = Calendar.getInstance()
                timeText = if (DateFormat.is24HourFormat(context)) String.format(Locale.US, "%d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                else String.format(Locale.US, "%d:%02d", if (cal.get(Calendar.HOUR) == 0) 12 else cal.get(Calendar.HOUR), cal.get(Calendar.MINUTE))
                dateText = String.format(Locale.getDefault(), "%1\$tA  %1\$td %1\$tb", cal).uppercase(Locale.getDefault())
            }
            if (toastA > 0f) toastA = (toastA - dt * 0.7f).coerceAtLeast(0f)
            if (tick == 0f) readSwitches()
        }

        fun flash(s: String) { toast = s; toastA = 1f }

        // ---- drawing
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (cr <= 0f) return
            // grid + drifting particles
            fill.shader = null
            line.color = faded(accent, 14); line.strokeWidth = 1f
            val g = 38 * d
            var gy = g; while (gy < h) { c.drawLine(0f, gy, w, gy, line); gy += g }
            var gx = g; while (gx < w) { c.drawLine(gx, 0f, gx, h, line); gx += g }
            for (i in px.indices) {
                fill.color = faded(accent, (40 + 90 * (0.5f + 0.5f * sin(pulse + i))).toInt())
                c.drawCircle(px[i] * w, py[i] * h, ps[i] * d, fill)
            }
            val calm = 1f - burstP

            // clock
            txt.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            txt.textAlign = Paint.Align.CENTER; txt.color = faded(textMain, (255 * calm).toInt()); txt.textSize = 48 * d
            txt.letterSpacing = 0f
            c.drawText(timeText, cx, cy - cr - 18 * d, txt)
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textSize = 10 * d; txt.letterSpacing = 0.25f
            txt.color = faded(accent, (230 * calm).toInt())
            c.drawText("J.A.R.V.I.S  ·  $dateText", cx, cy - cr - 68 * d, txt)
            drawCore(c)
            drawStats(c, calm)
            drawBurst(c)
            drawDial(c, 1f - 0.75f * burstP)

            // chip to go to the normal app
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.RIGHT
            txt.textSize = 10 * d; txt.letterSpacing = 0.18f; txt.color = faded(accent, (230 * calm).toInt())
            c.drawText("JARVIS APP \u25B8", w - 18 * d, 30 * d, txt)
            // hint + toast
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textSize = 9 * d; txt.letterSpacing = 0.18f
            txt.textAlign = Paint.Align.CENTER
            txt.color = faded(dim, (150 * calm).toInt())
            c.drawText("↑ APPS    ·    ↓ PHONE DECK    ·    TAP THE CORE", cx, h - 16 * d, txt)
            if (toastA > 0f) {
                txt.textSize = 12 * d; txt.letterSpacing = 0.2f; txt.color = faded(accent, (255 * toastA).toInt())
                c.drawText(toast, cx, cy + cr + 30 * d, txt)
            }
        }

        private fun drawStats(c: Canvas, a: Float) {
            if (a <= 0.02f) return
            val top = (cy + cr + dTop - 34 * d) / 2f - 28 * d
            val l = 18 * d; val r = width - 18 * d; val h = 56 * d
            oval.set(l, top, r, top + h)
            hfill.color = faded(accent, (14 * a).toInt()); c.drawRoundRect(oval, 16 * d, 16 * d, hfill)
            line.color = faded(edge, (200 * a).toInt()); line.strokeWidth = 1.2f * d; c.drawRoundRect(oval, 16 * d, 16 * d, line)
            val names = arrayOf("BATTERY", "NETWORK", "LAPTOP", "INBOX")
            val vals = arrayOf(if (bat < 0) "--" else "$bat%" + (if (charging) "\u26A1" else ""), netText,
                when (lap) { 1 -> "ONLINE"; 0 -> "OFFLINE"; else -> "NOT PAIRED" },
                if (unread < 0) "--" else if (unread == 0) "CLEAR" else "$unread NEW")
            val cols = intArrayOf(if (bat in 0..19 && !charging) warn else okCol, accent,
                if (lap == 1) okCol else if (lap == 0) warn else dim, if (unread > 0) warn else accent)
            val cw = (r - l) / 4f
            for (i in 0..3) {
                val x = l + cw * (i + 0.5f)
                if (i > 0) { line.color = faded(edge, (150 * a).toInt()); c.drawLine(l + cw * i, top + 12 * d, l + cw * i, top + h - 12 * d, line) }
                txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.CENTER
                txt.textSize = 8 * d; txt.letterSpacing = 0.18f; txt.color = faded(dim, (230 * a).toInt())
                c.drawText(names[i], x, top + 21 * d, txt)
                txt.textSize = (if (vals[i].length > 7) 10 else 13) * d; txt.letterSpacing = 0.05f; txt.color = faded(cols[i], (255 * a).toInt())
                c.drawText(vals[i], x, top + 41 * d, txt)
            }
        }

        private fun drawCore(c: Canvas) {
            val breathe = 0.5f + 0.5f * sin(pulse)
            val r = cr * (1f - 0.06f * burstP)
            fill.shader = glow; fill.alpha = (150 + 100 * breathe).toInt().coerceAtMost(255)
            c.drawCircle(cx, cy, cr * 1.25f, fill); fill.shader = null; fill.alpha = 255
            stroke.shader = null; stroke.color = faded(accent, 90); stroke.strokeWidth = 1.5f * d
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 9 * d), rot * 0.6f)
            c.drawCircle(cx, cy, r * 0.98f, stroke); stroke.pathEffect = null
            if (bat >= 0) {
                oval.set(cx - r * 1.1f, cy - r * 1.1f, cx + r * 1.1f, cy + r * 1.1f)
                stroke.color = faded(edge, 160); stroke.strokeWidth = 3f * d; c.drawArc(oval, 0f, 360f, false, stroke)
                stroke.color = if (bat < 20 && !charging) warn else if (charging) okCol else accent
                c.drawArc(oval, -90f, 360f * bat / 100f, false, stroke)
            }
            c.save(); c.rotate(-rot * 0.15f, cx, cy)
            stroke.color = faded(accent, 140); stroke.strokeWidth = 1.2f * d
            for (i in 0 until 72) {
                val len = (if (i % 6 == 0) 9f else 4f) * d
                c.drawLine(cx, cy - r * 0.9f, cx, cy - r * 0.9f + len, stroke); c.rotate(5f, cx, cy)
            }
            c.restore()
            stroke.color = accent; stroke.strokeWidth = 4f * d
            oval.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
            c.save(); c.rotate(-rot * 1.2f - burstP * 120f, cx, cy)
            for (i in 0 until 3) c.drawArc(oval, i * 120f + 8f, 72f, false, stroke)
            c.restore()
            fill.shader = sweep; c.save(); c.rotate(rot * 2.2f, cx, cy); c.drawCircle(cx, cy, r * 0.7f, fill); c.restore(); fill.shader = null
            stroke.color = faded(accent, 220); stroke.strokeWidth = 2.2f * d
            oval.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f)
            c.save(); c.rotate(rot * 3f, cx, cy); c.drawArc(oval, 0f, 110f, false, stroke); c.drawArc(oval, 180f, 110f, false, stroke); c.restore()
            val s = 1f + 0.05f * breathe
            c.save(); c.scale(s, s, cx, cy); fill.shader = core; c.drawCircle(cx, cy, r * 0.34f, fill); fill.shader = null; c.restore()
            hex(hexP, cx, cy, r * 0.2f)
            stroke.color = Color.WHITE; stroke.strokeWidth = 1.6f * d; c.drawPath(hexP, stroke)
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.CENTER
            txt.letterSpacing = 0f; txt.textSize = r * 0.24f; txt.color = act.getColor(R.color.bg_root)
            c.drawText("J", cx, cy + r * 0.085f, txt)
        }

        private fun burstPos(i: Int, p: Float): FloatArray {
            val ang = Math.toRadians((BURST_ANG[i]).toDouble())
            val rr = (cr + 66 * d) * p
            return floatArrayOf((cx + rr * cos(ang)).toFloat(), (cy + rr * sin(ang)).toFloat())
        }

        private fun drawBurst(c: Canvas) {
            if (burstP <= 0.01f) return
            val p = burstP
            val sh = (1f - p.coerceIn(0f, 1f))
            if (sh > 0.02f) { line.color = faded(accent, (200 * sh).toInt()); line.strokeWidth = 2f * d; c.drawCircle(cx, cy, cr * (1f + 0.9f * p.coerceIn(0f, 1f)), line) }
            val states = booleanArrayOf(sv, mc, nt)
            for (i in burst.indices) {
                val pt = burstPos(i, p)
                val col = if (states[i]) okCol else warn
                line.color = faded(col, (110 * p).toInt()); line.strokeWidth = 1.2f * d
                c.drawLine(cx, cy, pt[0], pt[1], line)
                val r = 32 * d * (0.4f + 0.6f * p)
                hex(hexP, pt[0], pt[1], r)
                hfill.color = faded(col, (if (states[i]) 70 else 40) * (255 * p).toInt() / 255); c.drawPath(hexP, hfill)
                line.color = faded(col, (255 * p).toInt().coerceIn(0, 255)); line.strokeWidth = 2f * d; c.drawPath(hexP, line)
                ic.color = faded(col, (255 * p).toInt().coerceIn(0, 255))
                Ico.draw(c, burst[i].icon, pt[0], pt[1], r * 0.95f, ic)
                txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textSize = 9 * d; txt.letterSpacing = 0.15f
                txt.textAlign = Paint.Align.CENTER; txt.color = faded(textMain, (230 * p).toInt())
                c.drawText(burst[i].label, pt[0], pt[1] + r + 14 * d, txt)
                txt.textSize = 13 * d; txt.color = faded(col, (255 * p).toInt().coerceIn(0, 255))
                c.drawText(if (states[i]) "ON" else "OFF", pt[0], pt[1] + r + 30 * d, txt)
            }
        }

        private fun mod(i: Int, n: Int) = ((i % n) + n) % n

        private fun drawDial(c: Canvas, alpha: Float) {
            val dcx = cx; val dcy = dTop + dR
            // track + ticks that turn with the dial
            line.strokeWidth = 1.2f * d; line.color = faded(edge, (255 * alpha).toInt())
            oval.set(dcx - dR, dcy - dR, dcx + dR, dcy + dR)
            c.drawArc(oval, -90f - 40f, 80f, false, line)
            line.color = faded(accent, (110 * alpha).toInt())
            val tickStep = Math.toDegrees(DIAL_STEP.toDouble()).toFloat() / 4f
            val base = -pos * 4f
            for (t in -14..14) {
                val a = (t - (base - floor(base))) * tickStep
                val ra = Math.toRadians((-90f + a).toDouble())
                val long = ((t - floor(base).toInt()) % 4 == 0)
                val r1 = dR + 6 * d; val r2 = dR + (if (long) 15 else 10) * d
                c.drawLine((dcx + r1 * cos(ra)).toFloat(), (dcy + r1 * sin(ra)).toFloat(), (dcx + r2 * cos(ra)).toFloat(), (dcy + r2 * sin(ra)).toFloat(), line)
            }
            val b = floor(pos + 0.5f).toInt()
            for (k in -3..3) {
                val off = (b + k) - pos
                val ang = off * DIAL_STEP
                val x = dcx + dR * sin(ang)
                val y = dTop + dR * (1f - cos(ang))
                val dist = abs(off)
                if (dist > 2.6f) continue
                val sc = 1f - 0.2f * min(dist, 2.5f)
                val a = ((1f - 0.3f * dist).coerceIn(0.12f, 1f) * 255 * alpha).toInt()
                val item = dial[mod(b + k, dial.size)]
                if (dist < 0.6f) {
                    val gl = (1f - dist / 0.6f) * (0.6f + 0.4f * sin(pulse * 1.3f))
                    for (q in 1..3) { hfill.color = faded(accent, (a * 0.07f * gl / q).toInt()); c.drawCircle(x, y, 34 * d * sc + q * 9 * d, hfill) }
                }
                drawHexIcon(c, item, x, y, 34 * d * sc, dist < 0.5f, a, hexP, line, hfill, ic)
                if (item.label == "NOTIFICATIONS" && unread > 0) {
                    val bx = x + 26 * d * sc; val by = y - 26 * d * sc
                    hfill.color = faded(warn, a); c.drawCircle(bx, by, 10 * d, hfill)
                    txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.CENTER
                    txt.textSize = 10 * d; txt.letterSpacing = 0f; txt.color = faded(bgc, a)
                    c.drawText(if (unread > 99) "99+" else unread.toString(), bx, by + 3.5f * d, txt)
                }
            }
            val cur = dial[mod(b, dial.size)]
            val near = 1f - min(1f, abs(pos - b) * 2f)
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.CENTER
            txt.textSize = 13 * d; txt.letterSpacing = 0.2f; txt.color = faded(accent, (255 * near * alpha).toInt())
            c.drawText(cur.label, cx, dTop + 34 * d + 36 * d, txt)
            txt.textSize = 9 * d; txt.color = faded(dim, (200 * near * alpha).toInt())
            c.drawText(String.format(Locale.US, "%02d / %02d", mod(b, dial.size) + 1, dial.size), cx, dTop + 34 * d + 52 * d, txt)
        }

        // ---- interaction
        fun closeBurst() { if (burstOpen) { burstOpen = false; animateBurst(0f) } }

        private fun animateBurst(to: Float) {
            burstAnim?.cancel()
            burstAnim = ValueAnimator.ofFloat(burstP, to).apply {
                duration = if (to > 0f) 420 else 220
                interpolator = if (to > 0f) OvershootInterpolator(1.6f) else DecelerateInterpolator()
                addUpdateListener { burstP = (it.animatedValue as Float).coerceAtLeast(0f); invalidate() }
                start()
            }
            updateBack()
        }

        private fun snapTo(target: Float) {
            posAnim?.cancel()
            posAnim = ValueAnimator.ofFloat(pos, target).apply {
                duration = 340; interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { pos = it.animatedValue as Float; detent(); invalidate() }
                start()
            }
        }

        private fun detent() {
            val b = floor(pos + 0.5f).toInt()
            if (b != lastDetent) { lastDetent = b; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; mode = 0; pos0 = pos
                    posAnim?.cancel()
                    vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.x - x0; val dy = e.y - y0
                    if (mode == 0) {
                        if (abs(dx) > slop && abs(dx) > abs(dy) && !burstOpen) mode = 1
                        else if (abs(dy) > slop * 2 && abs(dy) > abs(dx)) mode = 2
                    }
                    if (mode == 1) { pos = pos0 - dx / step(); detent(); invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    vt?.addMovement(e)
                    val dx = e.x - x0; val dy = e.y - y0
                    when (mode) {
                        1 -> {
                            vt?.computeCurrentVelocity(1000)
                            val vx = vt?.xVelocity ?: 0f
                            snapTo(Math.round(pos - vx / step() * 0.22f).toFloat())
                        }
                        2 -> {
                            if (burstOpen) closeBurst()
                            else if (dy < -dp(80f)) AppDrawer.open(act)
                            else if (dy > dp(80f)) openDeck()
                        }
                        else -> tap(e.x, e.y)
                    }
                    vt?.recycle(); vt = null
                }
                MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null; if (mode == 1) snapTo(Math.round(pos).toFloat()) }
            }
            return true
        }

        private fun tap(x: Float, y: Float) {
            if (!burstOpen && y < 56 * d && x > width - 150 * d) { setLauncher(false); return }
            if (burstOpen) {
                for (i in burst.indices) {
                    val pt = burstPos(i, 1f)
                    if (hypot(x - pt[0], y - pt[1]) < 38 * d) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); when (i) { 0 -> toggleServer(); 1 -> toggleMic(); else -> toggleNotif() }; return }
                }
                closeBurst(); return
            }
            if (hypot(x - cx, y - cy) < cr) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                burstOpen = true; animateBurst(1f); return
            }
            val b = floor(pos + 0.5f).toInt()
            for (k in -2..2) {
                val off = (b + k) - pos
                val ang = off * DIAL_STEP
                val ix = cx + dR * sin(ang); val iy = dTop + dR * (1f - cos(ang))
                if (hypot(x - ix, y - iy) < 38 * d) {
                    if (k == 0 && abs(off) < 0.5f) fire(dial[mod(b, dial.size)]) else snapTo((b + k).toFloat())
                    return
                }
            }
        }
    }

    // ================================================================== DECK (the drum)

    private inner class DeckView(ctx: Context) : Ticking(ctx) {
        private val d = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val hfill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ic = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 1.7f * d }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hexP = Path()
        private val oval = RectF()

        private var gr: List<Pair<String, List<Item>>> = phoneGroups
        private var title = "PHONE DECK"
        private var tab = 0
        private var tabX = 0f
        private var pos = 0f
        private var posAnim: ValueAnimator? = null
        private var lastDetent = 0
        private var scan = 0f
        private var holdP = 0f
        private var holdAnim: ValueAnimator? = null
        private var x0 = 0f; private var y0 = 0f; private var mode = 0; private var pos0 = 0f
        private var downOnCenter = false
        private var vt: VelocityTracker? = null
        private var sent = ""; private var sentA = 0f

        private fun items() = gr[tab].second
        private fun pitch() = 70 * d
        private val stepRad = 0.46f
        private fun radius() = pitch() / stepRad

        fun prepare(t: String, g: List<Pair<String, List<Item>>>) { title = t; gr = g; tab = 0; tabX = 0f; pos = 0f; lastDetent = 0; invalidate() }

        override fun frame(dt: Float) {
            scan = (scan + dt * 0.5f) % 1f
            if (sentA > 0f) sentA = (sentA - dt * 0.7f).coerceAtLeast(0f)
        }

        private fun centerY() = height * 0.56f
        private fun mod(i: Int, n: Int) = ((i % n) + n) % n

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            fill.shader = null
            fill.color = bgc; c.drawRect(0f, 0f, w, h, fill)
            line.color = faded(accent, 14); line.strokeWidth = 1f
            val g = 38 * d
            var gy = g; while (gy < h) { c.drawLine(0f, gy, w, gy, line); gy += g }
            var gx = g; while (gx < w) { c.drawLine(gx, 0f, gx, h, line); gx += g }
            // scan line
            fill.shader = LinearGradient(0f, scan * h - 30 * d, 0f, scan * h + 30 * d, intArrayOf(faded(accent, 0), faded(accent, 38), faded(accent, 0)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, scan * h - 30 * d, w, scan * h + 30 * d, fill); fill.shader = null

            // header
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.LEFT
            txt.textSize = 11 * d; txt.letterSpacing = 0.25f; txt.color = accent
            c.drawText("◢ " + title, 24 * d, 38 * d, txt)
            txt.textAlign = Paint.Align.RIGHT; txt.textSize = 9 * d; txt.color = dim; txt.letterSpacing = 0.15f
            c.drawText("SWIPE UP TO CLOSE", w - 24 * d, 38 * d, txt)
            // tabs
            val tw = (w - 48 * d) / gr.size
            for (i in gr.indices) {
                val on = i == tab
                txt.textAlign = Paint.Align.CENTER; txt.textSize = (if (gr.size > 4) 10 else 11) * d; txt.letterSpacing = if (gr.size > 4) 0.08f else 0.2f
                txt.color = if (on) accent else dim
                c.drawText(gr[i].first, 24 * d + tw * (i + 0.5f), 80 * d, txt)
            }
            val target = 24 * d + tw * tab
            tabX += (target - tabX) * 0.35f
            fill.shader = LinearGradient(tabX, 0f, tabX + tw, 0f, intArrayOf(faded(accent, 0), accent, faded(accent, 0)), null, Shader.TileMode.CLAMP)
            c.drawRect(tabX, 90 * d, tabX + tw, 92.5f * d, fill); fill.shader = null
            if (abs(target - tabX) > 0.5f) invalidate()

            // the drum
            val list = items(); val n = list.size
            val cy = centerY(); val R = radius()
            val b = floor(pos + 0.5f).toInt()
            // centre band with brackets
            val bandH = 62 * d
            line.color = faded(accent, 70); line.strokeWidth = 1f * d
            c.drawLine(24 * d, cy - bandH / 2, w - 24 * d, cy - bandH / 2, line)
            c.drawLine(24 * d, cy + bandH / 2, w - 24 * d, cy + bandH / 2, line)
            line.color = accent; line.strokeWidth = 2f * d; line.strokeCap = Paint.Cap.SQUARE
            val bl = 14 * d; val bx = 20 * d
            c.drawLine(bx, cy - bandH / 2 - 4 * d + bl, bx, cy - bandH / 2 - 4 * d, line); c.drawLine(bx, cy - bandH / 2 - 4 * d, bx + bl, cy - bandH / 2 - 4 * d, line)
            c.drawLine(w - bx, cy - bandH / 2 - 4 * d + bl, w - bx, cy - bandH / 2 - 4 * d, line); c.drawLine(w - bx - bl, cy - bandH / 2 - 4 * d, w - bx, cy - bandH / 2 - 4 * d, line)
            c.drawLine(bx, cy + bandH / 2 + 4 * d - bl, bx, cy + bandH / 2 + 4 * d, line); c.drawLine(bx, cy + bandH / 2 + 4 * d, bx + bl, cy + bandH / 2 + 4 * d, line)
            c.drawLine(w - bx, cy + bandH / 2 + 4 * d - bl, w - bx, cy + bandH / 2 + 4 * d, line); c.drawLine(w - bx - bl, cy + bandH / 2 + 4 * d, w - bx, cy + bandH / 2 + 4 * d, line)
            line.strokeCap = Paint.Cap.BUTT

            for (k in -4..4) {
                val idx = b + k
                if (idx < 0 || idx >= n) continue
                val off = idx - pos
                val th = off * stepRad
                if (abs(th) > 1.35f) continue
                val sc = cos(th)
                val y = cy + R * sin(th)
                val a = (255 * (sc * sc)).toInt().coerceIn(0, 255)
                val it = list[idx]
                val on = abs(off) < 0.5f
                val danger = it.risky
                val x = 24 * d + 36 * d * sc + 34 * d
                drawHexIcon(c, it, x, y, 26 * d * (0.55f + 0.45f * sc), on, a, hexP, line, hfill, ic, danger)
                txt.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); txt.textAlign = Paint.Align.LEFT
                txt.letterSpacing = 0.04f; txt.textSize = 25 * d * (0.6f + 0.4f * sc)
                txt.color = faded(if (on && danger) warn else textMain, a)
                c.drawText(it.label, x + 40 * d * sc, y + 8 * d * sc, txt)
                if (on && it.risky) {
                    txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textSize = 8 * d; txt.letterSpacing = 0.15f
                    txt.color = faded(warn, 200)
                    c.drawText("HOLD TO CONFIRM", x + 40 * d * sc, y + 24 * d, txt)
                }
            }
            // hold ring around the centre icon
            if (holdP > 0f) {
                val it = list[mod(b, n).coerceIn(0, n - 1)]
                val x = 24 * d + 36 * d + 34 * d
                line.color = faded(warn, 255); line.strokeWidth = 3f * d; line.strokeCap = Paint.Cap.ROUND
                oval.set(x - 32 * d, cy - 32 * d, x + 32 * d, cy + 32 * d)
                c.drawArc(oval, -90f, 360f * holdP, false, line); line.strokeCap = Paint.Cap.BUTT
                if (it.label.isEmpty()) return
            }
            // sent note
            if (sentA > 0f) {
                txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.CENTER
                txt.textSize = 12 * d; txt.letterSpacing = 0.2f; txt.color = faded(accent, (255 * sentA).toInt())
                c.drawText(sent, w / 2f, h - 40 * d, txt)
            } else {
                txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.CENTER
                txt.textSize = 9 * d; txt.letterSpacing = 0.15f; txt.color = faded(dim, 170)
                c.drawText("SPIN ↕   ·   SWIPE ↔ FOR OTHER GROUPS   ·   TAP THE GLOWING ONE", w / 2f, h - 40 * d, txt)
            }
        }

        private fun snapTo(target: Float) {
            val t = target.coerceIn(0f, (items().size - 1).toFloat())
            posAnim?.cancel()
            posAnim = ValueAnimator.ofFloat(pos, t).apply {
                duration = 360; interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { pos = it.animatedValue as Float; detent(); invalidate() }
                start()
            }
        }

        private fun detent() {
            val b = floor(pos + 0.5f).toInt()
            if (b != lastDetent) { lastDetent = b; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }

        private fun setTab(t: Int) {
            val nt = t.coerceIn(0, gr.size - 1)
            if (nt == tab) return
            tab = nt; pos = 0f; lastDetent = 0
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
        }

        private fun centerItem(): Item = items()[floor(pos + 0.5f).toInt().coerceIn(0, items().size - 1)]

        private fun send(it: Item) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            val res = it.run
            if (res != null) {
                sent = try { "▶ " + res().take(46) } catch (e: Exception) { "FAILED: " + (e.message ?: "").take(30) }
            } else {
                val ok = it.tile?.let { t -> findAndClick(act.window.decorView, t) } ?: false
                sent = if (ok) "▶ " + it.label + " SENT" else "NOT IN THIS VERSION"
            }
            sentA = 1f
            invalidate()
        }

        private fun startHold() {
            holdAnim?.cancel()
            holdAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900; interpolator = LinearInterpolator()
                addUpdateListener { holdP = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { if (holdP >= 1f) { holdP = 0f; send(centerItem()) } }
                })
                start()
            }
        }

        private fun cancelHold() { holdAnim?.cancel(); holdP = 0f; invalidate() }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; mode = 0; pos0 = pos
                    posAnim?.cancel()
                    vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                    downOnCenter = abs(e.y - centerY()) < 34 * d
                    if (downOnCenter && centerItem().risky) startHold()
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.x - x0; val dy = e.y - y0
                    if (mode == 0) {
                        if (abs(dy) > slop && abs(dy) > abs(dx)) { mode = 1; cancelHold() }
                        else if (abs(dx) > slop * 2 && abs(dx) > abs(dy)) { mode = 2; cancelHold() }
                    }
                    if (mode == 1) { pos = (pos0 - dy / pitch()).coerceIn(-0.4f, items().size - 0.6f); detent(); invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    vt?.addMovement(e)
                    val dx = e.x - x0; val dy = e.y - y0
                    when (mode) {
                        1 -> {
                            vt?.computeCurrentVelocity(1000)
                            val vy = vt?.yVelocity ?: 0f
                            if (dy < -dp(150f) && vy < -1500f && pos0 < 0.6f && pos < 0.6f) closeDeck()
                            else snapTo(Math.round(pos - vy / pitch() * 0.2f).toFloat())
                        }
                        2 -> setTab(if (dx < 0) tab + 1 else tab - 1)
                        else -> {
                            cancelHold()
                            val ty = e.y
                            if (ty < 100 * d) {
                                if (ty < 55 * d && e.x > width - 90 * d) closeDeck()
                                else { val tw = (width - 48 * d) / gr.size; setTab(((e.x - 24 * d) / tw).toInt()) }
                            } else if (downOnCenter) {
                                if (!centerItem().risky) send(centerItem())
                            } else {
                                val off = (ty - centerY()) / (pitch())
                                snapTo(Math.round(pos + off).toFloat())
                            }
                        }
                    }
                    vt?.recycle(); vt = null
                }
                MotionEvent.ACTION_CANCEL -> { cancelHold(); vt?.recycle(); vt = null; snapTo(Math.round(pos).toFloat()) }
            }
            return true
        }
    }

    // ================================================================== INBOX (the notifications stored on the laptop)

    private inner class InboxPanel(ctx: Context) : FrameLayout(ctx) {
        private val d = resources.displayMetrics.density
        private val all = ArrayList<org.json.JSONObject>()
        private val shown = ArrayList<org.json.JSONObject>()
        private var savedOnly = false
        private val status = TextView(ctx)
        private val tabAll = TextView(ctx)
        private val tabSaved = TextView(ctx)
        private val listV = android.widget.ListView(ctx)
        private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        private val fmt = java.text.SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault())

        private fun label(t: TextView, s: String, size: Float, col: Int) {
            t.text = s; t.textSize = size; t.setTextColor(col); t.typeface = mono; t.letterSpacing = 0.15f
        }

        private val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, old: View?, parent: ViewGroup?): View {
                val e = shown[i]
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((20 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
                }
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val ts = e.optDouble("ts", 0.0)
                val top = TextView(context)
                label(top, e.optString("app", "?").uppercase() + (if (ts > 0) "   " + fmt.format(java.util.Date((ts * 1000).toLong())) else ""), 10f, accent)
                val title = TextView(context).apply {
                    text = e.optString("title", ""); textSize = 16f; setTextColor(textMain)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2
                }
                val body = TextView(context).apply {
                    text = e.optString("text", ""); textSize = 13f; setTextColor(dim); maxLines = 4
                }
                col.addView(top); col.addView(title); col.addView(body)
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                val saved = e.optBoolean("saved", false)
                val star = TextView(context).apply {
                    text = if (saved) "\u2605" else "\u2606"; textSize = 26f
                    setTextColor(if (saved) warn else dim); gravity = Gravity.CENTER
                    setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
                    setOnClickListener { toggleSaved(e) }
                }
                row.addView(star, LinearLayout.LayoutParams(-2, -1))
                val sep = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                sep.addView(row)
                sep.addView(View(context).apply { setBackgroundColor(edge) }, LinearLayout.LayoutParams(-1, 1))
                return sep
            }
        }

        init {
            setBackgroundColor(bgc)
            isClickable = true
            val v = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (30 * d).toInt(), 0, 0) }
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (6 * d).toInt()) }
            val title = TextView(ctx); label(title, "\u25E2 LAPTOP INBOX", 12f, accent)
            val refresh = TextView(ctx); label(refresh, "REFRESH", 10f, accent)
            refresh.setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt()); refresh.setOnClickListener { load() }
            val close = TextView(ctx); label(close, "CLOSE \u2715", 10f, dim)
            close.setPadding((10 * d).toInt(), (8 * d).toInt(), 0, (8 * d).toInt()); close.setOnClickListener { closeInbox() }
            head.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(refresh); head.addView(close)
            v.addView(head)
            val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (4 * d).toInt()) }
            tabAll.setOnClickListener { savedOnly = false; refilter() }
            tabSaved.setOnClickListener { savedOnly = true; refilter() }
            tabAll.setPadding(0, (8 * d).toInt(), (22 * d).toInt(), (8 * d).toInt())
            tabSaved.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            tabs.addView(tabAll); tabs.addView(tabSaved)
            v.addView(tabs)
            label(status, "", 9f, dim); status.setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (8 * d).toInt())
            v.addView(status)
            listV.divider = null; listV.adapter = adapter; listV.setBackgroundColor(Color.TRANSPARENT)
            v.addView(listV, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(v, LayoutParams(-1, -1))
            refilter()
        }

        private fun tabs() {
            label(tabAll, "ALL", 12f, if (!savedOnly) accent else dim)
            label(tabSaved, "SAVED \u2605", 12f, if (savedOnly) warn else dim)
        }

        private fun refilter() {
            shown.clear()
            for (e in all) if (!savedOnly || e.optBoolean("saved", false)) shown.add(e)
            tabs(); adapter.notifyDataSetChanged()
        }

        fun load() {
            label(status, "LOADING FROM THE LAPTOP...", 9f, dim)
            Thread {
                val r = LaptopApi.call(act, "GET", "/phone_notifications?limit=150", null, 6000)
                act.runOnUiThread {
                    val err = r.optString("error", "")
                    if (err.isNotEmpty()) { label(status, err.uppercase(), 9f, warn); return@runOnUiThread }
                    all.clear()
                    val arr = r.optJSONArray("items")
                    if (arr != null) for (i in 0 until arr.length()) all.add(arr.getJSONObject(i))
                    label(status, r.optInt("total").toString() + " STORED  \u00B7  " + r.optInt("unread") + " UNREAD  \u00B7  " + r.optInt("saved") + " SAVED", 9f, dim)
                    refilter()
                }
            }.start()
        }

        private fun toggleSaved(e: org.json.JSONObject) {
            val now = !e.optBoolean("saved", false)
            e.put("saved", now)
            refilter()
            val id = e.optInt("id", -1)
            Thread { LaptopApi.call(act, "POST", "/phone_notifications/save", org.json.JSONObject().put("id", id).put("saved", now), 6000) }.start()
        }
    }

    companion object {
        private const val DIAL_STEP = 0.42f
        private val BURST_ANG = intArrayOf(215, 270, 325)
        private var instance: HoloHome? = null

        /** Call once after setContentView(). Returns true when the new Home was installed (needs the HUD Home page). */
        @JvmStatic
        fun attach(act: Activity): Boolean {
            if (instance?.act === act) return true
            val id = act.resources.getIdentifier("pageHome", "id", act.packageName)
            val page = if (id != 0) act.findViewById<View>(id) else null
            if (page == null) return false
            val keep = instance?.act?.isChangingConfigurations == true
            val was = instance?.launcher ?: false
            val h = HoloHome(act, page)
            h.build()
            h.setLauncher(if (keep) was else isHomeIntent(act.intent))
            instance = h
            AppDrawer.hideHandle(act)
            return true
        }

        private fun isHomeIntent(i: android.content.Intent?): Boolean =
            i != null && i.hasCategory(android.content.Intent.CATEGORY_HOME)

        /** A new intent arrived: the Home button means launcher look, the app icon means the normal app. */
        @JvmStatic
        fun onHome(act: Activity, intent: android.content.Intent?) {
            val h = instance?.takeIf { it.act === act } ?: return
            h.stage.closeBurst(); h.closeDeck()
            h.setLauncher(isHomeIntent(intent))
        }

        /** Shows the launcher look inside the app (for a preview). */
        @JvmStatic
        fun showLauncher(act: Activity) { instance?.takeIf { it.act === act }?.setLauncher(true) }

        /** Opens the phone's "default home app" picker. */
        @JvmStatic
        fun chooseHomeApp(act: Activity) {
            try { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
            catch (e: Exception) {
                try { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                catch (e2: Exception) { Toast.makeText(act, "Settings > Apps > Default apps > Home app", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
