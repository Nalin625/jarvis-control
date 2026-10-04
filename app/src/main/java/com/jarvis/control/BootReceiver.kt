package com.jarvis.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts the control server after the phone reboots, if "Start on phone boot" is ticked. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = context.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
        if (!p.getBoolean("start_on_boot", false)) return
        val svc = Intent(context, JarvisService::class.java)
            .putExtra(JarvisService.ACTION_TOKEN_EXTRA, p.getString("token", "") ?: "")
        ContextCompat.startForegroundService(context, svc)
    }
}
