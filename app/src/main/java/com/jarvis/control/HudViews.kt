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
import android.view.View
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

    private fun colorFor(l: Int) = when (l) { 2 -> 0xFF4FE3FF.toInt(); 1 -> 0xFFFFC24D.toInt(); else -> 0xFFFF7A5C.toInt() }

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

    private val ticker = Ticker { dt ->
        val speed = when (level) { 2 -> 38f; 1 -> 22f; else -> 8f }
        rot = (rot + dt * speed) % 3600f
        pulse += dt * (if (level == 2) 2.4f else 1.4f)
        invalidate()
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

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) ticker.start() else ticker.stop()
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

        text.color = 0xFF04121A.toInt(); text.textSize = r * 0.24f
        c.drawText("J", cx, cy + r * 0.085f, text)
    }
}

/** Full-screen animated backdrop: drifting grid, a slow scan line and floating data motes. */
class HudBackgroundView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = 0x1A4FE3FF; strokeWidth = 1f }
    private val scan = Paint()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x664FE3FF }
    private val bg = Paint()
    private var t = 0f
    private val motes = FloatArray(16 * 3).also { a ->
        val rnd = java.util.Random(7)
        for (i in 0 until 16) { a[i * 3] = rnd.nextFloat(); a[i * 3 + 1] = rnd.nextFloat(); a[i * 3 + 2] = 0.01f + rnd.nextFloat() * 0.03f }
    }
    private var acc = 0f
    // the full-screen backdrop only repaints ~30x per second to save battery
    private val ticker = Ticker { dt ->
        acc += dt
        if (acc >= 0.033f) { t += acc; acc = 0f; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF05080B.toInt(), 0xFF071620.toInt(), Shader.TileMode.CLAMP)
        scan.shader = LinearGradient(0f, 0f, 0f, 90 * d, intArrayOf(0x004FE3FF, 0x244FE3FF, 0x004FE3FF), null, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) ticker.start() else ticker.stop()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        val step = 38 * d
        val off = (t * 6 * d) % step
        var y = -step + off
        while (y < h) { c.drawLine(0f, y, w, y, grid); y += step }
        var x = 0f
        while (x < w) { c.drawLine(x, 0f, x, h, grid); x += step }
        // scan line sweeps top -> bottom every ~9 s
        val sy = ((t / 9f) % 1f) * (h + 90 * d) - 90 * d
        c.save(); c.translate(0f, sy); c.drawRect(0f, 0f, w, 90 * d, scan); c.restore()
        for (i in 0 until 16) {
            val py = (((motes[i * 3 + 1] - t * motes[i * 3 + 2]) % 1f) + 1f) % 1f
            c.drawCircle(motes[i * 3] * w, py * h, (1.2f + (i % 3)) * d, dot)
        }
    }
}

/** A glassy card with neon corner brackets and a glowing top edge. */
class HudCard @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0xFF173039.toInt() }
    private val brackets = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFF4FE3FF.toInt(); strokeCap = Paint.Cap.SQUARE
    }
    private val glow = Paint()
    private val rect = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        body.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xF20F1E28.toInt(), 0xF2081219.toInt(), Shader.TileMode.CLAMP)
        glow.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(0x004FE3FF, 0xCC4FE3FF.toInt(), 0x004FE3FF), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = 6 * d
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        c.drawRoundRect(rect, rad, rad, body)
        c.drawRoundRect(rect, rad, rad, edge)
        // glowing top edge
        c.drawRect(rad, 0f, w - rad, 1.5f * d, glow)
        // corner brackets
        val l = 16 * d; val i = 1.5f * d
        c.drawLine(i, i + l, i, i, brackets); c.drawLine(i, i, i + l, i, brackets)
        c.drawLine(w - i - l, i, w - i, i, brackets); c.drawLine(w - i, i, w - i, i + l, brackets)
        c.drawLine(i, h - i - l, i, h - i, brackets); c.drawLine(i, h - i, i + l, h - i, brackets)
        c.drawLine(w - i - l, h - i, w - i, h - i, brackets); c.drawLine(w - i, h - i, w - i, h - i - l, brackets)
    }
}
