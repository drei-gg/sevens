package gg.seven.alarm

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.seven.alarm.core.*
import gg.seven.kit.SevenColors
import gg.seven.kit.Space
import gg.seven.kit.numeric
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Times the way the phone's clock writes them, split so the hours and minutes can be big and the
 * AM/PM small, as clock apps do (iOS ClockFormat). [h24] reads the system setting each time, through
 * the application context [AlarmApplication] installs before any activity, receiver or service
 * runs, so a notification or ringing screen after process death follows it too.
 */
object ClockFormat {
    /** Reads the system setting through the application context (which lives as the process). */
    @Volatile private var system: (() -> Boolean)? = null

    /** Tests pin the clock style; null follows the system setting. */
    @Volatile var h24Override: Boolean? = null

    fun install(context: android.content.Context) {
        val app = context.applicationContext
        system = { android.text.format.DateFormat.is24HourFormat(app) }
    }

    val h24: Boolean
        get() = h24Override ?: system?.invoke() ?: false

    private val locale
        get() = Locale.getDefault()

    private fun pattern(): String {
        val best = android.text.format.DateFormat.getBestDateTimePattern(locale, if (h24) "Hmm" else "hmm")
        return best.replace("a", "").trim(' ', ' ', ' ')
    }

    /** "7:00" and "AM", or "07:00" and null on a 24-hour clock. */
    fun parts(at: ZonedDateTime): Pair<String, String?> {
        val time = at.format(DateTimeFormatter.ofPattern(pattern(), locale))
        if (h24) return time to null
        val symbols = java.text.DateFormatSymbols.getInstance(locale).amPmStrings
        return time to symbols[if (at.hour < 12) 0 else 1]
    }

    fun parts(at: Instant): Pair<String, String?> = parts(at.atZone(ZoneId.systemDefault()))

    /** A clock time stored as minutes after midnight (a routine's shared time). */
    fun parts(minute: Int): Pair<String, String?> =
        parts(LocalTime.of(minute / 60, minute % 60).atDate(java.time.LocalDate.of(2001, 1, 1)).atZone(ZoneId.of("UTC")))

    fun time(at: Instant) = parts(at).let { (time, period) -> if (period == null) time else "$time $period" }

    fun time(minute: Int) = parts(minute).let { (time, period) -> if (period == null) time else "$time $period" }

    /** "in 14 hr, 46 min", rounded down to the minute. */
    fun countdown(to: Instant, from: Instant): String {
        val seconds = ChronoUnit.SECONDS.between(from, to)
        if (seconds < 60) return "in less than a minute"
        var minutes = seconds / 60
        val days = minutes / 1440
        minutes -= days * 1440
        val hours = minutes / 60
        minutes -= hours * 60
        val all = listOf(days to MeasureUnit.DAY, hours to MeasureUnit.HOUR, minutes to MeasureUnit.MINUTE)
        val shown = all.dropWhile { it.first == 0L }.take(2).filter { it.first > 0 }
        val text = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(*shown.map { Measure(it.first, it.second) }.toTypedArray())
        return "in $text"
    }

    /** "Today", "Tomorrow" or the weekday, for the day an alarm rings. */
    fun day(at: Instant, from: Instant): String {
        val zone = ZoneId.systemDefault()
        val date = at.atZone(zone).toLocalDate()
        val today = from.atZone(zone).toLocalDate()
        return when {
            date == today -> "Today"
            date == today.plusDays(1) -> "Tomorrow"
            ChronoUnit.SECONDS.between(from, at) < 6 * 86_400 -> date.dayOfWeek.getDisplayName(DayStyle.FULL, locale)
            else -> at.atZone(zone).format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale))
        }
    }

    /** "Thursday, Nov 20": a one-off's date. */
    fun longDay(at: Instant): String =
        at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMd"), locale))

    /** "Thu, Nov 20". */
    fun shortDay(at: Instant): String =
        at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale))

    /** "5 minutes", "1 minute": plural-safe and localized. */
    fun minutes(count: Int, short: Boolean = false): String =
        MeasureFormat.getInstance(locale, if (short) MeasureFormat.FormatWidth.SHORT else MeasureFormat.FormatWidth.WIDE).format(Measure(count, MeasureUnit.MINUTE))
}

enum class ClockRole {
    Display,
    Hero,
    Title,
    Section,
}

/** A big clock time: the digits in one role, AM/PM smaller beside them. */
@Composable
fun ClockText(parts: Pair<String, String?>, modifier: Modifier = Modifier, role: ClockRole = ClockRole.Title, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    val type = MaterialTheme.typography
    val (digits, period) =
        when (role) {
            ClockRole.Display -> type.displayLarge.copy(fontWeight = FontWeight.Bold) to type.titleLarge
            ClockRole.Hero -> type.displayMedium.copy(fontWeight = FontWeight.Bold) to type.titleMedium
            ClockRole.Title -> type.headlineMedium to type.bodyLarge.copy(fontWeight = FontWeight.Bold)
            ClockRole.Section -> type.titleLarge to type.bodySmall.copy(fontWeight = FontWeight.Bold)
        }
    Row(
        modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(parts.first, style = digits.numeric(), color = color, maxLines = 1, modifier = Modifier.alignByBaseline())
        parts.second?.let { Text(it, style = period, color = color, maxLines = 1, modifier = Modifier.alignByBaseline()) }
    }
}

/** Weekdays in the person's week order (Foundation numbers, 1 = Sunday). */
object WeekOrder {
    val days: List<Int>
        get() {
            val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
            val start = if (first == DayOfWeek.SUNDAY) 1 else first.value + 1
            return (0 until 7).map { (start - 1 + it) % 7 + 1 }
        }

    fun dayOfWeek(day: Int): DayOfWeek = if (day == 1) DayOfWeek.SUNDAY else DayOfWeek.of(day - 1)

    fun letter(day: Int): String = dayOfWeek(day).getDisplayName(DayStyle.NARROW, Locale.getDefault())

    fun name(day: Int): String = dayOfWeek(day).getDisplayName(DayStyle.FULL, Locale.getDefault())

    fun short(day: Int): String = dayOfWeek(day).getDisplayName(DayStyle.SHORT, Locale.getDefault())

    fun spoken(days: List<Int>): String {
        val set = days.toSet()
        return when {
            set.size == 7 -> "Every day"
            set == setOf(2, 3, 4, 5, 6) -> "Weekdays"
            set == setOf(1, 7) -> "Weekends"
            else -> {
                val names = WeekOrder.days.filter { it in set }.map(::name)
                android.icu.text.ListFormatter.getInstance(Locale.getDefault()).format(names)
            }
        }
    }

    /** "Mon · Wed · Fri", in week order. */
    fun list(days: List<Int>) = WeekOrder.days.filter { it in days }.joinToString(" · ") { short(it) }
}

/** The week as initials, the days that ring bold and the rest muted: M T W T F S S. */
@Composable
fun DayLetters(days: List<Int>, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = WeekOrder.spoken(days) },
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        WeekOrder.days.forEach { day ->
            val on = day in days
            Text(
                WeekOrder.letter(day),
                Modifier.widthIn(min = 14.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                color = if (on && !dimmed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A 24-hour dial, midnight at the top: a faint track, a dot where now is, and an arc in the time
 * hue from now to the next alarm when it rings within a day, ending in a knob (iOS DayDial). The
 * night, sunrise, noon and sunset marks are decoration; the words in the middle carry the meaning.
 */
@Composable
fun DayDial(now: Instant, next: Instant?, modifier: Modifier = Modifier, width: Dp = 14.dp, content: @Composable BoxScope.() -> Unit) {
    val start = fraction(now)
    val span = next?.let { ChronoUnit.SECONDS.between(now, it) / 86_400.0 } ?: 0.0
    val track = MaterialTheme.colorScheme.outlineVariant
    val hue = SevenColors.time
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val card = MaterialTheme.colorScheme.surface
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(width / 2).clearAndSetSemantics {}) {
            val stroke = width.toPx()
            val radius = min(size.width, size.height) / 2
            val center = Offset(size.width / 2, size.height / 2)
            fun point(f: Double) = Offset(center.x + radius * cos(f * 2 * Math.PI - Math.PI / 2).toFloat(), center.y + radius * sin(f * 2 * Math.PI - Math.PI / 2).toFloat())
            drawCircle(track, radius, center, style = Stroke(stroke))
            val visible = span > 0 && span <= 1
            if (visible)
                drawArc(
                    hue,
                    startAngle = (start * 360 - 90).toFloat(),
                    sweepAngle = (span * 360).toFloat(),
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            drawCircle(dot, stroke * 0.2f, point(start))
            if (visible) {
                val knob = point(start + span)
                drawCircle(card, stroke * 0.45f, knob)
                drawCircle(hue, stroke * 0.45f, knob, style = Stroke(stroke / 4))
            }
        }
        // The quarter marks just inside the track.
        val marks = listOf(Icons.Outlined.Bedtime, Icons.Outlined.WbTwilight, Icons.Outlined.WbSunny, Icons.Outlined.WbTwilight)
        BoxWithConstraints(Modifier.fillMaxSize().padding(width / 2)) {
            val radius = min(maxWidth.value, maxHeight.value) / 2 - width.value - Space.ms.value - 8
            marks.forEachIndexed { index, icon ->
                val f = index / 4.0
                val x = (radius * cos(f * 2 * Math.PI - Math.PI / 2)).toFloat()
                val y = (radius * sin(f * 2 * Math.PI - Math.PI / 2)).toFloat()
                Icon(
                    icon,
                    null,
                    Modifier.align(Alignment.Center).offset(x.dp, y.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.padding(Space.xxl), contentAlignment = Alignment.Center, content = content)
    }
}

private fun fraction(at: Instant): Double {
    val time = at.atZone(ZoneId.systemDefault())
    return (time.hour * 60 + time.minute) / 1440.0
}

val MissionKind.title: String
    get() =
        when (this) {
            MissionKind.NONE -> "None"
            MissionKind.ARITHMETIC -> "Arithmetic"
            MissionKind.TYPING -> "Type a phrase"
            MissionKind.CODE -> "Scan a code"
            MissionKind.MEMORY -> "Memory tiles"
            MissionKind.SHAKE -> "Shake"
            MissionKind.WALK -> "Walk"
            MissionKind.NUMBER_ORDER -> "Number order"
            MissionKind.COLOR_WORD -> "Color clash"
        }

val MissionKind.icon: ImageVector
    get() =
        when (this) {
            MissionKind.NONE -> Icons.Outlined.Alarm
            MissionKind.ARITHMETIC -> Icons.Outlined.Calculate
            MissionKind.TYPING -> Icons.Outlined.Keyboard
            MissionKind.CODE -> Icons.Outlined.QrCodeScanner
            MissionKind.MEMORY -> Icons.Outlined.GridView
            MissionKind.SHAKE -> Icons.Outlined.Vibration
            MissionKind.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
            MissionKind.NUMBER_ORDER -> Icons.Outlined.Pin
            MissionKind.COLOR_WORD -> Icons.Outlined.Palette
        }

val EventKind.title: String
    get() =
        when (this) {
            EventKind.COMPLETED -> "Mission completed"
            EventKind.DISMISSED -> "System dismissal"
            EventKind.SNOOZED -> "Snoozed"
            EventKind.EMERGENCY_STOP -> "Emergency stop"
            EventKind.MANUAL -> "Manual check-in"
            EventKind.FOLLOW_UP_SCHEDULED -> "Wake-up check scheduled"
        }

val EventKind.icon: ImageVector
    get() =
        when (this) {
            EventKind.COMPLETED -> Icons.Outlined.CheckCircle
            EventKind.DISMISSED -> Icons.Outlined.HighlightOff
            EventKind.SNOOZED -> Icons.Outlined.Snooze
            EventKind.EMERGENCY_STOP -> Icons.Outlined.Report
            EventKind.MANUAL -> Icons.Outlined.TouchApp
            EventKind.FOLLOW_UP_SCHEDULED -> Icons.Outlined.NotificationsActive
        }

val AlarmTone.title: String
    get() =
        when (this) {
            AlarmTone.SYSTEM -> "System default"
            AlarmTone.CHIME -> "Soft chime"
            AlarmTone.PULSE -> "Pulse"
            AlarmTone.BRIGHT -> "Bright bells"
        }

/** The next time this routine rings or reminds, if it is on. */
fun Routine.next(after: Instant): Instant? {
    if (!enabled) return null
    return runCatching { scheduleOccurrences(this, after, if (timing == Timing.ONCE) 400 else 8, deviceCalendar()) }.getOrNull()?.firstOrNull()
}

/** The challenge level from 1 to 3; older backups without one get 1. */
val Routine.difficulty: Int
    get() = routineDifficulty(this)

/** Tabular figures on a style. */
val TextStyle.tabular: TextStyle
    get() = numeric()
