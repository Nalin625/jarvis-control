package com.jarvis.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** One tappable tile on the Home or Deck page. */
class Tile(val glyph: String, val label: String, val action: () -> Unit)

/** Small animation helpers. Everything uses GPU view properties (scale/alpha/translation), no per-frame drawing. */
object Fx {
    private fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Squash on touch-down, springy overshoot on release. Never swallows the click. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** Gives every button and checkbox under [root] the press animation. */
    fun attachAll(root: View) {
        if (root is Button || root is CheckBox) press(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) attachAll(root.getChildAt(i))
    }

    /** Children of [parent] fade and rise in one after another. */
    fun stagger(parent: ViewGroup, step: Long = 55L) {
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(parent.context, 18f).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(step * i).setDuration(360)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Tiles pop in with a springy scale, in reading order. */
    fun popIn(views: List<View>, step: Long = 28L) {
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f; v.scaleX = 0.6f; v.scaleY = 0.6f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(step * i).setDuration(380)
                .setInterpolator(OvershootInterpolator(2.2f)).start()
        }
    }

    fun tile(ctx: Context, t: Tile): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
            setPadding(dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f))
            addView(TextView(ctx).apply {
                text = t.glyph; textSize = 24f; gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(TextView(ctx).apply {
                text = t.label.uppercase(); textSize = 9.5f; gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
                maxLines = 2
                setPadding(0, dp(ctx, 5f), 0, 0)
            })
            setOnClickListener { t.action() }
        }
        press(box)
        return box
    }

    /** Builds a [cols]-wide grid of tiles. Returns the container; its tiles are in [tiles] order. */
    fun grid(ctx: Context, tiles: List<Tile>, cols: Int = 3, heightDp: Float = 84f): LinearLayout {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val gap = dp(ctx, 4f)
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, t ->
            if (i % cols == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                col.addView(row)
            }
            row!!.addView(tile(ctx, t), LinearLayout.LayoutParams(0, dp(ctx, heightDp), 1f).apply {
                setMargins(gap, gap, gap, gap)
            })
        }
        // pad the last row so tiles keep their width
        val rem = tiles.size % cols
        if (rem != 0) for (k in 0 until cols - rem)
            row!!.addView(View(ctx), LinearLayout.LayoutParams(0, dp(ctx, heightDp), 1f).apply { setMargins(gap, gap, gap, gap) })
        return col
    }

    /** All the tile views of a grid made by [grid], flattened. */
    fun tilesOf(grid: LinearLayout): List<View> {
        val out = ArrayList<View>()
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as ViewGroup
            for (i in 0 until row.childCount) if (row.getChildAt(i) is LinearLayout) out.add(row.getChildAt(i))
        }
        return out
    }
}
