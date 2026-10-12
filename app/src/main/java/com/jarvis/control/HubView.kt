package com.jarvis.control

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.PowerManager
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Hub's five orbs. They circle the reactor, bob gently and turn their rings. Tap one: it swells,
 * the others drift away, then [onOrb] opens its page. It redraws only while the Hub is on screen.
 */
class HubView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Called with the orb's index (0 DECK, 1 PHONE, 2 LINK, 3 CORE, 4 SETUP) when its tap animation ends. */
    var onOrb: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val glyphs = arrayOf("🖥", "📱", "🔗", "🧠", "⚙")
    private val names = arrayOf("DECK", "PHONE", "LINK", "CORE", "SETUP")
    private val colors = intArrayOf(
        ContextCompat.getColor(ctx, R.color.accent),
        ContextCompat.getColor(ctx, R.color.neon_violet),
        ContextCompat.getColor(ctx, R.color.neon_blue),
        ContextCompat.getColor(ctx, R.color.warning),
        ContextCompat.getColor(ctx, R.color.success)
    )
    private val halos = Array(5) { i ->
        val c = colors[i]
        RadialGradient(
            0f, 0f, 54f * d,
            Color.argb(110, Color.red(c), Color.green(c), Color.blue(c)),
            Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)),
            Shader.TileMode.CLAMP
        )
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND
    }
    private val spoke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = Color.argb(70, Color.red(colors[0]), Color.green(colors[0]), Color.blue(colors[0]))
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 6f * d), 0f)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 19f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * d
        letterSpacing = 0.14f
    }
    private val arc = RectF()
    private val px = FloatArray(5)          // orb centres, in this view's coordinates, from the last draw
    private val py = FloatArray(5)
    private val t0 = System.nanoTime()
    private var orbR = 30f * d
    private var rx = 120f * d               // orbit radii, set in onSizeChanged
    private var ry = 110f * d
    private var active = false
    private var saver = false
    private var pressed = -1
    private var tapping = -1                // the orb being tapped, or -1
    private var tapT = 0f                   // 0..1 through the tap animation
    private var tapAnim: ValueAnimator? = null

    /** Turns the animation on while the Hub is shown, and off when another page covers it. */
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            tapping = -1
            tapT = 0f
            saver = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
            postInvalidateOnAnimation()
        }
    }

    /** Where orb [i] is on the window, so a page can grow out of it. */
    fun orbCentreInWindow(i: Int): FloatArray {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return floatArrayOf(loc[0] + px[i], loc[1] + py[i])
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        orbR = 30f * d
        rx = w * 0.39f
        ry = h * 0.36f
        place(0f)
    }

    /** Sets where every orb is at time [t] seconds: a slow orbit with a small bob. */
    private fun place(t: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val spin = if (saver) 0f else t * 0.10f
        for (i in 0 until 5) {
            val a = -PI.toFloat() / 2f + i * 2f * PI.toFloat() / 5f + spin
            val bob = if (saver) 0f else sin(t * 1.4f + i * 1.3f) * 5f * d
            px[i] = cx + rx * cos(a)
            py[i] = cy + ry * sin(a) + bob
        }
    }

    override fun onDraw(c: Canvas) {
        val t = (System.nanoTime() - t0) / 1_000_000_000f
        place(t)
        val cx = width / 2f
        val cy = height / 2f
        for (i in 0 until 5) c.drawLine(cx, cy, px[i], py[i], spoke)
        for (i in 0 until 5) {
            var s = 1f
            var a = 1f
            if (tapping >= 0) {
                if (i == tapping) s = 1f + 0.4f * sin(tapT * PI.toFloat()) else a = (1f - tapT).coerceIn(0f, 1f)
            }
            if (i == pressed) s *= 0.92f
            val col = colors[i]
            c.save()
            c.translate(px[i], py[i])
            halo.shader = halos[i]
            halo.alpha = (255f * a).toInt()
            c.drawCircle(0f, 0f, 54f * d * s, halo)
            fill.color = withAlpha(col, 46f * a)
            c.drawCircle(0f, 0f, orbR * s, fill)
            ring.color = withAlpha(col, 235f * a)
            c.drawCircle(0f, 0f, orbR * s, ring)
            // three arcs that turn round the orb
            val r = orbR * 1.28f * s
            arc.set(-r, -r, r, r)
            ring.color = withAlpha(col, 150f * a)
            val turn = t * (if (i % 2 == 0) 70f else -55f) + i * 40f
            for (k in 0 until 3) c.drawArc(arc, turn + k * 120f, 64f, false, ring)
            glyph.color = withAlpha(col, 255f * a)
            c.drawText(glyphs[i], 0f, 7f * d, glyph)
            label.color = withAlpha(col, 230f * a)
            c.drawText(names[i], 0f, orbR * 1.7f + 10f * d, label)
            c.restore()
        }
        if (active) {
            if (saver) postInvalidateDelayed(66L) else postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tapping >= 0) return false
                pressed = orbAt(e.x, e.y)
                invalidate()
                return pressed >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed >= 0 && orbAt(e.x, e.y) != pressed) { pressed = -1; invalidate() }
                return pressed >= 0
            }
            MotionEvent.ACTION_UP -> {
                val hit = pressed
                pressed = -1
                if (hit >= 0 && orbAt(e.x, e.y) == hit) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tap(hit)
                }
                invalidate()
                return hit >= 0
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
                return true
            }
        }
        return false
    }

    private fun orbAt(x: Float, y: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until 5) {
            val dx = x - px[i]
            val dy = y - py[i]
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= orbR * 1.4f && dist < bestDist) { best = i; bestDist = dist }
        }
        return best
    }

    /** The tapped orb swells, the others fade, then [onOrb] fires. It stays in this state until the Hub is shown again. */
    private fun tap(i: Int) {
        if (tapping >= 0) return
        tapping = i
        tapAnim?.cancel()
        tapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = DecelerateInterpolator()
            addUpdateListener { tapT = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    val done = tapping
                    if (done >= 0) onOrb?.invoke(done)
                }
            })
            start()
        }
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb(a.coerceIn(0f, 255f).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && active) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        active = false
        tapping = -1
        tapAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
