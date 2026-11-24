package xyz.rycerz.apofis.chat

/** A stored user row split into the text to show and its images: per image the 32 hex digest prefix of the server hint, or null. */
data class UserContent(val text: String, val images: List<String?>)

private const val MAX_IMAGES = 10
private const val KNOWN_HINT = "[Image attached at: " // ... api_<32 hex>.<ext>]
private const val UNKNOWN_HINT = "[Image attached: " // ...]
private const val FILE_NAME = "api_"
private const val PREFIX_HEX = 32
private val MARKERS = setOf("[screenshot]", "[screenshot detail=low]", "[screenshot detail=high]", "[screenshot detail=auto]")
private val MAX_MARKER_LINE = MARKERS.maxOf { it.length }

/**
 * Splits [content] into its text and the server's image block at its very end (H2): a blank line, k hint lines, then
 * exactly k `[screenshot]` lines, k from 1 to 10. Anything else is text with no images. Total on any string; one
 * backward scan over at most eleven short marker lines, the hint lines and the blank lines it trims before the block.
 */
fun parseUserContent(content: String): UserContent {
    val plain = UserContent(content, emptyList())
    var end = content.length
    // The start of the line that ends at [end], or -1 when that line is longer than [max].
    fun lineStart(end: Int, max: Int = Int.MAX_VALUE): Int {
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
        val start = lineStart(end)
        if (start == 0) return plain // no blank line can come before it
        images[i] = (hintAt(content, start, end) ?: return plain).ifEmpty { null }
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

/**
 * The hint line [start, end) of [s] read by its two ends: the 32 hex digest prefix of a known hint, "" for an unknown
 * one, or null when it is no hint. Copies nothing but the 32 hex.
 */
private fun hintAt(s: String, start: Int, end: Int): String? {
    if (s[end - 1] != ']') return null
    if (s.startsWith(UNKNOWN_HINT, start)) return if (end - 1 >= start + UNKNOWN_HINT.length) "" else null
    if (!s.startsWith(KNOWN_HINT, start)) return null
    var dot = end - 2 // the extension, read backwards from the bracket
    while (dot > start && s[dot] in 'a'..'z') dot--
    val hex = dot - PREFIX_HEX
    val known = dot < end - 2 && hex - FILE_NAME.length >= start + KNOWN_HINT.length && s[dot] == '.' &&
        s.startsWith(FILE_NAME, hex - FILE_NAME.length) && (hex until dot).all { s[it] in '0'..'9' || s[it] in 'a'..'f' }
    return if (known) s.substring(hex, dot) else null
}
