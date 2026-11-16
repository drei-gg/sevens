@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.cleaner

import androidx.core.net.toUri
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.MatchKind
import gg.seven.cleaner.core.MediaKind
import gg.seven.cleaner.core.ReviewDecision
import gg.seven.cleaner.core.storageSummary
import gg.seven.kit.ConnectedChoice
import gg.seven.kit.EmptyState
import gg.seven.kit.SearchInput
import gg.seven.kit.SevenFormat
import gg.seven.kit.Space
import gg.seven.kit.numeric
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

// MARK: Overview

@Composable
fun Dashboard(model: CleanerModel) {
    val actions = LocalActions.current
    val media by model.media.collectAsStateWithLifecycle()
    val groups by model.groups.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val limited by model.limitedPhotos.collectAsStateWithLifecycle()
    val summary = remember(media) { storageSummary(media) }
    val pending = media.count { state.decisions[it.id] == ReviewDecision.REMOVE }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(bottom = Space.l)) {
            CleanerHero(
                label = if (media.isEmpty()) "Make room for more" else "Your accessible library",
                value = if (media.isEmpty()) "Room to breathe." else if (summary.knownBytes > 0) byteText(summary.knownBytes) else "%,d".format(media.size),
                detail =
                    when {
                        media.isEmpty() -> "Keep the memories. Find the clutter."
                        summary.knownBytes > 0 -> "Measured media, ready for a closer look."
                        else -> "Accessible items. Sizes are not measured yet."
                    },
                icon = Icons.Outlined.Layers,
                big = media.isNotEmpty(),
            )
            if (groups.isNotEmpty()) PrimaryButton("Review matches", Modifier.testTag("cleaner.review-groups")) { actions.open("groups") }
            else
                PrimaryButton(if (media.isEmpty()) "Scan library" else "Find duplicates", Modifier.testTag("cleaner.scan"), enabled = !busy) {
                    if (media.isEmpty()) actions.scanPhotos() else model.analyze()
                }
            CleanerTiles(
                { m -> CleanerStat("Matching groups", "%,d".format(groups.size), Icons.Outlined.FilterNone, m.testTag("cleaner.groups"), detail = "Compare before choosing") { actions.open("groups") } },
                { m -> CleanerStat("Pending removal", "%,d".format(pending), Icons.Outlined.Inbox, m.testTag("cleaner.pending"), detail = "Nothing deleted yet", hue = Hues.warm) { actions.open("pending") } },
            )
            if (media.isNotEmpty()) LibraryComposition(media)
            SectionRule("A place for everything")
            CleanerTiles(
                { m -> CleanerStat("Photos", "%,d".format(media.count { it.kind == MediaKind.PHOTO }), Icons.Outlined.PhotoLibrary, m, detail = "Duplicates, screenshots, keeps") { actions.switchTo(Area.Photos) } },
                { m -> CleanerStat("Videos", "%,d".format(media.count { it.kind == MediaKind.VIDEO }), Icons.Outlined.VideoLibrary, m, detail = "Start with the largest", hue = Hues.warm) { actions.switchTo(Area.Videos) } },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                QuickAction("Refresh", Icons.Outlined.Refresh, enabled = !busy) { actions.scanPhotos() }
                QuickAction("Activity", Icons.Outlined.History) { actions.open("activity") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                if (limited) CleanerNotice("Only the photos you allowed are included.", Icons.Outlined.PhotoLibrary)
                if (summary.unknownCount > 0) CleanerNotice(count(R.plurals.unmeasured, summary.unknownCount))
                CleanerNotice("Media size is not space freed on this phone. The trash and cloud backups affect local storage.")
            }
        }
    }
}

// MARK: Photos and Videos

private val photoCollections = listOf("All", "Screenshots", "Live Photos", "Bursts", "Keep", "Later")
private val videoCollections = listOf("All", "Screen recordings", "High frame rate", "Keep", "Later")

private fun visible(media: List<CleanupItem>, decisions: Map<String, ReviewDecision>, kind: MediaKind, filters: LibraryFilters): List<CleanupItem> {
    val query = filters.query
    val category = filters.category
    return media
        .filter { it.kind == kind }
        .filter { item ->
            (query.isEmpty() || item.name.contains(query, ignoreCase = true)) &&
                (category == "All" || category in item.tags ||
                    (category == "Keep" && decisions[item.id] == ReviewDecision.KEEP) ||
                    (category == "Later" && decisions[item.id] == ReviewDecision.LATER))
        }
        .let { list -> if (filters.order == "Largest") list.sortedByDescending { it.bytes ?: -1 } else list.sortedByDescending { it.date?.toEpochMilli() ?: Long.MIN_VALUE } }
}

/** The top bar's two menus on a library: sort and filter, and batch actions on the selection. */
@Composable
fun LibraryMenus(model: CleanerModel, kind: MediaKind, filters: LibraryFilters) {
    val actions = LocalActions.current
    val media by model.media.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    var sort by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { sort = true }) { Icon(Icons.Outlined.FilterList, "Sort and filter") }
        DropdownMenu(sort, onDismissRequest = { sort = false }) {
            MenuLabel("Collection")
            (if (kind == MediaKind.PHOTO) photoCollections else videoCollections).forEach { option ->
                Checked(collectionLabel(option), filters.category == option) {
                    filters.category = option
                    sort = false
                }
            }
            HorizontalDivider()
            MenuLabel("Sort")
            listOf("Newest", "Largest").forEach { option ->
                Checked(option, filters.order == option) {
                    filters.order = option
                    sort = false
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Read media library") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = {
                sort = false
                actions.scanPhotos()
            })
        }
    }
    Box {
        IconButton(onClick = { batch = true }) { Icon(Icons.Outlined.Checklist, "Batch actions") }
        DropdownMenu(batch, onDismissRequest = { batch = false }) {
            val shown = visible(media, state.decisions, kind, filters).map { it.id }.toSet()
            fun item(label: String, action: () -> Unit) =
                @Composable {
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        batch = false
                        action()
                    })
                }
            item("Select visible") { model.selected.value = shown }()
            item("Deselect all") { model.selected.value = emptySet() }()
            item("Invert visible selection") { model.selected.value = (selected - shown) + (shown - selected) }()
            HorizontalDivider()
            item("Keep selected") { model.decide(selected, ReviewDecision.KEEP) }()
            item("Decide later") { model.decide(selected, ReviewDecision.LATER) }()
            item("Queue removal") { model.decide(selected, ReviewDecision.REMOVE) }()
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, Modifier.padding(horizontal = Space.m, vertical = Space.s), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Checked(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { if (checked) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) },
        modifier = Modifier.semantics { stateDescription = if (checked) "Selected" else "Not selected" },
    )
}

@Composable
fun MediaLibrary(model: CleanerModel, kind: MediaKind, filters: LibraryFilters) {
    val actions = LocalActions.current
    val media by model.media.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val library = media.filter { it.kind == kind }
    val shown = visible(media, state.decisions, kind, filters)
    val photo = kind == MediaKind.PHOTO
    val hue = if (photo) Hues.cool else Hues.warm
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            GridCells.Fixed(if (largeText()) 1 else 2),
            Modifier.widthIn(max = 672.dp).fillMaxSize().testTag("library-$kind"),
            contentPadding = PaddingValues(start = Space.m, end = Space.m, bottom = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.ms),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    val known = storageSummary(library).knownBytes
                    CleanerHero(
                        label = if (photo) "Your photo library" else "The bigger picture",
                        value = if (!photo && known > 0) byteText(known) else "%,d".format(library.size),
                        detail = if (photo) "A little less clutter. The moments stay yours." else "Review the clips taking up room.",
                        icon = if (photo) Icons.Outlined.PhotoLibrary else Icons.Outlined.VideoLibrary,
                        hue = hue,
                    )
                    PrimaryButton(if (library.isEmpty()) "Scan library" else "Find duplicates", enabled = !busy) {
                        if (library.isEmpty()) actions.scanPhotos() else model.analyze()
                    }
                    if (library.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            QuickAction("Compare groups", Icons.Outlined.FilterNone) { actions.open("groups") }
                            if (photo) QuickAction("Similar shots", Icons.Outlined.ImageSearch, enabled = !busy) { model.findSimilar() }
                            else QuickAction("Largest first", Icons.Outlined.ArrowDownward) { filters.order = "Largest" }
                        }
                        val tiles = if (photo) listOf("Screenshots" to Icons.Outlined.Screenshot, "Live Photos" to Icons.Outlined.MotionPhotosOn)
                            else listOf("Screen recordings" to Icons.Outlined.ScreenshotMonitor, "High frame rate" to Icons.Outlined.SlowMotionVideo)
                        CleanerTiles(*tiles.map { (title, icon) ->
                            @Composable { m: Modifier ->
                                CleanerStat(
                                    collectionLabel(title),
                                    "%,d".format(library.count { title in it.tags }),
                                    icon,
                                    m,
                                    detail = if (filters.category == title) "Showing this collection" else null,
                                    hue = hue,
                                    selected = filters.category == title,
                                ) { filters.category = if (filters.category == title) "All" else title }
                            }
                        }.toTypedArray())
                    }
                    if (selected.isNotEmpty()) SelectionCard(model, selected)
                    if (library.isEmpty())
                        CleanerNotice("Choose which photos and videos Cleaner can see when you scan. Originals stay in your library.", Icons.Outlined.PhotoLibrary)
                    else {
                        SearchInput("Search ${if (photo) "photos" else "videos"}", state = filters.search)
                        SectionRule(if (filters.category == "All") "Browse your library" else collectionLabel(filters.category)) {
                            Text("%,d".format(shown.size), style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (shown.isEmpty())
                            EmptyState(
                                if (filters.query.isEmpty()) "Nothing in this collection" else "No results for “${filters.query}”",
                                "Try another collection or search.",
                                icon = Icons.Outlined.Search,
                            )
                    }
                }
            }
            items(shown, key = { it.id }) { item -> MediaTile(model, item) }
        }
    }
}

@Composable
private fun SelectionCard(model: CleanerModel, selected: Set<String>) {
    val actions = LocalActions.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.ms), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count(R.plurals.selected, selected.size), style = MaterialTheme.typography.titleMedium.numeric())
            DecisionButtons { model.decide(selected, it) }
            TextButton(onClick = { actions.open("pending") }) { Text("Review pending removal") }
        }
    }
}

/** Keep, Later and Queue removal, as one connected group. */
@Composable
fun DecisionButtons(decide: (ReviewDecision) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedButton(onClick = { decide(ReviewDecision.KEEP) }) { Text("Keep") }
        OutlinedButton(onClick = { decide(ReviewDecision.LATER) }) { Text("Later") }
        OutlinedButton(
            onClick = { decide(ReviewDecision.REMOVE) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Queue removal")
        }
    }
}

private fun decisionLabel(decision: ReviewDecision) =
    when (decision) {
        ReviewDecision.KEEP -> "Keep"
        ReviewDecision.LATER -> "Later"
        ReviewDecision.REMOVE -> "Remove"
        ReviewDecision.UNDECIDED -> ""
    }

/** A media card: thumbnail, name, size, its decision or favorite, and its selection box. */
@Composable
fun MediaTile(model: CleanerModel, item: CleanupItem, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val state by model.state.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val isSelected = item.id in selected
    Card(modifier.fillMaxWidth().testTag("tile-${item.name}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(Space.ms), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Column(
                Modifier.combinedClickable(onClickLabel = "Review", onLongClickLabel = "Select", onLongClick = { model.toggle(item.id) }) {
                    actions.open("detail:${item.id}")
                },
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Thumbnail(item, Modifier.fillMaxWidth().aspectRatio(1.2f), pixels = 360)
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(byteText(item.bytes), style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val decision = state.decisions[item.id]
                if (item.favorite)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Favorite, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Favorite", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                else if (decision != null && decision != ReviewDecision.UNDECIDED)
                    Text(decisionLabel(decision), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { model.toggle(item.id) },
                    modifier = Modifier.semantics { contentDescription = "Select ${item.name}" },
                )
            }
        }
    }
}

/** A list row for a media item, with its thumbnail. */
@Composable
fun MediaRow(item: CleanupItem, trailing: @Composable () -> Unit = {}) {
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(byteText(item.bytes), style = MaterialTheme.typography.bodyMedium.numeric())
                if (item.favorite)
                    Icon(Icons.Outlined.Favorite, "Favorite; protected from batch removal", Modifier.size(16.dp))
            }
        },
        leadingContent = { Thumbnail(item, Modifier.size(56.dp), pixels = 160) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clip(MaterialTheme.shapes.medium),
    )
}

// MARK: Groups, pending, activity

@Composable
fun GroupsScreen(model: CleanerModel) {
    val actions = LocalActions.current
    val groups by model.groups.collectAsStateWithLifecycle()
    val media by model.media.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        item {
            Readable {
                CleanerHero("A closer look", "%,d".format(groups.size), "Matching groups. You choose what stays.", Icons.Outlined.FilterNone)
                PrimaryButton("Select suggested exact copies", enabled = groups.isNotEmpty()) { model.smartSelect() }
                if (selected.isNotEmpty())
                    FilledTonalButton(onClick = { model.decide(selected, ReviewDecision.REMOVE) }, Modifier.fillMaxWidth().testTag("cleaner.queue-selection")) {
                        Text(count(R.plurals.queue, selected.size))
                    }
                TextButton(onClick = { actions.open("pending") }) { Text("Review pending removal") }
            }
        }
        items(groups, key = { it.id }) { group ->
            var why by rememberSaveable(group.id) { mutableStateOf(false) }
            Readable(Modifier.padding(top = Space.l)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                    SectionRule(if (group.kind == MatchKind.IDENTICAL_RESOURCES) "Identical files" else "Similar moments")
                    val members = group.members.mapNotNull { id -> media.firstOrNull { it.id == id } }
                    CleanerTiles(*members.map { member -> @Composable { m: Modifier -> MediaTile(model, member, m) } }.toTypedArray())
                    TextButton(onClick = { why = !why }) {
                        Text("Why these match")
                        Icon(if (why) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                    if (why) Text(group.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Readable(Modifier.padding(top = Space.l)) {
                CleanerNotice("Similar photos can show different moments. Matching bytes can still have different dates or folders.")
                if (groups.isEmpty())
                    EmptyState("No groups yet", "Run an exact or similar scan from Photos or Videos.", icon = Icons.Outlined.FilterNone)
            }
        }
    }
}

@Composable
fun PendingScreen(model: CleanerModel) {
    val media by model.media.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val items = media.filter { state.decisions[it.id] == ReviewDecision.REMOVE }
    var approved by remember { mutableStateOf<Set<String>?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        item {
            Readable {
                CleanerHero("Nothing deleted yet", "%,d".format(items.size), "Items queued for your final review.", Icons.Outlined.Inbox, hue = Hues.warm)
            }
        }
        items(items, key = { it.id }) { item ->
            Readable(Modifier.padding(bottom = Space.s)) {
                MediaRow(item) { TextButton(onClick = { model.decide(setOf(item.id), ReviewDecision.UNDECIDED) }) { Text("Undo") } }
            }
        }
        item {
            Readable(Modifier.padding(top = Space.m)) {
                CleanerNotice("Deleted items go to the trash, where Android keeps them for 30 days and they keep using storage. A cloud backup may keep its own copy. Favorites are protected.")
                OutlinedButton(
                    onClick = { approved = items.map { it.id }.toSet() },
                    enabled = items.isNotEmpty() && !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("cleaner.delete"),
                ) {
                    Text("Delete reviewed items…")
                }
                if (Build.VERSION.SDK_INT < 30) CleanerNotice("Moving media to the trash needs Android 11 or later.", caution = true)
            }
        }
    }
    approved?.let { ids ->
        AlertDialog(
            onDismissRequest = { approved = null },
            title = { Text(count(R.plurals.delete, ids.size)) },
            text = { Text("Android asks you to confirm next. You can restore them from the trash for 30 days.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        approved = null
                        model.deleteReviewed(ids)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Move to trash")
                }
            },
            dismissButton = { TextButton(onClick = { approved = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ActivityScreen(model: CleanerModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        if (state.history.isEmpty())
            item { EmptyState("Your review history", "Completed scans and actions appear here.", icon = Icons.Outlined.History) }
        items(state.history, key = { it.id.toString() }) { entry ->
            Readable(Modifier.padding(bottom = Space.s)) {
                ListItem(
                    headlineContent = { Text(entry.message) },
                    supportingContent = { Text(SevenFormat.date(entry.date.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall.numeric()) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clip(MaterialTheme.shapes.medium),
                )
            }
        }
    }
}

// MARK: Review one item

@Composable
fun MediaDetail(model: CleanerModel, id: String) {
    val media by model.media.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val item = media.firstOrNull { it.id == id }
    if (item == null) {
        EmptyState("Not in the library any more", "This item was removed or changed since the scan.", icon = Icons.Outlined.ImageNotSupported)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(bottom = Space.l)) {
            if (item.kind == MediaKind.VIDEO) VideoPreview(item)
            else {
                val photos = LocalPhotos.current
                val bitmap by produceState<Bitmap?>(null, item.id) { value = withContext(Dispatchers.IO) { photos?.previewBitmap(item.id.toUri()) } }
                val image = bitmap
                if (image != null) ZoomImage(image, "Photo ${item.name}", Modifier.fillMaxWidth().height(360.dp))
                else EmptyState("Preview unavailable", "The original can't be read right now.", icon = Icons.Outlined.Image)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge)
                Text("${"%,d".format(item.width)} × ${"%,d".format(item.height)} · ${byteText(item.bytes)}", style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.date?.let { Text(SevenFormat.date(it.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (item.kind == MediaKind.VIDEO) {
                    val seconds = item.duration.toLong()
                    Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DecisionButtons { model.decide(setOf(item.id), it) }
            if (item.kind == MediaKind.PHOTO) {
                PrimaryButton("Inspect compression", enabled = !busy) { model.compress(item) }
                Text("Original retained. Inspect the actual output before saving a copy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (Build.VERSION.SDK_INT >= 30)
                TextButton(onClick = { model.setFavorite(item) }, enabled = !busy) {
                    Icon(if (item.favorite) Icons.Outlined.HeartBroken else Icons.Outlined.FavoriteBorder, null)
                    Spacer(Modifier.width(Space.s))
                    Text(if (item.favorite) "Remove favorite" else "Mark favorite")
                }
        }
    }
}

/** Pinch and double-tap zoom over a still (iOS ZoomImage), up to 12×. */
@Composable
fun ZoomImage(bitmap: Bitmap, description: String, modifier: Modifier = Modifier) {
    val zoom = rememberZoomableState(me.saket.telephoto.zoomable.ZoomSpec(maxZoomFactor = 12f))
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(modifier.clip(MaterialTheme.shapes.medium).zoomable(zoom)) {
        Image(image, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** The platform video view with its transport controls, and a playback-speed choice. */
@Composable
private fun VideoPreview(item: CleanupItem) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var rate by rememberSaveable { mutableStateOf(1f) }
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnPreparedListener { player = it }
                setVideoURI(item.id.toUri())
                contentDescription = "Video ${item.name}"
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).clip(MaterialTheme.shapes.medium),
        onRelease = { it.stopPlayback() },
    )
    ConnectedChoice(listOf(0.5f to "0.5×", 1f to "1×", 1.5f to "1.5×", 2f to "2×"), rate) { value ->
        rate = value
        player?.let { runCatching { it.playbackParams = it.playbackParams.setSpeed(value) } }
    }
}
