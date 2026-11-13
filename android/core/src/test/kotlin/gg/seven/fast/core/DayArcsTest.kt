package gg.seven.fast.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class DayArcsTest {
    private val zone = ZoneId.of("America/New_York")

    private fun time(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli().toDouble()

    @Test
    fun midnightCrossingLinksButExactMidnightEndDoesNot() {
        val day = LocalDate.of(2026, 11, 10)
        val f =
            FastRecord(
                start = time("2026-11-10T18:00:00-05:00[America/New_York]"),
                end = time("2026-11-11T06:00:00-05:00[America/New_York]"),
            )
        val a = FastArchive(fasts = listOf(f))
        val now = f.end!! + 1
        val left = DayArcs.pieces(day, a, zone, now).single()
        val right = DayArcs.pieces(day.plusDays(1), a, zone, now).single()
        assertEquals(.75, left.from, 0.0)
        assertEquals(1.0, left.to, 0.0)
        assertTrue(left.intoNextDay)
        assertTrue(right.fromPreviousDay)
        assertEquals(.25, right.to, 0.0)
        val midnight = f.copy(end = time("2026-11-11T00:00:00-05:00[America/New_York]"))
        assertFalse(
            DayArcs.pieces(day, FastArchive(fasts = listOf(midnight)), zone, now)
                .single()
                .intoNextDay
        )
        assertTrue(
            DayArcs.pieces(day.plusDays(1), FastArchive(fasts = listOf(midnight)), zone, now)
                .isEmpty()
        )
    }

    @Test
    fun repeatedHourDoesNotWrapAndNoonStaysAtBottom() {
        val start = time("2026-11-01T01:45:00-04:00[America/New_York]")
        val end = time("2026-11-01T01:15:00-05:00[America/New_York]")
        val a = FastArchive(fasts = listOf(FastRecord(start = start, end = end)))
        val piece = DayArcs.pieces(LocalDate.of(2026, 11, 1), a, zone, end + 1).single()
        assertEquals(piece.from, piece.to, 0.0)
        assertEquals(.5, History.hours(a.fasts, LocalDate.of(2026, 11, 1), zone, end + 1), 0.0)
    }
}
