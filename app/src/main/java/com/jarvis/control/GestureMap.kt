package com.jarvis.control

import android.content.Context
import org.json.JSONArray

/**
 * One action from the laptop's gesture map. The phone trains the SIGN (the name the phone sends);
 * the PLACE it is sent with picks which action runs, the same way the laptop's resolver does.
 */
data class MapAction(
    val id: Int,
    val emoji: String,
    val sign: String,          // what the phone trains and sends, e.g. "point_right"
    val place: String,         // where it runs: global, windows, browser, media, jarvis, or target_* (needs the screen)
    val status: String,        // ready, needs_command, needs_tool, phone_model, vision
    val description: String,
    val shares: List<Int>      // other actions with the same sign in the same place; none of them runs while they share
)

/** The 68 actions, from app/src/main/assets/gesture_map.json (made by make_gesture_map_asset.py on the laptop). */
object GestureMap {

    @Volatile
    private var cache: List<MapAction>? = null

    fun load(ctx: Context): List<MapAction> =
        cache ?: synchronized(this) {
            cache ?: parse(ctx.applicationContext.assets.open("gesture_map.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
                .also { cache = it }
        }

    fun parse(text: String): List<MapAction> {
        val arr = JSONArray(text)
        val out = ArrayList<MapAction>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val shares = o.optJSONArray("shares") ?: JSONArray()
            out.add(
                MapAction(
                    id = o.getInt("id"),
                    emoji = o.optString("emoji", ""),
                    sign = o.getString("label"),
                    place = o.getString("context"),
                    status = o.getString("status"),
                    description = o.getString("description"),
                    shares = (0 until shares.length()).map { shares.getInt(it) }
                )
            )
        }
        return out
    }

    /** Places the phone can be in, in map order. The target_* places need the screen, so the phone cannot set them. */
    fun places(actions: List<MapAction>): List<String> =
        actions.map { it.place }.distinct().filter { !it.startsWith("target") }

    /** Words a person can read for each status. */
    fun statusWord(status: String): String = when (status) {
        "ready" -> "ready"
        "needs_command" -> "needs a JARVIS command first"
        "needs_tool" -> "needs the laptop mouse helper"
        "phone_model" -> "needs motion or two-hand tracking"
        "vision" -> "needs the laptop to see the screen"
        else -> status.replace('_', ' ')
    }
}
