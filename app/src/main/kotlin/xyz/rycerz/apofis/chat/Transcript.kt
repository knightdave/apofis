package xyz.rycerz.apofis.chat

import kotlinx.serialization.SerializationException
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
private const val PARSE_LIMIT = 262144
private const val SCAN_WINDOW = 4096
private val FIELD_AT_END = Regex(""""(exit_code|error)"\s*:\s*(-?\d+|null|true|false|"(?:[^"\\]|\\.)*")\s*$""")

/** Turns stored session rows (oldest first) into chat items. Linear in the number of rows. */
fun buildTranscript(rows: List<MessageDto>): List<ChatItem> {
    val items = mutableListOf<Any>() // ChatItem, or a ReplyBuilder that may still receive tool results
    var reply: ReplyBuilder? = null
    // Calls waiting for a result, up to the next user bubble, even across notices.
    val waiting = HashMap<String, Pair<ReplyBuilder, Int>>()
    for (row in rows) {
        val kind = row.displayKind
        when {
            row.role == "session_meta" || kind == "hidden" -> Unit
            row.role == "user" && (kind == null || kind == "internal_notification" || kind == "steer") -> {
                reply = null
                waiting.clear()
                val steered = kind == "steer"
                val text = if (steered) unwrapSteer(row.content) else row.content
                items += UserMessage("u${row.id}", text, steered, row.timestamp)
            }
            kind != null -> {
                reply = null
                items += Notice("n${row.id}", kind, row.content)
            }
            row.role == "tool" && waiting.containsKey(row.toolCallId) -> {
                val (owner, index) = waiting.remove(row.toolCallId)!! // a second result for the same id becomes its own step
                owner.setResult(index, row)
            }
            row.role == "assistant" || row.role == "tool" -> {
                val current = reply ?: ReplyBuilder(row).also { reply = it; items += it }
                if (row.role == "tool") current.addOrphan(row) else current.addAssistant(row, waiting)
            }
            else -> Unit // e.g. a system row: nothing to show
        }
    }
    return items.map { if (it is ReplyBuilder) it.build() else it as ChatItem }
}

/**
 * Returns the user's text from a steer row byte for byte: only the opening line with its terminator and the
 * closing marker with the one terminator before it are removed (whitespace outside the wrapper is allowed,
 * `\n` or `\r\n`). When no well-formed wrapper is found, returns the content trimmed.
 */
fun unwrapSteer(content: String): String {
    val start = content.indexOfFirst { !it.isWhitespace() }
    val lineEnd = if (start >= 0) content.indexOf('\n', start) else -1
    val closeAt = content.lastIndexOf(STEER_CLOSE)
    val wrapped = start >= 0 && content.startsWith(STEER_OPEN, start) && lineEnd >= 0 &&
        content.substring(start, lineEnd).trimEnd().endsWith("]") && closeAt > lineEnd &&
        content.substring(closeAt + STEER_CLOSE.length).isBlank()
    if (!wrapped) return content.trim()
    val bodyStart = lineEnd + 1
    if (closeAt == bodyStart) return ""
    if (content[closeAt - 1] != '\n') return content.trim()
    val bodyEnd = if (closeAt - 2 >= bodyStart && content[closeAt - 2] == '\r') closeAt - 2 else closeAt - 1
    return content.substring(bodyStart, bodyEnd)
}

private class ReplyBuilder(first: MessageDto) {
    private val key = "r${first.id}"
    private val timestamp = first.timestamp
    private val steps = mutableListOf<Step>()
    private val texts = mutableListOf<String>()

    fun addAssistant(row: MessageDto, waiting: MutableMap<String, Pair<ReplyBuilder, Int>>) {
        row.reasoning?.takeIf { it.isNotBlank() }?.let { steps += ReasoningStep(it) }
        for (call in parseArray(row.toolCallsJson)) {
            val obj = call as? JsonObject ?: continue
            val fn = obj["function"] as? JsonObject
            val id = obj.str("id") ?: obj.str("call_id") ?: continue
            val name = fn?.str("name") ?: obj.str("name") ?: ""
            waiting[id] = this to steps.size
            steps += ToolStep(id, name, argsPreview(fn?.get("arguments")), null, false, false, true)
        }
        row.content.takeIf { it.isNotBlank() }?.let { texts += it }
    }

    fun setResult(index: Int, row: MessageDto) {
        steps[index] = withResult(steps[index] as ToolStep, row)
    }

    /** A result row whose call is unknown: shown once, as its own step. */
    fun addOrphan(row: MessageDto) {
        steps += withResult(ToolStep(row.toolCallId ?: "", row.toolName ?: "", "", null, false, false, true), row)
    }

    private fun withResult(step: ToolStep, row: MessageDto): ToolStep {
        val content = row.content
        // Results above PARSE_LIMIT are never parsed: shown raw, judged by their tail.
        val obj = if (content.length <= PARSE_LIMIT) parse(content) as? JsonObject else null
        val shown = obj?.str("output") ?: content
        return step.copy(
            result = shown.take(TOOL_RESULT_DISPLAY_LIMIT),
            resultTruncated = shown.length > TOOL_RESULT_DISPLAY_LIMIT,
            error = if (content.length <= PARSE_LIMIT) obj != null && isError(obj) else tailIsError(content),
            running = false,
        )
    }

    fun build() = AssistantReply(key, steps.toList(), texts.joinToString("\n\n"), timestamp)
}

private fun JsonObject.str(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun parse(text: String?): JsonElement? =
    if (text.isNullOrBlank()) {
        null
    } else {
        try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

private fun parseArray(text: String?) = parse(text) as? JsonArray ?: JsonArray(emptyList())

/**
 * A result failed when `error` is present and is not null, not boolean false and not the empty string
 * (the string "false" is a failure), or when `exit_code` is non-zero. A result that is not a JSON object never failed.
 */
private fun isError(obj: JsonObject): Boolean {
    val error = obj["error"]
    val errorSet = error != null && error != JsonNull &&
        !(error is JsonPrimitive && ((!error.isString && error.booleanOrNull == false) || (error.isString && error.content.isEmpty())))
    val exit = (obj["exit_code"] as? JsonPrimitive)?.doubleOrNull
    return errorSet || (exit != null && exit != 0.0)
}

/**
 * [isError] for a huge result, from the top-level `exit_code` and `error` fields that end the document.
 * shortcut: recognises only the terminal tool's result shape (those fields last), use a streaming parse if other large tools need it.
 */
private fun tailIsError(content: String): Boolean {
    var rest = content.takeLast(SCAN_WINDOW).trimEnd()
    if (!rest.endsWith('}')) return false
    rest = rest.dropLast(1)
    val fields = HashMap<String, JsonElement>()
    for (i in 0 until 2) {
        val match = FIELD_AT_END.find(rest) ?: break
        val before = rest.substring(0, match.range.first).trimEnd()
        if (before.isEmpty() || (before.last() != ',' && before.last() != '{')) break
        fields.putIfAbsent(match.groupValues[1], parse(match.groupValues[2]) ?: break)
        if (before.last() == '{') break
        rest = before.dropLast(1)
    }
    return isError(JsonObject(fields))
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
