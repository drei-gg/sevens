@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.qr

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    fun age(res: Resources, days: Long?): String =
        when {
            days == null || days < 0 -> res.getString(R.string.qr_peek_age_unknown)
            days == 0L -> res.getString(R.string.qr_peek_age_today)
            else -> days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let { res.getQuantityString(R.plurals.qr_peek_age_days, it, it) }
        }

    fun connection(res: Resources, capture: PeekCapture): String {
        if (capture.stoppedBecause == StopReason.TLS_FAILURE) return res.getString(R.string.qr_peek_connection_rejected)
        val last = capture.hops.lastOrNull()
        if (last != null && last.status == null && last.url.lowercase().startsWith("http://")) return res.getString(R.string.qr_peek_connection_plain_http)
        capture.tlsVersion?.takeIf { it.isNotEmpty() }?.let { return res.getString(R.string.qr_peek_connection_encrypted, it) }
        return res.getString(R.string.qr_peek_connection_unknown)
    }

    /** "a password, a one-time code or PIN" from the secret-field signals; null when none. */
    fun asks(res: Resources, signals: List<Signal>): String? {
        val kinds = signals.filter { it.id == SignalId.ASKS_FOR_SECRET || it.id == SignalId.SECRET_FORM_BRAND_MISMATCH }
            .map {
                res.getString(
                    when (it.args["kind"]) {
                        "password" -> R.string.qr_secret_password
                        "code" -> R.string.qr_secret_code
                        "card" -> R.string.qr_secret_card
                        else -> R.string.qr_secret_other
                    }
                )
            }
            .distinct()
        return kinds.takeIf { it.isNotEmpty() }?.joinToString(res.getString(R.string.qr_list_separator))
    }

    /**
     * Why Peek didn't fetch the scanned link itself, or null when it did (SPEC §6.1: plain http
     * links aren't fetched; their warning stands and they can still be opened).
     */
    fun notFetched(res: Resources, capture: PeekCapture?): String? {
        if (capture == null || capture.stoppedBecause != StopReason.HTTP_TARGET || capture.hops.size > 1) return null
        return res.getString(R.string.qr_peek_not_fetched)
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
    val res = LocalResources.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ReadableColumn {
            VerdictHeader(level, QrText.typeName(res, original.payload.typeId), signals, Modifier.testTag("peek.verdict"))
            when (val s = state) {
                is PeekState.Running ->
                    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        LoadingIndicator()
                        Text(stringResource(if (s.checkingAge) R.string.qr_peek_checking_age else R.string.qr_peek_fetching), style = MaterialTheme.typography.bodyLarge)
                    }
                PeekState.Idle -> Text(stringResource(R.string.qr_peek_starting), style = MaterialTheme.typography.bodyLarge, color = QrColors.muted)
                PeekState.Failed -> Text(stringResource(R.string.qr_peek_not_a_link), style = MaterialTheme.typography.bodyLarge)
                is PeekState.Finished -> Unit
            }
            PeekText.notFetched(res, capture)?.let { Text(it, Modifier.testTag("peek.notFetched"), style = MaterialTheme.typography.bodyLarge) }
            report?.let { ReasonList(ResultReasons.peekRows(res, it, original)) }
            val hops = capture?.hops.orEmpty()
            if (hops.isNotEmpty()) {
                val rows = remember(hops, report) { PeekHopRow.rows(hops, report?.hopAnalyses.orEmpty(), QrLists.shared()) }
                ResultCard(title = stringResource(R.string.qr_peek_where_it_goes)) {
                    rows.forEach { row ->
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(stringResource(R.string.qr_peek_hop, row.number, row.domain), style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Caption(row.url) }
                            Caption(row.status?.let { stringResource(R.string.qr_peek_hop_answered, it) } ?: stringResource(R.string.qr_peek_hop_not_fetched))
                            row.level?.takeIf { it != Level.NORMAL }?.let { VerdictLabel(it) }
                        }
                    }
                }
            }
            val shownCapture = capture
            if (report != null && shownCapture != null)
                ResultCard(title = stringResource(R.string.qr_peek_the_page)) {
                    ResultField(stringResource(R.string.qr_peek_title), report.title ?: stringResource(R.string.qr_peek_no_title))
                    ResultField(stringResource(R.string.qr_peek_connection), PeekText.connection(res, shownCapture))
                    ResultField(
                        stringResource(R.string.qr_peek_asks_for),
                        PeekText.asks(res, report.signals)
                            ?: stringResource(if (PeekText.pageWasRead(shownCapture)) R.string.qr_peek_asks_nothing else R.string.qr_peek_not_read),
                    )
                    ResultField(stringResource(R.string.qr_peek_domain_age), PeekText.age(res, age))
                }
        }
    }
}
