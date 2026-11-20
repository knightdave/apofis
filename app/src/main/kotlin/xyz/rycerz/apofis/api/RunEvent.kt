package xyz.rycerz.apofis.api

/** One frame of `GET /v1/runs/{id}/events`. [seq] is -1 for frames the server sends without one (`replay.truncated`). */
sealed interface RunEvent {
    val seq: Long

    data class ToolStarted(override val seq: Long, val toolCallId: String, val tool: String, val preview: String, val timestamp: Double) : RunEvent

    /** [durationSeconds] is always 0 on current servers; use event timestamps instead. */
    data class ToolCompleted(
        override val seq: Long,
        val toolCallId: String,
        val tool: String,
        val error: Boolean,
        val preview: String,
        val durationSeconds: Double,
        val timestamp: Double,
    ) : RunEvent

    data class MessageDelta(override val seq: Long, val delta: String) : RunEvent
    data class MessageInterim(override val seq: Long, val text: String, val alreadyStreamed: Boolean) : RunEvent
    data class Reasoning(override val seq: Long, val text: String) : RunEvent
    data class ApprovalRequested(override val seq: Long) : RunEvent
    data class Steered(override val seq: Long) : RunEvent
    data class Truncated(override val seq: Long, val oldestRetainedSeq: Long) : RunEvent

    /** `run.completed`, `run.failed`, `run.cancelled` or `run.interrupted`; [status] is the part after `run.`. */
    data class Terminal(override val seq: Long, val status: String, val output: String?) : RunEvent
    data class Other(override val seq: Long, val name: String) : RunEvent
}
