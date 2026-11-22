package gg.seven.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import gg.seven.kit.FullScreenForm
import gg.seven.scanner.core.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native crop handles with Material actions and an exact, keyboard-accessible alternative. */
@Composable
fun CropEditor(
    file: File,
    settings: PageSettings,
    onClose: () -> Unit,
    onApply: (PageCrop?) -> Unit,
) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val canvasHeight = minOf(320.dp, with(LocalDensity.current) { windowHeight.toDp() } * 0.45f)
    val whole = PageCrop(0.0, 0.0, 1.0, 1.0)
    val initial =
        remember(file, settings) {
            pageCropRotated(settings.crop ?: whole, settings.quarterTurns.toUInt())
        }
    var left by rememberSaveable { mutableDoubleStateOf(initial.left) }
    var top by rememberSaveable { mutableDoubleStateOf(initial.top) }
    var right by rememberSaveable { mutableDoubleStateOf(initial.right) }
    var bottom by rememberSaveable { mutableDoubleStateOf(initial.bottom) }
    var revision by remember { mutableIntStateOf(0) }
    var exact by rememberSaveable { mutableStateOf(false) }
    val crop = PageCrop(left, top, right, bottom)
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file, settings.quarterTurns) {
        var rendered: Bitmap? = null
        try {
            withContext(Dispatchers.Default) {
                rendered = Processing.rendered(file, settings.copy(crop = null), 1600)
            }
            bitmap = rendered
            rendered = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            failure = describe(e)
        } finally {
            rendered?.recycle()
        }
    }
    DisposableEffect(bitmap) {
        val owned = bitmap
        onDispose { owned?.recycle() }
    }
    fun set(value: PageCrop) {
        left = value.left
        top = value.top
        right = value.right
        bottom = value.bottom
    }
    val valid = runCatching { pageSettingsValidate(settings.copy(crop = crop)) }.isSuccess
    FullScreenForm(
        title = "Crop",
        onClose = onClose,
        confirmDiscard = crop != initial,
        saveLabel = "Use crop",
        saveEnabled = valid && bitmap != null,
        onSave = {
            val original = pageCropRotated(crop, (4 - settings.quarterTurns).toUInt())
            onApply(original.takeUnless { it == whole })
        },
    ) {
        Text(
            "Drag the edges or enter exact bounds. Your original stays intact. Save the page to update recognition and exports."
        )
        bitmap?.let { source ->
            key(revision) {
                AndroidView(
                    factory = { context ->
                        CropImageView(context).apply {
                            // Scans are usually white paper: the library's white handles disappear
                            // on it.
                            setImageCropOptions(
                                CropImageOptions(
                                    autoZoomEnabled = false,
                                    guidelines = CropImageView.Guidelines.ON,
                                    borderLineColor = android.graphics.Color.BLACK,
                                    borderLineThickness = resources.displayMetrics.density,
                                    borderCornerColor = android.graphics.Color.BLACK,
                                    guidelinesColor = android.graphics.Color.GRAY,
                                    minCropResultWidth = 1,
                                    minCropResultHeight = 1,
                                    minCropWindowWidth = 1,
                                    minCropWindowHeight = 1,
                                )
                            )
                            isAutoZoomEnabled = false
                            guidelines = CropImageView.Guidelines.ON
                            setImageBitmap(source)
                            val box =
                                pageCropPixels(crop, source.width.toUInt(), source.height.toUInt())
                            cropRect =
                                Rect(
                                    box.x.toInt(),
                                    box.y.toInt(),
                                    (box.x + box.width).toInt(),
                                    (box.y + box.height).toInt(),
                                )
                            var userMoved = false
                            setOnSetCropOverlayMovedListener { userMoved = true }
                            setOnSetCropOverlayReleasedListener { rect ->
                                // Initial layout can round pixel bounds; it is not a user edit.
                                if (userMoved)
                                    rect?.let { r ->
                                        set(
                                            PageCrop(
                                                r.left.toDouble() / source.width,
                                                r.top.toDouble() / source.height,
                                                r.right.toDouble() / source.width,
                                                r.bottom.toDouble() / source.height,
                                            )
                                        )
                                    }
                                userMoved = false
                            }
                        }
                    },
                    onRelease = {
                        it.setOnSetCropOverlayMovedListener(null)
                        it.setOnSetCropOverlayReleasedListener(null)
                        it.setImageBitmap(null)
                    },
                    modifier =
                        Modifier.fillMaxWidth().height(canvasHeight).testTag("page.cropCanvas"),
                )
            }
        } ?: if (failure == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Unit
        OutlinedButton(
            onClick = { exact = true },
            modifier = Modifier.fillMaxWidth().testTag("page.cropBounds"),
        ) {
            Text("Enter bounds")
        }
        TextButton(
            onClick = {
                set(whole)
                revision++
            },
            modifier = Modifier.testTag("page.cropReset"),
        ) {
            Text("Use full page")
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (exact)
        CropEdges(
            crop,
            onClose = { exact = false },
            onApply = {
                set(it)
                revision++
                exact = false
            },
        )
}

@Composable
internal fun CropEdges(initial: PageCrop, onClose: () -> Unit, onApply: (PageCrop) -> Unit) {
    fun percent(value: Double) =
        java.math.BigDecimal.valueOf(value * 100)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    var left by rememberSaveable { mutableStateOf(percent(initial.left)) }
    var top by rememberSaveable { mutableStateOf(percent(initial.top)) }
    var right by rememberSaveable { mutableStateOf(percent(initial.right)) }
    var bottom by rememberSaveable { mutableStateOf(percent(initial.bottom)) }
    fun number(value: String) = value.replace(',', '.').toDoubleOrNull()?.div(100)
    val value =
        runCatching {
                PageCrop(
                        requireNotNull(number(left)),
                        requireNotNull(number(top)),
                        requireNotNull(number(right)),
                        requireNotNull(number(bottom)),
                    )
                    .also { pageSettingsValidate(pageSettingsDefault().copy(crop = it)) }
            }
            .getOrNull()
    FullScreenForm(
        title = "Bounds",
        onClose = onClose,
        saveLabel = "Apply",
        saveEnabled = value != null,
        onSave = { value?.let(onApply) },
        confirmDiscard =
            listOf(left, top, right, bottom) !=
                listOf(initial.left, initial.top, initial.right, initial.bottom).map(::percent),
    ) {
        Text(
            "Percent of the displayed page, measured from its top-left corner. Right must exceed left; bottom must exceed top."
        )
        listOf(
                Triple("Left", left, { s: String -> left = s }),
                Triple("Top", top, { s: String -> top = s }),
                Triple("Right", right, { s: String -> right = s }),
                Triple("Bottom", bottom, { s: String -> bottom = s }),
            )
            .forEach { (label, text, change) ->
                OutlinedTextField(
                    text,
                    change,
                    label = { Text("$label (%)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("page.crop$label"),
                )
            }
        if (value == null)
            Text(
                "Enter bounds from 0 to 100 with a non-empty crop.",
                color = MaterialTheme.colorScheme.error,
            )
    }
}
