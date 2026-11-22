package gg.seven.qr

import android.content.Context
import android.content.res.Resources
import android.util.Log
import gg.seven.qr.core.ListsBundleException
import gg.seven.qr.core.ListsVersion
import gg.seven.qr.core.listsBundleCheck
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.math.ec.rfc8032.Ed25519

/*
 * Check for newer lists (SPEC §4.3): the second and last place the app uses the network (the
 * other is PeekClient). Runs only when the button is tapped: two GETs to the public repository,
 * through a fresh client with no cookies, cache or credentials and no header beyond a user agent.
 * Nothing about the person or their scans is sent. The bytes are accepted only if the Ed25519
 * signature verifies against a key built into the app, then the core's format, schema, no-rollback,
 * shape and size rules pass.
 */

/** Where newer lists come from and which keys may sign them; null without a repository and a key. */
class ListsConfiguration private constructor(val owner: String, val repository: String, val keys: List<ByteArray>) {
    /** `https://raw.githubusercontent.com/<owner>/<repo>/lists/<name>`. */
    fun fileUrl(name: String) = "https://$HOST/$owner/$repository/$BRANCH/$name"

    companion object {
        const val HOST = "raw.githubusercontent.com"
        /** The branch the publishing workflow writes to. */
        const val BRANCH = "lists"

        /** [repositoryUrl] is `https://github.com/<owner>/<repo>`; keys are 64 hex characters, blank or bad ones ignored. */
        fun of(repositoryUrl: String?, hexKeys: List<String?>): ListsConfiguration? {
            val (owner, repo) = parse(repositoryUrl) ?: return null
            val keys = mutableListOf<ByteArray>()
            for (hex in hexKeys) {
                val raw = hex(hex) ?: continue
                if (!Ed25519.validatePublicKeyFull(raw, 0) || keys.any { it.contentEquals(raw) }) continue
                keys += raw
            }
            if (keys.isEmpty()) return null
            return ListsConfiguration(owner, repo, keys)
        }

        /** This build's configuration (BuildConfig, from the brand's identity.properties). */
        fun configured(): ListsConfiguration? = of(BuildConfig.REPOSITORY_URL, listOf(BuildConfig.LISTS_KEY_CURRENT, BuildConfig.LISTS_KEY_NEXT))

        private fun parse(text: String?): Pair<String, String>? {
            val t = text?.trim() ?: return null
            val prefix = "https://github.com/"
            if (!t.lowercase().startsWith(prefix)) return null
            val parts = t.substring(prefix.length).split('/').filter { it.isNotEmpty() }.toMutableList()
            if (parts.size != 2) return null
            if (parts[1].endsWith(".git")) parts[1] = parts[1].dropLast(4)
            val allowed = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_', '.')
            for (part in parts) if (part.isEmpty() || part == "." || part == ".." || part.length > 100 || !part.all { it in allowed }) return null
            return parts[0] to parts[1]
        }

        private fun hex(text: String?): ByteArray? {
            val t = text?.trim() ?: return null
            if (t.length != 64) return null
            return runCatching { ByteArray(32) { i -> t.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }.getOrNull()
                ?.takeIf { t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
        }
    }
}

sealed interface ListsUpdateResult {
    data class Updated(val lists: ListsVersion) : ListsUpdateResult

    /** The connection failed, timed out or got something other than a plain 200. */
    data object Unreachable : ListsUpdateResult

    /** The lists were fine but couldn't be saved. */
    data object SaveFailed : ListsUpdateResult

    data class Refused(val reason: ListsBundleException) : ListsUpdateResult
}

class ListsUpdater(
    private val configuration: ListsConfiguration,
    private val store: ListsStore,
    /** The version in use now. */
    private val installedVersion: () -> Long = { QrLists.shared().listsVersion().version },
    /** Called with the accepted bytes, or null after a reset; reloads the shared lists. */
    private val apply: (ByteArray?) -> Unit = QrLists::reload,
    private val client: OkHttpClient = defaultClient(),
) {
    constructor(context: Context, configuration: ListsConfiguration) : this(configuration, ListsStore(context))

    /** Fetches, checks and, only if every check passes, saves and loads the newer lists. */
    suspend fun check(): ListsUpdateResult =
        withContext(Dispatchers.IO) {
            val bytes: ByteArray
            val signature: ByteArray
            try {
                bytes = fetch(configuration.fileUrl(ListsStore.FILE_NAME), ListsStore.MAX_BYTES)
                signature = fetch(configuration.fileUrl(ListsStore.FILE_NAME + ".sig"), MAX_SIGNATURE_BYTES.toLong())
            } catch (tooLarge: TooLarge) {
                return@withContext ListsUpdateResult.Refused(ListsBundleException.TooLarge())
            } catch (error: IOException) {
                Log.w(TAG, "lists fetch failed: ${error.javaClass.simpleName}")
                return@withContext ListsUpdateResult.Unreachable
            } finally {
                client.connectionPool.evictAll()
            }
            accept(bytes, signature)
        }

    /** Every acceptance rule except the network: the signature over these exact bytes, then the core's rules. */
    fun accept(bytes: ByteArray, signature: ByteArray): ListsUpdateResult {
        if (bytes.size > ListsStore.MAX_BYTES) return ListsUpdateResult.Refused(ListsBundleException.TooLarge())
        if (!verifies(signature, bytes)) return ListsUpdateResult.Refused(ListsBundleException.BadSignature())
        val header =
            try {
                listsBundleCheck(bytes, installedVersion())
            } catch (refused: ListsBundleException) {
                return ListsUpdateResult.Refused(refused)
            }
        try {
            store.write(bytes)
        } catch (error: IOException) {
            Log.w(TAG, "lists save failed: ${error.javaClass.simpleName}")
            return ListsUpdateResult.SaveFailed
        }
        apply(bytes)
        return ListsUpdateResult.Updated(header)
    }

    /** "Use the lists that came with the app": deletes the saved download and reloads. */
    fun resetToBundled() {
        store.remove()
        apply(null)
    }

    private fun verifies(signature: ByteArray, bytes: ByteArray): Boolean {
        if (signature.size != Ed25519.SIGNATURE_SIZE) return false
        return configuration.keys.any { key -> runCatching { Ed25519.verify(signature, 0, key, 0, bytes, 0, bytes.size) }.getOrDefault(false) }
    }

    private class TooLarge : IOException("too large")

    /** One GET. Refuses redirects, anything but 200, and anything larger than [limit]. */
    private fun fetch(url: String, limit: Long): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", PeekClient.USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) throw IOException("status ${response.code}")
            val body = response.body ?: throw IOException("no body")
            if (body.contentLength() > limit) throw TooLarge()
            val source = body.source()
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 8_192)
                if (read == -1L) break
                if (buffer.size > limit) throw TooLarge()
            }
            return buffer.readByteArray()
        }
    }

    companion object {
        private const val TAG = "QRLists"
        const val TIMEOUT_SECONDS = 15L
        /** A signature is 64 bytes; anything much larger is not one. */
        const val MAX_SIGNATURE_BYTES = 1024

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
                .build()
    }
}

/** The sentences and dates of the lists rows in Settings (iOS ListsAboutText). */
object ListsText {
    /** "2026-11-10" as that calendar day, or null. */
    fun date(built: String): LocalDate? {
        val parts = built.split('-').map { it.toIntOrNull() }
        if (parts.size != 3 || parts.any { it == null }) return null
        return runCatching { LocalDate.of(parts[0]!!, parts[1]!!, parts[2]!!) }.getOrNull()
    }

    fun dateText(built: String, locale: Locale = Locale.getDefault()): String? =
        date(built)?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(it) }

    fun title(res: Resources, lists: ListsVersion): String =
        dateText(lists.built)?.let { res.getString(R.string.qr_lists_title_dated, it) } ?: res.getString(R.string.qr_lists_title)

    /** More than six months before [today]; an unreadable date counts as old. */
    fun isOld(built: String, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Boolean {
        val day = date(built) ?: return true
        return today.isAfter(day.plusMonths(6))
    }

    fun sentence(res: Resources, result: ListsUpdateResult): String =
        when (result) {
            is ListsUpdateResult.Updated ->
                dateText(result.lists.built)?.let { res.getString(R.string.qr_lists_updated_dated, it) } ?: res.getString(R.string.qr_lists_updated)
            ListsUpdateResult.Unreachable -> res.getString(R.string.qr_lists_unreachable)
            ListsUpdateResult.SaveFailed -> res.getString(R.string.qr_lists_save_failed)
            is ListsUpdateResult.Refused ->
                res.getString(
                    when (result.reason) {
                        is ListsBundleException.NotNewer -> R.string.qr_lists_not_newer
                        is ListsBundleException.TooLarge -> R.string.qr_lists_too_large
                        is ListsBundleException.BadSignature -> R.string.qr_lists_bad_signature
                        is ListsBundleException.Unreadable -> R.string.qr_lists_unreadable
                        is ListsBundleException.UnknownSchema -> R.string.qr_lists_unknown_schema
                        is ListsBundleException.BadShape -> R.string.qr_lists_bad_shape
                    }
                )
        }
}
