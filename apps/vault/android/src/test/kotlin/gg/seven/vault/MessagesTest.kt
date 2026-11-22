package gg.seven.vault

import gg.seven.vault.core.VaultCredentialKind
import gg.seven.vault.core.VaultException
import gg.seven.vault.core.recognitionFailureCases
import gg.seven.vault.core.vaultErrorCases
import gg.seven.vault.core.vaultIssueCases
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every case the core or the platform layers report has an English sentence in strings.xml, with
 * as many placeholders as the wording passes. The core lists its own cases (`*_cases()`), so a new
 * core case fails here (and in the exhaustive `when`s of Messages.kt) until it has words.
 */
class MessagesTest {
    private val strings = mutableMapOf<String, List<String>>()

    init {
        val document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val root = document.documentElement
        for (index in 0 until root.childNodes.length) {
            val node = root.childNodes.item(index) as? Element ?: continue
            val name = node.getAttribute("name")
            strings[node.tagName + "/" + name] =
                when (node.tagName) {
                    "plurals" -> {
                        val items = node.getElementsByTagName("item")
                        List(items.length) { items.item(it).textContent }
                    }
                    else -> listOf(node.textContent)
                }
        }
    }

    /** The resource's name for an id in R.string or R.plurals. */
    private fun name(id: Int, type: Class<*>) = type.fields.firstOrNull { it.getInt(null) == id }?.let { it.name }

    private fun placeholders(text: String) = Regex("%(\\d+\\$)?[ds]").findAll(text).count()

    private fun check(case: Any, wording: Wording) {
        val key =
            if (wording.quantity != null) name(wording.id, R.plurals::class.java)?.let { "plurals/$it" }
            else name(wording.id, R.string::class.java)?.let { "string/$it" }
        val texts = strings[key]
        assertTrue("$case: no resource for $key", texts != null && texts.isNotEmpty())
        for (text in texts!!) {
            assertTrue("$case: $key is blank", text.isNotBlank())
            assertEquals("$case: $key placeholders", wording.args.size, placeholders(text))
        }
    }

    @Test
    fun everyCoreErrorHasASentence() {
        val cases = vaultErrorCases()
        assertEquals(13, cases.size)
        val ids = cases.map { it.wording.id }
        assertEquals("distinct sentences", ids.size, ids.toSet().size)
        cases.forEach { check(it, it.wording) }
    }

    @Test
    fun everyRecognitionFailureAndIssueHasASentence() {
        recognitionFailureCases().forEach { check(it, it.wording) }
        val issues = vaultIssueCases()
        assertEquals(issues.size, issues.map { it::class }.toSet().size)
        issues.forEach { check(it, it.wording) }
    }

    @Test
    fun everyPlatformReasonHasASentence() {
        BackupFailure.Reason.entries.forEach { check(it, it.wording) }
        BiometricKey.Reason.entries.forEach { check(it, it.wording) }
        assertEquals(BackupFailure.Reason.COPY, BackupFailure.Reason.of("An older build's English sentence."))
        BackupFailure.Reason.entries.forEach { assertEquals(it, BackupFailure.Reason.of(it.code)) }
    }

    @Test
    fun everyCredentialLabelExists() {
        for (kind in VaultCredentialKind.entries) for (role in KindLabel.entries) check("$kind $role", Wording.of(kind.label(role)))
    }

    @Test
    fun countsUseTheirQuantity() {
        val wording = VaultException.WeakPin().wording
        assertEquals(null, wording.quantity)
        val issue = gg.seven.vault.core.VaultIssue.TooManyFiles(10u).wording
        assertEquals(10, issue.quantity)
        assertEquals(listOf<Any>(10), issue.args)
    }
}
