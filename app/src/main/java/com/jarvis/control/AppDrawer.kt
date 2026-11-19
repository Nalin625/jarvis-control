package com.jarvis.control

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * APP INDEX: the Niagara way of finding apps (big thin names, an A-Z strip you slide along, search,
 * pinned apps on top) wrapped in the Jarvis HUD look. It is an overlay on top of the existing
 * Jarvis Control screen, so every page, tile and card of the app stays exactly as it was.
 * Open it with the APPS handle above the bottom bar (tap it or swipe it up). Back or Home closes it.
 */
class AppDrawer private constructor(private val act: Activity) {

    private class AppEntry(val label: String, val pkg: String, val cls: String)
    private class Row(val app: AppEntry?, val title: String?)

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = act.getSharedPreferences("niagara", Context.MODE_PRIVATE)
    private val accent = act.getColor(R.color.accent)
    private val textMain = act.getColor(R.color.text_primary)
    private val dim = act.getColor(R.color.text_dim)
    private val edge = act.getColor(R.color.accent_dim)
    private val bgc = act.getColor(R.color.bg_root)
    private val d = act.resources.displayMetrics.density

    private var apps: List<AppEntry> = emptyList()
    private var rows: List<Row> = emptyList()
    private var letterAt: Map<Char, Int> = emptyMap()
    private var pins: MutableList<String> = mutableListOf()
    private var loadedAt = 0L
    private var isOpen = false

    private lateinit var overlay: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var list: ListView
    private lateinit var strip: AlphaStrip
    private lateinit var search: EditText
    private lateinit var count: TextView
    private lateinit var bubble: HexBubble
    private lateinit var scan: View
    private lateinit var handle: HandleView
    private var handleOff = false
    private var backCb: OnBackPressedCallback? = null
    private val adapter = RowAdapter()

    private var downY = 0f
    private var downX = 0f
    private var atTopAtDown = false
    private var swipeOk = false

    private fun dp(v: Int): Int = (v * d).toInt()
    private fun light(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private fun mono(): Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private fun faded(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    // ------------------------------------------------------------------ build

    private fun build() {
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        overlay = FrameLayout(act)
        overlay.visibility = View.GONE
        overlay.isClickable = true
        overlay.setBackgroundColor(faded(bgc, 245))
        overlay.addView(GridBg(act, faded(accent, 22)), FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(6))
        }
        head.addView(TextView(act).apply {
            text = "◢ APP INDEX"
            textSize = 11f
            typeface = mono()
            letterSpacing = 0.25f
            setTextColor(accent)
            setOnLongClickListener { menu(); true }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        count = TextView(act).apply {
            textSize = 10f
            typeface = mono()
            setTextColor(dim)
        }
        head.addView(count)
        panel.addView(head)
        panel.addView(GlowLine(act, accent), LinearLayout.LayoutParams(-1, dp(2)).apply { setMargins(dp(24), 0, dp(24), 0) })

        val sr = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        sr.addView(TextView(act).apply {
            text = "›"
            textSize = 22f
            typeface = mono()
            setTextColor(accent)
            setPadding(0, 0, dp(10), dp(2))
        })
        search = EditText(act).apply {
            hint = "search apps"
            setHintTextColor(faded(dim, 160))
            setTextColor(textMain)
            textSize = 18f
            typeface = light()
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            setPadding(0, dp(8), 0, dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { rebuildRows() }
            })
            setOnEditorActionListener { _, _, _ ->
                rows.firstOrNull { it.app != null }?.app?.let { launch(it) }
                true
            }
        }
        sr.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(sr)

        list = ListView(act).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(24))
            adapter = this@AppDrawer.adapter
            setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.app?.let { launch(it) } }
            setOnItemLongClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.app?.let { appMenu(it) }; true }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(panel, FrameLayout.LayoutParams(-1, -1))

        overlay.addView(Brackets(act, accent), FrameLayout.LayoutParams(-1, -1))
        strip = AlphaStrip(act, accent, dim, onPick = { ch -> pick(ch) },
            onRelease = { bubble.animate().alpha(0f).setDuration(160).start() })
        overlay.addView(strip, FrameLayout.LayoutParams(dp(44), -1, Gravity.END).apply {
            topMargin = dp(118); bottomMargin = dp(20)
        })
        bubble = HexBubble(act, accent)
        bubble.alpha = 0f
        overlay.addView(bubble, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER))
        scan = View(act).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(faded(accent, 0), faded(accent, 150), faded(accent, 0)))
            alpha = 0f
        }
        overlay.addView(scan, FrameLayout.LayoutParams(-1, dp(46)))
        content.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        handle = HandleView(act, accent, dim, edge, bgc) { open() }
        content.addView(handle, FrameLayout.LayoutParams(dp(132), dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(70)
        })

        (act as? ComponentActivity)?.let { ca ->
            val cb = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (search.text.isNotEmpty()) search.setText("") else close()
                }
            }
            ca.onBackPressedDispatcher.addCallback(ca, cb)
            backCb = cb
        }
    }

    // ------------------------------------------------------------------ apps

    private fun loadApps() {
        loadedAt = System.currentTimeMillis()
        worker.execute {
            val pm = act.packageManager
            val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(probe, 0).mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                AppEntry(ri.loadLabel(pm).toString(), ai.packageName, ai.name)
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }
            ui.post {
                apps = found
                pins = (prefs.getString("pins", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()
                rebuildRows()
            }
        }
    }

    private fun savePins() { prefs.edit().putString("pins", pins.joinToString(",")).apply() }

    private fun rebuildRows() {
        val q = search.text.toString().trim().lowercase(Locale.getDefault())
        val out = ArrayList<Row>()
        var allStart = 0
        if (q.isEmpty()) {
            val pinned = pins.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
            if (pinned.isNotEmpty()) {
                out.add(Row(null, "PINNED"))
                pinned.forEach { out.add(Row(it, null)) }
                out.add(Row(null, "ALL APPS"))
                allStart = out.size
            }
            apps.forEach { out.add(Row(it, null)) }
        } else {
            apps.filter { it.label.lowercase(Locale.getDefault()).contains(q) }.forEach { out.add(Row(it, null)) }
        }
        rows = out
        val idx = HashMap<Char, Int>()
        for (i in allStart until out.size) out[i].app?.let { idx.getOrPut(firstLetter(it.label)) { i } }
        letterAt = idx
        strip.visibility = if (q.isEmpty()) View.VISIBLE else View.INVISIBLE
        count.text = apps.size.toString() + " APPS"
        adapter.notifyDataSetChanged()
    }

    private fun firstLetter(label: String): Char {
        val c = label.trim().firstOrNull()?.uppercaseChar() ?: return '#'
        return if (c in 'A'..'Z') c else '#'
    }

    private fun pick(ch: Char) {
        val order = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var i = order.indexOf(ch)
        var target = -1
        while (i < order.length) {
            val t = letterAt[order[i]]
            if (t != null) { target = t; break }
            i++
        }
        if (target < 0) target = (rows.size - 1).coerceAtLeast(0)
        list.setSelectionFromTop(target, 0)
        bubble.setLetter(ch)
        bubble.animate().cancel()
        bubble.alpha = 0.95f
    }

    // ------------------------------------------------------------------ actions

    private fun launch(e: AppEntry) {
        try {
            act.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(e.pkg, e.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            ui.postDelayed({ close() }, 250)
        } catch (ex: Exception) {
            Toast.makeText(act, "Can't open " + e.label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun appMenu(e: AppEntry) {
        val names = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        if (e.pkg in pins) {
            names.add("Unpin"); acts.add { pins.remove(e.pkg); savePins(); rebuildRows() }
            names.add("Move up"); acts.add { movePin(e.pkg, -1) }
            names.add("Move down"); acts.add { movePin(e.pkg, 1) }
        } else {
            names.add("Pin to top"); acts.add {
                if (pins.size >= 8) Toast.makeText(act, "8 pins is the limit. Unpin one first.", Toast.LENGTH_SHORT).show()
                else { pins.add(e.pkg); savePins(); rebuildRows() }
            }
        }
        names.add("App info"); acts.add {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)))
        }
        names.add("Uninstall"); acts.add {
            try { act.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg))) } catch (ex: Exception) {
                Toast.makeText(act, "Can't uninstall this one here", Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog.Builder(act).setTitle(e.label).setItems(names.toTypedArray()) { _, w -> acts[w]() }.show()
    }

    private fun movePin(pkg: String, by: Int) {
        val i = pins.indexOf(pkg)
        val j = i + by
        if (i < 0 || j < 0 || j >= pins.size) return
        pins.removeAt(i); pins.add(j, pkg)
        savePins(); rebuildRows()
    }

    private fun menu() {
        val names = arrayOf("Make Jarvis my home screen", "Clear all pins")
        AlertDialog.Builder(act).setTitle("App index").setItems(names) { _, w ->
            when (w) {
                0 -> try { act.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (ex: Exception) {
                    Toast.makeText(act, "Open Settings > Apps > Default apps > Home app", Toast.LENGTH_LONG).show()
                }
                1 -> { pins.clear(); savePins(); rebuildRows() }
            }
        }.show()
    }

    // ------------------------------------------------------------------ open / close

    fun open() {
        if (isOpen) return
        isOpen = true
        if (System.currentTimeMillis() - loadedAt > 5000) loadApps()
        overlay.visibility = View.VISIBLE
        handle.visibility = View.INVISIBLE
        overlay.bringToFront()
        overlay.alpha = 0f
        panel.translationY = dp(70).toFloat()
        panel.scaleX = 0.96f; panel.scaleY = 0.96f
        overlay.animate().alpha(1f).setDuration(220).start()
        panel.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        sweep()
        list.setSelectionFromTop(0, 0)
        backCb?.isEnabled = true
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        backCb?.isEnabled = false
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        search.clearFocus()
        overlay.animate().alpha(0f).setDuration(180).withEndAction {
            if (!isOpen) { overlay.visibility = View.GONE }
        }.start()
        panel.animate().translationY(dp(60).toFloat()).setDuration(180).start()
        handle.visibility = if (handleOff) View.GONE else View.VISIBLE
    }

    /** A bright line sweeps down the screen once when the index opens. */
    private fun sweep() {
        val h = overlay.height.coerceAtLeast(dp(600))
        scan.animate().cancel()
        scan.translationY = 0f
        scan.alpha = 1f
        scan.animate().translationY(h.toFloat()).alpha(0f).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun listAtTop(): Boolean =
        list.firstVisiblePosition == 0 && (list.childCount == 0 || list.getChildAt(0).top >= list.paddingTop)

    private fun inStrip(rawX: Float): Boolean {
        val loc = IntArray(2)
        strip.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + strip.width
    }

    /** Called by the activity for every touch. Swipe down with the list at the top closes the index. Never swallows touches. */
    fun watch(ev: MotionEvent) {
        if (!isOpen) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                swipeOk = !inStrip(ev.rawX)
                atTopAtDown = listAtTop()
            }
            MotionEvent.ACTION_UP -> if (swipeOk && atTopAtDown) {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (dy > dp(110) && dy > abs(dx) * 1.5f) close()
            }
            MotionEvent.ACTION_CANCEL -> swipeOk = false
        }
    }

    // ------------------------------------------------------------------ list rows

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].app == null) 0 else 1
        override fun isEnabled(position: Int) = rows[position].app != null
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = rows[position]
            val tv = (convertView as? TextView) ?: TextView(act).apply {
                maxLines = 1
                if (r.app == null) {
                    textSize = 10f
                    typeface = mono()
                    letterSpacing = 0.3f
                    setTextColor(accent)
                    setPadding(dp(24), dp(18), dp(24), dp(4))
                } else {
                    textSize = 24f
                    typeface = light()
                    setTextColor(textMain)
                    setPadding(dp(24), dp(11), dp(52), dp(11))
                }
            }
            tv.text = r.app?.label ?: r.title
            return tv
        }
    }

    companion object {
        private var instance: AppDrawer? = null

        /** Call once, right after setContentView(). Adds the APPS handle and the app index on top of the screen. */
        @JvmStatic
        fun attach(act: Activity) {
            if (instance?.act === act) return
            val dr = AppDrawer(act)
            dr.build()
            dr.loadApps()
            instance = dr
        }

        /** Closes the app index if it is open. Returns true if it was. */
        @JvmStatic
        fun closeIfOpen(act: Activity): Boolean {
            val i = instance?.takeIf { it.act === act } ?: return false
            if (!i.isOpen) return false
            i.close(); return true
        }

        /** Opens the app index (used by the new Home). */
        @JvmStatic
        fun open(act: Activity) { if (instance?.act === act) instance?.open() }

        /** The new Home opens the index itself, so the small APPS handle is not needed. */
        @JvmStatic
        fun hideHandle(act: Activity) {
            val i = instance?.takeIf { it.act === act } ?: return
            i.handleOff = true
            i.handle.visibility = View.GONE
        }

        /** The Home button was pressed while Jarvis is already showing: close the index. */
        @JvmStatic
        fun onHome(act: Activity) { if (instance?.act === act) instance?.close() }

        /** Optional: feed every touch of the activity here (dispatchTouchEvent) so a swipe down closes the index. */
        @JvmStatic
        fun touch(act: Activity, ev: MotionEvent) { if (instance?.act === act) instance?.watch(ev) }
    }
}

// ====================================================================== drawn pieces

/** Faint HUD grid behind the list. Drawn once. */
private class GridBg(ctx: Context, private val color: Int) : View(ctx) {
    private val p = Paint().apply { this.color = this@GridBg.color; strokeWidth = 1f }
    override fun onDraw(c: Canvas) {
        val step = 38 * resources.displayMetrics.density
        var y = step
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, p); y += step }
        var x = step
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), p); x += step }
    }
}

/** Thin line that glows in the middle and fades at both ends. */
private class GlowLine(ctx: Context, private val accent: Int) : View(ctx) {
    private val p = Paint()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent)), accent,
                Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent))), null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
}

/** Neon corner brackets around the screen. Touches pass through. */
private class Brackets(ctx: Context, accent: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = accent; strokeCap = Paint.Cap.SQUARE
    }
    init { isClickable = false; isFocusable = false }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val i = 8 * d; val l = 22 * d
        c.drawLine(i, i + l, i, i, p); c.drawLine(i, i, i + l, i, p)
        c.drawLine(w - i - l, i, w - i, i, p); c.drawLine(w - i, i, w - i, i + l, p)
        c.drawLine(i, h - i - l, i, h - i, p); c.drawLine(i, h - i, i + l, h - i, p)
        c.drawLine(w - i - l, h - i, w - i, h - i, p); c.drawLine(w - i, h - i, w - i, h - i - l, p)
    }
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

/** Hexagon with the current letter, shown while you slide along the A-Z strip. */
private class HexBubble(ctx: Context, private val accent: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var letter = "A"
    private val path = Path()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = accent
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    init { isClickable = false }
    fun setLetter(ch: Char) { letter = ch.toString(); invalidate() }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f - 4 * d
        path.reset()
        for (k in 0..5) {
            val a = (PI / 3 * k - PI / 2).toFloat()
            val x = cx + r * cos(a); val y = cy + r * sin(a)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
        text.textSize = r * 1.0f
        c.drawText(letter, cx, cy + r * 0.34f, text)
    }
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

/** The "APPS" handle above the bottom bar: tap it, or swipe it up. */
private class HandleView(
    ctx: Context, private val accent: Int, private val dim: Int, private val edge: Int, private val bg: Int,
    private val onOpen: () -> Unit
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(210, Color.red(bg), Color.green(bg), Color.blue(bg)) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = edge }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = accent; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = accent; textSize = 11f * d
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.3f
    }
    private val rect = android.graphics.RectF()
    private var y0 = 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        c.drawRoundRect(rect, h / 2f, h / 2f, body)
        c.drawRoundRect(rect, h / 2f, h / 2f, line)
        c.drawLine(w * 0.3f, 1.5f * d, w * 0.7f, 1.5f * d, glow)
        // chevron
        val cx = w * 0.2f; val cy = h / 2f; val s = 4f * d
        c.drawLine(cx - s, cy + s * 0.5f, cx, cy - s * 0.5f, glow)
        c.drawLine(cx, cy - s * 0.5f, cx + s, cy + s * 0.5f, glow)
        c.drawText("APPS", w * 0.58f, h / 2f + 4f * d, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { y0 = e.rawY; return true }
            MotionEvent.ACTION_UP -> {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onOpen()
                return true
            }
        }
        return true
    }
}

/** The A-Z strip. Slide a finger along it: letters near the finger bend towards you, you feel a tick, and the list jumps. */
private class AlphaStrip(
    ctx: Context,
    private val hot: Int,
    private val cold: Int,
    private val onPick: (Char) -> Unit,
    private val onRelease: () -> Unit
) : View(ctx) {
    private val letters = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var touchY = -1f
    private var last = ' '

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val slot = height.toFloat() / letters.length
        p.textSize = (slot * 0.62f).coerceAtMost(d * 15f)
        for (i in letters.indices) {
            val cy = slot * i + slot / 2f
            var pull = 0f
            var isHot = false
            if (touchY >= 0f) {
                val k = (cy - touchY) / slot
                pull = exp(-(k * k) / 4.5f)
                isHot = abs(k) < 0.6f
            }
            val x = width / 2f - pull * d * 26f
            p.color = if (isHot) hot else cold
            c.drawText(letters[i].toString(), x, cy + p.textSize / 3f, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchY = e.y
                val slot = height.toFloat() / letters.length
                val ch = letters[(e.y / slot).toInt().coerceIn(0, letters.length - 1)]
                if (ch != last) {
                    last = ch
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPick(ch)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchY = -1f
                last = ' '
                onRelease()
                invalidate()
            }
        }
        return true
    }
}
