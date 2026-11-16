@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.scanner

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
import gg.seven.scanner.core.PageSettings
import gg.seven.scanner.core.pageSettingsDefault
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    val settings = PageSettings(turns.toLong(), brightness, contrast, grayscale)
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun set(value: PageSettings) {
        turns = value.quarterTurns.toInt()
        brightness = value.brightness
        contrast = value.contrast
        grayscale = value.grayscale
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScannerTopBar("Page $number", onBack = nav::pop) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.m, vertical = Space.s), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = {
                            working = true
                            scope.launch {
                                failure = model.updatePage(document, pageId, settings)
                                working = false
                                if (failure == null) nav.pop()
                            }
                        },
                        enabled = settings != page.settings && !working,
                        modifier = Modifier.widthIn(max = ScannerMetrics.contentWidth).fillMaxWidth().heightIn(min = 56.dp).testTag("page.save"),
                    ) {
                        Text("Save page")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readable().padding(horizontal = Space.m, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            PageImage(
                model.repository.pageFile(document.id, page.id),
                settings,
                Modifier.fillMaxWidth().height(420.dp).semantics { contentDescription = "Page $number" },
                maxPixels = 1600,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = {
                    turns = (turns + 3) % 4
                    view.tick()
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("page.rotateLeft")) {
                    Icon(Icons.AutoMirrored.Outlined.RotateLeft, null)
                    Spacer(Modifier.width(Space.s))
                    Text("Rotate left", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = {
                    turns = (turns + 1) % 4
                    view.tick()
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("page.rotate")) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, null)
                    Spacer(Modifier.width(Space.s))
                    Text("Rotate right", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Section(title = "Adjust", footer = "Your original stays untouched. Save to apply these settings to previews, recognition and exports.") {
                switch("Grayscale", grayscale) { grayscale = it }
                content {
                    val shown = (brightness * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }
                    Adjustment("Brightness", shown, brightness.toFloat(), -0.5f..0.5f) { brightness = it }
                }
                content {
                    Adjustment("Contrast", "${(contrast * 100).roundToInt()}%", contrast.toFloat(), 0.5f..2f) { contrast = it }
                }
                content {
                    TextButton(onClick = { set(pageSettingsDefault()) }, enabled = settings != pageSettingsDefault(), modifier = Modifier.testTag("page.reset")) { Text("Reset to original") }
                }
            }
            ContentCard("Recognized text") {
                if (page.text.isEmpty()) Text("No recognized text yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    SelectionContainer { Text(page.text, style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis) }
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
private fun Adjustment(title: String, shown: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(shown, style = MaterialTheme.typography.bodyLarge.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            // Steps of 0.05 without tick marks; rounding in Double keeps stored values exact.
            onValueChange = { onChange((it * 20).roundToInt() / 20.0) },
            valueRange = range,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = shown
            }.testTag("page.$title"),
        )
    }
}
