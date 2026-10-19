package com.jarvis.control

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The dial screen: time in a glowing dial, eight quick features on the ring, a dock at the bottom. */
class ThemeDialView(ctx: Context) : View(ctx) {

    companion object {
        const val NONE = -1
        const val CENTER = -2
        const val DOCK = 100
    }

    private val d = resources.displayMetrics.density
    private val cyan = 0xFF4FE3FF.toInt()
    private val blue = 0xFF2F7BFF.toInt()
    private val coral = 0xFFFF7A5C.toInt()
    private val dim = 0xFF6F8B93.toInt()
    private val white = 0xFFEAF7FB.toInt()
    private val ringLabels = arrayOf("LOCK", "WI-FI", "CHAT", "STUDY", "RECORD", "REVIEW", "REMOTE", "FIND")
    private val ringGlyphs = arrayOf(
        "🔒", "📶", "💬", "📚",
        "🎙", "📊", "🖥", "📍"
    )
    private val dockGlyphs = arrayOf(
        "📞", "🎤", "🌐", "📷", "💬"
    )

    var onRing: ((Int) -> Unit)? = null
    var onDock: ((Int) -> Unit)? = null
    var onCenter: (() -> Unit)? = null
    var laptopLine = "LAPTOP  checking..."
    var stats = intArrayOf(-1, -1, -1) // cpu, ram, battery; -1 = unknown

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var spin = 0f
    private var longPressed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val dayFmt = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())

    private val spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 50000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            spin = it.animatedValue as Float
            invalidate()
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) {
            if (hit(e.x, e.y) == CENTER) {
                longPressed = true
                performLongClick()   // the apps drawer (HomeDrawer) listens for this
            }
        }
    })

    init {
        isClickable = true
        isLongClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f
        cy = h * 0.46f
        radius = min(w * 0.40f, h * 0.25f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spinAnim.start()
    }

    override fun onDetachedFromWindow() {
        spinAnim.cancel()
        super.onDetachedFromWindow()
    }

    private fun dockX(j: Int): Float = width * (0.14f + j * 0.18f)
    private fun dockY(): Float = height - 66f * d
    private fun ringX(i: Int): Float = cx + radius * 0.80f * cos(Math.toRadians(-90.0 + i * 45.0)).toFloat()
    private fun ringY(i: Int): Float = cy + radius * 0.80f * sin(Math.toRadians(-90.0 + i * 45.0)).toFloat()
    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun hit(x: Float, y: Float): Int {
        for (j in dockGlyphs.indices) {
            if (dist(x, y, dockX(j), dockY()) < 34f * d) return DOCK + j
        }
        for (i in ringGlyphs.indices) {
            if (dist(x, y, ringX(i), ringY(i)) < 30f * d) return i
        }
        if (dist(x, y, cx, cy) < radius * 0.56f) return CENTER
        return NONE
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> longPressed = false
            MotionEvent.ACTION_UP -> {
                if (!longPressed) {
                    val h = hit(e.x, e.y)
                    when {
                        h == CENTER -> onCenter?.invoke()
                        h >= DOCK -> onDock?.invoke(h - DOCK)
                        h >= 0 -> onRing?.invoke(h)
                    }
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // background: dark gradient + faint floor grid
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF03080D.toInt(), 0xFF071B27.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * d
        paint.color = Color.argb(34, 79, 227, 255)
        var gx = 0f
        while (gx <= w) {
            c.drawLine(gx, h * 0.6f, gx, h, paint)
            gx += 40f * d
        }
        var gy = h * 0.6f
        while (gy <= h) {
            c.drawLine(0f, gy, w, gy, paint)
            gy += 40f * d
        }

        // 60 ticks around the dial
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90.0)
            val major = i % 5 == 0
            val r1 = radius + 14f * d
            val r2 = radius + (if (major) 28f else 20f) * d
            paint.strokeWidth = (if (major) 2.5f else 1.2f) * d
            paint.color = Color.argb(if (major) 220 else 90, 79, 227, 255)
            c.drawLine(
                cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), paint
            )
        }

        // spinning neon arcs (outer ring turns one way, inner ring the other)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 4f * d
        rect.set(cx - radius - 40f * d, cy - radius - 40f * d, cx + radius + 40f * d, cy + radius + 40f * d)
        c.save()
        c.rotate(spin, cx, cy)
        paint.setShadowLayer(12f * d, 0f, 0f, cyan)
        paint.color = cyan
        c.drawArc(rect, 0f, 80f, false, paint)
        paint.color = blue
        c.drawArc(rect, 150f, 40f, false, paint)
        paint.color = cyan
        c.drawArc(rect, 240f, 110f, false, paint)
        paint.clearShadowLayer()
        c.restore()

        val inner = RectF(cx - radius - 28f * d, cy - radius - 28f * d, cx + radius + 28f * d, cy + radius + 28f * d)
        c.save()
        c.rotate(-spin * 0.6f, cx, cy)
        paint.strokeWidth = 6f * d
        paint.color = coral
        c.drawArc(inner, 20f, 60f, false, paint)
        c.drawArc(inner, 200f, 30f, false, paint)
        c.restore()

        // inner ring + glowing core
        paint.strokeWidth = 2f * d
        paint.color = Color.argb(170, 79, 227, 255)
        c.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 0.58f,
            intArrayOf(0xFF0E2D3A.toInt(), 0xFF03101A.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, radius * 0.56f, paint)
        paint.shader = null

        // big clock in the middle
        val cal = Calendar.getInstance()
        val hour12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val hm = String.format(Locale.US, "%02d:%02d", hour12, cal.get(Calendar.MINUTE))
        val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.color = white
        text.textSize = radius * 0.36f
        c.drawText(hm, cx, cy + radius * 0.12f, text)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 13f * d
        text.color = cyan
        c.drawText(ampm, cx, cy + radius * 0.12f + 26f * d, text)

        // eight features on the ring
        for (i in ringGlyphs.indices) {
            val x = ringX(i)
            val y = ringY(i)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF0B2430.toInt()
            c.drawCircle(x, y, 26f * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.color = cyan
            c.drawCircle(x, y, 26f * d, paint)
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.textSize = 20f * d
            text.color = white
            c.drawText(ringGlyphs[i], x, y + 7f * d, text)
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = 8f * d
            text.color = dim
            c.drawText(ringLabels[i], x, y + 42f * d, text)
        }

        // top bar: day on the left, laptop status on the right, stats below
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 15f * d
        text.color = cyan
        c.drawText(dayFmt.format(cal.time).uppercase(Locale.getDefault()), 22f * d, 40f * d, text)
        text.textAlign = Paint.Align.RIGHT
        text.typeface = Typeface.DEFAULT
        text.textSize = 11f * d
        text.color = dim
        c.drawText(laptopLine, w - 22f * d, 40f * d, text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * d
        val names = arrayOf("CPU", "RAM", "BAT")
        for (k in 0 until 3) {
            val v = stats[k]
            text.color = if (v < 0) dim else white
            val shown = if (v < 0) "--" else "$v%"
            c.drawText("${names[k]} $shown", (22f + k * 120f) * d, 66f * d, text)
        }

        // dock
        paint.style = Paint.Style.FILL
        paint.color = 0xCC0B1A22.toInt()
        rect.set(18f * d, h - 98f * d, w - 18f * d, h - 34f * d)
        c.drawRoundRect(rect, 32f * d, 32f * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = 0x663FA3C4.toInt()
        c.drawRoundRect(rect, 32f * d, 32f * d, paint)
        for (j in dockGlyphs.indices) {
            val x = dockX(j)
            val y = dockY()
            paint.style = Paint.Style.FILL
            paint.color = 0xFF0F2A36.toInt()
            c.drawCircle(x, y, 24f * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = cyan
            c.drawCircle(x, y, 24f * d, paint)
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.textSize = 20f * d
            text.color = white
            c.drawText(dockGlyphs[j], x, y + 7f * d, text)
        }

        postInvalidateDelayed(1000L) // keeps the clock moving
    }
}

/** Home screen with the dial. Tap the centre to ask Jarvis, hold it for all phone apps, Back does nothing here. */
class ThemeHomeActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var dial: ThemeDialView

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send(it) }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            refreshStats()
            ui.postDelayed(this, 45_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF03080D.toInt()
        window.navigationBarColor = 0xFF03080D.toInt()
        dial = ThemeDialView(this)
        setContentView(dial)

        dial.onRing = { i ->
            when (i) {
                0 -> send("lock")
                1 -> send("wifi devices")
                2 -> send("chat mode flirty")
                3 -> send("study mode")
                4 -> send("record notes")
                5 -> send("weekly review")
                6 -> openApp(Intent(this, RemoteActivity::class.java))
                7 -> send("find my phone")
            }
        }
        dial.onDock = { j ->
            when (j) {
                0 -> openApp(Intent(Intent.ACTION_DIAL))
                1 -> listen()
                2 -> openApp(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
                3 -> openApp(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                4 -> openApp(Intent(Intent.ACTION_VIEW, Uri.parse("sms:")))
            }
        }
        dial.onCenter = { askDialog() }

        // Back on the home screen never leaves it
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        HomeDrawer.attach(this, dial)   // hold the centre = all phone apps
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        ui.removeCallbacks(poll)
        ui.postDelayed(poll, 45_000L)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

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

    private fun send(text: String) {
        if (!LaptopApi.paired(this)) {
            toast("Link your laptop first (open Control)")
            return
        }
        toast("Sent: $text")
        Thread {
            val r = LaptopApi.call(this, "POST", "/command", JSONObject().put("command", text), 70000)
            val msg = if (r.has("error")) "⚠ " + r.optString("error") else r.optString("response", "(no reply)")
            ui.post { Toast.makeText(this, msg.take(200), Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun refreshStats() {
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { i ->
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        } ?: -1
        dial.stats = intArrayOf(-1, -1, batt)
        if (!LaptopApi.paired(this)) {
            dial.laptopLine = "LAPTOP NOT LINKED"
            dial.invalidate()
            return
        }
        Thread {
            val r = LaptopApi.call(this, "GET", "/remote/glance", null, 3000)
            val ok = !r.has("error")
            ui.post {
                dial.laptopLine = if (ok) "● LAPTOP ONLINE" else "○ LAPTOP OFFLINE"
                dial.stats = intArrayOf(
                    if (ok) r.optInt("cpu_pct", -1) else -1,
                    if (ok) r.optInt("ram_pct", -1) else -1,
                    batt
                )
                dial.invalidate()
            }
        }.start()
    }
}
