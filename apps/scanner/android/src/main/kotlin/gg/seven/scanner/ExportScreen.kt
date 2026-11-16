@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.scanner

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gg.seven.kit.ConnectedChoice
import gg.seven.kit.Section
import gg.seven.kit.Space
import gg.seven.kit.content
import gg.seven.kit.numeric
import gg.seven.kit.switch
import gg.seven.scanner.core.PaperSize
import gg.seven.scanner.core.PdfQuality
import gg.seven.scanner.core.ScanDocument
import gg.seven.scanner.core.pdfExportPlan
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export: pick pages on their thumbnails, set the PDF options, then one large button prepares the
 * file and becomes Share once it's ready. The ready file's size is the hero. Prepared files stay in
 * a per-export cache folder, cleaned a day later, so a share target has time to read them.
 */
@Composable
fun ExportScreen(model: ScannerModel, document: ScanDocument, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(document.pages.map { it.id.toString() }.toSet()) }
    var quality by rememberSaveable { mutableStateOf(PdfQuality.STANDARD) }
    var paper by rememberSaveable { mutableStateOf(PaperSize.A4) }
    var searchable by rememberSaveable { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<File?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selected, quality, paper, searchable) {
        exported = null
        saved = null
    }
    val allSelected = selected.size == document.pages.size
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        val file = exported ?: return@rememberLauncherForActivityResult
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) { copyTo(context, file, target) }
                saved = "Saved ${file.name}."
            } catch (e: Exception) {
                failure = describe(e)
            }
        }
    }
    fun prepare() {
        working = true
        failure = null
        exported = null
        scope.launch {
            try {
                val plan = pdfExportPlan(document, selected.map(java.util.UUID::fromString))
                val files = model.repository.io { pageFiles(document.copy(pages = plan.pages)) }
                val out = Processing.exportFile(context, plan.filename)
                withContext(Dispatchers.Default) { Processing.pdf(context, files, plan.pages, out, quality, paper, searchable) }
                exported = out
                view.confirm()
            } catch (e: Exception) {
                failure = describe(e)
                view.reject()
            }
            working = false
        }
    }
    Dialog(onDismissRequest = { if (!working) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Export PDF") },
                    navigationIcon = { IconButton(onClick = onClose, enabled = !working, modifier = Modifier.testTag("export.close")) { Icon(Icons.Outlined.Close, "Close") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.m, vertical = Space.s), contentAlignment = Alignment.Center) {
                        val file = exported
                        val wide = Modifier.widthIn(max = ScannerMetrics.contentWidth).fillMaxWidth().heightIn(min = 56.dp)
                        if (file != null)
                            Button(onClick = { shareFile(context, file, "application/pdf") }, modifier = wide.testTag("export.share")) {
                                Icon(Icons.Outlined.Share, null)
                                Spacer(Modifier.width(Space.s))
                                Text("Share PDF")
                            }
                        else
                            Button(onClick = ::prepare, enabled = selected.isNotEmpty() && !working, modifier = wide.testTag("export.prepare")) {
                                if (working) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Outlined.PictureAsPdf, null)
                                Spacer(Modifier.width(Space.s))
                                Text(if (working) "Preparing PDF…" else "Prepare PDF")
                            }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readable().padding(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size} of ${pageCount(document.pages.size)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.numeric())
                        TextButton(onClick = { selected = if (allSelected) emptySet() else document.pages.map { it.id.toString() }.toSet() }) {
                            Text(if (allSelected) "Clear selection" else "Select all")
                        }
                    }
                    val columns = if (largeText()) 2 else 4
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.ms), verticalArrangement = Arrangement.spacedBy(Space.ms), maxItemsInEachRow = columns) {
                        document.pages.forEachIndexed { index, page ->
                            val on = page.id.toString() in selected
                            Column(
                                Modifier.weight(1f)
                                    .toggleable(on, role = Role.Checkbox) {
                                        selected = if (on) selected - page.id.toString() else selected + page.id.toString()
                                        view.tick()
                                    }
                                    .semantics { contentDescription = "Page ${index + 1}" }
                                    .testTag("export.page.${index + 1}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Space.xs),
                            ) {
                                Box {
                                    PageThumbnail(model.repository.pageFile(document.id, page.id), page, Modifier.fillMaxWidth().alpha(if (on) 1f else 0.45f), maxPixels = 240)
                                    Icon(
                                        if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                        null,
                                        Modifier.align(Alignment.TopEnd).padding(Space.xs).background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape),
                                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("${index + 1}", style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        repeat((columns - document.pages.size % columns) % columns) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Section(
                    title = "PDF",
                    footer = "Text recognition runs on your device. Check important text for recognition errors. Smaller files reduce image detail in this export; your saved pages stay unchanged.",
                ) {
                    content {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text("Paper size", style = MaterialTheme.typography.bodyLarge)
                            ConnectedChoice(listOf(PaperSize.A4 to "A4", PaperSize.LETTER to "US Letter"), paper) { paper = it }
                        }
                    }
                    switch("Searchable text", searchable) { searchable = it }
                    content {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text("Quality", style = MaterialTheme.typography.bodyLarge)
                            ConnectedChoice(listOf(PdfQuality.STANDARD to "Standard", PdfQuality.COMPACT to "Smaller file"), quality) { quality = it }
                        }
                    }
                }
                exported?.let { file ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(Space.m), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text("PDF READY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Formatter.formatShortFileSize(context, file.length()), style = MaterialTheme.typography.displayLarge.numeric(), modifier = Modifier.testTag("export.size"))
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                            Spacer(Modifier.height(Space.s))
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                OutlinedButton(onClick = { previewing = true }, modifier = Modifier.weight(1f).testTag("export.preview")) {
                                    Icon(Icons.Outlined.Visibility, null)
                                    Spacer(Modifier.width(Space.s))
                                    Text("Preview")
                                }
                                OutlinedButton(onClick = { save.launch(file.name) }, modifier = Modifier.weight(1f).testTag("export.save")) {
                                    Icon(Icons.Outlined.Folder, null)
                                    Spacer(Modifier.width(Space.s))
                                    Text("Save to files", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            saved?.let { StatusLine(it, icon = Icons.Outlined.CheckCircle, tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
                failure?.let { StatusLine(it) }
            }
        }
    }
    val file = exported
    if (previewing && file != null) PdfPreview(file) { previewing = false }
}
