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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    val resources = LocalResources.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable {
        mutableStateOf(document.pages.map { it.id.toString() }.toSet())
    }
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
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { target ->
            val file = exported ?: return@rememberLauncherForActivityResult
            if (target == null) return@rememberLauncherForActivityResult
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { copyTo(context, file, target) }
                    saved = resources.getString(R.string.saved_file, file.name)
                } catch (e: Exception) {
                    failure = describe(resources, e)
                } finally {
                    working = false
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
                withContext(Dispatchers.Default) {
                    Processing.pdf(context, files, plan.pages, out, quality, paper, searchable)
                }
                exported = out
                view.confirm()
            } catch (e: Exception) {
                failure = describe(resources, e)
                view.reject()
            }
            working = false
        }
    }
    Dialog(
        onDismissRequest = { if (!working) onClose() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.export_pdf)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onClose,
                            enabled = !working,
                            modifier = Modifier.testTag("export.close"),
                        ) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.close))
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(
                        Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Space.m, vertical = Space.s),
                        contentAlignment = Alignment.Center,
                    ) {
                        val file = exported
                        val wide =
                            Modifier.widthIn(max = ScannerMetrics.contentWidth)
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        if (file != null)
                            Button(
                                onClick = { shareFile(context, file, "application/pdf") },
                                enabled = !working,
                                modifier = wide.testTag("export.share"),
                            ) {
                                Icon(Icons.Outlined.Share, null)
                                Spacer(Modifier.width(Space.s))
                                Text(stringResource(R.string.share_pdf))
                            }
                        else
                            Button(
                                onClick = ::prepare,
                                enabled = selected.isNotEmpty() && !working,
                                modifier = wide.testTag("export.prepare"),
                            ) {
                                if (working) LoadingIndicator(Modifier.size(24.dp))
                                else Icon(Icons.Outlined.PictureAsPdf, null)
                                Spacer(Modifier.width(Space.s))
                                Text(
                                    stringResource(
                                        if (working) R.string.preparing_pdf
                                        else R.string.prepare_pdf
                                    )
                                )
                            }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .readable()
                    .padding(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                R.string.export_selection,
                                selected.size,
                                pageCount(document.pages.size),
                            ),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall.numeric(),
                        )
                        TextButton(
                            enabled = !working,
                            onClick = {
                                selected =
                                    if (allSelected) emptySet()
                                    else document.pages.map { it.id.toString() }.toSet()
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.clear_selection
                                    else R.string.select_all
                                )
                            )
                        }
                    }
                    val columns = if (largeText()) 2 else 4
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.ms),
                        verticalArrangement = Arrangement.spacedBy(Space.ms),
                        maxItemsInEachRow = columns,
                    ) {
                        document.pages.forEachIndexed { index, page ->
                            val on = page.id.toString() in selected
                            val pageLabel = stringResource(R.string.page_number, index + 1)
                            Column(
                                Modifier.weight(1f)
                                    .toggleable(on, enabled = !working, role = Role.Checkbox) {
                                        selected =
                                            if (on) selected - page.id.toString()
                                            else selected + page.id.toString()
                                        view.tick()
                                    }
                                    .semantics { contentDescription = pageLabel }
                                    .testTag("export.page.${index + 1}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Space.xs),
                            ) {
                                Box {
                                    PageThumbnail(
                                        model.repository.pageFile(document.id, page.id),
                                        page,
                                        Modifier.fillMaxWidth().alpha(if (on) 1f else 0.45f),
                                        maxPixels = 240,
                                    )
                                    Checkbox(
                                        checked = on,
                                        onCheckedChange = null,
                                        enabled = !working,
                                        modifier =
                                            Modifier.align(Alignment.TopEnd)
                                                .background(
                                                    MaterialTheme.colorScheme.surface,
                                                    CircleShape,
                                                ),
                                    )
                                }
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall.numeric(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        repeat((columns - document.pages.size % columns) % columns) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                val paperSize = stringResource(R.string.paper_size)
                val papers =
                    listOf(
                        PaperSize.A4 to stringResource(R.string.paper_a4),
                        PaperSize.LETTER to stringResource(R.string.paper_letter),
                    )
                val searchableText = stringResource(R.string.searchable_text)
                val qualityLabel = stringResource(R.string.quality)
                val qualities =
                    listOf(
                        PdfQuality.STANDARD to stringResource(R.string.quality_standard),
                        PdfQuality.COMPACT to stringResource(R.string.quality_compact),
                    )
                Section(
                    title = stringResource(R.string.pdf),
                    footer = stringResource(R.string.pdf_footer),
                ) {
                    content {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(paperSize, style = MaterialTheme.typography.bodyLarge)
                            ConnectedChoice(
                                papers,
                                paper,
                                enabled = !working,
                            ) {
                                paper = it
                            }
                        }
                    }
                    switch(searchableText, searchable, enabled = !working) { searchable = it }
                    content {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(qualityLabel, style = MaterialTheme.typography.bodyLarge)
                            ConnectedChoice(
                                qualities,
                                quality,
                                enabled = !working,
                            ) {
                                quality = it
                            }
                        }
                    }
                }
                exported?.let { file ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(Space.m),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            Text(
                                stringResource(R.string.pdf_ready),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                Formatter.formatShortFileSize(context, file.length()),
                                style = MaterialTheme.typography.displayLarge.numeric(),
                                modifier = Modifier.testTag("export.size"),
                            )
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                            Spacer(Modifier.height(Space.s))
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                OutlinedButton(
                                    onClick = { previewing = true },
                                    enabled = !working,
                                    modifier = Modifier.weight(1f).testTag("export.preview"),
                                ) {
                                    Icon(Icons.Outlined.Visibility, null)
                                    Spacer(Modifier.width(Space.s))
                                    Text(stringResource(R.string.preview))
                                }
                                OutlinedButton(
                                    onClick = { save.launch(file.name) },
                                    enabled = !working,
                                    modifier = Modifier.weight(1f).testTag("export.save"),
                                ) {
                                    Icon(Icons.Outlined.Folder, null)
                                    Spacer(Modifier.width(Space.s))
                                    Text(
                                        stringResource(R.string.save_to_files),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            saved?.let {
                                StatusLine(
                                    it,
                                    icon = Icons.Outlined.CheckCircle,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
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
