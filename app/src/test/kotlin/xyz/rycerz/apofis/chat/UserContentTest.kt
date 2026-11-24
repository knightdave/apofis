package xyz.rycerz.apofis.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.rycerz.apofis.api.MessageDto
import xyz.rycerz.apofis.ui.chat.actionable

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
        val long = "[".repeat(400_000)
        val expected = mapOf(
            "" to plain(""),
            "[".repeat(200_000) to plain("[".repeat(200_000)),
            "$long\n\n${hint(a)}\n[screenshot]" to UserContent(long, listOf(a)),
            "x\n\n[Image attached: $long]\n[screenshot]" to UserContent("x", listOf(null)), // a hint of any length
            "x\n\n[Image attached at: $long]\n[screenshot]" to plain("x\n\n[Image attached at: $long]\n[screenshot]"),
            "x\n\n[Image attached at: ${"api_".repeat(5_000)}]\n[screenshot]" to
                plain("x\n\n[Image attached at: ${"api_".repeat(5_000)}]\n[screenshot]"),
            "[screenshot]x" to plain("[screenshot]x"),
            "x\r\n\r\n${hint(a)}\r\n[screenshot]" to plain("x\r\n\r\n${hint(a)}\r\n[screenshot]"),
            "\uD800\n\n[Image attached: \uDC00\uD800]\n[screenshot]" to UserContent("\uD800", listOf(null)),
            "\n".repeat(50) + "[screenshot]" to plain("\n".repeat(50) + "[screenshot]"),
            "\n\n" + "[screenshot]\n".repeat(30) + "[screenshot]" to plain("\n\n" + "[screenshot]\n".repeat(30) + "[screenshot]"),
            "x\n\n]\n[screenshot]" to plain("x\n\n]\n[screenshot]"),
            "x\n\n[Image attached: \n[screenshot]" to plain("x\n\n[Image attached: \n[screenshot]"),
        )
        for ((input, result) in expected) assertEquals(result, parseUserContent(input))
    }

    @Test
    fun longUnknownHintStaysInTheBlock() {
        for (length in listOf(4_200, 4_201)) {
            val unknown = "[Image attached: https://example.org/" + "u".repeat(length - 38) + "]"
            assertEquals(length, unknown.length)
            val content = "cap\n\n${hint(a)}\n$unknown\n[screenshot]\n[screenshot]"
            assertEquals(UserContent("cap", listOf(a, null)), parseUserContent(content))
            val items = buildTranscript(listOf(MessageDto(1, "user", null, content, null, null, null, null, 1.0, null)))
            assertEquals("cap", (items.single() as UserMessage).text)
            assertEquals("cap", actionable(items, "u1")) // Copy
            assertEquals("cap", actionable(items, "u1", fullMessage = true)) // Select and the full reader
        }
    }

    @Test
    fun lineTerminatorInsideAHintIsText() {
        // The vertical tab and the form feed are line terminators to Android's patterns, not to the host JDK's.
        for (c in listOf("\r", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")) {
            for (content in listOf("cap\n\n[Image attached: left${c}right]\n[screenshot]", "cap\n\n[Image attached at: /p${c}q/api_$a.jpg]\n[screenshot]")) {
                assertEquals(plain(content), parseUserContent(content))
                val items = buildTranscript(listOf(MessageDto(1, "user", null, content, null, null, null, null, 1.0, null)))
                assertEquals(content, (items.single() as UserMessage).text)
            }
        }
    }

    @Test
    fun digestIsExactly32LowerCaseHex() {
        for (digest in listOf("a".repeat(31), "a".repeat(33), "A".repeat(32), "g".repeat(32))) {
            val content = "x\n\n[Image attached at: /p/api_$digest.jpg]\n[screenshot]"
            assertEquals(plain(content), parseUserContent(content))
        }
        for (name in listOf("api_${a}.]", "api_${a}jpg]", "api_${a}.JPG]", "ap_${a}.jpg]", "${a}.jpg]")) {
            val content = "x\n\n[Image attached at: $name\n[screenshot]"
            assertEquals(plain(content), parseUserContent(content))
        }
        assertEquals(UserContent("x", listOf(a)), parseUserContent("x\n\n[Image attached at: api_$a.j]\n[screenshot]"))
        assertEquals(UserContent("x", listOf(a)), parseUserContent("x\n\n[Image attached at: /a]b/api_x/api_$a.jpeg]\n[screenshot]"))
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
