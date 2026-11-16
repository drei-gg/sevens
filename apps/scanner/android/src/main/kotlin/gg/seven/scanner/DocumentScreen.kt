@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package gg.seven.scanner

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.Space
import gg.seven.kit.numeric
import gg.seven.scanner.core.ScanDocument
import gg.seven.scanner.core.ScanPage
import gg.seven.scanner.core.pdfExportPlan
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * A document, page first: its pages as a grid (tap to adjust, the last tile adds more), quick
 * actions, the recognized text and details below, and Export PDF as the one large action.
 */
@Composable
fun DocumentScreen(model: ScannerModel, id: UUID) {
    val nav = LocalNav.current
    val gate = LocalSevenGate.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val documents by model.documents.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val found = documents.firstOrNull { it.id == id }
    if (found == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val document: ScanDocument = found
    var title by rememberSaveable(id) { mutableStateOf(document.title) }
    var note by rememberSaveable(id) { mutableStateOf(document.note.orEmpty()) }
    var lastTitle by rememberSaveable(id) { mutableStateOf(document.title) }
    var lastNote by rememberSaveable(id) { mutableStateOf(document.note.orEmpty()) }
    // Adopt a change from elsewhere (OCR finishing, a favorite) unless the field has been edited.
    LaunchedEffect(document.title, document.note) {
        if (title == lastTitle) title = document.title
        if (note == lastNote) note = document.note.orEmpty()
        lastTitle = document.title
        lastNote = document.note.orEmpty()
    }
    val edited = title != document.title || note != document.note.orEmpty()
    var working by remember { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<File?>(null) }
    var removing by remember { mutableStateOf<ScanPage?>(null) }
    val adders = rememberAdders(model, id)
    val camera = remember { cameraAvailable(context) }
    val allText = document.pages.map { it.text }.filter { it.isNotEmpty() }.joinToString("\n\n")
    val large = largeText()

    fun orderPages(ids: List<UUID>) {
        working = true
        scope.launch {
            model.setPageOrder(document, ids)
            working = false
        }
    }

    fun makePreview() {
        working = true
        scope.launch {
            try {
                val files = model.repository.io { pageFiles(document) }
                val out = Processing.exportFile(context, pdfExportPlan(document, document.pages.map { it.id }).filename)
                withContext(Dispatchers.Default) { Processing.pdf(context, files, document.pages, out, searchable = false) }
                preview = out
            } catch (e: Exception) {
                model.error.value = describe(e)
            }
            working = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(nav.snackbar) },
        topBar = {
            ScannerTopBar(document.title, onBack = nav::pop) {
                val starred = document.favorite == true
                IconToggleButton(
                    checked = starred,
                    onCheckedChange = {
                        view.tick()
                        scope.launch { model.setFavorite(document, it) }
                    },
                    modifier = Modifier.testTag("scanner.favorite"),
                ) {
                    Icon(if (starred) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (starred) "Remove from Favorites" else "Add to Favorites")
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = Space.m, vertical = Space.s), contentAlignment = Alignment.Center) {
                    Button(onClick = { exporting = true }, enabled = !working, modifier = Modifier.widthIn(max = ScannerMetrics.contentWidth).fillMaxWidth().heightIn(min = 56.dp).testTag("scanner.export")) {
                        Icon(Icons.Outlined.PictureAsPdf, null)
                        Spacer(Modifier.width(Space.s))
                        Text("Export PDF")
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).readable().padding(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Text(
                    "${pageCount(document.pages.size)} · ${dayAndTime(document.createdMillis)}",
                    style = MaterialTheme.typography.bodyMedium.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val columns = if (large) 1 else 2
                val tiles: List<ScanPage?> = document.pages + listOf(null)
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    tiles.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            row.forEach { page ->
                                Box(Modifier.weight(1f)) {
                                    if (page == null)
                                        AddPagesTile(enabled = !busy, camera = camera, onScan = { gate.run("Scan documents", adders.scan) }, onImport = { gate.run("Scan documents", adders.import) })
                                    else {
                                        val index = document.pages.indexOf(page)
                                        PageTile(
                                            model,
                                            document,
                                            page,
                                            index,
                                            onOpen = { nav.push(Route.Page(document.id, page.id)) },
                                            onShare = { shareText(context, page.text) },
                                            onRemove = { removing = page },
                                        )
                                    }
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                val actions: @Composable (Modifier) -> Unit = { each ->
                    ActionTile("Preview", Icons.Outlined.Visibility, each.testTag("scanner.preview"), enabled = !working) { makePreview() }
                    ActionTile("Arrange", Icons.Outlined.GridView, each.testTag("scanner.arrange"), enabled = document.pages.size > 1 && !working) { arranging = true }
                    ActionTile("Share text", Icons.Outlined.FormatQuote, each, enabled = allText.isNotEmpty()) { shareText(context, allText) }
                }
                if (large) Column(verticalArrangement = Arrangement.spacedBy(Space.s)) { actions(Modifier.fillMaxWidth()) }
                else Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { actions(Modifier.weight(1f)) }
                ContentCard("Recognized text") {
                    if (allText.isEmpty()) Text("No recognized text yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else SelectionContainer { Text(allText, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                    TextButton(
                        onClick = {
                            working = true
                            model.recognize(document) { working = false }
                        },
                        enabled = !working,
                        modifier = Modifier.testTag("scanner.recognize"),
                    ) {
                        Text("Recognize text again")
                    }
                }
                ContentCard("Details") {
                    OutlinedTextField(
                        title,
                        { title = it },
                        Modifier.fillMaxWidth().testTag("scanner.title"),
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        note,
                        { note = it },
                        Modifier.fillMaxWidth().testTag("scanner.note"),
                        label = { Text("Notes") },
                        placeholder = { Text("Optional notes") },
                        minLines = 3,
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    FilledTonalButton(
                        onClick = {
                            working = true
                            scope.launch {
                                model.saveDetails(document, title, note)?.let {
                                    lastTitle = it.title
                                    lastNote = it.note.orEmpty()
                                    title = it.title
                                    note = it.note.orEmpty()
                                }
                                working = false
                            }
                        },
                        enabled = edited && !working,
                        modifier = Modifier.testTag("scanner.saveDetails"),
                    ) {
                        Text("Save details")
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            if (model.delete(document)) {
                                nav.tell("Moved to Recently Deleted") { model.restore(document) }
                                nav.pop()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scanner.delete"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete document")
                }
            }
            if (working) LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
        }
    }
    removing?.let { page ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this page?") },
            text = { Text("Its image is deleted when the document is saved.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    orderPages(document.pages.filter { it.id != page.id }.map { it.id })
                }) { Text("Remove page", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (exporting) ExportScreen(model, document) { exporting = false }
    if (arranging) ArrangePages(model, document, onClose = { arranging = false }) { ids ->
        arranging = false
        orderPages(ids)
    }
    preview?.let { PdfPreview(it) { preview = null } }
}

/** An icon over a short label: the document's quick actions. */
@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp), shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(Space.s)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun PageTile(model: ScannerModel, document: ScanDocument, page: ScanPage, index: Int, onOpen: () -> Unit, onShare: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val number = index + 1
    Box {
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(onLongClickLabel = "Options", onLongClick = { menu = true }, onClickLabel = "Rotate or adjust", onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Page $number"
                    stateDescription = page.text.ifEmpty { "No recognized text" }.take(200)
                }
                .testTag("scanner.page.$number"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            PageThumbnail(model.repository.pageFile(document.id, page.id), page, Modifier.fillMaxWidth(), maxPixels = 700)
            Text("$number", style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (page.text.isNotEmpty())
                DropdownMenuItem(text = { Text("Share text") }, leadingIcon = { Icon(Icons.Outlined.FormatQuote, null) }, onClick = {
                    menu = false
                    onShare()
                })
            if (document.pages.size > 1) {
                if (page.text.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Remove page", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
            if (page.text.isEmpty() && document.pages.size < 2)
                DropdownMenuItem(text = { Text("Rotate or adjust") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = {
                    menu = false
                    onOpen()
                })
        }
    }
}

/** The last tile: add pages by camera or from files. Adding pages needs access, like a new document. */
@Composable
private fun AddPagesTile(enabled: Boolean, camera: Boolean, onScan: () -> Unit, onImport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val rule = MaterialTheme.colorScheme.outline
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Surface(
                onClick = { menu = true },
                enabled = enabled,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(ScannerMetrics.thumbnailRadius),
                color = MaterialTheme.colorScheme.background,
                modifier =
                    Modifier.fillMaxWidth()
                        .aspectRatio(ScannerMetrics.PAGE_ASPECT)
                        .drawBehind {
                            val radius = ScannerMetrics.thumbnailRadius.toPx()
                            drawRoundRect(rule, cornerRadius = CornerRadius(radius), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                        }
                        .semantics { contentDescription = "Add pages" }
                        .testTag("scanner.addPages"),
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically)) {
                    Icon(Icons.Outlined.Add, null)
                    Text("Add pages", style = MaterialTheme.typography.titleSmall)
                }
            }
            Text(" ", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (camera)
                DropdownMenuItem(text = { Text("Scan more pages") }, leadingIcon = { Icon(Icons.Outlined.DocumentScanner, null) }, onClick = {
                    menu = false
                    onScan()
                })
            DropdownMenuItem(text = { Text("Append images or PDF") }, leadingIcon = { Icon(Icons.Outlined.FileOpen, null) }, onClick = {
                menu = false
                onImport()
            })
        }
    }
}

/**
 * Reorder or remove pages in one list: drag by the handle (or TalkBack's Move up / Move down),
 * remove with the trailing button. Nothing is saved until Save; at least one page stays.
 */
@Composable
private fun ArrangePages(model: ScannerModel, document: ScanDocument, onClose: () -> Unit, onSave: (List<UUID>) -> Unit) {
    var pages by remember { mutableStateOf(document.pages) }
    val numbers = remember(document) { document.pages.withIndex().associate { (i, p) -> p.id to i + 1 } }
    val list = rememberLazyListState()
    val view = LocalView.current
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        // By key: the list's first item is the explanation, not a page.
        val a = pages.indexOfFirst { it.id.toString() == from.key }
        val b = pages.indexOfFirst { it.id.toString() == to.key }
        if (a >= 0 && b >= 0) {
            pages = pages.toMutableList().apply { add(b, removeAt(a)) }
            view.tick()
        }
    }
    fun move(from: Int, to: Int) {
        if (to !in pages.indices) return
        pages = pages.toMutableList().apply { add(to, removeAt(from)) }
    }
    val changed = pages.map { it.id } != document.pages.map { it.id }
    // Material's full-screen dialog (SevenKit FullScreenForm's bar), with the list itself scrolling.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Arrange pages") },
                    navigationIcon = { IconButton(onClick = onClose, modifier = Modifier.testTag("editor-cancel")) { Icon(Icons.Outlined.Close, "Close") } },
                    actions = { TextButton(onClick = { onSave(pages.map { it.id }) }, enabled = changed, modifier = Modifier.testTag("editor-save")) { Text("Save") } },
                )
            },
        ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().readable(), state = list, contentPadding = PaddingValues(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            item {
                Text(
                    "Drag to reorder. Removed pages are deleted when you tap Save. Keep at least one page, or delete the document.",
                    Modifier.padding(bottom = Space.s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(pages, key = { it.id.toString() }) { page ->
                ReorderableItem(reorder, key = page.id.toString()) { dragging ->
                    val index = pages.indexOf(page)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
                        tonalElevation = if (dragging) 2.dp else 0.dp,
                        modifier =
                            Modifier.semantics {
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction("Move up") { move(index, index - 1).let { true } },
                                        CustomAccessibilityAction("Move down") { move(index, index + 1).let { true } },
                                    )
                            },
                    ) {
                        Row(Modifier.padding(Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
                            Icon(Icons.Outlined.DragHandle, "Reorder", Modifier.draggableHandle().size(48.dp).padding(Space.ms))
                            PageThumbnail(model.repository.pageFile(document.id, page.id), page, Modifier.width(44.dp), maxPixels = 160)
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text("Page ${numbers[page.id]}", style = MaterialTheme.typography.titleSmall)
                                Text(page.text.ifEmpty { "No recognized text" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { pages = pages - page }, enabled = pages.size > 1) {
                                Icon(Icons.Outlined.RemoveCircleOutline, "Remove page ${numbers[page.id]}")
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
