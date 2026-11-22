package gg.seven.cleaner

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import gg.seven.cleaner.core.FileSizeFilter
import gg.seven.cleaner.core.MediaDurationFilter
import gg.seven.cleaner.core.MediaKind
import gg.seven.cleaner.core.MediaResolutionFilter
import gg.seven.kit.DropdownField
import gg.seven.kit.FullScreenForm
import gg.seven.kit.Space

internal val MediaResolutionFilter.title: String
    get() =
        when (this) {
            MediaResolutionFilter.ANY -> "Any dimensions"
            MediaResolutionFilter.AT_LEAST2160 -> "4K or larger (2160 px short side)"
            MediaResolutionFilter.AT_LEAST1080 -> "At least 1080 px short side"
            MediaResolutionFilter.AT_LEAST720 -> "At least 720 px short side"
            MediaResolutionFilter.BELOW720 -> "Under 720 px short side"
            MediaResolutionFilter.UNKNOWN -> "Dimensions unknown"
        }

internal val MediaDurationFilter.title: String
    get() =
        when (this) {
            MediaDurationFilter.ANY -> "Any length"
            MediaDurationFilter.UNDER1_MINUTE -> "Under 1 minute"
            MediaDurationFilter.AT_LEAST1_MINUTE -> "At least 1 minute"
            MediaDurationFilter.AT_LEAST5_MINUTES -> "At least 5 minutes"
            MediaDurationFilter.AT_LEAST30_MINUTES -> "At least 30 minutes"
            MediaDurationFilter.UNKNOWN -> "Length unknown"
        }

/** The active size, dimension and length filters as removable chips (label, clear). */
internal fun measureChips(
    filters: LibraryFilters,
    kind: MediaKind,
): List<Pair<String, () -> Unit>> =
    buildList {
        if (filters.size != FileSizeFilter.ANY)
            add(filters.size.title to { filters.size = FileSizeFilter.ANY })
        if (filters.resolution != MediaResolutionFilter.ANY)
            add(filters.resolution.title to { filters.resolution = MediaResolutionFilter.ANY })
        if (kind == MediaKind.VIDEO && filters.duration != MediaDurationFilter.ANY)
            add(filters.duration.title to { filters.duration = MediaDurationFilter.ANY })
    }

/** Size, dimensions and (for videos) length: a draft applied together, like the date filter. */
@Composable
fun MediaMeasureEditor(filters: LibraryFilters, kind: MediaKind, onClose: () -> Unit) {
    var size by rememberSaveable { mutableStateOf(filters.size) }
    var resolution by rememberSaveable { mutableStateOf(filters.resolution) }
    var duration by rememberSaveable { mutableStateOf(filters.duration) }
    val video = kind == MediaKind.VIDEO
    FullScreenForm(
        if (video) "Size, resolution and length" else "Size and dimensions",
        onClose,
        onSave = {
            filters.size = size
            filters.resolution = resolution
            filters.duration = duration
            onClose()
        },
        saveLabel = "Apply",
    ) {
        DropdownField(
            "Size",
            size,
            FileSizeFilter.entries.map { it to it.title },
            Modifier.testTag("media-size"),
        ) {
            size = it
        }
        DropdownField(
            if (video) "Resolution" else "Dimensions",
            resolution,
            MediaResolutionFilter.entries.map { it to it.title },
            Modifier.testTag("media-resolution"),
        ) {
            resolution = it
        }
        if (video)
            DropdownField(
                "Length",
                duration,
                MediaDurationFilter.entries.map { it to it.title },
                Modifier.testTag("media-duration"),
            ) {
                duration = it
            }
        Text(
            "Sizes come from the library or from Find duplicates. Items with an unknown size, dimension or length match only the matching Unknown choice, never a smaller size. 1 MB is 1,000,000 bytes.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                size = FileSizeFilter.ANY
                resolution = MediaResolutionFilter.ANY
                duration = MediaDurationFilter.ANY
            },
            modifier = Modifier.testTag("media-clear-draft"),
        ) {
            Text("Clear these filters")
        }
    }
}

/**
 * An Android album is a folder in Pictures. Moving changes where the files live and needs the
 * system's confirmation (Android 11+); copying leaves the originals in place and uses more storage.
 */
@Composable
fun AlbumDialog(count: Int, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    val name = rememberTextFieldState()
    val canMove = Build.VERSION.SDK_INT >= 30
    var move by rememberSaveable { mutableStateOf(canMove) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    name,
                    label = { Text("Album name") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("album-name"),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    FilterChip(
                        selected = move,
                        onClick = { move = true },
                        enabled = canMove,
                        label = { Text("Move") },
                        modifier = Modifier.testTag("album-move"),
                    )
                    FilterChip(
                        selected = !move,
                        onClick = { move = false },
                        label = { Text("Copy") },
                        modifier = Modifier.testTag("album-copy"),
                    )
                }
                Text(
                    when {
                        !canMove ->
                            "Moving needs Android 11 or later, so Cleaner copies them into Pictures/<album>. The originals stay where they are and the copies use more storage."
                        move ->
                            "Android albums are folders. Cleaner moves the selected items into Pictures/<album> after Android asks you to confirm. Their files stay the same; only the folder changes."
                        else ->
                            "Cleaner copies the selected items into Pictures/<album> and checks each copy. The originals stay where they are and the copies use more storage."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.text.toString(), move && canMove) },
                enabled = name.text.isNotBlank() && count > 0,
                modifier = Modifier.testTag("album-confirm"),
            ) {
                Text(if (move && canMove) "Move" else "Copy")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
