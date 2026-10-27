package com.jarvis.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Rule-of-thirds grid drawn over the live picture. */
class GridLines(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 2f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 3, 0f, w / 3, h, paint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, paint)
        canvas.drawLine(0f, h / 3, w, h / 3, paint)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, paint)
    }
}
