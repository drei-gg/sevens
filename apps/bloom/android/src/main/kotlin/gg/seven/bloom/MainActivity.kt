package gg.seven.bloom

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import gg.seven.kit.*
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val model: BloomModel by viewModels()
    private lateinit var authentication: BiometricPrompt

    /**
     * Registered on the Activity, not in the composition the lock removes, so the picker's answer
     * still arrives while the journal is locked or after recreation.
     */
    private val createDocument =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
                "application/octet-stream"
            )
        ) {
            model.picked(it)
        }
    private val openDocument =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) {
            model.importPicked(it)
        }
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rebind the callback on recreation; AndroidX retains an in-progress system prompt.
        authentication =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                            model.appLock.authenticated()
                        else
                            model.appLock.failed(getString(R.string.lock_late))
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        model.appLock.failed(getString(R.string.lock_error))
                    }
                },
            )
        model.openCare.value = intent.getBooleanExtra("open_care", false)
        enableEdgeToEdge()
        protectWindow(model.appLock.enabled.value)
        lifecycleScope.launch { model.appLock.enabled.collect(::protectWindow) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Requests made just before the journal locked are dropped, never shown over it.
                launch {
                    for (name in model.launches)
                        if (model.appLock.locked.value) model.picked(null)
                        else createDocument.launch(name)
                }
                launch {
                    for (request in model.importLaunches)
                        if (model.appLock.locked.value) model.importPicked(null)
                        else openDocument.launch(arrayOf("*/*"))
                }
                launch {
                    for (intent in model.intents)
                        if (!model.appLock.locked.value) startActivity(intent)
                }
            }
        }
        setContent {
            val locked by model.appLock.locked.collectAsStateWithLifecycle()
            SevenTheme {
                if (locked)
                    LockedJournal(model.appLock, { requestJournalAuthentication() }) {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_SECURITY_SETTINGS
                            )
                        )
                    }
                else SevenRoot(model.store, bloomContent()) { BloomApp(model) }
            }
        }
    }

    private fun protectWindow(enabled: Boolean) {
        if (enabled) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        model.openCare.value = intent.getBooleanExtra("open_care", false)
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.protectJournal()
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && model.appLock.authenticating.value) {
            authentication.cancelAuthentication()
            model.appLock.failed(getString(R.string.lock_interrupted))
        }
        super.onDestroy()
    }

    internal fun requestJournalAuthentication(change: Boolean? = null) {
        if (!model.appLock.begin(change)) return
        if (
            BiometricManager.from(this).canAuthenticate(authenticators) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            model.appLock.failed(getString(R.string.lock_no_screen_lock))
            return
        }
        authentication.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(
                    getString(
                        if (change == null) R.string.lock_prompt_unlock
                        else R.string.lock_prompt_change
                    )
                )
                .setSubtitle(getString(R.string.lock_prompt_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }
}

/** Bloom's words for SevenKit's brand moments, in the device language. */
@Composable
internal fun bloomContent() =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                stringResource(R.string.brand_title),
                listOf(
                    BeforeYouBuyContent.Paragraph(stringResource(R.string.brand_built_in)),
                    BeforeYouBuyContent.Paragraph(
                        stringResource(R.string.brand_adds),
                        withoutPrice = stringResource(R.string.brand_adds_no_price),
                    ),
                    BeforeYouBuyContent.Paragraph(stringResource(R.string.brand_platform)),
                ),
                lookAroundLabel = stringResource(R.string.brand_look_around),
            ),
        unlock =
            UnlockContent(
                stringResource(R.string.brand_app_name),
                listOf(
                    stringResource(R.string.brand_feature_journal),
                    stringResource(R.string.brand_feature_contacts),
                    stringResource(R.string.brand_feature_plans),
                    stringResource(R.string.brand_feature_exports),
                ),
            ),
        trialEnded =
            TrialEndedContent(
                listOf(
                    stringResource(R.string.brand_free_records),
                    stringResource(R.string.brand_free_doses),
                ),
                listOf(stringResource(R.string.brand_unlock_adds)),
            ),
        notStartedStatus = stringResource(R.string.brand_not_started),
    )
