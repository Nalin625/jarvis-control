package com.jarvis.control

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Shared frame ticker: one ValueAnimator that only runs while its view is on screen. */
private class Ticker(private val onFrame: (dtSeconds: Float) -> Unit) {
    private var last = 0L
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
            last = now
            onFrame(dt)
        }
    }

    fun start() { if (!anim.isRunning) { last = 0L; anim.start() } }
    fun stop() { anim.cancel() }
}

/**
 * The animated Jarvis core. level: 0 = server off (red), 1 = server up (amber), 2 = linked to the
 * laptop (cyan). Rings spin faster the more "alive" the connection is.
 */
class ReactorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var level = 0
        set(v) {
            if (v == field) return
            field = v
            val target = colorFor(v)
            ValueAnimator.ofObject(ArgbEvaluator(), cur, target).apply {
                duration = 450
                addUpdateListener { cur = it.animatedValue as Int; rebuild(); invalidate() }
            }.start()
        }

    private fun colorFor(l: Int) = when (l) { 2 -> ContextCompat.getColor(context, R.color.accent); 1 -> 0xFFFFC24D.toInt(); else -> 0xFFFF7A5C.toInt() }

    private var cur = colorFor(0)
    private var rot = 0f
    private var pulse = 0f
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val oval = RectF()
    private val hex = Path()
    private var glow: RadialGradient? = null
    private var core: RadialGradient? = null
    private var sweep: SweepGradient? = null
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    private var acc = 0f
    // ~30 fps is plenty for slow rings and halves the battery cost
    private val ticker = Ticker { dt ->
        acc += dt
        if (acc >= 0.033f) {
            val speed = when (level) { 2 -> 38f; 1 -> 22f; else -> 8f }
            rot = (rot + acc * speed) % 3600f
            pulse += acc * (if (level == 2) 2.4f else 1.4f)
            acc = 0f
            invalidate()
        }
    }

    private fun faded(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun rebuild() {
        if (r <= 0f) return
        glow = RadialGradient(cx, cy, r, faded(cur, 110), faded(cur, 0), Shader.TileMode.CLAMP)
        core = RadialGradient(cx, cy, r * 0.34f, intArrayOf(Color.WHITE, cur, faded(cur, 40)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        sweep = SweepGradient(cx, cy, intArrayOf(faded(cur, 0), faded(cur, 0), faded(cur, 90)), floatArrayOf(0f, 0.6f, 1f))
        hex.reset()
        val hr = r * 0.2f
        for (i in 0..5) {
            val a = (PI / 3 * i - PI / 2).toFloat()
            val x = cx + hr * cos(a)
            val y = cy + hr * sin(a)
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cx = w / 2f; cy = h / 2f; r = min(w, h) / 2f - 4 * d
        rebuild()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (isShown) ticker.start() }
    override fun onDetachedFromWindow() { ticker.stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) ticker.start() else ticker.stop()
    }
    // pages are switched with GONE, so stop drawing while the Home page is hidden
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow && isShown) ticker.start() else ticker.stop()
    }

    override fun onDraw(c: Canvas) {
        if (r <= 0f) return
        val breathe = 0.5f + 0.5f * sin(pulse)

        // soft glow behind everything
        fill.shader = glow; fill.alpha = (150 + 100 * breathe).toInt().coerceAtMost(255)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null; fill.alpha = 255

        // outer dashed ring (slow, clockwise)
        stroke.shader = null; stroke.color = faded(cur, 90); stroke.strokeWidth = 1.5f * d
        stroke.pathEffect = DashPathEffect(floatArrayOf(5 * d, 9 * d), rot * 0.6f)
        c.drawCircle(cx, cy, r * 0.98f, stroke)
        stroke.pathEffect = null

        // tick ring (counter-rotating)
        c.save(); c.rotate(-rot * 0.15f, cx, cy)
        stroke.color = faded(cur, 140); stroke.strokeWidth = 1.2f * d
        for (i in 0 until 72) {
            val long = i % 6 == 0
            val len = (if (long) 9f else 4f) * d
            c.drawLine(cx, cy - r * 0.9f, cx, cy - r * 0.9f + len, stroke)
            c.rotate(5f, cx, cy)
        }
        c.restore()

        // three bold arcs (counter-clockwise)
        stroke.color = cur; stroke.strokeWidth = 4f * d
        oval.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        c.save(); c.rotate(-rot * 1.2f, cx, cy)
        for (i in 0 until 3) c.drawArc(oval, i * 120f + 8f, 72f, false, stroke)
        c.restore()

        // radar sweep
        fill.shader = sweep
        c.save(); c.rotate(rot * 2.2f, cx, cy)
        c.drawCircle(cx, cy, r * 0.7f, fill)
        c.restore()
        fill.shader = null

        // inner fast arcs (clockwise)
        stroke.color = faded(cur, 220); stroke.strokeWidth = 2.2f * d
        oval.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f)
        c.save(); c.rotate(rot * 3f, cx, cy)
        c.drawArc(oval, 0f, 110f, false, stroke)
        c.drawArc(oval, 180f, 110f, false, stroke)
        c.restore()

        // pulsing core
        val s = 1f + 0.05f * breathe
        c.save(); c.scale(s, s, cx, cy)
        fill.shader = core
        c.drawCircle(cx, cy, r * 0.34f, fill)
        fill.shader = null
        c.restore()

        stroke.color = Color.WHITE; stroke.strokeWidth = 1.6f * d
        c.drawPath(hex, stroke)

        text.color = ContextCompat.getColor(context, R.color.on_accent); text.textSize = r * 0.24f
        c.drawText("J", cx, cy + r * 0.085f, text)
    }
}

/** Static themed backdrop: gradient, faint grid and two soft glows. Drawn once, no timers. */
class HudBackgroundView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = ContextCompat.getColor(ctx, R.color.line_b); strokeWidth = 1f }
    private val bg = Paint()
    private val glowA = Paint()
    private val glowB = Paint()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val top = ContextCompat.getColor(context, R.color.bg_root)
        val bot = ContextCompat.getColor(context, R.color.bg_root2)
        val acc = ContextCompat.getColor(context, R.color.accent)
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bot, Shader.TileMode.CLAMP)
        val r1 = w * 0.9f
        glowA.shader = RadialGradient(w * 0.15f, h * 0.12f, r1, Color.argb(46, Color.red(acc), Color.green(acc), Color.blue(acc)), Color.argb(0, Color.red(acc), Color.green(acc), Color.blue(acc)), Shader.TileMode.CLAMP)
        glowB.shader = RadialGradient(w * 0.95f, h * 0.88f, r1, Color.argb(34, Color.red(acc), Color.green(acc), Color.blue(acc)), Color.argb(0, Color.red(acc), Color.green(acc), Color.blue(acc)), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        c.drawRect(0f, 0f, w, h, glowA)
        c.drawRect(0f, 0f, w, h, glowB)
        val step = 38 * d
        var y = step
        while (y < h) { c.drawLine(0f, y, w, y, grid); y += step }
        var x = step
        while (x < w) { c.drawLine(x, 0f, x, h, grid); x += step }
    }
}

/** A glassy card with neon corner brackets and a glowing top edge (theme aware). */
class HudCard @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val acc = ContextCompat.getColor(ctx, R.color.accent)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d; color = ContextCompat.getColor(ctx, R.color.accent_dim)
    }
    private val brackets = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = acc; strokeCap = Paint.Cap.SQUARE
    }
    private val glow = Paint()
    private val rect = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val a = ContextCompat.getColor(context, R.color.tile_a)
        val b = ContextCompat.getColor(context, R.color.tile_b)
        body.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), a, b, Shader.TileMode.CLAMP)
        glow.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(ContextCompat.getColor(context, R.color.line_c), ContextCompat.getColor(context, R.color.line_a), ContextCompat.getColor(context, R.color.line_c)),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = 6 * d
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        c.drawRoundRect(rect, rad, rad, body)
        c.drawRoundRect(rect, rad, rad, edge)
        c.drawRect(rad, 0f, w - rad, 1.5f * d, glow)
        val l = 16 * d; val i = 1.5f * d
        c.drawLine(i, i + l, i, i, brackets); c.drawLine(i, i, i + l, i, brackets)
        c.drawLine(w - i - l, i, w - i, i, brackets); c.drawLine(w - i, i, w - i, i + l, brackets)
        c.drawLine(i, h - i - l, i, h - i, brackets); c.drawLine(i, h - i, i + l, h - i, brackets)
        c.drawLine(w - i - l, h - i, w - i, h - i, brackets); c.drawLine(w - i, h - i, w - i, h - i - l, brackets)
    }
}

/**
 * Bottom navigation: 5 drawn icons (home, deck, link, core, setup) with an accent bar that slides
 * under the selected one. Static drawing; only redraws on tap.
 */
class NavBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val labels = arrayOf("HOME", "DECK", "LINK", "CORE", "SETUP")
    private val accent = ContextCompat.getColor(ctx, R.color.accent)
    private val dim = ContextCompat.getColor(ctx, R.color.text_dim)
    private val bgPaint = Paint().apply { color = ContextCompat.getColor(ctx, R.color.nav_bg) }
    private val topLine = Paint().apply { color = ContextCompat.getColor(ctx, R.color.accent_dim); strokeWidth = 1f * d }
    private val ic = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER; textSize = 9.5f * d
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    var selected = 0
        private set
    var onSelect: ((Int) -> Unit)? = null
    private var barX = -1f
    private var bounce = FloatArray(5)

    fun select(i: Int, animate: Boolean = true) {
        if (i == selected && barX >= 0f) return
        selected = i
        val target = (width / 5f) * (i + 0.5f)
        if (!animate || barX < 0f || width == 0) { barX = target; invalidate(); return }
        ValueAnimator.ofFloat(barX, target).apply {
            duration = 260; interpolator = DecelerateInterpolator()
            addUpdateListener { barX = it.animatedValue as Float; invalidate() }
        }.start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { bounce[i] = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (62 * d).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        barX = (w / 5f) * (selected + 0.5f)
        bar.shader = LinearGradient(0f, 0f, 28 * d, 0f, intArrayOf(0x00000000, accent, 0x00000000), null, Shader.TileMode.CLAMP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            val i = (e.x / (width / 5f)).toInt().coerceIn(0, 4)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (i != selected) { select(i); onSelect?.invoke(i) }
            return true
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bgPaint)
        c.drawLine(0f, 0f, w, 0f, topLine)
        // sliding indicator
        c.save(); c.translate(barX - 14 * d, 0f); c.drawRect(0f, 0f, 28 * d, 2.5f * d, bar); c.restore()
        val cw = w / 5f
        for (i in 0 until 5) {
            val on = i == selected
            val col = if (on) accent else dim
            ic.color = col; txt.color = col
            val cx = cw * (i + 0.5f)
            val lift = if (on) -2f * d - 3f * d * kotlin.math.sin(bounce[i] * PI.toFloat()) else 0f
            val cy = h * 0.40f + lift
            val s = 8.5f * d
            when (i) {
                0 -> { // house
                    path.reset()
                    path.moveTo(cx - s, cy); path.lineTo(cx, cy - s); path.lineTo(cx + s, cy)
                    path.moveTo(cx - s * 0.7f, cy - s * 0.15f); path.lineTo(cx - s * 0.7f, cy + s * 0.8f)
                    path.lineTo(cx + s * 0.7f, cy + s * 0.8f); path.lineTo(cx + s * 0.7f, cy - s * 0.15f)
                    c.drawPath(path, ic)
                }
                1 -> { // 2x2 grid
                    val g = s * 0.85f; val k = s * 0.08f
                    oval.set(cx - g, cy - g, cx - k, cy - k); c.drawRoundRect(oval, 2 * d, 2 * d, ic)
                    oval.set(cx + k, cy - g, cx + g, cy - k); c.drawRoundRect(oval, 2 * d, 2 * d, ic)
                    oval.set(cx - g, cy + k, cx - k, cy + g); c.drawRoundRect(oval, 2 * d, 2 * d, ic)
                    oval.set(cx + k, cy + k, cx + g, cy + g); c.drawRoundRect(oval, 2 * d, 2 * d, ic)
                }
                2 -> { // two linked circles
                    c.drawCircle(cx - s * 0.5f, cy, s * 0.62f, ic)
                    c.drawCircle(cx + s * 0.5f, cy, s * 0.62f, ic)
                }
                3 -> { // hexagon
                    path.reset()
                    for (k in 0..5) {
                        val a = (PI / 3 * k - PI / 2).toFloat()
                        val x = cx + s * cos(a); val y = cy + s * sin(a)
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); c.drawPath(path, ic)
                    c.drawCircle(cx, cy, s * 0.22f, ic)
                }
                else -> { // sliders
                    c.drawLine(cx - s, cy - s * 0.55f, cx + s, cy - s * 0.55f, ic)
                    c.drawLine(cx - s, cy + s * 0.55f, cx + s, cy + s * 0.55f, ic)
                    fill.color = col
                    c.drawCircle(cx - s * 0.3f, cy - s * 0.55f, s * 0.28f, fill)
                    c.drawCircle(cx + s * 0.35f, cy + s * 0.55f, s * 0.28f, fill)
                }
            }
            c.drawText(labels[i], cx, h * 0.80f, txt)
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
}
