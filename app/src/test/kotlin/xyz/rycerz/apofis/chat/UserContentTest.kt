package xyz.rycerz.apofis.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class UserContentTest {
    private val a = "a".repeat(32)
    private val b = "0123456789abcdef".repeat(2)

    private fun hint(prefix: String) = "[Image attached at: /home/h/.hermes/cache/native-inputs/api_$prefix.jpg]"

    private fun plain(content: String) = UserContent(content, emptyList())

    @Test
    fun plainText() {
        assertEquals(plain("hello\nworld"), parseUserContent("hello\nworld"))
    }

    @Test
    fun textPlusOneImage() {
        assertEquals(UserContent("look at this", listOf(a)), parseUserContent("look at this\n\n${hint(a)}\n[screenshot]"))
    }

    @Test
    fun emptyCaption() {
        assertEquals(UserContent("", listOf(a)), parseUserContent("\n\n${hint(a)}\n[screenshot]"))
        assertEquals(UserContent("", listOf(a)), parseUserContent("\n${hint(a)}\n[screenshot]"))
    }

    @Test
    fun bareScreenshotIsText() {
        assertEquals(plain("[screenshot]"), parseUserContent("[screenshot]"))
        assertEquals(plain("\n[screenshot]"), parseUserContent("\n[screenshot]"))
        assertEquals(plain("hi\n\n[screenshot]"), parseUserContent("hi\n\n[screenshot]"))
    }

    @Test
    fun detailMarker() {
        for (detail in listOf("low", "high", "auto")) {
            assertEquals(UserContent("x", listOf(a)), parseUserContent("x\n\n${hint(a)}\n[screenshot detail=$detail]"))
        }
        assertEquals(plain("x\n\n${hint(a)}\n[screenshot detail=max]"), parseUserContent("x\n\n${hint(a)}\n[screenshot detail=max]"))
    }

    @Test
    fun twoImagesOrder() {
        assertEquals(UserContent("two", listOf(a, b)), parseUserContent("two\n\n${hint(a)}\n${hint(b)}\n[screenshot]\n[screenshot]"))
        assertEquals(UserContent("two", listOf(b, a)), parseUserContent("two\n\n${hint(b)}\n${hint(a)}\n[screenshot]\n[screenshot detail=low]"))
    }

    @Test
    fun mixedKnownAndUnknownHintsInOrder() {
        val content = "m\n\n[Image attached: https://example.org/x.png]\n${hint(b)}\n[Image attached: data:image/jpg;base64,AAAA]\n" +
            "[screenshot]\n[screenshot]\n[screenshot]"
        assertEquals(UserContent("m", listOf(null, b, null)), parseUserContent(content))
        // A path hint that is not a committed file name gives nothing: the whole content is text.
        val odd = "m\n\n[Image attached at: /tmp/photo.jpg]\n[screenshot]"
        assertEquals(plain(odd), parseUserContent(odd))
    }

    @Test
    fun tenImagesAndNotEleven() {
        fun content(k: Int) = "t\n\n" + List(k) { hint(a) }.joinToString("\n") + "\n" + List(k) { "[screenshot]" }.joinToString("\n")
        assertEquals(UserContent("t", List(10) { a }), parseUserContent(content(10)))
        assertEquals(plain(content(11)), parseUserContent(content(11)))
    }

    @Test
    fun hintWithoutScreenshotIsText() {
        val content = "x\n\n${hint(a)}"
        assertEquals(plain(content), parseUserContent(content))
    }

    @Test
    fun moreMarkersThanHintsIsText() {
        val content = "x\n\n${hint(a)}\n[screenshot]\n[screenshot]"
        assertEquals(plain(content), parseUserContent(content))
        val fewer = "x\n\n${hint(a)}\n${hint(b)}\n[screenshot]"
        assertEquals(plain(fewer), parseUserContent(fewer))
    }

    @Test
    fun separatorMustBeBlank() {
        val content = "x\n${hint(a)}\n[screenshot]"
        assertEquals(plain(content), parseUserContent(content))
        val trailing = "x\n\n${hint(a)}\n[screenshot]\n"
        assertEquals(plain(trailing), parseUserContent(trailing))
    }

    @Test
    fun hostile() {
        val inputs = listOf(
            "",
            "[".repeat(200_000),
            "[".repeat(400_000) + "\n\n${hint(a)}\n[screenshot]",
            "[screenshot]x",
            "x\r\n\r\n${hint(a)}\r\n[screenshot]",
            "\uD800\n\n[Image attached: \uDC00\uD800]\n[screenshot]",
            "\n".repeat(50) + "[screenshot]",
            "\n\n" + "[screenshot]\n".repeat(30) + "[screenshot]",
            "[Image attached at: " + "api_".repeat(5_000) + "]\n[screenshot]",
        )
        for (input in inputs) parseUserContent(input) // no exception
        assertEquals(plain(""), parseUserContent(""))
        assertEquals(plain("[screenshot]x"), parseUserContent("[screenshot]x"))
        val crlf = "x\r\n\r\n${hint(a)}\r\n[screenshot]"
        assertEquals(plain(crlf), parseUserContent(crlf))
        assertEquals(UserContent("\uD800", listOf(null)), parseUserContent("\uD800\n\n[Image attached: \uDC00\uD800]\n[screenshot]"))
        assertEquals(UserContent("[".repeat(400_000), listOf(a)), parseUserContent("[".repeat(400_000) + "\n\n${hint(a)}\n[screenshot]"))
    }

    @Test
    fun markerLineInsideTextStaysText() {
        val content = "before\n[screenshot]\nafter"
        assertEquals(plain(content), parseUserContent(content))
        val hinted = "before\n\n${hint(a)}\n[screenshot]\nafter"
        assertEquals(plain(hinted), parseUserContent(hinted))
    }

    @Test
    fun captionEndingWithMarkerLineNotOverstripped() {
        assertEquals(
            UserContent("caption\n[screenshot]", listOf(a)),
            parseUserContent("caption\n[screenshot]\n\n${hint(a)}\n[screenshot]"),
        )
        assertEquals(
            UserContent("caption\n\n${hint(b)}\n[screenshot]", listOf(a)),
            parseUserContent("caption\n\n${hint(b)}\n[screenshot]\n\n${hint(a)}\n[screenshot]"),
        )
    }

    @Test
    fun trailingBlankLinesOfTheTextAreTrimmed() {
        assertEquals(UserContent("hi", listOf(a)), parseUserContent("hi\n  \n\n\n${hint(a)}\n[screenshot]"))
        assertEquals(UserContent("hi  ", listOf(a)), parseUserContent("hi  \n\n${hint(a)}\n[screenshot]"))
        assertEquals(UserContent("", listOf(a)), parseUserContent(" \n\n${hint(a)}\n[screenshot]"))
    }
}
