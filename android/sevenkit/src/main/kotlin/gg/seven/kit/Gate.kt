@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package gg.seven.kit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * Gate: how paid entry points stay behind the unlock (port of iOS Gate, BeforeYouBuyView,
 * UnlockView and TrialEndedView). Wrap the app once:
 *
 *     SevenRoot(store, content) { AppContent() }
 *
 * then, anywhere below it:
 *
 *     val gate = LocalSevenGate.current
 *     Button(onClick = { gate.run("Peek") { openPeek() } }) { Text("Peek") }   // iOS GatedButton
 *     Gated("Make") { MakeScreen() }                                          // iOS .gated(feature:)
 *
 * Nothing is sold at launch. At most one sheet opens per launch (LaunchSheetPlan): Before you buy
 * on the first launch (two equal choices), otherwise, once, what stays free after the trial ends. The unlock sheet appears only after the
 * person taps something paid, or Unlock in Settings.
 */

/** Runs paid actions, or offers the unlock instead. */
class SevenGate
internal constructor(
    private val store: SevenStore,
    private val offerUnlock: (String?) -> Unit,
    private val openBeforeYouBuy: () -> Unit,
) {
    /** Runs [action] while paid features are available; otherwise shows the unlock sheet. */
    fun run(feature: String? = null, action: () -> Unit) {
        if (store.paidFeaturesAvailable) action() else offerUnlock(feature)
    }

    /** Shows the unlock sheet (Settings' Unlock row, a paid screen's placeholder). */
    fun offer(feature: String? = null) = offerUnlock(feature)

    /** Shows Before you buy again, from Settings. */
    fun beforeYouBuy() = openBeforeYouBuy()
}

val LocalSevenStore = staticCompositionLocalOf<SevenStore> { error("Wrap the app in SevenRoot") }
val LocalSevenContent =
    staticCompositionLocalOf<SevenContent> { error("Wrap the app in SevenRoot") }
val LocalSevenGate = staticCompositionLocalOf<SevenGate> { error("Wrap the app in SevenRoot") }

internal fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

@Composable
fun SevenRoot(store: SevenStore, content: SevenContent, body: @Composable () -> Unit) {
    val purchase by store.purchase.collectAsStateWithLifecycle()
    // The launch sheet is decided once, after the purchase source has answered (iOS waits for
    // Store.hasStarted), so an owner is never shown the trial-ended sheet, and a trial that lapses
    // mid-use never opens anything.
    var launchDecided by rememberSaveable { mutableStateOf(false) }
    var beforeYouBuy by rememberSaveable { mutableStateOf(false) }
    var trialEnded by rememberSaveable { mutableStateOf(false) }
    var unlockOpen by rememberSaveable { mutableStateOf(false) }
    var unlockFeature by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(purchase.loaded) {
        if (!purchase.loaded || launchDecided) return@LaunchedEffect
        launchDecided = true
        when (store.launchSheet) {
            LaunchSheetPlan.BeforeYouBuy -> beforeYouBuy = true
            LaunchSheetPlan.TrialEnded -> trialEnded = true
            LaunchSheetPlan.None -> Unit
        }
    }
    val gate = remember(store) {
        SevenGate(
            store,
            offerUnlock = {
                unlockFeature = it
                unlockOpen = true
            },
            openBeforeYouBuy = { beforeYouBuy = true },
        )
    }
    CompositionLocalProvider(
        LocalSevenStore provides store,
        LocalSevenContent provides content,
        LocalSevenGate provides gate,
    ) {
        body()
        when {
            beforeYouBuy -> BeforeYouBuyScreen(content.beforeYouBuy) { beforeYouBuy = false }
            trialEnded -> TrialEndedSheet(content.trialEnded) { trialEnded = false }
            unlockOpen -> UnlockSheet(unlockFeature) { unlockOpen = false }
        }
    }
}

/**
 * A paid screen (iOS `.gated(feature:)`): [content] while paid features are available; otherwise a
 * short placeholder, and the unlock sheet offered once.
 */
@Composable
fun Gated(feature: String? = null, content: @Composable () -> Unit) {
    val store = LocalSevenStore.current
    val gate = LocalSevenGate.current
    val entitlement by store.entitlement.collectAsStateWithLifecycle()
    if (entitlement.paidFeaturesAvailable) return content()
    var offered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!offered) {
            offered = true
            gate.offer(feature)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            feature?.let { "$it is part of the unlock." } ?: "This is part of the unlock.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { gate.offer(feature) }) { Text("See what the unlock adds") }
    }
}

/** Calls [onAvailable] when paid features become available while a sheet is open (iOS onChange). */
@Composable
private fun DismissWhenAvailable(onAvailable: () -> Unit) {
    val store = LocalSevenStore.current
    val entitlement by store.entitlement.collectAsStateWithLifecycle()
    val available = entitlement.paidFeaturesAvailable
    var before by remember { mutableStateOf(available) }
    LaunchedEffect(available) {
        if (available && !before) onAvailable()
        before = available
    }
}

/** A modal sheet that opens fully expanded (no half-height stop). */
@Composable
private fun rememberFullSheet(): SheetState =
    rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))

/** Equal-width stacked actions for the brand moments. */
@Composable
private fun Actions(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
    )
}

@Composable
private fun Lines(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
    }
}

/** The one red button in the app (native-polish: red only on the 7, Unlock and the stamp). */
@Composable
private fun UnlockButton(onClick: () -> Unit) {
    val store = LocalSevenStore.current
    val purchase by store.purchase.collectAsStateWithLifecycle()
    val entitlement by store.entitlement.collectAsStateWithLifecycle()
    Button(
        onClick = onClick,
        enabled = canPurchase(purchase, entitlement),
        modifier = Modifier.fillMaxWidth().testTag("seven-unlock"),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = SevenColors.unlock,
                contentColor = SevenColors.onUnlock,
            ),
    ) {
        Text(purchase.unlockTitle())
    }
}

/**
 * First launch, and again from Settings: a full-screen page in the app's own words. Before the
 * trial, two choices of equal weight; after it starts (or on a personal build), just Done.
 */
@Composable
fun BeforeYouBuyScreen(content: BeforeYouBuyContent, onFinish: () -> Unit) {
    val store = LocalSevenStore.current
    val entitlement by store.entitlement.collectAsStateWithLifecycle()
    val purchase by store.purchase.collectAsStateWithLifecycle()
    fun finish() {
        store.beforeYouBuyShown = true
        onFinish()
    }
    Dialog(
        onDismissRequest = ::finish,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.navigationBarsPadding().padding(Space.m)) {
                        Actions {
                            if (entitlement == Entitlement.NotStarted) {
                                Button(
                                    onClick = {
                                        store.startTrial()
                                        finish()
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("seven-start-trial"),
                                ) {
                                    Text(content.startTrialLabel)
                                }
                                Button(
                                    onClick = ::finish,
                                    modifier = Modifier.fillMaxWidth().testTag("seven-look-around"),
                                ) {
                                    Text(content.lookAroundLabel)
                                }
                            } else
                                Button(onClick = ::finish, modifier = Modifier.fillMaxWidth()) {
                                    Text("Done")
                                }
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.l, vertical = Space.l)
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Heading(content.title)
                content.resolved(purchase.price).forEach {
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * What the unlock adds, the Play price, Start trial (only before it starts), Unlock, Restore and a
 * plain Not now of the same size. No countdown, nothing pre-selected. Closes itself once paid
 * features become available.
 */
@Composable
fun UnlockSheet(feature: String?, onDismiss: () -> Unit) {
    val store = LocalSevenStore.current
    val content = LocalSevenContent.current.unlock
    val entitlement by store.entitlement.collectAsStateWithLifecycle()
    val purchase by store.purchase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DismissWhenAvailable(onDismiss)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFullSheet(),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l)
                .padding(bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Heading("What the unlock adds")
            feature?.let {
                Text(
                    "$it is part of the unlock.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Lines(content.features)
            val status =
                when (val e = entitlement) {
                    is Entitlement.Expired -> "Your free 7 days ended on ${day(e.endedAt)}."
                    Entitlement.Pending ->
                        "Waiting for approval. You’ll get access as soon as it comes through."
                    else -> null
                }
            // Play's own pending line would repeat the status above.
            val message = purchase.message.takeUnless { entitlement == Entitlement.Pending }
            listOfNotNull(status, message).forEach { Note(it) }
            Actions {
                if (entitlement == Entitlement.NotStarted)
                    Button(
                        onClick = store::startTrial,
                        enabled = !purchase.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start my free 7 days")
                    }
                UnlockButton { context.activity()?.let(store::unlock) }
                if (purchase.busy) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                TextButton(
                    onClick = store::restore,
                    enabled = !purchase.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Restore purchases")
                }
                // Same size and shape as the buy button, which alone is filled (SPEC §13).
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Not now")
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Once, at the first launch after the trial ends: what stays free and what the unlock keeps. */
@Composable
fun TrialEndedSheet(content: TrialEndedContent, onDismiss: () -> Unit) {
    val store = LocalSevenStore.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { store.trialEndedShown = true }
    DismissWhenAvailable(onDismiss)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFullSheet(),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l)
                .padding(bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Heading("Your free 7 days have ended")
            Text("What stays free", style = MaterialTheme.typography.titleMedium)
            Lines(content.stillFree)
            Text("What the unlock keeps", style = MaterialTheme.typography.titleMedium)
            Lines(content.unlockKeeps)
            Actions {
                UnlockButton { context.activity()?.let(store::unlock) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Not now")
                }
            }
        }
    }
}
