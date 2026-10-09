package com.jarvis.control

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tells the laptop when the phone starts. Every boot becomes one event in a small queue kept
 * on the phone. An event leaves the queue only after the laptop has answered "ok", so a boot
 * that happens while the laptop is off or out of reach is reported on a later try.
 */
object PhoneStartReporter {
    private const val PREFS = "jarvis_control"
    private const val KEY_QUEUE = "start_queue"
    private const val MAX_QUEUE = 50

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY_QUEUE, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun save(ctx: Context, queue: JSONArray) {
        // commit (not apply) so the queue is on disk before the receiver can be killed
        prefs(ctx).edit().putString(KEY_QUEUE, queue.toString()).commit()
    }

    private fun batteryInfo(ctx: Context): JSONObject {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return JSONObject().put("battery", -1).put("charging", false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return JSONObject().put("battery", pct).put("charging", charging)
    }

    /** Called on every boot: queues this start once. Never throws. */
    @Synchronized
    fun recordBoot(ctx: Context) {
        try {
            // The moment of boot = wall clock minus time since boot. The laptop then gets the
            // real start time even when the event is delivered later.
            val bootSec = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000
            val id = "boot-$bootSec"
            val queue = load(ctx)
            for (i in 0 until queue.length()) {
                if (queue.getJSONObject(i).optString("id") == id) return
            }
            val ev = batteryInfo(ctx)
                .put("id", id)
                .put("event", "started")
                .put("boot_ms", bootSec * 1000)
                .put("queued_ms", System.currentTimeMillis())
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            queue.put(ev)
            while (queue.length() > MAX_QUEUE) queue.remove(0)
            save(ctx, queue)
        } catch (e: Exception) {
            // logging must never break the boot receiver
        }
    }

    /**
     * Sends queued events to the laptop, oldest first. Each one the laptop confirms is dropped;
     * at the first failure the rest stay queued. Returns how many are still waiting.
     * Blocking: call from a background thread only.
     */
    @Synchronized
    fun flush(ctx: Context): Int {
        val queue = load(ctx)
        if (queue.length() == 0) return 0
        if (!LaptopApi.paired(ctx)) return queue.length()
        val waiting = JSONArray()
        var blocked = false
        for (i in 0 until queue.length()) {
            val ev = queue.getJSONObject(i)
            if (!blocked) {
                val reply = LaptopApi.call(ctx, "POST", "/phone/event", ev, 5000)
                if (reply.optBoolean("ok", false)) continue
                blocked = true
            }
            waiting.put(ev)
        }
        save(ctx, waiting)
        return waiting.length()
    }

    /** Tries up to [tries] times, [pauseMs] apart, until the queue is empty. Background thread only. */
    fun deliver(ctx: Context, tries: Int, pauseMs: Long) {
        for (i in 0 until tries) {
            if (flush(ctx) == 0) return
            if (i < tries - 1) Thread.sleep(pauseMs)
        }
    }
}
