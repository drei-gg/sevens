package gg.seven.vault

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.vault.core.VaultCredentialKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Trial expiry, which only a build with a real trial has: the personal flavor without the build-time
 * unlock (`sevens.personalUnlock=false`, the default). The generic build is fully unlocked, so this
 * lives in androidTestPersonal; run it with `:vault:connectedPersonalDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class VaultTrialTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app
        get() = ui.activity.application as VaultApplication

    private val model
        get() = androidx.lifecycle.ViewModelProvider(ui.activity)[VaultModel::class.java]

    private val secret = "several private testing words"

    private fun waitFor(text: String, timeout: Long = 30_000) =
        ui.waitUntil(timeout) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun expiredTrialStillOpensAndExportsButGatesAdding() {
        assertFalse("needs sevens.personalUnlock=false", BuildConfig.PERSONAL_UNLOCK)
        if (model.library != null) ui.runOnUiThread { model.lock() }
        ui.waitUntil(10_000) { !model.busy }
        runBlocking { app.repository.lock() }
        app.repository.root.deleteRecursively()
        val photo =
            File(app.cacheDir, "Weekend.png").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        runBlocking {
            app.repository.prepare()
            app.repository.create("Archive", secret, VaultCredentialKind.PASSPHRASE)
            app.repository.importFile(photo.path, "Weekend.png", "public.png")
            app.repository.lock()
        }
        photo.delete()
        app.getSharedPreferences("seven-trial", 0)
            .edit()
            .clear()
            .putLong("start", System.currentTimeMillis() - 8 * 86_400_000L)
            .commit()
        ui.runOnUiThread { model.store.refresh() }
        ui.waitForIdle()
        assertFalse("trial expired", model.store.paidFeaturesAvailable)
        if (ui.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithText("Not now").performClick()
        ui.onNodeWithTag("vault.credential").performTextInput(secret)
        ui.onNodeWithTag("vault.unlock")
            .performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Weekend.png")
        // Adding is part of the unlock.
        ui.onNodeWithTag("vault.add").performClick()
        ui.onNodeWithTag("vault.add.note").performClick()
        ui.waitForIdle()
        waitFor("Write a note is part of the unlock")
        ui.onNodeWithText("Not now").performScrollTo().performClick()
        // Export is not.
        ui.onNodeWithText("Weekend.png").performClick()
        ui.onNodeWithText("Export original").performScrollTo().performClick()
        waitFor("Original ready")
        val files = (model.presentation as Presentation.Files).files
        assertEquals(4096L, files.single().length())
        ui.onNodeWithText("Done").performClick()
        ui.waitUntil(10_000) { app.repository.temporary.listFiles().isNullOrEmpty() }
    }
}
