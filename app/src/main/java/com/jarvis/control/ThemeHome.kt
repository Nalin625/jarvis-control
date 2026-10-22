package com.jarvis.control

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val C_CYAN = 0xFF4FE3FF.toInt()
private val C_BLUE = 0xFF2F7BFF.toInt()
private val C_GOLD = 0xFFFFBE46.toInt()
private val C_GREEN = 0xFF78FFAA.toInt()
private val C_CORAL = 0xFFFF7A5C.toInt()
private val C_DIM = 0xFF8FA6AE.toInt()
private val C_WHITE = 0xFFEAF7FB.toInt()
private val C_BG = 0xFF03080D.toInt()


/** Thin line icons drawn with paths: no emoji, same look on every phone. Drawn on a 24x24 grid. */
object Hud {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.7f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rf = RectF()

    fun draw(c: Canvas, kind: String, left: Float, top: Float, size: Float, color: Int) {
        p.color = color
        fill.color = color
        val k = size / 24f
        c.save()
        c.translate(left, top)
        c.scale(k, k)
        when (kind) {
            "terminal" -> { rr(c, 3f, 4f, 21f, 20f, 2.5f); poly(c, false, 7f, 9f, 10.5f, 12f, 7f, 15f); line(c, 13f, 15f, 17f, 15f) }
            "laptop" -> { rr(c, 5f, 4f, 19f, 16f, 1.5f); line(c, 2f, 20f, 22f, 20f) }
            "note" -> { poly(c, true, 4f, 20f, 5f, 16f, 16f, 5f, 19f, 8f, 8f, 19f); line(c, 14f, 7f, 17f, 10f) }
            "power" -> { rf.set(5f, 5f, 19f, 19f); c.drawArc(rf, -60f, 300f, false, p); line(c, 12f, 3f, 12f, 11f) }
            "play" -> poly(c, true, 8f, 5f, 19f, 12f, 8f, 19f)
            "search" -> { c.drawCircle(11f, 11f, 6.5f, p); line(c, 16f, 16f, 21f, 21f) }
            "lock" -> {
                rr(c, 5f, 11f, 19f, 21f, 2f)
                path.reset()
                path.moveTo(8f, 11f)
                path.lineTo(8f, 7f)
                rf.set(8f, 3f, 16f, 11f)
                path.arcTo(rf, 180f, 180f, false)
                path.lineTo(16f, 11f)
                c.drawPath(path, p)
            }
            "moon" -> { rf.set(4f, 4f, 20f, 20f); c.drawArc(rf, 40f, 280f, false, p) }
            "screen" -> { rr(c, 2f, 4f, 22f, 17f, 2f); line(c, 8f, 21f, 16f, 21f); line(c, 12f, 17f, 12f, 21f) }
            "shot", "camera" -> { rr(c, 3f, 7f, 21f, 20f, 2.5f); c.drawCircle(12f, 13.5f, 3.5f, p); poly(c, false, 8f, 7f, 9.5f, 4f, 14.5f, 4f, 16f, 7f) }
            "flash" -> poly(c, true, 13f, 2f, 4f, 14f, 11f, 14f, 10f, 22f, 20f, 9f, 13f, 9f)
            "wifi" -> {
                for (r in listOf(5f, 10f, 15f)) {
                    rf.set(12f - r, 19f - r, 12f + r, 19f + r)
                    c.drawArc(rf, 225f, 90f, false, p)
                }
                c.drawCircle(12f, 19f, 1.6f, fill)
            }
            "bt" -> poly(c, false, 7f, 7f, 17f, 17f, 12f, 22f, 12f, 2f, 17f, 7f, 7f, 17f)
            "sound" -> {
                poly(c, true, 4f, 9f, 8f, 9f, 13f, 5f, 13f, 19f, 8f, 15f, 4f, 15f)
                rf.set(12f, 8f, 18f, 16f)
                c.drawArc(rf, -55f, 110f, false, p)
            }
            "display" -> { rr(c, 2f, 4f, 22f, 16f, 2f); line(c, 8f, 20f, 16f, 20f); line(c, 12f, 16f, 12f, 20f) }
            "battery" -> { rr(c, 2f, 7f, 19f, 17f, 2f); line(c, 22f, 11f, 22f, 13f) }
            "phone" -> { rr(c, 7f, 2f, 17f, 22f, 3f); line(c, 11f, 18f, 13f, 18f) }
            "files" -> poly(c, true, 3f, 6f, 9f, 6f, 11f, 8f, 21f, 8f, 21f, 18f, 3f, 18f)
            "globe" -> { c.drawCircle(12f, 12f, 9f, p); line(c, 3f, 12f, 21f, 12f); rf.set(7.5f, 3f, 16.5f, 21f); c.drawOval(rf, p) }
            "chat" -> poly(c, true, 4f, 5f, 20f, 5f, 20f, 17f, 10f, 17f, 5f, 21f, 5f, 17f, 4f, 17f)
            "apps" -> {
                for (gx in listOf(5f, 13f)) for (gy in listOf(5f, 13f)) rr(c, gx, gy, gx + 6f, gy + 6f, 1.2f)
            }
            "plus" -> { line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f) }
            else -> {}
        }
        c.restore()
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        rf.set(l, t, r, b)
        c.drawRoundRect(rf, radius, radius, p)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        c.drawPath(path, p)
    }

    private fun poly(c: Canvas, closed: Boolean, vararg v: Float) {
        path.reset()
        path.moveTo(v[0], v[1])
        var i = 2
        while (i + 1 < v.size) {
            path.lineTo(v[i], v[i + 1])
            i += 2
        }
        if (closed) path.close()
        c.drawPath(path, p)
    }
}

/** A Hud icon usable as a normal Drawable (buttons, dock fallback). */
class HudDrawable(private val kind: String, private val col: Int) : Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        Hud.draw(canvas, kind, b.left.toFloat(), b.top.toFloat(), minOf(b.width(), b.height()).toFloat(), col)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** HUD corner brackets drawn on top of a card. */
class BracketDrawable(private val col: Int, private val dens: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dens
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        p.color = col
        val b = bounds
        val s = 12f * dens
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bo = b.bottom.toFloat()
        path.reset()
        path.moveTo(l, t + s)
        path.lineTo(l, t)
        path.lineTo(l + s, t)
        path.moveTo(r - s, bo)
        path.lineTo(r, bo)
        path.lineTo(r, bo - s)
        canvas.drawPath(path, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Wallpaper: sky glow, perspective floor, dot grid and scanlines. Built once per size. */
class HomeBackdrop(ctx: Context) : View(ctx) {
    private var bg: Bitmap? = null
    private val d = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg = if (w > 0 && h > 0) buildBackdrop(w, h) else null
    }

    private fun buildBackdrop(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val fw = w.toFloat()
        val fh = h.toFloat()
        c.drawColor(C_BG)
        p.shader = RadialGradient(fw * 0.5f, fh * 0.6f, fh * 0.7f,
            intArrayOf(0xFF0A2A3A.toInt(), 0xFF04121C.toInt(), C_BG), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        p.shader = RadialGradient(fw * 0.5f, fh * 0.6f, fw * 0.5f,
            intArrayOf(0x3D2EB0C8, 0x002EB0C8), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        p.shader = null

        // perspective floor converging under the orb
        val vx = fw * 0.5f
        val vy = fh * 0.71f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.6f * d
        p.color = 0x404FE3FF
        for (i in -4..10) c.drawLine(vx, vy, fw * i / 6f, fh, p)
        for (f in floatArrayOf(0.90f, 0.94f, 0.975f)) c.drawLine(0f, fh * f, fw, fh * f, p)

        // dot grid
        p.style = Paint.Style.FILL
        p.color = 0x334FE3FF
        val stepX = 26f * d
        val stepY = 22f * d
        var y = stepY / 2f
        while (y < fh) {
            var x = stepX / 2f
            while (x < fw) {
                c.drawCircle(x, y, 0.9f * d, p)
                x += stepX
            }
            y += stepY
        }

        // faint scanlines
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x0A4FE3FF
        var sy = 0f
        while (sy < fh) {
            c.drawLine(0f, sy, fw, sy, p)
            sy += 3f * d
        }
        return b
    }

    override fun onDraw(c: Canvas) {
        val b = bg
        if (b != null) c.drawBitmap(b, 0f, 0f, null) else c.drawColor(C_BG)
    }
}

/**
 * The orb: a rolling ring of six controls around a logo core.
 * Drag to roll the ring. The item at the top is selected; tap it (or the orb) to run it.
 * Hold the orb for the apps list.
 */
class ThemeDialView(ctx: Context) : View(ctx) {

    var labels: Array<String> = emptyArray()
        set(v) {
            field = v
            invalidate()
        }
    var kinds: Array<String> = emptyArray()
        set(v) {
            field = v
            invalidate()
        }
    var onActivate: ((Int) -> Unit)? = null
    var onSelectChanged: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var itemR = 0f
    private var rot = 0f
    private var phase = 0f
    private var lastSel = -1
    private var bg: Bitmap? = null
    private var snapAnim: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var lastAng = 0f
    private var dragged = false
    private var longFired = false

    // decorative rings spin continuously; the item ring only moves when you roll it
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 9000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) {
            if (dist(e.x, e.y, cx, cy) < radius * 0.5f) {
                longFired = true
                performLongClick()   // HomeDrawer listens for this: opens all apps
            }
        }
    })

    init {
        isClickable = true
        isLongClickable = true
    }

    private fun step(): Float = if (labels.isEmpty()) 60f else 360f / labels.size

    /** Index of the item currently at the top. */
    fun selected(): Int {
        val n = labels.size
        if (n == 0) return -1
        val a = ((-rot) % 360f + 360f) % 360f
        return Math.round(a / step()) % n
    }

    private fun checkSelection() {
        val s = selected()
        if (s != lastSel) {
            lastSel = s
            onSelectChanged?.invoke(s)
        }
    }

    private fun snapTo(i: Int) {
        val target = -(i * step())
        val delta = ((target - rot) % 360f + 540f) % 360f - 180f
        val from = rot
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rot = from + delta * (it.animatedValue as Float)
                checkSelection()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f
        cy = h / 2f
        val m = min(w, h).toFloat()
        radius = m * 0.30f
        itemR = m * 0.062f
        buildBackground()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!pulse.isRunning) pulse.start()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        snapAnim?.cancel()
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
                snapAnim?.cancel()
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
                    checkSelection()
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
        if (dist(x, y, cx, cy) < radius * 0.5f) {
            onActivate?.invoke(selected())
            return
        }
        for (i in labels.indices) {
            val a = Math.toRadians(-90.0 + i * step() + rot)
            val ix = cx + radius * cos(a).toFloat()
            val iy = cy + radius * sin(a).toFloat()
            if (dist(x, y, ix, iy) < itemR * 1.4f) {
                snapTo(i)
                return
            }
        }
    }

    /** Static parts: glow, rings, outer ticks. Drawn once per size. */
    private fun buildBackground() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val m = min(w, h).toFloat()

        p.shader = RadialGradient(cx, cy, m * 0.6f,
            intArrayOf(0x554FE3FF, 0x00101A22, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, m * 0.6f, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = 0x224FE3FF
        c.drawCircle(cx, cy, radius * 1.3f, p)
        c.drawCircle(cx, cy, radius * 1.42f, p)

        for (i in 0 until 90) {
            val a = Math.toRadians(i * 4.0 - 90.0)
            val r1 = radius * 1.4f
            val r2 = r1 + (if (i % 9 == 0) 10f else 4f) * d
            p.strokeWidth = (if (i % 9 == 0) 2f else 1f) * d
            p.color = if (i % 9 == 0) 0xAA4FE3FF.toInt() else 0x444FE3FF
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), p)
        }
        bg = b
    }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        if (bg == null) buildBackground()
        bg?.let { c.drawBitmap(it, 0f, 0f, null) }

        val beat = 1f + 0.025f * Math.sin(phase * 2.0 * Math.PI).toFloat()
        val spin = phase * 360f

        // spinning neon arcs, counter-rotating
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val r1 = radius * 1.19f
        arc.set(cx - r1, cy - r1, cx + r1, cy + r1)
        paint.strokeWidth = 5f * d
        c.save()
        c.rotate(spin, cx, cy)
        paint.color = C_CYAN
        c.drawArc(arc, 0f, 80f, false, paint)
        paint.color = C_BLUE
        c.drawArc(arc, 150f, 50f, false, paint)
        paint.color = C_CYAN
        c.drawArc(arc, 250f, 100f, false, paint)
        c.restore()

        val r2 = radius * 1.04f
        arc.set(cx - r2, cy - r2, cx + r2, cy + r2)
        paint.strokeWidth = 6f * d
        paint.color = C_CORAL
        c.save()
        c.rotate(-spin * 1.5f, cx, cy)
        c.drawArc(arc, 20f, 60f, false, paint)
        c.drawArc(arc, 200f, 30f, false, paint)
        paint.color = C_GOLD
        c.drawArc(arc, 110f, 25f, false, paint)
        c.restore()

        // item ring
        val n = labels.size
        val sel = selected()
        val s = step()
        for (i in 0 until n) {
            val a = Math.toRadians(-90.0 + i * s + rot)
            val x = cx + radius * cos(a).toFloat()
            val y = cy + radius * sin(a).toFloat()
            val on = i == sel
            val ir = if (on) itemR * 1.12f else itemR
            paint.style = Paint.Style.FILL
            paint.color = if (on) 0xFF0F3A4C.toInt() else 0xFF071C27.toInt()
            c.drawCircle(x, y, ir, paint)
            if (on) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 8f * d
                paint.color = 0x44FFBE46
                c.drawCircle(x, y, ir * 1.18f, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (on) 3f else 1.5f) * d
            paint.color = if (on) C_GOLD else C_CYAN
            c.drawCircle(x, y, ir, paint)
            val k = ir * 1.15f
            Hud.draw(c, kinds.getOrElse(i) { "" }, x - k / 2f, y - k / 2f, k, if (on) C_GOLD else C_CYAN)
        }

        // logo core
        val core = radius * 0.5f * beat
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, core,
            intArrayOf(0xFF123E50.toInt(), 0xFF03101A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, core, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.color = C_CYAN
        c.drawCircle(cx, cy, core, paint)

        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.letterSpacing = 0.22f
        text.textSize = core * 0.22f
        text.color = C_WHITE
        c.drawText("JARVIS", cx, cy + core * 0.02f, text)
        text.typeface = Typeface.DEFAULT
        text.letterSpacing = 0.18f
        text.textSize = core * 0.13f
        text.color = C_CYAN
        c.drawText("ONLINE", cx, cy + core * 0.36f, text)
        text.letterSpacing = 0f
    }
}

/**
 * Dock carousel: the icons loop forever. Drag to roll, it keeps drifting slowly,
 * a tap on the centre icon opens it, a tap on a side icon brings it to the centre, hold to change the app.
 */
class DockCarouselView(ctx: Context) : View(ctx) {

    var icons: Array<Drawable?> = arrayOfNulls(8)
        set(v) {
            field = v
            invalidate()
        }
    var onTapItem: ((Int) -> Unit)? = null
    var onLongItem: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var offset = 0f
    private var downX = 0f
    private var lastX = 0f
    private var touching = false
    private var dragged = false
    private var longFired = false
    private var anim: ValueAnimator? = null
    private var vt: VelocityTracker? = null

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) {
            if (!dragged) {
                longFired = true
                onLongItem?.invoke(centerIndex())
            }
        }
    })

    private val drift = object : Runnable {
        override fun run() {
            if (!touching && anim?.isRunning != true) {
                offset += 0.0018f
                invalidate()
            }
            postDelayed(this, 16L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(drift, 16L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(drift)
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun unit(): Float = width / 4.8f

    private fun centerIndex(): Int {
        val n = icons.size
        if (n == 0) return 0
        return ((Math.round(offset) % n) + n) % n
    }

    /** Position of item i relative to the centre, wrapped so the list loops. */
    private fun wrap(i: Int): Float {
        val n = icons.size.toFloat()
        var pos = ((i - offset) % n + n) % n
        if (pos >= n / 2f) pos -= n
        return pos
    }

    private fun snapTo(target: Float) {
        val from = offset
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = 420L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val n = icons.size
        if (n == 0) return
        val u = unit()
        val cx = width / 2f
        val cy = height / 2f
        val r = height * 0.27f
        val order = (0 until n).sortedByDescending { Math.abs(wrap(it)) }
        for (i in order) {
            val pos = wrap(i)
            val near = (1f - Math.abs(pos) / (n / 2f)).coerceIn(0f, 1f)
            val x = cx + pos * u
            val rad = r * (0.72f + 0.38f * near)
            val alpha = (90 + 165 * near).toInt()
            p.style = Paint.Style.FILL
            p.color = 0xFF0B2430.toInt()
            p.alpha = alpha
            c.drawCircle(x, cy, rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = (if (Math.abs(pos) < 0.5f) 3f else 1.5f) * d
            p.color = if (Math.abs(pos) < 0.5f) C_GOLD else C_CYAN
            p.alpha = alpha
            c.drawCircle(x, cy, rad, p)
            val icon = icons[i]
            if (icon != null) {
                val k = rad * 0.66f
                icon.setBounds((x - k).toInt(), (cy - k).toInt(), (x + k).toInt(), (cy + k).toInt())
                icon.alpha = alpha
                icon.draw(c)
            } else {
                t.textAlign = Paint.Align.CENTER
                t.typeface = Typeface.DEFAULT_BOLD
                t.textSize = rad * 0.8f
                t.color = C_DIM
                t.alpha = alpha
                c.drawText("+", x, cy + rad * 0.28f, t)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                dragged = false
                longFired = false
                downX = e.x
                lastX = e.x
                anim?.cancel()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!dragged && Math.abs(e.x - downX) > slop) dragged = true
                if (dragged) {
                    offset -= (e.x - lastX) / unit()
                    lastX = e.x
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val v = vt?.xVelocity ?: 0f
                vt?.recycle()
                vt = null
                if (dragged) {
                    // keep the momentum of the flick, then ease to the nearest icon
                    val projected = offset - v / (unit() * 3.3f)
                    snapTo(Math.round(projected).toFloat())
                } else if (!longFired) {
                    val side = e.x - width / 2f
                    when {
                        side > unit() * 0.6f -> snapTo(Math.round(offset).toFloat() - 1f)
                        side < -unit() * 0.6f -> snapTo(Math.round(offset).toFloat() + 1f)
                        else -> onTapItem?.invoke(centerIndex())
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }
}

/** Home screen: three pages (home, notes, phone tools). Swipe sideways to move between them. */
/** Start-up animation: rings draw in, the JARVIS mark rotates, then "MADE BY NALIN" types out. Tap to skip. */
class IntroView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var t = 0f
    private var angle = 0f
    private var done = false
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = C_CYAN; strokeCap = Paint.Cap.ROUND }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x553FD7F0.toInt() }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = C_CYAN }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = 30f * d; letterSpacing = 0.35f }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_CYAN; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 13f * d; letterSpacing = 0.3f }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_DIM; textAlign = Paint.Align.CENTER; textSize = 11f * d }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3200L
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; angle = t * 540f; alpha = 1f - seg(0.9f, 1f); invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { finish() }
        })
    }

    init {
        isClickable = true
        setOnClickListener { anim.cancel(); finish() }
        setBackgroundColor(C_BG)
    }

    fun start() { anim.start() }

    private fun finish() {
        if (done) return
        done = true
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun seg(a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.42f
        val r = min(w, h) * 0.20f
        val draw = seg(0f, 0.35f)
        val oval = RectF()

        // outer ring draws in, with ticks that light up one by one
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, -90f, 360f * draw, false, dim)
        for (i in 0 until 36) {
            if (i > 36 * draw) break
            val a = Math.toRadians((angle * 0.3 + i * 10).toDouble())
            c.drawLine(cx + (r * 0.92f * cos(a)).toFloat(), cy + (r * 0.92f * sin(a)).toFloat(),
                cx + (r * 1.0f * cos(a)).toFloat(), cy + (r * 1.0f * sin(a)).toFloat(), ring)
        }

        // middle ring: three rotating arcs
        val rm = r * 0.76f
        oval.set(cx - rm, cy - rm, cx + rm, cy + rm)
        for (i in 0 until 3) {
            c.drawArc(oval, angle + i * 120f, 80f * draw, false, ring)
        }

        // inner ring counter-rotates
        val ri = r * 0.52f
        oval.set(cx - ri, cy - ri, cx + ri, cy + ri)
        c.drawArc(oval, -angle * 1.5f, 260f * draw, false, ring)

        // core pulses in
        val pulse = 0.6f + 0.4f * draw
        core.alpha = (255 * draw).toInt()
        c.drawCircle(cx, cy, r * 0.20f * pulse, core)

        // JARVIS, letters fade in
        val tAlpha = (255 * seg(0.30f, 0.60f)).toInt()
        title.alpha = tAlpha
        c.drawText("JARVIS", cx, cy + r + 52f * d, title)

        // MADE BY NALIN, typed out
        val full = "MADE BY NALIN"
        val shown = (full.length * seg(0.55f, 0.85f)).toInt()
        c.drawText(full.substring(0, shown), cx, cy + r + 84f * d, sub)

        if (t > 0.5f) c.drawText("tap to skip", cx, h - 40f * d, hint)
    }
}

class ThemeHomeActivity : AppCompatActivity() {

    private class Act(val label: String, val kind: String, val action: () -> Unit)

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val prefs by lazy { getSharedPreferences("jarvis_control", MODE_PRIVATE) }
    private val dens by lazy { resources.displayMetrics.density }
    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var actions: List<Act>
    private lateinit var dial: ThemeDialView
    private lateinit var dock: DockCarouselView
    private lateinit var selTv: TextView
    private lateinit var serverTv: TextView
    private lateinit var micTv: TextView
    private lateinit var notifTv: TextView
    private lateinit var cpuTv: TextView
    private lateinit var ramTv: TextView
    private lateinit var batTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var laptopTv: TextView
    private lateinit var weatherTv: TextView
    private var weatherText = "--"
    private var weatherAt = 0L
    private lateinit var netTv: TextView
    private lateinit var powerTv: TextView
    private lateinit var notesCountTv: TextView
    private lateinit var storageTv: TextView
    private lateinit var pagesHolder: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var pagerDots: List<View>
    private lateinit var card: LinearLayout
    private lateinit var cardText: TextView
    private lateinit var noteInput: EditText
    private lateinit var notesList: LinearLayout

    private var page = 0
    private var lastReply = ""
    private var torchOn = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val hideLater = Runnable { hideCard() }

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleMic() else toast("Microphone permission is needed")
    }

    private val dateTick = object : Runnable {
        override fun run() {
            updateDate()
            ui.postDelayed(this, 30_000L)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            refreshStats()
            ui.postDelayed(this, 10_000L)
        }
    }

    // ---- app drawer: swipe up from the home screen (search + alphabetical list) ----

    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: ListView
    private lateinit var drawerSearch: EditText
    private var drawerVisible = false
    private var allApps: List<Pair<String, String>> = emptyList()
    private var shownApps: List<Pair<String, String>> = emptyList()

    private fun buildDrawer(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(0xFA03080D.toInt())
        box.setPadding(dp(16), dp(14), dp(16), dp(10))
        box.isClickable = true

        drawerSearch = EditText(this)
        drawerSearch.hint = "Search apps"
        drawerSearch.setHintTextColor(C_DIM)
        drawerSearch.setTextColor(C_WHITE)
        drawerSearch.textSize = 15f
        drawerSearch.isSingleLine = true
        drawerSearch.setPadding(dp(14), dp(10), dp(14), dp(10))
        drawerSearch.background = panel(12f)
        drawerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        box.addView(drawerSearch, LinearLayout.LayoutParams(matchParent, wrap))

        drawerList = ListView(this)
        drawerList.divider = null
        drawerList.isVerticalScrollBarEnabled = true
        drawerList.isFastScrollEnabled = true
        drawerList.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        drawerList.adapter = object : BaseAdapter() {
            override fun getCount(): Int = shownApps.size
            override fun getItem(position: Int): Any = shownApps[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: TextView(this@ThemeHomeActivity).apply {
                    textSize = 17f
                    setTextColor(C_WHITE)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                }
                tv.text = shownApps[position].first
                return tv
            }
        }
        drawerList.setOnItemClickListener { _, _, pos, _ ->
            val pkg = shownApps.getOrNull(pos)?.second
            if (pkg != null) {
                hideDrawer()
                packageManager.getLaunchIntentForPackage(pkg)?.let { openApp(it) }
            }
        }
        box.addView(drawerList, LinearLayout.LayoutParams(matchParent, 0, 1f).apply { topMargin = dp(10) })

        val close = TextView(this)
        close.text = "CLOSE"
        close.gravity = Gravity.CENTER
        close.textSize = 10f
        close.typeface = Typeface.DEFAULT_BOLD
        close.letterSpacing = 0.2f
        close.setTextColor(C_CYAN)
        close.setPadding(0, dp(10), 0, dp(6))
        close.setOnClickListener { hideDrawer() }
        box.addView(close, LinearLayout.LayoutParams(matchParent, wrap))
        return box
    }

    private fun loadApps() {
        val pm = packageManager
        allApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }
    }

    private fun filterApps(q: String) {
        shownApps = if (q.isBlank()) allApps else allApps.filter { it.first.contains(q, ignoreCase = true) }
        (drawerList.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun showDrawer() {
        if (drawerVisible) return
        drawerVisible = true
        loadApps()
        drawerSearch.setText("")
        filterApps("")
        drawer.visibility = View.VISIBLE
        drawer.translationY = resources.displayMetrics.heightPixels.toFloat()
        drawer.animate().translationY(0f).setDuration(240L).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun hideDrawer() {
        if (!drawerVisible) return
        drawerVisible = false
        drawer.animate()
            .translationY(resources.displayMetrics.heightPixels.toFloat())
            .setDuration(200L)
            .withEndAction { drawer.visibility = View.INVISIBLE }
            .start()
    }

    private var swipeVt: VelocityTracker? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeIgnored = false

    /** Sideways swipes move between pages; a swipe up from the bottom opens all apps; a swipe down from the top refreshes. */
    private fun onSwipe(dx: Float, dy: Float, vx: Float, vy: Float) {
        val h = resources.displayMetrics.heightPixels
        if (drawerVisible) {
            val listTop = IntArray(2).also { drawerList.getLocationOnScreen(it) }[1]
            if (vy > 1200f && Math.abs(dy) > 120 * dens && swipeStartY < listTop) hideDrawer()
            return
        }
        if (Math.abs(vx) > Math.abs(vy) * 1.2f && Math.abs(vx) > 900f && Math.abs(dx) > 60 * dens) {
            setPage(if (vx < 0) page + 1 else page - 1)
            return
        }
        if (vy < -1200f && Math.abs(dy) > 120 * dens) {
            showDrawer()
            return
        }
        if (vy > 1200f && swipeStartY < h * 0.25f) {
            refreshStats()
            toast("Refreshed")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeVt?.recycle()
                swipeVt = VelocityTracker.obtain()
                swipeVt?.addMovement(ev)
                swipeStartX = ev.rawX
                swipeStartY = ev.rawY
                swipeIgnored = startedIn(dial, ev) || startedIn(dock, ev)
            }
            MotionEvent.ACTION_MOVE -> swipeVt?.addMovement(ev)
            MotionEvent.ACTION_UP -> {
                val vt = swipeVt
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle()
                swipeVt = null
                if (!swipeIgnored) onSwipe(ev.rawX - swipeStartX, ev.rawY - swipeStartY, vx, vy)
            }
            MotionEvent.ACTION_CANCEL -> {
                swipeVt?.recycle()
                swipeVt = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        actions = listOf(
            Act("LOCAL CMDS", "terminal") { localCommands() },
            Act("NOTE", "note") { openNotes() },
            Act("MEDIA", "play") { mediaToggle() },
            Act("FIND LAPTOP", "search") { send("find my laptop") },
            Act("SHUT DOWN", "power") { confirm("Shut down the laptop?") { send("shut down laptop") } },
            Act("LAPTOP", "laptop") { startActivity(Intent(this, RemoteActivity::class.java)) }
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(C_BG)
        root.addView(HomeBackdrop(this), FrameLayout.LayoutParams(matchParent, matchParent))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        root.addView(content, FrameLayout.LayoutParams(matchParent, matchParent))

        pagesHolder = FrameLayout(this)
        content.addView(pagesHolder, LinearLayout.LayoutParams(matchParent, 0, 1f))

        val home = buildHome()
        val notes = buildNotes()
        val tools = buildTools()
        pages = listOf(home, notes, tools)
        pages.forEachIndexed { i, v ->
            if (i > 0) v.visibility = View.INVISIBLE
            pagesHolder.addView(v, FrameLayout.LayoutParams(matchParent, matchParent))
        }

        card = buildCard()
        content.addView(card, LinearLayout.LayoutParams(matchParent, wrap).apply { setMargins(dp(12), 0, dp(12), dp(6)) })

        val pager = LinearLayout(this)
        pager.orientation = LinearLayout.HORIZONTAL
        pager.gravity = Gravity.CENTER
        pager.setPadding(0, dp(2), 0, dp(10))
        pagerDots = (0 until 3).map {
            View(this).also { dot ->
                pager.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }
        content.addView(pager, LinearLayout.LayoutParams(matchParent, wrap))

        drawer = buildDrawer()
        drawer.visibility = View.INVISIBLE
        root.addView(drawer, FrameLayout.LayoutParams(matchParent, matchParent))

        setContentView(root)
        // the intro plays every time the home screen is opened fresh (not when you come back to it from another app)
        rootView = root
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerVisible) hideDrawer() else if (page != 0) setPage(0)
            }
        })
        HomeDrawer.attach(this, dial)
        refreshDockIcons()
        pagesHolder.post { setPage(0, false) }
    }

    // The intro plays on the first show and again every time the home screen comes back after being stopped (screen off, unlock, app switch).
    private var introDue = true
    private lateinit var rootView: FrameLayout

    private fun playIntro() {
        if (!::rootView.isInitialized) return
        val intro = IntroView(this)
        rootView.addView(intro, FrameLayout.LayoutParams(matchParent, matchParent))
        intro.start()
    }

    override fun onStop() {
        super.onStop()
        introDue = true
    }

    override fun onResume() {
        super.onResume()
        if (introDue) {
            introDue = false
            playIntro()
        }
        refreshSwitches()
        updateDate()
        ui.removeCallbacks(dateTick)
        ui.postDelayed(dateTick, 30_000L)
        ui.removeCallbacks(poll)
        refreshStats()
        ui.postDelayed(poll, 10_000L)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        ui.removeCallbacks(dateTick)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---- pages ----

    private fun setPage(index: Int, animate: Boolean = true) {
        val target = index.coerceIn(0, pages.size - 1)
        page = target
        val w = pagesHolder.width.toFloat()
        pages.forEachIndexed { i, v ->
            v.visibility = View.VISIBLE
            val x = (i - target) * w
            v.animate().cancel()
            if (animate) {
                v.animate().translationX(x).setDuration(260L).setInterpolator(DecelerateInterpolator(1.6f)).start()
            } else {
                v.translationX = x
            }
        }
        pagerDots.forEachIndexed { i, dot -> dot.background = dotDrawable(i == target) }
        if (target == 1) refreshNotes()
    }

    private fun dotDrawable(active: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (active) {
            setColor(C_CYAN)
        } else {
            setColor(0x00000000)
            setStroke(dp(1), C_CYAN)
        }
    }

    private fun startedIn(v: View, e: MotionEvent): Boolean {
        val r = Rect()
        if (!v.getGlobalVisibleRect(r)) return false
        return r.contains(e.rawX.toInt(), e.rawY.toInt())
    }

    // ---- page 1: home ----

    private fun buildHome(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(4), dp(12), dp(14))

        // header: JARVIS between two rules
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(rule(false), LinearLayout.LayoutParams(0, dp(1), 1f))
        val title = TextView(this)
        title.text = "JARVIS"
        title.textSize = 13f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.24f
        title.setTextColor(C_CYAN)
        title.setPadding(dp(10), 0, dp(10), 0)
        head.addView(title)
        head.addView(rule(true), LinearLayout.LayoutParams(0, dp(1), 1f))
        col.addView(head, LinearLayout.LayoutParams(matchParent, dp(30)))

        // two cards: phone switches on the left, laptop stats on the right
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        val left = card("SERVER · MIC · NOTIFS")
        serverTv = statRow(left, "Server") { toggleServer() }
        micTv = statRow(left, "Mic") { toggleMic() }
        notifTv = statRow(left, "Notifications") { toggleNotif() }
        val right = card("SYSTEM · LAPTOP")
        cpuTv = statRow(right, "CPU", null)
        ramTv = statRow(right, "RAM", null)
        batTv = statRow(right, "Phone battery", null)
        topRow.addView(left, LinearLayout.LayoutParams(0, wrap, 1f).apply { setMargins(0, 0, dp(5), 0) })
        topRow.addView(right, LinearLayout.LayoutParams(0, wrap, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        col.addView(topRow, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(8) })

        // three tiles: date, laptop link, phone uptime
        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        dateTv = tile(tiles, "DATE")
        laptopTv = tile(tiles, "LAPTOP")
        weatherTv = tile(tiles, "WEATHER")
        weatherTv.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 77)
            } else {
                weatherAt = 0L
                refreshWeather()
            }
        }
        col.addView(tiles, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(8) })

        // phone strip: network, power, notes count, free storage
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.background = panel(12f)
        strip.setPadding(dp(4), dp(8), dp(4), dp(8))
        netTv = cell(strip, "NETWORK")
        powerTv = cell(strip, "POWER")
        notesCountTv = cell(strip, "NOTES")
        storageTv = cell(strip, "FREE")
        col.addView(strip, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(8) })

        // action row: laptop power and screenshot
        val acts = LinearLayout(this)
        acts.orientation = LinearLayout.HORIZONTAL
        acts.addView(actionButton("LOCK", "lock") { laptopPower("lock", "Laptop locked") }, weighted(dp(3)))
        acts.addView(actionButton("SLEEP", "moon") { laptopPower("sleep", "Laptop sleeping") }, weighted(dp(3)))
        acts.addView(actionButton("SCREEN", "screen") { laptopPower("screen_off", "Laptop screen off") }, weighted(dp(3)))
        acts.addView(actionButton("SHOT", "shot") { send("take a screenshot") }, weighted(dp(3)))
        col.addView(acts, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(8) })

        // the selected item's name sits above the orb
        selTv = TextView(this)
        selTv.textSize = 11f
        selTv.typeface = Typeface.DEFAULT_BOLD
        selTv.letterSpacing = 0.12f
        selTv.setTextColor(C_GOLD)
        selTv.gravity = Gravity.CENTER
        col.addView(selTv, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(4) })

        // orb: square, sized from the screen width so the proportions hold on any phone
        dial = ThemeDialView(this)
        dial.labels = actions.map { it.label }.toTypedArray()
        dial.kinds = actions.map { it.kind }.toTypedArray()
        dial.onActivate = { i -> actions.getOrNull(i)?.action?.invoke() }
        dial.onSelectChanged = { i -> selTv.text = "▲ " + (actions.getOrNull(i)?.label ?: "") }
        selTv.text = "▲ " + actions[0].label
        col.addView(dial, LinearLayout.LayoutParams(matchParent, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(4) })

        // dock: an endless carousel inside a rounded pill
        dock = DockCarouselView(this)
        dock.onTapItem = { i -> dockTap(i) }
        dock.onLongItem = { i -> pickApp(i) }
        val pill = FrameLayout(this)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(0xE60C2433.toInt())
            setStroke(dp(1), 0x594FE3FF)
        }
        pill.addView(dock, FrameLayout.LayoutParams(matchParent, matchParent))
        col.addView(pill, LinearLayout.LayoutParams(matchParent, dp(84)).apply { topMargin = dp(8) })
        return col
    }

    // ---- page 2: notes (phone only, never sent to the laptop) ----

    private fun buildNotes(): ScrollView {
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(10), dp(14), dp(14))
        scroll.addView(col)

        col.addView(sectionTitle("NOTES"), LinearLayout.LayoutParams(matchParent, wrap))
        col.addView(subText("Saved on this phone only. Never sent to the laptop."),
            LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(2) })

        noteInput = EditText(this)
        noteInput.hint = "Write a note for yourself…"
        noteInput.setHintTextColor(C_DIM)
        noteInput.setTextColor(C_WHITE)
        noteInput.textSize = 14f
        noteInput.minLines = 3
        noteInput.gravity = Gravity.TOP
        noteInput.setPadding(dp(12), dp(10), dp(12), dp(10))
        noteInput.background = panel(12f)
        col.addView(noteInput, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(10) })

        col.addView(actionButton("SAVE NOTE", "plus") {
            val text = noteInput.text.toString().trim()
            if (text.isEmpty()) {
                toast("Write something first")
                return@actionButton
            }
            addNote(text)
            noteInput.setText("")
            refreshNotes()
            toast("Saved on this phone")
        }, LinearLayout.LayoutParams(matchParent, dp(42)).apply { topMargin = dp(8) })

        notesList = LinearLayout(this)
        notesList.orientation = LinearLayout.VERTICAL
        col.addView(notesList, LinearLayout.LayoutParams(matchParent, wrap).apply { topMargin = dp(14) })
        return scroll
    }

    private fun openNotes() {
        setPage(1)
        noteInput.postDelayed({
            noteInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(noteInput, InputMethodManager.SHOW_IMPLICIT)
        }, 300L)
    }

    private fun loadNotes(): JSONArray = try {
        JSONArray(prefs.getString("jarvis_notes", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun saveNotes(a: JSONArray) {
        prefs.edit().putString("jarvis_notes", a.toString()).apply()
    }

    private fun addNote(text: String) {
        val old = loadNotes()
        val fresh = JSONArray()
        val now = System.currentTimeMillis()
        fresh.put(JSONObject().put("id", now).put("t", now).put("text", text))
        for (i in 0 until old.length()) {
            old.optJSONObject(i)?.let { fresh.put(it) }
        }
        saveNotes(fresh)
    }

    private fun deleteNote(id: Long) {
        val old = loadNotes()
        val fresh = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optLong("id") != id) fresh.put(o)
        }
        saveNotes(fresh)
        refreshNotes()
    }

    private fun refreshNotes() {
        notesList.removeAllViews()
        val a = loadNotes()
        notesCountTv.text = a.length().toString()
        if (a.length() == 0) {
            notesList.addView(TextView(this).apply {
                text = "No notes yet."
                textSize = 12f
                setTextColor(C_DIM)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
            return
        }
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(12), dp(10), dp(12), dp(10))
            box.background = panel(12f)
            val body = TextView(this)
            body.text = o.optString("text")
            body.textSize = 14f
            body.setTextColor(C_WHITE)
            box.addView(body)
            val foot = LinearLayout(this)
            foot.orientation = LinearLayout.HORIZONTAL
            foot.gravity = Gravity.CENTER_VERTICAL
            foot.setPadding(0, dp(6), 0, 0)
            val timeTv = TextView(this)
            timeTv.text = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(o.optLong("t")))
            timeTv.textSize = 10f
            timeTv.setTextColor(C_DIM)
            foot.addView(timeTv, LinearLayout.LayoutParams(0, wrap, 1f))
            val del = TextView(this)
            del.text = "DELETE"
            del.textSize = 9f
            del.typeface = Typeface.DEFAULT_BOLD
            del.letterSpacing = 0.12f
            del.setTextColor(C_CORAL)
            del.setPadding(dp(8), dp(4), 0, dp(4))
            del.setOnClickListener { confirm("Delete this note?") { deleteNote(id) } }
            foot.addView(del)
            box.addView(foot)
            notesList.addView(box, LinearLayout.LayoutParams(matchParent, wrap).apply { bottomMargin = dp(8) })
        }
    }

    // ---- page 3: phone tools ----

    private var calendarPage: CalendarPage? = null

    /** The swipe page after NOTES: the phone's month calendar (it replaced PHONE TOOLS). */
    private fun buildTools(): View {
        val cal = CalendarPage(this) { v -> dp(v) }
        calendarPage = cal
        return cal.root
    }

    private fun wifiIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI)
        else Intent(Settings.ACTION_WIFI_SETTINGS)

    private fun openSettings(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Can't open that here")
        }
    }

    // ---- small view helpers ----

    private fun dp(v: Int): Int = (v * dens).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun weighted(m: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, wrap, 1f).apply { setMargins(m, 0, m, 0) }

    private fun panel(radiusDp: Float): Drawable {
        val gd = GradientDrawable().apply {
            setColor(0xB80A1E2A.toInt())
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setStroke(dp(1), 0x474FE3FF)
        }
        return LayerDrawable(arrayOf<Drawable>(gd, BracketDrawable(C_CYAN, dens)))
    }

    private fun rule(reverse: Boolean): View {
        val v = View(this)
        v.background = GradientDrawable(
            if (reverse) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00000000, 0x994FE3FF.toInt())
        )
        return v
    }

    private fun sectionTitle(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.18f
        setTextColor(C_CYAN)
    }

    private fun subText(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 11f
        setTextColor(C_DIM)
    }

    private fun actionButton(label: String, kind: String, onTap: () -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(4), dp(10), dp(4), dp(10))
        box.background = GradientDrawable().apply {
            setColor(0xCC0E2A3A.toInt())
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0x594FE3FF)
        }
        val icon = ImageView(this)
        icon.setImageDrawable(HudDrawable(kind, C_CYAN))
        box.addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(0, 0, dp(5), 0) })
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 9f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.letterSpacing = 0.1f
        tv.setTextColor(0xFFCFEFF9.toInt())
        box.addView(tv)
        box.setOnClickListener { onTap() }
        return box
    }

    /** A tile with a small label above a value. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) refreshWeather()
        if (requestCode == 78) calendarPage?.refresh()
    }

    // Weather from Open-Meteo (free, no key). Uses the phone's last known location; refreshes at most every 30 minutes.
    private fun refreshWeather() {
        if (!::weatherTv.isInitialized) return
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            weatherTv.text = "Tap to allow"
            return
        }
        if (weatherText != "--" && System.currentTimeMillis() - weatherAt < 30 * 60 * 1000L) {
            weatherTv.text = weatherText
            return
        }
        io.execute {
            val text = try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val loc = listOf(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.PASSIVE_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER
                ).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null } }
                    .maxByOrNull { it.time }
                if (loc == null) {
                    "No location"
                } else {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current_weather=true"
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val cw = org.json.JSONObject(body).getJSONObject("current_weather")
                    "${cw.getDouble("temperature").toInt()}\u00B0C " + weatherWord(cw.getInt("weathercode"))
                }
            } catch (e: Exception) {
                "Offline"
            }
            ui.post {
                weatherText = text
                weatherAt = System.currentTimeMillis()
                if (!isFinishing) weatherTv.text = text
            }
        }
    }

    private fun weatherWord(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Storm"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        else -> "Drizzle"
    }

    private fun tile(parent: LinearLayout, label: String): TextView {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(7), dp(10), dp(7))
        box.background = panel(12f)
        val l = TextView(this)
        l.text = label
        l.textSize = 8f
        l.typeface = Typeface.DEFAULT_BOLD
        l.letterSpacing = 0.14f
        l.setTextColor(0xFF6FD8EE.toInt())
        box.addView(l)
        val v = TextView(this)
        v.textSize = 13f
        v.setTextColor(C_WHITE)
        v.setPadding(0, dp(3), 0, 0)
        box.addView(v)
        parent.addView(box, LinearLayout.LayoutParams(0, wrap, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        return v
    }

    /** A cell in the phone strip: label above value, centred. */
    private fun cell(parent: LinearLayout, label: String): TextView {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        val l = TextView(this)
        l.text = label
        l.textSize = 7f
        l.typeface = Typeface.DEFAULT_BOLD
        l.letterSpacing = 0.14f
        l.gravity = Gravity.CENTER
        l.setTextColor(0xFF6FD8EE.toInt())
        box.addView(l)
        val v = TextView(this)
        v.textSize = 11f
        v.setTextColor(C_WHITE)
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(2), 0, 0)
        box.addView(v)
        parent.addView(box, LinearLayout.LayoutParams(0, wrap, 1f))
        return v
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(11), dp(9), dp(11), dp(9))
        c.background = panel(12f)
        val head = TextView(this)
        head.text = title
        head.textSize = 9f
        head.setTextColor(C_CYAN)
        head.typeface = Typeface.DEFAULT_BOLD
        head.letterSpacing = 0.14f
        head.setPadding(0, 0, 0, dp(4))
        c.addView(head)
        return c
    }

    private fun statRow(c: LinearLayout, label: String, onTap: (() -> Unit)?): TextView {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(0, dp(5), 0, dp(5))
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

    // ---- orb actions ----

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun localCommands() {
        val cmds = arrayOf("status report", "startup voice test")
        AlertDialog.Builder(this)
            .setTitle("LAPTOP COMMANDS")
            .setItems(cmds) { _, which -> send(cmds[which]) }
            .show()
    }

    /** Play / pause the phone's current media. Uses the notification access Jarvis already has. */
    private fun mediaToggle() {
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val session = msm.getActiveSessions(ComponentName(this, JarvisNotificationListener::class.java)).firstOrNull()
            if (session != null) {
                if (session.playbackState?.state == PlaybackState.STATE_PLAYING) session.transportControls?.pause()
                else session.transportControls?.play()
                return
            }
        } catch (e: SecurityException) {
            // no notification access: fall through to the key event
        }
        try {
            val am = getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        } catch (e: Exception) {
            toast("Media control is not allowed here")
        }
    }

    private fun toggleTorch() {
        try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull()
            if (id == null) {
                toast("No flashlight on this phone")
                return
            }
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (e: Exception) {
            toast("Flashlight not available")
        }
    }

    /** Laptop power action: shows the result in the reply card. */
    private fun laptopPower(action: String, done: String) {
        if (!LaptopApi.paired(this)) {
            showReply("Link your laptop first (open Control)")
            return
        }
        val app = applicationContext
        io.execute {
            val r = LaptopApi.call(app, "POST", "/remote/power", JSONObject().put("action", action), 8000)
            val out = if (r.has("error")) "⚠ " + r.optString("error") else done
            ui.post { showReply(out) }
        }
    }

    // ---- dock ----

    private fun defaultIntent(j: Int): Intent? = when (j) {
        0 -> Intent(Intent.ACTION_DIAL)
        1 -> Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
        2 -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        3 -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        4 -> Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
        else -> null
    }

    private fun dockKind(j: Int): String = arrayOf("phone", "files", "globe", "camera", "chat").getOrElse(j) { "plus" }

    private fun dockIcon(j: Int): Drawable? {
        val pkg = prefs.getString("dock_$j", "") ?: ""
        if (pkg.isNotEmpty()) {
            return try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
        }
        val i = defaultIntent(j) ?: return null
        val icon = packageManager.resolveActivity(i, 0)?.loadIcon(packageManager)
        return icon ?: HudDrawable(dockKind(j), C_CYAN)
    }

    private fun refreshDockIcons() {
        dock.icons = Array(8) { j -> dockIcon(j) }
    }

    private fun dockTap(j: Int) {
        val pkg = prefs.getString("dock_$j", "") ?: ""
        if (pkg.isNotEmpty()) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) openApp(launch) else toast("App not found")
            return
        }
        val i = defaultIntent(j)
        if (i != null) openApp(i) else pickApp(j)
    }

    /** Hold a dock icon (or tap an empty "+") to choose which installed app it opens. */
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
                refreshDockIcons()
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

    // ---- date and stats ----

    private fun updateDate() {
        dateTv.text = SimpleDateFormat("EEE · dd MMM", Locale.getDefault()).format(Date())
    }

    private fun netLabel(): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "Other"
        }
    } catch (e: Exception) {
        "--"
    }

    private fun refreshStats() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        batTv.text = if (pct < 0) "--" else "$pct%"
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        powerTv.text = if (charging) "Charging" else "Battery"
        notesCountTv.text = loadNotes().length().toString()
        netTv.text = netLabel()
        storageTv.text = try {
            val gb = StatFs(Environment.getDataDirectory().path).availableBytes / 1e9
            String.format(Locale.US, "%.1f GB", gb)
        } catch (e: Exception) {
            "--"
        }
        refreshWeather()

        if (!LaptopApi.paired(this)) {
            laptopTv.text = "Not linked"
            cpuTv.text = "--"
            ramTv.text = "--"
            return
        }
        val app = applicationContext
        io.execute {
            val r = LaptopApi.call(app, "GET", "/remote/glance", null, 3000)
            val ok = !r.has("error")
            ui.post {
                laptopTv.text = if (ok) "Online" else "Offline"
                cpuTv.text = if (ok && r.optInt("cpu_pct", -1) >= 0) "${r.optInt("cpu_pct")}%" else "--"
                ramTv.text = if (ok && r.optInt("ram_pct", -1) >= 0) "${r.optInt("ram_pct")}%" else "--"
            }
        }
    }

    // ---- laptop commands and the reply card ----

    /** Sends a command to the laptop; the reply shows in the card. */
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
        row.addView(plainButton("SPEAK") { speak(lastReply) })
        row.addView(plainButton("CLOSE") { hideCard() })
        return row
    }

    private fun plainButton(label: String, onTap: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 10f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.letterSpacing = 0.1f
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
}
