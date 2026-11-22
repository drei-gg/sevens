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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        stringResource(R.string.seven_back),
                    )
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
    val status =
        if (purchase.sourceBuild) stringResource(R.string.seven_about_source_build_status)
        else
            entitlement.statusLine(
                LocalResources.current,
                System.currentTimeMillis(),
                content.notStartedStatus,
            )
    val version = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }
    // Section rows are built outside composition, so their words are read here.
    val unlockTitle = purchase.unlockTitle()
    val restoreLabel = stringResource(R.string.seven_restore_purchases)
    val beforeYouBuyLabel = stringResource(R.string.seven_before_you_buy)
    val versionLabel = stringResource(R.string.seven_about_version)
    val coreLabel = stringResource(R.string.seven_about_core)
    val licensesLabel = stringResource(R.string.seven_licenses)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Section(
            title =
                stringResource(
                    if (purchase.sourceBuild) R.string.seven_about_edition
                    else R.string.seven_about_trial_unlock
                ),
            footer = purchase.messageText(),
        ) {
            item(content.unlock.appName, supporting = status)
            if (entitlement != Entitlement.Unlocked) {
                action(unlockTitle) { gate.offer() }
                action(restoreLabel, enabled = !purchase.busy) { store.restore() }
            }
            item(beforeYouBuyLabel, onClick = gate::beforeYouBuy)
        }
        Section(
            title = stringResource(R.string.seven_about),
            footer = stringResource(R.string.seven_about_footer),
        ) {
            item(versionLabel, version)
            coreVersion?.let { item(coreLabel, it) }
            item(licensesLabel, onClick = { notices = true })
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
                    title = { Text(stringResource(R.string.seven_licenses)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.seven_close)) }
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
