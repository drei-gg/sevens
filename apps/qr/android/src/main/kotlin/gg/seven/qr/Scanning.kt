package gg.seven.qr

import androidx.core.graphics.scale
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import gg.seven.qr.core.ScannedCode
import gg.seven.qr.core.Symbology
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader

/*
 * Reading codes: the counterparts of iOS ScanDecoding, ScanDebouncer, ImageScanner and
 * PasteScanButton. zxing-cpp (Apache-2.0) reads every code, live and from still images, in every
 * flavor; only the decoding library differs from iOS (Vision there).
 */

/** The one place that turns zxing-cpp's results into the contract's ScannedCode. */
object ScanDecoding {
    /** Longest payload kept, in bytes. Real codes top out near 3 KB; this bounds hostile input. */
    const val MAX_PAYLOAD_BYTES = 16 * 1024

    /** Variants of one family collapse to it, as iOS does; Interleaved 2 of 5, rMQR and the rest are OTHER. */
    fun symbology(format: BarcodeReader.Format): Symbology =
        when (format) {
            BarcodeReader.Format.QR_CODE, BarcodeReader.Format.QR_CODE_MODEL_1, BarcodeReader.Format.QR_CODE_MODEL_2 -> Symbology.QR
            BarcodeReader.Format.MICRO_QR_CODE -> Symbology.MICRO_QR
            BarcodeReader.Format.AZTEC, BarcodeReader.Format.AZTEC_CODE, BarcodeReader.Format.AZTEC_RUNE -> Symbology.AZTEC
            BarcodeReader.Format.PDF_417, BarcodeReader.Format.COMPACT_PDF_417 -> Symbology.PDF417
            BarcodeReader.Format.MICRO_PDF_417 -> Symbology.MICRO_PDF417
            BarcodeReader.Format.DATA_MATRIX -> Symbology.DATA_MATRIX
            BarcodeReader.Format.CODE_128 -> Symbology.CODE128
            BarcodeReader.Format.CODE_39, BarcodeReader.Format.CODE_39_STD, BarcodeReader.Format.CODE_39_EXT -> Symbology.CODE39
            BarcodeReader.Format.CODE_93 -> Symbology.CODE93
            BarcodeReader.Format.EAN_13, BarcodeReader.Format.UPC_A, BarcodeReader.Format.ISBN -> Symbology.EAN13
            BarcodeReader.Format.EAN_8 -> Symbology.EAN8
            BarcodeReader.Format.UPC_E -> Symbology.UPCE
            BarcodeReader.Format.ITF_14 -> Symbology.ITF14
            BarcodeReader.Format.CODABAR -> Symbology.CODABAR
            BarcodeReader.Format.DATA_BAR, BarcodeReader.Format.DATA_BAR_OMNI, BarcodeReader.Format.DATA_BAR_STK,
            BarcodeReader.Format.DATA_BAR_STK_OMNI, BarcodeReader.Format.DATA_BAR_LTD, BarcodeReader.Format.DATA_BAR_EXP,
            BarcodeReader.Format.DATA_BAR_EXP_STK -> Symbology.GS1_DATA_BAR
            else -> Symbology.OTHER
        }

    /** Strict UTF-8, or null. Latin-1 is never tried: it would turn binary payloads into mojibake. */
    fun strictUtf8(bytes: ByteArray): String? =
        runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }
            .getOrNull()

    /**
     * `bytes` come from the raw payload when the reader has it, else from the text's UTF-8. `text`
     * is the reader's text for text content, or the raw bytes as strict UTF-8, or null (binary).
     */
    fun code(text: String?, raw: ByteArray?, binary: Boolean, symbology: Symbology): ScannedCode {
        var shown = if (binary) null else text
        val bytes: ByteArray =
            when {
                raw != null && raw.isNotEmpty() -> {
                    val kept = if (raw.size > MAX_PAYLOAD_BYTES) raw.copyOf(MAX_PAYLOAD_BYTES) else raw
                    if (shown == null) shown = strictUtf8(kept)
                    kept
                }
                shown != null -> shown.toByteArray().let { if (it.size > MAX_PAYLOAD_BYTES) it.copyOf(MAX_PAYLOAD_BYTES) else it }
                else -> ByteArray(0)
            }
        if (shown != null && shown.toByteArray().size > MAX_PAYLOAD_BYTES) shown = null
        return ScannedCode(shown, bytes, symbology)
    }

    fun code(result: BarcodeReader.Result): ScannedCode =
        code(result.text, result.bytes, result.contentType == BarcodeReader.ContentType.BINARY, symbology(result.format))

    fun reader(stills: Boolean): BarcodeReader =
        BarcodeReader(
            BarcodeReader.Options(
                tryHarder = stills,
                tryRotate = true,
                tryInvert = stills,
                tryDownscale = true,
                textMode = BarcodeReader.TextMode.PLAIN,
                maxNumberOfSymbols = 50,
            )
        )
}

/**
 * D7 timing for the live scanner, pure so it can be tested without a camera:
 * - one code continuously in view for [dwellMillis] (0.4 s) is delivered;
 * - several in view are never auto-delivered; the person taps one;
 * - after a delivery it is paused (the result sheet is up). [resume] restarts it and ignores the
 *   delivered payload until it has left the frame or [repeatSuppressionMillis] (3 s) has passed.
 *   A deliberate [tap] is never suppressed.
 */
class ScanDebouncer(private val dwellMillis: Long = 400, private val repeatSuppressionMillis: Long = 3_000) {
    var isPaused = false
        private set
    private var inView: List<ScannedCode> = emptyList()
    private var candidate: CodeKey? = null
    private var candidateSince = 0L
    private var lastDelivered: CodeKey? = null
    private var suppressed: CodeKey? = null
    private var suppressedUntil = 0L

    /** More than one distinct code in view: the screen shows "Tap a code". */
    val hasMultiple
        get() = inView.size > 1

    val codesInView: List<ScannedCode>
        get() = inView

    /** The full set of codes in view. Returns a code to deliver, if any. */
    fun observe(codes: List<ScannedCode>, now: Long): ScannedCode? {
        val seen = LinkedHashMap<CodeKey, ScannedCode>()
        codes.forEach { seen.putIfAbsent(it.key, it) }
        inView = seen.values.toList()
        if (suppressed != null && suppressed !in seen) suppressed = null
        return evaluate(now)
    }

    /** Re-evaluate at [nextWake]: the camera reports changes, not the passing of time. */
    fun tick(now: Long): ScannedCode? = evaluate(now)

    fun tap(code: ScannedCode): ScannedCode? = if (isPaused) null else deliver(code)

    fun pause() {
        isPaused = true
        candidate = null
        inView = emptyList()
    }

    fun resume(now: Long) {
        if (!isPaused) return
        isPaused = false
        candidate = null
        inView = emptyList()
        lastDelivered?.let {
            suppressed = it
            suppressedUntil = now + repeatSuppressionMillis
        }
    }

    /** When [tick] would next change the outcome (same clock as `now`), or null. */
    val nextWake: Long?
        get() {
            if (isPaused) return null
            if (candidate != null) return candidateSince + dwellMillis
            if (inView.size == 1 && suppressed != null && inView[0].key == suppressed) return suppressedUntil
            return null
        }

    private fun evaluate(now: Long): ScannedCode? {
        if (isPaused) return null
        if (suppressed != null && now >= suppressedUntil) suppressed = null
        val code = inView.singleOrNull()
        if (code == null) {
            candidate = null
            return null
        }
        val key = code.key
        if (key == suppressed) {
            candidate = null
            return null
        }
        if (candidate != key) {
            candidate = key
            candidateSince = now
        }
        if (now - candidateSince < dwellMillis) return null
        return deliver(code)
    }

    private fun deliver(code: ScannedCode): ScannedCode {
        lastDelivered = code.key
        suppressed = null
        candidate = null
        isPaused = true
        inView = emptyList()
        return code
    }
}

/** A code with where it sits in the picture (any coordinate space the caller chooses). */
data class Located(val code: ScannedCode, val corners: List<PointF>) {
    val bounds: RectF
        get() =
            RectF(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
}

/**
 * Every code in a still image: photos, paste and shared images. Images are decoded no larger than
 * [MAX_PIXEL_SIZE] on the long side, identical codes appear once, results run top to bottom and
 * left to right. Failures yield an empty list ("no code found").
 */
object ImageScanner {
    const val MAX_PIXEL_SIZE = 2048
    const val MAX_RESULTS = 50

    suspend fun scan(context: Context, uri: Uri): List<ScannedCode>? = withContext(Dispatchers.Default) { decode(context, uri)?.let(::scan) }

    suspend fun scan(bytes: ByteArray): List<ScannedCode>? = withContext(Dispatchers.Default) { decode(bytes)?.let(::scan) }

    fun scan(bitmap: Bitmap): List<ScannedCode> {
        val found = runCatching { ScanDecoding.reader(stills = true).read(bitmap) }.getOrDefault(emptyList())
        val located = found.map { r -> Located(ScanDecoding.code(r), listOf(r.position.topLeft, r.position.topRight, r.position.bottomRight, r.position.bottomLeft).map { PointF(it.x.toFloat(), it.y.toFloat()) }) }
        return order(located).take(MAX_RESULTS)
    }

    /**
     * Top to bottom, then left to right within a row; duplicates dropped, keeping the first in
     * reading order. Codes whose vertical centres are within half the smaller height of each other
     * count as one row, so a slightly tilted line still reads left to right. Y grows downward.
     */
    fun order(found: List<Located>): List<ScannedCode> {
        val byTop = found.sortedBy { it.bounds.centerY() }
        val rows = mutableListOf<MutableList<Located>>()
        var reference: RectF? = null
        for (item in byTop) {
            val box = item.bounds
            val ref = reference
            if (ref != null && abs(box.centerY() - ref.centerY()) <= 0.5f * min(box.height(), ref.height())) rows.last() += item
            else {
                rows += mutableListOf(item)
                reference = box
            }
        }
        val seen = HashSet<CodeKey>()
        return rows.flatMap { row -> row.sortedBy { it.bounds.left } }.map { it.code }.filter { seen.add(it.key) }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_PIXEL_SIZE) sample *= 2
        return sample
    }

    private fun downscaled(bitmap: Bitmap): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= MAX_PIXEL_SIZE) return bitmap
        val scale = MAX_PIXEL_SIZE.toFloat() / long
        return bitmap.scale(max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()))
    }

    /** Applies EXIF orientation (ImageDecoder, Android 9+) and bounds memory. */
    fun decode(context: Context, uri: Uri): Bitmap? =
        runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val long = max(info.size.width, info.size.height)
                        if (long > MAX_PIXEL_SIZE) {
                            val scale = MAX_PIXEL_SIZE.toFloat() / long
                            decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
                        }
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.let(::downscaled)
                }
            }
            .getOrNull()

    fun decode(bytes: ByteArray): Bitmap? =
        runCatching {
                if (Build.VERSION.SDK_INT >= 28)
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val long = max(info.size.width, info.size.height)
                        if (long > MAX_PIXEL_SIZE) {
                            val scale = MAX_PIXEL_SIZE.toFloat() / long
                            decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
                        }
                    }
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })?.let(::downscaled)
                }
            }
            .getOrNull()
}

/**
 * Scan from the clipboard: an image is scanned; a link or text becomes one code "analysed as if
 * scanned". Images win over links, links over text. Empty: nothing usable was copied.
 */
object PasteScanner {
    /** Longest pasted text accepted, in UTF-8 bytes. */
    const val MAX_TEXT_BYTES = 64 * 1024

    fun textCode(text: String): ScannedCode? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray().size > MAX_TEXT_BYTES) return null
        return textCode(trimmed, Symbology.QR)
    }

    suspend fun codes(context: Context): List<ScannedCode> {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return emptyList()
        val clip = clipboard.primaryClip ?: return emptyList()
        val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
        val resolver = context.contentResolver
        for (item in items) {
            val uri = item.uri ?: continue
            val type = runCatching { resolver.getType(uri) }.getOrNull() ?: continue
            if (!type.startsWith("image/")) continue
            val codes = ImageScanner.scan(context, uri)
            if (!codes.isNullOrEmpty()) return codes
        }
        for (item in items) {
            val uri = item.uri ?: continue
            if (uri.scheme == "http" || uri.scheme == "https") textCode(uri.toString())?.let { return listOf(it) }
        }
        for (item in items) {
            val text = item.text?.toString() ?: continue
            textCode(text)?.let { return listOf(it) }
        }
        return emptyList()
    }
}
