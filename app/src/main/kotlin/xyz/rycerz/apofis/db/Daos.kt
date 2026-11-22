package xyz.rycerz.apofis.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    // Sessions never active sort by their start time. Hidden rows are kept only for their unsent work, never shown.
    @Query("SELECT * FROM SessionEntity WHERE archived = :archived AND hidden = 0 ORDER BY pinned DESC, COALESCE(lastActiveAt, startedAt) DESC")
    fun observe(archived: Boolean): Flow<List<SessionEntity>>

    @Query("SELECT * FROM SessionEntity")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM SessionEntity WHERE `key` = :key")
    suspend fun get(key: String): SessionEntity?

    @Query("SELECT * FROM SessionEntity WHERE `key` = :key")
    fun observeOne(key: String): Flow<SessionEntity?>

    // instr instead of LIKE: server ids contain '_', which LIKE treats as a wildcard.
    @Query(
        "SELECT * FROM SessionEntity WHERE :id != '' AND (`key` = :id OR currentId = :id " +
            "OR (instr(:id, ',') = 0 AND instr(',' || lineageIds || ',', ',' || :id || ',') > 0)) LIMIT 1",
    )
    suspend fun findByAnyId(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(s: SessionEntity)

    /** User deletion: removes the session and everything that belongs to it, unsent messages included. */
    @Transaction
    suspend fun delete(key: String) {
        deleteRow(key)
        deleteMessages(key)
        deletePendingSends(key)
        deleteActiveRuns(key)
        deleteReplyOutcomes(key)
    }

    /**
     * Upserts [rows] and evicts every server-backed session not among them, with its cached
     * messages and outcomes. Sessions not yet created on the server, and sessions that still
     * have a pending send or an active run, are kept so a sync never loses unsent messages.
     */
    @Transaction
    suspend fun replaceServerSessions(rows: List<SessionEntity>) {
        upsertAll(rows)
        val keep = rows.mapTo(HashSet()) { it.key }
        evictableKeys().filterNot { it in keep }.forEach {
            deleteRow(it)
            deleteMessages(it)
            deleteReplyOutcomes(it)
        }
    }

    @Upsert
    suspend fun upsertAll(rows: List<SessionEntity>)

    @Query(
        "SELECT `key` FROM SessionEntity WHERE existsOnServer = 1 " +
            "AND `key` NOT IN (SELECT sessionKey FROM PendingSendEntity) " +
            "AND `key` NOT IN (SELECT sessionKey FROM ActiveRunEntity)",
    )
    suspend fun evictableKeys(): List<String>

    /** Sessions that [replaceServerSessions] never evicts: those with a pending send or an active run. */
    @Query("SELECT sessionKey FROM PendingSendEntity UNION SELECT sessionKey FROM ActiveRunEntity")
    suspend fun retainedKeys(): List<String>

    @Query("DELETE FROM SessionEntity WHERE `key` = :key")
    suspend fun deleteRow(key: String)

    @Query("DELETE FROM MessageEntity WHERE sessionKey = :key")
    suspend fun deleteMessages(key: String)

    @Query("DELETE FROM PendingSendEntity WHERE sessionKey = :key")
    suspend fun deletePendingSends(key: String)

    @Query("DELETE FROM ActiveRunEntity WHERE sessionKey = :key")
    suspend fun deleteActiveRuns(key: String)

    @Query("DELETE FROM ReplyOutcomeEntity WHERE sessionKey = :key")
    suspend fun deleteReplyOutcomes(key: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM MessageEntity WHERE sessionKey = :sessionKey ORDER BY position")
    fun observe(sessionKey: String): Flow<List<MessageEntity>>

    @Transaction
    suspend fun replaceSession(sessionKey: String, rows: List<MessageEntity>) {
        require(rows.all { it.sessionKey == sessionKey }) { "rows must all belong to $sessionKey" }
        deleteSession(sessionKey)
        insertAll(rows)
    }

    @Query("SELECT * FROM MessageEntity WHERE sessionKey = :sessionKey ORDER BY position")
    suspend fun rows(sessionKey: String): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM MessageEntity WHERE sessionKey = :sessionKey)")
    suspend fun hasRows(sessionKey: String): Boolean

    @Query("SELECT * FROM MessageEntity WHERE sessionKey = :sessionKey AND role = 'user' ORDER BY position")
    suspend fun userRows(sessionKey: String): List<MessageEntity>

    @Query("SELECT MAX(id) FROM MessageEntity WHERE sessionKey = :sessionKey")
    suspend fun maxId(sessionKey: String): Long?

    @Query("DELETE FROM MessageEntity WHERE sessionKey = :sessionKey")
    suspend fun deleteSession(sessionKey: String)

    @Insert
    suspend fun insertAll(rows: List<MessageEntity>)
}

@Dao
interface PendingSendDao {
    @Query("SELECT * FROM PendingSendEntity WHERE sessionKey = :sessionKey ORDER BY createdAt")
    fun observe(sessionKey: String): Flow<List<PendingSendEntity>>

    @Query("SELECT * FROM PendingSendEntity WHERE sessionKey = :sessionKey ORDER BY createdAt")
    suspend fun rows(sessionKey: String): List<PendingSendEntity>

    @Query("SELECT * FROM PendingSendEntity WHERE state = 'queued' ORDER BY createdAt")
    suspend fun allQueued(): List<PendingSendEntity>

    @Query("SELECT * FROM PendingSendEntity WHERE state = :state ORDER BY createdAt")
    suspend fun withState(state: String): List<PendingSendEntity>

    @Query("SELECT * FROM PendingSendEntity WHERE idempotencyKey = :idempotencyKey")
    suspend fun get(idempotencyKey: String): PendingSendEntity?

    /** Sessions with a run in progress: a send already started, or an active run. */
    @Query("SELECT sessionKey FROM PendingSendEntity WHERE state = 'sending' UNION SELECT sessionKey FROM ActiveRunEntity")
    suspend fun busySessionKeys(): List<String>

    @Upsert
    suspend fun upsert(p: PendingSendEntity)

    /**
     * The API key changed: Hermes scopes idempotency keys by API key, so a send the old key may have started could run
     * again under the new one. Each such send waits for the user's confirmation (unconfirmed); rows that already need it stay.
     */
    @Query(
        "UPDATE PendingSendEntity SET state = 'failed', error = 'unconfirmed', runId = NULL WHERE attempted = 1 " +
            "AND state IN ('queued', 'failed') AND (error IS NULL OR error NOT IN ('steer_uncertain', 'expired_uncertain'))",
    )
    suspend fun quarantineAttempted()

    /** Changes the row only if it still exists (never recreates a deleted one); returns the rows changed. */
    @Update
    suspend fun update(p: PendingSendEntity): Int

    @Query("DELETE FROM PendingSendEntity WHERE idempotencyKey = :idempotencyKey")
    suspend fun delete(idempotencyKey: String): Int

    /**
     * The send that started the run and the steers delivered to it. Failed steers have no run id, and a steer cut off
     * mid-request (still `steering`) may not have been delivered, so neither is listed.
     */
    @Query("SELECT * FROM PendingSendEntity WHERE runId = :runId AND state IN ('sending', 'steered') ORDER BY createdAt")
    suspend fun forRun(runId: String): List<PendingSendEntity>
}

@Dao
interface ActiveRunDao {
    @Query("SELECT * FROM ActiveRunEntity ORDER BY startedAt")
    fun observeAll(): Flow<List<ActiveRunEntity>>

    @Query("SELECT * FROM ActiveRunEntity ORDER BY startedAt")
    suspend fun all(): List<ActiveRunEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM ActiveRunEntity)")
    fun observeAny(): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM ActiveRunEntity)")
    suspend fun any(): Boolean

    @Query("SELECT * FROM ActiveRunEntity WHERE runId = :runId")
    suspend fun get(runId: String): ActiveRunEntity?

    @Query("SELECT * FROM ActiveRunEntity WHERE sessionKey = :sessionKey ORDER BY startedAt DESC LIMIT 1")
    suspend fun forSession(sessionKey: String): ActiveRunEntity?

    @Query("SELECT * FROM ActiveRunEntity WHERE sessionKey = :sessionKey ORDER BY startedAt")
    suspend fun allForSession(sessionKey: String): List<ActiveRunEntity>

    /** Active runs of chats with a queued send: the runs that send waits for. */
    @Query("SELECT runId FROM ActiveRunEntity WHERE sessionKey IN (SELECT sessionKey FROM PendingSendEntity WHERE state = 'queued')")
    suspend fun blockingQueued(): List<String>

    @Upsert
    suspend fun upsert(r: ActiveRunEntity)

    @Query("DELETE FROM ActiveRunEntity WHERE runId = :runId")
    suspend fun delete(runId: String)

    /** Never moves backwards and never recreates a finished run's row. */
    @Query("UPDATE ActiveRunEntity SET lastSeq = :seq WHERE runId = :runId AND (lastSeq IS NULL OR lastSeq < :seq)")
    suspend fun updateLastSeq(runId: String, seq: Long)

    /** Never moves a run back from a terminal status: the first terminal status stored is final. */
    @Query("UPDATE ActiveRunEntity SET status = :status WHERE runId = :runId AND status NOT IN ('completed', 'failed', 'cancelled', 'interrupted')")
    suspend fun setStatus(runId: String, status: String)

    @Query("SELECT status FROM ActiveRunEntity WHERE runId = :runId")
    suspend fun status(runId: String): String?

    /**
     * The conditional [setStatus] and the row's status after it (null: the row is gone) as one transaction: it either
     * commits and returns what the row holds, or throws with nothing committed.
     */
    @Transaction
    suspend fun storeStatus(runId: String, status: String): String? {
        setStatus(runId, status)
        return status(runId)
    }

    @Query("UPDATE ActiveRunEntity SET stopRequested = 1 WHERE runId = :runId")
    suspend fun setStopRequested(runId: String)

    @Query("UPDATE ActiveRunEntity SET stopRequested = 0 WHERE runId = :runId")
    suspend fun clearStopRequested(runId: String)

    @Query("UPDATE ActiveRunEntity SET polling = 1 WHERE runId = :runId")
    suspend fun setPolling(runId: String)
}

@Dao
interface ReplyOutcomeDao {
    @Query("SELECT * FROM ReplyOutcomeEntity WHERE sessionKey = :sessionKey ORDER BY afterMessageId")
    fun observe(sessionKey: String): Flow<List<ReplyOutcomeEntity>>

    @Query("SELECT * FROM ReplyOutcomeEntity WHERE sessionKey = :sessionKey ORDER BY afterMessageId")
    suspend fun rows(sessionKey: String): List<ReplyOutcomeEntity>

    @Upsert
    suspend fun upsert(o: ReplyOutcomeEntity)
}

@Dao
interface NoticeOutboxDao {
    @Query("SELECT * FROM NoticeOutboxEntity WHERE generation = :generation ORDER BY runStartedAt, runId")
    suspend fun all(generation: Long): List<NoticeOutboxEntity>

    @Query("SELECT * FROM NoticeOutboxEntity WHERE generation = :generation AND eventId = :eventId")
    suspend fun get(generation: Long, eventId: String): NoticeOutboxEntity?

    @Upsert
    suspend fun upsert(row: NoticeOutboxEntity)

    @Query("DELETE FROM NoticeOutboxEntity WHERE generation = :generation AND eventId = :eventId")
    suspend fun delete(generation: Long, eventId: String)

    /** Strictly older only: a reader that is behind never removes a newer connection's row. */
    @Query("DELETE FROM NoticeOutboxEntity WHERE generation < :generation")
    suspend fun deleteOlder(generation: Long)

    @Query("DELETE FROM NoticeOutboxEntity")
    suspend fun clear()
}

@Dao
interface NoticeStateDao {
    @Query("SELECT * FROM NoticeStateEntity WHERE sessionKey = :sessionKey")
    suspend fun get(sessionKey: String): NoticeStateEntity?

    @Upsert
    suspend fun upsert(row: NoticeStateEntity)

    /** State of another connection, or of a chat no longer on this phone. */
    @Query("DELETE FROM NoticeStateEntity WHERE generation != :generation OR sessionKey NOT IN (SELECT `key` FROM SessionEntity)")
    suspend fun deleteStale(generation: Long)

    @Query("DELETE FROM NoticeStateEntity")
    suspend fun clear()
}
