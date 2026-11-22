package gg.seven.fast

import android.content.res.Resources
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import gg.seven.fast.core.*
import gg.seven.kit.SevenFormat
import java.time.ZoneId
import java.util.Locale

/*
 * The app's wording that needs more than one string resource: stamps, durations, amounts with
 * their unit, and the sentences for core errors. Every word lives in res/values; these only pick
 * and fill them. Composable overloads read the current resources, so text follows the locale.
 */

internal fun Double.number(digits: Int = 1): String =
    java.text.NumberFormat.getNumberInstance(Locale.getDefault())
        .apply {
            maximumFractionDigits = digits
            minimumFractionDigits = 0
        }
        .format(this)

/** Set from the device setting at the root, so every stamp honours 12/24-hour time. */
internal var use24Hour = false

/** "Nov 22, 2026 at 7:05 PM". */
internal fun Resources.stamp(value: Double): String {
    val at = value.instant().atZone(ZoneId.systemDefault())
    return getString(R.string.stamp, SevenFormat.date(at), SevenFormat.time(at, use24Hour))
}

internal fun Resources.stamp(value: java.time.Instant): String = stamp(value.millis())

@Composable @ReadOnlyComposable internal fun stamp(value: Double) = LocalResources.current.stamp(value)

@Composable
@ReadOnlyComposable
internal fun stamp(value: java.time.Instant) = LocalResources.current.stamp(value)

/** "16 hr", "16 hr 30 min", "45 min": the iOS duration wording. */
internal fun Resources.duration(value: Double): String {
    val mins = (value / 60_000).toLong().coerceAtLeast(0)
    val h = (mins / 60).toInt()
    val m = (mins % 60).toInt()
    val hours = getQuantityString(R.plurals.duration_hours, h, h)
    val minutes = getQuantityString(R.plurals.duration_minutes, m, m)
    return when {
        h == 0 -> minutes
        m == 0 -> hours
        else -> getString(R.string.duration_hours_minutes, hours, minutes)
    }
}

@Composable
@ReadOnlyComposable
internal fun duration(value: Double) = LocalResources.current.duration(value)

/** A goal in minutes as a duration, or "Open ended". */
internal fun Resources.goal(minutes: Int?): String =
    minutes?.let { duration(it * 60_000.0) } ?: getString(R.string.open_ended)

@Composable @ReadOnlyComposable internal fun goal(minutes: Int?) = LocalResources.current.goal(minutes)

/**
 * Decimal hours with the unit word, plural-correct for the locale ("1 hour", "5.5 hours"): Android
 * plurals take whole numbers only, so ICU picks the form.
 */
internal fun hoursText(value: Double, width: MeasureFormat.FormatWidth = MeasureFormat.FormatWidth.WIDE): String {
    val number =
        android.icu.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }
    return MeasureFormat.getInstance(Locale.getDefault(), width, number)
        .format(Measure(value, MeasureUnit.HOUR))
}

/** The unit label for [kind] in the chosen units: "kg", "lb", "mL", "US fl oz", "cm", "h". */
internal fun Resources.unit(kind: MetricKind, settings: TrackerSettings): String =
    getString(
        when (kind) {
            MetricKind.WEIGHT -> if (settings.usePounds) R.string.unit_lb else R.string.unit_kg
            MetricKind.WATER -> if (settings.useFluidOunces) R.string.unit_fl_oz else R.string.unit_ml
            MetricKind.WAIST -> R.string.unit_cm
            MetricKind.SLEEP -> R.string.unit_sleep_hours
        }
    )

@Composable
@ReadOnlyComposable
internal fun unit(kind: MetricKind, settings: TrackerSettings) = LocalResources.current.unit(kind, settings)

/** "250 mL": a stored value in the display unit, with its label. */
internal fun Resources.amount(
    value: Double,
    kind: MetricKind,
    settings: TrackerSettings,
    digits: Int = 1,
): String = getString(R.string.amount, Units.display(value, kind, settings).number(digits), unit(kind, settings))

@Composable
@ReadOnlyComposable
internal fun amount(value: Double, kind: MetricKind, settings: TrackerSettings, digits: Int = 1) =
    LocalResources.current.amount(value, kind, settings, digits)

/** "Weight", "Water", "Waist", "Sleep". */
internal fun Resources.kindName(kind: MetricKind): String =
    getString(
        when (kind) {
            MetricKind.WEIGHT -> R.string.metric_weight
            MetricKind.WATER -> R.string.metric_water
            MetricKind.WAIST -> R.string.metric_waist
            MetricKind.SLEEP -> R.string.metric_sleep
        }
    )

/** A preset's name; the core's built-in open-ended preset reads "Open ended" in the locale. */
internal fun Resources.presetName(preset: FastPreset): String =
    if (preset.goalMinutes == null && preset in FastPreset.defaults) getString(R.string.open_ended)
    else preset.name

/** The sentence the app shows for an error: core errors in the locale, others as thrown. */
internal fun Resources.explain(error: Throwable): String =
    when (error) {
        is TrackerException.InvalidTime -> getString(R.string.error_invalid_time)
        is TrackerException.FutureRecord -> getString(R.string.error_future_record)
        is TrackerException.InvalidGoal -> getString(R.string.error_invalid_goal)
        is TrackerException.Overlap -> getString(R.string.error_overlap)
        is TrackerException.MultipleActive -> getString(R.string.error_multiple_active)
        is TrackerException.DuplicateId -> getString(R.string.error_duplicate_id)
        is TrackerException.InvalidEntry -> getString(R.string.error_invalid_entry)
        is TrackerException.DanglingJournal -> getString(R.string.error_dangling_journal)
        is TrackerException.UnsupportedSchema -> getString(R.string.error_unsupported_schema)
        is TrackerException.Unreadable -> getString(R.string.error_unreadable)
        is TrackerException.Conflict -> getString(R.string.error_conflict)
        is TrackerException.TooLarge -> getString(R.string.error_too_large)
        is TrackerException.ReadOnly -> getString(R.string.error_read_only)
        else -> error.message ?: getString(R.string.error_generic)
    }
