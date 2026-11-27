package com.jarvis.control.intel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background monitor: every ~6 hours, on any network, it checks your watched sites, watched files, internet, and your account.
 *  It does NOT scan your Wi-Fi devices in the background (that needs you to start it). */
class IntelWork(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        try { runChecks(applicationContext) } catch (_: Throwable) { }
        return Result.success()
    }

    companion object {
        const val CH = "jarvis_intel"
        private const val WORK = "jarvis-intel-monitor"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<IntelWork>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }

        /** One pass of all monitors, also used by 'monitor now'. Returns plain lines for display. */
        fun runChecks(c: Context): List<String> {
            val out = ArrayList<String>()
            val db = IntelDb.get(c)
            for (site in WebTools.watchList(c)) {
                val rep = WebTools.changeCheck(c, site)
                val changed = rep.summary.startsWith("CHANGED")
                out.add("$site: " + if (changed) "CHANGED" else "no change")
                if (changed) { db.event("warn", "Website changed: $site"); notify(c, "Website changed", site) }
            }
            val integ = MediaTools.integrityCheck(c)
            if (integ.summary.contains("differ")) { out.add(integ.summary); db.event("warn", integ.summary); notify(c, "Watched file changed", integ.summary) }
            val prev = db.lastSnap("conn")
            val up = Net.get("https://connectivitycheck.gstatic.com/generate_204", timeoutMs = 6000, maxBytes = 1).code == 204
            db.snap("conn", if (up) "up" else "down")
            out.add("Internet: " + if (up) "up" else "down")
            if (prev == "up" && !up) { db.event("warn", "Internet went down"); notify(c, "Internet is down", "This phone cannot reach the internet.") }
            if (SocialTools.configured(c)) {
                val s = SocialTools.report(c)
                out.add("Account followers: " + (s.findings.firstOrNull { it.label == "Followers" }?.value ?: "not read"))
                if (s.findings.any { it.label == "New post" }) { db.event("info", "New post on your account"); notify(c, "New post on your account", "Open Intelligence to see it.") }
            }
            return out
        }

        private fun notify(c: Context, title: String, text: String) {
            try {
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CH, "Intelligence alerts", NotificationManager.IMPORTANCE_DEFAULT))
                val n = Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(text.take(120)).setAutoCancel(true).build()
                nm.notify(9000 + (System.currentTimeMillis() % 1000).toInt(), n)
            } catch (_: Throwable) { }
        }
    }
}
