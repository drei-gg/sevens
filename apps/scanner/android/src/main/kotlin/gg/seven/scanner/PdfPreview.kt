@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package gg.seven.scanner

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gg.seven.kit.Space
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The PDF as it will look, scrolling continuously, with Share beside Close (iOS PDFPreviewSheet). */
@Composable
fun PdfPreview(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val renderer = remember(file) { runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    val lock = remember { Mutex() }
    // PdfRenderer opens one page at a time: sizes are read once, up front, before any page renders.
    val aspects = remember(renderer) { (0 until (renderer?.pageCount ?: 0)).map { i -> renderer!!.openPage(i).use { it.width.toFloat() / it.height } } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                TopAppBar(
                    title = { Text("PDF preview") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") } },
                    actions = { IconButton(onClick = { shareFile(context, file, "application/pdf") }) { Icon(Icons.Outlined.Share, "Share PDF") } },
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val width = with(LocalDensity.current) { (maxWidth - Space.m * 2).roundToPx() }.coerceIn(200, 2000)
                LazyColumn(
                    Modifier.fillMaxSize().testTag("pdf.preview"),
                    contentPadding = PaddingValues(Space.m),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    val count = aspects.size
                    items(count) { index ->
                        var image by remember(index, width) { mutableStateOf<ImageBitmap?>(null) }
                        val aspect = aspects[index]
                        LaunchedEffect(index, width) {
                            image = withContext(Dispatchers.IO) {
                                lock.withLock {
                                    renderer!!.openPage(index).use { page ->
                                        val bitmap = createBitmap(width, (width / aspect).toInt().coerceAtLeast(1))
                                        bitmap.eraseColor(AndroidColor.WHITE)
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        bitmap.asImageBitmap()
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(MaterialTheme.colorScheme.surfaceContainerLowest).semantics { contentDescription = "Page ${index + 1} of $count" }) {
                            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                    }
                }
            }
        }
    }
}
