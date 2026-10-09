package com.jarvis.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Runs when the phone has started (after power-on or reboot, once it is unlocked for the first time):
 * - logs the start and tells the laptop about it (always);
 * - starts the control server if "Start on phone boot" is ticked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                PhoneStartReporter.recordBoot(app)
                // The laptop's Wi-Fi can come up a few seconds after the phone does, so try a few times.
                // Anything still unsent is retried by JarvisService and when the app is opened.
                PhoneStartReporter.deliver(app, 3, 10_000L)
            } finally {
                pending.finish()
            }
        }.start()

        val p = context.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
        if (!p.getBoolean("start_on_boot", false)) return
        val svc = Intent(context, JarvisService::class.java)
            .putExtra(JarvisService.ACTION_TOKEN_EXTRA, p.getString("token", "") ?: "")
        ContextCompat.startForegroundService(context, svc)
    }
}
