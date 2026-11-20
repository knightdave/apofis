package xyz.rycerz.apofis.sync

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.MessageDto
import xyz.rycerz.apofis.api.SessionDto
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ReplyOutcome
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.chat.buildTranscript
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.ReplyOutcomeEntity
import xyz.rycerz.apofis.db.SessionEntity

private const val SESSION_PAGE = 200
private const val MESSAGE_PAGE = 500

/**
 * The only data source of the UI. The server transcript is the truth; Room caches it next to the user's unsent
 * messages. Sync calls fetch everything first and write once, so an [ApiError] leaves the cache untouched.
 */
class ChatRepository(private val api: HermesApi, private val db: AppDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val sessionDao = db.sessionDao()

    fun sessions(archived: Boolean = false): Flow<List<SessionEntity>> = sessionDao.observe(archived)

    /** Server rows, then pending sends (oldest first) as unsent [UserMessage]s, with reply outcomes attached. */
    fun transcript(sessionKey: String): Flow<List<ChatItem>> {
        // Mapped once per change of the message rows, then reused when only pending sends or outcomes change.
        val server = db.messageDao().observe(sessionKey).distinctUntilChanged()
            .map { rows -> rows to buildTranscript(rows.map { it.toDto() }) }
        return combine(server, db.replyOutcomeDao().observe(sessionKey), db.pendingSendDao().observe(sessionKey)) { (rows, items), outcomes, pending ->
            withOutcomes(rows, items, outcomes) + pending.map { it.toItem() }
        }
    }

    suspend fun refreshSessions() {
        val listed = mutableListOf<SessionDto>()
        var offset = 0
        while (true) {
            val page = api.listSessions(SESSION_PAGE, offset)
            listed += page.data
            offset += page.data.size
            if (!page.hasMore || page.data.isEmpty()) break
        }
        val cached = sessionDao.observe(false).first() + sessionDao.observe(true).first()
        val index = HashMap<String, SessionEntity>()
        cached.forEach { row -> (row.lineage() + row.currentId + row.key).forEach { index[it] = row } }
        fun cachedFor(dto: SessionDto) = (listOf(sessionKeyOf(dto), dto.id) + dto.lineageIds).firstNotNullOfOrNull { index[it] }

        val rows = listed.filter(::isAppSession).map { it.toEntity(cachedFor(it)) }.distinctBy { it.key }.toMutableList()
        val listedKeys = rows.mapTo(HashSet()) { it.key }
        // The list never returns archived chats, so each one known locally is read by id; a 404 drops it.
        for (row in cached) {
            if (!row.archived || !row.existsOnServer || row.key in listedKeys) continue
            val dto = try {
                api.getSession(row.currentId)
            } catch (_: ApiError.NotFound) {
                continue
            }
            if (isAppSession(dto)) rows += dto.toEntity(row)
        }
        sessionDao.replaceServerSessions(rows)
    }

    suspend fun syncMessages(sessionKey: String) {
        val session = sessionDao.get(sessionKey) ?: return
        if (!session.existsOnServer) return
        val first = api.messages(session.currentId, MESSAGE_PAGE, 0)
        val messages = first.messages.toMutableList()
        var last = first.messages.size
        while (last >= MESSAGE_PAGE) {
            val page = api.messages(first.sessionId, MESSAGE_PAGE, messages.size)
            messages += page.messages
            last = page.messages.size
        }
        val rows = messages.mapIndexed { i, m -> m.toEntity(sessionKey, i) }
        db.withTransaction {
            val current = sessionDao.get(sessionKey) ?: return@withTransaction // deleted while fetching
            db.messageDao().replaceSession(sessionKey, rows)
            sessionDao.upsert(current.movedTo(first.sessionId).copy(stale = false))
        }
    }

    suspend fun createLocalSession(): String {
        val id = newSessionId()
        sessionDao.upsert(
            SessionEntity(
                key = id, currentId = id, lineageIds = "", title = null, pinned = false, archived = false,
                startedAt = now() / 1000.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false,
                existsOnServer = false,
            ),
        )
        return id
    }

    suspend fun rename(sessionKey: String, title: String) =
        update(sessionKey, { api.patchSession(it, title = title) }) { it.copy(title = title) }

    suspend fun setPinned(sessionKey: String, pinned: Boolean) =
        update(sessionKey, { api.patchSession(it, pinned = pinned) }) { it.copy(pinned = pinned) }

    suspend fun setArchived(sessionKey: String, archived: Boolean) =
        update(sessionKey, { api.patchSession(it, archived = archived) }) { it.copy(archived = archived) }

    /** Server first: local rows, unsent messages included, go only once the server confirmed. */
    suspend fun delete(sessionKey: String) {
        val session = sessionDao.get(sessionKey) ?: return
        if (session.existsOnServer) api.deleteSession(session.currentId)
        sessionDao.delete(sessionKey)
    }

    suspend fun resolveKey(serverSessionId: String): String? = sessionDao.findByAnyId(serverSessionId)?.key

    /** Patches the server (only for server-backed chats), then applies [change] to the freshly read local row. */
    private suspend fun update(sessionKey: String, patch: suspend (String) -> Unit, change: (SessionEntity) -> SessionEntity) {
        val session = sessionDao.get(sessionKey) ?: return
        if (session.existsOnServer) patch(session.currentId)
        db.withTransaction { sessionDao.get(sessionKey)?.let { sessionDao.upsert(change(it)) } }
    }
}

private fun SessionEntity.lineage(): List<String> = lineageIds.split(',').filter { it.isNotEmpty() }

/** This row under server id [id], the previous current id kept in the lineage so old ids still resolve. */
private fun SessionEntity.movedTo(id: String, extra: List<String> = emptyList()): SessionEntity =
    copy(currentId = id, lineageIds = (lineage() + currentId + extra).distinct().filter { it != id }.joinToString(","))

private fun SessionDto.toEntity(cached: SessionEntity?): SessionEntity {
    val lastActiveAt = lastActive ?: cached?.lastActiveAt // a single-session read has no last_active
    val base = cached?.movedTo(id, lineageIds) ?: SessionEntity(
        key = sessionKeyOf(this), currentId = id, lineageIds = lineageIds.filter { it != id }.joinToString(","),
        title = null, pinned = false, archived = false, startedAt = startedAt, lastActiveAt = null, messageCount = 0,
        preview = null, stale = true, existsOnServer = true,
    )
    val changed = cached != null && (cached.currentId != id || cached.messageCount != messageCount || cached.lastActiveAt != lastActiveAt)
    return base.copy(
        title = title, pinned = pinned, archived = archived, startedAt = startedAt, lastActiveAt = lastActiveAt,
        messageCount = messageCount, preview = preview ?: cached?.preview, stale = base.stale || changed, existsOnServer = true,
    )
}

private fun MessageDto.toEntity(sessionKey: String, position: Int) = MessageEntity(
    sessionKey = sessionKey, position = position, id = id, role = role, displayKind = displayKind, content = content,
    reasoning = reasoning, toolCallsJson = toolCallsJson, toolCallId = toolCallId, toolName = toolName,
    timestamp = timestamp, finishReason = finishReason,
)

private fun MessageEntity.toDto() = MessageDto(
    id = id, role = role, displayKind = displayKind, content = content, reasoning = reasoning,
    toolCallsJson = toolCallsJson, toolCallId = toolCallId, toolName = toolName, timestamp = timestamp,
    finishReason = finishReason,
)

private fun PendingSendEntity.toItem() = UserMessage(
    key = "p$idempotencyKey", text = text, steered = false, timestamp = createdAt / 1000.0,
    state = when (state) {
        "sending" -> SendState.SENDING
        "failed" -> SendState.FAILED
        else -> SendState.QUEUED
    },
    idempotencyKey = idempotencyKey,
)

/**
 * Each outcome goes to the last reply that starts at or before its message's row, which is the reply containing
 * that row or else the last one before it. An id missing from the rows counts as placed after every smaller id.
 */
private fun withOutcomes(rows: List<MessageEntity>, items: List<ChatItem>, outcomes: List<ReplyOutcomeEntity>): List<ChatItem> {
    if (outcomes.isEmpty()) return items
    val positionOf = rows.withIndex().associate { (i, row) -> row.id to i }
    // Item keys are a one-letter kind plus the id of the item's first row.
    val starts = items.map { positionOf[it.key.drop(1).toLongOrNull()] ?: -1 }
    val result = items.toMutableList()
    for (o in outcomes) {
        val outcome = ReplyOutcome.entries.firstOrNull { it.name.equals(o.outcome, ignoreCase = true) } ?: continue
        val at = positionOf[o.afterMessageId] ?: rows.indexOfLast { it.id < o.afterMessageId }
        if (at < 0) continue
        val target = items.indices.lastOrNull { items[it] is AssistantReply && starts[it] in 0..at } ?: continue
        result[target] = (result[target] as AssistantReply).copy(outcome = outcome)
    }
    return result
}
