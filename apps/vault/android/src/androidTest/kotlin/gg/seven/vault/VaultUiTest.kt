package gg.seven.vault

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.vault.core.VaultCredentialKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The SPEC's UI tour on a device: create, recovery key, note, lock, unlock, pattern, expiry, export. */
@RunWith(AndroidJUnit4::class)
class VaultUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as VaultApplication
    private val model get() = androidx.lifecycle.ViewModelProvider(ui.activity)[VaultModel::class.java]
    private val secret = "several private testing words"

    @Before
    fun prepare() {
        runBlocking { app.repository.lock() }
        app.repository.root.deleteRecursively()
        runBlocking { app.repository.prepare() }
        app.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        ui.runOnUiThread { model.store.startTrial() }
        ui.waitForIdle()
        // First launch shows Before you buy; once the trial has started its button reads Done.
        if (ui.onAllNodesWithText("Your phone already has a Locked Folder.").fetchSemanticsNodes().isNotEmpty())
            ui.onAllNodes(hasText("Look around") or hasText("Done")).onFirst().performClick()
        if (model.library != null) ui.runOnUiThread { model.lock() }
        ui.waitUntil(10_000) { !model.busy }
    }

    private fun waitFor(text: String, timeout: Long = 30_000) {
        try {
            ui.waitUntil(timeout) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            // What was on screen instead, for the test log.
            val roots = ui.onAllNodes(isRoot()).fetchSemanticsNodes().size
            for (i in 0 until roots) ui.onAllNodes(isRoot())[i].printToLog("VaultUiTest")
            throw e
        }
    }

    private fun createVault(name: String, kind: VaultCredentialKind, enter: (String) -> Unit) {
        ui.onNodeWithText("Create a vault").performScrollTo().performClick()
        ui.onNodeWithTag("vault.newName").performTextInput(name)
        if (kind == VaultCredentialKind.PATTERN) ui.onAllNodesWithTag("choice-Pattern").onLast().performClick()
        enter("vault.newPassword")
        enter("vault.repeatPassword")
        ui.onNodeWithText("I understand there is no account reset").performScrollTo().performClick()
        ui.onNodeWithTag("editor-save").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("vault.recoveryKey").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("I have saved my recovery key").performScrollTo().performClick()
        ui.onNodeWithTag("vault.continue").performScrollTo().performClick()
        waitFor(name)
    }

    @Test
    fun createNoteLockUnlockAndRecover() {
        createVault("Travel", VaultCredentialKind.PASSPHRASE) { ui.onNodeWithTag(it).performScrollTo().performTextInput(secret) }
        val recovery = runBlocking { app.repository.replaceRecoveryKey() } // a key this test knows
        ui.onNodeWithTag("vault.add").performClick()
        ui.onNodeWithTag("vault.add.note").performClick()
        ui.onNodeWithTag("vault.noteName").performTextInput("Checklist")
        ui.onNodeWithTag("vault.noteText").performTextInput("Passport")
        ui.onNodeWithTag("editor-save").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Checklist.txt")
        ui.onNodeWithTag("vault.lock").performClick()
        waitFor("Yours to keep.")
        assertNull(model.library)
        ui.onNodeWithTag("vault.credential").performTextInput("not the right words")
        ui.onNodeWithTag("vault.unlock").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("did not open a vault")
        ui.onNodeWithText("OK").performClick()
        ui.onNodeWithTag("vault.credential").performTextInput(secret)
        ui.onNodeWithTag("vault.unlock").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Checklist.txt")
        // Leaving the app locks it; coming back shows the lock screen.
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Yours to keep.")
        ui.onNodeWithText("Use a recovery key").performClick()
        ui.onNodeWithTag("vault.credential").performTextInput(recovery)
        ui.onNodeWithTag("vault.unlock").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Checklist.txt")
        ui.onNodeWithText("Checklist.txt").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("vault.editNote").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("vault.editNote").assertTextContains("Passport")
    }

    @Test
    fun patternVaultFromTappedDots() {
        val dots = listOf(0, 1, 2, 3, 4, 9, 14, 19)
        createVault("Pattern library", VaultCredentialKind.PATTERN) { tag ->
            dots.forEach { ui.onNodeWithTag("$tag.dot.$it").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
        }
        ui.onNodeWithTag("vault.lock").performClick()
        waitFor("Open your vault")
        ui.onNodeWithTag("choice-Pattern").performClick()
        // Tapping 0 then 4 fills in the crossed dots 1-3, as the core canonicalises.
        listOf(0, 4, 9, 14, 19).forEach { ui.onNodeWithTag("vault.credential.dot.$it").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
        ui.onNodeWithTag("vault.unlock").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("Pattern library")
        assertEquals("Pattern library", model.library?.name)
    }

    @Test
    fun expiredTrialStillOpensAndExportsButGatesAdding() {
        val photo = File(app.cacheDir, "Weekend.png").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        runBlocking {
            app.repository.create("Archive", secret, VaultCredentialKind.PASSPHRASE)
            app.repository.importFile(photo.path, "Weekend.png", "public.png")
            app.repository.lock()
        }
        photo.delete()
        app.getSharedPreferences("seven-trial", 0).edit().putLong("start", System.currentTimeMillis() - 8 * 86_400_000L).commit()
        ui.runOnUiThread { model.store.refresh() }
        ui.waitForIdle()
        org.junit.Assert.assertFalse("trial expired", model.store.paidFeaturesAvailable)
        if (ui.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("Not now").performClick()
        ui.onNodeWithTag("vault.credential").performTextInput(secret)
        ui.onNodeWithTag("vault.unlock").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
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
