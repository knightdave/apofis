package xyz.rycerz.apofis

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

private val QUANTITIES = setOf("one", "few", "many", "other")

private fun parse(source: InputSource): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source)

private fun Element.childElements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

private fun resources(doc: Document) = doc.documentElement.childElements().filter { it.tagName == "string" || it.tagName == "plurals" }

/** Every translatable English (type, name) exists in Polish and nothing else does; Polish plurals have all four forms. */
internal fun parityProblems(english: Document, polish: Document): List<String> {
    val translatable = resources(english).filter { it.getAttribute("translatable") != "false" }.map { it.tagName to it.getAttribute("name") }.toSet()
    val pl = resources(polish)
    val plKeys = pl.map { it.tagName to it.getAttribute("name") }.toSet()
    val problems = mutableListOf<String>()
    (translatable - plKeys).forEach { (type, name) -> problems += "values-pl lacks $type $name" }
    (plKeys - translatable).forEach { (type, name) -> problems += "values-pl has $type $name, which values has not as a translatable $type" }
    for (plural in pl.filter { it.tagName == "plurals" }) {
        val forms = plural.childElements().filter { it.tagName == "item" }.map { it.getAttribute("quantity") }.toSet()
        if (!forms.containsAll(QUANTITIES)) problems += "plurals ${plural.getAttribute("name")} has only $forms"
    }
    return problems
}

class StringsParityTest {
    private val english = File("src/main/res/values/strings.xml")
    private val polish = File("src/main/res/values-pl/strings.xml")

    private fun xml(body: String) = parse(InputSource(StringReader("<resources>$body</resources>")))

    private val englishFixture = xml(
        """<string name="a">A</string><string name="url" translatable="false">x</string>""" +
            """<plurals name="files"><item quantity="one">%d file</item><item quantity="other">%d files</item></plurals>""",
    )

    private fun pluralPl(forms: List<String>) =
        """<plurals name="files">${forms.joinToString("") { "<item quantity=\"$it\">%d</item>" }}</plurals>"""

    @Test
    fun everyTranslatableStringHasPolish() {
        assertEquals(emptyList<String>(), parityProblems(parse(InputSource(english.inputStream())), parse(InputSource(polish.inputStream()))))
    }

    @Test
    fun checkerAcceptsValidPluralAndRejectsBrokenFiles() {
        assertEquals(emptyList<String>(), parityProblems(englishFixture, xml("""<string name="a">A</string>""" + pluralPl(listOf("one", "few", "many", "other")))))
        val missingFew = parityProblems(englishFixture, xml("""<string name="a">A</string>""" + pluralPl(listOf("one", "many", "other"))))
        assertEquals(1, missingFew.size)
        assertTrue(missingFew.single(), missingFew.single().contains("plurals files"))
        val wrongType = parityProblems(englishFixture, xml("""<string name="a">A</string><string name="files">pliki</string>"""))
        assertEquals(2, wrongType.size) // plurals files missing, string files extra
        val untranslatable = parityProblems(englishFixture, xml("""<string name="a">A</string><string name="url">y</string>""" + pluralPl(QUANTITIES.toList())))
        assertEquals(listOf("values-pl has string url, which values has not as a translatable string"), untranslatable)
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
