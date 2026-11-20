package xyz.rycerz.apofis.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A chat, keyed by a stable local key; [currentId] and [lineageIds] are server session ids. */
@Entity
data class SessionEntity(
    @PrimaryKey val key: String,
    val currentId: String,
    val lineageIds: String, // comma separated
    val title: String?,
    val pinned: Boolean,
    val archived: Boolean,
    val startedAt: Double,
    val lastActiveAt: Double?,
    val messageCount: Int,
    val preview: String?,
    val stale: Boolean,
    val existsOnServer: Boolean,
    val hidden: Boolean = false, // hidden on the server: kept only while it holds unsent work, never shown
)

@Entity(primaryKeys = ["sessionKey", "position"])
data class MessageEntity(
    val sessionKey: String,
    val position: Int,
    val id: Long,
    val role: String,
    val displayKind: String?,
    val content: String,
    val reasoning: String?,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val toolName: String?,
    val timestamp: Double,
    val finishReason: String?,
)

@Entity
data class PendingSendEntity(
    @PrimaryKey val idempotencyKey: String,
    val sessionKey: String,
    val text: String,
    val requestBody: String,
    val createdAt: Long,
    val state: String, // queued|sending|failed|steering|steered (see SendState in sync/SendQueue.kt)
    val runId: String?,
    val error: String?, // one of the fixed codes in SendError, never server text
    val attempted: Boolean = false, // a startRun or steer request for this row may have reached the server
)

@Entity
data class ActiveRunEntity(
    @PrimaryKey val runId: String,
    val sessionKey: String,
    val lastSeq: Long?,
    val status: String,
    val startedAt: Long,
    val polling: Boolean = false, // the event stream is gone for good (truncated or 404): follow by polling only
)

@Entity(primaryKeys = ["sessionKey", "afterMessageId"])
data class ReplyOutcomeEntity(
    val sessionKey: String,
    val afterMessageId: Long,
    val outcome: String,
    val detail: String?,
)
