package com.jarvis.control

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.util.Random
import kotlin.math.min
import kotlin.math.sin

/** Opening screen: a spinning JARVIS core, a glitch-in title and "Made by Nalin". Then the app opens. */
class SplashActivity : AppCompatActivity() {

    companion object {
        const val SPLASH_MS = 3600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(SplashView(this))
        handler.postDelayed({ openMain() }, SPLASH_MS)
    }

    private fun openMain() {
        if (opened || isFinishing) return
        opened = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** Draws the splash frame by frame. Uses about the same colours as the laptop command center. */
private class SplashView(context: Context) : View(context) {

    private val bg = Color.parseColor("#050B16")
    private val blue = Color.parseColor("#2F7BFF")
    private val cyan = Color.parseColor("#43D8FF")
    private val mint = Color.parseColor("#3FF5C9")
    private val magenta = Color.parseColor("#FF4FD8")
    private val text = Color.parseColor("#D6ECFF")
    private val dim = Color.parseColor("#6283A6")
    private val line = Color.parseColor("#1D4466")

    private val started = SystemClock.uptimeMillis()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val rng = Random(7)
    private val specks = Array(60) { floatArrayOf(rng.nextFloat(), rng.nextFloat(), 0.02f + rng.nextFloat() * 0.05f) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (SystemClock.uptimeMillis() - started) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        val cx = w / 2f
        val cy = h * 0.40f
        val ease = min(1f, t / 0.8f)
        val r = unit * 0.27f

        canvas.drawColor(bg)

        // soft blue glow behind the core
        fill.shader = RadialGradient(cx, cy, w * 0.6f,
            intArrayOf(withAlpha(blue, 0.30f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // drifting specks
        fill.color = withAlpha(cyan, 0.35f)
        for (s in specks) {
            val y = ((s[1] - t * s[2]) % 1f + 1f) % 1f * h
            canvas.drawCircle(s[0] * w, y, 2f, fill)
        }

        // three rings of arcs, spinning in opposite directions
        val rings = listOf(
            Triple(1.0f, 0.9f, cyan), Triple(0.8f, -1.4f, blue),
            Triple(0.61f, 2.2f, mint), Triple(0.42f, -2.8f, cyan),
        )
        ring.strokeWidth = unit * 0.005f
        for ((scale, speed, colour) in rings) {
            val rr = r * scale * ease
            val box = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            ring.color = withAlpha(colour, 0.85f * ease)
            for (k in 0 until 3) {
                // three arcs per ring, each 86 degrees long, evenly spaced; the start turns with time
                val startDeg = Math.toDegrees((t * speed + k * 2.0943951f).toDouble()).toFloat()
                canvas.drawArc(box, startDeg, 86f, false, ring)
            }
        }

        // pulsing diamond core
        val pulse = 0.5f + 0.5f * sin(t * 4f)
        val side = unit * (0.05f + 0.01f * pulse) * ease
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(45f + t * 35f)
        fill.color = withAlpha(cyan, 0.12f + 0.12f * pulse)
        canvas.drawRect(-side / 2, -side / 2, side / 2, side / 2, fill)
        ring.color = withAlpha(cyan, 0.95f)
        ring.strokeWidth = unit * 0.005f
        canvas.drawRect(-side / 2, -side / 2, side / 2, side / 2, ring)
        canvas.restore()

        // title, glitching when it appears and now and then after
        val titleY = cy + r + unit * 0.05f
        val titleSize = unit * 0.085f
        titlePaint.textSize = titleSize
        if (t > 0.2f) {
            val glitch = t < 1.1f || rng.nextFloat() < 0.07f
            if (glitch) {
                for (i in 0 until 3) {
                    val y0 = titleY - titleSize * 0.8f + rng.nextFloat() * titleSize * 1.1f
                    val dx = (rng.nextFloat() * 2f - 1f) * unit * 0.025f
                    canvas.save()
                    canvas.clipRect(0f, y0, w, y0 + unit * 0.012f + rng.nextFloat() * unit * 0.02f)
                    titlePaint.color = withAlpha(if (dx > 0) magenta else cyan, 0.9f)
                    canvas.drawText("JARVIS", cx + dx, titleY, titlePaint)
                    canvas.restore()
                }
            }
            titlePaint.color = withAlpha(text, min(1f, (t - 0.2f) / 0.5f))
            canvas.drawText("JARVIS", cx, titleY, titlePaint)
        }

        // a scan line sweeping down
        val scanY = (t * h * 0.22f) % h
        fill.color = withAlpha(cyan, 0.10f)
        canvas.drawRect(0f, scanY, w, scanY + 3f, fill)

        // "Made by Nalin" fades in last
        val madeAlpha = ((t - 1.3f) / 0.7f).coerceIn(0f, 1f)
        smallPaint.textSize = unit * 0.034f
        smallPaint.color = withAlpha(cyan, madeAlpha)
        canvas.drawText("Made by Nalin", cx, titleY + unit * 0.11f, smallPaint)

        // loading bar
        val barW = unit * 0.7f
        val barY = h - unit * 0.10f
        fill.color = line
        canvas.drawRect(cx - barW / 2, barY, cx + barW / 2, barY + unit * 0.006f, fill)
        fill.color = withAlpha(mint, 0.9f)
        val progress = min(1f, t / (SplashActivity.SPLASH_MS / 1000f))
        canvas.drawRect(cx - barW / 2, barY, cx - barW / 2 + barW * progress, barY + unit * 0.006f, fill)
        smallPaint.textSize = unit * 0.022f
        smallPaint.color = dim
        canvas.drawText("booting the command center", cx, barY - unit * 0.02f, smallPaint)

        postInvalidateOnAnimation()
    }

    private fun withAlpha(colour: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(colour), Color.green(colour), Color.blue(colour))
    }
}
