package xyz.rycerz.apofis.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Capabilities(val platform: String, val features: JsonObject = JsonObject(emptyMap()))

@Serializable
data class SessionDto(
    val id: String,
    val source: String,
    val title: String?,
    val pinned: Boolean,
    val archived: Boolean,
    val hidden: Boolean,
    @SerialName("started_at") val startedAt: Double,
    @SerialName("last_active") val lastActive: Double?, // list rows only
    @SerialName("message_count") val messageCount: Int,
    val preview: String?, // list rows only
    @SerialName("_lineage_root_id") val lineageRootId: String?,
    @SerialName("_lineage_ids") val lineageIds: List<String> = emptyList(),
)

@Serializable
data class SessionPage(val data: List<SessionDto>, @SerialName("has_more") val hasMore: Boolean)

@Serializable
data class MessageDto(
    val id: Long,
    val role: String,
    @SerialName("display_kind") val displayKind: String?,
    @Serializable(with = JsonTextSerializer::class) val content: String = "",
    val reasoning: String?,
    @SerialName("tool_calls") @Serializable(with = JsonTextSerializer::class) val toolCallsJson: String?,
    @SerialName("tool_call_id") val toolCallId: String?,
    @SerialName("tool_name") val toolName: String?,
    val timestamp: Double,
    @SerialName("finish_reason") val finishReason: String?,
)

@Serializable
data class MessagePage(@SerialName("session_id") val sessionId: String, @SerialName("data") val messages: List<MessageDto>)

@Serializable
data class RunStart(@SerialName("run_id") val runId: String, val replayed: Boolean = false)

@Serializable
data class RunStatus(
    @SerialName("run_id") val runId: String,
    val status: String,
    @SerialName("session_id") val sessionId: String?,
    val output: String?,
    val error: String?,
    @SerialName("admission_id") val admissionId: String?,
    @SerialName("execution_generation") val executionGeneration: Long?,
    @SerialName("pending_controls") val pendingControls: List<PendingControl> = emptyList(),
)

@Serializable
data class PendingControl(
    val kind: String,
    @SerialName("prompt_id") val requestId: String, // the server calls it prompt_id; answers send it as request_id
    @SerialName("execution_generation") val executionGeneration: Long,
    val command: String?,
    val question: String?,
)

enum class ApprovalChoice { ONCE, SESSION, ALWAYS, DENY }

/** The answer to an approval or clarify POST: [ALREADY_RESOLVED] when the prompt was answered or expired in between. */
enum class ControlResult { RESOLVED, ALREADY_RESOLVED }

@Serializable
internal data class ControlReply(
    @SerialName("run_id") val runId: String? = null,
    val status: String? = null,
    @SerialName("prompt_id") val promptId: String? = null,
)

@Serializable
internal data class SessionEnvelope(val session: SessionDto)

@Serializable
internal data class ErrorEnvelope(val error: ErrorBody) {
    @Serializable
    data class ErrorBody(val code: String?)
}

/**
 * Any JSON value as text: a string as itself, anything else as its JSON. JSON null becomes "" on purpose:
 * bookkeeping rows (`session_meta`) have null content and the app treats null and empty content the same.
 */
internal object JsonTextSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("JsonText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = when (val e = (decoder as JsonDecoder).decodeJsonElement()) {
        JsonNull -> ""
        is JsonPrimitive -> if (e.isString) e.content else e.toString()
        else -> e.toString()
    }

    override fun serialize(encoder: Encoder, value: String) = (encoder as JsonEncoder).encodeString(value)
}
