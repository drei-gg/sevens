package gg.seven.cleaner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.storageSummary
import gg.seven.kit.EmptyState
import gg.seven.kit.SearchInput
import gg.seven.kit.Space
import gg.seven.kit.numeric

/** Large files and exact copies in a folder the person picks (iOS FilesScreen). Read-only. */
@Composable
fun FilesScreen(model: CleanerModel) {
    val actions = LocalActions.current
    val scan by model.folderScan.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var duplicatesOnly by rememberSaveable { mutableStateOf(false) }
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val current = scan
    val duplicates = current?.groups?.flatMap { it.members }?.toSet().orEmpty()
    val items = current?.items.orEmpty().filter { (!duplicatesOnly || it.id in duplicates) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
    LazyColumn(Modifier.fillMaxSize().testTag("files"), contentPadding = PaddingValues(bottom = Space.l)) {
        item {
            Readable {
                val summary = current?.let { storageSummary(it.items) }
                val unmeasured = summary != null && summary.knownBytes == 0L && summary.unknownCount > 0
                CleanerHero(
                    "Your chosen folders",
                    when {
                        summary == null -> "Find the big stuff."
                        unmeasured -> "Not measured"
                        else -> byteText(summary.knownBytes)
                    },
                    "Large files and exact copies, in the places you choose.",
                    Icons.Outlined.Folder,
                    hue = Hues.warm,
                    big = summary != null && !unmeasured,
                )
                PrimaryButton("Choose a folder", enabled = !busy) { actions.chooseFolder() }
                if (current != null) {
                    CleanerTiles(
                        { m -> CleanerStat("Files found", "%,d".format(current.items.size), Icons.Outlined.Description, m, hue = Hues.warm) },
                        { m ->
                            CleanerStat(
                                "Duplicate groups",
                                "%,d".format(current.groups.size),
                                Icons.Outlined.FileCopy,
                                m,
                                detail = if (duplicatesOnly) "Showing duplicates" else "Tap to inspect",
                                hue = Hues.warm,
                                selected = duplicatesOnly,
                            ) { duplicatesOnly = !duplicatesOnly }
                        },
                    )
                    FileSizeChart(current.items)
                    SearchInput("Search files", state = search)
                    SectionRule(if (duplicatesOnly) "Exact copies" else "All files") {
                        if (duplicatesOnly) TextButton(onClick = { duplicatesOnly = false }) { Text("Show all") }
                    }
                    if (items.isEmpty())
                        EmptyState(if (query.isEmpty()) "No files here" else "No results for “$query”", "Try another folder or search.", icon = Icons.Outlined.Search)
                }
            }
        }
        if (current != null)
            items(items, key = { it.id }) { item ->
                Readable(Modifier.padding(top = Space.s)) {
                    ListItem(
                        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text(byteText(item.bytes), style = MaterialTheme.typography.bodyMedium.numeric())
                                if (item.id in duplicates) Text("Exact duplicate candidate", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leadingContent = { Icon(fileIcon(item.name), null, tint = Hues.warm) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clip(MaterialTheme.shapes.medium),
                    )
                }
            }
        item {
            Readable(Modifier.padding(top = Space.l)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                    if (current != null) {
                        val unknown = storageSummary(current.items).unknownCount
                        if (unknown > 0) CleanerNotice(count(R.plurals.unmeasured, unknown))
                        if (current.skipped > 0) CleanerNotice(count(R.plurals.unreadable, current.skipped))
                        if (current.unhashed) CleanerNotice("This folder is on a cloud or app provider, so files were listed but not read. Exact copies are found only in this phone’s own storage.")
                    }
                    CleanerNotice("Only folders you choose are scanned. Other apps’ private data and system files are outside Cleaner’s access.", Icons.Outlined.FolderShared)
                    CleanerNotice("Cloud files are not downloaded for hashing. File providers may behave differently.")
                }
            }
        }
    }
}

private fun fileIcon(name: String): ImageVector =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mov", "mp4", "mkv", "webm", "3gp" -> Icons.Outlined.Movie
        "zip", "7z", "rar", "gz", "tar" -> Icons.Outlined.FolderZip
        "pdf" -> Icons.Outlined.PictureAsPdf
        else -> Icons.Outlined.Description
    }
