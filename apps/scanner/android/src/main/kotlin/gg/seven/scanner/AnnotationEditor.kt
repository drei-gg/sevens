package gg.seven.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.seven.kit.ConnectedChoice
import gg.seven.kit.FullScreenForm
import gg.seven.kit.Space
import gg.seven.scanner.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kept in the ViewModel across recreation; no save can silently adopt a newer page geometry. */
class AnnotationDraft(val documentID: UUID, val original: ScanPage) {
    var marks by mutableStateOf(original.annotations)
    var style by mutableStateOf(AnnotationStyle.HIGHLIGHT)
    var working by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var ink by mutableStateOf(original.ink)
    var inkMode by mutableStateOf(false)
    var inkColor by mutableStateOf(InkColor.BLACK)
    var inkWidth by mutableIntStateOf(4)
    var inking by mutableStateOf(false)

    private data class Snapshot(val marks: List<PageAnnotation>, val ink: List<PageInk>)

    private val undo = mutableStateListOf<Snapshot>()
    private val redo = mutableStateListOf<Snapshot>()
    val dirty
        get() = marks != original.annotations || ink != original.ink

    val canUndo
        get() = undo.isNotEmpty()

    val canRedo
        get() = redo.isNotEmpty()

    private fun snapshot() = Snapshot(marks, ink)

    private fun restore(value: Snapshot) {
        marks = value.marks
        ink = value.ink
    }

    /** Validates in the core first; a rejected change leaves the draft and history as they were. */
    private fun commit(next: List<PageAnnotation>, nextInk: List<PageInk>) {
        annotationsValidate(next)
        inkValidate(nextInk)
        undo.add(snapshot())
        if (undo.size > 100) undo.removeAt(0)
        redo.clear()
        marks = next
        ink = nextInk
    }

    /** Applies an edit, or explains why the core refused it. Never throws into a gesture. */
    fun change(next: List<PageAnnotation>, nextInk: List<PageInk> = ink): Boolean =
        try {
            commit(next, nextInk)
            true
        } catch (e: Exception) {
            failure = describe(e)
            false
        }

    fun undo() {
        if (canUndo) {
            redo.add(snapshot())
            restore(undo.removeAt(undo.lastIndex))
        }
    }

    fun redo() {
        if (canRedo) {
            undo.add(snapshot())
            restore(redo.removeAt(redo.lastIndex))
        }
    }

    fun addInk(stroke: PageInk) {
        try {
            commit(marks, ink + stroke)
        } catch (_: Exception) {
            failure =
                "This stroke exceeds the page’s ink limit. Your previous marks are kept. Undo or clear ink to make room."
        }
    }

    /** A rectangle drawn or entered against the displayed page, from a drag end or exact bounds. */
    fun add(bounds: PageCrop) {
        if (marks.size >= 100) {
            failure = "This page already has 100 marks. Undo or remove a mark first."
            return
        }
        try {
            commit(marks + annotationFromDisplay(bounds, original.settings, style), ink)
        } catch (e: Exception) {
            failure = describe(e)
        }
    }
}

internal val AnnotationStyle.title
    get() =
        when (this) {
            AnnotationStyle.HIGHLIGHT -> "Highlight"
            AnnotationStyle.RED_OUTLINE -> "Red box"
            AnnotationStyle.BLUE_OUTLINE -> "Blue box"
        }
internal val AnnotationStyle.ink
    get() =
        when (this) {
            AnnotationStyle.HIGHLIGHT -> Color(0x52FFD000)
            AnnotationStyle.RED_OUTLINE -> Color(0xFFC82323)
            AnnotationStyle.BLUE_OUTLINE -> Color(0xFF1E50D2)
        }

@Composable
fun AnnotationEditor(file: File, draft: AnnotationDraft, close: () -> Unit, save: () -> Unit) {
    var image by remember(file, draft.original.settings) { mutableStateOf<ImageBitmap?>(null) }
    var exact by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(file, draft.original.settings) {
        try {
            image =
                withContext(Dispatchers.Default) {
                    Processing.rendered(file, draft.original.settings, 1600).asImageBitmap()
                }
        } catch (e: Exception) {
            draft.failure = describe(e)
        }
    }
    FullScreenForm(
        "Annotate",
        close,
        save,
        busy = draft.working,
        saveEnabled = image != null && draft.dirty && !draft.inking,
        confirmDiscard = draft.dirty || draft.inking,
    ) {
        Text(
            "Choose Ink to draw, or Rectangle for a highlight or box. Visual marks keep the original and searchable text; they do not redact information."
        )
        ConnectedChoice(listOf(false to "Rectangle", true to "Ink"), draft.inkMode) {
            draft.inkMode = it
        }
        if (draft.inkMode) {
            ConnectedChoice(InkColor.entries.map { it to it.title }, draft.inkColor) {
                draft.inkColor = it
            }
            ConnectedChoice(listOf(2 to "Fine", 4 to "Medium", 8 to "Bold"), draft.inkWidth) {
                draft.inkWidth = it
            }
            Text(
                "Draw with a finger or stylus. Undo removes the last change; ink does not erase or redact the page.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else
            ConnectedChoice(AnnotationStyle.entries.map { it to it.title }, draft.style) {
                draft.style = it
            }
        image?.let { source -> AnnotationCanvas(source, draft) }
        Text("${draft.marks.size} of 100 marks", Modifier.testTag("annotations-count"))
        Text("${draft.ink.size} of 100 ink strokes", Modifier.testTag("ink-count"))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            OutlinedButton(
                onClick = { draft.undo() },
                enabled = draft.canUndo,
                modifier = Modifier.testTag("annotations-undo"),
            ) {
                Text("Undo")
            }
            OutlinedButton(
                onClick = { draft.redo() },
                enabled = draft.canRedo,
                modifier = Modifier.testTag("annotations-redo"),
            ) {
                Text("Redo")
            }
            OutlinedButton(
                onClick = { exact = true },
                enabled = draft.marks.size < 100 && image != null,
                modifier = Modifier.testTag("annotations-exact"),
            ) {
                Text("Add with bounds")
            }
        }
        if (draft.ink.isNotEmpty()) {
            TextButton(
                onClick = { draft.change(draft.marks, draft.ink.dropLast(1)) },
                modifier = Modifier.testTag("ink-remove"),
            ) {
                Text("Remove last stroke")
            }
            TextButton(
                onClick = { draft.change(draft.marks, emptyList()) },
                modifier = Modifier.testTag("ink-clear"),
            ) {
                Text("Clear ink")
            }
        }
        if (draft.marks.isNotEmpty()) {
            TextButton(
                onClick = { draft.change(draft.marks.dropLast(1)) },
                modifier = Modifier.testTag("annotations-remove"),
            ) {
                Text("Remove last mark")
            }
            TextButton(
                onClick = { draft.change(emptyList()) },
                modifier = Modifier.testTag("annotations-clear"),
            ) {
                Text("Clear all marks")
            }
        }
        Text(
            "Marks follow the original page through crop and rotation. Cropping hides marks outside the visible area; resetting the crop brings them back.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    draft.failure?.let { message ->
        AlertDialog(
            onDismissRequest = { draft.failure = null },
            title = { Text("Marks not saved") },
            text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { draft.failure = null }) { Text("Keep editing") }
            },
        )
    }

    if (exact)
        CropEdges(PageCrop(0.1, 0.1, 0.9, 0.3), { exact = false }) {
            draft.add(it)
            exact = false
        }
}

@Composable
private fun AnnotationCanvas(image: ImageBitmap, draft: AnnotationDraft) {
    // The canvas is exactly the fitted image: letterboxed margins are never annotation coordinates.
    val windowHeight =
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maximumHeight = minOf(340.dp, windowHeight * 0.48f)
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = maximumHeight)) {
        val ratio = image.width.toFloat() / image.height
        val width = minOf(maxWidth, maximumHeight * ratio)
        val height = width / ratio
        var start by remember { mutableStateOf<Offset?>(null) }
        var end by remember { mutableStateOf<Offset?>(null) }
        Box(Modifier.size(width, height).align(androidx.compose.ui.Alignment.Center)) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(
                Modifier.fillMaxSize()
                    .testTag("annotations-canvas")
                    .semantics {
                        contentDescription =
                            "Page annotation canvas. Use Add with bounds as an alternative to dragging."
                    }
                    .pointerInput(draft, draft.style, draft.inkMode) {
                        fun normalized(p: Offset) =
                            Offset(
                                (p.x / size.width).coerceIn(0f, 1f),
                                (p.y / size.height).coerceIn(0f, 1f),
                            )
                        if (draft.inkMode) return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                start = normalized(it)
                                end = start
                            },
                            onDragCancel = {
                                start = null
                                end = null
                            },
                            onDragEnd = {
                                val a = start
                                val b = end
                                if (
                                    a != null &&
                                        b != null &&
                                        kotlin.math.abs(a.x - b.x) >= 0.005f &&
                                        kotlin.math.abs(a.y - b.y) >= 0.005f
                                ) {
                                    draft.add(
                                        PageCrop(
                                            minOf(a.x, b.x).toDouble(),
                                            minOf(a.y, b.y).toDouble(),
                                            maxOf(a.x, b.x).toDouble(),
                                            maxOf(a.y, b.y).toDouble(),
                                        )
                                    )
                                }
                                start = null
                                end = null
                            },
                        ) { change, _ ->
                            change.consume()
                            end = normalized(change.position)
                        }
                    }
            ) {
                clipRect {
                    for (mark in draft.marks) {
                        val r = annotationDisplayBounds(mark.bounds, draft.original.settings)
                        drawRect(
                            mark.style.ink,
                            Offset((r.x * size.width).toFloat(), (r.y * size.height).toFloat()),
                            Size(
                                (r.width * size.width).toFloat(),
                                (r.height * size.height).toFloat(),
                            ),
                            style =
                                if (mark.style == AnnotationStyle.HIGHLIGHT)
                                    androidx.compose.ui.graphics.drawscope.Fill
                                else Stroke(size.minDimension * 0.004f),
                        )
                    }
                    val a = start
                    val b = end
                    if (a != null && b != null)
                        drawRect(
                            Color.Black,
                            Offset(minOf(a.x, b.x) * size.width, minOf(a.y, b.y) * size.height),
                            Size(
                                kotlin.math.abs(a.x - b.x) * size.width,
                                kotlin.math.abs(a.y - b.y) * size.height,
                            ),
                            style = Stroke(2.dp.toPx()),
                        )
                }
            }
            if (draft.inkMode) InkAuthoring(draft, Modifier.fillMaxSize())
            else
                Canvas(Modifier.fillMaxSize()) {
                    drawContext.canvas.nativeCanvas.drawPageInk(
                        draft.ink,
                        draft.original.settings,
                        size.width,
                        size.height,
                    )
                }
        }
    }
}
