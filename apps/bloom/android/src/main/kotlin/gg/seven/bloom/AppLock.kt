package gg.seven.bloom

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Device-local UI protection. Background reminders still use the encrypted repository. */
internal class JournalLock(
    initiallyEnabled: Boolean,
    /** Shown when the setting could not be stored. */
    private val unsaved: String,
    private val persist: (Boolean) -> Boolean,
) {
    val enabled = MutableStateFlow(initiallyEnabled)
    val locked = MutableStateFlow(initiallyEnabled)
    val authenticating = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private var requested: Request? = null

    private sealed interface Request {
        data object Unlock : Request

        data class Change(val enabled: Boolean) : Request
    }

    fun begin(change: Boolean? = null): Boolean {
        if (authenticating.value) return false
        requested = change?.let(Request::Change) ?: Request.Unlock
        authenticating.value = true
        message.value = null
        return true
    }

    fun authenticated() {
        val request = requested ?: return
        requested = null
        authenticating.value = false
        if (request is Request.Change) {
            if (!persist(request.enabled)) {
                message.value = unsaved
                return
            }
            enabled.value = request.enabled
        }
        locked.value = false
        message.value = null
    }

    fun failed(message: String) {
        requested = null
        authenticating.value = false
        this.message.value = message
    }

    fun protect() {
        if (enabled.value) locked.value = true
    }

    companion object {
        // KTX edit(commit = true) returns Unit and discards the durable-write result.
        @android.annotation.SuppressLint("UseKtx")
        fun stored(context: Context): JournalLock {
            val preferences = context.getSharedPreferences("bloom-device-protection", 0)
            return JournalLock(
                preferences.getBoolean("enabled", false),
                context.getString(R.string.lock_save_failed),
            ) {
                preferences.edit().putBoolean("enabled", it).commit()
            }
        }
    }
}

@Composable
internal fun JournalLockSettings(model: BloomModel) {
    val enabled by model.appLock.enabled.collectAsStateWithLifecycle()
    val working by model.appLock.authenticating.collectAsStateWithLifecycle()
    val message by model.appLock.message.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? MainActivity
    val require = stringResource(R.string.lock_require)
    val lockNow = stringResource(R.string.lock_now)
    Section(
        title = stringResource(R.string.lock_title),
        footer = stringResource(R.string.lock_footer),
    ) {
        switch(require, enabled, enabled = !working) {
            activity?.requestJournalAuthentication(it)
        }
        if (enabled) action(lockNow, Icons.Outlined.Lock) { model.protectJournal() }
        message?.let { content { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
internal fun LockedJournal(lock: JournalLock, unlock: () -> Unit, deviceSettings: () -> Unit) {
    val working by lock.authenticating.collectAsStateWithLifecycle()
    val message by lock.message.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(Space.l),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(48.dp))
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.lock_locked), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.lock_authenticate))
            Spacer(Modifier.height(Space.l))
            Button(
                onClick = unlock,
                enabled = !working,
                modifier = Modifier.testTag("journal.unlock"),
            ) {
                Text(stringResource(if (working) R.string.lock_authenticating else R.string.lock_unlock))
            }
            message?.let {
                Spacer(Modifier.height(Space.m))
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = deviceSettings, enabled = !working) {
                    Text(stringResource(R.string.lock_device_settings))
                }
            }
        }
    }
}
