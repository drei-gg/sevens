package gg.seven.qr

import android.content.res.Resources
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The English catalog (src/main/res/values) behind a real [Resources], for JVM tests of code that
 * words things through resources. The unit-test android.jar returns defaults, so the stub
 * constructor does nothing; only the lookups the app uses are answered, by resource name via R.
 */
class TestResources : Resources(null, null, null) {
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        File("src/main/res/values").listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        plurals[e.getAttribute("name")] =
                            (0 until items.length).associate {
                                val item = items.item(it) as Element
                                item.getAttribute("quantity") to unescape(item.textContent)
                            }
                    }
                }
            }
        }
    }

    private fun name(type: Class<*>, id: Int): String =
        type.fields.firstOrNull { it.getInt(null) == id }?.name ?: error("No resource $id in ${type.simpleName}")

    override fun getString(id: Int): String = strings.getValue(name(R.string::class.java, id))

    override fun getString(id: Int, vararg formatArgs: Any?): String =
        String.format(Locale.US, getString(id), *formatArgs)

    override fun getText(id: Int): CharSequence = getString(id)

    override fun getQuantityString(id: Int, quantity: Int): String {
        val forms = plurals.getValue(name(R.plurals::class.java, id))
        return (if (quantity == 1) forms["one"] else null) ?: forms.getValue("other")
    }

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
        String.format(Locale.US, getQuantityString(id, quantity), *formatArgs)

    private fun unescape(raw: String): String {
        val text = raw.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.drop(1).dropLast(1) else it }
        return buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    when (val n = text[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        else -> append(n)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}

/** One catalog for every test. */
val testRes: Resources by lazy { TestResources() }
