package com.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app comes to the front. During the night lock, any app that is not allowed is
 * covered by the Jarvis lock screen. It only reads the app name, never the screen contents.
 * Turn it on in Settings > Accessibility > Jarvis night lock.
 */
class NightLockService : AccessibilityService() {

    companion object {
        /** True while the system has this service connected. */
        @Volatile var alive = false
        private var lastShown = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true
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
        return super.onUnbind(intent)
    }
}
