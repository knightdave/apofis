package xyz.rycerz.apofis.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner grammar of spec 6.1: one test per example row, then the further cases of the T4a brief. */
class AttachmentScanTest {
    private fun pic(path: String) = Attachment(path, AttachmentKind.PICTURE)

    private fun found(vararg a: Attachment, tagOnly: Boolean = false) = AttachmentScan(a.toList(), tagOnly)

    private val none = AttachmentScan(emptyList(), false)

    private fun check(text: String, expected: AttachmentScan) = assertEquals(text, expected, scanAttachments(text))

    /** The scanner's work counter across [block]. */
    private fun steps(block: () -> Unit): Long {
        val before = attachmentScanSteps
        block()
        return attachmentScanSteps - before
    }

    /** A line of exactly [length] UTF-16 units that ends with ` /srv/out/a.png`. */
    private fun longLine(length: Int) = "x".repeat(length - " /srv/out/a.png".length) + " /srv/out/a.png"

    @Test
    fun row1() = check("Here it is.\nMEDIA:/srv/out/chart.png", found(pic("/srv/out/chart.png")))

    @Test
    fun row2() = check("MEDIA:/srv/out/chart.png", found(pic("/srv/out/chart.png"), tagOnly = true))

    @Test
    fun row3() = check("1) MEDIA:/srv/out/chart.png", found(pic("/srv/out/chart.png"), tagOnly = true))

    @Test
    fun row4() = check("MEDIA:\"/srv/out/my chart.png\"", found(pic("/srv/out/my chart.png"), tagOnly = true))

    @Test
    fun row5() = check("MEDIA:/srv/out/chart.png draft.png", found(pic("/srv/out/chart.png draft.png"), tagOnly = true))

    @Test
    fun row5b() {
        check("MEDIA:/srv/out/my chart.png is ready", none)
        check("MEDIA:/srv/out/my chart.png", found(pic("/srv/out/my chart.png"), tagOnly = true))
    }

    @Test
    fun row5c() = check("MEDIA: /srv/out/chart.png", found(pic("/srv/out/chart.png"), tagOnly = true))

    @Test
    fun row6() {
        check("MEDIA:\"/srv/my /srv/a.png draft.png\" today", none)
        check("MEDIA:\"/srv/my /srv/a.png draft.png\"", found(pic("/srv/my /srv/a.png draft.png"), tagOnly = true))
    }

    @Test
    fun row7() {
        check("MEDIA:/srv/a.png MEDIA:/srv/b.png", none)
        check("MEDIA:/srv/a.png", found(pic("/srv/a.png"), tagOnly = true))
    }

    @Test
    fun row8() = check("MEDIA:/srv/out/report.pdf", found(Attachment("/srv/out/report.pdf", AttachmentKind.FILE), tagOnly = true))

    @Test
    fun row9() = check("I saved it at /srv/out/chart.png.", found(pic("/srv/out/chart.png")))

    @Test
    fun row10() = check("Saved to `/srv/out/chart.png`.", found(pic("/srv/out/chart.png")))

    @Test
    fun row11() = check("Saved as \"/srv/out/chart.png\".", found(pic("/srv/out/chart.png")))

    @Test
    fun row12() {
        check("Run `cp /srv/a.png /tmp/b.png` ", none)
        check("Run ``cp /srv/a.png /tmp/b.png`` ", none)
        check("Run `/srv/a.png` ", found(pic("/srv/a.png")))
    }

    @Test
    fun row13() {
        check("To send a file, write \"MEDIA:/srv/out/chart.png\" on its own line.", none)
        check("To send a file, write \"/srv/out/chart.png\" on its own line.", found(pic("/srv/out/chart.png")))
    }

    @Test
    fun row14() {
        check("To attach, write `MEDIA:/srv/out/chart.png` alone.", none)
        check("To attach, write `/srv/out/chart.png` alone.", found(pic("/srv/out/chart.png")))
    }

    @Test
    fun row15() {
        check("\"the examples /srv/a.png and /srv/b.png are not attached\"", none)
        check("the examples /srv/a.png and /srv/b.png are not attached", found(pic("/srv/a.png"), pic("/srv/b.png")))
    }

    @Test
    fun row16() {
        check("> MEDIA:/srv/out/chart.png", none)
        check("\$ cp /srv/out/chart.png /tmp/x.png", none)
        check("MEDIA:/srv/out/chart.png", found(pic("/srv/out/chart.png"), tagOnly = true))
        check("cp /srv/out/chart.png /tmp/x.png", found(pic("/srv/out/chart.png"), pic("/tmp/x.png")))
    }

    @Test
    fun row16b() {
        check("user@host:~\$ cp /srv/out/a.png /tmp/b.png", none)
        check("cp /srv/out/a.png /tmp/b.png", found(pic("/srv/out/a.png"), pic("/tmp/b.png")))
    }

    @Test
    fun row17() = check("```\nMEDIA:/srv/out/a.png\n```\nMEDIA:/srv/out/b.png", found(pic("/srv/out/b.png")))

    @Test
    fun row18() {
        check("```text\n/srv/out/a.png", none)
        check("```text\n```\n/srv/out/a.png", found(pic("/srv/out/a.png")))
    }

    @Test
    fun row19() {
        check("See [the chart](/srv/out/chart.png) now.", none)
        check("![chart](/srv/out/chart.png)", none)
        check("See /srv/out/chart.png now.", found(pic("/srv/out/chart.png")))
    }

    @Test
    fun row20() {
        check("**/srv/out/a.png**", none)
        check("(/srv/out/a.png)", none)
        check("See MEDIA:/srv/out/a.png today", none)
        check("/srv/out/a.png", found(pic("/srv/out/a.png")))
    }

    @Test
    fun row21() = check("| chart | /srv/out/chart.png |", found(pic("/srv/out/chart.png")))

    @Test
    fun row21b() = check("|Sales|/srv/out/chart.png|", found(pic("/srv/out/chart.png")))

    @Test
    fun row22() {
        check("/srv/out/a.png.bak /srv/out/a.pngx /srv/out/a.png?x=1 /srv/out/a.pdf", none)
        check("/srv/out/a.png", found(pic("/srv/out/a.png")))
    }

    @Test
    fun row23() {
        check("https://example.com/a.png //host/a.png ~/a.png ./a.png", none)
        check("/srv/a.png", found(pic("/srv/a.png")))
    }

    @Test
    fun row24() = check("The ` key, then /srv/out/a.png", found(pic("/srv/out/a.png")))

    @Test
    fun row24b() {
        check("foo\"x\"/srv/a.png", none)
        check("<img src=\"/srv/a.png\">", none)
        check("foo \"x\" /srv/a.png", found(pic("/srv/a.png")))
    }

    @Test
    fun row24c() {
        check("It's 'an example /srv/a.png here' now", none)
        check("Saved as '/srv/a.png'.", found(pic("/srv/a.png")))
    }

    @Test
    fun row25() = check("/srv/b.png then /srv/a.png then /srv/b.png", found(pic("/srv/b.png"), pic("/srv/a.png")))

    @Test
    fun row26() {
        val format = "/srv/out/a\u200B.png"
        assertEquals(0x200B, format.codePointAt(10))
        check(format, none)
        check("MEDIA:/srv/out/a.png\r\n", found(pic("/srv/out/a.png"), tagOnly = true))
    }

    @Test
    fun row27() {
        val paths = (1..17).map { "/srv/out/p${it.toString().padStart(2, '0')}.png" }
        check(paths.joinToString("\n") { "MEDIA:$it" }, AttachmentScan(paths.take(16).map(::pic), false))
    }

    @Test
    fun row28() {
        val line = longLine(4097)
        assertEquals(4097, line.length)
        check(line, none)
        check(longLine(4096), found(pic("/srv/out/a.png")))
    }

    @Test
    fun row29() = check("MEDIA:/srv/out/a.png\rMEDIA:/srv/out/b.png", found(pic("/srv/out/a.png"), pic("/srv/out/b.png")))

    @Test
    fun row30() {
        val text = "\u00A0```\nMEDIA:/srv/out/a.png\n```\nMEDIA:/srv/out/b.png"
        assertEquals(0x00A0, text.codePointAt(0))
        check(text, found(pic("/srv/out/b.png")))
    }

    @Test
    fun row31() = check("0. MEDIA:/srv/out/a.png", found(pic("/srv/out/a.png"), tagOnly = true))

    @Test
    fun longProseRegression() {
        val words = "plain words of a long answer with nothing special in them at all "
        val line = words.repeat(4000 / words.length)
        // Lines of unmatched openers: each kind searches for a closer once per line, then is plain text.
        val openers = listOf(" 'x", " \"x", " ](x", " `x").map { it.repeat(4000 / it.length) }
        val text = (List(13) { line } + openers).joinToString("\n") + "\nand the last one is /srv/out/end.png"
        assertTrue(text.length > 50_000)
        assertTrue(text.split('\n').all { it.length <= 4096 })
        val steps = steps { check(text, found(pic("/srv/out/end.png"))) }
        assertTrue("$steps steps", steps <= 3L * text.length)
    }

    @Test
    fun lineOfExactly4096IsRead() {
        val line = longLine(4096)
        assertEquals(4096, line.length)
        check(line, found(pic("/srv/out/a.png")))
    }

    @Test
    fun malformedTagOwnsItsLine() {
        check("MEDIA:/srv/out/a.png /srv/out/b.png today", none)
        check("/srv/out/b.png today", found(pic("/srv/out/b.png")))
    }

    @Test
    fun nbspBeforeTagIsNotStripped() {
        val text = "\u00A0MEDIA:/srv/out/a.png"
        assertEquals(0x00A0, text.codePointAt(0))
        check(text, none)
        check("MEDIA:/srv/out/a.png", found(pic("/srv/out/a.png"), tagOnly = true))
    }

    @Test
    fun fourBacktickLineDoesNotClose() {
        check("```\n````\nMEDIA:/srv/out/a.png", none)
        check("```\n```\nMEDIA:/srv/out/a.png", found(pic("/srv/out/a.png")))
    }

    @Test
    fun formatCharacterOutsideBmpIsRefused() {
        val text = "MEDIA:/srv/out/a" + String(Character.toChars(0xE0001)) + ".png"
        assertEquals(0xE0001, text.codePointAt(16))
        assertEquals(Character.FORMAT.toInt(), Character.getType(0xE0001))
        check(text, none)
        check("MEDIA:/srv/out/a.png", found(pic("/srv/out/a.png"), tagOnly = true))
    }

    @Test
    fun loneSurrogateIsRefused() {
        val text = "MEDIA:/srv/out/a\uD800.png"
        assertEquals(0xD800, text.codePointAt(16)) // unpaired: codePointAt returns the lone unit
        assertTrue(Character.isSurrogate(text[16]) && !Character.isSurrogate(text[17]))
        check(text, none)
        check("MEDIA:/srv/out/a.png", found(pic("/srv/out/a.png"), tagOnly = true))
    }

    @Test
    fun spanGluedBeforeIsNotAnException() {
        check("src=\"/srv/a.png\" here", none)
        check("src \"/srv/a.png\" here", found(pic("/srv/a.png")))
    }

    @Test
    fun unmatchedLinkOpenerIsPlainText() {
        check("See ](x /srv/out/a.png", found(pic("/srv/out/a.png")))
        check("See ](x /srv/out/a.png)", none)
    }

    @Test
    fun dataUrlIsNeverAPath() {
        check("See data:image/png;base64,iVBORw0KGgo here", none)
        check("![chart](data:image/png;base64,iVBORw0KGgo)", none)
        check("MEDIA:data:image/png;base64,iVBORw0KGgo", none)
        check("See /srv/out/chart.png here", found(pic("/srv/out/chart.png")))
    }

    @Test
    fun codeSpanSkipsRunsOfOtherLengths() {
        check("Run ``a ` /srv/a.png `` now", none)
        check("Run ``a`` /srv/a.png now", found(pic("/srv/a.png")))
    }

    @Test
    fun mixedBacktickRunLengthsStayLinear() {
        val text = "x " + (1..60).joinToString(" ") { "`".repeat(it) } + " " + "y".repeat(2000) + " /srv/out/a.png"
        assertTrue(text.length <= 4096)
        val steps = steps { check(text, found(pic("/srv/out/a.png"))) }
        assertTrue("$steps steps", steps <= 3L * text.length)
    }

    @Test
    fun linesAfterTheSixteenthAttachmentAreNotRead() {
        val paths = (1..16).map { "/srv/out/p${it.toString().padStart(2, '0')}.png" }
        val head = paths.joinToString("\n") { "MEDIA:$it" }
        val text = head + "\n" + "x\n".repeat(100_000)
        val steps = steps { check(text, AttachmentScan(paths.map(::pic), false)) }
        assertTrue("$steps steps", steps <= 2L * head.length)
    }

    @Test
    fun stopAt16InsideOneProseLine() {
        val paths = (1..17).map { "/srv/p$it.png" }
        check(paths.joinToString(" "), AttachmentScan(paths.take(16).map(::pic), false))
    }

    @Test
    fun shellPromptLimitIs64Utf16Units() {
        check("a".repeat(63) + "\$ /srv/out/a.png", none)
        check("a".repeat(64) + "\$ /srv/out/a.png", found(pic("/srv/out/a.png")))
        val prompt = String(Character.toChars(0x1F600)).repeat(32) + "\$"
        assertEquals(0x1F600, prompt.codePointAt(0))
        assertEquals(65, prompt.length)
        assertEquals(33, prompt.codePointCount(0, prompt.length))
        check("$prompt /srv/out/a.png", found(pic("/srv/out/a.png")))
    }

    @Test
    fun listMarkers() {
        check("-MEDIA:/srv/a.png", none)
        check("- - MEDIA:/srv/a.png", none)
        for (marker in listOf("-", "*", "+", "1.", "2)")) check("$marker MEDIA:/srv/a.png", found(pic("/srv/a.png"), tagOnly = true))
        check("-\t MEDIA:/srv/a.png", found(pic("/srv/a.png"), tagOnly = true))
    }

    @Test
    fun digitMarkerHasOneToNineDigits() {
        check("1234567890. MEDIA:/srv/a.png", none)
        check("123456789. MEDIA:/srv/a.png", found(pic("/srv/a.png"), tagOnly = true))
    }

    @Test
    fun backtickQuotedTag() {
        check("MEDIA:`/srv/out/a`b.png`", none)
        check("MEDIA:`/srv/out/my chart.png`", found(pic("/srv/out/my chart.png"), tagOnly = true))
    }

    @Test
    fun tagDropsOnePunctAndTrailingWs() {
        check("MEDIA:/srv/a.png..", none)
        check("MEDIA:/srv/a.png. \t", found(pic("/srv/a.png"), tagOnly = true))
    }

    @Test
    fun suffixIsCaseInsensitiveAndAsciiOnly() {
        val text = "MEDIA:/srv/out/a.p\u00E9g"
        assertEquals(0x00E9, text.codePointAt(text.length - 2))
        check(text, none)
        check("MEDIA:/srv/out/a.PnG", found(pic("/srv/out/a.PnG"), tagOnly = true))
    }

    @Test
    fun allFivePictureSuffixes() {
        check("/s/a.bmp", none)
        val paths = listOf("/s/a.png", "/s/b.jpg", "/s/c.jpeg", "/s/d.gif", "/s/e.webp")
        check(paths.joinToString(" "), AttachmentScan(paths.map(::pic), false))
    }

    @Test
    fun pathLimitIs1024Units() {
        val path = "/" + "a".repeat(1019) + ".png"
        assertEquals(1024, path.length)
        check(path.dropLast(4) + "a.png", none)
        check(path, found(pic(path)))
    }

    @Test
    fun emptyNameBeforeDotIsNoPath() {
        check("/srv/out/.png", none)
        check("/srv/out/a.png", found(pic("/srv/out/a.png")))
    }

    @Test
    fun controlAndFillerCharactersAreRefused() {
        val control = "MEDIA:/srv/a\u0001.png"
        val filler = "MEDIA:/srv/a\u3164.png"
        assertEquals(0x0001, control.codePointAt(12))
        assertEquals(0x3164, filler.codePointAt(12))
        check(control, none)
        check(filler, none)
        check("MEDIA:/srv/a.png", found(pic("/srv/a.png"), tagOnly = true))
    }

    @Test
    fun singleQuoteOpensAfterParenthesis() {
        check("(' /srv/a.png ' x", none)
        check("( /srv/a.png ' x", found(pic("/srv/a.png")))
    }

    @Test
    fun spanContentIsNotTrimmed() {
        check("Saved as \" /srv/a.png\".", none)
        check("Saved as \"/srv/a.png\".", found(pic("/srv/a.png")))
    }

    @Test
    fun closerMayEndInWhitespace() {
        check("```\n``` x\nMEDIA:/srv/a.png", none)
        check("```\n```  \t\nMEDIA:/srv/a.png", found(pic("/srv/a.png")))
    }
}
