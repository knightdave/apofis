package xyz.rycerz.apofis.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.BufferedSource

internal object SseReader {
    private val terminal = setOf("run.completed", "run.failed", "run.cancelled", "run.interrupted")

    /** Parses run events until the source ends. A partial frame at EOF is dropped, as the SSE spec says. */
    fun events(source: BufferedSource): Flow<RunEvent> = flow {
        var id: Long? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (hasData) parse(data.toString(), id)?.let { emit(it) }
                data.clear()
                hasData = false
                id = null
                continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "id" -> id = value.toLongOrNull()
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
    }

    private fun parse(data: String, id: Long?): RunEvent? {
        val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        val name = obj.str("event") ?: return null
        val seq = obj.long("seq") ?: id ?: -1
        return when (name) {
            "tool.started" -> RunEvent.ToolStarted(seq, obj.str("tool_call_id").orEmpty(), obj.str("tool").orEmpty(), obj.str("preview").orEmpty())
            "tool.completed" -> RunEvent.ToolCompleted(
                seq,
                obj.str("tool_call_id").orEmpty(),
                obj.str("tool").orEmpty(),
                obj.bool("error") ?: false,
                obj.str("preview").orEmpty(),
                obj["duration"]?.primitive()?.doubleOrNull ?: 0.0,
            )
            "message.delta" -> RunEvent.MessageDelta(seq, obj.str("delta").orEmpty())
            "message.interim" -> RunEvent.MessageInterim(seq, obj.str("text").orEmpty(), obj.bool("already_streamed") ?: false)
            "reasoning.available" -> RunEvent.Reasoning(seq, obj.str("text").orEmpty())
            "approval.request" -> RunEvent.ApprovalRequested(seq)
            "run.steered" -> RunEvent.Steered(seq)
            "replay.truncated" -> RunEvent.Truncated(seq, obj.long("oldest_retained_seq") ?: 0)
            in terminal -> RunEvent.Terminal(seq, name.removePrefix("run."), obj.str("output"))
            else -> RunEvent.Other(seq, name)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.primitive() = runCatching { jsonPrimitive }.getOrNull()
    private fun JsonObject.str(key: String) = this[key]?.primitive()?.contentOrNull
    private fun JsonObject.long(key: String) = this[key]?.primitive()?.longOrNull
    private fun JsonObject.bool(key: String) = this[key]?.primitive()?.booleanOrNull
}
