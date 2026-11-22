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
import androidx.compose.ui.res.stringResource
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

/**
 * iOS `VaultRoot`: the library when a vault is open, otherwise the lock screen; then the overlays.
 */
@Composable
fun VaultApp() {
    val model: VaultModel = viewModel()
    LaunchedEffect(Unit) { model.start() }
    key(model.generation) {
        when {
            model.library == null -> LockScreen(model)
            model.needsRecoveryReplacement -> RecoveryReplacementScreen(model)
            model.needsNewSecret -> NewSecretScreen(model)
            else -> LibraryScreen(model)
        }
    }
    model.error?.let { message ->
        AlertDialog(
            onDismissRequest = { model.error = null },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text(stringResource(gg.seven.kit.R.string.seven_ok)) } },
        )
    }
    when (val shown = model.presentation) {
        is Presentation.Preview -> PreviewScreen(model, shown)
        is Presentation.Files -> FilesReadySheet(model, shown)
        is Presentation.PdfRange -> PdfRangeScreen(model, shown)
        is Presentation.TextReview -> TextReviewScreen(model, shown)
        is RecognitionQueue -> BatchRecognitionScreen(model, shown)
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
    var credential by remember { mutableStateOf("") }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    val gate = LocalSevenGate.current
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SevenTopBar(stringResource(R.string.lock_title)) },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m, vertical = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                if (kind != VaultCredentialKind.PATTERN || recovery)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                        Icon(
                            Icons.Outlined.Shield,
                            null,
                            Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.lock_heading),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.lock_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    FormHeading(stringResource(R.string.lock_open_heading))
                    val useRecovery = stringResource(R.string.lock_use_recovery)
                    Section {
                        switch(useRecovery, recovery) {
                            recovery = it
                            credential = ""
                        }
                    }
                    if (!recovery)
                        CredentialKindPicker(kind) {
                            kind = it
                            credential = ""
                        }
                    val unlock = {
                        if (credential.isNotEmpty() && !model.busy) {
                            val secret = credential
                            credential = ""
                            model.unlock(secret, recovery, kind)
                        }
                    }
                    CredentialInput(
                        if (recovery) VaultCredentialKind.PASSPHRASE else kind,
                        credential,
                        if (recovery) stringResource(R.string.recovery_key) else kind.title,
                        "vault.credential",
                        onSubmit = unlock,
                    ) {
                        credential = it
                    }
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = unlock,
                        enabled = credential.isNotEmpty() && !model.busy,
                        modifier =
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("vault.unlock"),
                    ) {
                        Text(stringResource(R.string.lock_unlock))
                    }
                    if (model.hasBiometricKey)
                        OutlinedButton(
                            onClick = { context.fragmentActivity()?.let(model::unlockBiometrics) },
                            enabled = !model.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.Outlined.Fingerprint, null)
                            Spacer(Modifier.width(Space.s))
                            Text(stringResource(R.string.lock_unlock_biometric))
                        }
                    if (model.busy) Progress(model.progress)
                }
                val create = stringResource(R.string.lock_create)
                val restore = stringResource(R.string.lock_restore)
                val about = stringResource(R.string.lock_about)
                Section {
                    action(create, Icons.Outlined.Add, enabled = !model.busy) {
                        gate.run(create) { creating = true }
                    }
                    action(
                        restore,
                        Icons.Outlined.SettingsBackupRestore,
                        enabled = !model.busy,
                    ) {
                        restoring = true
                    }
                }
                Text(
                    stringResource(R.string.lock_recovery_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Section {
                    item(
                        about,
                        icon = Icons.Outlined.Info,
                        onClick = { settings = true },
                    )
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
    if (creating) CreateVaultScreen(model) { creating = false }
    if (restoring) RestoreVaultScreen(model) { restoring = false }
}

@Composable
fun Progress(text: String) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** iOS `CreateVaultSheet`, as Material's full-screen form. */
@Composable
fun CreateVaultScreen(model: VaultModel, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val ready =
        name.isNotEmpty() &&
            kind.valid(password) &&
            password == repeated &&
            acknowledged &&
            !model.busy
    FullScreenForm(
        title = stringResource(R.string.create_title),
        onClose = onClose,
        onSave = {
            val secret = password
            password = ""
            repeated = ""
            model.create(name, secret, kind, onClose)
        },
        busy = model.busy,
        confirmDiscard = name.isNotEmpty() || password.isNotEmpty() || repeated.isNotEmpty(),
        saveEnabled = ready,
        saveLabel = stringResource(R.string.create_save),
    ) {
        TextInput(stringResource(R.string.create_name), name, Modifier.testTag("vault.newName")) { name = it }
        CredentialKindPicker(kind) {
            kind = it
            password = ""
            repeated = ""
        }
        CredentialInput(kind, password, stringResource(kind.label(KindLabel.NEW)), "vault.newPassword") {
            password = it
        }
        CredentialInput(
            kind,
            repeated,
            stringResource(kind.label(KindLabel.REPEAT)),
            "vault.repeatPassword",
        ) {
            repeated = it
        }
        if (repeated.length >= password.length && repeated.isNotEmpty() && password != repeated)
            Text(
                stringResource(R.string.credential_mismatch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        Text(
            stringResource(R.string.create_rules),
            style = MaterialTheme.typography.bodyMedium,
        )
        val acknowledgement = stringResource(R.string.create_acknowledge)
        Section { checkbox(acknowledgement, acknowledged) { acknowledged = it } }
        Text(
            stringResource(R.string.create_next),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** iOS `RecoverySheet`: shown once; it can't be dismissed until the person says they saved it. */
@Composable
fun RecoveryKeyScreen(model: VaultModel) {
    var saved by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val key = model.recoveryKey ?: return
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties =
            androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.recovery_key)) }) }) { padding ->
            Column(
                Modifier.padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.m, vertical = Space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    FormHeading(stringResource(R.string.recovery_heading))
                    Text(stringResource(R.string.recovery_body))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        SelectionContainer {
                            Text(
                                key,
                                Modifier.padding(Space.m)
                                    .fillMaxWidth()
                                    .testTag("vault.recoveryKey"),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    OutlinedButton(onClick = { copySensitive(context, key) }) {
                        Icon(Icons.Outlined.ContentCopy, null)
                        Spacer(Modifier.width(Space.s))
                        Text(stringResource(R.string.recovery_copy))
                    }
                    Text(
                        stringResource(R.string.recovery_not_retained),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val savedLabel = stringResource(R.string.recovery_saved)
                    Section { checkbox(savedLabel, saved) { saved = it } }
                    if (model.busy) Progress(model.progress)
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = model::confirmRecoveryKey,
                        enabled = saved && !model.busy,
                        modifier =
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("vault.continue"),
                    ) {
                        Text(stringResource(R.string.recovery_continue))
                    }
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/**
 * Shown instead of the library when the vault's current recovery key was never confirmed as saved
 * (Vault locked or closed while it was on screen). Nothing secret survived the lock, so the only
 * safe way on is a replacement key, through the same path as Settings.
 */
@Composable
fun RecoveryReplacementScreen(model: VaultModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SevenTopBar(stringResource(R.string.recovery_key)) {
                IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, stringResource(R.string.lock_action)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m, vertical = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Icon(Icons.Outlined.Key, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.replacement_heading),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.replacement_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.replacement_old_backups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model.busy) Progress(model.progress)
                Button(
                    shapes = ButtonDefaults.shapes(),
                    onClick = model::replaceRecoveryKey,
                    enabled = !model.busy,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("vault.replaceRecovery"),
                ) {
                    Text(stringResource(R.string.replacement_create))
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/**
 * Shown instead of the library after a restore authorised by the recovery key: the backup's own
 * unlock secret couldn't be checked against the other vaults here, so the person sets a new one
 * (the usual change, with its duplicate check) before continuing.
 */
@Composable
fun NewSecretScreen(model: VaultModel) {
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SevenTopBar(stringResource(R.string.new_secret_title)) {
                IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, stringResource(R.string.lock_action)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m, vertical = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text(
                    stringResource(R.string.new_secret_heading),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.new_secret_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                CredentialKindPicker(kind) {
                    kind = it
                    password = ""
                    repeated = ""
                }
                CredentialInput(kind, password, stringResource(kind.label(KindLabel.NEW)), "vault.newSecret") { password = it }
                CredentialInput(kind, repeated, stringResource(kind.label(KindLabel.REPEAT)), "vault.repeatNewSecret") { repeated = it }
                if (repeated.length >= password.length && repeated.isNotEmpty() && password != repeated)
                    Text(
                        stringResource(R.string.credential_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                if (model.busy) Progress(model.progress)
                val setNotice = stringResource(R.string.new_secret_set_notice)
                Button(
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        val secret = password
                        val chosen = kind
                        model.update(then = {
                            password = ""
                            repeated = ""
                            model.showNotice(setNotice)
                        }) { model.repository.changeCredential(secret, chosen) }
                    },
                    enabled = kind.valid(password) && password == repeated && !model.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("vault.setNewSecret"),
                ) {
                    Text(stringResource(R.string.new_secret_set))
                }
                Text(
                    stringResource(R.string.new_secret_old_backups),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/** Copied as sensitive, so Android 13+ hides it from the clipboard preview. */
fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText(context.getString(R.string.recovery_key), text)
    clip.description.extras =
        PersistableBundle().apply {
            putBoolean(
                if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE",
                true,
            )
        }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/** iOS `RestoreVaultSheet`. */
@Composable
fun RestoreVaultScreen(model: VaultModel, onClose: () -> Unit) {
    var file by rememberSaveable { mutableStateOf<Uri?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var credential by remember { mutableStateOf("") }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(VaultCredentialKind.PASSPHRASE) }
    val context = LocalContext.current
    val chosenBackup = stringResource(R.string.restore_chosen_backup)
    val choose =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            model.endSystemFlow()
            if (uri != null) {
                file = uri
                fileName =
                    runCatching {
                            context.contentResolver
                                .query(
                                    uri,
                                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                    null,
                                    null,
                                    null,
                                )
                                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        }
                        .getOrNull() ?: chosenBackup
            }
        }
    FullScreenForm(
        title = stringResource(R.string.restore_title),
        onClose = onClose,
        onSave = {
            val chosen = file ?: return@FullScreenForm
            val secret = credential
            credential = ""
            model.restore(chosen, secret, recovery, kind, onClose)
        },
        busy = model.busy,
        saveEnabled = file != null && credential.isNotEmpty() && !model.busy,
        saveLabel = stringResource(R.string.restore_save),
    ) {
        val chooseLabel = fileName ?: stringResource(R.string.restore_choose)
        val useRecovery = stringResource(R.string.restore_use_recovery)
        Section {
            item(
                chooseLabel,
                icon = Icons.Outlined.FileOpen,
                onClick = {
                    model.beginSystemFlow()
                    choose.launch(arrayOf("*/*"))
                },
            )
            switch(useRecovery, recovery) {
                recovery = it
                credential = ""
            }
        }
        if (!recovery)
            CredentialKindPicker(kind) {
                kind = it
                credential = ""
            }
        CredentialInput(
            if (recovery) VaultCredentialKind.PASSPHRASE else kind,
            credential,
            stringResource(if (recovery) R.string.recovery_key else kind.label(KindLabel.BACKUP)),
            "vault.restoreCredential",
        ) {
            credential = it
        }
        Text(
            stringResource(R.string.restore_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
