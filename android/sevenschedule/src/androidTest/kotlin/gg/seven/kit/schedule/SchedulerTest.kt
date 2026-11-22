package gg.seven.kit.schedule

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The test app's wake: delivers through the scheduler under test, as an app's receiver does. */
class TestWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = current ?: return
        finishAsync { runCatching { scheduler.reconcile(deliver = true) } }
    }

    companion object {
        @Volatile var current: ReminderScheduler? = null
    }
}

/** A schedule the test edits between reconciles. */
class FakePlan(var reminders: List<DueReminder> = emptyList(), var failure: Exception? = null) : ReminderPlan {
    override fun due(after: Long, now: Long) = reminders.filter { it.at in (after + 1)..now }

    override fun next(now: Long) = reminders.map { it.at }.filter { it > now }.minOrNull()

    override fun build(reminder: DueReminder, builder: NotificationCompat.Builder) {
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(reminder.tag)
    }
}

@RunWith(AndroidJUnit4::class)
class SchedulerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val wakes = SystemWakes(context)
    private val plan = FakePlan()
    private val wake = WakeId(TestWakeReceiver::class.java, "gg.seven.kit.schedule.TEST")
    private val config =
        ReminderConfig(
            wake = wake,
            preferences = "sevenschedule-test",
            channel = ReminderChannel("sevenschedule-test", "Test reminders"),
            tagPrefix = "test.",
        )
    private val scheduler =
        ReminderScheduler(context, config) { plan.failure?.let { throw it } ?: plan }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
    }

    private fun allowExactAlarms() = shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")

    private fun posted(tag: String) = notifications.activeNotifications.count { it.tag == tag }

    @Before
    fun setUp() = runBlocking {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        allowExactAlarms()
        TestWakeReceiver.current = scheduler
        scheduler.clear()
    }

    @After
    fun tearDown() = runBlocking {
        TestWakeReceiver.current = null
        scheduler.clear()
        allowExactAlarms()
    }

    @Test
    fun wakesArmCheckAndCancel() {
        val other = WakeId(TestWakeReceiver::class.java, "gg.seven.kit.schedule.TEST", Uri.parse("test://wake/2"))
        assertFalse(wakes.isArmed(wake))
        assertEquals(Armed.EXACT, wakes.arm(wake, System.currentTimeMillis() + 3_600_000, Precision.ExactIfAllowed))
        assertEquals(Armed.INEXACT, wakes.arm(other, System.currentTimeMillis() + 3_600_000, Precision.Inexact))
        // Data tells wakes apart, as Alarm's one-wake-per-registration needs.
        assertTrue(wakes.isArmed(wake) && wakes.isArmed(other))
        wakes.cancel(wake)
        assertFalse(wakes.isArmed(wake))
        assertTrue(wakes.isArmed(other))
        wakes.cancel(other)
    }

    // Not tested here: revoking exact-alarm access kills the app's process (and with it this
    // instrumentation), so the inexact fallback and the orphan cleanup after a refused alarm clock
    // can't run in-process. Both are two-line branches in SystemWakes.arm.

    @Test
    fun aWakePostsEachReminderOnceAndArmsTheNext() = runBlocking {
        val now = Instant.now().epochSecond
        plan.reminders = listOf(DueReminder("test.a", now - 5), DueReminder("test.b", now + 3_600))
        scheduler.seed(now - 5)
        scheduler.reconcile(deliver = true, now = now)
        assertEquals(1, posted("test.a"))
        assertEquals(now - 5, scheduler.ledger().delivered["test.a"])
        assertEquals(now + 3_600, scheduler.ledger().next)
        assertTrue(wakes.isArmed(wake))
        // A replayed wake for the same moment posts nothing new.
        notifications.cancel("test.a", config.notificationId)
        scheduler.seed(now - 5)
        scheduler.reconcile(deliver = true, now = now)
        assertEquals(0, posted("test.a"))
    }

    @Test
    fun alertsThatNoLongerMatchAreRemoved() = runBlocking {
        val now = Instant.now().epochSecond
        plan.reminders = listOf(DueReminder("test.a", now - 5), DueReminder("test.b", now - 4))
        scheduler.seed(now - 6)
        scheduler.reconcile(deliver = true, now = now)
        assertEquals(1, posted("test.a"))
        assertEquals(1, posted("test.b"))
        // a was deleted and b moved: both alerts go; without `deliver`, nothing is posted.
        plan.reminders = listOf(DueReminder("test.b", now - 2))
        scheduler.reconcile(now = now)
        assertEquals(0, posted("test.a"))
        assertEquals(0, posted("test.b"))
        assertNull(scheduler.ledger().failure)
    }

    @Test
    fun aFailedReadIsSavedAndRetried() = runBlocking {
        val now = Instant.now().epochSecond
        scheduler.seed(now - 5)
        plan.failure = IllegalStateException("unreadable")
        try {
            scheduler.reconcile(deliver = true, now = now)
            fail("The failure must reach the caller")
        } catch (_: IllegalStateException) {}
        assertEquals("schedule", scheduler.ledger().failure)
        assertEquals("The retry replays from the old wake", now - 5, scheduler.ledger().next)
        assertTrue("A retry wake is armed", wakes.isArmed(wake))
        assertEquals(ReminderState.FAILED, scheduler.state())
        plan.failure = null
        scheduler.reconcile(deliver = true, now = now)
        assertNull(scheduler.ledger().failure)
    }

    @Test
    fun theSystemWakeDelivers() = runBlocking {
        val now = Instant.now().epochSecond
        plan.reminders = listOf(DueReminder("test.real", now + 4))
        scheduler.reconcile(now = now)
        assertEquals(now + 4, scheduler.ledger().next)
        var waited = 0
        while (posted("test.real") == 0 && waited < 20_000) {
            delay(500)
            waited += 500
        }
        assertEquals("The AlarmManager wake must post through the receiver", 1, posted("test.real"))
    }

    @Test
    fun restoreReasonsAreRecognised() {
        assertEquals(RestoreReason.LOCKED_BOOT, restoreReason(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)))
        assertEquals(RestoreReason.BOOT, restoreReason(Intent(Intent.ACTION_BOOT_COMPLETED)))
        assertEquals(RestoreReason.PACKAGE_REPLACED, restoreReason(Intent(Intent.ACTION_MY_PACKAGE_REPLACED)))
        assertTrue(restoreReason(Intent(Intent.ACTION_TIME_CHANGED))!!.clockMoved)
        assertTrue(restoreReason(Intent(Intent.ACTION_TIMEZONE_CHANGED))!!.clockMoved)
        assertFalse(restoreReason(Intent(Intent.ACTION_BOOT_COMPLETED))!!.clockMoved)
        assertEquals(
            RestoreReason.EXACT_ACCESS_CHANGED,
            restoreReason(Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")),
        )
        assertNull(restoreReason(Intent("something.else")))
        assertNull(restoreReason(null))
    }
}
