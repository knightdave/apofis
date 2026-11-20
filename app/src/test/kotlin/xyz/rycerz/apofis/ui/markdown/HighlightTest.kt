package xyz.rycerz.apofis.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.ui.GreenSeed
import xyz.rycerz.apofis.ui.PurpleSeed
import xyz.rycerz.apofis.ui.chat.Work
import xyz.rycerz.apofis.ui.chat.parseBlocks
import xyz.rycerz.apofis.ui.theme.apofisColors
import xyz.rycerz.apofis.ui.theme.contrastRatio
import xyz.rycerz.apofis.ui.theme.deriveScheme
import xyz.rycerz.apofis.ui.theme.pageFor
import xyz.rycerz.apofis.ui.theme.staticScheme

class HighlightTest {
    private data class Theme(val name: String, val dark: Boolean, val codeFill: Color, val onSurface: Color)

    /** Static and both seeds, light and dark, pure background off and on. */
    private val themes = listOf(null to "static", PurpleSeed to "purple", GreenSeed to "green").flatMap { (seed, name) ->
        listOf(false, true).flatMap { dark ->
            val base = seed?.let { if (dark) it.dark else it.light } ?: staticScheme(dark)
            listOf(false, true).map { pure ->
                val scheme = deriveScheme(base, dark, pageFor(base, dark, pure))
                Theme("$name dark=$dark pure=$pure", dark, apofisColors(scheme, dark).codeFill, scheme.onSurface)
            }
        }
    }
    private val light = themes.first()

    private fun spans(a: AnnotatedString) = a.spanStyles.map { a.text.substring(it.start, it.end) to it.item.color }

    @Test
    fun keywordStringComment() {
        val a = highlight("val x = \"s\" // c", "kotlin", dark = false, light.codeFill, light.onSurface)
        val s = spans(a).toMap()
        assertEquals(setOf("val", "\"s\"", "// c"), s.keys)
        assertEquals(3, s.values.toSet().size)
        for (c in s.values) assertTrue("$c", contrastRatio(c, light.codeFill) >= 4.5)
    }

    private val allKinds = "/* block */\nfun main() {\n    val s = \"text\" + 'c' + \"\"\"raw\"\"\"\n    return 42 + 0x1F + 3.14 // done\n}"

    @Test
    fun everyColorReadableInEveryTheme() {
        assertEquals(12, themes.size)
        for (t in themes) {
            val a = highlight(allKinds, "kt", t.dark, t.codeFill, t.onSurface)
            val used = a.spanStyles.map { it.item.color }.toSet()
            assertEquals(t.name, 4, used.size) // comment, keyword, string, number
            for (c in used + syntaxColors(t.dark, t.codeFill, t.onSurface)) {
                assertTrue("${t.name} $c: ${contrastRatio(c, t.codeFill)}", contrastRatio(c, t.codeFill) >= 4.5)
            }
        }
    }

    @Test
    fun unknownLanguageIsPlain() {
        assertTrue(highlight("x", "brainfuck", false, light.codeFill, light.onSurface).spanStyles.isEmpty())
        assertNull(languageOf(""))
        assertNull(languageOf("   "))
    }

    @Test
    fun aliases() {
        assertEquals(
            listOf(Lang.KOTLIN, Lang.SHELL, Lang.SHELL, Lang.PYTHON, Lang.TYPESCRIPT, Lang.CPP, Lang.GO, Lang.PYTHON),
            listOf("KT", "bash", "zsh", "py", "tsx", "c++", "golang", "python title=x.py").map(::languageOf),
        )
        assertEquals(Lang.COFFEESCRIPT, languageOf(" Coffee "))
        for (lang in Lang.entries) assertTrue("$lang has ${keywords(lang).size}", keywords(lang).size in 1..40)
    }

    @Test
    fun tokensPerLanguage() {
        fun kinds(code: String, lang: String) = spans(highlight(code, lang, false, light.codeFill, light.onSurface)).map { it.first }
        assertEquals(listOf("# c", "echo", "\"a#b\""), kinds("# c\necho \"a#b\" \${#x} a#b", "sh"))
        assertEquals(listOf("def", "1", "# floor // not"), kinds("def f(): 1 # floor // not", "py"))
        assertEquals(listOf("fn", "'a'"), kinds("fn f<'a>(x: &'a str) -> char { 'a' }", "rust").let { it.take(1) + it.takeLast(1) })
        assertEquals(listOf("`multi\nline`", "const"), kinds("`multi\nline` const", "js"))
        assertEquals(listOf("int", "\"x\\\"y\"", "/* a \" b */"), kinds("int s = \"x\\\"y\" /* a \" b */", "c"))
    }

    /** Code the highlighter must survive: nothing thrown, nothing dropped or changed, work linear in the length. */
    private val corpus: List<String> = listOf(
        "", " ", " \t \n\n  ", "x".repeat(HIGHLIGHT_MAX_CHARS), "a\n".repeat(HIGHLIGHT_MAX_CHARS / 2),
        "Zażółć gęślą jaźń; Привет мир; 漢字かな; مرحبا; שלום", "😀👩‍👩‍👧 val 🇵🇱 \"😀\" // 😀", "é vaĺ \"á\" 1́",
        "\uD83D".repeat(5) + "\uDE00".repeat(5), "x\uD83D\n\uDE00y", "\t\tval\tx\t=\t1", "a\u0000b \u0000\"\u0000\" // \u0000",
        "\"", "'", "`", "\"\"\"", "'''", "/*", "*/", "//", "#", "\\", "\"\\", "'\\", "\"a\\\n", "/* /* */ */", "\"\"\"\"\"\"\"",
        "'''\"\"\"'''", "\"'\"'\"'", "`\${`\${`}`}`", "/*\"*/\"/*", "#\"\n\"#", "// \"\n\"", "0x", "1.2.3.4", "1e-5", "0b1012",
        "9".repeat(5000), "_".repeat(5000), "a1".repeat(5000), "\"".repeat(5001), "'".repeat(5001), "/*".repeat(5000),
        "\"a\";".repeat(4000), ";".repeat(HIGHLIGHT_MAX_CHARS), "'a".repeat(5000), "'\\".repeat(5000),
    )

    @Test
    fun adversarialCorpusIsTotalExactAndLinear() {
        val failures = Work.highlightFailures
        for (lang in Lang.entries) for (code in corpus) {
            val before = Work.highlightSteps
            val a = tokens(code, lang)!!
            assertEquals("$lang ${code.take(20)}", code, a.text)
            assertTrue("$lang ${code.take(20)}: ${Work.highlightSteps - before} steps", Work.highlightSteps - before <= 13L * code.length)
            var end = 0
            for (r in a.getStringAnnotations(0, a.length).sortedBy { it.start }) {
                assertTrue("$lang overlapping or empty span in ${code.take(20)}", r.start >= end && r.end > r.start && r.end <= code.length)
                end = r.end
            }
            val shown = highlight(code, lang.name, true, light.codeFill, light.onSurface)
            assertEquals(code, shown.text)
        }
        assertEquals("the plain fallback for a failing highlight was never needed", failures, Work.highlightFailures)
    }

    @Test
    fun codeOverTheLimitIsNotHighlighted() {
        val before = Work.highlights
        assertNull(tokens("x".repeat(HIGHLIGHT_MAX_CHARS + 1), Lang.KOTLIN))
        assertNull(tokens(";\n".repeat(50_000), Lang.C))
        assertEquals(before, Work.highlights)
        assertTrue(highlight("y".repeat(100_000), "kt", false, light.codeFill, light.onSurface).spanStyles.isEmpty())
    }

    @Test
    fun languageLabelIsOneShortWord() {
        fun label(info: String) = parseBlocks("```$info\ncode\n```\n").single().lang
        assertEquals("python", label("python title=x.py"))
        assertEquals("kotlin", label("   kotlin   "))
        assertEquals("", label(""))
        assertEquals("a".repeat(LANG_MAX), label("a".repeat(500_000)))
        assertEquals("json", label("json " + "word ".repeat(100_000)))
        assertEquals("x".repeat(LANG_MAX - 1), label("x".repeat(LANG_MAX - 1) + "😀tail")) // never half a surrogate pair
        assertEquals("ab", label("ab\u0000cd"))
        assertEquals("ab", label("ab cd"))
        // Streaming the 500,000-character info string never hands more than the bound to the row either.
        val text = "```" + "z".repeat(500_000) + "\nbody\n```"
        for (end in listOf(10, 40, 250_000, 500_004, 500_009, text.length)) {
            for (b in parseBlocks(text.substring(0, end))) assertTrue(b.lang.length <= LANG_MAX)
        }
    }
}
