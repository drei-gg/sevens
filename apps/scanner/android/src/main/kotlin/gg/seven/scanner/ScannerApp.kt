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

/** Where the app is: the library at the root, then pushed screens. Saved across configuration changes. */
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
                "capture" -> Capture(parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(UUID::fromString))
                "deleted" -> Deleted
                "backup" -> Backup
                "settings" -> Settings
                else -> Library
            }
        }
    }
}

/** App-wide: the navigation stack, the snackbar and the paths to capture and import. */
class ScannerNav(private val keys: MutableList<String>, val snackbar: SnackbarHostState, private val scope: kotlinx.coroutines.CoroutineScope) {
    /** A snackbar that outlives the screen that raised it, with an optional Undo. */
    fun tell(message: String, undo: (suspend () -> Unit)? = null) {
        scope.launch {
            val result = snackbar.showSnackbar(message, actionLabel = undo?.let { "Undo" }, duration = SnackbarDuration.Short)
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
    val keys = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf("library") }
    val snackbar = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    val nav = remember(keys, snackbar) { ScannerNav(keys, snackbar, appScope) }
    val error by model.error.collectAsStateWithLifecycle()
    BackHandler(nav.depth > 1 && nav.top !is Route.Capture) { nav.pop() }
    CompositionLocalProvider(LocalNav provides nav) {
        when (val route = nav.top) {
            Route.Library -> LibraryScreen(model)
            is Route.Document -> DocumentScreen(model, route.id)
            is Route.Page -> PageEditor(model, route.document, route.page)
            is Route.Capture ->
                CameraCapture(
                    onSave = { uris, cleanUp ->
                        nav.pop()
                        model.capture(uris, route.appendTo, cleanUp)
                    },
                    onClose = nav::pop,
                    onImport = null,
                )
            Route.Deleted -> RecentlyDeletedScreen(model)
            Route.Backup -> BackupScreen(model)
            Route.Settings -> SettingsScreen(model)
        }
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { model.error.value = null },
            title = { Text("Could not complete") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { model.error.value = null }, modifier = Modifier.testTag("scanner.error.ok")) { Text("OK") } },
        )
    }
}

/** A screen's top bar on paper: back when pushed, its title, up to two actions. */
@Composable
fun ScannerTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

/** Opens the scanner (ML Kit on personal/store, CameraX otherwise) and the system picker for images and PDFs. */
class Adders(val scan: () -> Unit, val import: () -> Unit)

@Composable
fun rememberAdders(model: ScannerModel, appendTo: UUID?): Adders {
    val nav = LocalNav.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) model.importFiles(uris, appendTo)
    }
    val scan =
        rememberPlatformScanner(
            onPages = { uris -> model.capture(uris, appendTo) { uris.filter { it.scheme == "file" }.forEach { u -> u.path?.let { File(it).delete() } } } },
            onCamera = { nav.push(Route.Capture(appendTo)) },
        )
    return remember(picker, scan) { Adders(scan) { picker.launch(arrayOf("image/*", "application/pdf")) } }
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
    val visible = remember(documents, query, favoritesOnly, order) {
        librarySorted(order, documents.filter { documentMatches(it, query) && (!favoritesOnly || it.favorite == true) })
    }
    fun trash(document: ScanDocument) =
        scope.launch { if (model.delete(document)) nav.tell("Moved to Recently Deleted") { model.restore(document) } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(nav.snackbar) },
        topBar = {
            ScannerTopBar("Documents") {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(LibraryOrder.NEWEST to "Newest first", LibraryOrder.OLDEST to "Oldest first", LibraryOrder.NAME to "Name").forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    model.setOrder(value)
                                    sortMenu = false
                                },
                                trailingIcon = { if (order == value) Icon(Icons.Outlined.Check, "Selected") },
                            )
                        }
                    }
                }
                IconButton(onClick = { nav.push(Route.Settings) }) { Icon(Icons.Outlined.Settings, "Settings") }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                val buttons: @Composable (Modifier, Modifier) -> Unit = { importModifier, scanModifier ->
                    if (camera) {
                        FilledTonalButton(onClick = { gate.run("Import documents", adders.import) }, enabled = !busy, modifier = importModifier.heightIn(min = 56.dp).testTag("scanner.import")) {
                            Icon(Icons.Outlined.FileOpen, null)
                            Spacer(Modifier.width(Space.s))
                            Text("Import")
                        }
                        Button(onClick = { gate.run("Scan documents", adders.scan) }, enabled = !busy, modifier = scanModifier.heightIn(min = 56.dp).testTag("scanner.scan")) {
                            Icon(Icons.Outlined.DocumentScanner, null)
                            Spacer(Modifier.width(Space.s))
                            Text("Scan")
                        }
                    } else
                        Button(onClick = { gate.run("Import documents", adders.import) }, enabled = !busy, modifier = scanModifier.heightIn(min = 56.dp).testTag("scanner.import")) {
                            Icon(Icons.Outlined.FileOpen, null)
                            Spacer(Modifier.width(Space.s))
                            Text(if (large) "Import" else "Import images or PDF")
                        }
                }
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.m, vertical = Space.s), contentAlignment = Alignment.Center) {
                    if (large) Column(Modifier.widthIn(max = ScannerMetrics.contentWidth), verticalArrangement = Arrangement.spacedBy(Space.s)) { buttons(Modifier.fillMaxWidth(), Modifier.fillMaxWidth()) }
                    else Row(Modifier.widthIn(max = ScannerMetrics.contentWidth), horizontalArrangement = Arrangement.spacedBy(Space.s)) { buttons(Modifier, Modifier.weight(1f)) }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (large) 260.dp else 150.dp),
            modifier = Modifier.padding(padding).fillMaxSize().readable().testTag("scanner.library"),
            contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
            if (unreadable > 0)
                item(span = full) {
                    ContentCard("Some folders could not be read") {
                        StatusLine("$unreadable document folders could not be read. Their files have been preserved. Backup is paused until they can be recovered.", tint = MaterialTheme.colorScheme.error)
                    }
                }
            if (busy)
                item(span = full) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Row(Modifier.fillMaxWidth().padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.ms), verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(32.dp))
                            Text(progress, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            if (!camera)
                item(span = full) { Text("Camera scanning is unavailable here. You can import documents instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (documents.isEmpty()) {
                if (loaded)
                    item(span = full) {
                        EmptyState(
                            "No documents yet",
                            if (camera) "Scans you save appear here, newest first. Tap Scan to start." else "Documents you import appear here, newest first.",
                            Modifier.padding(vertical = Space.xxl),
                            icon = Icons.Outlined.DocumentScanner,
                        )
                    }
            } else {
                item(span = full) { SearchInput("Search names, notes and text", Modifier.testTag("scanner.search"), state = search) }
                item(span = full) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                when (order) {
                                    LibraryOrder.NEWEST -> "Recent"
                                    LibraryOrder.OLDEST -> "Oldest first"
                                    LibraryOrder.NAME -> "By name"
                                },
                                Modifier.weight(1f).semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(documentCount(visible.size), style = MaterialTheme.typography.bodyMedium.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (favoritesOnly || documents.any { it.favorite == true })
                            ConnectedChoice(listOf(false to "All", true to "Favorites"), favoritesOnly) { favoritesOnly = it }
                    }
                }
                if (visible.isEmpty())
                    item(span = full) {
                        if (query.isEmpty()) EmptyState("No favorites", "Star a document to keep it here.", icon = Icons.Outlined.StarOutline)
                        else EmptyState("No results for “$query”", "Try another word from the name, notes or text.", icon = Icons.Outlined.SearchOff, action = "Clear search" to { search.edit { replace(0, length, "") } })
                    }
                items(visible, key = { it.id }) { document ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        DocumentCard(
                            document,
                            document.pages.firstOrNull()?.let { model.repository.pageFile(document.id, it.id) },
                            Modifier.fillMaxWidth()
                                .combinedClickable(onLongClickLabel = "Options", onLongClick = { menu = true }) { nav.push(Route.Document(document.id)) }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = cardDescription(document)
                                    onLongClick("Options") {
                                        menu = true
                                        true
                                    }
                                }
                                .testTag("scanner.document"),
                        )
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            val starred = document.favorite == true
                            DropdownMenuItem(
                                text = { Text(if (starred) "Remove from Favorites" else "Add to Favorites") },
                                leadingIcon = { Icon(if (starred) Icons.Outlined.StarOutline else Icons.Outlined.Star, null) },
                                onClick = {
                                    menu = false
                                    scope.launch { model.setFavorite(document, !starred) }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Move to Recently Deleted", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
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
                Section(Modifier.padding(top = Space.s)) {
                    item("Recently Deleted", value = deleted.size.takeIf { it > 0 }?.toString(), icon = Icons.Outlined.Delete, onClick = { nav.push(Route.Deleted) })
                    item("Backup & restore", icon = Icons.Outlined.SettingsBackupRestore, onClick = { nav.push(Route.Backup) })
                }
            }
        }
    }
}
