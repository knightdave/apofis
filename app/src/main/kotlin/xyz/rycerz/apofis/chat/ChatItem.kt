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
    val runId: String? = null, // the run this message started, while its send is still pending (bubble or the server row showing it)
    val images: List<String?> = emptyList(), // per image its full digest when this phone's photo is bound or pending, else null (a placeholder)
    val raw: String = text, // the stored content: the delivery evidence, never shown
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
    val args: String = "", // stored steps only: the full arguments (pretty JSON when they parse), cut to the display limit
    val argsTruncated: Boolean = false,
) : Step

enum class ReplyOutcome { STOPPED, FAILED, INTERRUPTED }

const val TOOL_RESULT_DISPLAY_LIMIT = 20000
