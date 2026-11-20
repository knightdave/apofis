package xyz.rycerz.apofis

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StringsParityTest {
    private val english = File("src/main/res/values/strings.xml")
    private val polish = File("src/main/res/values-pl/strings.xml")

    private fun elements(file: File, tag: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun names(file: File, translatableOnly: Boolean) = (elements(file, "string") + elements(file, "plurals"))
        .filter { !translatableOnly || it.getAttribute("translatable") != "false" }
        .map { it.getAttribute("name") }
        .toSet()

    @Test
    fun everyTranslatableStringHasPolish() {
        val en = names(english, translatableOnly = true)
        val pl = names(polish, translatableOnly = false)
        assertTrue(en.isNotEmpty())
        assertEquals("missing in values-pl", emptySet<String>(), en - pl)
        assertEquals("only in values-pl", emptySet<String>(), pl - names(english, translatableOnly = false))
        for (plural in elements(polish, "plurals")) {
            val items = elements(polish, "item").filter { it.parentNode == plural }.map { it.getAttribute("quantity") }.toSet()
            assertTrue("${plural.getAttribute("name")} has $items", items.containsAll(setOf("one", "few", "many", "other")))
        }
    }

    @Test
    fun noDashes() {
        for (file in listOf(english, polish)) {
            val text = file.readText()
            assertFalse("$file has an en dash", text.contains('\u2013'))
            assertFalse("$file has an em dash", text.contains('\u2014'))
        }
    }
}
