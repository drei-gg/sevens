@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)

package gg.seven.qr

import android.app.Activity
import android.provider.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.R as KitR
import gg.seven.kit.Space
import gg.seven.qr.core.Analysis
import gg.seven.qr.core.MakeRequest
import gg.seven.qr.core.Payload
import gg.seven.qr.core.PeekReport
import gg.seven.qr.core.Severity
import gg.seven.qr.core.analysisAllSignals
import gg.seven.qr.core.knownAddressesOffer
import gg.seven.qr.core.peekAvailable
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
@Composable private fun largeText() = LocalDensity.current.fontScale >= 1.5f

/**
 * The sheet for one analysis. The caller presents it (from Scan, or History's Check again) and has
 * already recorded it; [onMake] opens Make with a prefill (the caller gates it).
 */
@Composable
fun ResultSheet(
    model: QrModel,
    initial: Analysis,
    onDismiss: () -> Unit,
    onMake: (MakeRequest) -> Unit,
) {
    var analysis by remember(initial) { mutableStateOf(initial) }
    var generation by remember { mutableIntStateOf(0) }
    var dismissedOffer by remember(initial) { mutableStateOf(false) }
    var pages by remember(initial) { mutableStateOf<List<ResultPage>>(emptyList()) }
    val known by model.knownAddresses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    /**
     * Each analysis's one Peek, made when its page is opened and kept after it closes, so the
     * verdict and Open's confirmation keep what a finished Peek found.
     */
    val peeks = remember(initial) { mutableStateMapOf<Analysis, PeekModel>() }
    fun peekFor(target: Analysis): PeekModel = peeks.getOrPut(target) { PeekModel(target, known) }
    val state =
        rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))

    /** The domain to ask about, or null: only for list-based warnings, once. */
    val offer = if (dismissedOffer) null else knownAddressesOffer(known, analysis)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets.statusBars },
    ) {
        BackHandler(pages.isNotEmpty()) { pages = pages.dropLast(1) }
        val page = pages.lastOrNull()
        val title = stringResource(if (page is ResultPage.Peek) R.string.qr_peek else R.string.qr_result_title)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pages.isNotEmpty())
                    IconButton(onClick = { pages = pages.dropLast(1) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(KitR.string.seven_back))
                    }
                else Spacer(Modifier.width(Space.m))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(
                    onClick = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } },
                    modifier = Modifier.testTag("result.done"),
                ) {
                    Icon(Icons.Rounded.Close, stringResource(KitR.string.seven_done))
                }
            }
            AnimatedContent(
                page,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "result-page",
                modifier = Modifier.weight(1f),
            ) { shown ->
                when (shown) {
                    null ->
                        key(generation) {
                            ResultContent(
                                analysis = analysis,
                                peek = peeks[analysis],
                                quietPrimary = offer != null,
                                afterSignals =
                                    offer?.let { domain ->
                                        {
                                            KnownOffer(
                                                question =
                                                    ResultKnownText.question(LocalResources.current, analysis, domain),
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
                                onPeek = {
                                peekFor(it)
                                pages = pages + ResultPage.Peek(it)
                            },
                                onMake = onMake,
                            )
                        }
                    is ResultPage.Embedded ->
                        ResultContent(
                            analysis = shown.analysis,
                            peek = peeks[shown.analysis],
                            onEmbedded = { pages = pages + ResultPage.Embedded(it) },
                            onPeek = {
                                peekFor(it)
                                pages = pages + ResultPage.Peek(it)
                            },
                            onMake = onMake,
                        )
                    is ResultPage.Peek -> peeks[shown.analysis]?.let { PeekPage(it) }
                }
            }
        }
    }
}

/** One analysis laid out: verdict, reasons, payload, links inside, actions. */
@Composable
fun ResultContent(
    analysis: Analysis,
    peek: PeekModel? = null,
    onEmbedded: (Analysis) -> Unit,
    onPeek: (Analysis) -> Unit,
    onMake: (MakeRequest) -> Unit,
    quietPrimary: Boolean = false,
    afterSignals: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val view = LocalView.current
    val gate = LocalSevenGate.current
    val scope = rememberCoroutineScope()
    val peekName = stringResource(R.string.qr_peek)
    val capabilities = remember { ResultCapabilities(canAddEvent = Handoffs.canAddEvent(context)) }
    // A finished Peek raises the verdict for this result: shown, ordered and asked about on Open.
    val peekReport by
        produceState<PeekReport?>(null, peek) {
            value = null
            peek?.state?.collect { value = (it as? PeekState.Finished)?.report }
        }
    val level = ResultActionPlan.effectiveLevel(analysis.level, peekReport)
    val canPeek = remember(analysis) { peekAvailable(analysis) }
    val actions =
        ResultActionPlan.actions(
            analysis.payload,
            capabilities,
            hasText = analysis.code.text != null,
            canPeek = canPeek,
        )
    val layout = ResultActionPlan.layout(actions, level)
    val pins = !largeText()
    var copied by remember { mutableStateOf<ResultAction?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingOpen by remember { mutableStateOf<Pair<android.net.Uri, Boolean>?>(null) }
    val wifi = (analysis.payload as? Payload.Wifi)?.info
    val wifiPlan =
        wifi?.let { ResultActionPlan.wifiJoinPlan(it) } ?: WiFiJoinPlan.JOIN
    val joinLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val ssid = wifi?.ssid.orEmpty()
            notice =
                if (result.resultCode != Activity.RESULT_OK) res.getString(R.string.qr_wifi_not_joined)
                else {
                    val codes =
                        result.data
                            ?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
                            .orEmpty()
                    when (codes.firstOrNull()) {
                        Settings.ADD_WIFI_RESULT_SUCCESS -> res.getString(R.string.qr_wifi_saved, ssid)
                        Settings.ADD_WIFI_RESULT_ALREADY_EXISTS -> res.getString(R.string.qr_wifi_already_saved, ssid)
                        else -> res.getString(R.string.qr_wifi_join_failed)
                    }
                }
        }

    fun open(external: Boolean) {
        val link = (analysis.payload as? Payload.Link)?.info ?: return
        val uri = Handoffs.openUri(link) ?: return
        if (ResultActionPlan.needsOpenConfirmation(analysis.level, peekReport)) pendingOpen = uri to external
        else Handoffs.open(context, uri, external)
    }

    fun copy(action: ResultAction) {
        val text =
            if (action == ResultAction.COPY_PASSWORD) wifi?.password
            else ResultPayloadText.copyText(res, analysis)
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
            ResultAction.PEEK -> gate.run(peekName) { onPeek(analysis) }
            ResultAction.MAKE_CODE -> ResultMakeMapping.request(analysis)?.let(onMake)
            ResultAction.SHARE ->
                ResultPayloadText.shareText(analysis)?.let { Handoffs.share(context, it) }
            ResultAction.COPY,
            ResultAction.COPY_DETAILS,
            ResultAction.COPY_PASSWORD -> copy(action)
            ResultAction.JOIN_WIFI -> {
                val intent = wifi?.let(Handoffs::wifiIntent)
                if (intent == null) notice = res.getString(R.string.qr_wifi_join_failed)
                else
                    runCatching { joinLauncher.launch(intent) }
                        .onFailure { notice = res.getString(R.string.qr_wifi_join_failed) }
            }
            ResultAction.WIFI_SETTINGS ->
                if (!Handoffs.wifiSettings(context))
                    notice = res.getString(R.string.qr_wifi_settings_failed)
            ResultAction.ADD_CONTACT ->
                (analysis.payload as? Payload.Contact)?.let {
                    Handoffs.addContact(context, it.info)
                }
            ResultAction.ADD_EVENT ->
                (analysis.payload as? Payload.Event)?.let { Handoffs.addEvent(context, it.info) }
            ResultAction.CALL ->
                (analysis.payload as? Payload.Phone)?.let { Handoffs.call(context, it.info.number) }
            ResultAction.MAIL ->
                (analysis.payload as? Payload.Email)?.let { Handoffs.mail(context, it.info) }
            ResultAction.MAPS ->
                (analysis.payload as? Payload.Geo)?.let { Handoffs.maps(context, it.info) }
            ResultAction.MESSAGE ->
                (analysis.payload as? Payload.Sms)?.let { Handoffs.message(context, it.info) }
            ResultAction.OPEN_PAYMENT_IN ->
                if (ResultActionPlan.isUriShapedPayment(analysis.payload))
                    Handoffs.openPayment(context, analysis.code.text)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m)
                .padding(bottom = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                VerdictHeader(
                    level,
                    QrText.typeName(res, analysis.payload.typeId),
                    analysisAllSignals(analysis) + peekReport?.signals.orEmpty(),
                    Modifier.testTag("result.verdict"),
                )
                ReasonList(ResultReasons.rows(res, analysis, peekReport))
                afterSignals?.invoke()
                PayloadView(analysis.payload, wifiPlan)
                if (pins && layout.chips.isNotEmpty())
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        layout.chips.forEach {
                            ActionButton(
                                it,
                                ActionRole.CHIP,
                                copied == it,
                                onExternal = { open(true) },
                                perform = perform,
                            )
                        }
                    }
                EmbeddedList(analysis.embedded, onEmbedded)
                notice?.let {
                    Text(
                        it,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (!pins)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        (listOfNotNull(layout.primary, layout.secondary) + layout.chips).forEach {
                            ActionButton(
                                it,
                                ActionRole.STACKED,
                                copied == it,
                                onExternal = { open(true) },
                                perform = perform,
                            )
                        }
                    }
            }
        }
        if (pins && (layout.primary != null || layout.secondary != null))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Space.m)
                        .padding(top = Space.ms, bottom = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.widthIn(max = 672.dp),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        layout.primary?.let {
                            ActionButton(
                                it,
                                if (quietPrimary) ActionRole.OUTLINED else ActionRole.FILLED,
                                copied == it,
                                onExternal = { open(true) },
                                perform = perform,
                            )
                        }
                        layout.secondary?.let {
                            ActionButton(
                                it,
                                ActionRole.OUTLINED,
                                copied == it,
                                onExternal = { open(true) },
                                perform = perform,
                            )
                        }
                    }
                }
            }
        else Spacer(Modifier.navigationBarsPadding())
    }

    pendingOpen?.let { (uri, external) ->
        val domain =
            (analysis.payload as? Payload.Link)?.info?.let(ResultLinkDisplay::headline).orEmpty()
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text(stringResource(R.string.qr_open_confirm_title)) },
            text = { Text(stringResource(R.string.qr_open_confirm_text, domain)) },
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text(stringResource(R.string.qr_open_confirm_dont)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOpen = null
                        Handoffs.open(context, uri, external)
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.qr_open_confirm_anyway, domain))
                }
            },
        )
    }
}

enum class ActionRole {
    FILLED,
    OUTLINED,
    CHIP,
    STACKED,
}

object ResultActionText {
    @StringRes
    fun title(action: ResultAction): Int =
        when (action) {
            ResultAction.OPEN -> R.string.qr_action_open
            ResultAction.PEEK -> R.string.qr_peek
            ResultAction.JOIN_WIFI -> R.string.qr_action_join_wifi
            ResultAction.COPY_PASSWORD -> R.string.qr_action_copy_password
            ResultAction.WIFI_SETTINGS -> R.string.qr_action_wifi_settings
            ResultAction.ADD_CONTACT -> R.string.qr_action_add_contact
            ResultAction.ADD_EVENT -> R.string.qr_action_add_event
            ResultAction.CALL -> R.string.qr_action_call
            ResultAction.MESSAGE -> R.string.qr_action_message
            ResultAction.MAIL -> R.string.qr_type_email
            ResultAction.MAPS -> R.string.qr_action_maps
            ResultAction.OPEN_PAYMENT_IN -> R.string.qr_action_open_payment_in
            ResultAction.COPY -> R.string.qr_action_copy
            ResultAction.COPY_DETAILS -> R.string.qr_action_copy_details
            ResultAction.SHARE -> R.string.qr_action_share
            ResultAction.MAKE_CODE -> R.string.qr_make_title
        }

    fun icon(action: ResultAction): ImageVector =
        when (action) {
            ResultAction.OPEN -> Icons.Rounded.Public
            ResultAction.PEEK -> Icons.Rounded.Visibility
            ResultAction.JOIN_WIFI -> Icons.Rounded.Wifi
            ResultAction.COPY_PASSWORD -> Icons.Rounded.Key
            ResultAction.WIFI_SETTINGS -> Icons.Rounded.Settings
            ResultAction.ADD_CONTACT -> Icons.Rounded.PersonAdd
            ResultAction.ADD_EVENT -> Icons.Rounded.EventAvailable
            ResultAction.CALL -> Icons.Rounded.Phone
            ResultAction.MESSAGE -> Icons.Rounded.Sms
            ResultAction.MAIL -> Icons.Rounded.Email
            ResultAction.MAPS -> Icons.Rounded.Map
            ResultAction.OPEN_PAYMENT_IN -> Icons.AutoMirrored.Rounded.OpenInNew
            ResultAction.COPY,
            ResultAction.COPY_DETAILS -> Icons.Rounded.ContentCopy
            ResultAction.SHARE -> Icons.Rounded.Share
            ResultAction.MAKE_CODE -> Icons.Rounded.QrCode2
        }
}

/**
 * One action button. Open is a split button: the in-app browser, or the browser app from its menu.
 */
@Composable
private fun ActionButton(
    action: ResultAction,
    role: ActionRole,
    copied: Boolean,
    onExternal: () -> Unit,
    perform: (ResultAction) -> Unit,
) {
    val title = stringResource(if (copied) R.string.qr_copied else ResultActionText.title(action))
    val icon = if (copied) Icons.Rounded.Check else ResultActionText.icon(action)
    val tag = Modifier.testTag("result.action.${action.name.lowercase()}")
    val label: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(title, textAlign = TextAlign.Center)
    }
    if (action == ResultAction.OPEN && role != ActionRole.CHIP) {
        var menu by remember { mutableStateOf(false) }
        Box(tag.fillMaxWidth()) {
            SplitButtonLayout(
                modifier = Modifier.fillMaxWidth(),
                leadingButton = {
                    if (role == ActionRole.FILLED)
                        SplitButtonDefaults.LeadingButton(
                            onClick = { perform(action) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            content = label,
                        )
                    else
                        SplitButtonDefaults.OutlinedLeadingButton(
                            onClick = { perform(action) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            content = label,
                        )
                },
                trailingButton = {
                    Box {
                        if (role == ActionRole.FILLED)
                            SplitButtonDefaults.TrailingButton(
                                onClick = { menu = !menu },
                                modifier = Modifier.heightIn(min = 56.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.qr_action_more_ways_to_open))
                            }
                        else
                            SplitButtonDefaults.OutlinedTrailingButton(
                                checked = menu,
                                onCheckedChange = { menu = it },
                                modifier = Modifier.heightIn(min = 56.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.qr_action_more_ways_to_open))
                            }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.qr_action_open_in_browser)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                                onClick = {
                                    menu = false
                                    onExternal()
                                },
                            )
                        }
                    }
                },
            )
        }
        return
    }
    when (role) {
        ActionRole.FILLED ->
            Button(
                onClick = { perform(action) },
                modifier = tag.fillMaxWidth().heightIn(min = 56.dp),
                content = label,
            )
        ActionRole.OUTLINED,
        ActionRole.STACKED ->
            OutlinedButton(
                onClick = { perform(action) },
                modifier = tag.fillMaxWidth().heightIn(min = 56.dp),
                content = label,
            )
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
fun SignalList(signals: List<gg.seven.qr.core.Signal>) =
    LocalResources.current.let { res -> ReasonList(signals.map { ResultReason(SignalText.sentence(res, it), it.severity == Severity.INFO) }.distinctBy { it.sentence }) }

/** Reasons under a verdict, warnings in body colour and info quieter. */
@Composable
fun ReasonList(reasons: List<ResultReason>) {
    if (reasons.isEmpty()) return
    val rows = reasons.map { it.sentence to it.isInfo }
    Column(
        Modifier.padding(horizontal = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        rows.forEach { (sentence, info) ->
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Icon(
                    if (info) Icons.Rounded.Info else Icons.Rounded.ErrorOutline,
                    null,
                    Modifier.size(20.dp).padding(top = 2.dp),
                    tint = QrColors.muted,
                )
                Text(
                    sentence,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (info) QrColors.muted else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Links found inside text, contacts, events, SMS and email, each with its own verdict. */
@Composable
private fun EmbeddedList(embedded: List<Analysis>, onOpen: (Analysis) -> Unit) {
    if (embedded.isEmpty()) return
    var showAll by rememberSaveable(embedded.size) { mutableStateOf(false) }
    val shown = remember(embedded, showAll) { ResultEmbedded.visible(embedded, showAll) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Caption(stringResource(R.string.qr_links_inside), Modifier.padding(start = Space.xs))
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            shown.forEachIndexed { index, item ->
                val domain = ResultEmbedded.domain(item)
                SegmentedListItem(
                    onClick = { onOpen(item) },
                    shapes = ListItemDefaults.segmentedShapes(index, shown.size),
                    supportingContent = { VerdictLabel(item.level) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                ) {
                    Text(domain)
                }
            }
        }
        if (!showAll && embedded.size > shown.size)
            TextButton(onClick = { showAll = true }, modifier = Modifier.testTag("result.embedded.showAll")) {
                Text(stringResource(R.string.qr_links_show_all, embedded.size))
            }
    }
}

/**
 * "I know this address" (SPEC §3): one question; Keep warning me is the default and recommended.
 */
@Composable
private fun KnownOffer(question: String, domain: String, onKeep: () -> Unit, onKnow: () -> Unit) {
    QrCard(Modifier.testTag("result.known.offer")) {
        Text(question, style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(
                onClick = onKeep,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("result.known.keep")
                        .semantics {},
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.qr_known_keep_warning))
                    Text(stringResource(R.string.qr_known_recommended), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                onClick = onKnow,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("result.known.know"),
            ) {
                Text(stringResource(R.string.qr_known_i_know))
            }
            Caption(stringResource(R.string.qr_known_remembers, domain))
        }
    }
}
