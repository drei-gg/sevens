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
import gg.seven.qr.core.webAddressParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/*
 * Peek (SPEC §6): one tap, online, paid. Fetches the link, then asks the registry how old the
 * domain is, then runs the core's pure analysis of what came back. The Gate is at the button.
 */

sealed interface PeekState {
    data object Idle : PeekState

    data class Running(val checkingAge: Boolean) : PeekState

    data class Finished(val report: PeekReport) : PeekState

    /** The original isn't a link, so there is nothing to Peek at. */
    data object Failed : PeekState
}

/** One Peek of [original] (iOS PeekModel). [run] does the whole thing; cancelling it stops it. */
class PeekModel(
    val original: Analysis,
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
        if (link == null) {
            mutableState.value = PeekState.Failed
            return
        }
        mutableState.value = PeekState.Running(checkingAge = false)
        try {
            val captured = client.capture(link)
            mutableCapture.value = captured
            var age: Long? = null
            val last = captured.hops.lastOrNull()
            if (last != null && last.status != null && captured.stoppedBecause == StopReason.FINISHED) {
                val address = webAddressParse(last.url, data)
                if (address != null) {
                    mutableState.value = PeekState.Running(checkingAge = true)
                    age = client.registrationAge(address.link, data)
                }
            }
            mutableAge.value = age
            val report = withContext(Dispatchers.Default) { PeekAnalyzer(data).use { it.report(original, captured, age) } }
            mutableState.value = PeekState.Finished(report)
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
fun PeekPage(original: Analysis) {
    val model = remember(original) { PeekModel(original) }
    val state by model.state.collectAsState()
    val capture by model.capture.collectAsState()
    val age by model.ageDays.collectAsState()
    // Leaving the page cancels a running Peek; coming back starts afresh.
    LaunchedEffect(model) { model.run() }
    val report = (state as? PeekState.Finished)?.report
    val level = report?.level ?: original.level
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
            report?.let { SignalList(it.signals) }
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
