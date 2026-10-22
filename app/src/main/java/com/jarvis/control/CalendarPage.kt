package com.jarvis.control

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.CalendarContract
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The month calendar that replaces the PHONE TOOLS swipe page.
 * Reads the phone's own calendar (your Google events show here when they are synced to the phone).
 * Read-only: it never adds, changes or deletes events.
 */
class CalendarPage(private val act: Activity, private val dp: (Int) -> Int) {
    val view = LinearLayout(act)
    val root: ScrollView = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        addView(view)
    }

    private val cyan = 0xFF4FE3FF.toInt()
    private val dim = 0xFF8FA6AE.toInt()
    private val white = 0xFFEAF7FB.toInt()
    private val panel = 0xFF0B1A22.toInt()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var month: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); zeroTime() }
    private var selected: Calendar = Calendar.getInstance().apply { zeroTime() }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(dp(14), dp(10), dp(14), dp(14))
        render()
    }

    fun refresh() = render()

    private fun Calendar.zeroTime() {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun render() {
        view.removeAllViews()
        if (act.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            view.addView(label("CALENDAR", cyan, 13f, bold = true))
            view.addView(label("Allow Jarvis to read your calendar. Google events show here when they are synced to this phone.", dim, 11f))
            view.addView(button("ALLOW CALENDAR") { act.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 78) })
            return
        }

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(button("<") { month.add(Calendar.MONTH, -1); selected = month.clone() as Calendar; render() })
        head.addView(label(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time), white, 14f, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(button(">") { month.add(Calendar.MONTH, 1); selected = month.clone() as Calendar; render() })
        view.addView(head)

        val events = eventsByDay()
        val grid = GridLayout(act)
        grid.columnCount = 7
        for (d in listOf("S", "M", "T", "W", "T", "F", "S")) grid.addView(cell(d, dim, false, false, null))
        val lead = month.get(Calendar.DAY_OF_WEEK) - 1
        for (i in 0 until lead) grid.addView(cell("", dim, false, false, null))
        val today = Calendar.getInstance()
        for (day in 1..month.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val isToday = today.get(Calendar.YEAR) == month.get(Calendar.YEAR) &&
                today.get(Calendar.MONTH) == month.get(Calendar.MONTH) &&
                today.get(Calendar.DAY_OF_MONTH) == day
            val isSelected = selected.get(Calendar.YEAR) == month.get(Calendar.YEAR) &&
                selected.get(Calendar.MONTH) == month.get(Calendar.MONTH) &&
                selected.get(Calendar.DAY_OF_MONTH) == day
            val mark = if (events[day] != null) "•" else ""
            grid.addView(cell("$day$mark", if (isToday) cyan else white, isToday, isSelected) {
                selected = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }
                render()
            })
        }
        view.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val picked = selected.get(Calendar.DAY_OF_MONTH)
        val dayTitle = SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(selected.time)
        view.addView(label(dayTitle.uppercase(Locale.getDefault()), cyan, 12f, bold = true).apply { topMargin = dp(10) })
        val list = events[picked]
        if (list.isNullOrEmpty()) {
            view.addView(label("Nothing planned.", dim, 12f))
        } else {
            list.forEach { view.addView(label(it, white, 12f)) }
        }
    }

    /** Events in this month, grouped by day of month. All-day events are read in UTC, where Android stores them. */
    private fun eventsByDay(): Map<Int, List<String>> {
        val out = HashMap<Int, MutableList<String>>()
        val start = month.timeInMillis
        val end = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
        try {
            CalendarContract.Instances.query(act.contentResolver, proj, start, end).use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: "(no title)"
                    val begin = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    val cal = Calendar.getInstance(if (allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
                    cal.timeInMillis = begin
                    val timeText = if (allDay) "All day" else timeFmt.format(begin)
                    out.getOrPut(cal.get(Calendar.DAY_OF_MONTH)) { mutableListOf() }.add("$timeText  $title")
                }
            }
        } catch (e: Exception) {
            // no calendar app or no permission: the page just shows nothing planned
        }
        return out
    }

    private fun cellWidth(): Int = (act.resources.displayMetrics.widthPixels - dp(28)) / 7

    private fun cell(s: String, color: Int, bold: Boolean, selected: Boolean, onTap: (() -> Unit)?): TextView =
        TextView(act).apply {
            text = s
            setTextColor(color)
            textSize = 12f
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (selected) background = GradientDrawable().apply {
                setStroke(dp(1), cyan); cornerRadius = dp(6).toFloat(); setColor(panel)
            }
            layoutParams = GridLayout.LayoutParams().apply { width = cellWidth(); height = dp(38) }
            if (onTap != null) setOnClickListener { onTap() }
        }

    private fun button(labelText: String, onTap: () -> Unit): TextView = TextView(act).apply {
        text = labelText
        textSize = 13f
        setTextColor(cyan)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setStroke(dp(1), cyan); cornerRadius = dp(8).toFloat(); setColor(panel) }
        setOnClickListener { onTap() }
    }

    private fun label(s: String, color: Int, size: Float, bold: Boolean = false): TextView = TextView(act).apply {
        text = s
        setTextColor(color)
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }
}
