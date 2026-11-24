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
 * A title as the server would keep it (its sanitizer, counted the same way): whitespace becomes a space, control and
 * invisible format characters and unpaired surrogates go, space runs collapse, the ends are trimmed, and at most
 * [TITLE_MAX] code points stay. Null when nothing remains.
 */
fun cleanTitle(text: String): String? {
    val kept = StringBuilder()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp).toByte()
        when {
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> kept.append(' ')
            type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE -> Unit
            else -> kept.appendCodePoint(cp)
        }
    }
    return kept.replace(SPACES, " ").trim().takeCodePoints(TITLE_MAX).trimEnd().ifEmpty { null }
}

private val SPACES = Regex(" {2,}")

/** At most [n] code points; a surrogate pair is never split. */
internal fun String.takeCodePoints(n: Int): String =
    if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))
