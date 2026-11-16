@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package gg.seven.qr

import android.app.Activity
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.Space
import gg.seven.qr.core.Analysis
import gg.seven.qr.core.MakeRequest
import gg.seven.qr.core.Payload
import gg.seven.qr.core.Severity
import gg.seven.qr.core.analysisAllSignals
import gg.seven.qr.core.knownAddressesOffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The result sheet (SPEC §3): verdict first and big, the reasons, the payload, the links inside,
 * then the actions. Every action is a tap; nothing runs by itself. Embedded links and Peek open
 * inside the sheet, with back to return.
 */

private sealed interface ResultPage {
    data class Embedded(val analysis: Analysis) : ResultPage

    data class Peek(val analysis: Analysis) : ResultPage
}

/** At the largest text sizes nothing is pinned: every action stacks at the end instead. */
@Composable
private fun largeText() = LocalDensity.current.fontScale >= 1.5f

/**
 * The sheet for one analysis. The caller presents it (from Scan, or History's Check again) and has
 * already recorded it; [onMake] opens Make with a prefill (the caller gates it).
 */
@Composable
fun ResultSheet(model: QrModel, initial: Analysis, onDismiss: () -> Unit, onMake: (MakeRequest) -> Unit) {
    var analysis by remember(initial) { mutableStateOf(initial) }
    var generation by remember { mutableIntStateOf(0) }
    var dismissedOffer by remember(initial) { mutableStateOf(false) }
    var pages by remember(initial) { mutableStateOf<List<ResultPage>>(emptyList()) }
    val known by model.knownAddresses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))

    /** The domain to ask about, or null: only for list-based warnings, once. */
    val offer = if (dismissedOffer) null else knownAddressesOffer(known, analysis)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        BackHandler(pages.isNotEmpty()) { pages = pages.dropLast(1) }
        val page = pages.lastOrNull()
        val title =
            when (page) {
                is ResultPage.Peek -> "Peek"
                else -> "Result"
            }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                if (pages.isNotEmpty())
                    IconButton(onClick = { pages = pages.dropLast(1) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                else Spacer(Modifier.width(Space.m))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(
                    onClick = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } },
                    modifier = Modifier.testTag("result.done"),
                ) {
                    Icon(Icons.Rounded.Close, "Done")
                }
            }
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "result-page", modifier = Modifier.weight(1f)) { shown ->
                when (shown) {
                    null ->
                        key(generation) {
                            ResultContent(
                                analysis = analysis,
                                quietPrimary = offer != null,
                                afterSignals =
                                    offer?.let { domain ->
                                        {
                                            KnownOffer(
                                                question = ResultKnownText.question(analysis, domain),
                                                domain = domain,
                                                onKeep = { dismissedOffer = true },
                                                onKnow = {
                                                    scope.launch {
                                                        if (model.knowAddress(domain)) {
                                                            analysis = model.analysis(analysis.code)
                                                            generation++
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    },
                                onEmbedded = { pages = pages + ResultPage.Embedded(it) },
                                onPeek = { pages = pages + ResultPage.Peek(it) },
                                onMake = onMake,
                            )
                        }
                    is ResultPage.Embedded ->
                        ResultContent(
                            analysis = shown.analysis,
                            onEmbedded = { pages = pages + ResultPage.Embedded(it) },
                            onPeek = { pages = pages + ResultPage.Peek(it) },
                            onMake = onMake,
                        )
                    is ResultPage.Peek -> PeekPage(shown.analysis)
                }
            }
        }
    }
}

/** One analysis laid out: verdict, reasons, payload, links inside, actions. */
@Composable
fun ResultContent(
    analysis: Analysis,
    onEmbedded: (Analysis) -> Unit,
    onPeek: (Analysis) -> Unit,
    onMake: (MakeRequest) -> Unit,
    quietPrimary: Boolean = false,
    afterSignals: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val gate = LocalSevenGate.current
    val scope = rememberCoroutineScope()
    val capabilities = remember { ResultCapabilities(wifiJoinAvailable = Build.VERSION.SDK_INT >= 30, canAddEvent = Handoffs.canAddEvent(context)) }
    val actions = ResultActionPlan.actions(analysis.payload, capabilities, hasText = analysis.code.text != null)
    val layout = ResultActionPlan.layout(actions, analysis.level)
    val pins = !largeText()
    var copied by remember { mutableStateOf<ResultAction?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingOpen by remember { mutableStateOf<Pair<android.net.Uri, Boolean>?>(null) }
    val wifi = (analysis.payload as? Payload.Wifi)?.info
    val wifiPlan = wifi?.let { ResultActionPlan.wifiJoinPlan(it, capabilities) } ?: WiFiJoinPlan.JOIN
    val joinLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ssid = wifi?.ssid.orEmpty()
            notice =
                if (result.resultCode != Activity.RESULT_OK) "Not joined."
                else if (Build.VERSION.SDK_INT >= 30) {
                    val codes = result.data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST).orEmpty()
                    when (codes.firstOrNull()) {
                        Settings.ADD_WIFI_RESULT_SUCCESS -> "Saved $ssid. Your phone joins it when it’s in range."
                        Settings.ADD_WIFI_RESULT_ALREADY_EXISTS -> "$ssid is already saved on this phone."
                        else -> "Couldn’t join. Try Settings › Network & internet › Wi-Fi."
                    }
                } else "Not joined."
        }

    fun open(external: Boolean) {
        val link = (analysis.payload as? Payload.Link)?.info ?: return
        val uri = Handoffs.openUri(link) ?: return
        if (ResultActionPlan.needsOpenConfirmation(analysis.level)) pendingOpen = uri to external else Handoffs.open(context, uri, external)
    }

    fun copy(action: ResultAction) {
        val text = if (action == ResultAction.COPY_PASSWORD) wifi?.password else ResultPayloadText.copyText(analysis)
        if (text.isNullOrEmpty()) return
        Handoffs.copy(context, text, sensitive = action == ResultAction.COPY_PASSWORD)
        view.confirmHaptic()
        copied = action
        scope.launch {
            delay(2_000)
            if (copied == action) copied = null
        }
    }

    val perform: (ResultAction) -> Unit = { action ->
        when (action) {
            ResultAction.OPEN -> open(external = false)
            ResultAction.PEEK -> gate.run("Peek") { onPeek(analysis) }
            ResultAction.MAKE_CODE -> ResultMakeMapping.request(analysis)?.let(onMake)
            ResultAction.SHARE -> ResultPayloadText.shareText(analysis)?.let { Handoffs.share(context, it) }
            ResultAction.COPY, ResultAction.COPY_DETAILS, ResultAction.COPY_PASSWORD -> copy(action)
            ResultAction.JOIN_WIFI ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = wifi?.let(Handoffs::wifiIntent)
                    if (intent == null) notice = "Couldn’t join. Try Settings › Network & internet › Wi-Fi."
                    else runCatching { joinLauncher.launch(intent) }.onFailure { notice = "Couldn’t join. Try Settings › Network & internet › Wi-Fi." }
                }
            ResultAction.ADD_CONTACT -> (analysis.payload as? Payload.Contact)?.let { Handoffs.addContact(context, it.info) }
            ResultAction.ADD_EVENT -> (analysis.payload as? Payload.Event)?.let { Handoffs.addEvent(context, it.info) }
            ResultAction.CALL -> (analysis.payload as? Payload.Phone)?.let { Handoffs.call(context, it.info.number) }
            ResultAction.MAIL -> (analysis.payload as? Payload.Email)?.let { Handoffs.mail(context, it.info) }
            ResultAction.MAPS -> (analysis.payload as? Payload.Geo)?.let { Handoffs.maps(context, it.info) }
            ResultAction.MESSAGE -> (analysis.payload as? Payload.Sms)?.let { Handoffs.message(context, it.info) }
            ResultAction.OPEN_PAYMENT_IN -> if (ResultActionPlan.isUriShapedPayment(analysis.payload)) Handoffs.openPayment(context, analysis.code.text)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.m).padding(bottom = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                VerdictHeader(
                    analysis.level,
                    QrText.typeName(analysis.payload.typeId),
                    analysisAllSignals(analysis),
                    Modifier.testTag("result.verdict"),
                )
                SignalList(analysis.signals)
                afterSignals?.invoke()
                PayloadView(analysis.payload, wifiPlan)
                if (pins && layout.chips.isNotEmpty())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        layout.chips.forEach { ActionButton(it, ActionRole.CHIP, copied == it, onExternal = { open(true) }, perform = perform) }
                    }
                EmbeddedList(analysis.embedded, onEmbedded)
                notice?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                }
                if (!pins)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        (listOfNotNull(layout.primary, layout.secondary) + layout.chips).forEach {
                            ActionButton(it, ActionRole.STACKED, copied == it, onExternal = { open(true) }, perform = perform)
                        }
                    }
            }
        }
        if (pins && (layout.primary != null || layout.secondary != null))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.m).padding(top = Space.ms, bottom = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 672.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        layout.primary?.let { ActionButton(it, if (quietPrimary) ActionRole.OUTLINED else ActionRole.FILLED, copied == it, onExternal = { open(true) }, perform = perform) }
                        layout.secondary?.let { ActionButton(it, ActionRole.OUTLINED, copied == it, onExternal = { open(true) }, perform = perform) }
                    }
                }
            }
        else Spacer(Modifier.navigationBarsPadding())
    }

    pendingOpen?.let { (uri, external) ->
        val domain = (analysis.payload as? Payload.Link)?.info?.let(ResultLinkDisplay::headline).orEmpty()
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Open this link?") },
            text = { Text("This looks like a scam. It would take you to $domain.") },
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text("Don’t open") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOpen = null
                        Handoffs.open(context, uri, external)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Open $domain anyway")
                }
            },
        )
    }
}

enum class ActionRole { FILLED, OUTLINED, CHIP, STACKED }

object ResultActionText {
    fun title(action: ResultAction): String =
        when (action) {
            ResultAction.OPEN -> "Open link"
            ResultAction.PEEK -> "Peek"
            ResultAction.JOIN_WIFI -> "Join Wi-Fi"
            ResultAction.COPY_PASSWORD -> "Copy password"
            ResultAction.ADD_CONTACT -> "Add to Contacts"
            ResultAction.ADD_EVENT -> "Add to Calendar"
            ResultAction.CALL -> "Call"
            ResultAction.MESSAGE -> "Message"
            ResultAction.MAIL -> "Email"
            ResultAction.MAPS -> "Open in Maps"
            ResultAction.OPEN_PAYMENT_IN -> "Open in…"
            ResultAction.COPY -> "Copy"
            ResultAction.COPY_DETAILS -> "Copy details"
            ResultAction.SHARE -> "Share"
            ResultAction.MAKE_CODE -> "Make a code"
        }

    fun icon(action: ResultAction): ImageVector =
        when (action) {
            ResultAction.OPEN -> Icons.Rounded.Public
            ResultAction.PEEK -> Icons.Rounded.Visibility
            ResultAction.JOIN_WIFI -> Icons.Rounded.Wifi
            ResultAction.COPY_PASSWORD -> Icons.Rounded.Key
            ResultAction.ADD_CONTACT -> Icons.Rounded.PersonAdd
            ResultAction.ADD_EVENT -> Icons.Rounded.EventAvailable
            ResultAction.CALL -> Icons.Rounded.Phone
            ResultAction.MESSAGE -> Icons.Rounded.Sms
            ResultAction.MAIL -> Icons.Rounded.Email
            ResultAction.MAPS -> Icons.Rounded.Map
            ResultAction.OPEN_PAYMENT_IN -> Icons.AutoMirrored.Rounded.OpenInNew
            ResultAction.COPY, ResultAction.COPY_DETAILS -> Icons.Rounded.ContentCopy
            ResultAction.SHARE -> Icons.Rounded.Share
            ResultAction.MAKE_CODE -> Icons.Rounded.QrCode2
        }
}

/** One action button. Open is a split button: the in-app browser, or the browser app from its menu. */
@Composable
private fun ActionButton(action: ResultAction, role: ActionRole, copied: Boolean, onExternal: () -> Unit, perform: (ResultAction) -> Unit) {
    val title = if (copied) "Copied" else ResultActionText.title(action)
    val icon = if (copied) Icons.Rounded.Check else ResultActionText.icon(action)
    val tag = Modifier.testTag("result.action.${action.name.lowercase()}")
    val label: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(title, textAlign = TextAlign.Center)
    }
    if (action == ResultAction.OPEN && role != ActionRole.CHIP) {
        var menu by remember { mutableStateOf(false) }
        Row(tag.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            val height = Modifier.heightIn(min = 56.dp)
            if (role == ActionRole.FILLED) Button(onClick = { perform(action) }, modifier = height.weight(1f), content = label)
            else OutlinedButton(onClick = { perform(action) }, modifier = height.weight(1f), content = label)
            Box {
                FilledTonalIconButton(onClick = { menu = true }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.MoreVert, "More ways to open")
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Open in browser") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                        onClick = {
                            menu = false
                            onExternal()
                        },
                    )
                }
            }
        }
        return
    }
    when (role) {
        ActionRole.FILLED -> Button(onClick = { perform(action) }, modifier = tag.fillMaxWidth().heightIn(min = 56.dp), content = label)
        ActionRole.OUTLINED, ActionRole.STACKED -> OutlinedButton(onClick = { perform(action) }, modifier = tag.fillMaxWidth().heightIn(min = 56.dp), content = label)
        ActionRole.CHIP ->
            AssistChip(
                onClick = { perform(action) },
                label = { Text(title) },
                leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = tag.heightIn(min = 48.dp),
            )
    }
}

/** The reasons behind a verdict, one plain sentence each. Info signals are quieter. */
@Composable
fun SignalList(signals: List<gg.seven.qr.core.Signal>) {
    if (signals.isEmpty()) return
    val rows = signals.map { SignalText.sentence(it) to (it.severity == Severity.INFO) }.distinctBy { it.first }
    Column(Modifier.padding(horizontal = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        rows.forEach { (sentence, info) ->
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Icon(if (info) Icons.Rounded.Info else Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp).padding(top = 2.dp), tint = QrColors.muted)
                Text(sentence, style = MaterialTheme.typography.bodyLarge, color = if (info) QrColors.muted else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Links found inside text, contacts, events, SMS and email, each with its own verdict. */
@Composable
private fun EmbeddedList(embedded: List<Analysis>, onOpen: (Analysis) -> Unit) {
    if (embedded.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Caption("Links inside", Modifier.padding(start = Space.xs))
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            embedded.forEachIndexed { index, item ->
                val domain = (item.payload as? Payload.Link)?.info?.let(ResultLinkDisplay::headline) ?: item.code.text.orEmpty()
                SegmentedListItem(
                    onClick = { onOpen(item) },
                    shapes = ListItemDefaults.segmentedShapes(index, embedded.size),
                    supportingContent = { VerdictLabel(item.level) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                ) {
                    Text(domain)
                }
            }
        }
    }
}

/** "I know this address" (SPEC §3): one question; Keep warning me is the default and recommended. */
@Composable
private fun KnownOffer(question: String, domain: String, onKeep: () -> Unit, onKnow: () -> Unit) {
    QrCard(Modifier.testTag("result.known.offer")) {
        Text(question, style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("result.known.keep").semantics { }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Keep warning me")
                    Text("Recommended", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onKnow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("result.known.know")) {
                Text("I know this address")
            }
            Caption("Remembers $domain on this phone. Other checks still run.")
        }
    }
}

