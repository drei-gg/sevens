@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.qr

import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.EmptyState
import gg.seven.kit.R as KitR
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(KitR.string.seven_back)) } },
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
    val networkContact = stringResource(R.string.qr_settings_network_contact)
    val keepHistory = stringResource(R.string.qr_settings_keep_history)
    val clearHistory = stringResource(R.string.qr_settings_clear_history)
    val knownTitle = stringResource(R.string.qr_known_title)
    PushedScreen(stringResource(R.string.qr_settings_title), nav::pop) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                Section { item(networkContact, onClick = { nav.push(Route.NetworkContact) }) }
                Section(
                    title = stringResource(R.string.qr_settings_history),
                    footer =
                        stringResource(
                            when {
                                history.readOnly -> R.string.qr_history_read_only
                                locked && keep -> R.string.qr_settings_history_locked
                                else -> R.string.qr_settings_history_footer
                            }
                        ),
                ) {
                    switch(keepHistory, keep) { model.settings.setKeepHistory(it) }
                    action(clearHistory, destructive = true, enabled = !history.readOnly && history.items.isNotEmpty()) { confirmClear = true }
                }
                Section(footer = stringResource(R.string.qr_settings_known_footer)) {
                    item(knownTitle, value = known.entries.size.toString(), onClick = { nav.push(Route.KnownAddresses) })
                }
                ListsSection()
                SevenAbout(coreVersion = remember { coreVersion() })
                AboutLinks(nav)
            }
        }
    }
    if (confirmClear)
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.qr_settings_clear_title)) },
            text = { Text(stringResource(R.string.qr_settings_clear_text)) },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(KitR.string.seven_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch { model.clearHistory() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.qr_settings_clear_history))
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
    val res = LocalResources.current
    val title = ListsText.title(res, lists)
    val oldLine = stringResource(R.string.qr_lists_old)
    val resetLabel = stringResource(R.string.qr_lists_reset)
    Section(title = stringResource(R.string.qr_lists_title), footer = said) {
        item(title, supporting = if (ListsText.isOld(lists.built)) oldLine else null)
        if (updater != null)
            row { shapes ->
                SegmentedListItem(
                    onClick = {
                        if (checking) return@SegmentedListItem
                        checking = true
                        scope.launch {
                            val result = updater.check()
                            lists = QrLists.shared().listsVersion()
                            said = ListsText.sentence(res, result)
                            checking = false
                        }
                    },
                    shapes = shapes,
                    enabled = !checking,
                    modifier = Modifier.testTag("lists.check"),
                    trailingContent = if (checking) ({ LoadingIndicator(Modifier.size(24.dp)) }) else null,
                ) {
                    Text(stringResource(if (checking) R.string.qr_lists_checking else R.string.qr_lists_check), color = MaterialTheme.colorScheme.primary)
                }
            }
        if (usingDownloaded)
            action(resetLabel, enabled = !checking) {
                if (updater != null) updater.resetToBundled()
                else {
                    ListsStore(context).remove()
                    QrLists.reload(null)
                }
                lists = QrLists.shared().listsVersion()
                said = res.getString(R.string.qr_lists_reset_done)
            }
    }
}

/**
 * The rows iOS has beside SevenKit's About: What's new, the licence and support, each shown when the
 * build has it, and the DENSO WAVE trademark line, which always shows.
 */
@Composable
private fun AboutLinks(nav: QrNav) {
    val context = LocalContext.current
    val repository = BuildConfig.REPOSITORY_URL.takeIf { it.startsWith("https://") }
    val support = BuildConfig.SUPPORT_EMAIL.takeIf { it.contains('@') }
    val hasChangelog = remember { Changelog.load(context) != null }
    val whatsNew = stringResource(R.string.qr_settings_whats_new)
    val sourceCode = stringResource(R.string.qr_settings_source_code)
    val contactSupport = stringResource(R.string.qr_settings_contact_support)
    val supportSubject = stringResource(R.string.qr_settings_support_subject, stringResource(R.string.app_name))
    Section(footer = stringResource(R.string.qr_settings_trademark)) {
        if (hasChangelog) item(whatsNew, onClick = { nav.push(Route.WhatsNew) })
        repository?.let { url ->
            item(sourceCode, icon = Icons.Rounded.Language, onClick = { Handoffs.open(context, url.toUri(), external = true) })
        }
        support?.let { address ->
            item(contactSupport, supporting = address, onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)).putExtra(Intent.EXTRA_SUBJECT, supportSubject))
                }
            })
        }
    }
}

/** The bundled changelog, newest first, as headings, paragraphs and bullets. */
@Composable
fun WhatsNewScreen(nav: QrNav) {
    val context = LocalContext.current
    val blocks = remember { Changelog.load(context)?.let(Changelog::blocks).orEmpty() }
    PushedScreen(stringResource(R.string.qr_settings_whats_new), nav::pop) { padding ->
        SelectionContainer(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.xl).testTag("settings.whatsNew"),
            ) {
                ReadableColumn {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        blocks.forEachIndexed { index, block ->
                            when (block) {
                                is Changelog.Block.Heading ->
                                    Text(
                                        block.text,
                                        Modifier.padding(top = if (index == 0) 0.dp else Space.m).semantics { heading() },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                is Changelog.Block.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                                is Changelog.Block.Bullet ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                        Text("•", style = MaterialTheme.typography.bodyLarge)
                                        Text(block.text, style = MaterialTheme.typography.bodyLarge)
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** SPEC §12, row for row: every time the app contacts anyone. */
@Composable
fun NetworkContactScreen(nav: QrNav) {
    val rows =
        listOf(
            Triple(R.string.qr_network_peek_when, R.string.qr_network_peek_who, R.string.qr_network_peek_sent),
            Triple(R.string.qr_network_peek_when, R.string.qr_network_registry_who, R.string.qr_network_registry_sent),
            Triple(R.string.qr_network_open_when, R.string.qr_network_open_who, R.string.qr_network_open_sent),
            Triple(R.string.qr_network_buy_when, R.string.qr_network_buy_who, R.string.qr_network_buy_sent),
            Triple(R.string.qr_network_lists_when, R.string.qr_network_lists_who, R.string.qr_network_lists_sent),
        )
    PushedScreen(stringResource(R.string.qr_settings_network_contact), nav::pop) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).navigationBarsPadding().padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                Text(stringResource(R.string.qr_network_intro), style = MaterialTheme.typography.bodyLarge)
                Section {
                    rows.forEach { (whenText, who, sent) ->
                        content {
                            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text(stringResource(whenText), style = MaterialTheme.typography.titleMedium)
                                Caption(stringResource(R.string.qr_network_who))
                                Text(stringResource(who), style = MaterialTheme.typography.bodyLarge)
                                Caption(stringResource(R.string.qr_network_sent))
                                Text(stringResource(sent), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.qr_network_nothing_else), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.qr_network_play_listing), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** "Addresses you know" (SPEC §4.4): swipe or tap to remove one with Undo, or Forget all with a confirmation. */
@Composable
fun KnownAddressesScreen(model: QrModel, nav: QrNav) {
    val known by model.knownAddresses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmAll by remember { mutableStateOf(false) }
    val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    PushedScreen(stringResource(R.string.qr_known_title), nav::pop, snackbar) { padding ->
        if (known.entries.isEmpty())
            Box(Modifier.padding(padding).fillMaxSize().testTag("settings.known.list")) {
                EmptyState(stringResource(R.string.qr_known_empty_title), stringResource(R.string.qr_known_empty_text))
            }
        else
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().testTag("settings.known.list"),
                contentPadding = PaddingValues(start = Space.m, end = Space.m, bottom = Space.xl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(known.entries, key = { _, e -> e.domain }) { index, entry ->
                    val shapes = ListItemDefaults.segmentedShapes(index, known.entries.size)
                    val removed = stringResource(R.string.qr_known_removed, entry.domain)
                    val undo = stringResource(R.string.qr_undo)
                    val removeLabel = stringResource(R.string.qr_known_remove, entry.domain)
                    fun remove() {
                        scope.launch {
                            if (model.forgetAddress(entry.domain)) {
                                val outcome = snackbar.showSnackbar(removed, actionLabel = undo)
                                if (outcome == SnackbarResult.ActionPerformed) model.knowAddress(entry.domain)
                            }
                        }
                    }
                    // Swipe toward the start removes it, with the same Undo as the trash button.
                    SwipeToDeleteRow(removeLabel, shapes.shape, enabled = true, onDelete = ::remove, modifier = Modifier.animateItem()) {
                        SegmentedListItem(
                            shapes = shapes,
                            supportingContent = { Text(stringResource(R.string.qr_known_added, format.format(entry.added.atZone(ZoneId.systemDefault())))) },
                            trailingContent = {
                                IconButton(onClick = ::remove) { Icon(Icons.Rounded.Delete, removeLabel) }
                            },
                        ) {
                            Text(entry.domain)
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { confirmAll = true },
                        modifier = Modifier.padding(top = Space.m).testTag("settings.known.forgetAll"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.qr_known_forget_all))
                    }
                }
            }
    }
    if (confirmAll)
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.qr_known_forget_all_title)) },
            text = { Text(stringResource(R.string.qr_known_forget_all_text)) },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(KitR.string.seven_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAll = false
                        scope.launch { model.forgetAllAddresses() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.qr_known_forget_all))
                }
            },
        )
}
