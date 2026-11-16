package gg.seven.qr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import gg.seven.kit.Gated
import gg.seven.kit.LocalSevenStore
import gg.seven.kit.SevenTopBar

/**
 * The app's root (SPEC §2, §13): the scan screen, with Make, History and Settings pushed over it,
 * and the result sheet over whichever screen opened it. SevenRoot (above this) shows at most one
 * launch sheet: Before you buy on the first launch, the trial-ended sheet once after the trial.
 */
@Composable
fun QrApp(model: QrModel) {
    val nav = model.nav
    val stack by nav.stack.collectAsStateWithLifecycle()
    val result by nav.result.collectAsStateWithLifecycle()
    val saveable = rememberSaveableStateHolder()
    BackHandler(stack.isNotEmpty()) { nav.pop() }
    val top = stack.lastOrNull()
    AnimatedContent(top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { route ->
        saveable.SaveableStateProvider(route?.toString() ?: "scan") {
            when (route) {
                null -> ScanScreen(model, nav)
                Route.History -> HistoryScreen(model, nav)
                is Route.HistoryDetail -> HistoryDetail(model, nav, route.id)
                Route.Settings -> SettingsScreen(model, nav)
                Route.NetworkContact -> NetworkContactScreen(nav)
                Route.KnownAddresses -> KnownAddressesScreen(model, nav)
                is Route.Make -> GatedScreen("Make", onBack = nav::pop) { MakeScreen(model, nav, route.prefill) }
            }
        }
    }
    result?.let { analysis ->
        ResultSheet(
            model,
            analysis,
            onDismiss = { nav.result.value = null },
            onMake = { request ->
                nav.result.value = null
                nav.push(Route.Make(request))
            },
        )
    }
}

/** A paid screen (iOS `.gated`): the screen, or SevenKit's placeholder under a bar with Back. */
@Composable
private fun GatedScreen(feature: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val entitlement by LocalSevenStore.current.entitlement.collectAsStateWithLifecycle()
    if (entitlement.paidFeaturesAvailable) return content()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { SevenTopBar("Make a code", onBack = onBack) }) { padding ->
        Box(Modifier.padding(padding)) { Gated(feature) { content() } }
    }
}
