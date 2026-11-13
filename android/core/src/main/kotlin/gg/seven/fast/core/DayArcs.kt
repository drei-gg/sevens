package gg.seven.fast.core

import java.time.LocalDate
import java.time.ZoneId

/** Swift DayArcs.swift: positions use wall time; totals remain actual elapsed time across DST. */
data class DayArc(
    val fastID: String,
    val kind: Kind,
    val from: Double,
    val to: Double,
    val fromPreviousDay: Boolean,
    val intoNextDay: Boolean,
) {
    enum class Kind {
        actual,
        running,
        planned,
    }
}

object DayArcs {
    fun pieces(day: LocalDate, archive: FastArchive, zone: ZoneId, now: Double): List<DayArc> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        data class Span(val id: String, val kind: DayArc.Kind, val start: Double, val end: Double)
        val spans =
            archive.fasts.map {
                Span(
                    it.id,
                    if (it.end == null) DayArc.Kind.running else DayArc.Kind.actual,
                    it.start,
                    it.end ?: now,
                )
            } +
                archive.plans
                    .filter { it.start >= now && it.goalMinutes != null }
                    .map {
                        Span(
                            it.id,
                            DayArc.Kind.planned,
                            it.start,
                            it.start + it.goalMinutes!! * 60_000.0,
                        )
                    }
        fun position(date: Double) =
            date.instant().atZone(zone).toLocalTime().toNanoOfDay() / 86_400_000_000_000.0
        return spans
            .mapNotNull { s ->
                if (s.end <= s.start || s.start >= end || s.end <= start) null
                else {
                    val from = if (s.start < start) 0.0 else position(s.start)
                    val to = if (s.end >= end) 1.0 else position(s.end)
                    DayArc(
                        s.id,
                        s.kind,
                        from,
                        maxOf(to, from),
                        fromPreviousDay = s.start < start,
                        intoNextDay = s.end > end,
                    )
                }
            }
            .sortedBy { it.from }
    }
}
