package xyz.rycerz.apofis.sync

import java.util.UUID
import xyz.rycerz.apofis.api.SessionDto

private const val TITLE_LIMIT = 60

/** Ids the app creates for other purposes (push and probe sessions); never shown, synced or changed. */
val RESERVED_PREFIXES = listOf("apofis-push-", "apofis-probe-")

fun isReservedId(id: String): Boolean = RESERVED_PREFIXES.any { id.startsWith(it) }

/**
 * Only chats this app created. The `apofis-` prefix is checked on [sessionKeyOf]: after compression the list
 * shows a chat under a new tip id, and its lineage root is the id the app created. No id of the chat, root,
 * tip or lineage, may be reserved.
 */
fun isAppSession(dto: SessionDto): Boolean =
    dto.source == "api_server" && !dto.hidden && sessionKeyOf(dto).startsWith("apofis-") &&
        (listOf(sessionKeyOf(dto), dto.id) + dto.lineageIds).none(::isReservedId)

fun sessionKeyOf(dto: SessionDto): String = dto.lineageRootId ?: dto.id

/** Keys of temporary chats: ordinary stored chats, deleted when the user leaves them in the app. */
const val TEMPORARY_PREFIX = "apofis-tmp-"

fun isTemporaryKey(key: String): Boolean = key.startsWith(TEMPORARY_PREFIX)

fun newSessionId(temporary: Boolean = false): String = (if (temporary) TEMPORARY_PREFIX else "apofis-") + UUID.randomUUID()

/** First non-blank line, trimmed, at most 60 characters; "" when there is none. */
fun titleFrom(text: String): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
    val cut = line.take(TITLE_LIMIT)
    return if (cut.length == TITLE_LIMIT && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** The longest title the app gives a chat: 100 (H11) minus the longest create suffix " (9)". */
const val TITLE_MAX = 96

/**
 * A title as the server would keep it (its sanitizer, counted the same way): what the server drops goes first, other
 * whitespace becomes a space, remaining control and invisible format characters and unpaired surrogates go, space runs
 * collapse, the ends are trimmed, and at most [TITLE_MAX] code points stay. Null when nothing remains. Only the first
 * [TITLE_INPUT_MAX] UTF-16 units are read: a paste of millions of characters costs no more than that.
 */
fun cleanTitle(text: String): String? {
    val input = text.take(TITLE_INPUT_MAX)
    val kept = StringBuilder()
    var i = 0
    while (i < input.length) {
        val cp = input.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp).toByte()
        when {
            serverDrops(cp) -> Unit
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 -> kept.append(' ') // 0x85: whitespace to Python
            type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE -> Unit
            else -> kept.appendCodePoint(cp)
        }
    }
    return kept.replace(SPACES, " ").trim().takeCodePoints(TITLE_MAX).trimEnd().ifEmpty { null }
}

private val SPACES = Regex(" {2,}")

/** Characters [cleanTitle] reads: far more than a title of [TITLE_MAX] code points needs after any real run of invisibles. */
private const val TITLE_INPUT_MAX = 4096

/**
 * What Hermes' sanitize_title removes before it collapses whitespace (hermes_state_titles.py: _TITLE_CONTROL_RE, which
 * keeps tab, line feed and carriage return, and _TITLE_INVISIBLE_RE): ASCII controls, zero-width and bidi marks, the
 * line and paragraph separators, U+2060 to U+2069, the byte order mark, the interlinear annotations and U+FFFC.
 */
private fun serverDrops(cp: Int) = cp in 0x00..0x08 || cp == 0x0B || cp == 0x0C || cp in 0x0E..0x1F || cp == 0x7F ||
    cp in 0x200B..0x200F || cp in 0x2028..0x202E || cp in 0x2060..0x2069 || cp == 0xFEFF || cp in 0xFFF9..0xFFFC

/** At most [n] code points; a surrogate pair is never split. Reads no further than the cut. */
internal fun String.takeCodePoints(n: Int): String {
    var end = 0
    repeat(n) {
        if (end >= length) return this
        end += Character.charCount(codePointAt(end))
    }
    return substring(0, end)
}
