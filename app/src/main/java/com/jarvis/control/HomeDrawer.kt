package com.jarvis.control

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Consumer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.hypot

/**
 * Phone-apps drawer for the Jarvis home screen.
 * Hold the reactor (the Jarvis circle) and every app on the phone blooms out of it. Search to find one
 * (type "insta" for Instagram). Tap = the app zooms open from its icon. Back / Home closes it.
 *
 * Usage (one line in MainActivity.onCreate, after the views exist): HomeDrawer.attach(this, binding.reactorView)
 */
object HomeDrawer {
    fun attach(act: AppCompatActivity, trigger: View) {
        DrawerImpl(act, trigger).install()
    }
}

private class DrawerApp(val label: String, val pkg: String, val cls: String, val icon: Drawable)

private class DrawerImpl(val act: AppCompatActivity, val trigger: View) {
    private val ACC = 0xFF4FE3FF.toInt()
    private val DIM = 0xFF6F8B93.toInt()
    private val FAINT = 0xFF3D5157.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val cols = 4

    private var all: List<DrawerApp> = emptyList()
    private var shown: List<DrawerApp> = emptyList()
    private var open = false
    private var animatingIn = false
    private var loadedAt = 0L

    private lateinit var root: ViewGroup
    private lateinit var overlay: FrameLayout
    private lateinit var search: EditText
    private lateinit var count: TextView
    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter
    private lateinit var back: OnBackPressedCallback

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun round(fill: Int, stroke: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(r).toFloat()
        g.setStroke(dp(1), stroke)
        return g
    }

    fun install() {
        root = act.findViewById(android.R.id.content)
        overlay = FrameLayout(act)
        overlay.setBackgroundColor(0xF505080B.toInt())
        overlay.isClickable = true
        overlay.visibility = View.INVISIBLE
        try {
            overlay.addView(HudBackgroundView(act), FrameLayout.LayoutParams(-1, -1))
        } catch (e: Exception) {
        }

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(18), dp(14), 0)

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(act)
        title.text = "// APPS"
        title.setTextColor(ACC)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.typeface = Typeface.create("monospace", Typeface.BOLD)
        title.letterSpacing = 0.12f
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        count = TextView(act)
        count.setTextColor(DIM)
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        count.typeface = Typeface.create("monospace", Typeface.NORMAL)
        head.addView(count)
        col.addView(head)

        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(14), 0, dp(4), 0)
        bar.background = round(0xFF0F1F26.toInt(), ACC, 24)
        search = EditText(act)
        search.hint = "Search apps  (try: insta)"
        search.setHintTextColor(FAINT)
        search.setTextColor(0xFFDCEEF2.toInt())
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        search.background = null
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                shown.firstOrNull()?.let { launch(it, search) }
                true
            } else {
                false
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString() ?: "")
            }
        })
        bar.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = TextView(act)
        x.text = "✕"
        x.setTextColor(ACC)
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        x.setPadding(dp(14), dp(12), dp(14), dp(12))
        x.setOnClickListener { close(true) }
        bar.addView(x)
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(10)
        col.addView(bar, blp)

        list = ListView(act)
        list.divider = null
        list.dividerHeight = 0
        list.setSelector(android.R.color.transparent)
        list.overScrollMode = View.OVER_SCROLL_NEVER
        adapter = RowAdapter()
        list.adapter = adapter
        val llp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        llp.topMargin = dp(8)
        col.addView(list, llp)

        overlay.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, ViewGroup.LayoutParams(-1, -1))

        trigger.setOnLongClickListener {
            trigger.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            show()
            true
        }

        back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                close(true)
            }
        }
        act.onBackPressedDispatcher.addCallback(act, back)
        act.addOnNewIntentListener(Consumer<Intent> { if (open) close(false) })
        act.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (open) close(false)
            }
        })
    }

    // ------------------------------------------------------------------ open / close
    private fun content(): View? = if (root.childCount > 0 && root.getChildAt(0) !== overlay) root.getChildAt(0) else null

    private fun center(): IntArray {
        val t = IntArray(2)
        val o = IntArray(2)
        trigger.getLocationInWindow(t)
        overlay.getLocationInWindow(o)
        return intArrayOf(t[0] - o[0] + trigger.width / 2, t[1] - o[1] + trigger.height / 2)
    }

    private fun show() {
        if (open) return
        open = true
        back.isEnabled = true
        search.setText("")
        if (all.isEmpty() || System.currentTimeMillis() - loadedAt > 30000) load()
        filter("")
        list.setSelection(0)
        animatingIn = true
        adapter.notifyDataSetChanged()
        overlay.visibility = View.VISIBLE
        content()?.animate()?.scaleX(0.9f)?.scaleY(0.9f)?.alpha(0.2f)?.setDuration(320)?.start()
        try {
            val c = center()
            val r = hypot(overlay.width.toDouble(), overlay.height.toDouble()).toFloat()
            ViewAnimationUtils.createCircularReveal(overlay, c[0], c[1], 0f, r).apply {
                duration = 480
                interpolator = DecelerateInterpolator(1.6f)
                start()
            }
        } catch (e: Exception) {
        }
        ui.postDelayed({ animatingIn = false }, 1100)
    }

    private fun close(animate: Boolean) {
        if (!open) return
        open = false
        back.isEnabled = false
        animatingIn = false
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(overlay.windowToken, 0)
        content()?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(if (animate) 300 else 0)?.start()
        if (!animate) {
            overlay.visibility = View.INVISIBLE
            return
        }
        try {
            val c = center()
            val r = hypot(overlay.width.toDouble(), overlay.height.toDouble()).toFloat()
            val a = ViewAnimationUtils.createCircularReveal(overlay, c[0], c[1], r, 0f)
            a.duration = 340
            a.interpolator = AccelerateInterpolator(1.3f)
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!open) overlay.visibility = View.INVISIBLE
                }
            })
            a.start()
        } catch (e: Exception) {
            overlay.visibility = View.INVISIBLE
        }
    }

    // ------------------------------------------------------------------ apps
    private fun load() {
        Thread {
            val pm = act.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val out = ArrayList<DrawerApp>()
            for (ri in pm.queryIntentActivities(i, 0)) {
                try {
                    out.add(DrawerApp(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.activityInfo.name, ri.loadIcon(pm)))
                } catch (e: Exception) {
                }
            }
            out.sortBy { it.label.lowercase() }
            ui.post {
                all = out
                loadedAt = System.currentTimeMillis()
                filter(search.text.toString())
            }
        }.start()
    }

    private fun filter(q: String) {
        val s = q.trim().lowercase()
        shown = if (s.isEmpty()) all else all.filter { it.label.lowercase().contains(s) }
        count.text = if (all.isEmpty()) "LOADING..." else "${shown.size} / ${all.size}"
        adapter.notifyDataSetChanged()
    }

    private fun launch(a: DrawerApp, from: View) {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.component = ComponentName(a.pkg, a.cls)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            val w = if (from.width > 0) from.width else 1
            val h = if (from.height > 0) from.height else 1
            val o = ActivityOptions.makeScaleUpAnimation(from, 0, 0, w, h)
            act.startActivity(i, o.toBundle())
        } catch (e: Exception) {
            Toast.makeText(act, "Can't open " + a.label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun options(a: DrawerApp) {
        AlertDialog.Builder(act)
            .setTitle(a.label)
            .setItems(arrayOf("App info", "Uninstall")) { _, which ->
                if (which == 0) {
                    act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)))
                } else {
                    act.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
                }
            }
            .show()
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = (shown.size + cols - 1) / cols
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            if (convertView == null) {
                row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(dp(2), dp(7), dp(2), dp(7))
                for (c in 0 until cols) {
                    val cell = LinearLayout(act)
                    cell.orientation = LinearLayout.VERTICAL
                    cell.gravity = Gravity.CENTER_HORIZONTAL
                    cell.setPadding(dp(2), dp(4), dp(2), dp(4))
                    val ic = ImageView(act)
                    cell.addView(ic, LinearLayout.LayoutParams(dp(54), dp(54)))
                    val nm = TextView(act)
                    nm.setTextColor(0xFFDCEEF2.toInt())
                    nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    nm.maxLines = 1
                    nm.gravity = Gravity.CENTER
                    nm.ellipsize = TextUtils.TruncateAt.END
                    cell.addView(nm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    cell.setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(3f)).start()
                        }
                        false
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            } else {
                row = convertView as LinearLayout
            }
            for (c in 0 until cols) {
                val cell = row.getChildAt(c) as LinearLayout
                val idx = position * cols + c
                cell.animate().cancel()
                if (idx < shown.size) {
                    val a = shown[idx]
                    cell.visibility = View.VISIBLE
                    (cell.getChildAt(0) as ImageView).setImageDrawable(a.icon)
                    (cell.getChildAt(1) as TextView).text = a.label
                    cell.setOnClickListener { launch(a, cell.getChildAt(0)) }
                    cell.setOnLongClickListener {
                        options(a)
                        true
                    }
                    if (animatingIn) {
                        // bloom out of the reactor: small, low, invisible -> springs into place, row by row
                        cell.alpha = 0f
                        cell.scaleX = 0.4f
                        cell.scaleY = 0.4f
                        cell.translationY = dp(46).toFloat()
                        cell.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                            .setStartDelay(160L + minOf(position, 9) * 55L + c * 28L)
                            .setDuration(380).setInterpolator(OvershootInterpolator(1.7f)).start()
                    } else {
                        cell.alpha = 1f
                        cell.scaleX = 1f
                        cell.scaleY = 1f
                        cell.translationY = 0f
                    }
                } else {
                    cell.visibility = View.INVISIBLE
                    cell.setOnClickListener(null)
                    cell.setOnLongClickListener(null)
                }
            }
            return row
        }
    }
}
