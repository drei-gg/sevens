package gg.seven.fast

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import gg.seven.fast.core.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fast's reminders through SevenKit's scheduler: upgrade, delivery once, stale removal. */
@RunWith(AndroidJUnit4::class)
class ReminderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as FastApplication
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var before: FastArchive? = null

    @Before
    fun setUp() = runBlocking {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            .close()
        // The recovery worker reconciles on its own schedule (at once, the first time): keep it
        // out of these tests' way.
        val work = androidx.work.WorkManager.getInstance(context)
        work.cancelUniqueWork("fast-reminder-recovery").result.get()
        repeat(50) {
            val running =
                work.getWorkInfosForUniqueWork("fast-reminder-recovery").get().any {
                    it.state == androidx.work.WorkInfo.State.RUNNING
                }
            if (!running) return@repeat
            Thread.sleep(100)
        }
        app.repository.load()
        before = app.repository.state.value
    }

    @After
    fun tearDown() = runBlocking {
        before?.let { saved -> app.repository.update { saved } }
        FastReminders.reconcile(app)
        app.reminderScheduler.maintain()
    }

    @Test
    fun theOldWatermarkCarriesOverOnce() {
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        prefs.edit().putLong("next", 1_800_000_000_123L).commit()
        context.getSharedPreferences("fast-reminders", Context.MODE_PRIVATE).edit().clear().commit()
        val scheduler = FastReminders.create(context)
        assertEquals(1_800_000_000L, scheduler.ledger().next)
        assertFalse(prefs.contains("next"))
    }

    @Test
    fun aGoalReminderPostsOnceAndGoesWhenTheFastEnds() = runBlocking {
        val now = Instant.now().epochSecond
        // Started 61 minutes ago with a 60-minute goal: due a minute ago, while the wake was armed.
        val fast = fastRecord(start = (now - 3_660) * 1000.0, goalMinutes = 60)
        app.repository.update {
            it.copy(settings = it.settings.copy(goalReminder = true), fasts = listOf(fast))
        }
        val scheduler = FastReminders.scheduler(app)
        scheduler.seed(now - 61)
        FastReminders.reconcile(app, deliver = true)
        val tag = "fast.goal.${fast.id.toString().uppercase()}"
        assertEquals(1, notifications.activeNotifications.count { it.tag == tag })
        // A replayed wake posts nothing new.
        notifications.cancel(tag, 1)
        scheduler.seed(now - 61)
        FastReminders.reconcile(app, deliver = true)
        assertEquals(0, notifications.activeNotifications.count { it.tag == tag })
        // Posted again for the stale check, then the fast ends: the alert goes.
        scheduler.seed(now - 61)
        context.getSharedPreferences("fast-reminders", Context.MODE_PRIVATE).edit().remove("delivered").commit()
        FastReminders.reconcile(app, deliver = true)
        assertEquals(1, notifications.activeNotifications.count { it.tag == tag })
        app.repository.update { a -> a.copy(fasts = listOf(fast.copy(end = Instant.ofEpochSecond(now - 1)))) }
        FastReminders.reconcile(app)
        assertEquals(0, notifications.activeNotifications.count { it.tag == tag })
    }
}
