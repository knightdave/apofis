package xyz.rycerz.apofis.chat

/** A stored user row split into the text to show and its images: per image the 32 hex digest prefix of the server hint, or null. */
data class UserContent(val text: String, val images: List<String?>)

private const val MAX_IMAGES = 10
private const val MAX_HINT_LINE = 4200 // a hint carries a server path (at most 4096 on Linux) plus its brackets
private val MARKERS = setOf("[screenshot]", "[screenshot detail=low]", "[screenshot detail=high]", "[screenshot detail=auto]")
private val MAX_MARKER_LINE = MARKERS.maxOf { it.length }
private val KNOWN_HINT = Regex("""\[Image attached at: .*api_([0-9a-f]{32})\.[a-z]+]""")
private val UNKNOWN_HINT = Regex("""\[Image attached: .*]""")

/**
 * Splits [content] into its text and the server's image block at its very end (H2): a blank line, k hint lines, then
 * exactly k `[screenshot]` lines, k from 1 to 10. Anything else is text with no images. Total on any string; it reads
 * only the last 2k + 1 lines, each bounded in length, plus the blank lines it trims before the block.
 */
fun parseUserContent(content: String): UserContent {
    val plain = UserContent(content, emptyList())
    var end = content.length
    // The start of the line that ends at [end], or -1 when that line is longer than [max].
    fun lineStart(end: Int, max: Int): Int {
        var i = end - 1
        while (i >= 0 && content[i] != '\n') if (end - i > max) return -1 else i--
        return i + 1
    }

    var markers = 0
    while (markers <= MAX_IMAGES) {
        val start = lineStart(end, MAX_MARKER_LINE)
        if (start < 0 || start == 0 || content.substring(start, end) !in MARKERS) break
        markers++
        end = start - 1
    }
    if (markers !in 1..MAX_IMAGES) return plain
    val images = arrayOfNulls<String>(markers)
    for (i in markers - 1 downTo 0) {
        val start = lineStart(end, MAX_HINT_LINE)
        if (start <= 0) return plain // too long, or no blank line can come before it
        val line = content.substring(start, end)
        images[i] = KNOWN_HINT.matchEntire(line)?.groupValues?.get(1) ?: if (UNKNOWN_HINT.matches(line)) null else return plain
        end = start - 1
    }
    if (end != 0 && content[end - 1] != '\n') return plain // the separator line is not blank
    // The text ends before the separator, with trailing blank lines trimmed.
    var cut = maxOf(end - 1, 0)
    var i = cut
    while (i > 0 && content[i - 1].isWhitespace()) {
        i--
        if (content[i] == '\n') cut = i
    }
    return UserContent(content.substring(0, if (i == 0) 0 else cut), images.toList())
}
