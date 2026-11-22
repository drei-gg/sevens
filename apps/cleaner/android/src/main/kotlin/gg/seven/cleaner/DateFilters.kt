package gg.seven.cleaner

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import gg.seven.cleaner.core.*
import gg.seven.kit.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val MediaDateFilter.title: String
    @Composable
    @ReadOnlyComposable
    get() =
        stringResource(
            when (this) {
                MediaDateFilter.ANY -> R.string.date_any
                MediaDateFilter.DAY -> R.string.date_day
                MediaDateFilter.MONTH -> R.string.date_month
                MediaDateFilter.YEAR -> R.string.date_year
                MediaDateFilter.ANNIVERSARY -> R.string.date_anniversary
            }
        )

private fun LocalDate.civil() = MediaCalendarDate(year, monthValue.toUInt(), dayOfMonth.toUInt())

internal fun matchesDate(
    value: Instant?,
    mode: MediaDateFilter,
    reference: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean = mediaDateMatches(mode, value?.atZone(zone)?.toLocalDate()?.civil(), reference.civil())

@Composable
@ReadOnlyComposable
internal fun dateSummary(mode: MediaDateFilter, date: LocalDate): String =
    when (mode) {
        MediaDateFilter.ANY -> stringResource(R.string.date_any)
        MediaDateFilter.YEAR -> date.year.toString()
        MediaDateFilter.MONTH -> {
            // The locale's own month-and-year order and month form, following configuration.
            val locale = LocalConfiguration.current.locales[0]
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "LLLLyyyy")
            date.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
        MediaDateFilter.DAY -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        MediaDateFilter.ANNIVERSARY ->
            stringResource(
                R.string.date_anniversary_summary,
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            )
    }

@Composable
fun DateFilterEditor(filters: LibraryFilters, onClose: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(filters.dateMode) }
    var reference by rememberSaveable { mutableStateOf(filters.date.toString()) }
    FullScreenForm(
        stringResource(R.string.date_filter_title),
        onClose,
        onSave = {
            filters.dateMode = mode
            filters.date = LocalDate.parse(reference)
            onClose()
        },
        saveLabel = stringResource(R.string.action_apply),
    ) {
        DateFilterFields(
            mode,
            LocalDate.parse(reference),
            { mode = it },
            { reference = it.toString() },
        )
    }
}

@Composable
internal fun DateFilterFields(
    mode: MediaDateFilter,
    reference: LocalDate,
    onMode: (MediaDateFilter) -> Unit,
    onReference: (LocalDate) -> Unit,
    showReviewNote: Boolean = true,
    dateMeaning: String = stringResource(R.string.date_meaning_library),
) {
    DropdownField(
        stringResource(R.string.date_match),
        mode,
        MediaDateFilter.entries.map { it to it.title },
        Modifier.testTag("cleaner-date-mode"),
    ) {
        onMode(it)
    }
    if (mode != MediaDateFilter.ANY)
        DateField(
            when (mode) {
                MediaDateFilter.MONTH -> stringResource(R.string.date_reference_month)
                MediaDateFilter.YEAR -> stringResource(R.string.date_reference_year)
                else -> stringResource(R.string.date_reference)
            },
            reference,
            Modifier.testTag("cleaner-date-reference"),
        ) {
            onReference(it)
        }
    Text(
        if (mode == MediaDateFilter.ANNIVERSARY) stringResource(R.string.date_anniversary_note)
        else dateMeaning
    )
    Text(
        if (showReviewNote) stringResource(R.string.date_gregorian_review)
        else stringResource(R.string.date_gregorian)
    )
    TextButton(onClick = { onMode(MediaDateFilter.ANY) }) { Text(stringResource(R.string.date_clear)) }
}
