@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.Section
import gg.seven.kit.Space
import gg.seven.kit.content
import gg.seven.kit.numeric
import gg.seven.kit.switch
import gg.seven.scanner.core.PageCrop
import gg.seven.scanner.core.PageSettings
import gg.seven.scanner.core.ScanPage
import gg.seven.scanner.core.pageSettingsDefault
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class TextCorrectionDraft(val original: ScanPage) {
    var text by mutableStateOf(original.text)
}

/**
 * One page, big, with the few adjustments the app offers under it: rotate, grayscale, brightness
 * and contrast. The original stays untouched; Save applies the settings and recognizes the page
 * again.
 */
@Composable
fun PageEditor(model: ScannerModel, documentId: UUID, pageId: UUID) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val documents by model.documents.collectAsStateWithLifecycle()
    val document = documents.firstOrNull { it.id == documentId }
    val index = document?.pages?.indexOfFirst { it.id == pageId } ?: -1
    if (document == null || index < 0) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val page = document.pages[index]
    val number = index + 1
    var turns by rememberSaveable(pageId) { mutableIntStateOf(page.settings.quarterTurns.toInt()) }
    var brightness by rememberSaveable(pageId) { mutableDoubleStateOf(page.settings.brightness) }
    var contrast by rememberSaveable(pageId) { mutableDoubleStateOf(page.settings.contrast) }
    var grayscale by rememberSaveable(pageId) { mutableStateOf(page.settings.grayscale) }
    var cropped by rememberSaveable(pageId) { mutableStateOf(page.settings.crop != null) }
    var cropLeft by
        rememberSaveable(pageId) { mutableDoubleStateOf(page.settings.crop?.left ?: 0.0) }
    var cropTop by rememberSaveable(pageId) { mutableDoubleStateOf(page.settings.crop?.top ?: 0.0) }
    var cropRight by
        rememberSaveable(pageId) { mutableDoubleStateOf(page.settings.crop?.right ?: 1.0) }
    var cropBottom by
        rememberSaveable(pageId) { mutableDoubleStateOf(page.settings.crop?.bottom ?: 1.0) }
    var editingCrop by rememberSaveable(pageId) { mutableStateOf(false) }
    val settings =
        PageSettings(
            turns.toLong(),
            brightness,
            contrast,
            grayscale,
            if (cropped) PageCrop(cropLeft, cropTop, cropRight, cropBottom) else null,
        )
    if (editingCrop) {
        CropEditor(
            model.repository.pageFile(document.id, page.id),
            settings,
            onClose = { editingCrop = false },
            onApply = { crop ->
                cropped = crop != null
                cropLeft = crop?.left ?: 0.0
                cropTop = crop?.top ?: 0.0
                cropRight = crop?.right ?: 1.0
                cropBottom = crop?.bottom ?: 1.0
                editingCrop = false
            },
        )
    }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var discarding by remember { mutableStateOf(false) }
    val annotationDraft by model.annotationDraft.collectAsStateWithLifecycle()
    annotationDraft
        ?.takeIf { it.original.id == pageId }
        ?.let { draft ->
            AnnotationEditor(
                model.repository.pageFile(documentId, pageId),
                draft,
                close = { model.annotationDraft.value = null },
                save = { model.saveAnnotations() },
            )
        }
    val correction by model.textCorrection.collectAsStateWithLifecycle()
    correction
        ?.takeIf { it.original.id == pageId }
        ?.let { draft ->
            gg.seven.kit.FullScreenForm(
                title = "Edit text",
                onClose = {
                    model.textCorrection.value = null
                    failure = null
                },
                busy = working,
                confirmDiscard = draft.text != draft.original.text,
                saveEnabled = draft.text != draft.original.text,
                onSave = {
                    working = true
                    failure = null
                    scope.launch {
                        try {
                            model.repository.io {
                                updatePageText(documentId, draft.original, draft.text)
                            }
                            model.reload()
                            model.textCorrection.value = null
                        } catch (e: Exception) {
                            failure =
                                describe(e) +
                                    " Your text is kept here. If the page changed, close and reopen this editor."
                        } finally {
                            working = false
                        }
                    }
                },
            ) {
                Text(
                    "Correct the saved text used by search and text sharing. The image stays unchanged. Searchable PDFs recognize the image again; they do not use these corrections."
                )
                OutlinedTextField(
                    draft.text,
                    { draft.text = it },
                    label = { Text("Recognized text") },
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .testTag("page.correctedText"),
                    enabled = !working,
                )
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    val close = {
        if (!working) {
            if (settings != page.settings) discarding = true else nav.pop()
        }
    }
    BackHandler { close() }
    if (discarding)
        gg.seven.kit.DiscardChangesDialog(
            { discarding = false },
            {
                discarding = false
                nav.pop()
            },
        )
    fun set(value: PageSettings) {
        turns = value.quarterTurns.toInt()
        brightness = value.brightness
        contrast = value.contrast
        grayscale = value.grayscale
        cropped = value.crop != null
        cropLeft = value.crop?.left ?: 0.0
        cropTop = value.crop?.top ?: 0.0
        cropRight = value.crop?.right ?: 1.0
        cropBottom = value.crop?.bottom ?: 1.0
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScannerTopBar("Page $number", onBack = close) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Space.m, vertical = Space.s),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        shapes = ButtonDefaults.shapesFor(56.dp),
                        onClick = {
                            working = true
                            scope.launch {
                                failure = model.updatePage(document, pageId, settings)
                                working = false
                                if (failure == null) nav.pop()
                            }
                        },
                        enabled = settings != page.settings && !working,
                        modifier =
                            Modifier.widthIn(max = ScannerMetrics.contentWidth)
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .testTag("page.save"),
                    ) {
                        Text("Save page")
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
            PageImage(
                model.repository.pageFile(document.id, page.id),
                settings,
                Modifier.fillMaxWidth().height(420.dp).semantics {
                    contentDescription = "Page $number"
                },
                maxPixels = 1600,
                annotations = page.annotations,
                ink = page.ink,
            )
            OutlinedButton(
                onClick = { editingCrop = true },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag("page.crop"),
            ) {
                Text(if (cropped) "Adjust crop" else "Crop page")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            val latest =
                                model.repository
                                    .io { load(documentId) }
                                    .pages
                                    .first { it.id == pageId }
                            model.annotationDraft.value = AnnotationDraft(documentId, latest)
                        } catch (e: Exception) {
                            failure = describe(e)
                        }
                    }
                },
                enabled = !working && settings == page.settings,
                modifier = Modifier.fillMaxWidth().testTag("page.annotate"),
            ) {
                val count = page.annotations.size + page.ink.size
                Text(pluralStringResource(R.plurals.annotate, count, count))
            }
            if (settings != page.settings)
                Text(
                    "Save image adjustments before annotating.",
                    style = MaterialTheme.typography.bodySmall,
                )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                OutlinedButton(
                    onClick = {
                        turns = (turns + 3) % 4
                        view.tick()
                    },
                    enabled = !working,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("page.rotateLeft"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.RotateLeft, null)
                    Spacer(Modifier.width(Space.s))
                    Text("Rotate left")
                }
                OutlinedButton(
                    onClick = {
                        turns = (turns + 1) % 4
                        view.tick()
                    },
                    enabled = !working,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("page.rotate"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, null)
                    Spacer(Modifier.width(Space.s))
                    Text("Rotate right")
                }
            }
            Section(
                title = "Adjust",
                footer =
                    "Your original stays untouched. Save to apply these settings to previews, recognition and exports.",
            ) {
                switch("Grayscale", grayscale) { grayscale = it }
                content {
                    val shown =
                        (brightness * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }
                    Adjustment("Brightness", shown, brightness.toFloat(), -0.5f..0.5f) {
                        brightness = it
                    }
                }
                content {
                    Adjustment(
                        "Contrast",
                        "${(contrast * 100).roundToInt()}%",
                        contrast.toFloat(),
                        0.5f..2f,
                    ) {
                        contrast = it
                    }
                }
                content {
                    TextButton(
                        onClick = { set(pageSettingsDefault()) },
                        enabled = settings != pageSettingsDefault(),
                        modifier = Modifier.testTag("page.reset"),
                    ) {
                        Text("Reset to original")
                    }
                }
            }
            ContentCard("Recognized text") {
                OutlinedButton(
                    // The latest saved page, not this screen's copy: recognition may have finished.
                    onClick = {
                        scope.launch {
                            try {
                                val latest =
                                    model.repository
                                        .io { load(documentId) }
                                        .pages
                                        .firstOrNull { it.id == pageId }
                                        ?: throw gg.seven.scanner.core.ScanException.MissingPage()
                                model.textCorrection.value = TextCorrectionDraft(latest)
                            } catch (e: Exception) {
                                failure = describe(e)
                            }
                        }
                    },
                    enabled = !working && settings == page.settings,
                    modifier = Modifier.testTag("page.editText"),
                ) {
                    Text("Edit text")
                }
                if (settings != page.settings)
                    Text(
                        "Save image adjustments before editing text.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (page.text.isEmpty())
                    Text(
                        "No recognized text yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else {
                    SelectionContainer {
                        Text(
                            page.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { shareText(context, page.text) }) {
                        Icon(Icons.Outlined.Share, null)
                        Spacer(Modifier.width(Space.s))
                        Text("Share text")
                    }
                }
            }
            failure?.let { StatusLine(it) }
        }
    }
}

/** A slider with its name and current value on one line above it, in 0.05 steps. */
@Composable
private fun Adjustment(
    title: String,
    shown: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                shown,
                style = MaterialTheme.typography.bodyLarge.numeric(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val slider =
            rememberSliderState(
                value,
                steps = ((range.endInclusive - range.start) * 20).roundToInt() - 1,
                trackRange = range,
            )
        slider.value = value
        Slider(
            state = slider,
            // Native discrete steps work with touch, arrow keys and accessibility increment
            // actions.
            onValueChange = { onChange((it * 20).roundToInt() / 20.0) },
            modifier =
                Modifier.semantics {
                        contentDescription = title
                        stateDescription = shown
                    }
                    .testTag("page.$title"),
        )
    }
}
