package xyz.rycerz.apofis.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun plain(text: String) = MdText(text, emptyList())
    private fun para(text: String, vararg spans: MdSpan) = MdBlock.Paragraph(MdText(text, spans.toList()))

    @Test
    fun headingLevels() {
        val blocks = parseMarkdown("# a\n## b\n###### f")
        assertEquals(listOf(MdBlock.Heading(1, plain("a")), MdBlock.Heading(2, plain("b")), MdBlock.Heading(6, plain("f"))), blocks)
    }

    @Test
    fun inlineSpans() {
        assertEquals(
            listOf(
                para(
                    "b i s c l",
                    MdSpan(0, 1, SpanKind.STRONG),
                    MdSpan(2, 3, SpanKind.EMPHASIS),
                    MdSpan(4, 5, SpanKind.STRIKE),
                    MdSpan(6, 7, SpanKind.CODE),
                    MdSpan(8, 9, SpanKind.LINK, "https://x.y"),
                ),
            ),
            parseMarkdown("**b** *i* ~~s~~ `c` [l](https://x.y)"),
        )
    }

    @Test
    fun unsafeLinksArePlainText() {
        for (src in listOf("[x](javascript:alert(1))", "[x](JAVASCRIPT:y)", "[x](data:text/html,hi)", "[x](mailto:a@b.c)", "[x](/relative)", "[x](https:///nohost)", "<javascript:alert(1)>")) {
            val blocks = parseMarkdown(src)
            val text = (blocks.single() as MdBlock.Paragraph).text
            assertTrue(src, text.spans.none { it.kind == SpanKind.LINK })
        }
        assertEquals(listOf(para("x")), parseMarkdown("[x](javascript:alert(1))"))
        // The scheme is normalized to lower case: Android matches intent schemes case-sensitively.
        assertEquals(listOf(para("x", MdSpan(0, 1, SpanKind.LINK, "https://a.b/c"))), parseMarkdown("[x](HTTPS://a.b/c)"))
        assertEquals("https://Example.com/Path?q=1#f", safeUrl("HtTpS://Example.com/Path?q=1#f"))
        assertEquals("http://u@h:8080/p", safeUrl("http://u@h:8080/p"))
        assertEquals("https://[200::1]/", safeUrl("https://[200::1]/"))
        val bad = listOf(
            "http://", "https://:80/", "ftp://a.b", "https://a b", "https://a\u0000b", "//a.b/c", " https://a.b",
            "https://[broken", "https://[broken/x", "https://a..b/", "https://a.b:port/", "https://@/", "https:a.b", "https:/a.b", "javascript://a.b/%0Aalert(1)",
        )
        for (url in bad) assertNull(url, safeUrl(url))
    }

    @Test
    fun htmlIsLiteral() {
        assertEquals(listOf(para("<script>alert(1)</script>")), parseMarkdown("<script>alert(1)</script>"))
        assertEquals(listOf(para("a <b>c</b>")), parseMarkdown("a <b>c</b>"))
        assertEquals(listOf(para("<div>\nx\n</div>")), parseMarkdown("<div>\nx\n</div>"))
    }

    private fun depth(blocks: List<MdBlock>): Int = blocks.maxOfOrNull { b ->
        when (b) {
            is MdBlock.Quote -> 1 + depth(b.blocks)
            is MdBlock.ListBlock -> 1 + (b.items.maxOfOrNull { depth(it.blocks) } ?: 0)
            else -> 0
        }
    } ?: 0

    private fun allText(blocks: List<MdBlock>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is MdBlock.Paragraph -> b.text.text
            is MdBlock.Heading -> b.text.text
            is MdBlock.Quote -> allText(b.blocks)
            is MdBlock.ListBlock -> b.items.joinToString("\n") { allText(it.blocks) }
            is MdBlock.Table -> (b.header + b.rows.flatten()).joinToString(" ") { it.text }
            is MdBlock.Image -> b.alt
            MdBlock.Rule -> ""
        }
    }

    @Test
    fun deepNestingIsCapped() {
        val quotes = parseMarkdown("> ".repeat(500) + "innermost")
        assertEquals(MAX_NESTING, depth(quotes))
        assertTrue(allText(quotes).contains("innermost"))

        val list = parseMarkdown((0 until 500).joinToString("\n") { "  ".repeat(it) + "- item$it" })
        assertTrue("depth ${depth(list)}", depth(list) in 1..MAX_NESTING)
        assertTrue(allText(list).contains("item499"))

        val emphasis = parseMarkdown("*".repeat(500) + "x" + "*".repeat(500))
        assertTrue(allText(emphasis).contains("x"))
    }

    @Test
    fun taskItems() {
        assertEquals(
            listOf(
                MdBlock.ListBlock(
                    false,
                    1,
                    listOf(MdListItem(true, false, listOf(para("open"))), MdListItem(true, true, listOf(para("done"))), MdListItem(false, false, listOf(para("plain")))),
                ),
            ),
            parseMarkdown("- [ ] open\n- [x] done\n- plain"),
        )
    }

    @Test
    fun orderedStart() {
        val list = parseMarkdown("3. c\n4. d").single() as MdBlock.ListBlock
        assertEquals(true, list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun tableWithAlignment() {
        assertEquals(
            listOf(MdBlock.Table(listOf(plain("a"), plain("b"), plain("c")), listOf(Align.START, Align.CENTER, Align.END), listOf(listOf(plain("1"), plain("2"), plain(""))))),
            parseMarkdown("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 |"),
        )
        val long = parseMarkdown("| a |\n|---|\n| 1 | 2 | 3 |").single() as MdBlock.Table
        assertEquals(listOf(listOf(plain("1"))), long.rows)
    }

    @Test
    fun indentedTextIsParagraph() {
        assertEquals(listOf(para("not code")), parseMarkdown("    not code"))
        assertEquals(listOf(para("~~~\nnot a fence\n~~~")), parseMarkdown("~~~\nnot a fence\n~~~"))
    }

    @Test
    fun imageOnlyParagraph() {
        assertEquals(listOf(MdBlock.Image("alt", "https://x.y/i.png")), parseMarkdown("![alt](https://x.y/i.png)"))
        assertEquals(listOf(MdBlock.Image("alt", "")), parseMarkdown("![alt](javascript:x)"))
        assertEquals(listOf(para("see alt here", MdSpan(4, 7, SpanKind.LINK, "https://x.y/i.png"))), parseMarkdown("see ![alt](https://x.y/i.png) here"))
        // An image inside a link takes the link's target: links never nest.
        assertEquals(listOf(para("alt", MdSpan(0, 3, SpanKind.LINK, "https://a.b"))), parseMarkdown("[![alt](https://x.y/i.png)](https://a.b)"))
    }

    @Test
    fun ruleAndBreaks() {
        assertEquals(listOf(para("a\nb")), parseMarkdown("a\nb"))
        assertEquals(listOf(para("a\nb")), parseMarkdown("a  \nb"))
        assertEquals(listOf(para("a"), MdBlock.Rule, para("b")), parseMarkdown("a\n\n---\n\nb"))
        assertEquals(listOf(MdBlock.Rule), parseMarkdown("***"))
        assertEquals(listOf(para("~one~")), parseMarkdown("~one~")) // one tilde is text
    }

    @Test
    fun rawHtmlBlocksAreLiteral() {
        val html = "<div>\n**hi** [site](https://x.y)\n</div>"
        assertEquals(listOf(para(html)), parseMarkdown(html))
        assertEquals(listOf(para("<details>\n<summary>*s*</summary>"), para("text")), parseMarkdown("<details>\n<summary>*s*</summary>\n\ntext"))
    }
}
