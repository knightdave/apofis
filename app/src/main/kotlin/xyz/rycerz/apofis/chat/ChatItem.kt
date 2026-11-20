package xyz.rycerz.apofis.chat

sealed interface ChatItem { val key: String }

data class UserMessage(
    override val key: String,
    val text: String,
    val steered: Boolean,
    val timestamp: Double,
    val state: SendState = SendState.SENT,
    val idempotencyKey: String? = null,
    val error: String? = null, // unsent only: a fixed SendError code when FAILED
    val attempted: Boolean = false, // unsent only: a request for it may already have reached the server
) : ChatItem

enum class SendState { QUEUED, SENDING, FAILED, SENT }

data class AssistantReply(
    override val key: String,
    val steps: List<Step>,
    val text: String,
    val timestamp: Double,
    val live: Boolean = false,
    val outcome: ReplyOutcome? = null,
    val rowIds: List<Long> = emptyList(), // every assistant and tool row grouped into this reply
) : ChatItem

data class Notice(override val key: String, val kind: String, val text: String) : ChatItem

sealed interface Step

data class ReasoningStep(val text: String) : Step

data class ToolStep(
    val callId: String,
    val name: String,
    val argsPreview: String,
    val result: String?,
    val resultTruncated: Boolean,
    val error: Boolean,
    val running: Boolean,
    val durationSeconds: Double? = null, // live steps only: the stored transcript has no timing
) : Step

enum class ReplyOutcome { STOPPED, FAILED, INTERRUPTED }

const val TOOL_RESULT_DISPLAY_LIMIT = 20000
