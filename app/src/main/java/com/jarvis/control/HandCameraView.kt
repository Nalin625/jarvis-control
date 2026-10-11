package com.jarvis.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt

/**
 * What the phone camera sees, mirrored like a mirror, with your hand outlined on top:
 * the bones are cyan lines, the 21 joints are dots, a box shows where the hand is, and a ring
 * sits on the index fingertip (filled while you pinch). The picture stays on this phone.
 */
class HandCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var picture: Bitmap? = null
    private var hand: List<FloatArray>? = null
    private var score = 0f
    private var label: String? = null

    /** Shown in the middle while there is no picture, for example "Camera is off". */
    var message: String = "Camera is off. Press START CAMERA."
        set(value) {
            field = value
            invalidate()
        }

    private val bg = Color.parseColor("#05090F")
    private val cyan = Color.parseColor("#4FE3FF")
    private val frame = RectF()
    private val rim = RectF()
    private val box = RectF()
    private val clip = Path()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(170, 79, 227, 255)
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3f)
        color = Color.argb(235, 79, 227, 255)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(9f)
        color = Color.argb(60, 79, 227, 255)
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = cyan
    }
    private val pinchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 79, 227, 255)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(225, 79, 227, 255)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9FB8C6")
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // a faint cyan sheen at the top, so the picture looks like it is behind glass
        glassPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(60, 79, 227, 255), Color.argb(10, 79, 227, 255), Color.argb(0, 79, 227, 255)),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** Called on the main thread with each picture and hand. A null picture keeps the last one on screen. */
    fun update(pic: Bitmap?, landmarks: List<FloatArray>?, sure: Float, name: String?) {
        if (pic != null) picture = pic
        hand = landmarks
        score = sure
        label = name
        invalidate()
    }

    /** Removes the picture (the camera stopped). */
    fun clear() {
        picture = null
        hand = null
        label = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = dp(18f)
        frame.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(frame, r, r, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(bg)
        val pic = picture
        if (pic != null && pic.width > 0 && pic.height > 0) {
            // fit the picture in the box, keep its shape, and show it mirrored like a mirror
            val s = minOf(w / pic.width, h / pic.height)
            val dw = pic.width * s
            val dh = pic.height * s
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            canvas.save()
            canvas.translate(left + dw, top)
            canvas.scale(-s, s)
            canvas.drawBitmap(pic, 0f, 0f, null)
            canvas.restore()
            canvas.drawRect(frame, glassPaint)
            drawHand(canvas, left, top, dw, dh)
        } else {
            canvas.drawText(message, w / 2f, h / 2f, hintPaint)
        }
        canvas.restore()

        rim.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        canvas.drawRoundRect(rim, r, r, rimPaint)
    }

    private fun drawHand(canvas: Canvas, left: Float, top: Float, dw: Float, dh: Float) {
        val hl = hand ?: return
        if (hl.size < 21) return
        // The landmarks are upright and not mirrored, so x is flipped to line up with the mirrored picture.
        val right = left + dw
        fun px(i: Int): Float = right - hl[i][0] * dw
        fun py(i: Int): Float = top + hl[i][1] * dh

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 21) {
            val x = px(i)
            val y = py(i)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        // the box around the hand, with the name and how sure it is above it
        val pad = dp(10f)
        box.set(minX - pad, minY - pad, maxX + pad, maxY + pad)
        canvas.drawRoundRect(box, dp(10f), dp(10f), boxPaint)
        val sure = (score * 100).toInt()
        val caption = if (label != null) "Hand $sure% | $label" else "Hand $sure%"
        canvas.drawText(caption, box.left, maxOf(dp(16f), box.top - dp(6f)), captionPaint)

        // bones (a wide soft line underneath, then the sharp one), then the joints
        for (pair in BONES) {
            val a = pair.first
            val b = pair.second
            canvas.drawLine(px(a), py(a), px(b), py(b), glowPaint)
        }
        for (pair in BONES) {
            val a = pair.first
            val b = pair.second
            canvas.drawLine(px(a), py(a), px(b), py(b), bonePaint)
        }
        for (i in 0 until 21) {
            val fingertip = i == 4 || i == 8 || i == 12 || i == 16 || i == 20
            canvas.drawCircle(px(i), py(i), dp(if (fingertip) 4f else 2.8f), jointPaint)
        }

        // the index fingertip: a ring, filled while the thumb and finger are pinched together
        val size = dist(hl[0], hl[9]).coerceAtLeast(1e-4f)
        val pinched = dist(hl[4], hl[8]) / size < 0.25f
        val tx = px(8)
        val ty = py(8)
        canvas.drawCircle(tx, ty, dp(15f), tipPaint)
        if (pinched) canvas.drawCircle(tx, ty, dp(8f), pinchPaint)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        /** The 21 hand joints, joined the same way MediaPipe joins them. */
        val BONES = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )
    }
}
