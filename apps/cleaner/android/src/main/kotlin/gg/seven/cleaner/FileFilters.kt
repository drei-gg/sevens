package gg.seven.cleaner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import gg.seven.cleaner.core.*
import gg.seven.kit.*
import java.time.LocalDate

internal val FileSizeFilter.title: String
    @Composable
    @ReadOnlyComposable
    get() =
        stringResource(
            when (this) {
                FileSizeFilter.ANY -> R.string.size_any
                FileSizeFilter.AT_LEAST1_MB -> R.string.size_1mb
                FileSizeFilter.AT_LEAST10_MB -> R.string.size_10mb
                FileSizeFilter.AT_LEAST100_MB -> R.string.size_100mb
                FileSizeFilter.AT_LEAST1_GB -> R.string.size_1gb
                FileSizeFilter.UNKNOWN -> R.string.size_unknown
            }
        )

@Composable
@ReadOnlyComposable
internal fun extensionTitle(value: String?): String =
    when (value) {
        null -> stringResource(R.string.extension_any)
        "" -> stringResource(R.string.extension_none)
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
        stringResource(R.string.files_filters_title),
        close,
        onSave = {
            filters.extension = extension
            filters.size = size
            filters.dateMode = dateMode
            filters.date = LocalDate.parse(date)
            filters.duplicatesOnly = duplicates
            close()
        },
        saveLabel = stringResource(R.string.action_apply),
    ) {
        DropdownField(
            stringResource(R.string.files_filter_extension),
            extension,
            listOf(null to stringResource(R.string.extension_any)) + choices.map { it to extensionTitle(it) },
            Modifier.testTag("files-extension"),
        ) {
            extension = it
        }
        DropdownField(
            stringResource(R.string.files_filter_size),
            size,
            FileSizeFilter.entries.map { it to it.title },
            Modifier.testTag("files-size"),
        ) {
            size = it
        }
        FilterChip(
            selected = duplicates,
            onClick = { duplicates = !duplicates },
            label = { Text(stringResource(R.string.files_filter_duplicates)) },
            modifier = Modifier.testTag("files-duplicates"),
        )
        DateFilterFields(
            dateMode,
            LocalDate.parse(date),
            { dateMode = it },
            { date = it.toString() },
            showReviewNote = false,
            dateMeaning = stringResource(R.string.files_filter_date_meaning),
        )
        Text(
            stringResource(R.string.files_filter_note),
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
            Text(stringResource(R.string.files_filter_clear))
        }
    }
}
