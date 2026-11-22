@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
)

package gg.seven.vault

import android.text.format.Formatter
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.seven.kit.*
import gg.seven.vault.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The order names as iOS shows them: "Newest", "Oldest", "Name", "Largest", "Manual". */
val VaultOrder.title: String
    @Composable
    get() =
        stringResource(
            when (this) {
                VaultOrder.NEWEST -> R.string.order_newest
                VaultOrder.OLDEST -> R.string.order_oldest
                VaultOrder.NAME -> R.string.order_name
                VaultOrder.LARGEST -> R.string.order_largest
                VaultOrder.MANUAL -> R.string.order_manual
            }
        )

/** iOS `VaultLibraryView`. */
@Composable
fun LibraryScreen(model: VaultModel) {
    val library = model.library ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
    val gate = LocalSevenGate.current
    val store = LocalSevenStore.current
    val entitlement by store.entitlement.collectAsState()
    val paid = entitlement.paidFeaturesAvailable
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    var settings by rememberSaveable { mutableStateOf(false) }
    // Search text/results never enter Activity saved state or an on-disk index.
    val search = remember { TextFieldState() }
    var includeNoteText by remember { mutableStateOf(false) }
    val query = search.text.toString()
    var folderText by rememberSaveable { mutableStateOf<String?>(null) }
    val folder =
        folderText?.let(UUID::fromString)?.takeIf { id -> library.folders.any { it.id == id } }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var trash by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<UUID>()) }
    var note by rememberSaveable { mutableStateOf(false) }
    var folderDialog by rememberSaveable { mutableStateOf(false) }
    var renamingFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var camera by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    val thumbnails = remember { LruCache<String, ImageBitmap>(60) }

    if (settings) {
        BackHandler { settings = false }
        SettingsScreen(model, onBack = { settings = false })
        return
    }

    var noteResult by
        remember(library, trash, query, favorites, includeNoteText) {
            mutableStateOf<NoteSearchResult?>(null)
        }
    var searchFailure by
        remember(library, trash, query, favorites, includeNoteText) {
            mutableStateOf<String?>(null)
        }
    var searching by
        remember(library, trash, query, favorites, includeNoteText) { mutableStateOf(false) }
    LaunchedEffect(library, trash, query, favorites, includeNoteText) {
        if (includeNoteText && query.isNotBlank()) {
            searching = true
            try {
                kotlinx.coroutines.delay(250)
                val result = model.repository.searchNotes(query, trash, favorites)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                noteResult = result
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                searchFailure = resources.getString(R.string.search_failed)
            } finally {
                searching = false
            }
        }
    }
    val noteIds = noteResult?.ids.orEmpty().toSet()
    val visible =
        remember(library, trash, query, folder, favorites, noteIds) {
            vaultOrderSorted(
                library.sort,
                library.items.filter {
                    (if (trash) it.deleted != null else it.deleted == null) &&
                        (if (query.isEmpty()) it.folder == folder else true) &&
                        (!favorites || it.favorite) &&
                        (vaultItemMatches(it, query) || it.id in noteIds)
                },
            )
        }
    val current = library.folders.firstOrNull { it.id == folder }

    val documents =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            model.endSystemFlow()
            model.importDocuments(uris, folder, fromPhotos = false)
        }
    val photos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris
            ->
            model.endSystemFlow()
            model.importDocuments(uris, folder, fromPhotos = true)
        }

    fun changeTrash(value: Boolean, ids: Set<UUID> = selected) {
        model.update(
            then = {
                selected = emptySet()
                selecting = false
                if (value)
                    scope.launch {
                        val result =
                            snackbar.showSnackbar(
                                resources.getQuantityString(R.plurals.library_trashed, ids.size, ids.size),
                                actionLabel = resources.getString(R.string.library_undo),
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        if (result == SnackbarResult.ActionPerformed) changeTrash(false, ids)
                    }
            }
        ) {
            for (id in ids) model.repository.updateItem(id, trashed = value)
        }
    }

    fun move(target: UUID?) {
        val ids = selected
        model.update(
            then = {
                selected = emptySet()
                selecting = false
            }
        ) {
            model.repository.move(ids, target)
        }
    }

    BackHandler(selecting || folder != null || trash) {
        when {
            selecting -> {
                selecting = false
                selected = emptySet()
            }
            folder != null -> folderText = current?.parent?.toString()
            else -> trash = false
        }
    }

    LaunchedEffect(model.notice) {
        model.notice?.let {
            snackbar.showSnackbar(it)
            model.notice = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting)
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.library_selected, selected.size, selected.size)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                selecting = false
                                selected = emptySet()
                            }
                        ) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.library_done_selecting))
                        }
                    },
                    actions = {
                        IconButton(onClick = { selected = visible.map { it.id }.toSet() }) {
                            Icon(Icons.Outlined.SelectAll, stringResource(R.string.library_select_all))
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                )
            else
                SevenTopBar(
                    when {
                        trash -> stringResource(R.string.library_recently_deleted)
                        current != null -> current.name
                        else -> library.name
                    },
                    onBack =
                        when {
                            current != null -> {
                                { folderText = current.parent?.toString() }
                            }
                            trash -> {
                                { trash = false }
                            }
                            else -> null
                        },
                ) {
                    IconButton(onClick = model::lock, modifier = Modifier.testTag("vault.lock")) {
                        Icon(Icons.Outlined.Lock, stringResource(R.string.lock_action))
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !model.busy) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.library_options))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (library.grid) R.string.library_list_view else R.string.library_grid_view)) },
                                leadingIcon = {
                                    Icon(
                                        if (library.grid) Icons.AutoMirrored.Outlined.Notes
                                        else Icons.Outlined.GridView,
                                        null,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    model.update {
                                        model.repository.preferences(library.sort, !library.grid)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_select)) },
                                leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                                onClick = {
                                    menu = false
                                    selecting = true
                                    selected = emptySet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (trash) R.string.library_show_library else R.string.library_recently_deleted)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = {
                                    menu = false
                                    trash = !trash
                                    selected = emptySet()
                                    folderText = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_new_folder)) },
                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                onClick = {
                                    menu = false
                                    renamingFolder = null
                                    folderDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.backup_prepare)) },
                                leadingIcon = { Icon(Icons.Outlined.Backup, null) },
                                onClick = {
                                    menu = false
                                    model.backup()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_title)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = {
                                    menu = false
                                    settings = true
                                },
                            )
                        }
                    }
                }
        },
        bottomBar = {
            if (selecting)
                BottomAppBar {
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { model.export(selected) },
                        enabled = selected.isNotEmpty() && !model.busy,
                    ) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.library_export_selected))
                    }
                    if (trash) {
                        IconButton(
                            onClick = { changeTrash(false) },
                            enabled = selected.isNotEmpty() && !model.busy,
                        ) {
                            Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.library_restore_selected))
                        }
                        IconButton(
                            onClick = { deleting = true },
                            enabled = selected.isNotEmpty() && !model.busy,
                        ) {
                            Icon(Icons.Outlined.DeleteForever, stringResource(R.string.library_delete_selected))
                        }
                    } else {
                        IconButton(
                            onClick = { model.prepareRecognitionQueue(selected) },
                            enabled = selected.isNotEmpty() && !model.busy,
                            modifier = Modifier.testTag("ocr-batch-open"),
                        ) {
                            Icon(Icons.Outlined.TextSnippet, stringResource(R.string.library_recognize_selected))
                        }
                        Box {
                            IconButton(
                                onClick = { moveMenu = true },
                                enabled = selected.isNotEmpty() && !model.busy,
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.library_move_selected))
                            }
                            DropdownMenu(moveMenu, onDismissRequest = { moveMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_vault_root)) },
                                    onClick = {
                                        moveMenu = false
                                        move(null)
                                    },
                                )
                                library.folders.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(target.name) },
                                        leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                        onClick = {
                                            moveMenu = false
                                            move(target.id)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { changeTrash(true) },
                            enabled = selected.isNotEmpty() && !model.busy,
                        ) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.library_trash_selected))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
        },
        floatingActionButton = {
            if (!trash && !selecting) {
                val closeAdd = stringResource(R.string.library_add_close)
                val openAdd = stringResource(R.string.library_add_open)
                val expanded = stringResource(R.string.state_expanded)
                val collapsed = stringResource(R.string.state_collapsed)
                val importFiles = stringResource(R.string.gate_import_files)
                val importPhotos = stringResource(R.string.gate_import_photos)
                val writeNote = stringResource(R.string.gate_write_note)
                val capturePhoto = stringResource(R.string.gate_capture_photo)
                FloatingActionButtonMenu(
                    expanded = addMenu,
                    button = {
                        ToggleFloatingActionButton(
                            checked = addMenu,
                            onCheckedChange = { addMenu = !addMenu },
                            modifier =
                                Modifier.testTag("vault.add").semantics {
                                    contentDescription = if (addMenu) closeAdd else openAdd
                                    stateDescription = if (addMenu) expanded else collapsed
                                },
                        ) {
                            Icon(
                                if (checkedProgress > 0.5f) Icons.Outlined.Close
                                else Icons.Outlined.Add,
                                null,
                                tint =
                                    androidx.compose.ui.graphics.lerp(
                                        MaterialTheme.colorScheme.onPrimaryContainer,
                                        MaterialTheme.colorScheme.onPrimary,
                                        checkedProgress,
                                    ),
                            )
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = {
                            addMenu = false
                            gate.run(importFiles) {
                                model.beginSystemFlow()
                                documents.launch(arrayOf("*/*"))
                            }
                        },
                        text = { Text(stringResource(R.string.add_files)) },
                        icon = { Icon(Icons.Outlined.UploadFile, null) },
                        modifier = Modifier.testTag("vault.add.files"),
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            addMenu = false
                            gate.run(importPhotos) {
                                model.beginSystemFlow()
                                photos.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                    )
                                )
                            }
                        },
                        text = { Text(stringResource(R.string.add_photos)) },
                        icon = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            addMenu = false
                            gate.run(writeNote) { note = true }
                        },
                        text = { Text(stringResource(R.string.add_note)) },
                        icon = { Icon(Icons.Outlined.EditNote, null) },
                        modifier = Modifier.testTag("vault.add.note"),
                    )
                    if (
                        context.packageManager.hasSystemFeature(
                            android.content.pm.PackageManager.FEATURE_CAMERA_ANY
                        )
                    )
                        FloatingActionButtonMenuItem(
                            onClick = {
                                addMenu = false
                                gate.run(capturePhoto) { camera = true }
                            },
                            text = { Text(stringResource(R.string.add_camera)) },
                            icon = { Icon(Icons.Outlined.PhotoCamera, null) },
                        )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (library.grid && !selecting) 140.dp else 10_000.dp),
            modifier =
                Modifier.padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .fillMaxSize()
                    .testTag("vault.library"),
            contentPadding =
                PaddingValues(
                    start = Space.m,
                    end = Space.m,
                    top = Space.s,
                    bottom = Space.xxl * 2,
                ),
            horizontalArrangement = Arrangement.spacedBy(Space.ms),
            verticalArrangement =
                Arrangement.spacedBy(
                    if (library.grid && !selecting) Space.ms else ListItemDefaults.SegmentedGap
                ),
        ) {
            val full:
                (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) =
                {
                    GridItemSpan(maxLineSpan)
                }
            item(span = full) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    SearchInput(
                        stringResource(if (includeNoteText) R.string.search_names_and_notes else R.string.search_names),
                        state = search,
                        modifier = Modifier.testTag("vault.search"),
                    )
                    FilterChip(
                        selected = includeNoteText,
                        onClick = { includeNoteText = !includeNoteText },
                        label = { Text(stringResource(R.string.search_include_notes)) },
                        modifier = Modifier.testTag("vault.searchNotes"),
                    )
                    if (includeNoteText) {
                        Text(
                            stringResource(R.string.search_notes_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (searching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s),
                            ) {
                                LoadingIndicator(Modifier.size(32.dp))
                                Text(
                                    stringResource(R.string.search_searching),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        noteResult?.let { result ->
                            val searched = result.searched.toInt()
                            val unreadable = result.unavailable.toInt()
                            val skipped = result.omitted.toInt()
                            val coverage =
                                listOfNotNull(
                                        pluralStringResource(R.plurals.search_searched, searched, searched),
                                        if (unreadable > 0) pluralStringResource(R.plurals.search_unreadable, unreadable, unreadable) else null,
                                        if (skipped > 0) pluralStringResource(R.plurals.search_skipped, skipped, skipped) else null,
                                    )
                                    .joinToString(stringResource(R.string.search_separator))
                            Text(
                                result.issue?.wording?.text() ?: coverage,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("vault.searchCoverage"),
                            )
                        }
                        searchFailure?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item(span = full) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    var sorting by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(
                            onClick = { sorting = true },
                            enabled = !model.busy && !selecting,
                            label = { Text(stringResource(R.string.library_sort, library.sort.title)) },
                            leadingIcon = { Icon(Icons.Outlined.Sort, null) },
                            modifier = Modifier.testTag("vault.sort"),
                        )
                        DropdownMenu(sorting, onDismissRequest = { sorting = false }) {
                            VaultOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.title) },
                                    leadingIcon = {
                                        RadioButton(library.sort == order, onClick = null)
                                    },
                                    modifier =
                                        Modifier.semantics {
                                            role = Role.RadioButton
                                            this.selected = library.sort == order
                                        },
                                    onClick = {
                                        sorting = false
                                        model.update {
                                            model.repository.preferences(order, library.grid)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    FilterChip(
                        selected = favorites,
                        onClick = { favorites = !favorites },
                        enabled = !model.busy && !selecting,
                        label = { Text(stringResource(R.string.library_favorites)) },
                        leadingIcon = {
                            Icon(
                                if (favorites) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                null,
                            )
                        },
                        modifier = Modifier.testTag("vault.favorites"),
                    )
                }
            }
            if (model.busy) item(span = full) { Progress(model.progress) }
            if (model.importResult.isNotEmpty()) item(span = full) { Caption(model.importResult) }
            if (!paid && !trash)
                item(span = full) {
                    Caption(
                        stringResource(
                            if (entitlement is Entitlement.NotStarted) R.string.library_trial_not_started
                            else R.string.library_trial_ended
                        )
                    )
                }
            if (!trash && query.isEmpty()) {
                val children = library.folders.filter { it.parent == folder }
                if (children.isNotEmpty())
                    item(span = full) {
                        Section(Modifier.padding(top = Space.s), title = stringResource(R.string.library_folders)) {
                            children.forEach { child ->
                                row { shapes ->
                                    var options by remember { mutableStateOf(false) }
                                    Box {
                                        SegmentedListItem(
                                            onClick = {
                                                folderText = child.id.toString()
                                                selected = emptySet()
                                            },
                                            onLongClick = { options = true },
                                            shapes = shapes,
                                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                                        ) {
                                            Text(child.name)
                                        }
                                        DropdownMenu(
                                            options,
                                            onDismissRequest = { options = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.library_rename_folder)) },
                                                onClick = {
                                                    options = false
                                                    renamingFolder = child.id.toString()
                                                    folderDialog = true
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
            item(span = full) {
                Text(
                    stringResource(if (trash) R.string.library_recently_deleted else R.string.library_your_files),
                    Modifier.padding(start = Space.m, top = Space.m, bottom = Space.xs).semantics {
                        heading()
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (visible.isEmpty())
                item(span = full) {
                    when {
                        query.isNotEmpty() ->
                            EmptyState(
                                if (searching) stringResource(R.string.search_searching)
                                else stringResource(R.string.empty_no_results, query),
                                stringResource(
                                    if (includeNoteText) R.string.empty_no_results_notes_detail
                                    else R.string.empty_no_results_detail
                                ),
                                icon = Icons.Outlined.SearchOff,
                                action =
                                    stringResource(R.string.empty_clear_search) to
                                        { search.edit { replace(0, length, "") } },
                            )
                        trash ->
                            EmptyState(
                                stringResource(R.string.empty_trash_title),
                                stringResource(R.string.empty_trash_detail),
                                icon = Icons.Outlined.Delete,
                            )
                        else ->
                            EmptyState(
                                stringResource(R.string.empty_library_title),
                                stringResource(R.string.empty_library_detail),
                                icon = Icons.Outlined.Lock,
                            )
                    }
                }
            val grid = library.grid && !selecting
            items(visible, key = { it.id.toString() }) { item ->
                val isSelected = item.id in selected
                val open = {
                    if (selecting)
                        selected = if (isSelected) selected - item.id else selected + item.id
                    else editing = item.id.toString()
                }
                val startSelecting = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    selecting = true
                    selected = selected + item.id
                }
                val size = Formatter.formatShortFileSize(context, item.bytes)
                val detailsLabel = stringResource(R.string.library_details)
                val selectLabel = stringResource(R.string.library_select)
                if (grid)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier =
                            Modifier.testTag("vault.item")
                                .combinedClickable(
                                    onClickLabel = detailsLabel,
                                    onLongClickLabel = selectLabel,
                                    onClick = open,
                                    onLongClick = startSelecting,
                                ),
                    ) {
                        Column(
                            Modifier.padding(Space.s),
                            verticalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            Thumbnail(
                                model,
                                item,
                                thumbnails,
                                Modifier.fillMaxWidth().height(100.dp),
                            )
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                size,
                                style = MaterialTheme.typography.bodySmall.numeric(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                else {
                    val index = visible.indexOf(item)
                    SegmentedListItem(
                        onClick = open,
                        onLongClick = startSelecting,
                        shapes = ListItemDefaults.segmentedShapes(index, visible.size),
                        modifier =
                            Modifier.testTag("vault.item").semantics {
                                if (selecting) {
                                    role = Role.Checkbox
                                    toggleableState =
                                        if (isSelected) androidx.compose.ui.state.ToggleableState.On
                                        else androidx.compose.ui.state.ToggleableState.Off
                                }
                            },
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selecting) {
                                    Checkbox(checked = isSelected, onCheckedChange = null)
                                    Spacer(Modifier.width(Space.ms))
                                }
                                Thumbnail(model, item, thumbnails, Modifier.size(56.dp))
                            }
                        },
                        supportingContent = {
                            Text(size, style = MaterialTheme.typography.bodySmall.numeric())
                        },
                        trailingContent =
                            if (item.favorite)
                                ({
                                    Icon(
                                        Icons.Filled.Star,
                                        stringResource(R.string.item_favorite),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                })
                            else null,
                    ) {
                        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(span = full) { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (note) NoteScreen(model, folder) { note = false }
    editing?.let { text ->
        val id = UUID.fromString(text)
        val item = library.items.firstOrNull { it.id == id }
        if (item == null) editing = null else ItemDetailsScreen(model, item) { editing = null }
    }
    if (camera)
        CameraScreen(
            model,
            onCaptured = { file ->
                camera = false
                model.importCapture(file, folder)
            },
            onClose = { camera = false },
        )
    if (folderDialog) {
        val existing = renamingFolder?.let(UUID::fromString)
        var name by rememberSaveable {
            mutableStateOf(
                existing?.let { id -> library.folders.firstOrNull { it.id == id }?.name } ?: ""
            )
        }
        AlertDialog(
            onDismissRequest = { if (!model.busy) folderDialog = false },
            title = { Text(stringResource(if (existing == null) R.string.library_new_folder else R.string.library_rename_folder)) },
            text = {
                TextInput(stringResource(R.string.library_folder_name), name, Modifier.testTag("vault.folderName")) { name = it }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !model.busy,
                    onClick = {
                        val value = name
                        model.update(then = { folderDialog = false }) {
                            if (existing != null) model.repository.renameFolder(existing, value)
                            else model.repository.addFolder(value, folder)
                        }
                    },
                ) {
                    Text(stringResource(gg.seven.kit.R.string.seven_save))
                }
            },
            dismissButton = { TextButton(onClick = { folderDialog = false }) { Text(stringResource(gg.seven.kit.R.string.seven_cancel)) } },
        )
    }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = {
                Text(
                    if (selected.size == 1) stringResource(R.string.library_delete_one_title)
                    else pluralStringResource(R.plurals.library_delete_title, selected.size, selected.size)
                )
            },
            text = { Text(stringResource(R.string.library_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        val ids = selected
                        model.update(
                            then = {
                                selected = emptySet()
                                selecting = false
                            }
                        ) {
                            model.repository.permanentlyDelete(ids)
                        }
                    }
                ) {
                    Text(stringResource(R.string.library_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(gg.seven.kit.R.string.seven_cancel)) } },
        )
}

@Composable
fun Caption(text: String) {
    Text(
        text,
        Modifier.padding(start = Space.m, end = Space.m, top = Space.xs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * iOS `VaultThumbnail`: images get a small decoded thumbnail; the plaintext copy is removed at
 * once.
 */
@Composable
fun Thumbnail(
    model: VaultModel,
    item: VaultItem,
    cache: LruCache<String, ImageBitmap>,
    modifier: Modifier,
) {
    val image = Types.isImage(item.contentType, item.name)
    var bitmap by remember(item.id, item.digest) { mutableStateOf(cache.get(item.digest)) }
    if (image && bitmap == null)
        LaunchedEffect(item.id, item.digest) {
            if (model.busy) return@LaunchedEffect
            val generation = model.generation
            val decoded =
                withContext(Dispatchers.IO) {
                    val folder = File(model.repository.temporary, "thumb-" + UUID.randomUUID())
                    try {
                        val file = model.repository.export(item.id, folder)
                        Images.decode(file, 240)?.asImageBitmap()
                    } catch (_: Exception) {
                        null // A thumbnail is optional; opening the file reports the underlying
                        // error.
                    } finally {
                        folder.deleteRecursively()
                    }
                }
            if (decoded != null && generation == model.generation && model.library != null) {
                cache.put(item.digest, decoded)
                bitmap = decoded
            }
        }
    Box(modifier.clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null)
            Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else
            Icon(
                when {
                    Types.isVideo(item.contentType, item.name) -> Icons.Outlined.Videocam
                    image -> Icons.Outlined.Image
                    Types.isText(item.contentType, item.name) -> Icons.Outlined.Description
                    else -> Icons.Outlined.InsertDriveFile
                },
                null,
                Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
