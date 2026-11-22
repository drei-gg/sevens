@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.qr

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import gg.seven.kit.SevenTheme
import gg.seven.kit.Space
import gg.seven.qr.core.Analysis
import gg.seven.qr.core.MakeRequest
import gg.seven.qr.core.Payload
import gg.seven.qr.core.Severity
import gg.seven.qr.core.analysisAllSignals
import gg.seven.qr.core.contactParse
import gg.seven.qr.core.payloadEncode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The two share targets (SPEC §9), the counterparts of iOS's Check this code and Make QR code
 * share extensions. They run in the app's process but, like the extensions, write no History and
 * open nothing: Check only warns (free forever, D2); Make QR code needs the trial or the unlock.
 */

private fun Intent.streams(): List<Uri> {
    fun one(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
    fun many(): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else @Suppress("DEPRECATION") getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<Uri>()
    return when (action) {
        Intent.ACTION_SEND_MULTIPLE -> many()
        Intent.ACTION_SEND -> listOfNotNull(one())
        else -> emptyList()
    }
}

/** At most [limit] + 1 bytes, so the caller can tell a file was too large. */
private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (out.size() <= limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (n <= 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun ShareScaffold(title: String, onDone: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onDone, Modifier.testTag("share.done")) { Icon(Icons.Rounded.Close, "Done") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        content = content,
    )
}

// MARK: Check this code

/** What came out of one shared image. */
data class CheckedImage(val position: Int, val codes: List<Analysis>, val unreadable: Boolean)

object CheckRules {
    const val MAX_IMAGES = 5
    const val MAX_CODES_PER_IMAGE = 10
}

/** The one "main field" line under a verdict: the domain for links, the payee for payments, … */
object ShareSummary {
    private const val MAX_VALUE_LENGTH = 160

    fun main(payload: Payload): Pair<String, String>? {
        fun make(label: String, value: String?): Pair<String, String>? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { label to QrText.cut(it, MAX_VALUE_LENGTH) }
        return when (payload) {
            is Payload.Link -> make("Domain", payload.info.hostUnicode.ifEmpty { payload.info.scheme })
            is Payload.AppLink -> make("App", payload.info.scheme)
            is Payload.Payment -> make("Payee", payload.info.payeeName ?: payload.info.payeeAccount)
            is Payload.Crypto -> make("Address", payload.info.address)
            is Payload.Wifi -> make("Network", payload.info.ssid)
            is Payload.Contact -> {
                val i = payload.info
                val joined = listOfNotNull(i.givenName, i.familyName).joinToString(" ")
                make("Name", i.name ?: joined.ifEmpty { i.organization })
            }
            is Payload.Event -> make("Event", payload.info.summary)
            is Payload.Phone -> make("Number", payload.info.number)
            is Payload.Sms -> make("Number", payload.info.number)
            is Payload.Email -> make("Address", payload.info.address)
            is Payload.Geo -> make("Location", "${payload.info.latitude}, ${payload.info.longitude}")
            is Payload.Text -> make("Text", payload.info.text)
            is Payload.Binary -> make("Size", "${payload.byteCount} bytes")
        }
    }
}

/** Check this code: the verdict for every code in up to five shared images. No Peek, no Open. */
class CheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QrLists.start(this)
        enableEdgeToEdge()
        val uris = intent.streams().take(CheckRules.MAX_IMAGES)
        setContent { SevenTheme { CheckScreen(uris, onDone = ::finish) } }
    }
}

@Composable
private fun CheckScreen(uris: List<Uri>, onDone: () -> Unit) {
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<CheckedImage>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(uris) {
        // One image at a time, so only one is in memory.
        for ((index, uri) in uris.withIndex()) {
            val codes = ImageScanner.scan(context, uri)
            results =
                results +
                    if (codes == null) CheckedImage(index + 1, emptyList(), unreadable = true)
                    else CheckedImage(index + 1, withContext(Dispatchers.Default) { codes.take(CheckRules.MAX_CODES_PER_IMAGE).map { analyze(it) } }, unreadable = false)
        }
        if (uris.isEmpty()) results = listOf(CheckedImage(1, emptyList(), unreadable = false))
        finished = true
    }
    ShareScaffold("Check this code", onDone) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            ReadableColumn {
                results.forEach { image ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (results.size > 1) Text("Image ${image.position}", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = QrColors.muted)
                        when {
                            image.unreadable -> QrCard { Text("This image couldn’t be read.", style = MaterialTheme.typography.bodyLarge) }
                            image.codes.isEmpty() -> QrCard { Text("No code found in this image", style = MaterialTheme.typography.bodyLarge) }
                            else -> image.codes.forEach { CheckCard(it) }
                        }
                    }
                }
                if (!finished) LoadingIndicator()
            }
        }
    }
}

@Composable
private fun CheckCard(analysis: Analysis) {
    val context = LocalContext.current
    val view = LocalView.current
    var copied by remember { mutableStateOf(false) }
    val typeName = QrText.typeName(analysis.payload.typeId)
    val all = analysisAllSignals(analysis)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        VerdictHeader(analysis.level, typeName, all)
        QrCard {
            SignalText.sentences(all.filter { it.severity != Severity.INFO }).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            ShareSummary.main(analysis.payload)?.let { (label, value) -> ResultField(label, value) }
            analysis.code.text?.let { text ->
                OutlinedButton(onClick = {
                    Handoffs.copy(context, text)
                    view.confirmHaptic()
                    copied = true
                }) {
                    Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (copied) "Copied" else "Copy")
                }
            }
        }
    }
}

// MARK: Make QR code

/** What the shared items hold, before any decision (iOS ExtensionMakeSource). */
sealed interface ShareMakeSource {
    data class WebUrl(val url: String) : ShareMakeSource

    data class VCard(val contents: String) : ShareMakeSource

    data class Text(val text: String) : ShareMakeSource

    /** A file that is not a vCard and not text. */
    data object OtherFile : ShareMakeSource
}

object ShareMakeInput {
    /** Longest text kept from an attachment. */
    const val MAX_TEXT_BYTES = 64 * 1024

    /** Pure mapping from shared content to a request; null: "Can’t make a code from this file". */
    fun decide(source: ShareMakeSource?): MakeRequest? =
        when (source) {
            null, ShareMakeSource.OtherFile -> null
            is ShareMakeSource.WebUrl -> source.url.trim().takeIf { it.isNotEmpty() }?.let { MakeRequest.Link(it) }
            is ShareMakeSource.VCard -> {
                val trimmed = source.contents.trim()
                if (trimmed.isEmpty()) null else contactParse(trimmed)?.let { MakeRequest.Contact(it) } ?: MakeRequest.Text(trimmed)
            }
            is ShareMakeSource.Text -> source.text.trim().takeIf { it.isNotEmpty() }?.let { MakeRequest.Text(it) }
        }

    /**
     * Android shares a link as text: one http(s) URL with nothing else around it is a web URL (a
     * Maps link arrives like this and stays a link), as iOS gets it as a URL attachment.
     */
    fun classify(text: String): ShareMakeSource {
        val t = text.trim()
        val single = t.isNotEmpty() && t.none { it.isWhitespace() }
        val scheme = t.substringBefore("://", "").lowercase()
        return if (single && (scheme == "https" || scheme == "http")) ShareMakeSource.WebUrl(t) else ShareMakeSource.Text(t)
    }

    fun load(activity: ComponentActivity, intent: Intent): ShareMakeSource? {
        val type = intent.type.orEmpty().lowercase()
        val streams = intent.streams()
        val vcard = type.contains("vcard") || type == "text/directory"
        if (vcard) {
            for (uri in streams) {
                val bytes = runCatching { activity.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_TEXT_BYTES) } }.getOrNull() ?: continue
                if (bytes.size > MAX_TEXT_BYTES) continue
                return ShareMakeSource.VCard(ScanDecoding.strictUtf8(bytes) ?: String(bytes, Charsets.ISO_8859_1))
            }
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (text != null) return if (vcard) ShareMakeSource.VCard(text) else classify(QrText.prefix(text, MAX_TEXT_BYTES))
        return if (streams.isNotEmpty()) ShareMakeSource.OtherFile else null
    }
}

/** The entitlement for the share target (the app's own store, as the Make extension reads it). */
class ShareModel(app: Application) : AndroidViewModel(app) {
    val store = SevenStore(app, BuildConfig.PERSONAL_UNLOCK, sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY), viewModelScope)

    override fun onCleared() = store.close()
}

private sealed interface MakePhase {
    data object Loading : MakePhase

    data object Locked : MakePhase

    data object Unsupported : MakePhase

    data class Failed(val message: String) : MakePhase

    class Ready(val bitmap: Bitmap, val png: ByteArray) : MakePhase
}

/** Make QR code: the code at once, full width with the screen brightened, ready to show or save. */
class MakeShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QrLists.start(this)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[ShareModel::class.java]
        val source = ShareMakeInput.load(this, intent)
        setContent { SevenTheme { MakeShareScreen(model, source, onDone = ::finish, onBright = ::brighten) } }
    }

    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[ShareModel::class.java].store.refresh()
    }

    /** Raises the screen brightness while the code is shown; the window puts it back on close. */
    private fun brighten(on: Boolean) {
        window.attributes = window.attributes.apply { screenBrightness = if (on) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
}

@Composable
private fun MakeShareScreen(model: ShareModel, source: ShareMakeSource?, onDone: () -> Unit, onBright: (Boolean) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val purchase by model.store.purchase.collectAsStateWithLifecycle()
    val entitlement by model.store.entitlement.collectAsStateWithLifecycle()
    var phase by remember { mutableStateOf<MakePhase>(MakePhase.Loading) }
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    val saveFile = rememberSaveFile { ok ->
        saved = ok
        if (ok) view.confirmHaptic()
    }
    LaunchedEffect(purchase.loaded, entitlement) {
        if (!purchase.loaded) return@LaunchedEffect
        if (!entitlement.paidFeaturesAvailable) {
            phase = MakePhase.Locked
            return@LaunchedEffect
        }
        val request = ShareMakeInput.decide(source)
        phase =
            if (request == null) MakePhase.Unsupported
            else
                withContext(Dispatchers.Default) {
                    try {
                        val matrix = CodeGenerator.generate(payloadEncode(request), MakeSymbology.QR, Correction.AUTO)
                        val bitmap = PngRenderer.bitmap(matrix, 1024, BLACK, WHITE)
                        if (bitmap == null) MakePhase.Failed(MakeError.GENERATOR_FAILED.message) else MakePhase.Ready(bitmap, PngRenderer.png(matrix, 1024, BLACK, WHITE))
                    } catch (e: MakeException) {
                        MakePhase.Failed(e.error.message)
                    }
                }
    }
    val ready = phase is MakePhase.Ready
    DisposableEffect(ready) {
        onBright(ready)
        onDispose { onBright(false) }
    }
    ShareScaffold("Make QR code", onDone) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            when (val p = phase) {
                MakePhase.Loading -> LoadingIndicator()
                MakePhase.Locked -> Text("Make QR code is part of the unlock. Open the app to see what it adds.", style = MaterialTheme.typography.bodyLarge)
                MakePhase.Unsupported -> Text("Can’t make a code from this file.", style = MaterialTheme.typography.bodyLarge)
                is MakePhase.Failed -> Text(p.message, style = MaterialTheme.typography.bodyLarge)
                is MakePhase.Ready -> {
                    Image(
                        p.bitmap.asImageBitmap(),
                        contentDescription = "QR code",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).testTag("share.make.code"),
                        filterQuality = FilterQuality.None,
                    )
                    ReadableColumn {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            if (saved == false) Text("The image couldn’t be saved there. Try again.", color = MaterialTheme.colorScheme.error)
                            Button(
                                onClick = {
                                    saved = null
                                    saveFile.save(FileShare.write(context, "QR code.png", p.png))
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("share.make.save"),
                            ) {
                                Icon(if (saved == true) Icons.Rounded.Check else Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(if (saved == true) "Saved" else "Save image")
                            }
                            OutlinedButton(
                                onClick = {
                                    val file = FileShare.write(context, "QR code.png", p.png)
                                    FileShare.share(context, file, "image/png", "QR code")
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("share.make.share"),
                            ) {
                                Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Share")
                            }
                            OutlinedButton(
                                onClick = {
                                    FileShare.copyImage(context, FileShare.writeClipboard(context, "QR code.png", p.png))
                                    view.confirmHaptic()
                                    copied = true
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            ) {
                                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(if (copied) "Copied" else "Copy")
                            }
                        }
                    }
                }
            }
        }
    }
}
