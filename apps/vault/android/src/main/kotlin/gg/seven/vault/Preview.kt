@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.vault

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import gg.seven.kit.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * iOS `VaultPreview` (Quick Look) on Android: Vault's own viewers, in the app, over the temporary
 * decrypted copy, so nothing is handed to another app to look at it. Images zoom (Telephoto), video
 * and audio play (Media3), PDFs render (PdfRenderer), text reads. Anything else says so and offers
 * export. The copy is removed when the preview closes or the vault locks.
 */
@Composable
fun PreviewScreen(model: VaultModel, shown: Presentation.Preview) {
    val item = shown.item
    val file = shown.file
    Dialog(
        onDismissRequest = model::cleanPresentation,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = model::cleanPresentation) {
                            Icon(Icons.Outlined.Close, "Done")
                        }
                    },
                    actions = {
                        IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, "Lock") }
                        IconButton(onClick = { model.export(setOf(item.id)) }) {
                            Icon(Icons.Outlined.Share, "Export original")
                        }
                    },
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    Types.isImage(item.contentType, item.name) ->
                        ImagePreview(file) { Unsupported(model, item.id) }
                    Types.isVideo(item.contentType, item.name) ||
                        Types.isAudio(item.contentType, item.name) -> MediaPreview(file)
                    Types.isPdf(item.contentType, item.name) ->
                        PdfPreview(file) { Unsupported(model, item.id) }
                    Types.isText(item.contentType, item.name) -> TextPreview(file)
                    else -> Unsupported(model, item.id)
                }
            }
        }
    }
}

@Composable
private fun Unsupported(model: VaultModel, id: java.util.UUID) {
    EmptyState(
        "No preview for this kind of file",
        "Export the original to open it in another app. The exported copy is no longer protected by Vault.",
        icon = Icons.Outlined.InsertDriveFile,
        action = "Export original" to { model.export(setOf(id)) },
    )
}

@Composable
private fun ImagePreview(file: File, fallback: @Composable () -> Unit) {
    var state by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val decoded = withContext(Dispatchers.IO) { Images.decode(file, 4096) }
        if (decoded == null) failed = true else state = decoded
    }
    val bitmap = state
    when {
        failed -> fallback()
        bitmap == null -> LoadingIndicator()
        else -> {
            val zoom = rememberZoomableState()
            LaunchedEffect(bitmap) {
                zoom.setContentLocation(
                    ZoomableContentLocation.scaledInsideAndCenterAligned(
                        Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                    )
                )
            }
            Image(
                bitmap.asImageBitmap(),
                null,
                Modifier.fillMaxSize().zoomable(zoom),
                contentScale = ContentScale.Inside,
            )
        }
    }
}

@AndroidOptIn(UnstableApi::class)
@Composable
private fun MediaPreview(file: File) {
    val context = LocalContext.current
    val player =
        remember(file) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
        }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        { PlayerView(it).apply { this.player = player } },
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),
    )
}

@Composable
private fun PdfPreview(file: File, fallback: @Composable () -> Unit) {
    var count by remember(file) { mutableStateOf<Int?>(null) }
    LaunchedEffect(file) {
        count =
            withContext(Dispatchers.IO) {
                runCatching {
                        PdfRenderer(
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            )
                            .use { it.pageCount }
                    }
                    .getOrDefault(-1)
            }
    }
    when (val pages = count) {
        null -> LoadingIndicator()
        -1,
        0 -> fallback()
        else ->
            LazyColumn(
                Modifier.fillMaxSize().testTag("vault.pdf.pages"),
                contentPadding = PaddingValues(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                // Render only visible pages. Opening a long PDF must not allocate every page or
                // silently stop at page 50.
                items(pages, key = { it }) { index ->
                    var bitmap by remember(file, index) { mutableStateOf<Bitmap?>(null) }
                    var failed by remember(file, index) { mutableStateOf(false) }
                    LaunchedEffect(file, index) {
                        val rendered =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                        PdfRenderer(
                                                ParcelFileDescriptor.open(
                                                    file,
                                                    ParcelFileDescriptor.MODE_READ_ONLY,
                                                )
                                            )
                                            .use { pdf ->
                                                pdf.openPage(index).use { page ->
                                                    val scale =
                                                        1600f / maxOf(page.width, page.height)
                                                    createBitmap(
                                                            (page.width * scale)
                                                                .toInt()
                                                                .coerceAtLeast(1),
                                                            (page.height * scale)
                                                                .toInt()
                                                                .coerceAtLeast(1),
                                                        )
                                                        .also {
                                                            it.eraseColor(
                                                                android.graphics.Color.WHITE
                                                            )
                                                            page.render(
                                                                it,
                                                                null,
                                                                null,
                                                                PdfRenderer.Page
                                                                    .RENDER_MODE_FOR_DISPLAY,
                                                            )
                                                        }
                                                }
                                            }
                                    }
                                    .getOrNull()
                            }
                        if (rendered == null) failed = true else bitmap = rendered
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(
                            "Page ${index + 1} of $pages",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val shown = bitmap
                        if (shown != null)
                            Image(
                                shown.asImageBitmap(),
                                "Page ${index + 1}",
                                Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                            )
                        else
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(0.7f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (failed) Text("This page could not be displayed.")
                                else LoadingIndicator()
                            }
                    }
                }
            }
    }
}

@Composable
private fun TextPreview(file: File) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        text =
            withContext(Dispatchers.IO) {
                runCatching {
                        file.inputStream().use {
                            String(it.readNBytesCompat(1_000_000), Charsets.UTF_8)
                        }
                    }
                    .getOrElse { "This text file could not be read." }
            }
    }
    val shown = text
    if (shown == null) LoadingIndicator()
    else
        SelectionContainer(Modifier.fillMaxSize()) {
            Text(
                (if (file.length() > 1_000_000)
                    "Preview shows the first 1 MB. Export the original to read the complete file.\n\n"
                else "") + shown,
                Modifier.verticalScroll(rememberScrollState())
                    .padding(Space.m)
                    .widthIn(max = ContentWidth),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(65_536)
    while (out.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private fun Context.shareUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.files", file)

/** Prepared originals or a backup: save them with the system's document picker, or share them. */
@Composable
fun FilesReadySheet(model: VaultModel, shown: Presentation.Files) {
    val context = LocalContext.current
    val files = shown.files
    val one = files.singleOrNull()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val saveOne =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                if (shown.backup) "application/octet-stream" else "*/*"
            )
        ) { uri ->
            model.endSystemFlow()
            if (uri != null && one != null) {
                saving = true
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                (context.contentResolver.openOutputStream(uri, "w")
                                        ?: error("No output stream"))
                                    .use { out ->
                                        one.inputStream().use { it.copyTo(out, 1 shl 20) }
                                    }
                            }
                        }
                    saving = false
                    result
                        .onSuccess {
                            model.showNotice(
                                if (shown.backup) "Encrypted backup saved." else "Original saved."
                            )
                        }
                        .onFailure {
                            model.error = "The file could not be saved there. ${it.vaultMessage}"
                        }
                }
            }
        }
    val saveMany =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            model.endSystemFlow()
            val folder =
                tree?.let { DocumentFile.fromTreeUri(context, it) }
                    ?: return@rememberLauncherForActivityResult
            saving = true
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        files.count { file ->
                            runCatching {
                                    val target =
                                        folder.createFile(
                                            Types.mime(
                                                Types.identifier(null, file.name),
                                                file.name,
                                            ),
                                            file.name,
                                        ) ?: error("Could not create file")
                                    try {
                                        (context.contentResolver.openOutputStream(target.uri, "w")
                                                ?: error("No output stream"))
                                            .use { out ->
                                                file.inputStream().use { it.copyTo(out, 1 shl 20) }
                                            }
                                    } catch (error: Exception) {
                                        target.delete()
                                        throw error
                                    }
                                }
                                .isSuccess
                        }
                    }
                saving = false
                if (saved == files.size) model.showNotice("$saved originals saved.")
                else model.error = "${files.size - saved} originals could not be saved there."
            }
        }
    ModalBottomSheet(
        onDismissRequest = { if (!saving) model.cleanPresentation() },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l)
                .padding(bottom = Space.l)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                if (shown.backup) "Encrypted backup ready"
                else if (one != null) "Original ready" else "${files.size} originals ready",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (shown.backup)
                    "Save the .vault7 file outside this app, then test a restore on another device. It opens only with this vault’s secret or recovery key."
                else
                    "These are decrypted copies. Once saved or shared, they are no longer protected by Vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    model.beginSystemFlow()
                    if (one != null) saveOne.launch(one.name) else saveMany.launch(null)
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = androidx.compose.ui.unit.Dp(56f)),
            ) {
                Icon(Icons.Outlined.SaveAlt, null)
                Spacer(Modifier.width(Space.s))
                Text(if (one != null) "Save to device" else "Save to a folder")
            }
            OutlinedButton(
                onClick = {
                    val uris = files.map { context.shareUri(it) }
                    val type =
                        if (shown.backup) "application/octet-stream"
                        else
                            files
                                .map { Types.mime(Types.identifier(null, it.name), it.name) }
                                .distinct()
                                .singleOrNull() ?: "*/*"
                    val send =
                        if (uris.size == 1)
                            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                        else
                            Intent(Intent.ACTION_SEND_MULTIPLE)
                                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    send.type = type
                    send.clipData =
                        ClipData.newRawUri(files[0].name, uris[0]).apply {
                            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                        }
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    model.beginSystemFlow()
                    context.startActivity(Intent.createChooser(send, null))
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = androidx.compose.ui.unit.Dp(56f)),
            ) {
                Icon(Icons.Outlined.Share, null)
                Spacer(Modifier.width(Space.s))
                Text("Share")
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(
                onClick = model::cleanPresentation,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}
