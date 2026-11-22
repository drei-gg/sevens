@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.cleaner

import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.MatchKind
import gg.seven.cleaner.core.MediaDateFilter
import gg.seven.cleaner.core.MediaFilters
import gg.seven.cleaner.core.MediaKind
import gg.seven.cleaner.core.RemovalStatus
import gg.seven.cleaner.core.ReviewDecision
import gg.seven.cleaner.core.keeperSuggestion
import gg.seven.cleaner.core.mediaFiltersMatch
import gg.seven.cleaner.core.largeItems
import gg.seven.cleaner.core.removalOutcomes
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
    val savedMeasurements by model.savedMeasurements.collectAsStateWithLifecycle()
    val limited by model.limitedPhotos.collectAsStateWithLifecycle()
    val summary = remember(media) { storageSummary(media) }
    val pending = media.count { state.decisions[it.id] == ReviewDecision.REMOVE }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(bottom = Space.l)) {
            CleanerHero(
                label =
                    stringResource(
                        if (media.isEmpty()) R.string.media_hero_empty_label
                        else R.string.media_hero_label
                    ),
                value =
                    if (media.isEmpty()) stringResource(R.string.media_hero_empty_value)
                    else if (summary.knownBytes > 0) byteText(summary.knownBytes)
                    else "%,d".format(media.size),
                detail =
                    when {
                        media.isEmpty() -> stringResource(R.string.media_hero_empty_detail)
                        summary.knownBytes > 0 -> stringResource(R.string.media_hero_measured_detail)
                        else -> stringResource(R.string.media_hero_unmeasured_detail)
                    },
                icon = Icons.Outlined.Layers,
                big = media.isNotEmpty(),
            )
            if (groups.isNotEmpty())
                PrimaryButton(
                    stringResource(R.string.media_review_matches),
                    Modifier.testTag("cleaner.review-groups"),
                ) {
                    actions.open("groups")
                }
            else
                PrimaryButton(
                    stringResource(
                        if (media.isEmpty()) R.string.media_scan_library
                        else if (savedMeasurements > 0) R.string.media_continue_comparison
                        else R.string.media_find_duplicates
                    ),
                    Modifier.testTag("cleaner.scan"),
                    enabled = !busy,
                ) {
                    if (media.isEmpty()) actions.scanPhotos() else model.analyze()
                }
            if (savedMeasurements > 0)
                CleanerNotice(
                    stringResource(R.string.media_saved_measurements_notice, savedMeasurements)
                )
            CleanerTiles(
                { m ->
                    CleanerStat(
                        stringResource(R.string.media_matching_groups),
                        "%,d".format(groups.size),
                        Icons.Outlined.FilterNone,
                        m.testTag("cleaner.groups"),
                        detail = stringResource(R.string.media_matching_groups_detail),
                    ) {
                        actions.open("groups")
                    }
                },
                { m ->
                    CleanerStat(
                        stringResource(R.string.media_pending_removal),
                        "%,d".format(pending),
                        Icons.Outlined.Inbox,
                        m.testTag("cleaner.pending"),
                        detail = stringResource(R.string.media_pending_removal_detail),
                        hue = Hues.warm,
                    ) {
                        actions.open("pending")
                    }
                },
            )
            if (media.isNotEmpty()) LibraryComposition(media)
            SectionRule(stringResource(R.string.media_section_places))
            CleanerTiles(
                { m ->
                    CleanerStat(
                        stringResource(R.string.media_photos),
                        "%,d".format(media.count { it.kind == MediaKind.PHOTO }),
                        Icons.Outlined.PhotoLibrary,
                        m,
                        detail = stringResource(R.string.media_photos_detail),
                    ) {
                        actions.switchTo(Area.Photos)
                    }
                },
                { m ->
                    CleanerStat(
                        stringResource(R.string.media_videos),
                        "%,d".format(media.count { it.kind == MediaKind.VIDEO }),
                        Icons.Outlined.VideoLibrary,
                        m,
                        detail = stringResource(R.string.media_videos_detail),
                        hue = Hues.warm,
                    ) {
                        actions.switchTo(Area.Videos)
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                QuickAction(
                    stringResource(R.string.media_refresh),
                    Icons.Outlined.Refresh,
                    enabled = !busy,
                ) {
                    actions.scanPhotos()
                }
                QuickAction(stringResource(R.string.media_activity), Icons.Outlined.History) {
                    actions.open("activity")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                if (limited)
                    CleanerNotice(
                        stringResource(R.string.media_limited_notice),
                        Icons.Outlined.PhotoLibrary,
                    )
                if (summary.unknownCount > 0)
                    CleanerNotice(count(R.plurals.unmeasured, summary.unknownCount))
                CleanerNotice(stringResource(R.string.media_size_notice))
            }
        }
    }
}

// MARK: Photos and Videos

private val photoCollections =
    listOf("All", "Large", "Screenshots", "Live Photos", "Bursts", "Keep", "Later")
private val videoCollections =
    listOf("All", "Large", "Screen recordings", "High frame rate", "Keep", "Later")

private fun visible(
    media: List<CleanupItem>,
    decisions: Map<String, ReviewDecision>,
    kind: MediaKind,
    filters: LibraryFilters,
): List<CleanupItem> {
    val query = filters.query
    val category = filters.category
    return media
        .filter { it.kind == kind }
        .filter { item ->
            matchesDate(item.date, filters.dateMode, filters.date) &&
                mediaFiltersMatch(
                    item,
                    MediaFilters(filters.size, filters.resolution, filters.duration),
                ) &&
                (query.isEmpty() || item.name.contains(query, ignoreCase = true)) &&
                (category == "All" ||
                    category == "Large" ||
                    category in item.tags ||
                    (category == "Keep" && decisions[item.id] == ReviewDecision.KEEP) ||
                    (category == "Later" && decisions[item.id] == ReviewDecision.LATER))
        }
        .let { list ->
            // Large is the core's: known sizes from the chosen minimum, largest first.
            if (category == "Large") largeItems(list, filters.largeFrom(kind))
            else if (filters.order == "Largest") list.sortedByDescending { it.bytes ?: -1 }
            else list.sortedByDescending { it.date?.toEpochMilli() ?: Long.MIN_VALUE }
        }
}

/** The top bar's two menus on a library: sort and filter, and batch actions on the selection. */
@Composable
fun LibraryMenus(model: CleanerModel, kind: MediaKind, filters: LibraryFilters) {
    val actions = LocalActions.current
    val media by model.media.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val allSelected by model.selected.collectAsStateWithLifecycle()
    val selected = allSelected.intersect(media.filter { it.kind == kind }.map { it.id }.toSet())
    var dateEditor by rememberSaveable { mutableStateOf(false) }
    if (dateEditor) DateFilterEditor(filters) { dateEditor = false }
    var measureEditor by rememberSaveable { mutableStateOf(false) }
    if (measureEditor) MediaMeasureEditor(filters, kind) { measureEditor = false }
    var album by rememberSaveable { mutableStateOf(false) }
    if (album) AlbumDialog(selected.size, onDismiss = { album = false }) { title ->
        album = false
        model.addToAlbum(selected, title)
    }
    var sort by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { sort = true }) {
            Icon(Icons.Outlined.FilterList, stringResource(R.string.media_sort_and_filter))
        }
        DropdownMenu(sort, onDismissRequest = { sort = false }) {
            MenuLabel(stringResource(R.string.media_menu_collection))
            (if (kind == MediaKind.PHOTO) photoCollections else videoCollections).forEach { option
                ->
                Checked(collectionLabel(option), filters.category == option) {
                    filters.category = option
                    sort = false
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.media_recorded_date)) },
                onClick = {
                    sort = false
                    dateEditor = true
                },
                modifier = Modifier.testTag("cleaner-date-open"),
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (kind == MediaKind.VIDEO) R.string.media_measure_video
                            else R.string.media_measure_photo
                        )
                    )
                },
                onClick = {
                    sort = false
                    measureEditor = true
                },
                modifier = Modifier.testTag("cleaner-measure-open"),
            )
            MenuLabel(stringResource(R.string.media_menu_sort))
            // The order keys stay English; only their labels are translated.
            listOf(
                    "Newest" to R.string.media_sort_newest,
                    "Largest" to R.string.media_sort_largest,
                )
                .forEach { (option, label) ->
                Checked(stringResource(label), filters.order == option) {
                    filters.order = option
                    sort = false
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.media_read_library)) },
                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                onClick = {
                    sort = false
                    actions.scanPhotos()
                },
            )
        }
    }
    Box {
        IconButton(onClick = { batch = true }) {
            Icon(Icons.Outlined.Checklist, stringResource(R.string.media_batch_actions))
        }
        DropdownMenu(batch, onDismissRequest = { batch = false }) {
            val shown = visible(media, state.decisions, kind, filters).map { it.id }.toSet()
            if (kind == MediaKind.PHOTO) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.media_review_selected_photos)) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.testTag("compression-batch-open"),
                    onClick = {
                        batch = false
                        model.startCompressionBatch(selected)
                    },
                )
                HorizontalDivider()
            }

            @Composable
            fun item(label: String, enabled: Boolean = true, action: () -> Unit) =
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = enabled,
                    onClick = {
                        batch = false
                        action()
                    },
                )
            item(stringResource(R.string.media_select_visible)) { model.selected.value = allSelected + shown }
            item(stringResource(R.string.media_clear_selection), selected.isNotEmpty()) {
                model.selected.value = allSelected - selected
            }
            item(stringResource(R.string.media_invert_selection)) {
                model.selected.value = (allSelected - shown) + (shown - selected)
            }
            HorizontalDivider()
            item(stringResource(R.string.media_keep_selected), selected.isNotEmpty()) {
                model.decide(selected, ReviewDecision.KEEP)
            }
            item(stringResource(R.string.media_decide_later), selected.isNotEmpty()) {
                model.decide(selected, ReviewDecision.LATER)
            }
            item(stringResource(R.string.media_queue_removal), selected.isNotEmpty()) {
                model.decide(selected, ReviewDecision.REMOVE)
            }
            HorizontalDivider()
            item(stringResource(R.string.media_add_to_album), selected.isNotEmpty()) {
                album = true
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = Space.m, vertical = Space.s),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Checked(label: String, checked: Boolean, onClick: () -> Unit) {
    val state =
        stringResource(if (checked) R.string.media_state_selected else R.string.media_state_not_selected)
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            if (checked) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp))
        },
        modifier =
            Modifier.semantics { stateDescription = state },
    )
}

@Composable
fun MediaLibrary(model: CleanerModel, kind: MediaKind, filters: LibraryFilters) {
    val actions = LocalActions.current
    val media by model.media.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val allSelected by model.selected.collectAsStateWithLifecycle()
    val selected = allSelected.intersect(media.filter { it.kind == kind }.map { it.id }.toSet())
    BackHandler(selected.isNotEmpty()) { model.selected.value = allSelected - selected }
    val busy by model.busy.collectAsStateWithLifecycle()
    val savedMeasurements by model.savedMeasurements.collectAsStateWithLifecycle()
    val library = media.filter { it.kind == kind }
    val shown = visible(media, state.decisions, kind, filters)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var actionBarHeight by remember { mutableIntStateOf(0) }
    val photo = kind == MediaKind.PHOTO
    val hue = if (photo) Hues.cool else Hues.warm
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            GridCells.Fixed(if (largeText()) 1 else 2),
            Modifier.widthIn(max = 672.dp)
                .fillMaxSize()
                // Reserve the actual viewport so focus/scroll-to cannot land behind the bar.
                .padding(
                    bottom =
                        if (selected.isEmpty()) 0.dp else with(density) { actionBarHeight.toDp() }
                )
                .testTag("library-$kind"),
            contentPadding = PaddingValues(start = Space.m, end = Space.m, bottom = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.ms),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    val known = storageSummary(library).knownBytes
                    CleanerHero(
                        label =
                            stringResource(
                                if (photo) R.string.media_photo_hero_label
                                else R.string.media_video_hero_label
                            ),
                        value =
                            if (!photo && known > 0) byteText(known)
                            else "%,d".format(library.size),
                        detail =
                            stringResource(
                                if (photo) R.string.media_photo_hero_detail
                                else R.string.media_video_hero_detail
                            ),
                        icon =
                            if (photo) Icons.Outlined.PhotoLibrary else Icons.Outlined.VideoLibrary,
                        hue = hue,
                    )
                    PrimaryButton(
                        stringResource(
                            if (library.isEmpty()) R.string.media_scan_library
                            else if (savedMeasurements > 0) R.string.media_continue_comparison
                            else R.string.media_find_duplicates
                        ),
                        enabled = !busy,
                    ) {
                        if (library.isEmpty()) actions.scanPhotos() else model.analyze()
                    }
                    if (library.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            QuickAction(
                                stringResource(R.string.media_compare_groups),
                                Icons.Outlined.FilterNone,
                            ) {
                                actions.open("groups")
                            }
                            if (photo)
                                QuickAction(
                                    stringResource(R.string.media_similar_shots),
                                    Icons.Outlined.ImageSearch,
                                    enabled = !busy,
                                ) {
                                    model.findSimilar()
                                }
                            else
                                QuickAction(
                                    stringResource(R.string.media_largest_first),
                                    Icons.Outlined.ArrowDownward,
                                ) {
                                    filters.order = "Largest"
                                }
                        }
                        val tiles =
                            if (photo)
                                listOf(
                                    "Screenshots" to Icons.Outlined.Screenshot,
                                    "Live Photos" to Icons.Outlined.MotionPhotosOn,
                                )
                            else
                                listOf(
                                    "Screen recordings" to Icons.Outlined.ScreenshotMonitor,
                                    "High frame rate" to Icons.Outlined.SlowMotionVideo,
                                )
                        CleanerTiles(
                            *tiles
                                .map { (title, icon) ->
                                    @Composable { m: Modifier ->
                                        CleanerStat(
                                            collectionLabel(title),
                                            "%,d".format(library.count { title in it.tags }),
                                            icon,
                                            m,
                                            detail =
                                                if (filters.category == title)
                                                    stringResource(R.string.media_showing_collection)
                                                else null,
                                            hue = hue,
                                            selected = filters.category == title,
                                        ) {
                                            filters.category =
                                                if (filters.category == title) "All" else title
                                        }
                                    }
                                }
                                .toTypedArray()
                        )
                    }
                    if (library.isEmpty())
                        CleanerNotice(
                            stringResource(R.string.media_library_empty_notice),
                            Icons.Outlined.PhotoLibrary,
                        )
                    else {
                        SearchInput(
                            stringResource(
                                if (photo) R.string.media_search_photos else R.string.media_search_videos
                            ),
                            state = filters.search,
                        )
                        val clearDate = stringResource(R.string.media_clear_date_filter)
                        val clearCollection = stringResource(R.string.media_clear_collection_filter)
                        val resetSort = stringResource(R.string.media_reset_sort)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            if (filters.dateMode != MediaDateFilter.ANY)
                                FilterChip(
                                    selected = true,
                                    onClick = { filters.dateMode = MediaDateFilter.ANY },
                                    label = { Text(dateSummary(filters.dateMode, filters.date)) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, null) },
                                    modifier =
                                        Modifier.testTag("cleaner-date-active").semantics {
                                            contentDescription = clearDate
                                        },
                                )
                            if (filters.category != "All")
                                FilterChip(
                                    selected = true,
                                    onClick = { filters.category = "All" },
                                    label = { Text(collectionLabel(filters.category)) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, null) },
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription = clearCollection
                                        },
                                )
                            measureChips(filters, kind).forEach { (label, clear) ->
                                val description = stringResource(R.string.media_clear_filter, label)
                                FilterChip(
                                    selected = true,
                                    onClick = clear,
                                    label = { Text(label) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, null) },
                                    modifier =
                                        Modifier.semantics { contentDescription = description },
                                )
                            }
                            if (filters.order != "Newest")
                                FilterChip(
                                    selected = true,
                                    onClick = { filters.order = "Newest" },
                                    label = { Text(stringResource(R.string.media_largest_first)) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, null) },
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription = resetSort
                                        },
                                )
                        }
                        SectionRule(
                            if (filters.category == "All") stringResource(R.string.media_browse_library)
                            else collectionLabel(filters.category)
                        ) {
                            Text(
                                "%,d".format(shown.size),
                                style = MaterialTheme.typography.bodySmall.numeric(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (filters.category == "Large")
                            Text(
                                stringResource(R.string.media_large_note, byteText(filters.largeFrom(kind))),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        if (shown.isEmpty())
                            EmptyState(
                                if (filters.query.isEmpty()) stringResource(R.string.media_empty_collection)
                                else stringResource(R.string.media_no_results, filters.query),
                                stringResource(R.string.media_empty_hint),
                                icon = Icons.Outlined.Search,
                            )
                    }
                }
            }
            items(shown, key = { it.id }) { item -> MediaTile(model, item) }
        }
        if (selected.isNotEmpty())
            Surface(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { actionBarHeight = it.height }
                        .padding(Space.s),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = Space.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { model.selected.value = allSelected - selected }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.media_clear_selection_short))
                        }
                        Text(
                            count(R.plurals.selected, selected.size),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(
                            onClick = {
                                model.selected.value =
                                    (allSelected - library.map { it.id }.toSet()) +
                                        shown.map { it.id }.toSet()
                            }
                        ) {
                            Text(stringResource(R.string.media_select_visible))
                        }
                    }
                    DecisionButtons(enabled = !busy) { model.decide(selected, it) }
                }
            }
    }
}

/** Keep, Later and Queue removal, as one connected group. */
@Composable
fun DecisionButtons(
    selected: ReviewDecision? = null,
    enabled: Boolean = true,
    decide: (ReviewDecision) -> Unit,
) {
    if (selected != null) {
        ConnectedChoice(
            listOf(
                ReviewDecision.KEEP to stringResource(R.string.media_decision_keep),
                ReviewDecision.LATER to stringResource(R.string.media_decision_later),
                ReviewDecision.REMOVE to stringResource(R.string.media_queue_removal),
            ),
            selected,
            enabled = enabled,
            onSelect = decide,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        OutlinedButton(onClick = { decide(ReviewDecision.KEEP) }, enabled = enabled) {
            Text(stringResource(R.string.media_decision_keep))
        }
        OutlinedButton(onClick = { decide(ReviewDecision.LATER) }, enabled = enabled) {
            Text(stringResource(R.string.media_decision_later))
        }
        OutlinedButton(
            onClick = { decide(ReviewDecision.REMOVE) },
            enabled = enabled,
            colors =
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.media_queue_removal))
        }
    }
}

@Composable
@ReadOnlyComposable
private fun decisionLabel(decision: ReviewDecision) =
    when (decision) {
        ReviewDecision.KEEP -> stringResource(R.string.media_decision_keep)
        ReviewDecision.LATER -> stringResource(R.string.media_decision_later)
        ReviewDecision.REMOVE -> stringResource(R.string.media_decision_remove)
        ReviewDecision.UNDECIDED -> ""
    }

/** A media card: thumbnail, name, size, its decision or favorite, and its selection box. */
@Composable
fun MediaTile(
    model: CleanerModel,
    item: CleanupItem,
    modifier: Modifier = Modifier,
    keeper: String? = null,
) {
    val actions = LocalActions.current
    val state by model.state.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val isSelected = item.id in selected
    Card(
        modifier.fillMaxWidth().testTag("tile-${item.name}"),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Space.ms), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Column(
                Modifier.combinedClickable(
                    onClickLabel = stringResource(R.string.media_review_label),
                    onLongClickLabel = stringResource(R.string.media_select_label),
                    onLongClick = { model.toggle(item.id) },
                ) {
                    if (
                        selected.any { id ->
                            model.media.value.any { it.id == id && it.kind == item.kind }
                        }
                    )
                        model.toggle(item.id)
                    else actions.open("detail:${item.id}")
                },
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Thumbnail(item, Modifier.fillMaxWidth().aspectRatio(1.2f), pixels = 360)
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    byteText(item.bytes),
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                keeper?.let { reason ->
                    Row(
                        Modifier.testTag("keeper-${item.name}"),
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Icon(Icons.Outlined.Verified, null, Modifier.size(16.dp), tint = Hues.cool)
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val decision = state.decisions[item.id]
                if (item.favorite)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Favorite,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.media_favorite),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else if (decision != null && decision != ReviewDecision.UNDECIDED)
                    Text(
                        decisionLabel(decision),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Spacer(Modifier.weight(1f))
                val selectItem = stringResource(R.string.media_select_item, item.name)
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { model.toggle(item.id) },
                    modifier = Modifier.semantics { contentDescription = selectItem },
                )
            }
        }
    }
}

/** A list row for a media item, with its thumbnail. */
@Composable
fun MediaRow(
    item: CleanupItem,
    note: String? = null,
    caution: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val supporting: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(byteText(item.bytes), style = MaterialTheme.typography.bodyMedium.numeric())
                if (item.favorite)
                    Icon(
                        Icons.Outlined.Favorite,
                        stringResource(R.string.media_favorite_protected),
                        Modifier.size(16.dp),
                    )
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (caution) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val colors =
        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    if (onClick != null)
        ListItem(
            content = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
            supportingContent = supporting,
            leadingContent = { Thumbnail(item, Modifier.size(56.dp), pixels = 160) },
            trailingContent = trailing,
            colors = colors,
            onClick = onClick,
        )
    else
        ListItem(
            content = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
            supportingContent = supporting,
            leadingContent = { Thumbnail(item, Modifier.size(56.dp), pixels = 160) },
            trailingContent = trailing,
            colors = colors,
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
    val state by model.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize().testTag("groups"),
        contentPadding = PaddingValues(bottom = Space.l),
    ) {
        item {
            Readable {
                CleanerHero(
                    stringResource(R.string.media_groups_hero_label),
                    "%,d".format(groups.size),
                    stringResource(R.string.media_groups_hero_detail),
                    Icons.Outlined.FilterNone,
                )
                PrimaryButton(
                    stringResource(R.string.media_select_suggested),
                    enabled = groups.isNotEmpty(),
                ) {
                    model.smartSelect()
                }
                if (selected.isNotEmpty())
                    FilledTonalButton(
                        onClick = { model.decide(selected, ReviewDecision.REMOVE) },
                        Modifier.fillMaxWidth().testTag("cleaner.queue-selection"),
                    ) {
                        Text(count(R.plurals.queue, selected.size))
                    }
                TextButton(onClick = { actions.open("pending") }) {
                    Text(stringResource(R.string.media_review_pending))
                }
            }
        }
        items(groups, key = { it.id }) { group ->
            var why by rememberSaveable(group.id) { mutableStateOf(false) }
            Readable(Modifier.padding(top = Space.l)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                    SectionRule(
                        stringResource(
                            if (group.kind == MatchKind.IDENTICAL_RESOURCES) R.string.media_identical_files
                            else R.string.media_similar_moments
                        )
                    )
                    val members =
                        group.members.mapNotNull { id -> media.firstOrNull { it.id == id } }
                    // The core's keeper suggestion and its reason; a label, never a selection.
                    val keeper =
                        remember(members, state) { keeperSuggestion(members, state.decisions) }
                    CleanerTiles(
                        *members
                            .map { member ->
                                @Composable { m: Modifier ->
                                    MediaTile(
                                        model,
                                        member,
                                        m,
                                        keeper = keeper?.takeIf { it.id == member.id }?.reason,
                                    )
                                }
                            }
                            .toTypedArray()
                    )
                    TextButton(onClick = { why = !why }) {
                        Text(stringResource(R.string.media_why_match))
                        Icon(
                            if (why) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null,
                        )
                    }
                    if (why)
                        Text(
                            group.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
        item {
            Readable(Modifier.padding(top = Space.l)) {
                CleanerNotice(stringResource(R.string.media_groups_notice))
                if (groups.isEmpty())
                    EmptyState(
                        stringResource(R.string.media_no_groups),
                        stringResource(R.string.media_no_groups_hint),
                        icon = Icons.Outlined.FilterNone,
                    )
            }
        }
    }
}

@Composable
fun PendingScreen(model: CleanerModel) {
    val media by model.media.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()

    val actions = LocalActions.current
    val items = media.filter { state.decisions[it.id] == ReviewDecision.REMOVE }
    // Per-item outcomes from the core: a changed or favorite item is marked here and never
    // deleted; the rest stay deletable.
    val outcomes =
        remember(media, state) {
            removalOutcomes(items.map { it.id }, media, state).associate { it.id to it.status }
        }
    val ready = items.filter { outcomes[it.id] == RemovalStatus.READY }
    val held = items.size - ready.size
    LaunchedEffect(Unit) { model.refreshPending() }
    var approved by remember { mutableStateOf<Set<String>?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        item {
            Readable {
                CleanerHero(
                    stringResource(R.string.media_pending_hero_label),
                    "%,d".format(items.size),
                    stringResource(R.string.media_pending_hero_detail),
                    Icons.Outlined.Inbox,
                    hue = Hues.warm,
                )
            }
        }
        items(items, key = { it.id }) { item ->
            val status = outcomes[item.id]
            Readable(Modifier.padding(bottom = Space.s)) {
                MediaRow(
                    item,
                    note =
                        when (status) {
                            RemovalStatus.CHANGED -> stringResource(R.string.media_pending_changed)
                            RemovalStatus.FAVORITE -> stringResource(R.string.media_pending_favorite)
                            else -> null
                        },
                    caution = status != RemovalStatus.READY,
                    onClick = { actions.open("detail:${item.id}") },
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (status == RemovalStatus.CHANGED)
                            TextButton(
                                onClick = { model.decide(setOf(item.id), ReviewDecision.REMOVE) },
                                enabled = !busy,
                                modifier = Modifier.testTag("pending-confirm-${item.name}"),
                            ) {
                                Text(stringResource(R.string.media_confirm))
                            }
                        TextButton(
                            onClick = { model.decide(setOf(item.id), ReviewDecision.UNDECIDED) }
                        ) {
                            Text(
                                stringResource(
                                    if (status == RemovalStatus.READY) R.string.media_undo
                                    else R.string.media_remove_from_queue
                                )
                            )
                        }
                    }
                }
            }
        }
        item {
            Readable(Modifier.padding(top = Space.m)) {
                CleanerNotice(stringResource(R.string.media_trash_notice))
                if (held > 0) CleanerNotice(count(R.plurals.held, held), caution = true)
                OutlinedButton(
                    onClick = { approved = ready.map { it.id }.toSet() },
                    enabled = ready.isNotEmpty() && !busy,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    modifier = Modifier.fillMaxWidth().testTag("cleaner.delete"),
                ) {
                    Text(stringResource(R.string.media_delete_reviewed))
                }
            }
        }
    }
    approved?.let { ids ->
        AlertDialog(
            onDismissRequest = { approved = null },
            title = { Text(count(R.plurals.delete, ids.size)) },
            text = {
                Text(stringResource(R.string.media_delete_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        approved = null
                        model.deleteReviewed(ids)
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.media_move_to_trash))
                }
            },
            dismissButton = {
                TextButton(onClick = { approved = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
fun ActivityScreen(model: CleanerModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        if (state.history.isEmpty())
            item {
                EmptyState(
                    stringResource(R.string.media_history_empty),
                    stringResource(R.string.media_history_empty_hint),
                    icon = Icons.Outlined.History,
                )
            }
        items(state.history, key = { it.id.toString() }) { entry ->
            Readable(Modifier.padding(bottom = Space.s)) {
                ListItem(
                    content = { Text(entry.message) },
                    supportingContent = {
                        Text(
                            SevenFormat.date(entry.date.atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.bodySmall.numeric(),
                        )
                    },
                    colors =
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
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

    val review by model.state.collectAsStateWithLifecycle()
    val item = media.firstOrNull { it.id == id }
    if (item == null) {
        EmptyState(
            stringResource(R.string.media_missing),
            stringResource(R.string.media_missing_hint),
            icon = Icons.Outlined.ImageNotSupported,
        )
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(bottom = Space.l)) {
            if (item.kind == MediaKind.VIDEO) VideoPreview(item)
            else {
                val photos = LocalPhotos.current
                val bitmap by
                    produceState<Bitmap?>(null, item.id) {
                        value =
                            withContext(Dispatchers.IO) { photos?.previewBitmap(item.id.toUri()) }
                    }
                val image = bitmap
                if (image != null)
                    ZoomImage(
                        image,
                        stringResource(R.string.media_photo_description, item.name),
                        Modifier.fillMaxWidth().height(360.dp),
                    )
                else
                    EmptyState(
                        stringResource(R.string.media_preview_unavailable),
                        stringResource(R.string.media_preview_unavailable_hint),
                        icon = Icons.Outlined.Image,
                    )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(
                        R.string.media_dimensions_size,
                        "%,d".format(item.width),
                        "%,d".format(item.height),
                        byteText(item.bytes),
                    ),
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.date?.let {
                    Text(
                        SevenFormat.date(it.atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodySmall.numeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.kind == MediaKind.VIDEO) {
                    val seconds = item.duration.toLong()
                    Text(
                        "%d:%02d".format(seconds / 60, seconds % 60),
                        style = MaterialTheme.typography.bodySmall.numeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DecisionButtons(
                selected = review.decisions[item.id] ?: ReviewDecision.UNDECIDED,
                enabled = !busy,
            ) {
                model.decide(setOf(item.id), it)
            }
            if (item.kind == MediaKind.PHOTO) {
                PrimaryButton(stringResource(R.string.media_inspect_compression), enabled = !busy) {
                    model.compress(item)
                }
                Text(
                    stringResource(R.string.media_original_retained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.kind == MediaKind.VIDEO) {
                PrimaryButton(stringResource(R.string.media_trim_or_compress), enabled = !busy) {
                    model.openVideo(item)
                }
            }
            TextButton(onClick = { model.setFavorite(item) }, enabled = !busy) {
                Icon(
                    if (item.favorite) Icons.Outlined.HeartBroken else Icons.Outlined.FavoriteBorder,
                    null,
                )
                Spacer(Modifier.width(Space.s))
                Text(
                    stringResource(
                        if (item.favorite) R.string.media_remove_favorite else R.string.media_mark_favorite
                    )
                )
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
internal fun VideoPreview(item: CleanupItem) {
    var failed by remember { mutableStateOf(false) }
    var rate by rememberSaveable { mutableFloatStateOf(1f) }
    var view by remember { mutableStateOf<SpeedVideoView?>(null) }
    if (failed) {
        EmptyState(
            stringResource(R.string.media_preview_unavailable),
            stringResource(R.string.media_video_unavailable_hint),
            icon = Icons.Outlined.Videocam,
        )
        return
    }
    val description = stringResource(R.string.media_video_description, item.name)
    val speeds =
        listOf(0.5f, 1f, 1.5f, 2f).map {
            it to stringResource(R.string.media_speed, java.text.NumberFormat.getInstance().format(it))
        }
    AndroidView(
        factory = { context ->
            SpeedVideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                // The first frame, not a blank box, until the person presses play.
                setOnPreparedListener {
                    player = it
                    seekTo(1)
                }
                // Shown in the screen's own words instead of the platform's alert.
                setOnErrorListener { _, _, _ ->
                    failed = true
                    true
                }
                setVideoURI(item.id.toUri())
                contentDescription = description
                view = this
            }
        },
        // Exact constraints: VideoView sizes itself to the video, which it knows only once its
        // surface exists, so it gets the item's own shape up front.
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(
                    (item.width.coerceAtLeast(1).toFloat() / item.height.coerceAtLeast(1)).coerceIn(
                        0.5f,
                        2f,
                    )
                )
                .clip(MaterialTheme.shapes.medium),
        onRelease = { it.stopPlayback() },
    )
    ConnectedChoice(speeds, rate) { value ->
        rate = value
        view?.speed = value
    }
}

/** VideoView that keeps a playback speed across play and pause. */
private class SpeedVideoView(context: android.content.Context) : VideoView(context) {
    var player: MediaPlayer? = null
    var speed = 1f
        set(value) {
            field = value
            if (isPlaying) apply()
        }

    override fun start() {
        super.start()
        apply()
    }

    private fun apply() {
        player?.let { runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }
}
