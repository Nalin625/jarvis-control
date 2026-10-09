package com.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

/**
 * Watches which app comes to the front. During the night lock, any app that is not allowed is
 * covered by the Jarvis lock screen. It only reads the app name, never the screen contents.
 *
 * While it is connected it runs in the foreground with a quiet notification. That keeps the app
 * alive when it is swiped away from the recent apps, so the lock keeps working.
 * Turn it on in Settings > Accessibility > Jarvis night lock.
 */
class NightLockService : AccessibilityService() {

    companion object {
        /** True while the system has this service connected. */
        @Volatile var alive = false
        private var lastShown = 0L
        private const val CHANNEL = "night_lock"
        private const val NOTIF_ID = 91
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true
        keepAlive()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!NightLock.blocks(this, pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastShown < 600L) return
        lastShown = now
        startActivity(
            Intent(this, NightLockScreen::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("blocked", pkg)
        )
    }

    override fun onInterrupt() {
        // nothing to stop
    }

    override fun onUnbind(intent: Intent?): Boolean {
        alive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        return super.onUnbind(intent)
    }

    /** Runs in the foreground with a quiet notification, so swiping the app away doesn't stop the lock. */
    private fun keepAlive() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Night lock", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, NightLockActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val note: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Night lock blocker is on")
            .setContentText("Keep this on so the night lock keeps working. Tap to open Night Lock.")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, note)
    }
}
