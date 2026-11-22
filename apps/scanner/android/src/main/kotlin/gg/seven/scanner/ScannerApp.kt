@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package gg.seven.scanner

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.seven.kit.ConnectedChoice
import gg.seven.kit.EmptyState
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.SearchInput
import gg.seven.kit.Section
import gg.seven.kit.Space
import gg.seven.kit.item
import gg.seven.kit.numeric
import gg.seven.scanner.core.LibraryOrder
import gg.seven.scanner.core.ScanDocument
import gg.seven.scanner.core.documentMatches
import gg.seven.scanner.core.librarySorted
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Where the app is: the library at the root, then pushed screens. Saved across configuration
 * changes.
 */
sealed interface Route {
    data object Library : Route

    data class Document(val id: UUID) : Route

    data class Page(val document: UUID, val page: UUID) : Route

    data class Capture(val appendTo: UUID?) : Route

    data object Deleted : Route

    data object Backup : Route

    data object Settings : Route

    fun key(): String =
        when (this) {
            Library -> "library"
            is Document -> "document/$id"
            is Page -> "page/$document/$page"
            is Capture -> "capture/${appendTo ?: ""}"
            Deleted -> "deleted"
            Backup -> "backup"
            Settings -> "settings"
        }

    companion object {
        fun of(key: String): Route {
            val parts = key.split("/")
            return when (parts[0]) {
                "document" -> Document(UUID.fromString(parts[1]))
                "page" -> Page(UUID.fromString(parts[1]), UUID.fromString(parts[2]))
                "capture" ->
                    Capture(parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(UUID::fromString))
                "deleted" -> Deleted
                "backup" -> Backup
                "settings" -> Settings
                else -> Library
            }
        }
    }
}

/** App-wide: the navigation stack, the snackbar and the paths to capture and import. */
class ScannerNav(
    private val keys: MutableList<String>,
    val snackbar: SnackbarHostState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val undoLabel: String,
) {
    /** A snackbar that outlives the screen that raised it, with an optional Undo. */
    fun tell(message: String, undo: (suspend () -> Unit)? = null) {
        scope.launch {
            val result =
                snackbar.showSnackbar(
                    message,
                    actionLabel = undo?.let { undoLabel },
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    val top: Route
        get() = Route.of(keys.last())

    fun push(route: Route) {
        keys += route.key()
    }

    fun pop() {
        if (keys.size > 1) keys.removeAt(keys.lastIndex)
    }

    /** Pops back to the library. */
    fun home() {
        while (keys.size > 1) keys.removeAt(keys.lastIndex)
    }

    val depth: Int
        get() = keys.size
}

val LocalNav = staticCompositionLocalOf<ScannerNav> { error("No navigation") }

@Composable
fun ScannerApp(model: ScannerModel = viewModel()) {
    val keys =
        rememberSaveable(
            saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
        ) {
            mutableStateListOf("library")
        }
    val snackbar = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.undo)
    val nav =
        remember(keys, snackbar, undoLabel) { ScannerNav(keys, snackbar, appScope, undoLabel) }
    val error by model.error.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val unsaved by model.unsavedCapture.collectAsStateWithLifecycle()
    BackHandler(nav.depth > 1 && nav.top !is Route.Capture) { nav.pop() }
    CompositionLocalProvider(LocalNav provides nav) {
        when (val route = nav.top) {
            Route.Library -> LibraryScreen(model)
            is Route.Document -> DocumentScreen(model, route.id)
            is Route.Page -> PageEditor(model, route.document, route.page)
            is Route.Capture -> {
                // Without camera access, import from the same place, into the same document.
                val importer =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenMultipleDocuments()
                    ) { uris: List<Uri> ->
                        if (uris.isNotEmpty()) {
                            model.importFiles(uris, route.appendTo)
                            nav.pop()
                        }
                    }
                CameraCapture(
                    busy = model.busy.collectAsStateWithLifecycle().value,
                    progress = model.progress.collectAsStateWithLifecycle().value,
                    limit = model.capturePageLimit(route.appendTo),
                    onSave = { uris, cleanUp ->
                        model.capture(
                            uris,
                            route.appendTo,
                            // Saved or kept by the model (Try again / Discard pages): either way
                            // the capture screen can close without losing the pages.
                            onHandled = nav::pop,
                            cleanUp = cleanUp,
                        )
                    },
                    onClose = nav::pop,
                    onImport = { importer.launch(arrayOf("image/*", "application/pdf")) },
                )
            }
            Route.Deleted -> RecentlyDeletedScreen(model)
            Route.Backup -> BackupScreen(model)
            Route.Settings -> SettingsScreen(model)
        }
    }
    notice?.takeIf { error == null }?.let {
        AlertDialog(
            onDismissRequest = { model.notice.value = null },
            title = { Text(stringResource(R.string.scan_saved)) },
            text = { Text(it) },
            confirmButton = {
                TextButton(
                    onClick = { model.notice.value = null },
                    modifier = Modifier.testTag("scanner.notice.ok"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
    if (unsaved != null)
        error?.let {
            // Unsaved scanned pages leave only by being saved or by an explicit discard.
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.could_not_complete)) },
                text = { Text(it) },
                confirmButton = {
                    TextButton(
                        onClick = { model.retryCapture() },
                        modifier = Modifier.testTag("scanner.capture.retry"),
                    ) {
                        Text(stringResource(R.string.try_again))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { model.discardCapture() },
                        modifier = Modifier.testTag("scanner.capture.discard"),
                    ) {
                        Text(
                            stringResource(R.string.discard_pages),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    else error?.let {
        AlertDialog(
            onDismissRequest = { model.error.value = null },
            title = { Text(stringResource(R.string.could_not_complete)) },
            text = { Text(it) },
            confirmButton = {
                TextButton(
                    onClick = { model.error.value = null },
                    modifier = Modifier.testTag("scanner.error.ok"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

/** A screen's top bar on paper: back when pushed, its title, up to two actions. */
@Composable
fun ScannerTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
            }
        },
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    )
}

/**
 * Opens the scanner (ML Kit on personal/store, CameraX otherwise) and the system picker for images
 * and PDFs.
 */
class Adders(val scan: () -> Unit, val import: () -> Unit)

@Composable
fun rememberAdders(model: ScannerModel, appendTo: UUID?): Adders {
    val nav = LocalNav.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            uris: List<Uri> ->
            if (uris.isNotEmpty()) model.importFiles(uris, appendTo)
        }
    val scan =
        rememberPlatformScanner(
            pageLimit = { model.capturePageLimit(appendTo) },
            onPages = { uris ->
                model.capture(uris, appendTo) {
                    uris
                        .filter { it.scheme == "file" }
                        .forEach { u -> u.path?.let { File(it).delete() } }
                }
            },
            onCamera = { nav.push(Route.Capture(appendTo)) },
        )
    return remember(picker, scan) {
        Adders(scan) { picker.launch(arrayOf("image/*", "application/pdf")) }
    }
}

/**
 * The library: documents as a grid of first pages, newest first, search on top, Scan as the one
 * large action at the bottom with Import beside it. Recently Deleted and Backup sit under the grid.
 */
@Composable
fun LibraryScreen(model: ScannerModel) {
    val nav = LocalNav.current
    val gate = LocalSevenGate.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documents by model.documents.collectAsStateWithLifecycle()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    val unreadable by model.unreadable.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val order by model.order.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val camera = remember { cameraAvailable(context) }
    val adders = rememberAdders(model, null)
    val large = largeText()
    val visible =
        remember(documents, query, favoritesOnly, order) {
            librarySorted(
                order,
                documents.filter {
                    documentMatches(it, query) && (!favoritesOnly || it.favorite == true)
                },
            )
        }
    val moved = stringResource(R.string.moved_to_trash)
    val importFeature = stringResource(R.string.feature_import)
    val scanFeature = stringResource(R.string.feature_scan)
    val optionsLabel = stringResource(R.string.options)
    fun trash(document: ScanDocument) =
        scope.launch { if (model.delete(document)) nav.tell(moved) { model.restore(document) } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(nav.snackbar) },
        topBar = {
            ScannerTopBar(stringResource(R.string.library_title)) {
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort))
                    }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(
                                LibraryOrder.NEWEST to stringResource(R.string.sort_newest),
                                LibraryOrder.OLDEST to stringResource(R.string.sort_oldest),
                                LibraryOrder.NAME to stringResource(R.string.sort_name),
                            )
                            .forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        model.setOrder(value)
                                        sortMenu = false
                                    },
                                    trailingIcon = {
                                        if (order == value)
                                            Icon(
                                                Icons.Outlined.Check,
                                                stringResource(R.string.sort_selected),
                                            )
                                    },
                                )
                            }
                    }
                }
                IconButton(onClick = { nav.push(Route.Settings) }) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                val buttons: @Composable (Modifier, Modifier) -> Unit =
                    { importModifier, scanModifier ->
                        if (camera) {
                            FilledTonalButton(
                                onClick = { gate.run(importFeature, adders.import) },
                                enabled = !busy,
                                modifier =
                                    importModifier.heightIn(min = 56.dp).testTag("scanner.import"),
                            ) {
                                Icon(Icons.Outlined.FileOpen, null)
                                Spacer(Modifier.width(Space.s))
                                Text(stringResource(R.string.import_short))
                            }
                            Button(
                                onClick = { gate.run(scanFeature, adders.scan) },
                                enabled = !busy,
                                modifier =
                                    scanModifier.heightIn(min = 56.dp).testTag("scanner.scan"),
                            ) {
                                Icon(Icons.Outlined.DocumentScanner, null)
                                Spacer(Modifier.width(Space.s))
                                Text(stringResource(R.string.scan))
                            }
                        } else
                            Button(
                                onClick = { gate.run(importFeature, adders.import) },
                                enabled = !busy,
                                modifier =
                                    scanModifier.heightIn(min = 56.dp).testTag("scanner.import"),
                            ) {
                                Icon(Icons.Outlined.FileOpen, null)
                                Spacer(Modifier.width(Space.s))
                                Text(
                                    stringResource(
                                        if (large) R.string.import_short
                                        else R.string.import_images_or_pdf
                                    )
                                )
                            }
                    }
                Box(
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Space.m, vertical = Space.s),
                    contentAlignment = Alignment.Center,
                ) {
                    if (large)
                        Column(
                            Modifier.widthIn(max = ScannerMetrics.contentWidth),
                            verticalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            buttons(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
                        }
                    else
                        Row(
                            Modifier.widthIn(max = ScannerMetrics.contentWidth),
                            horizontalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            buttons(Modifier, Modifier.weight(1f))
                        }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (large) 260.dp else 150.dp),
            modifier =
                Modifier.padding(padding).fillMaxSize().readable().testTag("scanner.library"),
            contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            val full:
                (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) =
                {
                    GridItemSpan(maxLineSpan)
                }
            if (unreadable > 0)
                item(span = full) {
                    ContentCard(stringResource(R.string.unreadable_title)) {
                        StatusLine(
                            pluralStringResource(R.plurals.unreadable, unreadable, unreadable),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            if (busy)
                item(span = full) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(Space.m),
                            horizontalArrangement = Arrangement.spacedBy(Space.ms),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingIndicator(Modifier.size(32.dp))
                            Text(progress, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            if (!camera)
                item(span = full) {
                    Text(
                        stringResource(R.string.camera_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (documents.isEmpty()) {
                if (loaded)
                    item(span = full) {
                        EmptyState(
                            stringResource(R.string.library_empty_title),
                            stringResource(
                                if (camera) R.string.library_empty_camera
                                else R.string.library_empty_import
                            ),
                            Modifier.padding(vertical = Space.xxl),
                            icon = Icons.Outlined.DocumentScanner,
                        )
                    }
            } else {
                item(span = full) {
                    SearchInput(
                        stringResource(R.string.search_hint),
                        Modifier.testTag("scanner.search"),
                        state = search,
                    )
                }
                item(span = full) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                stringResource(
                                    when (order) {
                                        LibraryOrder.NEWEST -> R.string.heading_recent
                                        LibraryOrder.OLDEST -> R.string.heading_oldest
                                        LibraryOrder.NAME -> R.string.heading_by_name
                                    }
                                ),
                                Modifier.weight(1f).semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                documentCount(visible.size),
                                style = MaterialTheme.typography.bodyMedium.numeric(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (favoritesOnly || documents.any { it.favorite == true })
                            ConnectedChoice(
                                listOf(
                                    false to stringResource(R.string.filter_all),
                                    true to stringResource(R.string.favorites),
                                ),
                                favoritesOnly,
                            ) {
                                favoritesOnly = it
                            }
                    }
                }
                if (visible.isEmpty())
                    item(span = full) {
                        if (query.isEmpty())
                            EmptyState(
                                stringResource(R.string.no_favorites_title),
                                stringResource(R.string.no_favorites_text),
                                icon = Icons.Outlined.StarOutline,
                            )
                        else
                            EmptyState(
                                stringResource(R.string.no_results_title, query),
                                stringResource(R.string.no_results_text),
                                icon = Icons.Outlined.SearchOff,
                                action =
                                    stringResource(R.string.clear_search) to
                                        {
                                            search.edit { replace(0, length, "") }
                                        },
                            )
                    }
                items(visible, key = { it.id }) { document ->
                    var menu by remember { mutableStateOf(false) }
                    val description = cardDescription(document, pageCount(document.pages.size))
                    Box {
                        DocumentCard(
                            document,
                            document.pages.firstOrNull()?.let {
                                model.repository.pageFile(document.id, it.id)
                            },
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onLongClickLabel = optionsLabel,
                                    onLongClick = { menu = true },
                                ) {
                                    nav.push(Route.Document(document.id))
                                }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = description
                                    onLongClick(optionsLabel) {
                                        menu = true
                                        true
                                    }
                                }
                                .testTag("scanner.document"),
                        )
                        FilledTonalIconButton(
                            onClick = { menu = true },
                            modifier =
                                Modifier.align(Alignment.TopEnd).testTag("scanner.document.options"),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                stringResource(R.string.document_actions, document.title),
                            )
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            val starred = document.favorite == true
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (starred) R.string.favorite_remove
                                            else R.string.favorite_add
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (starred) Icons.Outlined.StarOutline
                                        else Icons.Outlined.Star,
                                        null,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    scope.launch { model.setFavorite(document, !starred) }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.move_to_trash),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    trash(document)
                                },
                            )
                        }
                    }
                }
            }
            item(span = full) {
                val trashLabel = stringResource(R.string.recently_deleted)
                val backupLabel = stringResource(R.string.backup_restore)
                Section(Modifier.padding(top = Space.s)) {
                    item(
                        trashLabel,
                        value = deleted.size.takeIf { it > 0 }?.toString(),
                        icon = Icons.Outlined.Delete,
                        onClick = { nav.push(Route.Deleted) },
                    )
                    item(
                        backupLabel,
                        icon = Icons.Outlined.SettingsBackupRestore,
                        onClick = { nav.push(Route.Backup) },
                    )
                }
            }
        }
    }
}
