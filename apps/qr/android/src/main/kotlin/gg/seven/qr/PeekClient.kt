package gg.seven.qr

import android.os.LocaleList
import android.util.Log
import gg.seven.qr.core.BundledData
import gg.seven.qr.core.LinkInfo
import gg.seven.qr.core.PeekCapture
import gg.seven.qr.core.PeekHop
import gg.seven.qr.core.StopReason
import gg.seven.qr.core.domainAgeDays
import gg.seven.qr.core.rdapRegistrationDate
import java.io.IOException
import java.security.cert.CertificateException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * One of the only two places that use the network (SPEC §6, §12; the other is ListsUpdater). A
 * fresh client per Peek: no cookies, no cache, no credentials, the system's trust only. Follows up
 * to [MAX_REDIRECTS] redirects by hand so every hop is seen, reads at most [MAX_BODY_BYTES] of the
 * final page without running any of it, and gives up after [totalTimeoutMillis]. Plain http links
 * aren't fetched. Never throws: whatever was learned comes back with the reason the Peek stopped.
 */
class PeekClient(
    private val totalTimeoutMillis: Long = 8_000,
    private val rdapTimeoutMillis: Long = 5_000,
    languages: List<String> = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } },
) {
    private val acceptLanguage = acceptLanguage(languages)

    private fun client(timeoutMillis: Long): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .retryOnConnectionFailure(false)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    private fun request(url: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .header("Accept-Language", acceptLanguage)
            .get()
            .build()

    /** Hops, the final page's first bytes and headers, the TLS version and why it stopped. */
    suspend fun capture(link: LinkInfo): PeekCapture {
        val first = PeekHop(link.normalized, null)
        when (link.scheme.lowercase()) {
            "https" -> Unit
            "http" -> return PeekCapture(listOf(first), StopReason.HTTP_TARGET)
            else -> return PeekCapture(listOf(first), StopReason.NON_WEB_TARGET)
        }
        if (link.normalized.toHttpUrlOrNull()?.isHttps != true) return PeekCapture(listOf(first), StopReason.NETWORK_ERROR)
        val client = client(totalTimeoutMillis)
        val hops = mutableListOf(first)
        val state = CaptureState()
        val finished =
            withTimeoutOrNull(totalTimeoutMillis) {
                withContext(Dispatchers.IO) { follow(client, hops, state) }
            }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        val stop =
            when {
                finished == null -> StopReason.TIMEOUT
                else -> state.stop ?: StopReason.FINISHED
            }
        return PeekCapture(hops.toList(), stop, state.contentType, state.contentDisposition, state.body, state.tls)
    }

    private class CaptureState {
        var stop: StopReason? = null
        var contentType: String? = null
        var contentDisposition: String? = null
        var body = ByteArray(0)
        var tls: String? = null
    }

    private suspend fun follow(client: OkHttpClient, hops: MutableList<PeekHop>, state: CaptureState) {
        var followed = 0
        var url = hops.last().url
        while (true) {
            currentCoroutineContext().ensureActive()
            val call: Call = client.newCall(request(url))
            val response: Response =
                try {
                    call.await()
                } catch (error: IOException) {
                    state.stop = reason(error)
                    Log.w(TAG, "peek fetch failed: ${error.javaClass.simpleName}")
                    return
                }
            response.use {
                hops[hops.lastIndex] = PeekHop(url, response.code.toLong())
                state.tls = tlsName(response.handshake?.tlsVersion?.javaName)
                val location = response.header("Location")
                val target = if (response.isRedirect && location != null) resolve(url, location) else null
                if (target != null) {
                    val scheme = target.substringBefore(':', "").lowercase()
                    hops += PeekHop(target, null)
                    when {
                        scheme == "https" && followed >= MAX_REDIRECTS -> {
                            hops.removeAt(hops.lastIndex)
                            state.stop = StopReason.TOO_MANY_REDIRECTS
                            return
                        }
                        scheme == "https" -> {
                            followed++
                            url = target
                            return@use
                        }
                        scheme == "http" -> {
                            state.stop = StopReason.HTTP_TARGET
                            return
                        }
                        else -> {
                            state.stop = StopReason.NON_WEB_TARGET
                            return
                        }
                    }
                }
                state.contentType = response.header("Content-Type")
                state.contentDisposition = response.header("Content-Disposition")
                state.body =
                    try {
                        readCapped(response, MAX_BODY_BYTES)
                    } catch (error: IOException) {
                        state.stop = reason(error)
                        ByteArray(0)
                    }
                return
            }
        }
    }

    /** Whole days since registration, or null when unknown (no service, non-200, no event, timeout). */
    suspend fun registrationAge(link: LinkInfo, data: BundledData, now: Instant = Instant.now()): Long? {
        val domain = rdapDomain(link, data) ?: return null
        val query = data.rdapQueryUrl(domain)?.takeIf { it.lowercase().startsWith("https://") } ?: return null
        if (query.toHttpUrlOrNull()?.isHttps != true) return null
        val client = client(rdapTimeoutMillis)
        val request =
            Request.Builder().url(query).header("Accept", "application/rdap+json").header("User-Agent", USER_AGENT).get().build()
        return try {
            withTimeoutOrNull(rdapTimeoutMillis) {
                withContext(Dispatchers.IO) {
                    client.newCall(request).await().use { response ->
                        if (response.code != 200) return@use null
                        val registered = rdapRegistrationDate(readCapped(response, MAX_RDAP_BYTES)) ?: return@use null
                        domainAgeDays(registered, now)
                    }
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "rdap lookup failed: ${error.javaClass.simpleName}")
            null
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        private const val TAG = "QRPeek"
        /**
         * Chrome on Android with the reduced user agent every Chrome now sends, so the page sees
         * what a real phone browser would (iOS sends Safari's).
         */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
        const val ACCEPT = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"
        const val MAX_BODY_BYTES = 512 * 1024
        const val MAX_REDIRECTS = 10
        private const val MAX_RDAP_BYTES = 1024 * 1024

        /** `en-US,fr;q=0.9,de;q=0.8` for the first three tags; `en` when there are none. */
        fun acceptLanguage(languages: List<String>): String {
            val tags = languages.filter { it.isNotEmpty() && it.toByteArray().size <= 35 }.take(3)
            if (tags.isEmpty()) return "en"
            val q = listOf("", ";q=0.9", ";q=0.8")
            return tags.mapIndexed { i, tag -> tag + q[i] }.joinToString(",")
        }

        /** Certificate and TLS problems stop the Peek as TLS_FAILURE; everything else is NETWORK_ERROR. */
        fun reason(error: Throwable): StopReason {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is SSLException || cause is CertificateException) return StopReason.TLS_FAILURE
                cause = cause.cause
            }
            return StopReason.NETWORK_ERROR
        }

        fun tlsName(javaName: String?): String? =
            when (javaName) {
                "TLSv1" -> "TLS 1.0"
                "TLSv1.1" -> "TLS 1.1"
                "TLSv1.2" -> "TLS 1.2"
                "TLSv1.3" -> "TLS 1.3"
                else -> null
            }

        /** A Location header resolved against the hop it came from; null when it isn't a reference. */
        fun resolve(base: String, location: String): String? =
            runCatching { java.net.URI(base).resolve(location.trim()).toString() }.getOrNull()?.takeIf { it.contains(':') }

        /**
         * The domain RDAP is asked about: the registrable domain in ASCII, or for a private suffix
         * such as github.io the ICANN-level domain. Null for IP addresses and unknown suffixes.
         */
        fun rdapDomain(link: LinkInfo, data: BundledData): String? {
            if (link.registrableDomain == null || link.isIpAddress || link.hostAscii.isEmpty()) return null
            val match = data.publicSuffixMatch(link.hostAscii)
            return if (link.suffixIsPrivate) match.icannRegistrableDomain else match.registrableDomain
        }

        private fun readCapped(response: Response, limit: Int): ByteArray {
            val source = response.body?.source() ?: return ByteArray(0)
            val buffer = okio.Buffer()
            while (buffer.size < limit) {
                val read = source.read(buffer, minOf(8_192L, limit - buffer.size))
                if (read == -1L) break
            }
            return buffer.readByteArray()
        }
    }
}

/** Runs the call; cancelling the coroutine (a timeout, Cancel) cancels the request. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    )
}
