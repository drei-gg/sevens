package gg.seven.fast.core

// A thin Kotlin face over the Rust core's generated bindings (fast_core.kt, from
// apps/fast/core). Every fasting rule, calculation and file format lives in Rust; this file only
// converts to the units the Android app works in (epoch-millisecond Doubles, LocalDate, ZoneId)
// and turns typed errors into the sentences the app shows. Scheduling reminders is platform work,
// so finding a repeating reminder's next moment stays here.

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

fun newId(): UUID = UUID.randomUUID()

fun nowMillis(): Double = System.currentTimeMillis().toDouble()

/** Epoch milliseconds, fractions kept, as the archive stores them. */
fun Double.instant(): Instant {
    val seconds = Math.floorDiv(toLong(), 1000L)
    val nanos = ((this - seconds * 1000.0) * 1_000_000.0).toLong()
    return Instant.ofEpochSecond(seconds, nanos)
}

fun Instant.millis(): Double = epochSecond * 1000.0 + nano / 1_000_000.0

fun LocalDate.start(zone: ZoneId): Instant = atStartOfDay(zone).toInstant()

fun Instant.localDate(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()

/** The device calendar: zone and first weekday in Foundation numbering (1 Sunday … 7 Saturday). */
fun calendarSpec(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) =
    CalendarSpec(zone.id, WeekFields.of(locale).firstDayOfWeek.foundation())

fun DayOfWeek.foundation(): Int = value % 7 + 1

// Records

fun FastRecord.duration(now: Double): Double = fastDuration(this, now.instant()) * 1000

val FastRecord.goalDate: Double?
    get() = fastGoalDate(this)?.millis()

val FastRecord.metGoal: Boolean?
    get() = fastMetGoal(this)

val FastRecord.searchableText: String
    get() = fastSearchableText(this)

fun FastRecord.matches(query: String): Boolean = historyMatches(this, query)

val FastArchive.active: FastRecord?
    get() = archiveActive(this)

val FastArchive.latestCompleted: FastRecord?
    get() = archiveLatestCompleted(this)

fun FastArchive.save(fast: FastRecord, now: Double): FastArchive = archiveSave(this, fast, now.instant())

fun FastArchive.deleteFast(id: UUID): FastArchive = archiveDeleteFast(this, id)

fun FastArchive.validate(now: Double) = archiveValidate(this, now.instant())

fun FastArchive.validated(now: Double): FastArchive = also { it.validate(now) }

val MetricKind.unit: String
    get() = metricCanonicalUnit(this)

/** "weight", "water", "waist", "sleep": the archive's names. */
val MetricKind.key: String
    get() = name.lowercase()

fun metricKind(key: String): MetricKind = MetricKind.valueOf(key.uppercase())

val WaterSettings.reminderHours: List<Int>
    get() = waterReminderHours(this)

fun WaterSettings.validate() = waterSettingsValidate(this)

val FastPreset.Companion.defaults: List<FastPreset>
    get() = defaultPresets()

fun FastArchive.Companion.empty(): FastArchive = newArchive()

fun fastRecord(
    start: Double,
    end: Double? = null,
    goalMinutes: Int? = 960,
    eatingMinutes: Int = 480,
    timeZoneId: String = ZoneId.systemDefault().id,
    intention: String = "",
    reflection: String = "",
    tags: List<String> = emptyList(),
    id: UUID = newId(),
) = FastRecord(id, start.instant(), end?.instant(), goalMinutes, eatingMinutes, timeZoneId, intention, reflection, tags)

fun metricEntry(date: Double, kind: MetricKind, value: Double, id: UUID = newId(), externalId: String? = null) =
    MetricEntry(id, date.instant(), kind, value, externalId)

object Units {
    fun display(value: Double, kind: MetricKind, settings: TrackerSettings) = unitsDisplay(value, kind, settings)

    fun canonical(value: Double, kind: MetricKind, settings: TrackerSettings) = unitsCanonical(value, kind, settings)

    fun label(kind: MetricKind, settings: TrackerSettings) =
        when {
            kind == MetricKind.WEIGHT && settings.usePounds -> "lb"
            kind == MetricKind.WATER && settings.useFluidOunces -> "US fl oz"
            else -> kind.unit
        }
}

object ArchiveCodec {
    val maxBytes: Long = archiveMaxBytes().toLong()

    fun encode(archive: FastArchive): String = archiveEncode(archive).decodeToString()

    fun decode(text: String, now: Double): FastArchive = archiveDecode(text.encodeToByteArray(), now.instant())

    fun merge(incoming: FastArchive, current: FastArchive, now: Double): FastArchive =
        archiveMerge(incoming, current, now.instant()).archive

    fun preview(incoming: FastArchive, current: FastArchive, now: Double): ImportPreview =
        archiveMerge(incoming, current, now.instant())

    fun count(a: FastArchive) =
        a.fasts.size + a.journal.size + a.metrics.size + a.plans.size + a.restDays.size + a.presets.size

    fun csv(a: FastArchive): String = archiveCsv(a)
}

data class Summary(
    val hours: Double,
    val sessions: Int,
    val averageHours: Double?,
    val goalsMet: Int,
    val goalsRecorded: Int,
)

object History {
    /** Recorded fasting hours inside `day` (actual elapsed time; 23 or 25 on clock-change days). */
    fun hours(archive: FastArchive, day: LocalDate, zone: ZoneId, now: Double): Double =
        (day(archive, day, zone, now)?.seconds ?: 0.0) / 3_600

    fun day(archive: FastArchive, day: LocalDate, zone: ZoneId, now: Double): DaySummary? =
        historyDay(day.start(zone), archive, calendarSpec(zone), now.instant())

    fun onDay(date: Instant, day: LocalDate, zone: ZoneId) = date.localDate(zone) == day

    fun water(metrics: List<MetricEntry>, day: LocalDate, zone: ZoneId) =
        waterTotal(metrics, day.start(zone), calendarSpec(zone))

    fun waterEntries(metrics: List<MetricEntry>, day: LocalDate, zone: ZoneId): List<MetricEntry> =
        gg.seven.fast.core.waterEntries(metrics, day.start(zone), calendarSpec(zone))

    fun waterDays(metrics: List<MetricEntry>, end: LocalDate, count: Int, zone: ZoneId): List<WaterDay> =
        gg.seven.fast.core.waterDays(metrics, end.start(zone), count, calendarSpec(zone))

    /** The `count` days ending with `end`. */
    fun summary(a: FastArchive, end: LocalDate, count: Int, zone: ZoneId, now: Double): Summary {
        val s = historySummary(end.minusDays(count - 1L).start(zone), end.plusDays(1).start(zone), a, now.instant())
        return Summary(s.seconds / 3_600, s.completedCount, s.averageDuration?.div(3_600), s.goalsMet, s.goalsCount)
    }

    fun monthGrid(day: LocalDate, zone: ZoneId): List<LocalDate> =
        historyMonthGrid(day.start(zone), calendarSpec(zone)).map { it.localDate(zone) }
}

object DayArcs {
    fun pieces(day: LocalDate, archive: FastArchive, zone: ZoneId, now: Double): List<DayArc> =
        dayArcs(day.start(zone), archive, calendarSpec(zone), now.instant())
}

/** The sentence the app shows for a core error. */
val Throwable.explanation: String
    get() =
        when (this) {
            is TrackerException.InvalidTime -> "End must be after start."
            is TrackerException.FutureRecord -> "Records can't be in the future."
            is TrackerException.InvalidGoal ->
                "Goal must be 1 minute to 365 days, and the eating window 1 minute to 7 days."
            is TrackerException.Overlap -> "This overlaps another fast."
            is TrackerException.MultipleActive -> "Only one fast can be running."
            is TrackerException.DuplicateId -> "This record is already here."
            is TrackerException.InvalidEntry -> "Check the dates, amounts and text."
            is TrackerException.DanglingJournal -> "This entry links to a fast that no longer exists."
            is TrackerException.UnsupportedSchema -> "This backup is from a newer version of Fast."
            is TrackerException.Unreadable -> "This file isn't a Fast backup."
            is TrackerException.Conflict -> "This backup has a different version of a record you already have."
            is TrackerException.TooLarge -> "This backup is too large."
            is TrackerException.ReadOnly -> "Records are read-only."
            else -> message ?: "Something went wrong."
        }
