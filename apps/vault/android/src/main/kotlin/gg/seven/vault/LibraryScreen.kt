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
import androidx.compose.ui.platform.testTag
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

/** The order names as iOS writes them: "Newest", "Oldest", "Name", "Largest", "Manual". */
val VaultOrder.title: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }

/** iOS `VaultLibraryView`. */
@Composable
fun LibraryScreen(model: VaultModel) {
    val library = model.library ?: return
    val context = LocalContext.current
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
                searchFailure = "Note text could not be searched. Unlock again and retry."
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
                                "Moved ${ids.size} to Recently Deleted",
                                actionLabel = "Undo",
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
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                selecting = false
                                selected = emptySet()
                            }
                        ) {
                            Icon(Icons.Outlined.Close, "Done selecting")
                        }
                    },
                    actions = {
                        IconButton(onClick = { selected = visible.map { it.id }.toSet() }) {
                            Icon(Icons.Outlined.SelectAll, "Select all shown")
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
                        trash -> "Recently Deleted"
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
                        Icon(Icons.Outlined.Lock, "Lock")
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !model.busy) {
                            Icon(Icons.Outlined.MoreVert, "Library options")
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (library.grid) "List view" else "Grid view") },
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
                                text = { Text("Select") },
                                leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                                onClick = {
                                    menu = false
                                    selecting = true
                                    selected = emptySet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (trash) "Show library" else "Recently Deleted") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = {
                                    menu = false
                                    trash = !trash
                                    selected = emptySet()
                                    folderText = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                onClick = {
                                    menu = false
                                    renamingFolder = null
                                    folderDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Prepare encrypted backup") },
                                leadingIcon = { Icon(Icons.Outlined.Backup, null) },
                                onClick = {
                                    menu = false
                                    model.backup()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Settings") },
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
                        Icon(Icons.Outlined.Share, "Export selected originals")
                    }
                    if (trash) {
                        IconButton(
                            onClick = { changeTrash(false) },
                            enabled = selected.isNotEmpty() && !model.busy,
                        ) {
                            Icon(Icons.Outlined.RestoreFromTrash, "Restore selected")
                        }
                        IconButton(
                            onClick = { deleting = true },
                            enabled = selected.isNotEmpty() && !model.busy,
                        ) {
                            Icon(Icons.Outlined.DeleteForever, "Permanently delete selected")
                        }
                    } else {
                        IconButton(
                            onClick = { model.prepareRecognitionQueue(selected) },
                            enabled = selected.isNotEmpty() && !model.busy,
                            modifier = Modifier.testTag("ocr-batch-open"),
                        ) {
                            Icon(Icons.Outlined.TextSnippet, "Recognize selected text")
                        }
                        Box {
                            IconButton(
                                onClick = { moveMenu = true },
                                enabled = selected.isNotEmpty() && !model.busy,
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Move selected to…")
                            }
                            DropdownMenu(moveMenu, onDismissRequest = { moveMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Vault root") },
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
                            Icon(Icons.Outlined.Delete, "Move selected to trash")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
        },
        floatingActionButton = {
            if (!trash && !selecting)
                FloatingActionButtonMenu(
                    expanded = addMenu,
                    button = {
                        ToggleFloatingActionButton(
                            checked = addMenu,
                            onCheckedChange = { addMenu = !addMenu },
                            modifier =
                                Modifier.testTag("vault.add").semantics {
                                    contentDescription =
                                        if (addMenu) "Close add menu"
                                        else "Add files, photos, a note or a photo"
                                    stateDescription = if (addMenu) "Expanded" else "Collapsed"
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
                            gate.run("Import files") {
                                model.beginSystemFlow()
                                documents.launch(arrayOf("*/*"))
                            }
                        },
                        text = { Text("Files") },
                        icon = { Icon(Icons.Outlined.UploadFile, null) },
                        modifier = Modifier.testTag("vault.add.files"),
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            addMenu = false
                            gate.run("Import photos") {
                                model.beginSystemFlow()
                                photos.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                    )
                                )
                            }
                        },
                        text = { Text("Photos") },
                        icon = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            addMenu = false
                            gate.run("Write a note") { note = true }
                        },
                        text = { Text("Note") },
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
                                gate.run("Capture a photo") { camera = true }
                            },
                            text = { Text("Camera") },
                            icon = { Icon(Icons.Outlined.PhotoCamera, null) },
                        )
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
                        if (includeNoteText) "Search names and note text" else "Search file names",
                        state = search,
                        modifier = Modifier.testTag("vault.search"),
                    )
                    FilterChip(
                        selected = includeNoteText,
                        onClick = { includeNoteText = !includeNoteText },
                        label = { Text("Include note text") },
                        modifier = Modifier.testTag("vault.searchNotes"),
                    )
                    if (includeNoteText) {
                        Text(
                            "Search stays on this device while unlocked. Photos and PDFs need saved text first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (searching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s),
                            ) {
                                LoadingIndicator(Modifier.size(32.dp))
                                Text(
                                    "Searching note text…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        noteResult?.let { result ->
                            Text(
                                result.issue
                                    ?: buildString {
                                        append(
                                            "Searched ${result.searched} ${if (result.searched == 1u) "note" else "notes"}"
                                        )
                                        if (result.unavailable > 0u)
                                            append(" · ${result.unavailable} unreadable")
                                        if (result.omitted > 0u)
                                            append(
                                                " · ${result.omitted} skipped (500 notes / 32 MB per search)"
                                            )
                                    },
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
                            label = { Text("Sort: ${library.sort.title}") },
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
                        label = { Text("Favorites") },
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
                        if (entitlement is Entitlement.NotStarted)
                            "Start your free 7 days to add files and notes. Everything here stays readable and exportable."
                        else
                            "Your free 7 days have ended. Everything here stays readable, exportable and backed up; adding files and notes is part of the unlock."
                    )
                }
            if (!trash && query.isEmpty()) {
                val children = library.folders.filter { it.parent == folder }
                if (children.isNotEmpty())
                    item(span = full) {
                        Section(Modifier.padding(top = Space.s), title = "Folders") {
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
                                                text = { Text("Rename folder") },
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
                    if (trash) "Recently Deleted" else "Your files",
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
                                if (searching) "Searching note text…"
                                else "No results for “$query”",
                                if (includeNoteText)
                                    "Matches ignore case and accents. Check search coverage above if some notes could not be searched."
                                else "Search matches file names, ignoring case and accents.",
                                icon = Icons.Outlined.SearchOff,
                                action =
                                    "Clear search" to { search.edit { replace(0, length, "") } },
                            )
                        trash ->
                            EmptyState(
                                "Nothing in the trash",
                                "Deleted items stay here until you permanently remove them.",
                                icon = Icons.Outlined.Delete,
                            )
                        else ->
                            EmptyState(
                                "A place for private things.",
                                "Add photos, documents or a note. Your files stay on this device until you choose to export or back up.",
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
                if (grid)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier =
                            Modifier.testTag("vault.item")
                                .combinedClickable(
                                    onClickLabel = "Details",
                                    onLongClickLabel = "Select",
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
                                        "Favorite",
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
            title = { Text(if (existing == null) "New folder" else "Rename folder") },
            text = {
                TextInput("Folder name", name, Modifier.testTag("vault.folderName")) { name = it }
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
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Cancel") } },
        )
    }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = {
                Text(
                    if (selected.size == 1) "Permanently delete this file?"
                    else "Permanently delete ${selected.size} files?"
                )
            },
            text = { Text("Backups and exported copies are not deleted. This can’t be undone.") },
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
                    Text("Permanently delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
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
