package gg.seven.fast

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import gg.seven.fast.core.*
import gg.seven.kit.schedule.Precision
import gg.seven.kit.schedule.SystemWakes
import gg.seven.kit.schedule.WakeId
import gg.seven.kit.schedule.finishAsync

/** Refresh the goal label even with all reminder notifications off. Never changes a fast. */
internal object WidgetGoalRefresh {
    // The wake Fast first shipped: WidgetGoalReceiver, no action, request code 0.
    private val wake = WakeId(WidgetGoalReceiver::class.java)

    fun schedule(context: Context, archive: FastArchive) {
        val wakes = SystemWakes(context)
        wakes.cancel(wake)
        val installed =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, FastWidget::class.java))
                .isNotEmpty()
        val goal = archive.active?.goalDate?.toLong()
        if (installed && goal != null && goal > System.currentTimeMillis()) {
            // Display maintenance never requests exact-alarm access. Android may defer it in Doze.
            wakes.arm(wake, goal, Precision.Inexact)
        }
    }
}

class WidgetGoalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        finishAsync { FastGlanceWidget.refresh(context) }
    }
}
