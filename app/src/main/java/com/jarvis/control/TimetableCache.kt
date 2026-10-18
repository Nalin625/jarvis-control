package com.jarvis.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Today's timetable kept on the phone, so it can be read and ticked while the laptop is off.
 * A tick made while the laptop is away waits in a queue and is sent when the laptop answers again.
 * Everything here is safe to call from any thread; the network calls must run off the main thread.
 */
object TimetableCache {
    private const val PREFS = "jarvis_timetable"
    private const val KEY_DAY = "day_json"
    private const val KEY_FETCHED = "fetched_at"
    private const val KEY_QUEUE = "tick_queue"

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keeps the laptop's answer for one day (the reply of GET /timetable/day). */
    fun save(ctx: Context, day: JSONObject) {
        prefs(ctx).edit()
            .putString(KEY_DAY, day.toString())
            .putLong(KEY_FETCHED, System.currentTimeMillis())
            .apply()
    }

    fun savedDay(ctx: Context): JSONObject? {
        val raw = prefs(ctx).getString(KEY_DAY, null) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    fun fetchedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_FETCHED, 0L)

    /** The queued ticks: each is {"id", "on", "done"}. */
    private fun queue(ctx: Context): List<JSONObject> {
        val raw = prefs(ctx).getString(KEY_QUEUE, null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun writeQueue(ctx: Context, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    private fun sameItem(t: JSONObject, id: Int, on: String): Boolean =
        t.optInt("id", -1) == id && t.optString("on") == on

    /** Records a tick. A newer tick for the same block and day replaces the older one. */
    @Synchronized
    fun queueTick(ctx: Context, id: Int, on: String, done: Boolean) {
        val kept = queue(ctx).filterNot { sameItem(it, id, on) }
        writeQueue(ctx, kept + JSONObject().put("id", id).put("on", on).put("done", done))
    }

    @Synchronized
    fun pendingCount(ctx: Context): Int = queue(ctx).size

    /** The day's blocks as the laptop last sent them, with the queued ticks laid on top. Empty if the copy is not for [on]. */
    @Synchronized
    fun itemsFor(ctx: Context, on: String): List<JSONObject> {
        val day = savedDay(ctx) ?: return emptyList()
        if (day.optString("date") != on) return emptyList()
        val arr = day.optJSONArray("items") ?: return emptyList()
        val ticks = queue(ctx).filter { it.optString("on") == on }
            .associate { it.optInt("id", -1) to it.optBoolean("done") }
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optBoolean("brk")) continue
            ticks[item.optInt("id", -1)]?.let { item.put("done", it) }
            out.add(item)
        }
        return out.sortedBy { it.optString("start") }
    }

    /**
     * Sends the queued ticks to the laptop. A tick is dropped from the queue only when the laptop answered;
     * when it is off or unreachable the tick stays queued. Returns how many were sent.
     * Call off the main thread.
     */
    fun flush(ctx: Context): Int {
        val snapshot = synchronized(this) { queue(ctx) }
        if (snapshot.isEmpty()) return 0
        val sent = ArrayList<JSONObject>()
        for (t in snapshot) {
            val body = JSONObject()
                .put("id", t.optInt("id", -1))
                .put("on", t.optString("on"))
                .put("done", t.optBoolean("done"))
            val reply = LaptopApi.call(ctx, "POST", "/timetable/done", body)
            if (!reply.has("error")) sent.add(t)
        }
        synchronized(this) {
            // keep anything queued while the sends were running, and anything that failed
            val rest = queue(ctx).filterNot { cur ->
                sent.any { s ->
                    sameItem(cur, s.optInt("id", -1), s.optString("on")) &&
                        cur.optBoolean("done") == s.optBoolean("done")
                }
            }
            writeQueue(ctx, rest)
        }
        return sent.size
    }

    /** Asks the laptop for today's plan and keeps it. Returns true when the laptop answered. Call off the main thread. */
    fun fetchToday(ctx: Context): Boolean {
        val reply = LaptopApi.call(ctx, "GET", "/timetable/day?date=" + today(), null, 3000)
        if (reply.has("error") || !reply.has("items")) return false
        save(ctx, reply)
        return true
    }

    // ------------------------------------------------------------ what the home-screen widget shows (pure, no Android calls)

    /** "HH:MM" as minutes after midnight, or -1 when the text is not a time. */
    fun minutesOf(hhmm: String): Int {
        val parts = hhmm.trim().split(":")
        if (parts.size != 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        return if (h in 0..23 && m in 0..59) h * 60 + m else -1
    }

    /** The block to show now: the first one not done that has not ended yet. Null when none is left. */
    fun nextFor(items: List<JSONObject>, nowMin: Int): JSONObject? =
        items.firstOrNull { !it.optBoolean("done") && minutesOf(it.optString("end")) > nowMin }

    /** The widget's three lines: [label, block, time]. Items are today's blocks (breaks already left out). */
    fun widgetLines(items: List<JSONObject>, nowMin: Int): Array<String> {
        if (items.isEmpty()) {
            return arrayOf("TODAY", "No plan saved yet", "Open Jarvis Control while the laptop is on")
        }
        val done = items.count { it.optBoolean("done") }
        val next = nextFor(items, nowMin)
            ?: return arrayOf(
                "TODAY",
                if (done == items.size) "All blocks done" else "Nothing else today",
                "$done of ${items.size} ticked"
            )
        val title = next.optString("title")
        return if (minutesOf(next.optString("start")) <= nowMin) {
            arrayOf("NOW", title, "until " + next.optString("end"))
        } else {
            arrayOf("NEXT", title, "starts at " + next.optString("start"))
        }
    }

    /** The next minute of the day after [nowMin] when a block starts or ends, or null when none is left. */
    fun nextChangeMinute(items: List<JSONObject>, nowMin: Int): Int? =
        items.flatMap { listOf(minutesOf(it.optString("start")), minutesOf(it.optString("end"))) }
            .filter { it > nowMin }
            .minOrNull()
}
