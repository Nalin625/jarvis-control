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
import android.os.PowerManager
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

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
 * laptop (cyan). Rings spin faster the more "alive" the connection is. Tap it for a shockwave and sparks.
 *
 * Cost: the static rings are built once as Paths (one drawPath each per frame, nothing allocated per frame),
 * it redraws less often while the laptop is not linked or battery saver is on, and nothing runs off screen.
 */
class ReactorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var level = 0
        set(v) {
            if (v == field) return
            field = v
            val target = colorFor(v)
            colourAnim?.cancel()
            colourAnim = ValueAnimator.ofObject(ArgbEvaluator(), cur, target).apply {
                duration = 450
                addUpdateListener { cur = it.animatedValue as Int; rebuild(); invalidate() }
            }
            colourAnim?.start()
            if (isAttachedToWindow && isShown) runTicker()   // the redraw rate depends on the level
        }

    private fun colorFor(l: Int) = when (l) { 2 -> ContextCompat.getColor(context, R.color.accent); 1 -> 0xFFFFC24D.toInt(); else -> 0xFFFF7A5C.toInt() }

    private var cur = colorFor(0)
    private var rot = 0f
    private var pulse = 0f
    private var boost = 0f          // 1 right after a kick, fading to 0: the rings spin up for a moment
    private var shockT = 1f         // tap shockwave progress 0..1; 1 = none
    private var acc = 0f
    private var interval = 0.05f    // seconds between redraws; set by runTicker()
    private var colourAnim: ValueAnimator? = null
    private var glow: RadialGradient? = null
    private var core: RadialGradient? = null
    private var sweep: SweepGradient? = null
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val onAccent = ContextCompat.getColor(ctx, R.color.on_accent)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = onAccent
    }
    private val oval = RectF()
    private val hex = Path()
    private val ticks = Path()                                   // 72 tick marks as one path
    private val ring = Path()                                    // the dashed outer circle
    private val dash = DashPathEffect(floatArrayOf(5 * d, 9 * d), 0f)

    // tap sparks: a fixed pool, so nothing is allocated while they fly
    private val pX = FloatArray(SPARKS)
    private val pY = FloatArray(SPARKS)
    private val pVX = FloatArray(SPARKS)
    private val pVY = FloatArray(SPARKS)
    private val pLife = FloatArray(SPARKS)                       // 0 = dead

    private val ticker = Ticker { dt ->
        val fx = stepEffects(dt)
        acc += dt
        if (fx || acc >= interval) {
            val base = when (level) { 2 -> 38f; 1 -> 22f; else -> 8f }
            val spin = base * (1f + 3f * boost)
            rot = (rot + acc * spin) % 3600f
            pulse += acc * (if (level == 2) 2.4f else 1.4f)
            acc = 0f
            invalidate()
        }
    }

    init {
        setOnClickListener { burst() }
    }

    companion object {
        private const val SPARKS = 22
        private val TO_DEG = (180.0 / PI).toFloat()
    }

    private fun faded(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** Starts the frame ticker at a rate that suits the link state (30 fps linked, 20 idle, 15 on battery saver). */
    private fun runTicker() {
        val saver = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        interval = when {
            saver -> 0.066f
            level == 2 -> 0.033f
            else -> 0.05f
        }
        ticker.start()
    }

    /** Spins the rings up for a moment. The Home page calls this when it opens. */
    fun kick() {
        if (!isShown) return
        boost = 1f
        runTicker()
    }

    /** Tap: a shockwave and a burst of sparks, then it settles back. */
    private fun burst() {
        if (r <= 0f) return
        shockT = 0f
        val start = Random.nextFloat() * 2f * PI.toFloat()
        for (i in 0 until SPARKS) {
            val a = start + 2f * PI.toFloat() * i / SPARKS
            val speed = r * (1.2f + 1.4f * Random.nextFloat())
            pX[i] = cx; pY[i] = cy
            pVX[i] = cos(a) * speed; pVY[i] = sin(a) * speed
            pLife[i] = 1f
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (isShown) runTicker()
    }

    /** Moves the shockwave, sparks and spin boost by dt. Returns true while any of them is still visible. */
    private fun stepEffects(dt: Float): Boolean {
        var alive = false
        if (boost > 0f) { boost = (boost - dt * 1.3f).coerceAtLeast(0f); alive = true }
        if (shockT < 1f) { shockT = (shockT + dt / 0.7f).coerceAtMost(1f); alive = true }
        val drag = (1f - 2.2f * dt).coerceAtLeast(0f)
        for (i in 0 until SPARKS) {
            if (pLife[i] <= 0f) continue
            alive = true
            pLife[i] -= dt / 0.9f
            pX[i] += pVX[i] * dt
            pY[i] += pVY[i] * dt
            pVX[i] *= drag
            pVY[i] *= drag
        }
        return alive
    }

    private fun rebuild() {
        if (r <= 0f) return
        glow = RadialGradient(cx, cy, r, faded(cur, 110), faded(cur, 0), Shader.TileMode.CLAMP)
        core = RadialGradient(cx, cy, r * 0.34f, intArrayOf(Color.WHITE, cur, faded(cur, 40)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        sweep = SweepGradient(cx, cy, intArrayOf(faded(cur, 0), faded(cur, 0), faded(cur, 90)), floatArrayOf(0f, 0.6f, 1f))
    }

    /** Geometry that only changes with the size: tick marks, the dashed ring and the hexagon. Built once. */
    private fun layoutStatic() {
        if (r <= 0f) return
        ticks.reset()
        for (i in 0 until 72) {
            val len = (if (i % 6 == 0) 9f else 4f) * d
            val th = Math.toRadians(i * 5.0)
            val s = sin(th).toFloat()
            val c = cos(th).toFloat()
            // same spokes as before: each one starts at 0.9r and points inwards, turned by i * 5 degrees
            val y1 = -r * 0.9f
            val y2 = y1 + len
            ticks.moveTo(cx - y1 * s, cy + y1 * c)
            ticks.lineTo(cx - y2 * s, cy + y2 * c)
        }
        ring.reset()
        ring.addCircle(cx, cy, r * 0.98f, Path.Direction.CW)
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
        layoutStatic()
        rebuild()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (isShown) runTicker() }
    override fun onDetachedFromWindow() { ticker.stop(); colourAnim?.cancel(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) runTicker() else ticker.stop()
    }
    // pages are switched with GONE, so stop drawing while the Home page is hidden
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow && isShown) runTicker() else ticker.stop()
    }

    override fun onDraw(c: Canvas) {
        if (r <= 0f) return
        val breathe = 0.5f + 0.5f * sin(pulse)

        // soft glow behind everything
        fill.shader = glow; fill.alpha = (150 + 100 * breathe).toInt().coerceAtMost(255)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null; fill.alpha = 255

        // outer dashed ring (slow, clockwise): the fixed circle is turned by the canvas, so nothing is allocated
        c.save()
        c.rotate(rot * 0.6f / (r * 0.98f) * TO_DEG, cx, cy)
        stroke.shader = null; stroke.color = faded(cur, 90); stroke.strokeWidth = 1.5f * d
        stroke.pathEffect = dash
        c.drawPath(ring, stroke)
        stroke.pathEffect = null
        c.restore()

        // tick ring (counter-rotating): one path, one draw call
        c.save(); c.rotate(-rot * 0.15f, cx, cy)
        stroke.color = faded(cur, 140); stroke.strokeWidth = 1.2f * d
        c.drawPath(ticks, stroke)
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

        // tap shockwave: a ring that grows and fades
        if (shockT < 1f) {
            val k = 1f - shockT
            stroke.color = faded(cur, (230 * k * k).toInt())
            stroke.strokeWidth = (1f + 3f * k) * d
            c.drawCircle(cx, cy, r * (0.3f + 0.8f * shockT), stroke)
        }

        // sparks from the tap
        for (i in 0 until SPARKS) {
            val l = pLife[i]
            if (l <= 0f) continue
            fill.color = faded(cur, (255 * l).toInt())
            c.drawCircle(pX[i], pY[i], (1.2f + 2.4f * l) * d, fill)
        }

        // pulsing core
        val s = 1f + 0.05f * breathe
        c.save(); c.scale(s, s, cx, cy)
        fill.shader = core
        c.drawCircle(cx, cy, r * 0.34f, fill)
        fill.shader = null
        c.restore()

        stroke.color = Color.WHITE; stroke.strokeWidth = 1.6f * d
        c.drawPath(hex, stroke)

        c.drawText("J", cx, cy + r * 0.085f, text)
    }
}

/** Static themed backdrop: gradient, faint grid and two soft glows. Drawn once, no timers. */
class HudBackgroundView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = ContextCompat.getColor(ctx, R.color.line_b); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val gridPath = Path()        // every grid line in one path: one draw call
    private val bg = Paint()
    private val glowA = Paint()
    private val glowB = Paint()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val top = ContextCompat.getColor(context, R.color.bg_root)
        val bot = ContextCompat.getColor(context, R.color.bg_root2)
        val acc = ContextCompat.getColor(context, R.color.accent)
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bot, Shader.TileMode.CLAMP)
        val r1 = (w * 0.9f).coerceAtLeast(1f)
        glowA.shader = RadialGradient(w * 0.15f, h * 0.12f, r1, Color.argb(46, Color.red(acc), Color.green(acc), Color.blue(acc)), Color.argb(0, Color.red(acc), Color.green(acc), Color.blue(acc)), Shader.TileMode.CLAMP)
        glowB.shader = RadialGradient(w * 0.95f, h * 0.88f, r1, Color.argb(34, Color.red(acc), Color.green(acc), Color.blue(acc)), Color.argb(0, Color.red(acc), Color.green(acc), Color.blue(acc)), Shader.TileMode.CLAMP)

        gridPath.reset()
        val step = 38 * d
        var y = step
        while (y < h) { gridPath.moveTo(0f, y); gridPath.lineTo(w.toFloat(), y); y += step }
        var x = step
        while (x < w) { gridPath.moveTo(x, 0f); gridPath.lineTo(x, h.toFloat()); x += step }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        c.drawRect(0f, 0f, w, h, glowA)
        c.drawRect(0f, 0f, w, h, glowB)
        c.drawPath(gridPath, grid)
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
 * under the selected one, a spring on the icon, and a ring that pulses out of it. Redraws on tap only.
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
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val path = Path()
    private val oval = RectF()

    var selected = 0
        private set
    var onSelect: ((Int) -> Unit)? = null
    private var barX = -1f
    private var bounce = FloatArray(5)
    private var pulseIdx = 0
    private var pulseT = 1f          // 1 = no ring showing

    fun select(i: Int, animate: Boolean = true) {
        if (i == selected && barX >= 0f) return
        selected = i
        val target = (width / 5f) * (i + 0.5f)
        if (!animate || barX < 0f || width == 0) { barX = target; invalidate(); return }
        ValueAnimator.ofFloat(barX, target).apply {
            duration = 260; interpolator = OvershootInterpolator(1.3f)
            addUpdateListener { barX = it.animatedValue as Float; invalidate() }
        }.start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { bounce[i] = it.animatedValue as Float; invalidate() }
        }.start()
        pulseIdx = i
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480
            addUpdateListener { pulseT = it.animatedValue as Float; invalidate() }
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
        // the ring that pulses out of the newly selected icon
        if (pulseT < 1f) {
            val k = 1f - pulseT
            ring.color = accent
            ring.alpha = (200 * k).toInt()
            c.drawCircle(cw * (pulseIdx + 0.5f), h * 0.40f, 8.5f * d * (1f + 1.4f * pulseT), ring)
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
}
