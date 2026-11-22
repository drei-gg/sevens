package gg.seven.bloom

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import gg.seven.kit.*
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val model: BloomModel by viewModels()
    private lateinit var authentication: BiometricPrompt
    private val authenticators: Int
        get() =
            (if (android.os.Build.VERSION.SDK_INT >= 30)
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            else BiometricManager.Authenticators.BIOMETRIC_WEAK) or
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
                            model.appLock.failed(
                                "Your records remain locked. Open the app and try again."
                            )
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        model.appLock.failed(
                            "Device authentication did not complete. Your protection setting is unchanged."
                        )
                    }
                },
            )
        model.openCare.value = intent.getBooleanExtra("open_care", false)
        enableEdgeToEdge()
        protectWindow(model.appLock.enabled.value)
        lifecycleScope.launch { model.appLock.enabled.collect(::protectWindow) }
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
                else SevenRoot(model.store, BloomContent) { BloomApp(model) }
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
            model.appLock.failed("Authentication was interrupted. Try again.")
        }
        super.onDestroy()
    }

    internal fun requestJournalAuthentication(change: Boolean? = null) {
        if (!model.appLock.begin(change)) return
        if (
            BiometricManager.from(this).canAuthenticate(authenticators) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            model.appLock.failed(
                "Set up a screen lock in Android device settings, then try again. Your records are unchanged."
            )
            return
        }
        authentication.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(
                    if (change == null) "Unlock your journal" else "Change journal protection"
                )
                .setSubtitle("Use your device’s fingerprint, face or screen lock")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }
}

internal val BloomContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                "Your phone already has a calendar.",
                listOf(
                    BeforeYouBuyContent.Paragraph(
                        "Calendar, Clock and Notes can keep dates, reminders and a journal. If that covers you, keep your money."
                    ),
                    BeforeYouBuyContent.Paragraph(
                        "This brings your cycle, symptom and private contact records together, with portable encrypted backups. Try everything for seven days, then {price}, once. Nothing renews.",
                        withoutPrice =
                            "Try the journal for seven days, then unlock once. Nothing renews.",
                    ),
                    BeforeYouBuyContent.Paragraph(
                        "Care reminders use Android notifications and your chosen alarm permissions. Health Connect flow transfer is manual and permission-based; the watch companion is optional in supported editions. Calendar predictions are not contraception or a diagnosis."
                    ),
                ),
                lookAroundLabel = "Look around first",
            ),
        unlock =
            UnlockContent(
                "Cycle & Health",
                listOf(
                    "Period and symptom journal",
                    "Private sex/contact records",
                    "Pill plans and dose history",
                    "Exports and encrypted backups",
                ),
            ),
        trialEnded =
            TrialEndedContent(
                listOf(
                    "Reading, correcting, deleting and exporting records",
                    "Dose records on existing plans",
                ),
                listOf("Adding new records and plans"),
            ),
        notStartedStatus = "Trial not started. Your records remain yours.",
    )
