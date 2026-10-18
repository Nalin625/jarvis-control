package com.jarvis.control

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Calendar

/**
 * Home-screen widget: the block to do now, or the next one, from the phone's own copy of today's plan.
 * It never calls the laptop, so it works with the laptop off. It is redrawn when the app syncs, when a tick
 * is made, by the system every 30 minutes, and at each block start or end (an alarm), so it changes on time.
 */
class TimetableWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, buildViews(context))
        scheduleNextChange(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.jarvis.control.TIMETABLE_WIDGET_REFRESH"
        private const val REQUEST_CHANGE = 4411

        /** Redraws every timetable widget from the saved copy. No network. Does nothing when no widget is placed. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimetableWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) manager.updateAppWidget(id, buildViews(context))
            scheduleNextChange(context)
        }

        private fun nowMinute(): Int {
            val now = Calendar.getInstance()
            return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_timetable)
            val items = TimetableCache.itemsFor(context, TimetableCache.today())
            val lines = TimetableCache.widgetLines(items, nowMinute())
            views.setTextViewText(R.id.widgetLabel, lines[0])
            views.setTextViewText(R.id.widgetBlock, lines[1])
            views.setTextViewText(R.id.widgetTime, lines[2])
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            return views
        }

        /** Sets an alarm for the next block start or end, so the widget changes on time. Inexact is fine here. */
        private fun scheduleNextChange(context: Context) {
            val items = TimetableCache.itemsFor(context, TimetableCache.today())
            val minute = TimetableCache.nextChangeMinute(items, nowMinute()) ?: return
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val at = midnight.timeInMillis + minute * 60_000L + 1_000L
            val intent = Intent(context, TimetableWidget::class.java).setAction(ACTION_REFRESH)
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CHANGE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.set(AlarmManager.RTC, at, pending)
        }
    }
}
