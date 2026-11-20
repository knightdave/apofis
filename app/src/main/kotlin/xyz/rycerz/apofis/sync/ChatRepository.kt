package xyz.rycerz.apofis.sync

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
private const val MAX_PAGES = 100

/**
 * The only data source of the UI. The server transcript is the truth; Room caches it next to the user's unsent
 * messages. Sync calls fetch everything first and write once, so an [ApiError] leaves the cache untouched.
 * Every mutating call holds one lock across its fetch and write, so a slow call never undoes a later one.
 */
class ChatRepository(private val api: HermesApi, private val db: AppDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val sessionDao = db.sessionDao()
    private val lock = Mutex()

    fun sessions(archived: Boolean = false): Flow<List<SessionEntity>> = sessionDao.observe(archived)

    /** Server rows, then pending sends (oldest first) as unsent [UserMessage]s, with reply outcomes attached. */
    fun transcript(sessionKey: String): Flow<List<ChatItem>> {
        requireAppKey(sessionKey)
        // Mapped once per change of the message rows, then reused when only pending sends or outcomes change.
        val server = db.messageDao().observe(sessionKey).distinctUntilChanged().map { rows -> buildTranscript(rows.map { it.toDto() }) }
        return combine(server, db.replyOutcomeDao().observe(sessionKey), db.pendingSendDao().observe(sessionKey)) { items, outcomes, pending ->
            withOutcomes(items, outcomes) + pending.map { it.toItem() }
        }
    }

    suspend fun refreshSessions() = lock.withLock {
        val listed = mutableListOf<SessionDto>()
        var offset = 0
        for (pages in 1..MAX_PAGES + 1) {
            if (pages > MAX_PAGES) throw ApiError.Protocol
            val page = api.listSessions(SESSION_PAGE, offset)
            if (page.hasMore && page.data.isEmpty()) throw ApiError.Protocol // the rest of the list would be lost
            listed += page.data
            offset += page.data.size
            if (!page.hasMore) break
        }
        val cached = sessionDao.all()
        val index = HashMap<String, SessionEntity>()
        cached.forEach { row -> (row.lineage() + row.currentId + row.key).forEach { index[it] = row } }
        fun cachedFor(dto: SessionDto) = (listOf(sessionKeyOf(dto), dto.id) + dto.lineageIds).firstNotNullOfOrNull { index[it] }

        val visible = mutableListOf<SessionEntity>()
        val hidden = mutableListOf<SessionEntity>()
        fun take(dto: SessionDto, row: SessionEntity?) {
            if (isAppSession(dto)) {
                visible += dto.toEntity(row)
            } else if (row != null && dto.hidden && isAppSession(dto.copy(hidden = false))) {
                hidden += dto.toEntity(row).copy(hidden = true)
            }
        }
        listed.forEach { take(it, cachedFor(it)) }
        val listedKeys = (visible + hidden).mapTo(HashSet()) { it.key }
        // The list never returns archived chats, so each one known locally is read by id; a 404 drops it.
        for (row in cached) {
            if (!row.archived || !row.existsOnServer || row.key in listedKeys) continue
            val dto = try {
                api.getSession(row.currentId)
            } catch (_: ApiError.NotFound) {
                continue
            }
            take(dto, row)
        }
        db.withTransaction {
            sessionDao.replaceServerSessions(visible.distinctBy { it.key })
            // A hidden chat survives only while the DAO keeps it for unsent work; its transcript goes at once.
            for (row in hidden) {
                if (sessionDao.get(row.key) == null) continue
                sessionDao.upsert(row)
                sessionDao.deleteMessages(row.key)
                sessionDao.deleteReplyOutcomes(row.key)
            }
        }
    }

    suspend fun syncMessages(sessionKey: String) {
        requireAppKey(sessionKey)
        lock.withLock {
            val session = sessionDao.get(sessionKey) ?: return
            if (!session.existsOnServer || session.hidden) return
            val (tip, messages) = fetchMessages(session.currentId)
            val rows = messages.mapIndexed { i, m -> m.toEntity(sessionKey, i) }
            db.withTransaction {
                val current = sessionDao.get(sessionKey) ?: return@withTransaction
                if (current.hidden) return@withTransaction
                db.messageDao().replaceSession(sessionKey, rows)
                sessionDao.upsert(current.movedTo(tip).copy(stale = false))
            }
        }
    }

    suspend fun createLocalSession(): String = lock.withLock {
        val id = newSessionId()
        sessionDao.upsert(
            SessionEntity(
                key = id, currentId = id, lineageIds = "", title = null, pinned = false, archived = false,
                startedAt = now() / 1000.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false,
                existsOnServer = false,
            ),
        )
        id
    }

    suspend fun rename(sessionKey: String, title: String) =
        update(sessionKey, { api.patchSession(it, title = title) }) { it.copy(title = title) }

    suspend fun setPinned(sessionKey: String, pinned: Boolean) =
        update(sessionKey, { api.patchSession(it, pinned = pinned) }) { it.copy(pinned = pinned) }

    suspend fun setArchived(sessionKey: String, archived: Boolean) =
        update(sessionKey, { api.patchSession(it, archived = archived) }) { it.copy(archived = archived) }

    /** Server first: local rows, unsent messages included, go only once the server confirmed. */
    suspend fun delete(sessionKey: String) {
        requireAppKey(sessionKey)
        lock.withLock {
            val session = sessionDao.get(sessionKey) ?: return
            if (session.existsOnServer) api.deleteSession(session.currentId)
            sessionDao.delete(sessionKey)
        }
    }

    suspend fun resolveKey(serverSessionId: String): String? =
        if (isReservedId(serverSessionId)) null else sessionDao.findByAnyId(serverSessionId)?.key

    /**
     * All pages of the transcript and the tip id that answered. If the tip changes between pages the read
     * starts over once; a second change, a reserved tip or endless paging is [ApiError.Protocol].
     */
    private suspend fun fetchMessages(id: String): Pair<String, List<MessageDto>> {
        repeat(2) {
            val first = api.messages(id, MESSAGE_PAGE, 0)
            val tip = first.sessionId
            if (isReservedId(tip)) throw ApiError.Protocol
            val all = first.messages.toMutableList()
            var last = first.messages
            var pages = 1
            while (last.size >= MESSAGE_PAGE) {
                if (++pages > MAX_PAGES) throw ApiError.Protocol
                val page = api.messages(tip, MESSAGE_PAGE, all.size)
                if (page.sessionId != tip) break
                all += page.messages
                last = page.messages
            }
            if (last.size < MESSAGE_PAGE) return tip to all
        }
        throw ApiError.Protocol
    }

    /** Patches the server (only for server-backed chats), then applies [change] to the freshly read local row. */
    private suspend fun update(sessionKey: String, patch: suspend (String) -> Unit, change: (SessionEntity) -> SessionEntity) {
        requireAppKey(sessionKey)
        lock.withLock {
            val session = sessionDao.get(sessionKey) ?: return
            if (session.existsOnServer) patch(session.currentId)
            sessionDao.upsert(change(sessionDao.get(sessionKey) ?: return))
        }
    }
}

private fun requireAppKey(sessionKey: String) = require(!isReservedId(sessionKey)) { "reserved session id" }

private fun SessionEntity.lineage(): List<String> = lineageIds.split(',').filter { it.isNotEmpty() }

/** This row under server id [id], the previous current id kept in the lineage so old ids still resolve. */
private fun SessionEntity.movedTo(id: String, extra: List<String> = emptyList()): SessionEntity =
    copy(currentId = id, lineageIds = (lineage() + currentId + extra).distinct().filter { it != id }.joinToString(","))

private fun SessionDto.toEntity(cached: SessionEntity?): SessionEntity {
    val base = cached?.movedTo(id, lineageIds) ?: SessionEntity(
        key = sessionKeyOf(this), currentId = id, lineageIds = lineageIds.filter { it != id }.joinToString(","),
        title = null, pinned = false, archived = false, startedAt = startedAt, lastActiveAt = null, messageCount = 0,
        preview = null, stale = true, existsOnServer = true,
    )
    val changed = cached != null && (cached.currentId != id || cached.messageCount != messageCount || cached.lastActiveAt != lastActive)
    return base.copy(
        title = title, pinned = pinned, archived = archived, startedAt = startedAt, lastActiveAt = lastActive,
        messageCount = messageCount, preview = preview, stale = base.stale || changed, existsOnServer = true, hidden = false,
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
 * Each outcome goes to the reply that owns its message row, or else to the last reply whose first row id is
 * lower; with neither it is ignored.
 */
private fun withOutcomes(items: List<ChatItem>, outcomes: List<ReplyOutcomeEntity>): List<ChatItem> {
    if (outcomes.isEmpty()) return items
    val result = items.toMutableList()
    for (o in outcomes) {
        val outcome = ReplyOutcome.entries.firstOrNull { it.name.equals(o.outcome, ignoreCase = true) } ?: continue
        val owner = items.indexOfFirst { it is AssistantReply && o.afterMessageId in it.rowIds }
        val target = owner.takeIf { it >= 0 } ?: items.indices.lastOrNull {
            val first = (items[it] as? AssistantReply)?.rowIds?.firstOrNull()
            first != null && first < o.afterMessageId
        } ?: continue
        result[target] = (result[target] as AssistantReply).copy(outcome = outcome)
    }
    return result
}
