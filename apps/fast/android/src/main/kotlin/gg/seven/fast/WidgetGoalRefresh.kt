package gg.seven.fast

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import gg.seven.fast.core.*
import kotlinx.coroutines.*

/** Refresh the goal label even with all reminder notifications off. Never changes a fast. */
internal object WidgetGoalRefresh {
    fun schedule(context: Context, archive: FastArchive) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WidgetGoalReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarm.cancel(pending)
        val installed =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, FastWidget::class.java))
                .isNotEmpty()
        val goal = archive.active?.goalDate?.toLong()
        if (installed && goal != null && goal > System.currentTimeMillis()) {
            // Display maintenance never requests exact-alarm access. Android may defer it in Doze.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, goal, pending)
        }
    }
}

class WidgetGoalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FastGlanceWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
