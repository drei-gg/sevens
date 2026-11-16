@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.seven.kit.*
import gg.seven.vault.core.*

/** Readable width on tablets (polish guide §5.4). */
val ContentWidth = 672.dp

internal fun Context.fragmentActivity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is android.content.ContextWrapper -> baseContext.fragmentActivity()
        else -> null
    }

/** iOS `VaultRoot`: the library when a vault is open, otherwise the lock screen; then the overlays. */
@Composable
fun VaultApp() {
    val model: VaultModel = viewModel()
    LaunchedEffect(Unit) { model.start() }
    key(model.generation) {
        if (model.library != null) LibraryScreen(model) else LockScreen(model)
    }
    model.error?.let { message ->
        AlertDialog(
            onDismissRequest = { model.error = null },
            title = { Text("Could not complete") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("OK") } },
        )
    }
    when (val shown = model.presentation) {
        is Presentation.Preview -> PreviewScreen(model, shown)
        is Presentation.Files -> FilesReadySheet(model, shown)
        null -> Unit
    }
    if (model.recoveryKey != null) RecoveryKeyScreen(model)
}

/** iOS `VaultLockView`: no vault list; the secret chooses the vault. */
@Composable
fun LockScreen(model: VaultModel) {
    var settings by rememberSaveable { mutableStateOf(false) }
    if (settings) {
        BackHandler { settings = false }
        SettingsScreen(model, onBack = { settings = false })
        return
    }
    var credential by rememberSaveable { mutableStateOf("") }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    val gate = LocalSevenGate.current
    val context = LocalContext.current
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { SevenTopBar("Vault") }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.m, vertical = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                if (kind != VaultCredentialKind.PATTERN || recovery)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                        Icon(Icons.Outlined.Shield, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Yours to keep.", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Draw its pattern or enter its PIN or passphrase. Your private library stays encrypted when locked.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    FormHeading("Open your vault")
                    Section {
                        switch("Use a recovery key", recovery) {
                            recovery = it
                            credential = ""
                        }
                    }
                    if (!recovery)
                        CredentialKindPicker(kind) {
                            kind = it
                            credential = ""
                        }
                    CredentialInput(if (recovery) VaultCredentialKind.PASSPHRASE else kind, credential, if (recovery) "Recovery key" else kind.title, "vault.credential") { credential = it }
                    Button(
                        onClick = {
                            val secret = credential
                            credential = ""
                            model.unlock(secret, recovery, kind)
                        },
                        enabled = credential.isNotEmpty() && !model.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("vault.unlock"),
                    ) {
                        Text("Unlock")
                    }
                    if (model.hasBiometricKey)
                        OutlinedButton(
                            onClick = { context.fragmentActivity()?.let(model::unlockBiometrics) },
                            enabled = !model.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.Outlined.Fingerprint, null)
                            Spacer(Modifier.width(Space.s))
                            Text("Unlock with fingerprint or face")
                        }
                    if (model.busy) Progress(model.progress)
                }
                Section {
                    action("Create a vault", Icons.Outlined.Add, enabled = !model.busy) { gate.run("Create a vault") { creating = true } }
                    action("Restore encrypted backup", Icons.Outlined.SettingsBackupRestore, enabled = !model.busy) { restoring = true }
                }
                Text(
                    "A recovery key opens existing encrypted data. It cannot bring back files after the app is uninstalled unless you kept a backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Section { item("About & ownership", icon = Icons.Outlined.Info, onClick = { settings = true }) }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
    if (creating) CreateVaultScreen(model) { creating = false }
    if (restoring) RestoreVaultScreen(model) { restoring = false }
}

@Composable
fun Progress(text: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** iOS `CreateVaultSheet`, as Material's full-screen form. */
@Composable
fun CreateVaultScreen(model: VaultModel, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeated by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val ready = name.isNotEmpty() && kind.valid(password) && password == repeated && acknowledged && !model.busy
    FullScreenForm(
        title = "Create vault",
        onClose = onClose,
        onSave = {
            val secret = password
            password = ""
            repeated = ""
            onClose()
            model.create(name, secret, kind)
        },
        saveEnabled = ready,
        saveLabel = "Create",
    ) {
        TextInput("Vault name", name, Modifier.testTag("vault.newName")) { name = it }
        CredentialKindPicker(kind) {
            kind = it
            password = ""
            repeated = ""
        }
        CredentialInput(kind, password, "New " + kind.title.lowercaseTitle(), "vault.newPassword") { password = it }
        CredentialInput(kind, repeated, "Repeat " + kind.title.lowercaseTitle(), "vault.repeatPassword") { repeated = it }
        if (repeated.isNotEmpty() && password != repeated) Text("These don’t match yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(
            "Use 12+ characters, an 8–32 digit PIN, or 8+ dots. Each secret opens its own vault. Longer, unpredictable secrets resist guessing better; avoid dates, letters and simple shapes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Section { switch("I understand there is no account reset", acknowledged) { acknowledged = it } }
        Text(
            "Next, save your recovery key somewhere outside this app. Keep a separate encrypted backup too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "PIN" stays upper-case in "New PIN"; the others read as words. */
fun String.lowercaseTitle() = if (this == "PIN") this else lowercase()

/** iOS `RecoverySheet`: shown once; it can't be dismissed until the person says they saved it. */
@Composable
fun RecoveryKeyScreen(model: VaultModel) {
    var saved by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val key = model.recoveryKey ?: return
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Scaffold(topBar = { TopAppBar(title = { Text("Recovery key") }) }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.m, vertical = Space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    FormHeading("Keep this key outside Vault")
                    Text("This key can open your vault without its usual unlock secret. Store it somewhere private, such as your password manager.")
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                        SelectionContainer {
                            Text(key, Modifier.padding(Space.m).fillMaxWidth().testTag("vault.recoveryKey"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    OutlinedButton(onClick = { copySensitive(context, key) }) {
                        Icon(Icons.Outlined.ContentCopy, null)
                        Spacer(Modifier.width(Space.s))
                        Text("Copy")
                    }
                    Text("We do not retain a readable copy. A backup is still needed if the local files are lost.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Section { switch("I have saved my recovery key", saved) { saved = it } }
                    Button(
                        onClick = { model.recoveryKey = null },
                        enabled = saved,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("vault.continue"),
                    ) {
                        Text("Continue to vault")
                    }
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/** Copied as sensitive, so Android 13+ hides it from the clipboard preview. */
fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("Recovery key", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/** iOS `RestoreVaultSheet`. */
@Composable
fun RestoreVaultScreen(model: VaultModel, onClose: () -> Unit) {
    var file by rememberSaveable { mutableStateOf<Uri?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var credential by rememberSaveable { mutableStateOf("") }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    val context = LocalContext.current
    val choose =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            model.external = false
            if (uri != null) {
                file = uri
                fileName = runCatching {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }
                    .getOrNull() ?: "Chosen backup"
            }
        }
    FullScreenForm(
        title = "Restore vault",
        onClose = onClose,
        onSave = {
            val chosen = file ?: return@FullScreenForm
            val secret = credential
            credential = ""
            onClose()
            model.restore(chosen, secret, recovery, kind)
        },
        saveEnabled = file != null && credential.isNotEmpty() && !model.busy,
        saveLabel = "Restore",
    ) {
        Section {
            item(fileName ?: "Choose a .vault7 backup", icon = Icons.Outlined.FileOpen, onClick = {
                model.external = true
                choose.launch(arrayOf("*/*"))
            })
            switch("Use recovery key", recovery) {
                recovery = it
                credential = ""
            }
        }
        if (!recovery)
            CredentialKindPicker(kind) {
                kind = it
                credential = ""
            }
        CredentialInput(if (recovery) VaultCredentialKind.PASSPHRASE else kind, credential, if (recovery) "Recovery key" else "Backup " + kind.title.lowercaseTitle(), "vault.restoreCredential") { credential = it }
        Text(
            "Use the pattern, PIN, passphrase or recovery key that applied when this backup was made. The whole backup is verified before it is added. Existing vaults are never overwritten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
