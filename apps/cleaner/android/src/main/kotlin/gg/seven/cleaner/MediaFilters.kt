package gg.seven.cleaner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import gg.seven.cleaner.core.FileSizeFilter
import gg.seven.cleaner.core.MediaDurationFilter
import gg.seven.cleaner.core.MediaKind
import gg.seven.cleaner.core.MediaResolutionFilter
import gg.seven.cleaner.core.largeMinimumChoices
import gg.seven.cleaner.core.largeMinimumDefault
import gg.seven.kit.DropdownField
import gg.seven.kit.FullScreenForm
import gg.seven.kit.Space

internal val MediaResolutionFilter.title: String
    @Composable
    @ReadOnlyComposable
    get() =
        stringResource(
            when (this) {
                MediaResolutionFilter.ANY -> R.string.resolution_any
                MediaResolutionFilter.AT_LEAST2160 -> R.string.resolution_2160
                MediaResolutionFilter.AT_LEAST1080 -> R.string.resolution_1080
                MediaResolutionFilter.AT_LEAST720 -> R.string.resolution_720
                MediaResolutionFilter.BELOW720 -> R.string.resolution_below_720
                MediaResolutionFilter.UNKNOWN -> R.string.resolution_unknown
            }
        )

internal val MediaDurationFilter.title: String
    @Composable
    @ReadOnlyComposable
    get() =
        stringResource(
            when (this) {
                MediaDurationFilter.ANY -> R.string.duration_any
                MediaDurationFilter.UNDER1_MINUTE -> R.string.duration_under_1m
                MediaDurationFilter.AT_LEAST1_MINUTE -> R.string.duration_1m
                MediaDurationFilter.AT_LEAST5_MINUTES -> R.string.duration_5m
                MediaDurationFilter.AT_LEAST30_MINUTES -> R.string.duration_30m
                MediaDurationFilter.UNKNOWN -> R.string.duration_unknown
            }
        )

/** Large's minimum for [kind]: the chosen one, or the core's default. */
internal fun LibraryFilters.largeFrom(kind: MediaKind): Long = largeMinimum ?: largeMinimumDefault(kind)

/** The active size, dimension, length and Large-minimum filters as removable chips (label, clear). */
@Composable
internal fun measureChips(filters: LibraryFilters, kind: MediaKind): List<Pair<String, () -> Unit>> {
    val sizeTitle = filters.size.title
    val resolutionTitle = filters.resolution.title
    val durationTitle = filters.duration.title
    val large = filters.largeMinimum
    val largeTitle = large?.let { stringResource(R.string.large_from_chip, byteText(it)) }
    return buildList {
        if (filters.size != FileSizeFilter.ANY)
            add(sizeTitle to { filters.size = FileSizeFilter.ANY })
        if (filters.resolution != MediaResolutionFilter.ANY)
            add(resolutionTitle to { filters.resolution = MediaResolutionFilter.ANY })
        if (kind == MediaKind.VIDEO && filters.duration != MediaDurationFilter.ANY)
            add(durationTitle to { filters.duration = MediaDurationFilter.ANY })
        if (large != null && large != largeMinimumDefault(kind) && largeTitle != null)
            add(largeTitle to { filters.largeMinimum = null })
    }
}

/** Size, dimensions and (for videos) length: a draft applied together, like the date filter. */
@Composable
fun MediaMeasureEditor(filters: LibraryFilters, kind: MediaKind, onClose: () -> Unit) {
    var size by rememberSaveable { mutableStateOf(filters.size) }
    var resolution by rememberSaveable { mutableStateOf(filters.resolution) }
    var duration by rememberSaveable { mutableStateOf(filters.duration) }
    var largeFrom by rememberSaveable { mutableLongStateOf(filters.largeFrom(kind)) }
    val video = kind == MediaKind.VIDEO
    FullScreenForm(
        stringResource(if (video) R.string.measure_title_video else R.string.measure_title_photo),
        onClose,
        onSave = {
            filters.size = size
            filters.resolution = resolution
            filters.duration = duration
            filters.largeMinimum = largeFrom.takeIf { it != largeMinimumDefault(kind) }
            onClose()
        },
        saveLabel = stringResource(R.string.action_apply),
    ) {
        DropdownField(
            stringResource(R.string.measure_size),
            size,
            FileSizeFilter.entries.map { it to it.title },
            Modifier.testTag("media-size"),
        ) {
            size = it
        }
        DropdownField(
            stringResource(if (video) R.string.measure_resolution else R.string.measure_dimensions),
            resolution,
            MediaResolutionFilter.entries.map { it to it.title },
            Modifier.testTag("media-resolution"),
        ) {
            resolution = it
        }
        if (video)
            DropdownField(
                stringResource(R.string.measure_length),
                duration,
                MediaDurationFilter.entries.map { it to it.title },
                Modifier.testTag("media-duration"),
            ) {
                duration = it
            }
        val largeChoices =
            largeMinimumChoices(kind).map {
                it to stringResource(R.string.large_at_least, byteText(it))
            }
        DropdownField(
            stringResource(R.string.measure_large_from),
            largeFrom,
            largeChoices,
            Modifier.testTag("media-large-minimum"),
        ) {
            largeFrom = it
        }
        Text(
            stringResource(R.string.measure_note),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                size = FileSizeFilter.ANY
                resolution = MediaResolutionFilter.ANY
                duration = MediaDurationFilter.ANY
                largeFrom = largeMinimumDefault(kind)
            },
            modifier = Modifier.testTag("media-clear-draft"),
        ) {
            Text(stringResource(R.string.measure_clear))
        }
    }
}

/**
 * An Android album is a folder in Pictures. Moving changes where the files live and needs the
 * system's confirmation; the files themselves stay the same.
 */
@Composable
fun AlbumDialog(count: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val name = rememberTextFieldState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    name,
                    label = { Text(stringResource(R.string.album_name)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("album-name"),
                )
                Text(
                    stringResource(R.string.album_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.text.toString()) },
                enabled = name.text.isNotBlank() && count > 0,
                modifier = Modifier.testTag("album-confirm"),
            ) {
                Text(stringResource(R.string.album_move))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
