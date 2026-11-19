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

    class Item(val label: String, val icon: String, val tile: String?, val risky: Boolean = false)

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

    private val burst = listOf(
        Item("SERVER ON", "bolt", "SERVER ON"), Item("SERVER OFF", "stop", "SERVER OFF"),
        Item("FIND LAPTOP", "radar", "FIND LAPTOP"), Item("PAIR", "link", "PAIR"),
        Item("REMOTE", "remote", "REMOTE"), Item("SHARE", "share", "SHARE SCREEN")
    )
    private val dial = listOf(
        Item("APPS", "apps", null), Item("LAPTOP DECK", "laptop", null),
        Item("LAPTOP STATUS", "bars", "LAPTOP STATUS"), Item("PASTE TO LAPTOP", "paste", "PASTE TO LAPTOP"),
        Item("MIC ACCESS", "mic", "MIC ACCESS"), Item("NOTIFICATIONS", "bell", "NOTIF ACCESS"),
        Item("THEME", "theme", "THEME"), Item("SETUP", "sliders", "SETUP")
    )
    private val groups = listOf(
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
        content.addView(root, rootLp)

        (act as? ComponentActivity)?.let { ca ->
            val cb = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (AppDrawer.closeIfOpen(act)) return
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
        if (!launcher) { stage.closeBurst(); if (deckOpen) closeDeck() }
        rootLp.bottomMargin = if (launcher) 0 else navHeight
        root.layoutParams = rootLp
        navView?.visibility = if (launcher) View.GONE else View.VISIBLE
        updateBack()
        if (launcher) root.bringToFront()
    }

    private fun updateBack() { backCb?.isEnabled = launcher || deckOpen || stage.burstOpen }

    fun setLauncher(on: Boolean) {
        if (launcher == on) return
        launcher = on
        applyMode()
    }

    fun openDeck() {
        if (deckOpen) return
        deckOpen = true
        deck.visibility = View.VISIBLE
        deck.prepare()
        deck.translationY = -root.height.toFloat()
        deck.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
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
            "APPS" -> AppDrawer.open(act)
            "LAPTOP DECK" -> openDeck()
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
        private val chipR = RectF()
        fun readSwitches() { try { sv = serverOn(); mc = micOn(); nt = notifOn() } catch (e: Exception) { } }
        private fun chipTop() = (cy + cr + dTop - 34 * d) / 2f - 25 * d
        private fun chipLeft(i: Int): Float { val w = (width - 40 * d - 16 * d) / 3f; return 20 * d + i * (w + 8 * d) }
        private fun chipW() = (width - 40 * d - 16 * d) / 3f
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
            drawBurst(c)
            drawChips(c, calm)
            drawDial(c, 1f - 0.75f * burstP)

            // chip to go to the normal app
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.RIGHT
            txt.textSize = 10 * d; txt.letterSpacing = 0.18f; txt.color = faded(accent, (230 * calm).toInt())
            c.drawText("JARVIS APP \u25B8", w - 18 * d, 30 * d, txt)
            // hint + toast
            txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textSize = 9 * d; txt.letterSpacing = 0.18f
            txt.textAlign = Paint.Align.CENTER
            txt.color = faded(dim, (150 * calm).toInt())
            c.drawText("↑ APPS    ·    ↓ DECK    ·    TAP THE CORE", cx, h - 16 * d, txt)
            if (toastA > 0f) {
                txt.textSize = 12 * d; txt.letterSpacing = 0.2f; txt.color = faded(accent, (255 * toastA).toInt())
                c.drawText(toast, cx, cy + cr + 30 * d, txt)
            }
        }

        private fun drawChips(c: Canvas, a: Float) {
            if (a <= 0.02f) return
            val names = arrayOf("SERVER", "MIC", "NOTIFS"); val on = booleanArrayOf(sv, mc, nt)
            for (i in 0..2) {
                val l = chipLeft(i); val t = chipTop()
                chipR.set(l, t, l + chipW(), t + 50 * d)
                val col = if (on[i]) okCol else warn
                hfill.color = faded(col, (30 * a).toInt()); c.drawRoundRect(chipR, 14 * d, 14 * d, hfill)
                line.color = faded(if (on[i]) col else edge, (200 * a).toInt()); line.strokeWidth = 1.3f * d
                c.drawRoundRect(chipR, 14 * d, 14 * d, line)
                txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textAlign = Paint.Align.CENTER
                txt.textSize = 9 * d; txt.letterSpacing = 0.2f; txt.color = faded(dim, (230 * a).toInt())
                c.drawText(names[i], l + chipW() / 2, t + 20 * d, txt)
                txt.textSize = 15 * d; txt.letterSpacing = 0.1f; txt.color = faded(col, (255 * a).toInt())
                c.drawText(if (on[i]) "ON" else "OFF", l + chipW() / 2, t + 40 * d, txt)
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
            val rr = (cr + 54 * d) * p
            return floatArrayOf((cx + rr * cos(ang)).toFloat(), (cy + rr * sin(ang)).toFloat())
        }

        private fun drawBurst(c: Canvas) {
            if (burstP <= 0.01f) return
            val p = burstP
            for (i in burst.indices) {
                val pt = burstPos(i, p)
                line.color = faded(accent, (90 * p).toInt()); line.strokeWidth = 1f * d
                c.drawLine(cx, cy, pt[0], pt[1], line)
                drawHexIcon(c, burst[i], pt[0], pt[1], 25 * d * (0.4f + 0.6f * p), true, (255 * p).toInt().coerceIn(0, 255), hexP, line, hfill, ic,
                    burst[i].label == "SERVER OFF")
                txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); txt.textSize = 8 * d; txt.letterSpacing = 0.12f
                txt.textAlign = Paint.Align.CENTER; txt.color = faded(textMain, (230 * p).toInt())
                c.drawText(burst[i].label, pt[0], pt[1] + 25 * d + 13 * d, txt)
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
                drawHexIcon(c, item, x, y, 34 * d * sc, dist < 0.5f, a, hexP, line, hfill, ic)
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
                    if (hypot(x - pt[0], y - pt[1]) < 34 * d) { closeBurst(); fire(burst[i]); return }
                }
                closeBurst(); return
            }
            if (y > chipTop() && y < chipTop() + 50 * d) {
                for (i in 0..2) if (x >= chipLeft(i) && x <= chipLeft(i) + chipW()) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    when (i) { 0 -> toggleServer(); 1 -> toggleMic(); else -> toggleNotif() }
                    return
                }
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

        private fun items() = groups[tab].second
        private fun pitch() = 70 * d
        private val stepRad = 0.46f
        private fun radius() = pitch() / stepRad

        fun prepare() { tab = 0; tabX = 0f; pos = 0f; lastDetent = 0; invalidate() }

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
            c.drawText("◢ LAPTOP DECK", 24 * d, 38 * d, txt)
            txt.textAlign = Paint.Align.RIGHT; txt.textSize = 9 * d; txt.color = dim; txt.letterSpacing = 0.15f
            c.drawText("SWIPE UP TO CLOSE", w - 24 * d, 38 * d, txt)
            // tabs
            val tw = (w - 48 * d) / groups.size
            for (i in groups.indices) {
                val on = i == tab
                txt.textAlign = Paint.Align.CENTER; txt.textSize = 11 * d; txt.letterSpacing = 0.2f
                txt.color = if (on) accent else dim
                c.drawText(groups[i].first, 24 * d + tw * (i + 0.5f), 80 * d, txt)
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
            val nt = t.coerceIn(0, groups.size - 1)
            if (nt == tab) return
            tab = nt; pos = 0f; lastDetent = 0
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
        }

        private fun centerItem(): Item = items()[floor(pos + 0.5f).toInt().coerceIn(0, items().size - 1)]

        private fun send(it: Item) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            val ok = it.tile?.let { t -> findAndClick(act.window.decorView, t) } ?: false
            sent = if (ok) "▶ " + it.label + " SENT" else "NOT IN THIS VERSION"
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
                                else { val tw = (width - 48 * d) / groups.size; setTab(((e.x - 24 * d) / tw).toInt()) }
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

    companion object {
        private const val DIAL_STEP = 0.42f
        private val BURST_ANG = intArrayOf(180, 218, 256, 284, 322, 360)
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
