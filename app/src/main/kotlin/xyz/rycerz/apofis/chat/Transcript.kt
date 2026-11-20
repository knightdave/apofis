package xyz.rycerz.apofis.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import xyz.rycerz.apofis.api.MessageDto

private const val STEER_OPEN = "[OUT-OF-BAND USER MESSAGE"
private const val STEER_CLOSE = "[/OUT-OF-BAND USER MESSAGE]"
private const val ARG_VALUE_LIMIT = 40

/** Turns stored session rows (oldest first) into chat items. Linear in the number of rows. */
fun buildTranscript(rows: List<MessageDto>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var reply: ReplyBuilder? = null
    fun flush() {
        reply?.let { items += it.build() }
        reply = null
    }
    for (row in rows) {
        val kind = row.displayKind
        when {
            row.role == "session_meta" || kind == "hidden" -> Unit
            row.role == "user" && (kind == null || kind == "internal_notification" || kind == "steer") -> {
                flush()
                val steered = kind == "steer"
                val text = if (steered) unwrapSteer(row.content) else row.content
                items += UserMessage("u${row.id}", text, steered, row.timestamp)
            }
            kind != null -> {
                flush()
                items += Notice("n${row.id}", kind, row.content)
            }
            row.role == "assistant" || row.role == "tool" -> (reply ?: ReplyBuilder(row).also { reply = it }).add(row)
            else -> Unit // e.g. a system row: nothing to show
        }
    }
    flush()
    return items
}

/** Returns the user's text from a steer row, or the trimmed content when the wrapper is absent or malformed. */
fun unwrapSteer(content: String): String {
    val trimmed = content.trim()
    val lineEnd = trimmed.indexOf('\n')
    if (!trimmed.startsWith(STEER_OPEN) || !trimmed.endsWith(STEER_CLOSE) || lineEnd < 0) return trimmed
    if (!trimmed.substring(0, lineEnd).trimEnd().endsWith("]")) return trimmed
    val closeAt = trimmed.length - STEER_CLOSE.length
    if (closeAt <= lineEnd) return trimmed
    return trimmed.substring(lineEnd + 1, closeAt).trim()
}

private class ReplyBuilder(first: MessageDto) {
    private val key = "r${first.id}"
    private val timestamp = first.timestamp
    private val steps = mutableListOf<Step>()
    private val toolIndex = HashMap<String, Int>()
    private val texts = mutableListOf<String>()

    fun add(row: MessageDto) {
        if (row.role == "tool") return addResult(row)
        row.reasoning?.trim()?.takeIf { it.isNotEmpty() }?.let { steps += ReasoningStep(it) }
        for (call in parseArray(row.toolCallsJson)) {
            val obj = call as? JsonObject ?: continue
            val fn = obj["function"] as? JsonObject
            val id = obj.str("id") ?: obj.str("call_id") ?: continue
            val name = fn?.str("name") ?: obj.str("name") ?: ""
            toolIndex[id] = steps.size
            steps += ToolStep(id, name, argsPreview(fn?.get("arguments")), null, false, false, true)
        }
        row.content.trim().takeIf { it.isNotEmpty() }?.let { texts += it }
    }

    private fun addResult(row: MessageDto) {
        val content = row.content
        val truncated = content.length > TOOL_RESULT_DISPLAY_LIMIT
        val callId = row.toolCallId ?: ""
        val index = toolIndex[callId]
        val base = index?.let { steps[it] as ToolStep } ?: ToolStep(callId, row.toolName ?: "", "", null, false, false, true)
        val done = base.copy(
            result = if (truncated) content.take(TOOL_RESULT_DISPLAY_LIMIT) else content,
            resultTruncated = truncated,
            error = isError(content),
            running = false,
        )
        if (index != null) steps[index] = done else steps += done
    }

    fun build() = AssistantReply(key, steps.toList(), texts.joinToString("\n\n"), timestamp)
}

private fun JsonObject.str(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun parse(text: String?): JsonElement? =
    if (text.isNullOrBlank()) null else runCatching { Json.parseToJsonElement(text) }.getOrNull()

private fun parseArray(text: String?) = parse(text) as? JsonArray ?: JsonArray(emptyList())

/** Parses the result once; a non-JSON result is never an error. */
private fun isError(content: String): Boolean {
    val obj = parse(content) as? JsonObject ?: return false
    val error = obj["error"]
    val errorSet = error != null && error != JsonNull &&
        !(error is JsonPrimitive && (error.booleanOrNull == false || (error.isString && error.content.isEmpty())))
    val exit = (obj["exit_code"] as? JsonPrimitive)?.doubleOrNull
    return errorSet || (exit != null && exit != 0.0)
}

private fun argsPreview(arguments: JsonElement?): String {
    val args = when (arguments) {
        JsonNull -> return ""
        is JsonObject -> arguments
        is JsonPrimitive -> parse(arguments.content) as? JsonObject ?: return arguments.content.take(ARG_VALUE_LIMIT)
        else -> return ""
    }
    return args.entries.take(2).joinToString(", ") { (name, value) ->
        val text = if (value is JsonPrimitive && value.isString) value.content else value.toString()
        "$name=${text.take(ARG_VALUE_LIMIT)}"
    }
}
