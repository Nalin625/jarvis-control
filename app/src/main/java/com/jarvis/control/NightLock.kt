package com.jarvis.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import java.util.Calendar

/**
 * The night lock. Between the start and end times only the phone app, WhatsApp, the home screen,
 * the notification shade, the keyboard and Jarvis itself can open. Every other app is covered by the
 * Jarvis lock screen. While the lock is on, its settings cannot be changed, and the Settings app is
 * blocked, so the accessibility switch cannot be turned off either.
 *
 * Times are minutes after midnight: 22:00 is 1320, 07:00 is 420.
 */
object NightLock {
    private const val PREFS = "jarvis_control"
    const val DEFAULT_START = 22 * 60
    const val DEFAULT_END = 7 * 60

    /** Always open at night. Emergency calls go through these. */
    private val ALWAYS_OPEN = setOf(
        "com.whatsapp",            // WhatsApp
        "com.android.incallui",    // the call screen
        "com.android.phone",       // calls and emergency dialling
        "com.android.emergency",   // emergency information
        "com.android.systemui",    // notifications, quick settings, recents
        "android",                 // system dialogs
        "com.jarvis.control"       // Jarvis itself
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("night_on", false)
    fun startMin(ctx: Context): Int = prefs(ctx).getInt("night_start", DEFAULT_START)
    fun endMin(ctx: Context): Int = prefs(ctx).getInt("night_end", DEFAULT_END)

    fun setSchedule(ctx: Context, on: Boolean, startMin: Int, endMin: Int) {
        prefs(ctx).edit()
            .putBoolean("night_on", on)
            .putInt("night_start", startMin)
            .putInt("night_end", endMin)
            .apply()
    }

    fun hhmm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    /** Minutes after midnight right now. */
    fun nowMin(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** True while the time is inside the window. Handles windows that cross midnight. */
    fun inWindow(ctx: Context, now: Int = nowMin()): Boolean {
        val s = startMin(ctx)
        val e = endMin(ctx)
        return if (s <= e) now >= s && now < e else now >= s || now < e
    }

    /** On and inside the window: the lock is active and its settings are fixed. */
    fun locked(ctx: Context): Boolean = enabled(ctx) && inWindow(ctx)

    /** Whether an app that just came to the front should be covered by the lock screen. */
    fun blocks(ctx: Context, pkg: String): Boolean {
        if (!locked(ctx)) return false
        if (pkg in ALWAYS_OPEN) return false
        return pkg !in openNow(ctx)
    }

    /** Also always open: the phone app the phone uses now, the home screen, and the keyboard. */
    private fun openNow(ctx: Context): Set<String> {
        val out = HashSet<String>()
        try {
            ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out.add(it) }
        } catch (e: Exception) {
            // no phone app to add
        }
        try {
            ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName?.let { out.add(it) }
        } catch (e: Exception) {
            // no home screen to add
        }
        try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotEmpty() }
                ?.let { out.add(it) }
        } catch (e: Exception) {
            // no keyboard to add
        }
        return out
    }
}
