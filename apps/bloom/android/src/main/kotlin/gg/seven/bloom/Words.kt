package gg.seven.bloom

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The words for a stored record value (flow, mucus, test results, methods, schedules, statuses,
 * modes). Values outside this list, such as a custom observation or a plan name, are data and are
 * shown as entered. Phases and exclusion reasons the Rust core still supplies in English are
 * matched here too, so they read in the device language.
 */
@StringRes
internal fun valueWords(value: String): Int? =
    when (value) {
        "" -> R.string.value_not_recorded
        "none" -> R.string.value_none
        "spotting" -> R.string.value_spotting
        "light" -> R.string.value_light
        "medium" -> R.string.value_medium
        "heavy" -> R.string.value_heavy
        "dry" -> R.string.value_dry
        "sticky" -> R.string.value_sticky
        "creamy" -> R.string.value_creamy
        "watery" -> R.string.value_watery
        "egg_white" -> R.string.value_egg_white
        "negative" -> R.string.value_negative
        "positive" -> R.string.value_positive
        "unclear" -> R.string.value_unclear
        "partly_full" -> R.string.value_partly_full
        "full" -> R.string.value_full
        "overflow" -> R.string.value_overflow
        "symptom" -> R.string.value_symptom
        "flow" -> R.string.value_flow
        "temperature" -> R.string.value_temperature
        "ovulation_test" -> R.string.value_ovulation_test
        "pregnancy_test" -> R.string.value_pregnancy_test
        "product" -> R.string.value_product
        "pill" -> R.string.value_pill
        "ring" -> R.string.value_ring
        "patch" -> R.string.value_patch
        "injection" -> R.string.value_injection
        "implant" -> R.string.value_implant
        "iud" -> R.string.value_iud
        "medication" -> R.string.value_medication
        "emergency" -> R.string.value_emergency
        "pack" -> R.string.value_pack
        "daily" -> R.string.value_daily
        "interval" -> R.string.value_interval
        "weekly" -> R.string.value_weekly
        "once" -> R.string.value_once
        "taken" -> R.string.value_taken
        "skipped" -> R.string.value_skipped
        "snoozed" -> R.string.value_snoozed
        "pending" -> R.string.value_pending
        "active",
        "Active" -> R.string.value_active
        "break" -> R.string.value_break
        "scheduled" -> R.string.value_scheduled
        "Break / placebo" -> R.string.value_break_placebo
        "cycle" -> R.string.value_cycle
        "conceive" -> R.string.value_conceive
        "pregnancy" -> R.string.value_pregnancy
        "postpartum" -> R.string.value_postpartum
        "perimenopause" -> R.string.value_perimenopause
        "unlogged" -> R.string.value_unlogged
        "yes" -> R.string.value_yes
        "no" -> R.string.value_no
        "Pregnancy interval" -> R.string.value_pregnancy_interval
        "Hormonal contraception" -> R.string.value_hormonal_contraception
        else -> null
    }

@Composable internal fun valueText(value: String): String = valueWords(value)?.let { stringResource(it) } ?: value

internal fun Context.valueText(value: String): String = valueWords(value)?.let(::getString) ?: value

/** A day's flow as a heading: "Light flow", "No flow". */
@Composable
internal fun flowTitle(flow: String): String =
    when (flow) {
        "none" -> stringResource(R.string.flow_title_none)
        "spotting" -> stringResource(R.string.flow_title_spotting)
        "light" -> stringResource(R.string.flow_title_light)
        "medium" -> stringResource(R.string.flow_title_medium)
        "heavy" -> stringResource(R.string.flow_title_heavy)
        else -> flow
    }

/** A record date in the person's locale, such as "Nov 3, 2026" or "3 Nov 2026". */
internal fun pretty(day: String): String =
    LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** A pattern for the locale from a skeleton such as "MMMd" or "yMMMM". */
internal fun localPattern(skeleton: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton),
        locale,
    )

/** A short day and month, such as "Nov 3" or "3 Nov". */
internal fun shortDay(day: String): String = LocalDate.parse(day).format(localPattern("MMMd"))

/** Items joined the way the locale lists things ("a, b and c"). */
internal fun listText(items: List<String>): String =
    android.icu.text.ListFormatter.getInstance().format(items)
