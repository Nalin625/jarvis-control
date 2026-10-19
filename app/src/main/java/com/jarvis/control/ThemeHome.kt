package com.jarvis.control

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val C_CYAN = 0xFF4FE3FF.toInt()
private val C_GOLD = 0xFFFFBE46.toInt()
private val C_GREEN = 0xFF78FFAA.toInt()
private val C_CORAL = 0xFFFF7A5C.toInt()
private val C_DIM = 0xFF6F8B93.toInt()
private val C_WHITE = 0xFFEAF7FB.toInt()
private val C_BG = 0xFF03080D.toInt()

/**
 * The reactor dial. Drag to roll the ring; the item at the top is the selected one.
 * Tap an item to bring it to the top, tap the centre to run the selected item, hold the centre for all apps.
 */
class ThemeDialView(ctx: Context) : View(ctx) {

    var labels: Array<String> = emptyArray()
        set(v) {
            field = v
            invalidate()
        }
    var glyphs: Array<String> = emptyArray()
        set(v) {
            field = v
            invalidate()
        }
    var onActivate: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var itemR = 0f
    private var rot = 0f
    private var bg: Bitmap? = null
    private var anim: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var lastAng = 0f
    private var dragged = false
    private var longFired = false

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) {
            if (dist(e.x, e.y, cx, cy) < radius * 0.42f) {
                longFired = true
                performLongClick()   // HomeDrawer listens for this: opens all apps
            }
        }
    })

    init {
        isClickable = true
        isLongClickable = true
    }

    private fun step(): Float = if (labels.isEmpty()) 45f else 360f / labels.size

    /** Index of the item currently at the top (the selected one). */
    fun selected(): Int {
        val n = labels.size
        if (n == 0) return -1
        val a = ((-rot) % 360f + 360f) % 360f
        return Math.round(a / step()) % n
    }

    private fun snapTo(i: Int) {
        val target = -(i * step())
        val delta = ((target - rot) % 360f + 540f) % 360f - 180f
        val from = rot
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rot = from + delta * (it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f
        cy = h / 2f
        val m = min(w, h).toFloat()
        radius = m * 0.36f
        itemR = m * 0.095f
        buildBackground()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun angle(x: Float, y: Float): Float =
        Math.toDegrees(Math.atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastAng = angle(e.x, e.y)
                dragged = false
                longFired = false
                anim?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged && dist(e.x, e.y, downX, downY) > slop) dragged = true
                if (dragged) {
                    val a = angle(e.x, e.y)
                    var delta = a - lastAng
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    rot += delta
                    lastAng = a
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) snapTo(selected())
                else if (!longFired) tapAt(e.x, e.y)
            }
        }
        return true
    }

    private fun tapAt(x: Float, y: Float) {
        if (dist(x, y, cx, cy) < radius * 0.42f) {
            onActivate?.invoke(selected())
            return
        }
        for (i in labels.indices) {
            val a = Math.toRadians((-90.0 + i * step() + rot))
            val ix = cx + radius * cos(a).toFloat()
            val iy = cy + radius * sin(a).toFloat()
            if (dist(x, y, ix, iy) < itemR * 1.3f) {
                snapTo(i)
                return
            }
        }
    }

    /** Static parts (glow, rings, ticks, core) drawn once per size. */
    private fun buildBackground() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val m = min(w, h).toFloat()

        p.shader = RadialGradient(cx, cy, m * 0.5f,
            intArrayOf(0x3D4FE3FF, 0x004FE3FF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, m * 0.5f, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = 0x4D4FE3FF
        c.drawCircle(cx, cy, radius, p)
        p.color = 0x264FE3FF
        c.drawCircle(cx, cy, radius * 1.25f, p)

        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90.0)
            val r1 = radius * 1.25f
            val r2 = r1 + (if (i % 5 == 0) 12f else 6f) * d
            p.strokeWidth = (if (i % 5 == 0) 2f else 1f) * d
            p.color = if (i % 5 == 0) 0xDD4FE3FF.toInt() else 0x554FE3FF
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), p)
        }
        bg = b
    }

    override fun onDraw(c: Canvas) {
        if (bg == null) buildBackground()
        bg?.let { c.drawBitmap(it, 0f, 0f, null) }
        if (labels.isEmpty()) return

        val sel = selected()
        val s = step()
        for (i in labels.indices) {
            val a = Math.toRadians(-90.0 + i * s + rot)
            val x = cx + radius * cos(a).toFloat()
            val y = cy + radius * sin(a).toFloat()
            val on = i == sel
            paint.style = Paint.Style.FILL
            paint.color = if (on) 0xFF0F3A4C.toInt() else 0xFF071C27.toInt()
            c.drawCircle(x, y, itemR, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (on) 3f else 1.5f) * d
            paint.color = if (on) C_GOLD else C_CYAN
            c.drawCircle(x, y, itemR, paint)
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.textSize = itemR * 0.9f
            text.color = C_WHITE
            c.drawText(glyphs.getOrElse(i) { "" }, x, y + itemR * 0.32f, text)
        }

        // centre: reactor core with the selected item's name
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, radius * 0.42f,
            intArrayOf(0xFF0E2D3A.toInt(), 0xFF03101A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radius * 0.42f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.color = C_CYAN
        c.drawCircle(cx, cy, radius * 0.42f, paint)

        if (sel >= 0) {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.textSize = radius * 0.22f
            text.color = C_WHITE
            c.drawText(glyphs.getOrElse(sel) { "" }, cx, cy + radius * 0.04f, text)
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = radius * 0.11f
            text.color = C_GOLD
            c.drawText(labels[sel], cx, cy + radius * 0.25f, text)
        }
    }
}

/** Home screen. Hold the centre for all apps; tap the centre to run the selected item. Back does nothing here. */
class ThemeHomeActivity : AppCompatActivity() {

    private class Act(val label: String, val glyph: String, val action: () -> Unit)

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val prefs by lazy { getSharedPreferences("jarvis_control", MODE_PRIVATE) }
    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var actions: List<Act>
    private lateinit var dial: ThemeDialView
    private lateinit var headerTv: TextView
    private lateinit var clockTv: TextView
    private lateinit var ampmTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var serverTv: TextView
    private lateinit var micTv: TextView
    private lateinit var notifTv: TextView
    private lateinit var cpuTv: TextView
    private lateinit var ramTv: TextView
    private lateinit var batTv: TextView
    private lateinit var dockRow: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var cardText: TextView

    private var lastReply = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val hideLater = Runnable { hideCard() }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send(it) }
        }
    }

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleMic() else toast("Microphone permission is needed")
    }

    // clock: once every 30 s, nothing in between
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            ui.postDelayed(this, 30_000L)
        }
    }

    // laptop stats: every 10 s while this screen is showing
    private val poll = object : Runnable {
        override fun run() {
            refreshStats()
            ui.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        actions = listOf(
            Act("LOCK", "🔒") { power("lock") },
            Act("SLEEP", "💤") { power("sleep") },
            Act("VOL +", "🔊") { act("/remote/volume", JSONObject().put("delta", 5)) },
            Act("VOL −", "🔉") { act("/remote/volume", JSONObject().put("delta", -5)) },
            Act("MUTE", "🔇") { act("/remote/volume", JSONObject().put("mute", true)) },
            Act("BRIGHT +", "☀") { act("/remote/brightness", JSONObject().put("delta", 10)) },
            Act("BRIGHT −", "🌙") { act("/remote/brightness", JSONObject().put("delta", -10)) },
            Act("NOTE", "📝") { noteDialog() }
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(C_BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(10), dp(14), dp(12))
        root.addView(col, FrameLayout.LayoutParams(matchParent, matchParent))

        headerTv = TextView(this).apply {
            text = "HOME"
            setTextColor(C_CYAN)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        col.addView(headerTv, LinearLayout.LayoutParams(matchParent, wrap))

        // top row: SERVER / MIC / NOTIFS on the left, system info on the right
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        val left = card("SERVER · MIC · NOTIFS")
        serverTv = statRow(left, "Server") { toggleServer() }
        micTv = statRow(left, "Mic") { toggleMic() }
        notifTv = statRow(left, "Notifications") { toggleNotif() }
        val right = card("SYSTEM INFO")
        cpuTv = statRow(right, "CPU (laptop)", null)
        ramTv = statRow(right, "RAM (laptop)", null)
        batTv = statRow(right, "Battery", null)
        topRow.addView(left, LinearLayout.LayoutParams(0, wrap, 1f).apply { setMargins(0, 0, dp(6), 0) })
        topRow.addView(right, LinearLayout.LayoutParams(0, wrap, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        col.addView(topRow, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(8) })

        // clock block
        val clockRow = LinearLayout(this)
        clockRow.orientation = LinearLayout.HORIZONTAL
        clockRow.gravity = Gravity.CENTER
        clockTv = TextView(this).apply {
            textSize = 46f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(C_WHITE)
        }
        ampmTv = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_CYAN)
            setPadding(dp(6), dp(16), 0, 0)
        }
        clockRow.addView(clockTv)
        clockRow.addView(ampmTv)
        col.addView(clockRow, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(10) })
        dateTv = TextView(this).apply {
            textSize = 13f
            setTextColor(C_DIM)
            gravity = Gravity.CENTER
        }
        col.addView(dateTv, LinearLayout.LayoutParams(matchParent, wrap))

        // three small icons: settings, quick action, battery
        val iconRow = LinearLayout(this)
        iconRow.orientation = LinearLayout.HORIZONTAL
        iconRow.gravity = Gravity.CENTER
        iconRow.addView(iconButton("⚙") { startActivity(Intent(this, MainActivity::class.java)) }, circleParams())
        iconRow.addView(iconButton("⚡") { send("click instant") }, circleParams())
        iconRow.addView(iconButton("🔋") { openApp(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }, circleParams())
        col.addView(iconRow, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(8) })

        // the reactor dial takes the middle of the screen
        dial = ThemeDialView(this)
        dial.labels = actions.map { it.label }.toTypedArray()
        dial.glyphs = actions.map { it.glyph }.toTypedArray()
        dial.onActivate = { i -> actions.getOrNull(i)?.action?.invoke() }
        col.addView(dial, LinearLayout.LayoutParams(matchParent, 0, 1f))

        // reply card sits above the dock
        card = buildCard()
        col.addView(card, LinearLayout.LayoutParams(matchParent, wrap).apply { bottomMargin = dp(8) })

        // dock: scrolls sideways, 8 slots
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        dockRow = LinearLayout(this)
        dockRow.orientation = LinearLayout.HORIZONTAL
        scroll.addView(dockRow)
        col.addView(scroll, LinearLayout.LayoutParams(matchParent, wrap))
        buildDock()

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        HomeDrawer.attach(this, dial)
    }

    override fun onResume() {
        super.onResume()
        refreshSwitches()
        updateClock()
        ui.removeCallbacks(clockTick)
        ui.postDelayed(clockTick, 30_000L)
        ui.removeCallbacks(poll)
        refreshStats()
        ui.postDelayed(poll, 10_000L)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        ui.removeCallbacks(clockTick)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun circleParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(8), 0, dp(8), 0) }

    private fun iconButton(glyph: String, onTap: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = glyph
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(C_CYAN)
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF071C27.toInt())
            setStroke(dp(1), C_CYAN)
        }
        tv.setOnClickListener { onTap() }
        return tv
    }

    // ---- cards and rows ----

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(10), dp(8), dp(10), dp(8))
        c.background = GradientDrawable().apply {
            setColor(0xB30A1E2A.toInt())
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x664FE3FF)
        }
        val head = TextView(this)
        head.text = title
        head.textSize = 10f
        head.setTextColor(C_CYAN)
        head.typeface = Typeface.DEFAULT_BOLD
        c.addView(head)
        return c
    }

    /** One label/value line in a card. Returns the value view so the caller can update it. */
    private fun statRow(c: LinearLayout, label: String, onTap: (() -> Unit)?): TextView {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(0, dp(7), 0, dp(7))
        val l = TextView(this)
        l.text = label
        l.textSize = 12f
        l.setTextColor(C_WHITE)
        line.addView(l, LinearLayout.LayoutParams(0, wrap, 1f))
        val v = TextView(this)
        v.textSize = 12f
        v.setTextColor(C_DIM)
        v.typeface = Typeface.DEFAULT_BOLD
        line.addView(v)
        if (onTap != null) line.setOnClickListener { onTap() }
        c.addView(line)
        return v
    }

    private fun setState(tv: TextView, on: Boolean) {
        tv.text = if (on) "ON" else "OFF"
        tv.setTextColor(if (on) C_GREEN else C_CORAL)
    }

    private fun notifAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun refreshSwitches() {
        setState(serverTv, JarvisService.running)
        setState(micTv, prefs.getBoolean("wake_listen", false) && MicService.hasPermission(this))
        setState(notifTv, prefs.getBoolean("notif_forward_enabled", false) && notifAccessGranted())
    }

    // ---- SERVER / MIC / NOTIFS: same logic as the Control screen ----

    private fun toggleServer() {
        if (JarvisService.running) {
            stopService(Intent(this, JarvisService::class.java))
            toast("Server stopped")
        } else {
            val token = prefs.getString("token", "") ?: ""
            if (token.isEmpty()) {
                toast("Open Control once to create the server token")
                return
            }
            ContextCompat.startForegroundService(
                this, Intent(this, JarvisService::class.java).putExtra(JarvisService.ACTION_TOKEN_EXTRA, token)
            )
            toast("Server started")
        }
        ui.postDelayed({ refreshSwitches() }, 600L)
    }

    private fun toggleMic() {
        if (!MicService.hasPermission(this)) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val on = prefs.getBoolean("wake_listen", false)
        if (on) {
            MicService.setListening(this, false)
            toast("Phone mic is off")
        } else {
            MicService.start(this)
            MicService.setListening(this, true)
            toast("Listening for the wake word")
        }
        ui.postDelayed({ refreshSwitches() }, 600L)
    }

    private fun toggleNotif() {
        val granted = notifAccessGranted()
        val on = prefs.getBoolean("notif_forward_enabled", false) && granted
        when {
            on -> {
                prefs.edit().putBoolean("notif_forward_enabled", false).apply()
                toast("Notifications stay on the phone")
            }
            !granted -> {
                toast("Allow Jarvis in the list, then come back")
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            else -> {
                prefs.edit().putBoolean("notif_forward_enabled", true).apply()
                toast("Notifications go to the laptop")
            }
        }
        refreshSwitches()
    }

    // ---- dock ----

    private fun defaultIntent(j: Int): Intent? = when (j) {
        0 -> Intent(Intent.ACTION_DIAL)
        2 -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        3 -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        4 -> Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
        else -> null
    }

    private fun dockIcon(j: Int): Drawable? {
        val pkg = prefs.getString("dock_$j", "") ?: ""
        if (pkg.isNotEmpty()) {
            return try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
        }
        if (j == 1) return ContextCompat.getDrawable(this, android.R.drawable.ic_btn_speak_now)
        val i = defaultIntent(j) ?: return null
        return packageManager.resolveActivity(i, 0)?.loadIcon(packageManager)
    }

    private fun buildDock() {
        dockRow.removeAllViews()
        for (j in 0 until 8) {
            val slot = FrameLayout(this)
            slot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0B2430.toInt())
                setStroke(dp(2), C_CYAN)
            }
            val icon = dockIcon(j)
            if (icon != null) {
                val iv = ImageView(this)
                iv.setImageDrawable(icon)
                iv.setPadding(dp(10), dp(10), dp(10), dp(10))
                slot.addView(iv, FrameLayout.LayoutParams(matchParent, matchParent))
            } else {
                val plus = TextView(this)
                plus.text = "+"
                plus.textSize = 22f
                plus.gravity = Gravity.CENTER
                plus.setTextColor(C_DIM)
                slot.addView(plus, FrameLayout.LayoutParams(matchParent, matchParent))
            }
            slot.setOnClickListener { dockTap(j) }
            slot.setOnLongClickListener {
                pickApp(j)
                true
            }
            dockRow.addView(slot, LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(dp(5), dp(4), dp(5), dp(4)) })
        }
    }

    private fun dockTap(j: Int) {
        val pkg = prefs.getString("dock_$j", "") ?: ""
        if (pkg.isNotEmpty()) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) openApp(launch) else toast("App not found")
            return
        }
        if (j == 1) {
            listen()
            return
        }
        defaultIntent(j)?.let { openApp(it) }
    }

    /** Long-press a dock slot to choose which installed app it opens. */
    private fun pickApp(j: Int) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }
        val names = (listOf("Default for this slot") + apps.map { it.first }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Dock slot ${j + 1}")
            .setItems(names) { _, which ->
                val pkg = if (which == 0) "" else apps[which - 1].second
                prefs.edit().putString("dock_$j", pkg).apply()
                buildDock()
            }
            .show()
    }

    private fun openApp(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Can't open that here")
        }
    }

    private fun listen() {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            )
        } catch (e: Exception) {
            toast("No speech recognizer on this phone")
        }
    }

    // ---- clock and stats ----

    private fun updateClock() {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        clockTv.text = String.format(Locale.US, "%02d:%02d", h, now.get(Calendar.MINUTE))
        ampmTv.text = if (now.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        dateTv.text = SimpleDateFormat("EEE MMMM dd", Locale.getDefault()).format(now.time)
    }

    private fun batteryPct(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
    }

    private fun refreshStats() {
        val b = batteryPct()
        batTv.text = if (b < 0) "--" else "$b%"
        if (!LaptopApi.paired(this)) {
            headerTv.text = "HOME  ·  LAPTOP NOT LINKED"
            cpuTv.text = "--"
            ramTv.text = "--"
            return
        }
        val app = applicationContext
        io.execute {
            val r = LaptopApi.call(app, "GET", "/remote/glance", null, 3000)
            val ok = !r.has("error")
            ui.post {
                headerTv.text = if (ok) "HOME  ·  ● LAPTOP ONLINE" else "HOME  ·  ○ LAPTOP OFFLINE"
                cpuTv.text = if (ok && r.optInt("cpu_pct", -1) >= 0) "${r.optInt("cpu_pct")}%" else "--"
                ramTv.text = if (ok && r.optInt("ram_pct", -1) >= 0) "${r.optInt("ram_pct")}%" else "--"
            }
        }
    }

    // ---- laptop commands and the reply card ----

    private fun power(action: String) = act("/remote/power", JSONObject().put("action", action))

    /** Fire-and-report call: toast on success, reply card on error. */
    private fun act(path: String, body: JSONObject) {
        if (!LaptopApi.paired(this)) {
            showReply("Link your laptop first (open Control)")
            return
        }
        val app = applicationContext
        io.execute {
            val r = LaptopApi.call(app, "POST", path, body, 5000)
            ui.post {
                if (r.has("error")) showReply("⚠ " + r.optString("error")) else toast("✓ Done")
            }
        }
    }

    private fun send(text: String) {
        if (!LaptopApi.paired(this)) {
            showReply("Link your laptop first (open Control)")
            return
        }
        showReply("…")
        val app = applicationContext
        io.execute {
            val r = LaptopApi.call(app, "POST", "/command", JSONObject().put("command", text), 70000)
            val msg = if (r.has("error")) "⚠ " + r.optString("error") else r.optString("response", "(no reply)")
            ui.post { showReply(msg) }
        }
    }

    private fun buildCard(): LinearLayout {
        cardText = TextView(this)
        cardText.setTextColor(C_WHITE)
        cardText.textSize = 14f
        cardText.maxLines = 4
        cardText.ellipsize = TextUtils.TruncateAt.END
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(10), dp(8), dp(10))
        row.background = GradientDrawable().apply {
            setColor(0xEE0B1A22.toInt())
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), C_CYAN)
        }
        row.visibility = View.GONE
        row.addView(cardText, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(iconButtonPlain("🔊") { speak(lastReply) })
        row.addView(iconButtonPlain("✕") { hideCard() })
        return row
    }

    private fun iconButtonPlain(label: String, onTap: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 18f
        tv.setTextColor(C_CYAN)
        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
        tv.setOnClickListener { onTap() }
        return tv
    }

    private fun showReply(msg: String) {
        lastReply = msg
        cardText.text = msg
        card.visibility = View.VISIBLE
        ui.removeCallbacks(hideLater)
        ui.postDelayed(hideLater, 30_000L)
    }

    private fun hideCard() {
        card.visibility = View.GONE
        ui.removeCallbacks(hideLater)
    }

    private fun speak(s: String) {
        if (s.isBlank()) return
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
            }
            return
        }
        if (ttsReady) engine.speak(s, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun askDialog() {
        val input = EditText(this)
        input.hint = "Ask Jarvis"
        AlertDialog.Builder(this)
            .setTitle("ASK JARVIS")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { send(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun noteDialog() {
        val input = EditText(this)
        input.hint = "Note for the laptop"
        AlertDialog.Builder(this)
            .setTitle("SAVE NOTE")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                    act("/remote/note", JSONObject().put("text", it))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
