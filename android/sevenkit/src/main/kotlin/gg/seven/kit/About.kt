@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package gg.seven.kit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Sevens' top app bar: the screen's title on the paper background, a back arrow when [onBack]
 * is given.
 */
@Composable
fun SevenTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
        },
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background,
            ),
    )
}

/**
 * Settings' shared sections (iOS AboutSection and its rows): the trial status, Unlock, Restore and
 * Before you buy; then version, the core's version and the open-source notices. Needs [SevenRoot]
 * above it. Notices are every file under `assets/notices` (SevenKit ships the GPL, the platform
 * purchase exception and Apache-2.0; apps add their libraries').
 */
@Composable
fun SevenAbout(coreVersion: String? = null, modifier: Modifier = Modifier) {
    val store = LocalSevenStore.current
    val gate = LocalSevenGate.current
    val content = LocalSevenContent.current
    val entitlement by store.entitlement.collectAsStateWithLifecycle()
    val purchase by store.purchase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notices by rememberSaveable { mutableStateOf(false) }
    val status = entitlement.statusLine(System.currentTimeMillis(), content.notStartedStatus)
    val version = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Section(title = "Trial and unlock", footer = purchase.message) {
            item(content.unlock.appName, supporting = status)
            if (entitlement != Entitlement.Unlocked) {
                action(purchase.unlockTitle()) { gate.offer() }
                action("Restore purchases", enabled = !purchase.busy) { store.restore() }
            }
            item("Before you buy", onClick = gate::beforeYouBuy)
        }
        Section(
            title = "About",
            footer =
                "Code is GPL-3.0; the source and full notices are in the public repository. " +
                    "The official name and icon are separately licensed trademarks.",
        ) {
            item("Version", version)
            coreVersion?.let { item("Core", it) }
            item("Open-source licenses", onClick = { notices = true })
        }
    }
    if (notices) NoticesDialog { notices = false }
}

/** Every file under `assets/notices`, in a full-screen dialog. */
@Composable
fun NoticesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        context.assets.list("notices").orEmpty().sorted().joinToString("\n\n\n") { name ->
            context.assets.open("notices/$name").bufferedReader().use { it.readText() }.trim()
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Open-source licenses") },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
            },
        ) { padding ->
            SelectionContainer(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                Text(
                    text,
                    Modifier.padding(Space.m),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
