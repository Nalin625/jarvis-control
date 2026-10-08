package com.jarvis.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat

/**
 * Official Android Telecom route. Active only while Jarvis Control is chosen as the phone's call manager
 * (default Phone app). It shows the in-call screen and tells the call assistant what the call is doing.
 */
class JarvisInCallService : InCallService() {
    companion object {
        @Volatile var instance: JarvisInCallService? = null
        @Volatile var current: Call? = null
    }

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            CallAssistService.telecomState(call, state)
            if (state == Call.STATE_DISCONNECTED) {
                if (current === call) current = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CallAssistService.appCtx = applicationContext
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        current = call
        call.registerCallback(cb)
        CallAssistService.appCtx = applicationContext
        if (CallAssistService.mode(this) != "off" && !CallAssistService.alive) CallAssistService.start(this)
        CallAssistService.telecomAdded(call)
        showUi(call)
    }

    override fun onCallRemoved(call: Call) {
        try { call.unregisterCallback(cb) } catch (e: Exception) { }
        if (current === call) current = null
        CallAssistService.telecomRemoved(call)
        try { getSystemService(NotificationManager::class.java).cancel(6) } catch (e: Exception) { }
    }

    /** The in-call screen: full-screen notification for a ringing call, direct launch otherwise. */
    private fun showUi(call: Call) {
        val i = Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (call.state == Call.STATE_RINGING) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("jarvis_incall", "Incoming call screen", NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(this, 20, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(this, "jarvis_incall")
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Incoming call")
                    .setContentText(call.details?.handle?.schemeSpecificPart ?: "")
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
                nm.notify(6, n)
            } catch (e: Exception) { }
        }
        try { startActivity(i) } catch (e: Exception) { }
    }
}
