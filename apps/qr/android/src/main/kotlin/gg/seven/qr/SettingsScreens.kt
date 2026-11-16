@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.qr

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.EmptyState
import gg.seven.kit.LocalSevenStore
import gg.seven.kit.Section
import gg.seven.kit.SevenAbout
import gg.seven.kit.Space
import gg.seven.kit.action
import gg.seven.kit.content
import gg.seven.kit.item
import gg.seven.kit.switch
import gg.seven.qr.core.bundledListsVersion
import gg.seven.qr.core.coreVersion
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/* Settings and About (SPEC §11): one screen, the app's own sections first, then SevenKit's. */

@Composable
private fun PushedScreen(title: String, onBack: () -> Unit, snackbar: SnackbarHostState? = null, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        content = content,
    )
}

@Composable
fun SettingsScreen(model: QrModel, nav: QrNav) {
    val scope = rememberCoroutineScope()
    val keep by model.settings.keepHistory.collectAsStateWithLifecycle()
    val history by model.historyState.collectAsStateWithLifecycle()
    val known by model.knownAddresses.collectAsStateWithLifecycle()
    val entitlement by LocalSevenStore.current.entitlement.collectAsStateWithLifecycle()
    val locked = !entitlement.paidFeaturesAvailable
    var confirmClear by remember { mutableStateOf(false) }
    PushedScreen("Settings", nav::pop) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                Section { item("Network contact", onClick = { nav.push(Route.NetworkContact) }) }
                Section(
                    title = "History",
                    footer =
                        when {
                            history.readOnly -> history.readOnlyReason
                            locked && keep -> "Saving new scans needs the unlock. What is already saved stays readable."
                            else -> "Turning this off stops saving new codes. It doesn’t delete what is already saved."
                        },
                ) {
                    switch("Keep history", keep) { model.settings.setKeepHistory(it) }
                    action("Clear history", destructive = true, enabled = !history.readOnly && history.items.isNotEmpty()) { confirmClear = true }
                }
                Section(footer = "Addresses you told us you know stop getting list-based warnings. Every other check still runs.") {
                    item("Addresses you know", value = known.entries.size.toString(), onClick = { nav.push(Route.KnownAddresses) })
                }
                ListsSection()
                SevenAbout(coreVersion = remember { coreVersion() })
                AboutLinks()
            }
        }
    }
    if (confirmClear)
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes every saved scan and made code, with its notes. It can’t be undone.") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch { model.clearHistory() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Clear history")
                }
            },
        )
}

/** "Scam lists from …" and Check for newer lists (SPEC §4.3). Nothing is fetched until the tap. */
@Composable
private fun ListsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { ListsConfiguration.configured()?.let { ListsUpdater(context, it) } }
    val bundled = remember { bundledListsVersion() }
    var lists by remember { mutableStateOf(QrLists.shared().listsVersion()) }
    var checking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val usingDownloaded = lists.version > bundled.version
    Section(title = "Scam lists", footer = said) {
        item(
            ListsText.title(lists),
            supporting = if (ListsText.isOld(lists.built)) ListsText.OLD_LINE else null,
        )
        if (updater != null)
            row { shapes ->
                SegmentedListItem(
                    onClick = {
                        if (checking) return@SegmentedListItem
                        checking = true
                        scope.launch {
                            val result = updater.check()
                            lists = QrLists.shared().listsVersion()
                            said = ListsText.sentence(result)
                            checking = false
                        }
                    },
                    shapes = shapes,
                    enabled = !checking,
                    modifier = Modifier.testTag("lists.check"),
                    trailingContent = if (checking) ({ LoadingIndicator(Modifier.size(24.dp)) }) else null,
                ) {
                    Text(if (checking) ListsText.CHECKING else ListsText.CHECK, color = MaterialTheme.colorScheme.primary)
                }
            }
        if (usingDownloaded)
            action(ListsText.RESET, enabled = !checking) {
                if (updater != null) updater.resetToBundled()
                else {
                    ListsStore(context).remove()
                    QrLists.reload(null)
                }
                lists = QrLists.shared().listsVersion()
                said = ListsText.RESET_DONE
            }
    }
}

/** The licence, support and notices rows iOS has beside SevenKit's About. */
@Composable
private fun AboutLinks() {
    val context = LocalContext.current
    val repository = BuildConfig.REPOSITORY_URL.takeIf { it.startsWith("https://") }
    val support = BuildConfig.SUPPORT_EMAIL.takeIf { it.contains('@') }
    if (repository == null && support == null) return
    Section(footer = "QR Code is a registered trademark of DENSO WAVE INCORPORATED.") {
        repository?.let { url ->
            item("Source code (GPL-3.0)", icon = Icons.Rounded.Language, onClick = { Handoffs.open(context, Uri.parse(url), external = true) })
        }
        support?.let { address ->
            item("Contact support", supporting = address, onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)).putExtra(Intent.EXTRA_SUBJECT, "QR Code Kit support"))
                }
            })
        }
    }
}

/** SPEC §12, row for row: every time the app contacts anyone. */
@Composable
fun NetworkContactScreen(nav: QrNav) {
    val rows =
        listOf(
            Triple("You tap Peek", "The address in the code, and each redirect", "A normal page request with no cookies"),
            Triple("You tap Peek", "The public registry for that domain ending", "The domain name"),
            Triple("You tap Open", "Your browser opens the link", "Whatever your browser sends"),
            Triple("You buy or restore", "Google, through Google Play", "Handled by Google Play"),
            Triple("You tap Check for newer lists", "GitHub, where our public code lives", "A request for one file. Nothing about you or what you scanned."),
        )
    PushedScreen("Network contact", nav::pop) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                Text("This is the complete list of when this app contacts anyone.", style = MaterialTheme.typography.bodyLarge)
                Section {
                    rows.forEach { (whenText, who, sent) ->
                        content {
                            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text(whenText, style = MaterialTheme.typography.titleMedium)
                                Caption("Who we contact")
                                Text(who, style = MaterialTheme.typography.bodyLarge)
                                Caption("What’s sent")
                                Text(sent, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Text("Anything not listed here doesn’t happen.", style = MaterialTheme.typography.titleMedium)
                Text("Google Play data safety: no data collected.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** "Addresses you know" (SPEC §4.4): remove one with Undo, or Forget all with a confirmation. */
@Composable
fun KnownAddressesScreen(model: QrModel, nav: QrNav) {
    val known by model.knownAddresses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmAll by remember { mutableStateOf(false) }
    val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    PushedScreen("Addresses you know", nav::pop, snackbar) { padding ->
        if (known.entries.isEmpty())
            Box(Modifier.padding(padding).fillMaxSize().testTag("settings.known.list")) {
                EmptyState("No addresses yet", "You haven’t told us you know any addresses.")
            }
        else
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().testTag("settings.known.list"),
                contentPadding = PaddingValues(start = Space.m, end = Space.m, bottom = Space.xl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(known.entries, key = { _, e -> e.domain }) { index, entry ->
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index, known.entries.size),
                        supportingContent = { Text("Added " + format.format(entry.added.atZone(ZoneId.systemDefault()))) },
                        trailingContent = {
                            IconButton(onClick = {
                                scope.launch {
                                    if (model.forgetAddress(entry.domain)) {
                                        val outcome = snackbar.showSnackbar("Removed ${entry.domain}", actionLabel = "Undo")
                                        if (outcome == SnackbarResult.ActionPerformed) model.knowAddress(entry.domain)
                                    }
                                }
                            }) {
                                Icon(Icons.Rounded.Delete, "Remove ${entry.domain}")
                            }
                        },
                    ) {
                        Text(entry.domain)
                    }
                }
                item {
                    TextButton(
                        onClick = { confirmAll = true },
                        modifier = Modifier.padding(top = Space.m).testTag("settings.known.forgetAll"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Forget all")
                    }
                }
            }
    }
    if (confirmAll)
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Forget all addresses?") },
            text = { Text("These addresses will get their warnings again. It can’t be undone.") },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAll = false
                        scope.launch { model.forgetAllAddresses() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Forget all")
                }
            },
        )
}
