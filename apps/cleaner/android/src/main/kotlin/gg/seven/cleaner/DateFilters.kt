package gg.seven.cleaner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import gg.seven.cleaner.core.*
import gg.seven.kit.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val MediaDateFilter.title: String
    get() =
        when (this) {
            MediaDateFilter.ANY -> "Any date"
            MediaDateFilter.DAY -> "On a date"
            MediaDateFilter.MONTH -> "In a month"
            MediaDateFilter.YEAR -> "In a year"
            MediaDateFilter.ANNIVERSARY -> "On this day"
        }

private fun LocalDate.civil() = MediaCalendarDate(year, monthValue.toUInt(), dayOfMonth.toUInt())

internal fun matchesDate(
    value: Instant?,
    mode: MediaDateFilter,
    reference: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean = mediaDateMatches(mode, value?.atZone(zone)?.toLocalDate()?.civil(), reference.civil())

internal fun dateSummary(mode: MediaDateFilter, date: LocalDate): String =
    when (mode) {
        MediaDateFilter.ANY -> "Any date"
        MediaDateFilter.YEAR -> date.year.toString()
        MediaDateFilter.MONTH ->
            date.format(DateTimeFormatter.ofPattern("LLLL uuuu", Locale.getDefault()))
        MediaDateFilter.DAY -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        MediaDateFilter.ANNIVERSARY ->
            "On this day: ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
    }

@Composable
fun DateFilterEditor(filters: LibraryFilters, onClose: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(filters.dateMode) }
    var reference by rememberSaveable { mutableStateOf(filters.date.toString()) }
    FullScreenForm(
        "Date filter",
        onClose,
        onSave = {
            filters.dateMode = mode
            filters.date = LocalDate.parse(reference)
            onClose()
        },
        saveLabel = "Apply",
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
    dateMeaning: String =
        "Uses each file’s library date in this device’s current time zone. Files without a date appear only with Any date.",
) {
    DropdownField(
        "Match",
        mode,
        MediaDateFilter.entries.map { it to it.title },
        Modifier.testTag("cleaner-date-mode"),
    ) {
        onMode(it)
    }
    if (mode != MediaDateFilter.ANY)
        DateField(
            when (mode) {
                MediaDateFilter.MONTH -> "Date in the month"
                MediaDateFilter.YEAR -> "Date in the year"
                else -> "Reference date"
            },
            reference,
            Modifier.testTag("cleaner-date-reference"),
        ) {
            onReference(it)
        }
    Text(
        if (mode == MediaDateFilter.ANNIVERSARY)
            "The same month and day in earlier years. The reference year and later years are excluded; February 29 is not moved to another day."
        else dateMeaning
    )
    Text(
        if (showReviewNote)
            "Dates use the Gregorian calendar. Changing this filter does not change selections or review decisions."
        else "Dates use the Gregorian calendar."
    )
    TextButton(onClick = { onMode(MediaDateFilter.ANY) }) { Text("Clear date filter") }
}
