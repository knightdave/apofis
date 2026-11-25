package xyz.rycerz.apofis.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The slash grammar (decision 13, H14) and the palette's filter. */
class CommandsTest {
    @Test
    fun grammar() {
        val long = "x".repeat(100_000)
        val table = listOf(
            "/new" to Parsed.Run(Command.NEW, ""),
            "  /NEW" to Parsed.Run(Command.NEW, ""),
            "/title Hello world" to Parsed.Run(Command.TITLE, "Hello world"),
            "/status" to Parsed.Unknown("status"),
            "/reload-mcp" to Parsed.Unknown("reload-mcp"),
            "/set-home x" to Parsed.Unknown("set-home"),
            "/my-skill do it" to Parsed.Unknown("my-skill"),
            "/gpt4" to Parsed.Unknown("gpt4"),
            "/2fa" to Parsed.Unknown("2fa"),
            "/小说拆条" to Parsed.Unknown("小说拆条"),
            "/etc/hosts" to Parsed.Message,
            "/ x" to Parsed.Message,
            "//x" to Parsed.Message,
            "hello /new" to Parsed.Message,
            "/new\nmore" to Parsed.Run(Command.NEW, "more"),
            "" to Parsed.Message,
            " /status" to Parsed.Unknown("status"),
            "\n/status" to Parsed.Unknown("status"),
            "./status" to Parsed.Message,
            "\"/status\"" to Parsed.Message,
            "/title $long" to Parsed.Run(Command.TITLE, long),
            "/Steer  go on  " to Parsed.Run(Command.STEER, "go on"),
            "/-x" to Parsed.Message, // a word starts with a letter, digit or underscore
            // Any Unicode space ends the word, the same on this JVM (ASCII-only \s) and on Android (Unicode \s).
            "/new\u00A0more" to Parsed.Run(Command.NEW, "more"),
            "/new\u2028x" to Parsed.Run(Command.NEW, "x"),
            "/status\u3000now" to Parsed.Unknown("status"),
            "/new\u0085x" to Parsed.Run(Command.NEW, "x"),
        )
        for ((draft, expected) in table) assertEquals(draft.take(40), expected, parseCommand(draft))
    }

    @Test
    fun paletteFilter() {
        assertEquals(Command.entries, paletteMatches("/"))
        assertEquals(listOf(Command.STOP, Command.STEER), paletteMatches("/st"))
        assertEquals(listOf(Command.STEER), paletteMatches("/STE"))
        assertEquals(listOf(Command.REASONING), paletteMatches("/r"))
        assertEquals(emptyList<Command>(), paletteMatches("/status"))
        assertEquals(emptyList<Command>(), paletteMatches("/new ")) // a space ends the word: the palette closes
        assertEquals(emptyList<Command>(), paletteMatches(" /new"))
        assertEquals(emptyList<Command>(), paletteMatches(""))
        assertEquals(emptyList<Command>(), paletteMatches("new"))
        assertEquals(emptyList<Command>(), paletteMatches("/n/"))
    }
}
