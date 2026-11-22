package gg.seven.vault

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import gg.seven.kit.*
import gg.seven.vault.core.*
import java.time.Instant

private fun Instant.shown(context: android.content.Context) =
    DateUtils.formatDateTime(context, toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)

/** iOS `VaultSettings`: the open vault's sections first, then privacy, what it's built on, and About. */
@Composable
fun SettingsScreen(model: VaultModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var restore by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.notice) {
        model.notice?.let {
            snackbar.showSnackbar(it)
            model.notice = null
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding()) },
        topBar = {
            SevenTopBar(stringResource(R.string.settings_title), onBack = onBack) {
                if (model.library != null) IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, stringResource(R.string.lock_action)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.m, vertical = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                model.library?.let { library ->
                    if (model.busy) Progress(model.progress)
                    AutomaticBackupSection(model)
                    val prepareLabel = stringResource(R.string.backup_prepare)
                    val restoreLabel = stringResource(R.string.settings_restore_another)
                    val replaceLabel = stringResource(R.string.settings_replace_recovery)
                    val prepared =
                        library.backupPrepared?.let { stringResource(R.string.settings_last_prepared, it.shown(context)) }
                            ?: stringResource(R.string.settings_no_backup)
                    Section(title = stringResource(R.string.settings_backup_recovery)) {
                        action(prepareLabel, Icons.Outlined.Backup, enabled = !model.busy) { model.backup() }
                        content { Text(prepared, style = MaterialTheme.typography.bodyMedium) }
                        action(restoreLabel, Icons.Outlined.SettingsBackupRestore, enabled = !model.busy) { restore = true }
                        action(replaceLabel, Icons.Outlined.Key, enabled = !model.busy) { model.replaceRecoveryKey() }
                    }
                    Caption(stringResource(R.string.settings_backup_note))
                    val unavailable = remember { BiometricKey.unavailable(context) }
                    val unavailableText = unavailable?.wording?.text()
                    val enableLabel = stringResource(R.string.settings_biometric_enable)
                    val removeLabel = stringResource(R.string.settings_biometric_remove)
                    Section(title = stringResource(R.string.settings_biometric), footer = stringResource(R.string.settings_biometric_footer)) {
                        if (unavailableText != null) content { Text(unavailableText, style = MaterialTheme.typography.bodyMedium) }
                        action(enableLabel, Icons.Outlined.Fingerprint, enabled = unavailable == null && !model.busy) {
                            context.fragmentActivity()?.let(model::enableBiometrics)
                        }
                        if (model.hasBiometricKey) action(removeLabel, Icons.Outlined.Block, destructive = true) { model.disableBiometrics() }
                    }
                    ChangeCredentialSection(model)
                }
                val privacy = stringResource(R.string.settings_privacy_data)
                val screen = stringResource(R.string.settings_privacy_screen)
                val builtOn = stringResource(R.string.settings_built_on_text)
                Section(title = stringResource(R.string.settings_privacy)) {
                    content { Text(privacy, style = MaterialTheme.typography.bodyMedium) }
                    content { Text(screen, style = MaterialTheme.typography.bodyMedium) }
                }
                Section(title = stringResource(R.string.settings_built_on)) {
                    content { Text(builtOn, style = MaterialTheme.typography.bodyMedium) }
                }
                SevenAbout(coreVersion = remember { coreVersion() })
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
    if (restore) RestoreVaultScreen(model) { restore = false }
}

/** iOS `AutomaticBackupSection`. */
@Composable
fun AutomaticBackupSection(model: VaultModel) {
    val context = LocalContext.current
    val choose =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            model.endSystemFlow()
            if (tree != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                model.enableAutomaticBackup(tree)
            }
        }
    val pick = {
        model.beginSystemFlow()
        choose.launch(null)
    }
    val status = model.automaticStatus
    val folderLabel = stringResource(R.string.automatic_folder)
    val preparing = stringResource(R.string.automatic_preparing)
    val receipt =
        status?.copiedAt?.let { stringResource(R.string.automatic_last_copied, it.shown(context)) }
            ?: stringResource(R.string.automatic_no_copy)
    val waiting = stringResource(R.string.automatic_waiting)
    val queued = stringResource(R.string.automatic_queued)
    val failure = status?.failure?.wording?.text()
    val backUpNow = stringResource(R.string.automatic_back_up_now)
    val changeFolder = stringResource(R.string.automatic_change_folder)
    val turnOff = stringResource(R.string.automatic_turn_off)
    val chooseFolder = stringResource(R.string.automatic_choose_folder)
    Section(title = stringResource(R.string.automatic_title), footer = stringResource(R.string.automatic_footer)) {
        if (status != null) {
            item(folderLabel, status.destination, icon = Icons.Outlined.Folder)
            if (model.automaticWorking) content { Progress(preparing) }
            content {
                Text(
                    receipt,
                    Modifier.testTag("vault.backupReceipt"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (model.automaticNeedsPreparation) content { Text(waiting, style = MaterialTheme.typography.bodyMedium) }
            if (status.pendingRevision != null) content { Text(queued, style = MaterialTheme.typography.bodyMedium) }
            failure?.let { failure -> content { Text(failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
            action(backUpNow, Icons.Outlined.CloudUpload, enabled = !model.busy) { model.backUpNow() }
            action(changeFolder, Icons.Outlined.DriveFolderUpload) { pick() }
            action(turnOff, Icons.Outlined.CloudOff, destructive = true) { model.disableAutomaticBackup() }
        } else action(chooseFolder, Icons.Outlined.CreateNewFolder) { pick() }
        model.automaticMessage?.let { message -> content { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun ChangeCredentialSection(model: VaultModel) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        FormHeading(stringResource(R.string.settings_change_method))
        CredentialKindPicker(kind) {
            kind = it
            password = ""
            repeated = ""
        }
        CredentialInput(kind, password, stringResource(kind.label(KindLabel.NEW)), "vault.changeCredential") { password = it }
        CredentialInput(kind, repeated, stringResource(kind.label(KindLabel.REPEAT)), "vault.repeatChangeCredential") { repeated = it }
        val changedNotice = stringResource(R.string.settings_changed_notice)
        FilledTonalButton(
            onClick = {
                val secret = password
                val chosen = kind
                model.update(then = {
                    // A failed save keeps the retryable draft. Do not erase a newer edit made
                    // while the captured credential was being saved.
                    val unchanged = kind == chosen && password == secret && repeated == secret
                    if (kind == chosen && password == secret) password = ""
                    if (kind == chosen && repeated == secret) repeated = ""
                    if (unchanged) {
                        focus.clearFocus()
                        keyboard?.hide()
                    }
                    model.showNotice(changedNotice)
                }) { model.repository.changeCredential(secret, chosen) }
            },
            enabled = kind.valid(password) && password == repeated && !model.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.settings_change_secret))
        }
        Caption(stringResource(R.string.settings_change_note))
    }
}
