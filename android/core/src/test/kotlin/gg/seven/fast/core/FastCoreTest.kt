package gg.seven.fast.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class FastCoreTest {
    private val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli().toDouble()

    private fun ms(s: String) = Instant.parse(s).toEpochMilli().toDouble()

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun lifecycleAllowsTouchingEndpointsAndEarlyEnds() {
        val first = FastRecord(start = now - 120_000, end = now - 60_000)
        val second = FastRecord(start = now - 60_000)
        val a = FastArchive().save(first, now).save(second, now)
        assertEquals(second, a.active)
        assertEquals(false, first.metGoal)
        val ended = a.save(second.copy(end = now), now)
        assertNull(ended.active)
        assertEquals(60_000.0, ended.latestCompleted!!.duration(now), 0.0)
    }

    @Test
    fun rejectsOverlapAndMultipleActiveWithoutMutatingOriginal() {
        val a = FastArchive().save(FastRecord(start = now - 1000), now)
        rejects { a.save(FastRecord(start = now - 500), now) }
        rejects { a.save(FastRecord(start = now - 2000, end = now), now) }
        assertEquals(1, a.fasts.size)
    }

    @Test
    fun rejectsFutureInvalidAndZeroLengthActuals() {
        rejects { FastArchive(fasts = listOf(FastRecord(start = now + 1))).validate(now) }
        rejects { FastArchive(fasts = listOf(FastRecord(start = now, end = now))).validate(now) }
        rejects {
            FastArchive(fasts = listOf(FastRecord(start = now - 1, goalMinutes = 0))).validate(now)
        }
        rejects {
            FastArchive(
                    metrics =
                        listOf(MetricEntry(date = now, kind = MetricKind.water, value = Double.NaN))
                )
                .validate(now)
        }
    }

    @Test
    fun goalDoesNotStopActiveTimer() {
        val f = FastRecord(start = now - 2 * 3_600_000, goalMinutes = 60)
        assertNull(f.end)
        assertEquals(7_200_000.0, f.duration(now), 0.0)
    }

    @Test
    fun localMidnightSplitsWithoutDuplicatingSessions() {
        val zone = ZoneId.of("Asia/Manila")
        val f = FastRecord(start = ms("2026-11-12T14:00:00Z"), end = ms("2026-11-12T18:00:00Z"))
        assertEquals(2.0, History.hours(listOf(f), LocalDate.parse("2026-11-12"), zone, now), 0.0)
        assertEquals(2.0, History.hours(listOf(f), LocalDate.parse("2026-11-13"), zone, now), 0.0)
    }

    @Test
    fun springAndAutumnDaysUseElapsedTime() {
        val z = ZoneId.of("America/New_York")
        listOf("2026-03-08" to 23.0, "2026-11-01" to 25.0).forEach { (date, hours) ->
            val day = LocalDate.parse(date)
            val f =
                FastRecord(
                    start = day.atStartOfDay(z).toInstant().toEpochMilli().toDouble(),
                    end = day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli().toDouble(),
                )
            assertEquals(hours, History.hours(listOf(f), day, z, now), 0.0)
        }
    }

    @Test
    fun waterRegroupsWhenTimezoneChanges() {
        val e =
            MetricEntry(date = ms("2026-11-12T23:30:00Z"), kind = MetricKind.water, value = 250.0)
        assertEquals(
            250.0,
            History.water(listOf(e), LocalDate.parse("2026-11-13"), ZoneId.of("Asia/Manila")),
            0.0,
        )
        assertEquals(
            250.0,
            History.water(listOf(e), LocalDate.parse("2026-11-12"), ZoneOffset.UTC),
            0.0,
        )
    }

    @Test
    fun missingWaterDaysAreNotZeroConsumption() {
        val e = MetricEntry(date = now, kind = MetricKind.water, value = 250.0)
        val days = History.waterDays(listOf(e), LocalDate.parse("2026-11-13"), 7, ZoneOffset.UTC)
        assertEquals(7, days.size)
        assertEquals(1, days.count { it.entryCount > 0 })
    }

    @Test
    fun completeBackupRoundTripAndMergeAreIdempotent() {
        val f =
            FastRecord(
                start = now - 10_000,
                end = now - 1000,
                intention = "quiet",
                reflection = "done",
                tags = listOf("home"),
            )
        val a =
            FastArchive(
                fasts = listOf(f),
                journal = listOf(JournalEntry(date = now, fastID = f.id, text = "hello", mood = 3)),
                metrics = listOf(MetricEntry(date = now, kind = MetricKind.water, value = 251.5)),
                plans = listOf(PlannedFast(start = now + 1000)),
                restDays = listOf(RestDay(date = now)),
                presets = listOf(FastPreset(name = "Mine", goalMinutes = null, eatingMinutes = 500)),
            )
        val b = ArchiveCodec.decode(ArchiveCodec.encode(a), now)
        assertEquals(a, b)
        assertEquals(a, ArchiveCodec.merge(b, a, now))
    }

    @Test
    fun conflictsAndOverlapRejectEntireImport() {
        val f = FastRecord(start = now - 10_000, end = now - 1000)
        val a = FastArchive(fasts = listOf(f))
        val incoming =
            FastArchive(
                fasts = listOf(f.copy(reflection = "different")),
                metrics = listOf(MetricEntry(date = now, kind = MetricKind.water, value = 250.0)),
            )
        rejects { ArchiveCodec.merge(incoming, a, now) }
        rejects {
            ArchiveCodec.merge(
                FastArchive(fasts = listOf(FastRecord(start = now - 2000, end = now))),
                a,
                now,
            )
        }
        assertTrue(a.metrics.isEmpty())
    }

    @Test
    fun importPreservesLocalReminderConsent() {
        val local =
            FastArchive(settings = TrackerSettings(water = WaterSettings(remindersPaused = true)))
        val incoming =
            FastArchive(
                settings =
                    TrackerSettings(
                        goalReminder = true,
                        water = WaterSettings(remindersEnabled = true),
                    )
            )
        assertEquals(local.settings, ArchiveCodec.merge(incoming, local, now).settings)
    }

    @Test
    fun subMicrosecondNoiseOnlyIsTolerated() {
        val f = FastRecord(start = now - 2000.125, end = now - 1000.125)
        val a = FastArchive(fasts = listOf(f))
        assertEquals(
            a,
            ArchiveCodec.merge(a.copy(fasts = listOf(f.copy(start = f.start + 0.0005))), a, now),
        )
        rejects { ArchiveCodec.merge(a.copy(fasts = listOf(f.copy(start = f.start + 1))), a, now) }
    }

    @Test
    fun incompleteAndFutureBackupsAreRejected() {
        rejects { ArchiveCodec.decode("{\"schemaVersion\":2}", now) }
        rejects { ArchiveCodec.decode("{\"schemaVersion\":1}", now) }
    }

    @Test
    fun olderSettingsGetWaterDefaults() {
        val encoded = ArchiveCodec.encode(FastArchive())
        val tree =
            kotlinx.serialization.json.Json.parseToJsonElement(encoded)
                as kotlinx.serialization.json.JsonObject
        val replacement =
            kotlinx.serialization.json.JsonObject(
                tree.toMutableMap().apply {
                    put("settings", kotlinx.serialization.json.JsonObject(emptyMap()))
                }
            )
        assertEquals(
            WaterSettings(),
            ArchiveCodec.decode(replacement.toString(), now).settings.water,
        )
    }

    @Test
    fun deletingFastUnlinksButKeepsJournal() {
        val f = FastRecord(start = now - 1000)
        val a =
            FastArchive(
                fasts = listOf(f),
                journal = listOf(JournalEntry(date = now, fastID = f.id)),
            )
        val deleted = a.deleteFast(f.id)
        assertTrue(deleted.fasts.isEmpty())
        assertEquals(1, deleted.journal.size)
        assertNull(deleted.journal[0].fastID)
    }

    @Test
    fun csvIncludesAllTypesEscapesQuotesAndNeutralizesFormulas() {
        val a =
            FastArchive(
                journal = listOf(JournalEntry(date = now, text = " =SUM(A1)\n\"x\"")),
                metrics = listOf(MetricEntry(date = now, kind = MetricKind.sleep, value = 8.0)),
                plans = listOf(PlannedFast(start = now)),
                restDays = listOf(RestDay(date = now)),
                presets =
                    listOf(FastPreset(name = "My timer", goalMinutes = 10, eatingMinutes = 10)),
            )
        val csv = ArchiveCodec.csv(a)
        assertTrue(csv.contains("\"' =SUM(A1)\n\"\"x\"\"\""))
        listOf("journal", "metric", "plan", "rest", "preset").forEach {
            assertTrue(csv.contains("\"$it\""))
        }
    }

    @Test
    fun waterPauseAndExclusiveHoursDoNotStopFastingReminders() {
        val a =
            FastArchive(
                fasts = listOf(FastRecord(start = now - 1000)),
                settings =
                    TrackerSettings(
                        goalReminder = true,
                        water = WaterSettings(remindersEnabled = true, remindersPaused = true),
                    ),
            )
        assertEquals(listOf("goal"), ReminderPlan.events(a, now, ZoneOffset.UTC).map { it.kind })
        val water =
            WaterSettings(remindersEnabled = true, startHour = 9, endHour = 21, intervalHours = 2)
        assertEquals(listOf(9, 11, 13, 15, 17, 19), water.reminderHours)
    }

    @Test
    fun reminderBudgetReservesRecurringSlots() {
        val a =
            FastArchive(
                plans = (1..100).map { PlannedFast(start = now + it * 1000) },
                settings =
                    TrackerSettings(
                        scheduleReminders = true,
                        reminderWeekdays = (1..7).toList(),
                        water =
                            WaterSettings(
                                remindersEnabled = true,
                                startHour = 0,
                                endHour = 24,
                                intervalHours = 1,
                            ),
                    ),
            )
        val events = ReminderPlan.events(a, now, ZoneOffset.UTC)
        assertEquals(64, events.size)
        assertEquals(33, events.count { it.kind == "planned" })
    }

    @Test
    fun recurringWaterResolvesDstInLocalTime() {
        val now = ms("2026-03-08T06:00:00Z")
        val a =
            FastArchive(
                settings =
                    TrackerSettings(
                        water = WaterSettings(remindersEnabled = true, startHour = 9, endHour = 10)
                    )
            )
        assertEquals(
            ms("2026-03-08T13:00:00Z"),
            ReminderPlan.events(a, now, ZoneId.of("America/New_York")).single().at,
            0.0,
        )
    }

    @Test
    fun unitsRoundTripWithoutChangingStoredValues() {
        val s = TrackerSettings(usePounds = true, useFluidOunces = true)
        for (k in listOf(MetricKind.water, MetricKind.weight)) assertEquals(
            250.0,
            Units.canonical(Units.display(250.0, k, s), k, s),
            1e-9,
        )
    }

    @Test
    fun averageDurationMatchesIosRatherThanMedian() {
        val lengths = listOf(1, 2, 9)
        var start = now - 3 * 86_400_000
        val records =
            lengths.map { h ->
                FastRecord(start = start, end = start + h * 3_600_000).also { start += 86_400_000 }
            }
        val summary =
            History.summary(
                FastArchive(fasts = records),
                now.instant().atZone(ZoneOffset.UTC).toLocalDate(),
                7,
                ZoneOffset.UTC,
                now,
            )
        assertEquals(4.0, summary.averageHours!!, 0.0)
        assertEquals(3, summary.sessions)
    }
}
