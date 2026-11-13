package gg.seven.fast.core

import java.time.*
import kotlin.math.max
import kotlin.math.min

object History {
    fun hours(fasts: List<FastRecord>, day: LocalDate, zone: ZoneId, now: Double): Double {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        return fasts.sumOf { max(0.0, min(it.end ?: now, min(now, end)) - max(it.start, start)) } /
            3_600_000
    }

    fun onDay(date: Double, day: LocalDate, zone: ZoneId) =
        date.instant().atZone(zone).toLocalDate() == day

    fun water(metrics: List<MetricEntry>, day: LocalDate, zone: ZoneId) =
        metrics
            .filter { it.kind == MetricKind.water && onDay(it.date, day, zone) }
            .sumOf { it.value }

    fun waterDays(
        metrics: List<MetricEntry>,
        end: LocalDate,
        count: Int,
        zone: ZoneId,
    ): List<WaterDay> {
        require(count in 1..366)
        val grouped =
            metrics
                .filter { it.kind == MetricKind.water }
                .groupBy { it.date.instant().atZone(zone).toLocalDate() }
        return (count - 1 downTo 0).map { offset ->
            val day = end.minusDays(offset.toLong())
            val items = grouped[day].orEmpty()
            WaterDay(day, items.sumOf { it.value }, items.size)
        }
    }

    fun summary(a: FastArchive, end: LocalDate, count: Int, zone: ZoneId, now: Double): Summary {
        val from =
            end.minusDays(count.toLong() - 1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
                .toDouble()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        val completed = a.fasts.filter { it.end != null && it.end >= from && it.end < until }
        val durations = completed.map { it.duration(now) / 3_600_000 }.sorted()
        val average = if (durations.isEmpty()) null else durations.average()
        return Summary(
            a.fasts.sumOf { max(0.0, min(it.end ?: now, min(now, until)) - max(it.start, from)) } /
                3_600_000,
            completed.size,
            average,
            completed.count { it.metGoal == true },
            completed.count { it.goalMinutes != null },
        )
    }
}

data class WaterDay(val date: LocalDate, val milliliters: Double, val entryCount: Int)

data class Summary(
    val hours: Double,
    val sessions: Int,
    val averageHours: Double?,
    val goalsMet: Int,
    val goalsRecorded: Int,
)

data class ReminderEvent(val id: String, val kind: String, val at: Double)

object ReminderPlan {
    // Resolve calendar recurrences each time: DST and timezone changes never become fixed 24h
    // intervals.
    fun events(a: FastArchive, now: Double, zone: ZoneId): List<ReminderEvent> {
        val s = a.settings
        val events = mutableListOf<ReminderEvent>()
        a.active?.let { f ->
            if (s.goalReminder)
                f.goalDate
                    ?.takeIf { it > now }
                    ?.let { events += ReminderEvent("goal.${f.id}", "goal", it) }
        }
        if (a.active == null && s.eatingReminder)
            a.latestCompleted?.let { f ->
                val finish = f.end!! + f.eatingMinutes * 60_000.0
                if (finish > now) events += ReminderEvent("eating.${f.id}", "eating", finish)
            }
        val today = now.instant().atZone(zone).toLocalDate()
        fun next(hour: Int, minute: Int, weekday: Int? = null): Double =
            (0..7)
                .map { today.plusDays(it.toLong()).atTime(hour, minute).atZone(zone) }
                .first {
                    it.toInstant().toEpochMilli() > now &&
                        (weekday == null || it.dayOfWeek.value % 7 + 1 == weekday)
                }
                .toInstant()
                .toEpochMilli()
                .toDouble()
        s.reminderWeekdays.sorted().forEach {
            events +=
                ReminderEvent("weekly.$it", "weekly", next(s.reminderHour, s.reminderMinute, it))
        }
        s.water.reminderHours.forEach { events += ReminderEvent("water.$it", "water", next(it, 0)) }
        if (s.scheduleReminders)
            a.plans
                .filter { it.start > now }
                .sortedBy { it.start }
                .take(min(40, 64 - events.size))
                .forEach { events += ReminderEvent("plan.${it.id}", "planned", it.start) }
        return events
    }
}
