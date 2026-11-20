package xyz.rycerz.apofis.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    // Sessions never active sort by their start time.
    @Query("SELECT * FROM SessionEntity WHERE archived = :archived ORDER BY pinned DESC, COALESCE(lastActiveAt, startedAt) DESC")
    fun observe(archived: Boolean): Flow<List<SessionEntity>>

    @Query("SELECT * FROM SessionEntity WHERE `key` = :key")
    suspend fun get(key: String): SessionEntity?

    // instr instead of LIKE: server ids contain '_', which LIKE treats as a wildcard.
    @Query(
        "SELECT * FROM SessionEntity WHERE `key` = :id OR currentId = :id " +
            "OR (instr(:id, ',') = 0 AND instr(',' || lineageIds || ',', ',' || :id || ',') > 0) LIMIT 1",
    )
    suspend fun findByAnyId(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(s: SessionEntity)

    /** Deletes the session and everything that belongs to it. */
    @Transaction
    suspend fun delete(key: String) {
        deleteRow(key)
        deleteMessages(key)
        deletePendingSends(key)
        deleteActiveRuns(key)
        deleteReplyOutcomes(key)
    }

    /**
     * Upserts [rows] and deletes every server-backed session not among them.
     * Sessions not yet created on the server are kept.
     */
    @Transaction
    suspend fun replaceServerSessions(rows: List<SessionEntity>) {
        upsertAll(rows)
        val keep = rows.mapTo(HashSet()) { it.key }
        serverKeys().filterNot { it in keep }.forEach { delete(it) }
    }

    @Upsert
    suspend fun upsertAll(rows: List<SessionEntity>)

    @Query("SELECT `key` FROM SessionEntity WHERE existsOnServer = 1")
    suspend fun serverKeys(): List<String>

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
        deleteSession(sessionKey)
        insertAll(rows)
    }

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

    @Query("SELECT * FROM PendingSendEntity WHERE state = 'queued' ORDER BY createdAt")
    suspend fun allQueued(): List<PendingSendEntity>

    @Upsert
    suspend fun upsert(p: PendingSendEntity)

    @Query("DELETE FROM PendingSendEntity WHERE idempotencyKey = :idempotencyKey")
    suspend fun delete(idempotencyKey: String)
}

@Dao
interface ActiveRunDao {
    @Query("SELECT * FROM ActiveRunEntity ORDER BY startedAt")
    fun observeAll(): Flow<List<ActiveRunEntity>>

    @Query("SELECT * FROM ActiveRunEntity ORDER BY startedAt")
    suspend fun all(): List<ActiveRunEntity>

    @Query("SELECT * FROM ActiveRunEntity WHERE sessionKey = :sessionKey ORDER BY startedAt DESC LIMIT 1")
    suspend fun forSession(sessionKey: String): ActiveRunEntity?

    @Upsert
    suspend fun upsert(r: ActiveRunEntity)

    @Query("DELETE FROM ActiveRunEntity WHERE runId = :runId")
    suspend fun delete(runId: String)
}

@Dao
interface ReplyOutcomeDao {
    @Query("SELECT * FROM ReplyOutcomeEntity WHERE sessionKey = :sessionKey ORDER BY afterMessageId")
    fun observe(sessionKey: String): Flow<List<ReplyOutcomeEntity>>

    @Upsert
    suspend fun upsert(o: ReplyOutcomeEntity)
}
