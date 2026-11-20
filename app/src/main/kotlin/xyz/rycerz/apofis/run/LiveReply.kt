package xyz.rycerz.apofis.run

import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.ToolStep

/** The reply of a run still in progress, as streamed. Replaced by the stored transcript once the run ends. */
data class LiveReply(
    val runId: String,
    val status: String,
    val text: String,
    val steps: List<ToolStep>,
    val pending: PendingControl?,
    val connection: Connection,
)

enum class Connection { LIVE, RECONNECTING, POLLING }
