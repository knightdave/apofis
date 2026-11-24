package xyz.rycerz.apofis.chat

import java.util.Locale

/** The app's slash commands; [takesText]: the command needs text after its word, else it refuses any. */
enum class Command(val word: String, val takesText: Boolean) {
    NEW("new", false), STOP("stop", false), STEER("steer", true), QUEUE("queue", true),
    TITLE("title", true), MODEL("model", false), REASONING("reasoning", false), FAST("fast", false),
}

/** What a draft is: one of the commands with the rest of the draft as [Run.text], another slash word, or a message. */
sealed interface Parsed {
    data class Run(val command: Command, val text: String) : Parsed
    data class Unknown(val word: String) : Parsed // the word without its slash
    data object Message : Parsed
}

// The server's command word (H14): a letter, digit or underscore, then those or hyphens, ended by whitespace or the end.
// The whitespace is spelled out: `\s` is ASCII only on the JVM and Unicode on Android; this class is the same on both.
private val COMMAND = Regex("""^/([\p{L}\p{N}_][\p{L}\p{N}_-]*)([\t\n\x0B\f\r\x{85}\p{Z}]|$)""")
private val PALETTE = Regex("""^/[\p{L}\p{N}_-]*$""")

private fun command(word: String): Command? = word.lowercase(Locale.ROOT).let { w -> Command.entries.firstOrNull { it.word == w } }

/**
 * Decision 13: the draft with leading whitespace removed is a command line when it starts with a slash word. Anything
 * else is a message, so `/etc/hosts`, `/ hi` and `//x` are sent as text.
 */
fun parseCommand(draft: String): Parsed {
    val line = draft.trimStart()
    val match = COMMAND.find(line) ?: return Parsed.Message
    val word = match.groupValues[1]
    val known = command(word) ?: return Parsed.Unknown(word)
    return Parsed.Run(known, line.substring(match.range.last + 1).trim())
}

/** While the draft is a slash and the start of a word: the commands whose word starts with it, in enum order. */
fun paletteMatches(draft: String): List<Command> {
    if (!PALETTE.matches(draft)) return emptyList()
    val start = draft.substring(1).lowercase(Locale.ROOT)
    return Command.entries.filter { it.word.startsWith(start) }
}
