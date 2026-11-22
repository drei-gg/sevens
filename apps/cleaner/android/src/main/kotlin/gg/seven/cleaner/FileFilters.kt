package gg.seven.cleaner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import gg.seven.cleaner.core.*
import gg.seven.kit.*
import java.time.LocalDate

internal val FileSizeFilter.title: String
    get() =
        when (this) {
            FileSizeFilter.ANY -> "Any size"
            FileSizeFilter.AT_LEAST1_MB -> "At least 1 MB"
            FileSizeFilter.AT_LEAST10_MB -> "At least 10 MB"
            FileSizeFilter.AT_LEAST100_MB -> "At least 100 MB"
            FileSizeFilter.AT_LEAST1_GB -> "At least 1 GB"
            FileSizeFilter.UNKNOWN -> "Size unknown"
        }

internal fun extensionTitle(value: String?): String =
    when (value) {
        null -> "Any extension"
        "" -> "No extension"
        else -> ".$value"
    }

class FolderFilters {
    var extension by mutableStateOf<String?>(null)
    var size by mutableStateOf(FileSizeFilter.ANY)
    var dateMode by mutableStateOf(MediaDateFilter.ANY)
    var date by mutableStateOf(LocalDate.now())
    var duplicatesOnly by mutableStateOf(false)
    val search = TextFieldState()
    val active: Boolean
        get() =
            extension != null ||
                size != FileSizeFilter.ANY ||
                dateMode != MediaDateFilter.ANY ||
                duplicatesOnly

    fun clear() {
        extension = null
        size = FileSizeFilter.ANY
        dateMode = MediaDateFilter.ANY
        duplicatesOnly = false
    }
}

@Composable
fun rememberFolderFilters(): FolderFilters =
    rememberSaveable(
        saver =
            listSaver(
                save = {
                    listOf(
                        it.extension?.let { value -> "ext:$value" }.orEmpty(),
                        it.size.name,
                        it.dateMode.name,
                        it.date.toString(),
                        it.duplicatesOnly.toString(),
                        it.search.text.toString(),
                    )
                },
                restore = { values ->
                    FolderFilters().apply {
                        extension = values[0].takeIf { it.isNotEmpty() }?.removePrefix("ext:")
                        size = FileSizeFilter.valueOf(values[1])
                        dateMode = MediaDateFilter.valueOf(values[2])
                        date = LocalDate.parse(values[3])
                        duplicatesOnly = values[4].toBoolean()
                        search.edit { append(values[5]) }
                    }
                },
            )
    ) {
        FolderFilters()
    }

@Composable
fun FileFilterEditor(filters: FolderFilters, extensions: List<String>, close: () -> Unit) {
    var extension by rememberSaveable { mutableStateOf(filters.extension) }
    var size by rememberSaveable { mutableStateOf(filters.size) }
    var dateMode by rememberSaveable { mutableStateOf(filters.dateMode) }
    var date by rememberSaveable { mutableStateOf(filters.date.toString()) }
    var duplicates by rememberSaveable { mutableStateOf(filters.duplicatesOnly) }
    val choices = (extensions + listOfNotNull(extension)).distinct().sorted()
    FullScreenForm(
        "File filters",
        close,
        onSave = {
            filters.extension = extension
            filters.size = size
            filters.dateMode = dateMode
            filters.date = LocalDate.parse(date)
            filters.duplicatesOnly = duplicates
            close()
        },
        saveLabel = "Apply",
    ) {
        DropdownField(
            "Filename extension",
            extension,
            listOf(null to "Any extension") + choices.map { it to extensionTitle(it) },
            Modifier.testTag("files-extension"),
        ) {
            extension = it
        }
        DropdownField(
            "Measured size",
            size,
            FileSizeFilter.entries.map { it to it.title },
            Modifier.testTag("files-size"),
        ) {
            size = it
        }
        FilterChip(
            selected = duplicates,
            onClick = { duplicates = !duplicates },
            label = { Text("Exact duplicates only") },
            modifier = Modifier.testTag("files-duplicates"),
        )
        DateFilterFields(
            dateMode,
            LocalDate.parse(date),
            { dateMode = it },
            { date = it.toString() },
            showReviewNote = false,
            dateMeaning =
                "Uses the modification date supplied by the chosen folder, in this device’s current time zone. Files without a date appear only with Any date.",
        )
        Text(
            "Extensions come from filenames, not content inspection. Unknown sizes are kept separate; 1 MB is 1,000,000 bytes.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                extension = null
                size = FileSizeFilter.ANY
                dateMode = MediaDateFilter.ANY
                duplicates = false
            },
            modifier = Modifier.testTag("files-clear-draft"),
        ) {
            Text("Clear all filters")
        }
    }
}
