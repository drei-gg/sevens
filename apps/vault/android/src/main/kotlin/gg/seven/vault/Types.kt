package gg.seven.vault

import android.webkit.MimeTypeMap

/**
 * The index stores a uniform type identifier, as on iOS (`public.png`), so the core's note rule
 * (`public.plain-text`) and a vault's index read the same on both platforms. Android speaks MIME
 * types; this is the translation, for the types Vault previews and names. Anything else is kept
 * by its MIME type under `dyn.android.mime.`, so nothing about the original is lost.
 */
object Types {
    private val pairs =
        listOf(
            "public.jpeg" to "image/jpeg",
            "public.png" to "image/png",
            "public.heic" to "image/heic",
            "public.heif" to "image/heif",
            "com.compuserve.gif" to "image/gif",
            "org.webmproject.webp" to "image/webp",
            "com.microsoft.bmp" to "image/bmp",
            "public.tiff" to "image/tiff",
            "public.avif" to "image/avif",
            "com.adobe.raw-image" to "image/x-adobe-dng",
            "public.mpeg-4" to "video/mp4",
            "com.apple.quicktime-movie" to "video/quicktime",
            "public.3gpp" to "video/3gpp",
            "org.webmproject.webm" to "video/webm",
            "public.avi" to "video/x-msvideo",
            "public.mp3" to "audio/mpeg",
            "public.mpeg-4-audio" to "audio/mp4",
            "com.microsoft.waveform-audio" to "audio/wav",
            "com.adobe.pdf" to "application/pdf",
            "public.plain-text" to "text/plain",
            "public.html" to "text/html",
            "public.comma-separated-values-text" to "text/csv",
            "public.json" to "application/json",
            "public.zip-archive" to "application/zip",
            "org.openxmlformats.wordprocessingml.document" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "org.openxmlformats.spreadsheetml.sheet" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
    private const val DYNAMIC = "dyn.android.mime."

    fun identifier(mime: String?, name: String): String {
        val type = mime?.lowercase()?.takeUnless { it.isBlank() || it == "application/octet-stream" } ?: fromName(name)
        if (type == null) return "public.data"
        return pairs.firstOrNull { it.second == type }?.first ?: (DYNAMIC + type)
    }

    fun mime(identifier: String, name: String): String =
        pairs.firstOrNull { it.first == identifier }?.second
            ?: identifier.removePrefix(DYNAMIC).takeIf { identifier.startsWith(DYNAMIC) }
            ?: fromName(name)
            ?: "application/octet-stream"

    private fun fromName(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    fun isImage(identifier: String, name: String) = mime(identifier, name).startsWith("image/")

    fun isVideo(identifier: String, name: String) = mime(identifier, name).startsWith("video/")

    fun isAudio(identifier: String, name: String) = mime(identifier, name).startsWith("audio/")

    fun isText(identifier: String, name: String) = identifier == VaultRepository.NOTE_TYPE || mime(identifier, name).startsWith("text/")

    fun isPdf(identifier: String, name: String) = mime(identifier, name) == "application/pdf"
}
