package xyz.rycerz.apofis.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import xyz.rycerz.apofis.api.ChatModel

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
    // The model choice of this chat, local to this phone, frozen into each run body when it is stored (see [choice]).
    val model: String? = null,
    val provider: String? = null,
    val reasoning: String? = null,
    val fast: Boolean? = null,
    // Written before the create request: the chat may exist on the server even when existsOnServer is still false.
    @ColumnInfo(defaultValue = "0") val createStarted: Boolean = false,
)

fun SessionEntity.choice() = ChatModel(model, provider, reasoning, fast)

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
    @ColumnInfo(defaultValue = "0") val droppedToolCalls: Int = 0,
    @ColumnInfo(defaultValue = "0") val shortened: Boolean = false,
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
    @ColumnInfo(defaultValue = "''") val images: String = "", // digests of the body's image references, comma separated, in order
    @ColumnInfo(defaultValue = "0") val steerOnly: Boolean = false,
)

@Entity
data class ActiveRunEntity(
    @PrimaryKey val runId: String,
    val sessionKey: String,
    val lastSeq: Long?,
    val status: String,
    val startedAt: Long,
    val polling: Boolean = false, // the event stream is gone for good (truncated or 404): follow by polling only
    val stopRequested: Boolean = false, // this app asked the server to stop the run (apart from what the server reports)
)

@Entity(primaryKeys = ["sessionKey", "afterMessageId"])
data class ReplyOutcomeEntity(
    val sessionKey: String,
    val afterMessageId: Long,
    val outcome: String,
    val detail: String?,
)

/**
 * A finish notice written in the same transaction that settles its run ([eventId] `run:<runId>`), so a process death
 * before the notification is posted cannot lose it. [notice] is a Notice name; [generation] the connection epoch it
 * was created under (the same run id on another connection is another row).
 */
@Entity(primaryKeys = ["generation", "eventId"])
data class NoticeOutboxEntity(
    val eventId: String,
    val sessionKey: String,
    val runId: String,
    val runStartedAt: Long,
    val notice: String,
    val generation: Long,
    val createdAt: Long,
)

/**
 * Per chat, the last decided event: the order every later event of the chat is compared with, and [alerted], the
 * request event ids of that run already alerted (a JSON array, at most 8). A finish is written after its post (or in
 * the transaction that consumes it without one); a request before its post, so it never alerts twice.
 */
@Entity
data class NoticeStateEntity(
    @PrimaryKey val sessionKey: String,
    val generation: Long,
    val runStartedAt: Long,
    val runId: String,
    val notice: String,
    val eventId: String,
    val alerted: String = "[]",
)

/** The photos of a confirmed own send, by the server message that shows it: [digests] ordered, comma separated, never empty. */
@Entity(primaryKeys = ["sessionKey", "messageId"])
data class ImageBindingEntity(
    val sessionKey: String,
    val messageId: Long,
    val digests: String,
)
