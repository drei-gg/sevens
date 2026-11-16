package gg.seven.cleaner

import gg.seven.cleaner.core.CleanerException
import gg.seven.cleaner.core.PhotoTraits
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/*
 * Small platform pieces the services share. No Cleaner rule lives here: those are the core's.
 * These are the Android counterparts of what stays native on iOS (CryptoKit hashing, the error
 * sentence, Photos' asset traits).
 */

/** `CleanerError.blocked(reason)`. */
internal fun blocked(reason: String) = CleanerException.Blocked(reason)

/** The sentence to show for an error (iOS `cleanerMessage`). */
internal val Throwable.cleanerMessage: String
    get() =
        when (this) {
            is CleanerException.Blocked -> reason
            is CleanerException.Unreadable -> "This file cannot be read."
            else -> localizedMessage ?: "Something went wrong."
        }

/** The core reads `&[u8]` without copying, which needs a direct buffer. */
internal fun ByteArray.direct(): ByteBuffer =
    ByteBuffer.allocateDirect(size).put(this).also { it.flip() }

/** SHA-256 of a stream in 1 MiB reads, checking for cancellation between reads (FileDigest). */
internal suspend fun sha256(input: InputStream): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1_048_576)
    var total = 0L
    input.use {
        while (true) {
            coroutineContext.ensureActive()
            val read = it.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } to total
}

/**
 * Android's media library has no asset subtypes, so the collection traits come from what it does
 * record: the folder a screenshot or screen recording is saved in, the motion-photo file name
 * (`.MP.jpg`, as Pixel and others write it), a burst name and a high capture frame rate. HDR stills
 * aren't recorded at all, so that collection stays empty on Android.
 */
internal fun mediaTraits(
    video: Boolean,
    relativePath: String?,
    displayName: String,
    captureFrameRate: Float?,
): PhotoTraits {
    val path = relativePath.orEmpty().lowercase()
    val name = displayName.lowercase()
    return PhotoTraits(
        screenshot = !video && ("screenshot" in path || name.startsWith("screenshot")),
        live = !video && (".mp." in name || name.endsWith(".mp.jpg") || "motionphoto" in name),
        screenRecording =
            video && ("screen record" in path || "screenrecord" in path || "screen_record" in name ||
                name.startsWith("screen-") || name.startsWith("screenrecord")),
        highFrameRate = video && (captureFrameRate ?: 0f) >= 90f,
        hdr = false,
        burst = !video && "burst" in name,
    )
}

/** The type identifier the core's compression gate expects, from a MIME type. */
internal fun typeIdentifier(mime: String?): String =
    when (mime?.lowercase()) {
        "image/jpeg", "image/jpg" -> "public.jpeg"
        "image/png" -> "public.png"
        null -> ""
        else -> mime
    }

/** The collection names on screen: the core's tags, with Android's word for a Live Photo. */
internal fun collectionLabel(tag: String): String =
    when (tag) {
        "Live Photos" -> "Motion photos"
        else -> tag
    }
