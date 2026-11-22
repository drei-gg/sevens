package gg.seven.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import gg.seven.scanner.core.*

internal val InkColor.title
    @StringRes
    get() =
        when (this) {
            InkColor.BLACK -> R.string.ink_black
            InkColor.RED -> R.string.ink_red
            InkColor.BLUE -> R.string.ink_blue
        }
internal val InkColor.argb
    get() =
        when (this) {
            InkColor.BLACK -> 0xFF171717.toInt()
            InkColor.RED -> 0xFFC82323.toInt()
            InkColor.BLUE -> 0xFF1E50D2.toInt()
        }

/** One final renderer for native editor handoff, thumbnails and exported page pixels. */
internal fun Canvas.drawPageInk(
    ink: List<PageInk>,
    settings: PageSettings,
    pageWidth: Float = width.toFloat(),
    pageHeight: Float = height.toFloat(),
) {
    if (pageWidth <= 0 || pageHeight <= 0) return
    for (stroke in ink) {
        val drawing = inkDisplay(stroke, settings, pageWidth.toDouble(), pageHeight.toDouble())
        withSave {
            clipRect(0f, 0f, pageWidth, pageHeight)
            val r = drawing.clip
            clipRect(
                r.x.toFloat(),
                r.y.toFloat(),
                (r.x + r.width).toFloat(),
                (r.y + r.height).toFloat(),
            )
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = stroke.color.argb
                    style = Paint.Style.STROKE
                    strokeWidth = drawing.width.toFloat()
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
            val first = drawing.points.first()
            if (drawing.points.size == 1) {
                paint.style = Paint.Style.FILL
                drawCircle(first.x.toFloat(), first.y.toFloat(), paint.strokeWidth / 2, paint)
            } else {
                val path =
                    Path().apply {
                        moveTo(first.x.toFloat(), first.y.toFloat())
                        drawing.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                    }
                drawPath(path, paint)
            }
        }
    }
}

@Composable
internal fun InkAuthoring(draft: AnnotationDraft, modifier: Modifier) {
    val width = with(LocalDensity.current) { draft.inkWidth.toFloat() * density }
    AndroidView(
        modifier = modifier.testTag("ink-canvas"),
        factory = { InkSurface(it).also { view -> view.update(draft, width) } },
        update = { it.update(draft, width) },
        onRelease = { it.release() },
    )
}

/** Native Ink owns the active stroke. The portable path is handed off in the same UI frame. */
private class InkSurface(context: Context) : FrameLayout(context) {
    private lateinit var draft: AnnotationDraft
    private val strokes = InProgressStrokesView(context)
    private val finished =
        object : View(context) {
            override fun onDraw(canvas: Canvas) {
                if (!::draft.isInitialized) return
                canvas.drawPageInk(
                    draft.ink,
                    draft.original.settings,
                    width.toFloat(),
                    height.toFloat(),
                )
            }
        }
    private var brushWidth = 4f
    private var pointer: Int? = null
    private var released = false

    init {
        clipChildren = true
        addView(finished, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(strokes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = context.getString(R.string.ink_canvas)
        strokes.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    if (!released && width > 0 && height > 0) {
                        for (stroke in strokes.values) {
                            try {
                                // Bounded extraction; never silently truncate a long stroke.
                                if (stroke.inputs.size > 8192) error("Stroke too long")
                                val points =
                                    (0 until stroke.inputs.size).map {
                                        val p = stroke.inputs[it]
                                        InkPoint(p.x.toDouble() / width, p.y.toDouble() / height)
                                    }
                                val color =
                                    InkColor.entries.first { it.argb == stroke.brush.colorIntArgb }
                                draft.addInk(
                                    inkFromDisplay(
                                        points,
                                        stroke.brush.size.toDouble(),
                                        width.toDouble(),
                                        height.toDouble(),
                                        draft.original.settings,
                                        color,
                                    )
                                )
                            } catch (_: Exception) {
                                draft.failure = Words { it.getString(R.string.ink_stroke_rejected) }
                            }
                        }
                        finished.invalidate()
                        draft.inking = false
                    }
                    this@InkSurface.strokes.removeFinishedStrokes(strokes.keys)
                }
            }
        )
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (released || !::draft.isInitialized || draft.working) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointer = event.getPointerId(0)
                draft.inking = true
                strokes.startStroke(
                    event,
                    pointer!!,
                    Brush.createWithColorIntArgb(
                        StockBrushes.marker(StockBrushes.MarkerVersion.V1),
                        draft.inkColor.argb,
                        brushWidth,
                        0.1f,
                    ),
                )
            }
            MotionEvent.ACTION_MOVE -> pointer?.let { strokes.addToStroke(event, it) }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) {
                    pointer?.let { strokes.finishStroke(event, it) }
                    pointer = null
                }
            }
            MotionEvent.ACTION_UP -> {
                pointer?.let { strokes.finishStroke(event, it) }
                pointer = null
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                strokes.cancelUnfinishedStrokes()
                pointer = null
                draft.inking = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun update(value: AnnotationDraft, width: Float) {
        draft = value
        brushWidth = width
        finished.invalidate()
    }

    fun release() {
        released = true
        strokes.cancelUnfinishedStrokes()
        strokes.clearFinishedStrokesListeners()
        draft.inking = false
    }
}
