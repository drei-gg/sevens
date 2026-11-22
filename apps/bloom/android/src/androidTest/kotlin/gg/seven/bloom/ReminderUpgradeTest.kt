package gg.seven.bloom

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** What Bloom saved and armed before SevenKit's scheduler (2026-11-22) is read and kept. */
@RunWith(AndroidJUnit4::class)
class ReminderUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun legacyWake(flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, BloomReminderReceiver::class.java).setAction("gg.seven.bloom.DUE"),
            flag or PendingIntent.FLAG_IMMUTABLE,
        )

    @Test
    fun theOldLedgerAndWakeCarryOver() = runBlocking {
        val now = java.time.Instant.now().epochSecond
        context
            .getSharedPreferences("bloom-reminders", Context.MODE_PRIVATE)
            .edit()
            .putLong("next", now + 3_600)
            .putString("delivered", """{"bloom.dose.x":${now - 60}}""")
            .putString("failure", "schedule")
            .commit()
        val operation = legacyWake(PendingIntent.FLAG_UPDATE_CURRENT)!!
        context
            .getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, (now + 3_600) * 1000, operation)
        val scheduler = (context.applicationContext as BloomApplication).reminderScheduler
        val ledger = scheduler.ledger()
        assertEquals(now + 3_600, ledger.next)
        assertEquals(now - 60, ledger.delivered["bloom.dose.x"])
        assertEquals("schedule", ledger.failure)
        assertTrue(
            "The shipped wake is the scheduler's wake",
            gg.seven.kit.schedule.SystemWakes(context).isArmed(scheduler.config.wake),
        )
        // A reconcile replaces it in place and clears the failure.
        BloomReminders.reconcile(context)
        assertNull(scheduler.ledger().failure)
        assertEquals(now - 60, scheduler.ledger().delivered["bloom.dose.x"])
    }
}
