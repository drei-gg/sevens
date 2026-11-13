package gg.seven.fast.core

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

fun newId(): String = UUID.randomUUID().toString().uppercase()

fun nowMillis(): Double = System.currentTimeMillis().toDouble()

fun Double.instant(): Instant = Instant.ofEpochMilli(toLong())

// Field names and millisecond timestamps intentionally match iOS FastCore's schema 1.
@Serializable
data class FastRecord(
    val id: String = newId(),
    val start: Double,
    val end: Double? = null,
    val goalMinutes: Int? = 960,
    val eatingMinutes: Int = 480,
    val timeZoneID: String = ZoneId.systemDefault().id,
    val intention: String = "",
    val reflection: String = "",
    val tags: List<String> = emptyList(),
) {
    fun duration(now: Double) = ((end ?: now) - start).coerceAtLeast(0.0)

    val goalDate
        get() = goalMinutes?.let { start + it * 60_000.0 }

    val metGoal
        get() = end?.let { finish -> goalDate?.let { finish >= it } }

    val searchableText
        get() = (listOf(intention, reflection) + tags).joinToString(" ")
}

@Serializable
data class JournalEntry(
    val id: String = newId(),
    val date: Double,
    val fastID: String? = null,
    val text: String = "",
    val mood: Int? = null,
    val energy: Int? = null,
    val hunger: Int? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
enum class MetricKind(val unit: String, val validRange: ClosedFloatingPointRange<Double>) {
    weight("kg", 1.0..700.0),
    water("mL", 1.0..10_000.0),
    waist("cm", 1.0..400.0),
    sleep("h", 0.01..24.0),
}

@Serializable
data class MetricEntry(
    val id: String = newId(),
    val date: Double,
    val kind: MetricKind,
    val value: Double,
    val externalID: String? = null,
)

@Serializable
data class PlannedFast(
    val id: String = newId(),
    val start: Double,
    val goalMinutes: Int? = 960,
    val eatingMinutes: Int = 480,
    val intention: String = "",
)

@Serializable data class RestDay(val id: String = newId(), val date: Double, val note: String = "")

@Serializable
data class FastPreset(
    val id: String = newId(),
    val name: String,
    val goalMinutes: Int?,
    val eatingMinutes: Int,
) {
    companion object {
        val defaults =
            listOf(12, 14, 16, 18, 20).mapIndexed { index, h ->
                FastPreset(
                    "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                    "$h:${24 - h}",
                    h * 60,
                    (24 - h) * 60,
                )
            } + FastPreset("00000000-0000-0000-0000-000000000006", "Open ended", null, 480)
    }
}

@Serializable
data class WaterSettings(
    val goalMilliliters: Double? = null,
    val quickAddMilliliters: Double = 250.0,
    val remindersEnabled: Boolean = false,
    val remindersPaused: Boolean = false,
    val startHour: Int = 9,
    val endHour: Int = 21,
    val intervalHours: Int = 2,
) {
    fun validate() {
        require(goalMilliliters == null || goalMilliliters in MetricKind.water.validRange) {
            "Water goal must be 1–10,000 mL"
        }
        require(quickAddMilliliters in MetricKind.water.validRange) { "Glass must be 1–10,000 mL" }
        require(
            startHour in 0..23 && endHour in 1..24 && startHour < endHour && intervalHours in 1..4
        ) {
            "Choose a daytime reminder window and 1–4 hour interval"
        }
    }

    val reminderHours
        get() =
            if (remindersEnabled && !remindersPaused)
                (startHour until endHour step intervalHours).toList()
            else emptyList()
}

@Serializable
data class TrackerSettings(
    val water: WaterSettings = WaterSettings(),
    val usePounds: Boolean = false,
    val useFluidOunces: Boolean = false,
    val showWeight: Boolean = true,
    val showEatingWindow: Boolean = true,
    val liveActivity: Boolean = false,
    val goalReminder: Boolean = false,
    val eatingReminder: Boolean = false,
    val scheduleReminders: Boolean = false,
    val reminderWeekdays: List<Int> = emptyList(),
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)

@Serializable
data class FastArchive(
    val schemaVersion: Int = 1,
    val fasts: List<FastRecord> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val metrics: List<MetricEntry> = emptyList(),
    val plans: List<PlannedFast> = emptyList(),
    val restDays: List<RestDay> = emptyList(),
    val presets: List<FastPreset> = emptyList(),
    val settings: TrackerSettings = TrackerSettings(),
    val companionRevision: String = newId(),
    val companionReceipts: List<JsonElement> = emptyList(),
) {
    val active
        get() = fasts.firstOrNull { it.end == null }

    val latestCompleted
        get() = fasts.filter { it.end != null }.maxByOrNull { it.end!! }

    fun save(fast: FastRecord, now: Double) =
        copy(fasts = fasts.filterNot { it.id == fast.id } + fast).validated(now)

    fun deleteFast(id: String) =
        copy(
            fasts = fasts.filterNot { it.id == id },
            journal = journal.map { if (it.fastID == id) it.copy(fastID = null) else it },
        )

    fun validated(now: Double): FastArchive {
        validate(now)
        return this
    }

    fun validate(now: Double) {
        require(schemaVersion == 1) {
            "Unsupported backup version. Keep this file for a newer app."
        }
        require(
            fasts.size + journal.size + metrics.size + plans.size + restDays.size <= 100_000 &&
                presets.size <= 100 &&
                companionReceipts.size <= 2_000
        ) {
            "Archive is too large"
        }
        unique(fasts.map { it.id })
        unique(journal.map { it.id })
        unique(metrics.map { it.id })
        unique(plans.map { it.id })
        unique(restDays.map { it.id })
        unique(presets.map { it.id })
        require(fasts.count { it.end == null } <= 1) { "Only one fast can be running" }
        val sorted = fasts.sortedBy { it.start }
        sorted.forEachIndexed { index, fast ->
            date(fast.start)
            fast.end?.let { date(it) }
            require(fast.end == null || fast.end > fast.start) { "End must be after start" }
            require(fast.start <= now && (fast.end == null || fast.end <= now)) {
                "Actual records cannot be in the future. Use a plan."
            }
            goal(fast.goalMinutes, fast.eatingMinutes)
            ZoneId.of(fast.timeZoneID)
            require(fast.intention.length <= 10_000 && fast.reflection.length <= 10_000) {
                "Text is too long"
            }
            tags(fast.tags)
            if (index > 0)
                require((sorted[index - 1].end ?: Double.POSITIVE_INFINITY) <= fast.start) {
                    "Fasts cannot overlap"
                }
        }
        val ids = fasts.map { it.id }.toSet()
        journal.forEach {
            date(it.date)
            require(it.date <= now && it.text.length <= 20_000) { "Invalid journal date or text" }
            require(
                listOf(it.mood, it.energy, it.hunger).all { score ->
                    score == null || score in 1..5
                }
            ) {
                "Ratings are 1–5"
            }
            require(it.fastID == null || it.fastID in ids) {
                "Journal link refers to a missing fast"
            }
            tags(it.tags)
        }
        metrics.forEach {
            date(it.date)
            require(
                it.date <= now &&
                    it.value.isFinite() &&
                    it.value in it.kind.validRange &&
                    (it.externalID?.length ?: 0) < 256
            ) {
                "Invalid ${it.kind} amount or date"
            }
        }
        val external = metrics.mapNotNull { it.externalID }
        require(external.distinct().size == external.size) { "Duplicate external measurement" }
        plans.forEach {
            date(it.start)
            goal(it.goalMinutes, it.eatingMinutes)
            require(it.intention.length <= 10_000)
        }
        restDays.forEach {
            date(it.date)
            require(it.note.length <= 10_000)
        }
        presets.forEach {
            require(it.name.isNotBlank() && it.name.length <= 80)
            goal(it.goalMinutes, it.eatingMinutes)
        }
        settings.water.validate()
        require(
            settings.reminderHour in 0..23 &&
                settings.reminderMinute in 0..59 &&
                settings.reminderWeekdays.all { it in 1..7 } &&
                settings.reminderWeekdays.distinct().size == settings.reminderWeekdays.size
        ) {
            "Invalid reminder schedule"
        }
    }

    private fun unique(ids: List<String>) {
        val parsed = ids.map { UUID.fromString(it) }
        require(parsed.distinct().size == parsed.size) { "Duplicate record ID" }
    }

    private fun date(value: Double) =
        require(value.isFinite() && value >= -2_208_988_800_000.0 && value < 7_258_118_400_000.0) {
            "Invalid date"
        }

    private fun goal(value: Int?, eating: Int) =
        require((value == null || value in 1..525_600) && eating in 1..10_080) {
            "Invalid timer duration"
        }

    private fun tags(values: List<String>) =
        require(values.size <= 20 && values.all { it.isNotEmpty() && it.length <= 80 }) {
            "Use at most 20 tags, each 1–80 characters"
        }
}

object Units {
    const val poundsPerKilogram = 2.2046226218487757
    const val millilitersPerFluidOunce = 29.5735295625

    fun display(value: Double, kind: MetricKind, settings: TrackerSettings) =
        when {
            kind == MetricKind.weight && settings.usePounds -> value * poundsPerKilogram
            kind == MetricKind.water && settings.useFluidOunces -> value / millilitersPerFluidOunce
            else -> value
        }

    fun canonical(value: Double, kind: MetricKind, settings: TrackerSettings) =
        when {
            kind == MetricKind.weight && settings.usePounds -> value / poundsPerKilogram
            kind == MetricKind.water && settings.useFluidOunces -> value * millilitersPerFluidOunce
            else -> value
        }

    fun label(kind: MetricKind, settings: TrackerSettings) =
        when {
            kind == MetricKind.weight && settings.usePounds -> "lb"
            kind == MetricKind.water && settings.useFluidOunces -> "US fl oz"
            else -> kind.unit
        }
}
