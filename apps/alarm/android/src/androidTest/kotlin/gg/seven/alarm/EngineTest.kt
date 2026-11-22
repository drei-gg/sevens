package gg.seven.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.alarm.core.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The model's order of operations, as iOS's AlarmModel tests exercise it, on the real core. */
@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun engine(
        seed: LoopArchive = newLoopArchive()
    ): Pair<AlarmEngine, PreviewAlarmDriver> {
        val driver = PreviewAlarmDriver()
        val engine = AlarmEngine(ArchiveRepository(null, seed), driver, isPreview = true)
        runBlocking { engine.reload() }
        return engine to driver
    }

    private fun alarm(
        name: String = "Wake up",
        minute: Int = 420,
        days: List<Int> = listOf(2, 3, 4, 5, 6),
    ) =
        newRoutine(RoutineKind.ALARM, Instant.now())
            .copy(name = name, slots = listOf(newTimeSlot(minute, days)))

    @Test
    fun unprojectableSchedulesClearThePreviouslyCheckedWidget() = runBlocking {
        var snapshot: AlarmWidgetSnapshot? = null
        val engine =
            AlarmEngine(
                ArchiveRepository(null),
                PreviewAlarmDriver(),
                true,
                widgetSink = { snapshot = it },
            )
        engine.reload()
        assertNotNull(snapshot)
        val loop =
            newRoutine(RoutineKind.LOOP, Instant.now())
                .copy(slots = (0 until 10).map { newTimeSlot(420 + it * 5, (1..7).toList()) })
        assertTrue(engine.mutate { it.copy(routines = listOf(loop)) })
        assertNull(snapshot)
        assertNotNull(engine.widgetError.value)
    }

    @Test
    fun savingInstallsReceiptsAndDisablingRemovesThem() = runBlocking {
        val (engine, driver) = engine()
        val r = alarm()
        assertTrue(engine.save(r, paid = true))
        assertEquals(1, driver.rows.size)
        assertEquals(driver.rows.keys, engine.archive.value.installed.map { it.id }.toSet())
        engine.toggle(r, false, paid = false)
        assertTrue(driver.rows.isEmpty())
        assertTrue(engine.archive.value.installed.isEmpty())
    }

    @Test
    fun expiredTrialKeepsAlarmsButRefusesEditsAndReenabling() = runBlocking {
        val (engine, driver) = engine()
        val r = alarm()
        engine.save(r, paid = true)
        assertFalse(engine.save(r.copy(name = "Changed"), paid = false))
        assertTrue(engine.error.value!!.startsWith("Start the trial or unlock"))
        assertEquals(1, driver.rows.size)
        engine.toggle(r, false, paid = false)
        engine.toggle(r, true, paid = false)
        assertFalse(engine.archive.value.routines.single().enabled)
        assertEquals(
            "Unlock to re-enable this routine. Existing enabled alarms keep running.",
            engine.error.value,
        )
    }

    @Test
    fun quietRemindersSplitPerWeekday() = runBlocking {
        val (engine, driver) = engine()
        val loop =
            newRoutine(RoutineKind.LOOP, Instant.now())
                .copy(slots = listOf(newTimeSlot(720, listOf(2, 4, 6))))
        engine.save(loop, paid = true)
        assertEquals(3, driver.rows.size)
        assertTrue(
            driver.rows.values.all {
                it.weekdays.size == 1 && it.routine.delivery == Delivery.REMINDER
            }
        )
    }

    @Test
    fun snoozeBelongsToItsRingingAndStopsAtTheLimit() = runBlocking {
        val (engine, driver) = engine()
        val r = alarm().copy(snoozeLimit = 1, snoozeMinutes = 5)
        engine.save(r, paid = true)
        val row = engine.archive.value.installed.single()
        engine.openNative(row.id)
        val pending = engine.archive.value.pending!!
        assertNotNull(pending.occurrenceId)
        assertTrue(engine.finish(pending, EventKind.SNOOZED))
        val snooze = engine.archive.value.installed.single { it.purpose == "snooze" }
        assertEquals(1, snooze.snoozes)
        assertEquals(pending.occurrenceId, snooze.occurrenceId)
        assertTrue(snooze.id in driver.rows)
        assertNull(engine.archive.value.pending)
        assertEquals(EventKind.SNOOZED, engine.archive.value.events.single().kind)
        // The snooze rings and is opened; the limit is reached.
        engine.openNative(snooze.id)
        assertFalse(engine.finish(engine.archive.value.pending!!, EventKind.SNOOZED))
        assertEquals("Snooze limit reached.", engine.error.value)
    }

    @Test
    fun systemStopRecordsADismissalAndSchedulesOneWakeUpCheck() = runBlocking {
        val (engine, driver) = engine()
        val r = alarm().copy(followUpMinutes = 5)
        engine.save(r, paid = true)
        val row = engine.archive.value.installed.single()
        engine.dismissNative(row.id)
        val event = engine.archive.value.events.single()
        assertEquals(EventKind.DISMISSED, event.kind)
        val check = engine.archive.value.installed.single { it.purpose == "followUp" }
        assertTrue(check.id in driver.rows)
        // Completing the check doesn't schedule another; it clears the routine's extras.
        assertTrue(engine.finish(check, EventKind.COMPLETED))
        assertEquals(
            0,
            engine.archive.value.installed.count { it.purpose == "followUp" && it.id != check.id },
        )
        engine.clearWakeUpChecks()
        assertTrue(engine.archive.value.installed.none { it.purpose == "followUp" })
    }

    @Test
    fun emergencyStopCancelsTheRoutinesExtras() = runBlocking {
        val (engine, driver) = engine()
        val r = alarm().copy(followUpMinutes = 10)
        engine.save(r, paid = true)
        val row = engine.archive.value.installed.single()
        engine.openNative(row.id)
        engine.finish(engine.archive.value.pending!!, EventKind.SNOOZED)
        val snooze = engine.archive.value.installed.single { it.purpose == "snooze" }
        engine.openNative(snooze.id)
        val other = engine.archive.value.installed.first { it.purpose == "schedule" }
        // A second extra of the same routine, then an emergency stop from the snooze.
        engine.extra(other, 30, "followUp")
        assertTrue(engine.finish(engine.archive.value.pending!!, EventKind.EMERGENCY_STOP))
        assertTrue(engine.archive.value.installed.none { it.purpose == "followUp" })
        assertTrue(driver.rows.values.none { it.purpose == "followUp" })
    }

    @Test
    fun napsAndDuplicatesUseTheCore() = runBlocking {
        val (engine, _) = engine()
        engine.saveDefaults(AlarmDefaults(AlarmTone.CHIME, 9, 2, 0))
        assertTrue(engine.nap(20, paid = true))
        val nap = engine.archive.value.routines.single()
        assertEquals("20-minute nap", nap.name)
        assertEquals(AlarmTone.CHIME, nap.tone)
        assertFalse(engine.nap(0, paid = true))
        assertTrue(engine.duplicate(nap, paid = true))
        val copy = engine.archive.value.routines.single { it.id != nap.id }
        assertEquals("20-minute nap copy", copy.name)
        assertFalse(copy.enabled)
    }

    @Test
    fun pastOneOffTurnsOffOnSync() = runBlocking {
        val past =
            newRoutine(RoutineKind.ALARM, Instant.now())
                .copy(timing = Timing.ONCE, once = Instant.now().minusSeconds(60))
        val seed = newLoopArchive().copy(routines = listOf(past))
        val (engine, _) = engine(seed)
        engine.synchronize()
        assertFalse(engine.archive.value.routines.single().enabled)
    }

    @Test
    fun backupRoundTripsAndImportsDormant() = runBlocking {
        val (engine, _) = engine()
        val r = alarm(name = "Gym")
        engine.save(r, paid = true)
        engine.manual(r, Instant.now())
        val bytes = backupEncode(engine.archive.value)
        val decoded = decodeArchive(bytes)
        assertEquals(engine.archive.value.routines, decoded.routines)
        // Into an empty phone: the routine arrives off, receipts and pending stay behind.
        val (other, driver) = engine()
        other.mutate { backupMerge(decoded, it) }
        assertFalse(other.archive.value.routines.single().enabled)
        assertTrue(other.archive.value.installed.isEmpty())
        assertEquals(1, other.archive.value.events.size)
        other.synchronize()
        assertTrue(driver.rows.isEmpty())
        assertTrue(backupCsv(other.archive.value).contains("Gym"))
    }

    @Test
    fun corruptFileIsPreservedAndBlocksWrites() = runBlocking {
        val file = File(context.cacheDir, "corrupt-${UUID.randomUUID()}.json")
        file.writeText("{broken")
        try {
            val engine =
                AlarmEngine(ArchiveRepository(file), PreviewAlarmDriver(), isPreview = true)
            engine.reload()
            assertFalse(engine.ready.value)
            assertNotNull(engine.loadError.value)
            assertFalse(engine.mutate { it.copy(events = emptyList()) })
            assertEquals("{broken", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun archivePersistsAtomicallyAcrossRepositories() = runBlocking {
        val file = File(context.cacheDir, "archive-${UUID.randomUUID()}.json")
        try {
            val first = AlarmEngine(ArchiveRepository(file), PreviewAlarmDriver(), isPreview = true)
            first.reload()
            first.save(alarm(name = "Persisted"), paid = true)
            val fresh = AlarmEngine(ArchiveRepository(file), PreviewAlarmDriver(), isPreview = true)
            fresh.reload()
            assertEquals("Persisted", fresh.archive.value.routines.single().name)
            // A routine the core refuses is never written.
            assertFalse(
                fresh.mutate { a -> a.copy(routines = a.routines.map { it.copy(name = "") }) }
            )
            assertEquals("Persisted", decodeArchive(file.readBytes()).routines.single().name)
        } finally {
            file.delete()
        }
    }

    @Test
    fun nativeDriverArmsAndCancelsAnAlarmClock() {
        val driver = NativeAlarmDriver(context)
        assertTrue("USE_EXACT_ALARM is granted to alarm clocks", driver.exactAllowed)
        val r = alarm().copy(timing = Timing.ONCE, once = Instant.now().plusSeconds(3600))
        val row = newRegistration(UUID.randomUUID(), r).copy(date = r.once)
        driver.schedule(row)
        try {
            assertTrue(driver.isLive(row))
            val next = context.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock
            assertNotNull(next)
        } finally {
            driver.cancel(row)
        }
        assertFalse(driver.isLive(row))
    }

    @Test
    fun clockPartsFollowTheTwelveHourSetting() {
        ClockFormat.h24 = true
        assertEquals(
            "07:05" to null,
            ClockFormat.parts(425).let {
                it.first.filter { c -> c.isDigit() || c == ':' } to it.second
            },
        )
        ClockFormat.h24 = false
        val (time, period) = ClockFormat.parts(13 * 60 + 5)
        assertTrue(time.contains("1:05"))
        assertNotNull(period)
        assertEquals(
            "in less than a minute",
            ClockFormat.countdown(Instant.now().plusSeconds(30), Instant.now()),
        )
    }
}
