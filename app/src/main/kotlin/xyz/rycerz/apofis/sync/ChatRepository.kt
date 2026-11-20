package xyz.rycerz.apofis.sync

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import xyz.rycerz.apofis.chat.storedToolCalls
import xyz.rycerz.apofis.chat.storedToolResult
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.ReplyOutcomeEntity
import xyz.rycerz.apofis.db.SessionEntity

private const val SESSION_PAGE = 200
private const val MESSAGE_PAGE = 500
private const val MAX_PAGES = 100

// Process-wide: the app has a single database, so every repository instance must share one lock.
private val lock = Mutex()

// Chats a delete is in progress for: the send queue starts no run for them until the delete ends. In memory only, and
// process-wide like the lock: a delete never outlives the process, and a killed one leaves nothing to clear.
private val deleting: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/** For the send pipeline, under the repository lock: true while a delete of [sessionKey] is in progress. */
internal fun isBeingDeleted(sessionKey: String): Boolean = sessionKey in deleting

/** For the send pipeline: runs [block] under the repository lock (taken after SendQueue's own lock, never before). */
internal suspend fun <T> withRepositoryLock(block: suspend () -> T): T = lock.withLock { block() }

/**
 * The only data source of the UI. The server transcript is the truth; Room caches it next to the user's unsent
 * messages. Sync calls fetch everything first and write once, so an [ApiError] leaves the cache untouched.
 * Every mutating call holds one lock across its fetch and write, so a slow call never undoes a later one.
 * [sendQueue] is needed only by [send], [cancelSend] and [retrySend].
 * [active] turns false when the app switches servers: mutating calls then throw IllegalStateException("closed") and
 * sync calls return without touching rows, each checked inside the lock before every request and first write.
 */
class ChatRepository(
    private val api: HermesApi,
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val sendQueue: SendQueue? = null,
    private val active: () -> Boolean = { true },
) {
    private val sessionDao = db.sessionDao()

    private val refreshes = MutableStateFlow(0L)

    /** Counts the session list refreshes that completed and were stored; a shown chat checks itself again on each. */
    val refreshed: StateFlow<Long> = refreshes.asStateFlow()

    /** Test-only: runs in the plain [syncMessages] after its fetch has returned, right before its write. */
    internal var beforeSyncWrite: suspend () -> Unit = {}

    fun sessions(archived: Boolean = false): Flow<List<SessionEntity>> = sessionDao.observe(archived)

    /** One chat's row, or null once it is gone. */
    fun session(sessionKey: String): Flow<SessionEntity?> = sessionDao.observeOne(sessionKey).distinctUntilChanged()

    /** Server rows, then pending sends (oldest first) as unsent [UserMessage]s, with reply outcomes attached. */
    fun transcript(sessionKey: String): Flow<List<ChatItem>> {
        requireAppKey(sessionKey)
        return flow {
            var lastRows: List<MessageEntity>? = null
            var mapped: Pair<Map<Long, Int>, List<ChatItem>> = emptyMap<Long, Int>() to emptyList()
            var shown: List<UserMessage> = emptyList()
            db.invalidationTracker.createFlow("MessageEntity", "PendingSendEntity", "ReplyOutcomeEntity").collect {
                // One read transaction for all three tables: a send moving from pending to the transcript is never in neither.
                val (rows, outcomes, pending) = db.withTransaction {
                    Triple(db.messageDao().rows(sessionKey), db.replyOutcomeDao().rows(sessionKey), db.pendingSendDao().rows(sessionKey))
                }
                // Mapped once per change of the message rows, then reused when only pending sends or outcomes change.
                if (rows != lastRows) {
                    lastRows = rows
                    mapped = rows.withIndex().associate { (i, row) -> row.id to i } to buildTranscript(rows.map { it.toDto() })
                    shown = mapped.second.filterIsInstance<UserMessage>()
                }
                // A started send the stored rows already show (synced while its run goes on) is not shown twice;
                // the row showing it carries its run id instead, so the screen knows where that run's turn starts.
                val used = HashSet<String>()
                val started = HashMap<String, String>() // bubble key to run id
                val unsent = pending.filter { p ->
                    val bubble = matchIn(p, shown, used)
                    if (bubble != null && p.state == PendingState.SENDING && p.runId != null) started[bubble.key] = p.runId
                    bubble == null
                }
                var items = withOutcomes(mapped.first, mapped.second, outcomes)
                if (started.isNotEmpty()) items = items.map { if (it is UserMessage && it.key in started) it.copy(runId = started[it.key]) else it }
                emit(items + unsent.map { it.toItem() })
            }
        }.distinctUntilChanged()
    }

    /** [onCached] gets every cached chat's key once the list is stored, still under the lock, so no chat is created meanwhile. */
    suspend fun refreshSessions(onCached: (Set<String>) -> Unit = {}): Unit = lock.withLock {
        val listed = mutableListOf<SessionDto>()
        var offset = 0
        for (pages in 1..MAX_PAGES + 1) {
            if (pages > MAX_PAGES) throw ApiError.Protocol
            if (!active()) return@withLock
            val page = api.listSessions(SESSION_PAGE, offset)
            if (page.hasMore && page.data.isEmpty()) throw ApiError.Protocol // the rest of the list would be lost
            listed += page.data
            // Hermes pages a recency window of SESSION_PAGE rows and appends the pinned chats outside it to every page,
            // and its has_more ignores pins inside the window. So step by the window, and stop only at a page that
            // cannot have been a full window (fewer rows, pins included): a list stopped early would evict real chats.
            if (!page.hasMore && page.data.size < SESSION_PAGE) break
            offset += SESSION_PAGE
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
        listed.distinctBy { it.id }.forEach { take(it, cachedFor(it)) }
        val listedKeys = (visible + hidden).mapTo(HashSet()) { it.key }
        val unlisted = cached.filter { it.existsOnServer && it.key !in listedKeys }
        // The list never returns archived chats, so each one known locally is read by id. A chat the DAO keeps
        // for unsent work is read too, since the list also omits hidden chats.
        // No cap: only chats with a pending send or an active run are retained, and those are few by nature.
        val retained = sessionDao.retainedKeys().toHashSet()
        for (row in unlisted.filter { it.archived || it.key in retained }) {
            if (!active()) return@withLock
            val dto = try {
                api.getSession(row.currentId)
            } catch (_: ApiError.NotFound) {
                hidden += row.copy(hidden = true) // gone from the server: written only if the DAO keeps it
                continue
            }
            take(dto, row)
        }
        db.withTransaction {
            if (!active()) return@withTransaction
            sessionDao.replaceServerSessions(visible.distinctBy { it.key })
            // A hidden chat survives only while the DAO keeps it for unsent work; its transcript goes at once.
            for (row in hidden) {
                if (sessionDao.get(row.key) == null) continue
                sessionDao.upsert(row)
                sessionDao.deleteMessages(row.key)
                sessionDao.deleteReplyOutcomes(row.key)
            }
        }
        if (active()) {
            onCached(sessionDao.all().mapTo(HashSet()) { it.key })
            refreshes.update { it + 1 }
        }
    }

    /** True when server rows of this chat are cached on the phone. */
    suspend fun hasCachedMessages(sessionKey: String): Boolean = db.messageDao().hasRows(sessionKey)

    /**
     * True when the transcript was fetched: a request was made and the server accepted it. [onResponse] runs as each
     * page request returns successfully, before any mapping or database work.
     */
    suspend fun syncMessages(sessionKey: String, onResponse: () -> Unit = {}): Boolean {
        requireAppKey(sessionKey)
        return lock.withLock {
            val (tip, rows) = fetchRows(sessionKey, onResponse) ?: return@withLock false
            beforeSyncWrite()
            db.withTransaction { replaceRows(sessionKey, tip, rows) }
            true
        }
    }

    /**
     * Like [syncMessages], and runs [afterReplace] with the fetched rows inside the transaction that writes them, so
     * no observer sees a state between the two. Only the fetch is cancellable: from the write on, through
     * [afterCommit] (run after the lock is released, only when the transaction committed with the block run), the
     * call completes even if the caller is cancelled. [onResponse] runs as each page request returns successfully,
     * before any mapping or database work. True when the transcript was fetched.
     */
    suspend fun syncMessages(
        sessionKey: String,
        onResponse: () -> Unit = {},
        afterCommit: suspend () -> Unit = {},
        afterReplace: suspend (List<MessageDto>) -> Unit,
    ): Boolean {
        requireAppKey(sessionKey)
        lock.lock()
        val fetched = try {
            fetchRows(sessionKey, onResponse)
        } catch (e: Throwable) {
            lock.unlock()
            throw e
        }
        if (fetched == null) {
            lock.unlock()
            return false
        }
        val (tip, rows) = fetched
        withContext(NonCancellable) {
            val written = try {
                db.withTransaction {
                    replaceRows(sessionKey, tip, rows).also { if (it) afterReplace(rows.map { r -> r.toDto() }) }
                }
            } finally {
                lock.unlock()
            }
            if (written) afterCommit()
        }
        return true
    }

    /** Under the lock: the server transcript as rows, or null when the chat has no server copy (no request made). */
    private suspend fun fetchRows(sessionKey: String, onResponse: () -> Unit): Pair<String, List<MessageEntity>>? {
        val session = sessionDao.get(sessionKey) ?: return null
        if (!session.existsOnServer || session.hidden || !active()) return null
        val (tip, messages) = fetchMessages(session.currentId, onResponse) ?: return null
        return tip to messages.mapIndexed { i, m -> m.toEntity(sessionKey, i) }
    }

    /** In a transaction: false when the chat went away or got hidden since the fetch. */
    private suspend fun replaceRows(sessionKey: String, tip: String, rows: List<MessageEntity>): Boolean {
        if (!active()) return false
        val current = sessionDao.get(sessionKey) ?: return false
        if (current.hidden) return false
        db.messageDao().replaceSession(sessionKey, rows)
        sessionDao.upsert(current.movedTo(tip).copy(stale = false))
        return true
    }

    suspend fun createLocalSession(): String = lock.withLock {
        ensureOpen()
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

    /**
     * Marks [sessionKey] as being deleted: from now until [endDelete] the send queue starts no run for it (queued sends
     * stay queued). Taken under the lock, so a run start in progress finishes first and its run is visible afterwards.
     */
    suspend fun beginDelete(sessionKey: String) = lock.withLock {
        ensureOpen()
        deleting += sessionKey
    }

    /** Ends [beginDelete]; after a failed delete the chat can send again (drain the queue to send what waited). */
    suspend fun endDelete(sessionKey: String) = lock.withLock {
        deleting -= sessionKey
    }

    /** Server first: local rows, unsent messages included, go only once the server confirmed. */
    suspend fun delete(sessionKey: String) {
        requireAppKey(sessionKey)
        lock.withLock {
            ensureOpen()
            val session = sessionDao.get(sessionKey) ?: return
            if (session.existsOnServer) api.deleteSession(session.currentId)
            ensureOpen()
            sessionDao.delete(sessionKey)
        }
    }

    /**
     * Steers the session's running run, else queues [text] and tries to start it at once. The message is stored
     * before any request; [onStored] runs once it is (before any request). A hidden chat fails locally; an unknown one
     * throws IllegalArgumentException.
     */
    suspend fun send(sessionKey: String, text: String, onStored: () -> Unit = {}) {
        requireAppKey(sessionKey)
        queue().submit(sessionKey, text, onStored)
    }

    /** See [SendQueue.cancel]. */
    suspend fun cancelSend(idempotencyKey: String, mayHaveSentShown: Boolean = true): CancelResult =
        queue().cancel(idempotencyKey, mayHaveSentShown)

    /** See [SendQueue.retry]; a send queued again is started at once when possible. */
    suspend fun retrySend(idempotencyKey: String, confirmedDuplicateRisk: Boolean = false): RetryResult =
        queue().retry(idempotencyKey, confirmedDuplicateRisk).also { if (it == RetryResult.STARTED) queue().drain() }

    private fun queue() = checkNotNull(sendQueue) { "no send queue" }

    suspend fun resolveKey(serverSessionId: String): String? =
        if (isReservedId(serverSessionId)) null else sessionDao.findByAnyId(serverSessionId)?.key

    /**
     * All pages of the transcript and the tip id that answered. If the tip changes between pages the read
     * starts over once; a second change, a reserved tip or endless paging is [ApiError.Protocol].
     * Null when the repository was closed before a request.
     */
    private suspend fun fetchMessages(id: String, onResponse: () -> Unit): Pair<String, List<MessageDto>>? {
        repeat(2) {
            if (!active()) return null
            val first = api.messages(id, MESSAGE_PAGE, 0).also { onResponse() }
            val tip = first.sessionId
            if (isReservedId(tip)) throw ApiError.Protocol
            val all = first.messages.toMutableList()
            var last = first.messages
            var pages = 1
            while (last.size >= MESSAGE_PAGE) {
                if (++pages > MAX_PAGES) throw ApiError.Protocol
                if (!active()) return null
                val page = api.messages(tip, MESSAGE_PAGE, all.size).also { onResponse() }
                if (isReservedId(page.sessionId)) throw ApiError.Protocol
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
            ensureOpen()
            val session = sessionDao.get(sessionKey) ?: return
            if (session.existsOnServer) patch(session.currentId)
            ensureOpen()
            sessionDao.upsert(change(sessionDao.get(sessionKey) ?: return))
        }
    }

    private fun ensureOpen() = check(active()) { "closed" }
}

private const val DELIVERY_WINDOW_S = 60.0

/**
 * True when [shown] (the user bubbles of the stored transcript) contains the started send [p]. Used both to hide a
 * delivered pending bubble and by the run follower to confirm sends at run end. Server rows carry no run id or
 * idempotency key, so this is evidence, not identity: equal trimmed text for a run send (contained in a merged
 * steer bubble for a steer) on a row from at most a minute before the send was created. Two equal messages sent
 * within a minute can therefore confirm each other. [used] keeps one bubble from confirming two run sends.
 * Rows in any other state are never confirmed.
 */
internal fun deliveredIn(p: PendingSendEntity, shown: List<UserMessage>, used: MutableSet<String>): Boolean = matchIn(p, shown, used) != null

/** [deliveredIn], giving the bubble that shows [p]. */
internal fun matchIn(p: PendingSendEntity, shown: List<UserMessage>, used: MutableSet<String>): UserMessage? {
    val text = p.text.trim()
    val since = p.createdAt / 1000.0 - DELIVERY_WINDOW_S
    return when (p.state) {
        PendingState.SENDING -> shown.firstOrNull { !it.steered && it.key !in used && it.timestamp >= since && it.text.trim() == text }
            ?.also { used += it.key }
        PendingState.STEERED -> shown.firstOrNull { it.steered && it.timestamp >= since && it.text.contains(text) }
        else -> null
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
    sessionKey = sessionKey, position = position, id = id, role = role, displayKind = displayKind,
    content = if (role == "tool") storedToolResult(content) else content,
    reasoning = reasoning, toolCallsJson = storedToolCalls(toolCallsJson), toolCallId = toolCallId, toolName = toolName,
    timestamp = timestamp, finishReason = finishReason,
)

private fun MessageEntity.toDto() = MessageDto(
    id = id, role = role, displayKind = displayKind, content = content, reasoning = reasoning,
    toolCallsJson = toolCallsJson, toolCallId = toolCallId, toolName = toolName, timestamp = timestamp,
    finishReason = finishReason,
)

private fun PendingSendEntity.toItem() = UserMessage(
    key = "p$idempotencyKey", text = text, steered = state == PendingState.STEERING || state == PendingState.STEERED,
    timestamp = createdAt / 1000.0,
    state = when (state) {
        PendingState.STEERING -> SendState.SENDING // the steer request is still in flight
        PendingState.SENDING, PendingState.STEERED -> SendState.SENT // its run was admitted, or it reached the run
        PendingState.FAILED -> SendState.FAILED
        else -> SendState.QUEUED
    },
    idempotencyKey = idempotencyKey,
    error = error,
    attempted = attempted,
    runId = runId.takeIf { state == PendingState.SENDING }, // a steer's run id is the run it joined, not one it started
)

/**
 * Each outcome goes to the reply that owns its message row, or else to the last reply whose first row sits before
 * that row's position (a late tool result does not move a reply) ([positions]: message id to transcript position). An unknown row means the last reply.
 */
private fun withOutcomes(positions: Map<Long, Int>, items: List<ChatItem>, outcomes: List<ReplyOutcomeEntity>): List<ChatItem> {
    if (outcomes.isEmpty()) return items
    val replies = items.indices.filter { items[it] is AssistantReply }
    val result = items.toMutableList()
    for (o in outcomes) {
        val outcome = ReplyOutcome.entries.firstOrNull { it.name.equals(o.outcome, ignoreCase = true) } ?: continue
        val at = positions[o.afterMessageId]
        val target = replies.firstOrNull { o.afterMessageId in (items[it] as AssistantReply).rowIds }
            ?: if (at == null) {
                replies.lastOrNull()
            } else {
                replies.lastOrNull { r -> ((items[r] as AssistantReply).rowIds.firstOrNull()?.let(positions::get) ?: Int.MAX_VALUE) < at }
            }
            ?: continue
        result[target] = (result[target] as AssistantReply).copy(outcome = outcome)
    }
    return result
}
