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
private val EXIT_CODE = Regex(""""exit_code"\s*:\s*(-?\d+(?:\.\d+)?)""")
private val ERROR_TEXT = Regex(""""error"\s*:\s*"[^"]""")

/** Turns stored session rows (oldest first) into chat items. Linear in the number of rows. */
fun buildTranscript(rows: List<MessageDto>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var reply: ReplyBuilder? = null
    val deferred = mutableListOf<Notice>() // notices that arrived while a tool call waited for its result
    fun flush() {
        reply?.let { items += it.build() }
        reply = null
        items += deferred
        deferred.clear()
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
                val notice = Notice("n${row.id}", kind, row.content)
                if (reply?.waiting == true) {
                    deferred += notice
                } else {
                    flush()
                    items += notice
                }
            }
            row.role == "assistant" || row.role == "tool" -> (reply ?: ReplyBuilder(row).also { reply = it }).add(row)
            else -> Unit // e.g. a system row: nothing to show
        }
    }
    flush()
    return items
}

/**
 * Returns the user's text from a steer row: only the opening line, the closing marker and the newline
 * before it are removed. When the wrapper is absent or malformed, returns the content trimmed.
 */
fun unwrapSteer(content: String): String {
    val lineEnd = content.indexOf('\n')
    val closeAt = content.lastIndexOf(STEER_CLOSE)
    val wrapped = content.startsWith(STEER_OPEN) && lineEnd >= 0 && content.substring(0, lineEnd).trimEnd().endsWith("]") &&
        closeAt > lineEnd && content[closeAt - 1] == '\n' && content.substring(closeAt + STEER_CLOSE.length).isBlank()
    if (!wrapped) return content.trim()
    return if (closeAt == lineEnd + 1) "" else content.substring(lineEnd + 1, closeAt - 1)
}

private class ReplyBuilder(first: MessageDto) {
    private val key = "r${first.id}"
    private val timestamp = first.timestamp
    private val steps = mutableListOf<Step>()
    private val toolIndex = HashMap<String, Int>() // calls still waiting for their result

    val waiting get() = toolIndex.isNotEmpty()
    private val texts = mutableListOf<String>()

    fun add(row: MessageDto) {
        if (row.role == "tool") return addResult(row)
        row.reasoning?.takeIf { it.isNotBlank() }?.let { steps += ReasoningStep(it) }
        for (call in parseArray(row.toolCallsJson)) {
            val obj = call as? JsonObject ?: continue
            val fn = obj["function"] as? JsonObject
            val id = obj.str("id") ?: obj.str("call_id") ?: continue
            val name = fn?.str("name") ?: obj.str("name") ?: ""
            toolIndex[id] = steps.size
            steps += ToolStep(id, name, argsPreview(fn?.get("arguments")), null, false, false, true)
        }
        row.content.takeIf { it.isNotBlank() }?.let { texts += it }
    }

    private fun addResult(row: MessageDto) {
        val content = row.content
        // shortcut: results above PARSE_LIMIT are shown raw and judged by a head/tail scan, never parsed.
        val obj = if (content.length <= PARSE_LIMIT) parse(content) as? JsonObject else null
        val shown = obj?.str("output") ?: content
        val callId = row.toolCallId ?: ""
        val index = toolIndex.remove(callId) // a second result for the same id becomes its own step
        val base = index?.let { steps[it] as ToolStep } ?: ToolStep(callId, row.toolName ?: "", "", null, false, false, true)
        val done = base.copy(
            result = shown.take(TOOL_RESULT_DISPLAY_LIMIT),
            resultTruncated = shown.length > TOOL_RESULT_DISPLAY_LIMIT,
            error = if (content.length <= PARSE_LIMIT) obj != null && isError(obj) else scanForError(content),
            running = false,
        )
        if (index != null) steps[index] = done else steps += done
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
 * A result failed when `error` is present and is neither null, nor false, nor an empty string,
 * or when `exit_code` is non-zero. A result that is not a JSON object never failed.
 */
private fun isError(obj: JsonObject): Boolean {
    val error = obj["error"]
    val errorSet = error != null && error != JsonNull &&
        !(error is JsonPrimitive && (error.booleanOrNull == false || (error.isString && error.content.isEmpty())))
    val exit = (obj["exit_code"] as? JsonPrimitive)?.doubleOrNull
    return errorSet || (exit != null && exit != 0.0)
}

/** Same rule as [isError] for huge results, judged only from the first and last SCAN_WINDOW characters. */
private fun scanForError(content: String): Boolean = listOf(content.take(SCAN_WINDOW), content.takeLast(SCAN_WINDOW)).any { part ->
    ERROR_TEXT.containsMatchIn(part) || EXIT_CODE.findAll(part).any { it.groupValues[1].toDouble() != 0.0 }
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
