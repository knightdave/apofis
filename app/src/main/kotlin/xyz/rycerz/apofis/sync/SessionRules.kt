package xyz.rycerz.apofis.sync

import java.util.UUID
import xyz.rycerz.apofis.api.SessionDto

private const val TITLE_LIMIT = 60

/**
 * Only chats this app created. The prefix is checked on [sessionKeyOf], not on `id`: after compression the
 * list shows a chat under a new tip id, and its lineage root is the id the app created.
 */
fun isAppSession(dto: SessionDto): Boolean {
    val id = sessionKeyOf(dto)
    return dto.source == "api_server" && id.startsWith("apofis-") && !id.startsWith("apofis-push-") &&
        !id.startsWith("apofis-probe-") && !dto.hidden
}

fun sessionKeyOf(dto: SessionDto): String = dto.lineageRootId ?: dto.id

fun newSessionId(): String = "apofis-" + UUID.randomUUID()

/** First non-blank line, trimmed, at most 60 characters; "" when there is none. */
fun titleFrom(text: String): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
    val cut = line.take(TITLE_LIMIT)
    return if (cut.length == TITLE_LIMIT && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}
