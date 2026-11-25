package xyz.rycerz.apofis.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.rycerz.apofis.api.MessageDto

private const val STEER_OPEN = "[OUT-OF-BAND USER MESSAGE"
private const val STEER_CLOSE = "[/OUT-OF-BAND USER MESSAGE]"
private const val ARG_VALUE_LIMIT = 40
private const val STORED_PREVIEW = "apofis_preview" // the arguments preview of a call stored by [storedToolCalls]

/** The key of the bubble showing user row [id]; the one place it is made. */
fun userKey(id: Long) = "u$id"

/**
 * A user row that starts a turn, whose image block is parsed: no display kind (rows of other surfaces) or
 * "internal_notification", which the server gives every user row of a run. A steer row is not one.
 */
fun MessageDto.startsTurn() = role == "user" && (displayKind == null || displayKind == "internal_notification")

/**
 * Turns stored session rows (oldest first) into chat items. Linear in the number of rows. A turn's user row shows its
 * parsed text and the digests [bindings] (message id to digests) holds for its id when its own hints are exactly
 * their first 32 hex, in order; otherwise one placeholder per parsed image. So a row that is not shortened never borrows
 * another row's photo. [cuts] holds each row's stored cut by index (dropped calls, shortened): a shortened turn user
 * row shows its stored content unparsed with the photos bound to its id, even when another row with that id holds them.
 */
fun buildTranscript(
    rows: List<MessageDto>,
    bindings: Map<Long, List<String>> = emptyMap(),
    cuts: List<Pair<Int, Boolean>> = emptyList(),
): List<ChatItem> {
    val items = mutableListOf<Any>() // ChatItem, or a ReplyBuilder that may still receive tool results
    var reply: ReplyBuilder? = null
    // Calls waiting for a result, up to the next user bubble, even across notices.
    var waiting = HashMap<String, Pair<ReplyBuilder, Int>>()
    for ((i, row) in rows.withIndex()) {
        val kind = row.displayKind
        val cut = cuts.getOrNull(i)
        val shortened = cut?.second == true
        when {
            row.role == "session_meta" || kind == "hidden" -> Unit
            row.startsTurn() || row.role == "user" && kind == "steer" -> {
                reply = null
                waiting = HashMap() // fresh, since clear() keeps the peak capacity and costs that much per turn
                val turn = row.startsTurn()
                items += if (turn && shortened) {
                    UserMessage(userKey(row.id), row.content, false, row.timestamp, images = bindings[row.id] ?: emptyList(), raw = row.content, shortened = true)
                } else if (turn) {
                    val parsed = parseUserContent(row.content)
                    val images = bindings[row.id]?.takeIf { d -> d.map { it.take(32) } == parsed.images } ?: parsed.images.map { null }
                    UserMessage(userKey(row.id), parsed.text, false, row.timestamp, images = images, raw = row.content)
                } else {
                    UserMessage(userKey(row.id), unwrapSteer(row.content), true, row.timestamp, raw = row.content, shortened = shortened)
                }
            }
            kind != null -> {
                reply = null
                items += Notice("n${row.id}", kind, row.content)
            }
            row.role == "tool" && waiting.containsKey(row.toolCallId) -> {
                val (owner, index) = waiting.remove(row.toolCallId)!! // a second result for the same id becomes its own step
                owner.setResult(index, row)
                owner.mark(cut)
            }
            row.role == "assistant" || row.role == "tool" -> {
                val current = reply ?: ReplyBuilder(row).also { reply = it; items += it }
                if (row.role == "tool") current.addOrphan(row) else current.addAssistant(row, waiting)
                current.mark(cut)
            }
            else -> Unit // e.g. a system row: nothing to show
        }
    }
    return items.map { if (it is ReplyBuilder) it.build() else it as ChatItem }
}

/**
 * Returns the user's text from a steer row byte for byte: only the opening line with its terminator and the
 * closing marker with the one terminator before it are removed (whitespace outside the wrapper is allowed,
 * `\n` or `\r\n`). When no well-formed wrapper is found, returns the content unchanged.
 */
fun unwrapSteer(content: String): String {
    val start = content.indexOfFirst { !it.isWhitespace() }
    val lineEnd = if (start >= 0) content.indexOf('\n', start) else -1
    val closeAt = content.lastIndexOf(STEER_CLOSE)
    val wrapped = start >= 0 && content.startsWith(STEER_OPEN, start) && lineEnd >= 0 &&
        content.substring(start, lineEnd).trimEnd().endsWith("]") && closeAt > lineEnd &&
        content.substring(closeAt + STEER_CLOSE.length).isBlank()
    if (!wrapped) return content
    val bodyStart = lineEnd + 1
    if (closeAt == bodyStart) return ""
    if (content[closeAt - 1] != '\n') return content
    val bodyEnd = if (closeAt - 2 >= bodyStart && content[closeAt - 2] == '\r') closeAt - 2 else closeAt - 1
    return content.substring(bodyStart, bodyEnd)
}

private class ReplyBuilder(first: MessageDto) {
    private val key = "r${first.id}"
    private val timestamp = first.timestamp
    private val steps = mutableListOf<Step>()
    private val texts = mutableListOf<String>()
    private val rowIds = mutableListOf<Long>()
    private var dropped = 0
    private var shortened = false

    fun addAssistant(row: MessageDto, waiting: MutableMap<String, Pair<ReplyBuilder, Int>>) {
        rowIds += row.id
        row.reasoning?.takeIf { it.isNotBlank() }?.let { steps += ReasoningStep(it) }
        for (call in parseArray(row.toolCallsJson)) {
            val obj = call as? JsonObject ?: continue
            val fn = obj["function"] as? JsonObject
            val id = obj.str("id") ?: obj.str("call_id") ?: continue
            val name = fn?.str("name") ?: obj.str("name") ?: ""
            waiting[id] = this to steps.size
            val args = fullArgs(fn?.get("arguments"))
            steps += ToolStep(
                id, name, obj.str(STORED_PREVIEW) ?: argsPreview(fn?.get("arguments")), null, false, false, true,
                args = args.take(TOOL_RESULT_DISPLAY_LIMIT), argsTruncated = args.length > TOOL_RESULT_DISPLAY_LIMIT,
            )
        }
        row.content.takeIf { it.isNotBlank() }?.let { texts += it }
    }

    fun setResult(index: Int, row: MessageDto) {
        rowIds += row.id
        steps[index] = withResult(steps[index] as ToolStep, row)
    }

    /** Adds the stored cut of a row folded into this reply. */
    fun mark(cut: Pair<Int, Boolean>?) {
        if (cut == null) return
        dropped += cut.first
        shortened = shortened || cut.second
    }

    /** A result row whose call is unknown: shown once, as its own step. */
    fun addOrphan(row: MessageDto) {
        rowIds += row.id
        steps += withResult(ToolStep(row.toolCallId ?: "", row.toolName ?: "", "", null, false, false, true), row)
    }

    private fun withResult(step: ToolStep, row: MessageDto): ToolStep {
        val fields = decodeResult(row.content)
        val shown = (fields?.output as? JsonPrimitive)?.takeIf { it.isString }?.content ?: row.content
        return step.copy(
            result = shown.take(TOOL_RESULT_DISPLAY_LIMIT),
            resultTruncated = shown.length > TOOL_RESULT_DISPLAY_LIMIT,
            error = fields != null && isError(fields),
            running = false,
        )
    }

    fun build() = AssistantReply(
        key, steps.toList(), texts.joinToString("\n\n"), timestamp, rowIds = rowIds.toList(), droppedToolCalls = dropped, shortened = shortened,
    )
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

/** The only members of a tool result that are read; the decoder skips all others without building them. */
@Serializable
private class ResultFields(
    val output: JsonElement? = null,
    val error: JsonElement? = null,
    @SerialName("exit_code") val exitCode: JsonElement? = null,
)

private val resultJson = Json { ignoreUnknownKeys = true }

/** Null when the text is not a JSON object. */
private fun decodeResult(text: String): ResultFields? =
    try {
        resultJson.decodeFromString<ResultFields>(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

/**
 * The tool calls of an assistant row as they are stored. A list longer than [TOOL_RESULT_DISPLAY_LIMIT] is rebuilt
 * from what the transcript shows of each call (its id, its name, its preview and its full arguments cut one
 * character past the limit), so a multi-megabyte argument never has to fit a database cursor window, while
 * [buildTranscript] shows the same steps. A call without an id is never shown, so it is not kept.
 */
fun storedToolCalls(text: String?): String? {
    if (text == null || text.length <= TOOL_RESULT_DISPLAY_LIMIT) return text
    val calls = parseArray(text).mapNotNull { call ->
        val obj = call as? JsonObject ?: return@mapNotNull null
        val fn = obj["function"] as? JsonObject
        val id = obj.str("id") ?: obj.str("call_id") ?: return@mapNotNull null
        val arguments = fn?.get("arguments")
        buildJsonObject {
            put("id", id)
            put("function", buildJsonObject {
                put("name", fn?.str("name") ?: obj.str("name") ?: "")
                put("arguments", fullArgs(arguments).take(TOOL_RESULT_DISPLAY_LIMIT + 1))
            })
            put(STORED_PREVIEW, argsPreview(arguments))
        }
    }
    return JsonArray(calls).toString()
}

/**
 * The calls of [text] (as [storedToolCalls] returns it) that fit [budget] characters: whole calls in order while they
 * fit, and how many were dropped. Elements that are no call the transcript shows (not an object, or without an id) are
 * neither kept nor counted. Null when none is kept, or when the text is no array and does not fit (never shown).
 */
fun fittingToolCalls(text: String?, budget: Int): Pair<String?, Int> {
    if (text == null || text.length <= budget) return text to 0
    val array = parse(text) as? JsonArray ?: return null to 0
    val calls = array.filter { call -> (call as? JsonObject)?.let { it.str("id") ?: it.str("call_id") } != null }
    var left = budget - 1 // the opening bracket; each kept call then costs its length and one comma or the closing bracket
    val kept = calls.takeWhile { left -= it.toString().length + 1; left >= 0 } // no overflow: left is at least -1 before each step
    return (if (kept.isEmpty()) null else JsonArray(kept).toString()) to calls.size - kept.size
}

/**
 * A tool result as it is stored. One longer than [TOOL_RESULT_DISPLAY_LIMIT] is cut before storage to what the list
 * shows, its error decided first, so [buildTranscript] maps it the same way while a multi-megabyte row never has to fit
 * a database cursor window (2 MB on Android). Nothing reads the rest of a result.
 */
fun storedToolResult(content: String): String {
    if (content.length <= TOOL_RESULT_DISPLAY_LIMIT) return content
    // One character over the limit keeps the cut visible; a cut non-object text stays a non-object.
    val fields = decodeResult(content) ?: return content.take(TOOL_RESULT_DISPLAY_LIMIT + 1)
    val shown = (fields.output as? JsonPrimitive)?.takeIf { it.isString }?.content ?: content
    return buildJsonObject {
        put("output", shown.take(TOOL_RESULT_DISPLAY_LIMIT + 1))
        if (isError(fields)) put("error", true)
    }.toString()
}

/**
 * A result failed when `error` is present and is not null, not boolean false and not the empty string
 * (the string "false" is a failure), or when `exit_code` is a non-zero integer number (a quoted one is ignored). A result that is not a JSON object never failed.
 */
private fun isError(fields: ResultFields): Boolean {
    val error = fields.error
    val errorSet = error != null && error != JsonNull &&
        !(error is JsonPrimitive && ((!error.isString && error.booleanOrNull == false) || (error.isString && error.content.isEmpty())))
    val exit = (fields.exitCode as? JsonPrimitive)?.takeIf { !it.isString }?.content
    return errorSet || (exit != null && isNonZeroInteger(exit))
}

/** One pass over the literal: optional `-`, then ASCII digits only, at least one of them not `0`. */
private fun isNonZeroInteger(literal: String): Boolean {
    val digits = literal.removePrefix("-")
    return digits.isNotEmpty() && digits.all { it in '0'..'9' } && digits.any { it != '0' }
}

private val prettyJson = Json { prettyPrint = true }

/** The arguments as the user should read them: pretty JSON when they parse, else the text as sent. */
private fun fullArgs(arguments: JsonElement?): String = when (arguments) {
    null, JsonNull -> ""
    is JsonPrimitive -> if (!arguments.isString) arguments.content else parse(arguments.content)?.let(::pretty) ?: arguments.content
    else -> pretty(arguments)
}

private fun pretty(element: JsonElement) = prettyJson.encodeToString(JsonElement.serializer(), element)

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
