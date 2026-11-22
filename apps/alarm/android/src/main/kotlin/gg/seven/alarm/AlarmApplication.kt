package gg.seven.alarm

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import gg.seven.alarm.core.Delivery
import gg.seven.kit.schedule.restoreReason
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AlarmApplication : Application() {
    /**
     * Where the archive lives: device-protected storage, so alarms can be restored and ring after a
     * reboot before the first unlock.
     */
    val archiveFile: File
        get() = File(createDeviceProtectedStorageContext().filesDir, "AlarmLoops/archive.v1.json")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val widgetRefresh = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var current: AlarmEngine? = null

    /** One engine per process; tests swap in an in-memory one before the activity starts. */
    var engine: AlarmEngine
        get() =
            current
                ?: synchronized(this) {
                    current
                        ?: AlarmEngine(
                                ArchiveRepository(archiveFile),
                                NativeAlarmDriver(this),
                                widgetSink = { snapshot ->
                                    AlarmWidgetStore.write(this, snapshot)
                                    widgetRefresh.value += 1
                                },
                            )
                            .also { current = it }
                }
        set(value) {
            current = value
        }

    override fun onCreate() {
        super.onCreate()
        Ringing.createChannels(this)
        scope.launch {
            widgetRefresh.collectLatest {
                kotlinx.coroutines.delay(300)
                AlarmGlanceWidget.refresh(this@AlarmApplication)
            }
        }
    }
}

val Context.engine: AlarmEngine
    get() = (applicationContext as AlarmApplication).engine

private fun BroadcastReceiver.work(context: Context, block: suspend (AlarmEngine) -> Unit) {
    val pending = goAsync()
    val app = context.applicationContext as AlarmApplication
    app.scope.launch {
        try {
            block(app.engine)
        } finally {
            pending.finish()
        }
    }
}

/**
 * A registration's time has come: ring it (alarms) or post it (quiet reminders), and arm its next
 * week. Runs from AlarmManager, so it may start the ringing foreground service from the background.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = NativeAlarmDriver.fireId(intent) ?: return
        work(context) { engine ->
            val row = engine.receipt(id)
            if (row == null) {
                // Not in the saved schedule any more (deleted while armed): nothing should ring.
                engine.driver.cancel(gg.seven.alarm.core.newRegistration(id, engine.newRoutine()))
                return@work
            }
            if (row.date == null) runCatching { engine.driver.schedule(row) } // next week's ring
            else engine.driver.cancel(row) // a one-off rings once
            if (row.routine.delivery == Delivery.ALARM)
                RingingService.ring(
                    context,
                    row.id,
                    row.routine.name,
                    row.routine.tone ?: gg.seven.alarm.core.AlarmTone.SYSTEM,
                    row.routine.importedSound,
                )
            else Reminders.post(context, row)
            engine.synchronize()
        }
    }

    companion object {
        const val ACTION_FIRE = "gg.seven.alarm.FIRE"
    }
}

/** Puts every alarm back after a reboot, an update, or a clock or time-zone change. */
class RestoreReceiver : BroadcastReceiver() {
    @android.annotation.SuppressLint("UnsafeProtectedBroadcastReceiver") // restoreReason checks it.
    override fun onReceive(context: Context, intent: Intent) {
        val reason = restoreReason(intent) ?: return
        work(context) { engine ->
            if (!engine.ready.value) engine.reload()
            // After a clock or zone change an armed alarm's instant is stale, even if it looks live.
            engine.synchronize(rearm = reason.clockMoved)
        }
    }
}

/** The Stop action on the ringing notification: the system-style Stop, recorded as a dismissal. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = NativeAlarmDriver.fireId(intent) ?: return
        RingingService.silence(context)
        work(context) { engine -> runCatching { engine.dismissNative(id) } }
    }
}

/** Quiet reminders: a plain notification with the routine's name (iOS "Time for your routine."). */
object Reminders {
    fun post(context: Context, row: gg.seven.alarm.core.Registration) {
        val open =
            android.app.PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(context, Ringing.REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(row.routine.name)
                .setContentText("Time for your routine.")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setWhen(Instant.now().toEpochMilli())
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        NotificationManagerCompat.from(context)
            .notify(row.id.toString(), NativeAlarmDriver.REMINDER_NOTIFICATION, notification)
    }
}
