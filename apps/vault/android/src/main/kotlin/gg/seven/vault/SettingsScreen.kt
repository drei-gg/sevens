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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SevenTopBar("Settings", onBack = onBack) {
                if (model.library != null) IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, "Lock") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.m, vertical = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                model.library?.let { library ->
                    if (model.busy) Progress(model.progress)
                    AutomaticBackupSection(model)
                    Section(title = "Backup & recovery") {
                        action("Prepare encrypted backup", Icons.Outlined.Backup, enabled = !model.busy) { model.backup() }
                        content {
                            Text(
                                library.backupPrepared?.let { "Last prepared: ${it.shown(context)}. This does not confirm that a provider uploaded it." } ?: "No backup prepared yet.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        action("Restore another vault", Icons.Outlined.SettingsBackupRestore, enabled = !model.busy) { restore = true }
                        action("Generate a replacement recovery key", Icons.Outlined.Key, enabled = !model.busy) { model.replaceRecoveryKey() }
                    }
                    Caption(
                        "Save the .vault7 file outside this app, then test a restore on another device. Uninstalling Vault deletes local files. Android's own device backup does not include your vaults. Replacing a recovery key affects this device. Old backups keep the previous key until you replace those backups."
                    )
                    val unavailable = remember { BiometricKey.unavailable(context) }
                    Section(title = "Biometric convenience", footer = "Turning this on keeps this vault's key in this device's secure hardware, released only by your current fingerprints or face. Enrolling a new one turns it off. Keep the independent secret and recovery key.") {
                        if (unavailable != null) content { Text(unavailable, style = MaterialTheme.typography.bodyMedium) }
                        action("Turn on fingerprint or face unlock for this vault", Icons.Outlined.Fingerprint, enabled = unavailable == null && !model.busy) {
                            context.fragmentActivity()?.let(model::enableBiometrics)
                        }
                        if (model.hasBiometricKey) action("Remove biometric unlock", Icons.Outlined.Block, destructive = true) { model.disableBiometrics() }
                    }
                    ChangeCredentialSection(model)
                }
                Section(title = "Privacy") {
                    content {
                        Text("No Vault account, analytics, ads or storage servers. Automatic backups copy encrypted files to the folder you select. That folder's app handles any cloud storage; Google Play purchases contact Google.", style = MaterialTheme.typography.bodyMedium)
                    }
                    content {
                        Text("Vault hides its contents from the recent apps screen and screenshots, covers them when it isn't in front, and locks when you leave it. It does not protect an open session on a compromised device.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Section(title = "Built on") {
                    content {
                        Text("age encryption (rage, the Rust implementation of age-encryption.org/v1), the Android Keystore and BiometricPrompt, CameraX, Media3, Telephoto and Android's photo picker and document folders. Original files are preserved without recompression by Vault.", style = MaterialTheme.typography.bodyMedium)
                    }
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
            model.external = false
            if (tree != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                model.enableAutomaticBackup(tree)
            }
        }
    val pick = {
        model.external = true
        choose.launch(null)
    }
    Section(
        title = "Automatic encrypted backup",
        footer =
            "Choose a folder once, on this device or in a cloud drive's app. Changes are backed up while this vault is open. Completed encrypted backups can retry in the background; Android decides when. If preparation is interrupted, open this vault again to catch up. The latest three copies made by this device are kept. That folder's app handles cloud upload; a completed copy is not an upload receipt. Backups remain available after the trial ends. Turning this off leaves existing copies in your chosen folder.",
    ) {
        val status = model.automaticStatus
        if (status != null) {
            item("Folder", status.destination, icon = Icons.Outlined.Folder)
            if (model.automaticWorking) content { Progress("Preparing encrypted backup…") }
            content {
                Text(
                    status.copiedAt?.let { "Last copied to the folder: ${it.shown(context)}." } ?: "No completed copy yet.",
                    Modifier.testTag("vault.backupReceipt"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (model.automaticNeedsPreparation) content { Text("Latest changes are waiting to be prepared.", style = MaterialTheme.typography.bodyMedium) }
            if (status.pendingRevision != null) content { Text("Encrypted backup queued for delivery.", style = MaterialTheme.typography.bodyMedium) }
            status.failure?.let { failure -> content { Text(failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
            action("Back up now", Icons.Outlined.CloudUpload, enabled = !model.busy) { model.backUpNow() }
            action("Change backup folder", Icons.Outlined.DriveFolderUpload) { pick() }
            action("Turn off automatic backup", Icons.Outlined.CloudOff, destructive = true) { model.disableAutomaticBackup() }
        } else action("Choose automatic backup folder", Icons.Outlined.CreateNewFolder) { pick() }
        model.automaticMessage?.let { message -> content { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun ChangeCredentialSection(model: VaultModel) {
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        FormHeading("Change unlock method")
        CredentialKindPicker(kind) {
            kind = it
            password = ""
            repeated = ""
        }
        CredentialInput(kind, password, "New " + kind.title.lowercaseTitle(), "vault.changeCredential") { password = it }
        CredentialInput(kind, repeated, "Repeat " + kind.title.lowercaseTitle(), "vault.repeatChangeCredential") { repeated = it }
        FilledTonalButton(
            onClick = {
                val secret = password
                val chosen = kind
                password = ""
                repeated = ""
                model.update(then = { model.showNotice("Unlock secret changed. Older backups still open with the old one.") }) { model.repository.changeCredential(secret, chosen) }
            },
            enabled = kind.valid(password) && password == repeated && !model.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("Change unlock secret")
        }
        Caption("Older backups still use their old credentials. Make a new backup after changing them.")
    }
}
