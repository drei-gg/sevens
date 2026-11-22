@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package gg.seven.scanner

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.DropdownField
import gg.seven.kit.EmptyState
import gg.seven.kit.Section
import gg.seven.kit.SevenAbout
import gg.seven.kit.Space
import gg.seven.kit.item
import gg.seven.kit.numeric
import gg.seven.scanner.core.ScanDocument
import gg.seven.scanner.core.coreVersion
import java.io.FileInputStream
import kotlinx.coroutines.launch

/**
 * Documents moved here wait until they're restored or deleted for good: a thumbnail per document,
 * Restore on the row, delete permanently from the row's menu (long-press) after a confirmation.
 */
@Composable
fun RecentlyDeletedScreen(model: ScannerModel) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    var permanent by remember { mutableStateOf<ScanDocument?>(null) }
    var working by remember { mutableStateOf(false) }
    fun restore(document: ScanDocument) {
        working = true
        scope.launch {
            val restored = model.restore(document)
            working = false
            if (restored) nav.tell("Restored “${document.title}”")
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(nav.snackbar) },
        topBar = { ScannerTopBar("Recently Deleted", onBack = nav::pop) },
    ) { padding ->
        if (deleted.isEmpty())
            EmptyState(
                "Nothing in Recently Deleted",
                "Documents you delete wait here until you restore them or delete them permanently.",
                Modifier.padding(padding).padding(top = Space.xxl),
                icon = Icons.Outlined.Delete,
            )
        else
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().readable(),
                contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                items(deleted, key = { it.id.toString() }) { document ->
                    var menu by remember { mutableStateOf(false) }
                    val index = deleted.indexOf(document)
                    Box {
                        // Tap or long-press opens the row's actions (Restore, Delete permanently).
                        SegmentedListItem(
                            onClick = { menu = true },
                            onLongClick = { menu = true },
                            onLongClickLabel = "Options",
                            enabled = !working,
                            shapes = ListItemDefaults.segmentedShapes(index, deleted.size),
                            modifier = Modifier.testTag("trash.row"),
                            leadingContent = {
                                PageThumbnail(
                                    document.pages.firstOrNull()?.let {
                                        model.repository.pageFile(
                                            document.id,
                                            it.id,
                                            deleted = true,
                                        )
                                    },
                                    document.pages.firstOrNull(),
                                    Modifier.width(44.dp),
                                    maxPixels = 160,
                                )
                            },
                            supportingContent = {
                                Text(
                                    pageCount(document.pages.size),
                                    style = LocalTextStyle.current.numeric(),
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { restore(document) },
                                        enabled = !working,
                                        modifier = Modifier.testTag("trash.restore"),
                                    ) {
                                        Text("Restore")
                                    }
                                    IconButton(onClick = { menu = true }, enabled = !working) {
                                        Icon(
                                            Icons.Outlined.MoreVert,
                                            "Actions for ${document.title}",
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Restore") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, null) },
                                onClick = {
                                    menu = false
                                    restore(document)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Delete permanently",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteForever,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    permanent = document
                                },
                                modifier = Modifier.testTag("trash.delete"),
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Deleted documents stay on this phone until you restore or permanently delete them. Open a document’s menu to delete it for good. They are not a backup; export important copies.",
                        Modifier.padding(top = Space.s, start = Space.m, end = Space.m),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
    permanent?.let { document ->
        AlertDialog(
            onDismissRequest = { permanent = null },
            title = { Text("Delete “${document.title}” permanently?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        permanent = null
                        working = true
                        scope.launch {
                            model.permanentlyDelete(document)
                            working = false
                        }
                    },
                    modifier = Modifier.testTag("trash.confirm"),
                ) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { permanent = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Backup: what one file will hold (tiles), one large Export button, Restore beneath it, and the
 * limits in plain words.
 */
@Composable
fun BackupScreen(model: ScannerModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val documents by model.documents.collectAsStateWithLifecycle()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<Uri?>(null) }
    val pages = (documents + deleted).sumOf { it.pages.size }
    fun report(text: String, failure: Boolean) {
        message = text
        failed = failure
        if (failure) view.reject() else view.confirm()
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { target ->
            if (target == null) return@rememberLauncherForActivityResult
            working = true
            message = null
            scope.launch {
                try {
                    model.repository.io {
                        val out =
                            context.contentResolver.openOutputStream(target, "wt")
                                ?: throw IllegalStateException("That location can’t be written to.")
                        out.use { exportBackup(it) }
                    }
                    report("Backup saved.", false)
                } catch (e: Exception) {
                    runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, target)
                    }
                    report(
                        "Backup could not be completed. Your library is unchanged. " + describe(e),
                        true,
                    )
                }
                working = false
            }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { candidate = it }
    fun restore(source: Uri) {
        working = true
        message = null
        scope.launch {
            try {
                val count =
                    model.repository.io {
                        val descriptor =
                            context.contentResolver.openFileDescriptor(source, "r")
                                ?: throw IllegalStateException("This file couldn’t be opened.")
                        descriptor.use { fd ->
                            val length = fd.statSize
                            if (length < 0)
                                throw gg.seven.scanner.core.ScanException.InvalidDocument()
                            FileInputStream(fd.fileDescriptor).buffered().use {
                                restoreBackup(it, length)
                            }
                        }
                    }
                model.reload()
                report("Restored ${documentCount(resources, count)} as copies.", false)
            } catch (e: Exception) {
                report(
                    "This backup could not be restored. Your existing documents are unchanged. " +
                        describe(e),
                    true,
                )
            }
            working = false
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScannerTopBar("Backup & restore", onBack = nav::pop) },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readable()
                .padding(horizontal = Space.m, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            val tiles =
                listOf(
                    "Documents" to documents.size,
                    "Pages" to pages,
                    "Favorites" to documents.count { it.favorite == true },
                    "Recently Deleted" to deleted.size,
                )
            val perRow = if (largeText()) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                tiles.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
                        row.forEach { (title, value) ->
                            StatTile(title, "$value", Modifier.weight(1f))
                        }
                    }
                }
            }
            Text(
                "One portable file holds your documents, original pages, adjustments, notes and Recently Deleted. Save it on this phone, a memory card or USB drive, or a storage provider you choose.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                Button(
                    onClick = { export.launch(BACKUP_NAME) },
                    enabled = !working,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("backup.export"),
                ) {
                    if (working) LoadingIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Outlined.Backup, null)
                    Spacer(Modifier.width(Space.s))
                    Text(if (working) "Working…" else "Export backup")
                }
                FilledTonalButton(
                    onClick = { open.launch(arrayOf("*/*")) },
                    enabled = !working,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("backup.restore"),
                ) {
                    Icon(Icons.Outlined.Restore, null)
                    Spacer(Modifier.width(Space.s))
                    Text("Restore from backup")
                }
            }
            message?.let {
                StatusLine(
                    it,
                    Modifier.testTag("backup.message"),
                    icon = if (failed) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    tint =
                        if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf(
                        "Restore adds copies and never overwrites your current documents. Recently Deleted items remain deleted. Each backup supports up to 1 GB and 1,000 documents.",
                        "No automatic uploads or Scanner account. Cloud storage, if you choose it, is provided by your storage provider.",
                    )
                    .forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
    candidate?.let { source ->
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text("Restore copies from this backup?") },
            text = { Text("Your current documents stay as they are.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        candidate = null
                        restore(source)
                    },
                    modifier = Modifier.testTag("backup.confirm"),
                ) {
                    Text("Add restored copies")
                }
            },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } },
        )
    }
}

const val BACKUP_NAME = "Scanner backup.scannerbackup"

/**
 * Settings: appearance, privacy in plain words, then SevenKit's trial/unlock and about sections.
 */
@Composable
fun SettingsScreen(model: ScannerModel) {
    val nav = LocalNav.current
    val appearance by model.appearance.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScannerTopBar("Settings", onBack = nav::pop) },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readable()
                .padding(horizontal = Space.m, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Section(title = "Your documents") {
                item(
                    "Backup & restore",
                    icon = Icons.Outlined.SettingsBackupRestore,
                    onClick = { nav.push(Route.Backup) },
                )
                item(
                    "Recently Deleted",
                    icon = Icons.Outlined.Delete,
                    onClick = { nav.push(Route.Deleted) },
                )
            }
            Section(title = "Appearance") {
                row { shapes ->
                    SegmentedListItem(shapes) {
                        DropdownField(
                            "Theme",
                            appearance,
                            listOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                            Modifier.fillMaxWidth().testTag("settings.theme"),
                        ) {
                            model.setAppearance(it)
                        }
                    }
                }
            }
            Section(title = "Privacy") {
                item(
                    "On this phone",
                    supporting =
                        "Scans and text recognition are processed on your phone. Files you export or back up go only where you choose. No Scanner account or servers.",
                    icon = Icons.Outlined.Lock,
                )
                item(
                    "Camera scanning",
                    supporting = SCANNER_ENGINE_NOTE,
                    icon = Icons.Outlined.CameraAlt,
                )
                item(
                    "Searchable text",
                    supporting =
                        "Searchable PDF exports include recognized text. Review important text for recognition errors.",
                    icon = Icons.Outlined.DocumentScanner,
                )
            }
            SevenAbout(coreVersion = remember { coreVersion() })
        }
    }
}
