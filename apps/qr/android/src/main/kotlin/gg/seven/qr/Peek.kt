@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.qr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import gg.seven.kit.Space
import gg.seven.qr.core.Analysis
import gg.seven.qr.core.BundledData
import gg.seven.qr.core.KnownAddresses
import gg.seven.qr.core.Level
import gg.seven.qr.core.LinkInfo
import gg.seven.qr.core.Payload
import gg.seven.qr.core.PeekAnalyzer
import gg.seven.qr.core.PeekCapture
import gg.seven.qr.core.PeekHop
import gg.seven.qr.core.PeekReport
import gg.seven.qr.core.Signal
import gg.seven.qr.core.SignalId
import gg.seven.qr.core.StopReason
import gg.seven.qr.core.analysisAllSignals
import gg.seven.qr.core.peekAgeLink
import gg.seven.qr.core.peekAvailable
import gg.seven.qr.core.webAddressParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/*
 * Peek (SPEC §6): one tap, online, paid. Fetches the link while asking the registry how old its
 * domain is (and asks again only if the page reached is on another domain), then runs the core's
 * pure analysis of what came back. The Gate is at the button.
 */

sealed interface PeekState {
    data object Idle : PeekState

    data class Running(val checkingAge: Boolean) : PeekState

    data class Finished(val report: PeekReport) : PeekState

    /** The original isn't a link, so there is nothing to Peek at. */
    data object Failed : PeekState
}

/**
 * One Peek of [original] (iOS PeekModel). [run] does the whole thing; cancelling it stops it and
 * returns to idle, while a finished Peek stays finished, so the result keeps what it found. Hops
 * are checked with the person's [known] addresses (SPEC §4.4).
 */
class PeekModel(
    val original: Analysis,
    private val known: KnownAddresses,
    private val client: PeekClient = PeekClient(),
    private val data: BundledData = QrLists.shared(),
) {
    private val mutableState = MutableStateFlow<PeekState>(PeekState.Idle)
    val state: StateFlow<PeekState> = mutableState.asStateFlow()
    private val mutableCapture = MutableStateFlow<PeekCapture?>(null)
    /** What the fetch returned; set once the fetch is done, so hops show while the age is pending. */
    val capture: StateFlow<PeekCapture?> = mutableCapture.asStateFlow()
    private val mutableAge = MutableStateFlow<Long?>(null)
    val ageDays: StateFlow<Long?> = mutableAge.asStateFlow()

    suspend fun run() {
        if (mutableState.value !is PeekState.Idle) return
        val link = (original.payload as? Payload.Link)?.info
        if (link == null || !peekAvailable(original)) {
            mutableState.value = PeekState.Failed
            return
        }
        mutableState.value = PeekState.Running(checkingAge = false)
        try {
            coroutineScope {
                // SPEC §6.2: the scanned domain's age is asked for while the page is fetched, so a
                // slow, failed or plain-http fetch still has it.
                val scannedAge = async { client.registrationAge(link, data) }
                val captured = client.capture(link)
                mutableCapture.value = captured
                var age: Long? = null
                val target = peekAgeLink(original, captured, data)
                if (target != null) {
                    mutableState.value = PeekState.Running(checkingAge = true)
                    val domain = PeekClient.rdapDomain(target, data)
                    age =
                        if (domain != null && domain == PeekClient.rdapDomain(link, data)) scannedAge.await()
                        else {
                            scannedAge.cancel()
                            client.registrationAge(target, data)
                        }
                } else scannedAge.cancel()
                mutableAge.value = age
                val report = withContext(Dispatchers.Default) { PeekAnalyzer.withKnown(data, known).use { it.report(original, captured, age) } }
                mutableState.value = PeekState.Finished(report)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (mutableState.value is PeekState.Running) mutableState.value = PeekState.Idle
            mutableCapture.value = null
            mutableAge.value = null
            throw cancelled
        }
    }
}

/** The wording of a Peek result (iOS PeekViewText). */
object PeekText {
    fun age(days: Long?): String =
        when {
            days == null || days < 0 -> "Age unknown"
            days == 0L -> "Registered today"
            days == 1L -> "Registered 1 day ago"
            else -> "Registered $days days ago"
        }

    fun connection(capture: PeekCapture): String {
        if (capture.stoppedBecause == StopReason.TLS_FAILURE) return "The site’s certificate was rejected"
        val last = capture.hops.lastOrNull()
        if (last != null && last.status == null && last.url.lowercase().startsWith("http://")) return "Not fetched: plain http"
        capture.tlsVersion?.takeIf { it.isNotEmpty() }?.let { return "Encrypted ($it)" }
        return "Not known"
    }

    /** "a password, a one-time code or PIN" from the secret-field signals; null when none. */
    fun asks(signals: List<Signal>): String? {
        val kinds = signals.filter { it.id == SignalId.ASKS_FOR_SECRET || it.id == SignalId.SECRET_FORM_BRAND_MISMATCH }
            .map {
                when (it.args["kind"]) {
                    "password" -> "a password"
                    "code" -> "a one-time code or PIN"
                    "card" -> "card details"
                    else -> "private details"
                }
            }
            .distinct()
        return kinds.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Why Peek didn't fetch the scanned link itself, or null when it did (SPEC §6.1: plain http
     * links aren't fetched; their warning stands and they can still be opened).
     */
    fun notFetched(capture: PeekCapture?): String? {
        if (capture == null || capture.stoppedBecause != StopReason.HTTP_TARGET || capture.hops.size > 1) return null
        return "Peek doesn’t open pages that aren’t encrypted, so it didn’t fetch this one. It still looked up how old the domain is. The warning about plain http stands, and you can still open the link."
    }

    /** The page was read, so "asks for nothing" means something. */
    fun pageWasRead(capture: PeekCapture) = capture.stoppedBecause == StopReason.FINISHED && capture.hops.lastOrNull()?.status != null
}

/** One hop in the numbered list. */
data class PeekHopRow(val number: Int, val domain: String, val url: String, val status: Long?, val level: Level?) {
    companion object {
        /** The finished report's analyses win; otherwise each hop is parsed on the spot. */
        fun rows(hops: List<PeekHop>, analyses: List<Analysis>, data: BundledData): List<PeekHopRow> =
            hops.mapIndexed { index, hop ->
                var link: LinkInfo? = null
                var level: Level? = null
                analyses.getOrNull(index)?.let {
                    level = it.level
                    link = (it.payload as? Payload.Link)?.info
                }
                if (link == null) link = webAddressParse(hop.url, data)?.link
                PeekHopRow(index + 1, link?.let(ResultLinkDisplay::headline) ?: hop.url, hop.url, hop.status, level)
            }
    }
}

/** The Peek page inside the result sheet. Opening it is the explicit action, so the fetch starts at once. */
@Composable
fun PeekPage(model: PeekModel) {
    val original = model.original
    val state by model.state.collectAsState()
    val capture by model.capture.collectAsState()
    val age by model.ageDays.collectAsState()
    // Leaving the page cancels a running Peek and coming back starts afresh; a finished one is shown again.
    LaunchedEffect(model) { model.run() }
    val report = (state as? PeekState.Finished)?.report
    val level = ResultActionPlan.effectiveLevel(original.level, report)
    val signals = analysisAllSignals(original) + report?.signals.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ReadableColumn {
            VerdictHeader(level, QrText.typeName(original.payload.typeId), signals, Modifier.testTag("peek.verdict"))
            when (val s = state) {
                is PeekState.Running ->
                    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        LoadingIndicator()
                        Text(if (s.checkingAge) "Checking the domain’s age…" else "Fetching…", style = MaterialTheme.typography.bodyLarge)
                    }
                PeekState.Idle -> Text("Starting…", style = MaterialTheme.typography.bodyLarge, color = QrColors.muted)
                PeekState.Failed -> Text("This isn’t a web link, so there is nothing to Peek at.", style = MaterialTheme.typography.bodyLarge)
                is PeekState.Finished -> Unit
            }
            PeekText.notFetched(capture)?.let { Text(it, Modifier.testTag("peek.notFetched"), style = MaterialTheme.typography.bodyLarge) }
            report?.let { ReasonList(ResultReasons.peekRows(it, original)) }
            val hops = capture?.hops.orEmpty()
            if (hops.isNotEmpty()) {
                val rows = remember(hops, report) { PeekHopRow.rows(hops, report?.hopAnalyses.orEmpty(), QrLists.shared()) }
                ResultCard(title = "Where it goes") {
                    rows.forEach { row ->
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text("${row.number}. ${row.domain}", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Caption(row.url) }
                            Caption(row.status?.let { "Answered $it" } ?: "Not fetched")
                            row.level?.takeIf { it != Level.NORMAL }?.let { VerdictLabel(it) }
                        }
                    }
                }
            }
            val shownCapture = capture
            if (report != null && shownCapture != null)
                ResultCard(title = "The page") {
                    ResultField("Title", report.title ?: "No title found")
                    ResultField("Connection", PeekText.connection(shownCapture))
                    ResultField(
                        "Asks for",
                        PeekText.asks(report.signals)
                            ?: if (PeekText.pageWasRead(shownCapture)) "No password, code or card field found" else "The page wasn’t read",
                    )
                    ResultField("Domain age", PeekText.age(age))
                }
        }
    }
}
