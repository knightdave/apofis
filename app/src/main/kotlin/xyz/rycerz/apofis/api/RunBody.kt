package xyz.rycerz.apofis.api

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A chat's requested model, provider, reasoning level and fast mode; each is sent only when set (H7). */
data class ChatModel(val model: String? = null, val provider: String? = null, val reasoning: String? = null, val fast: Boolean? = null)

/** A stored body names each image `apofis-image:<sha256>`; [expandImageRefs] turns it into a data URL at each attempt. */
const val IMAGE_REF = "apofis-image:"
const val MAX_BODY_BYTES = 9_000_000 // H3 allows 10,000,000

private const val DATA_URL = "data:image/jpeg;base64,"

/**
 * The exact JSON sent to `POST /v1/runs`; callers store it so a retry sends the same bytes. Without images `input` is
 * the text; with images it is one user message: a text part (empty without a caption), then one `image_url` part per
 * digest in order (H1, H2). Choice keys follow `input`, each only when set.
 */
fun buildRunBody(sessionId: String, text: String, images: List<String> = emptyList(), choice: ChatModel = ChatModel()): String = buildJsonObject {
    put("session_id", sessionId)
    if (images.isEmpty()) {
        put("input", text)
    } else {
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                    for (d in images) {
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", IMAGE_REF + d) }
                        }
                    }
                }
            }
        }
    }
    choice.model?.let { put("model", it) }
    choice.provider?.let { put("provider", it) }
    if (choice.reasoning != null || choice.fast != null) {
        putJsonObject("model_options") {
            choice.reasoning?.let { put("reasoning_effort", it) }
            choice.fast?.let { put("fast", it) }
        }
    }
}.toString()

/** The `url` values of the image parts of [body] built by [buildRunBody], in order; empty for a text body. */
private fun imageUrls(body: JsonObject): List<String> =
    contentOf(body)?.mapNotNull { imageUrlOf(it) } ?: emptyList()

private fun contentOf(body: JsonObject): JsonArray? = ((body["input"] as? JsonArray)?.lastOrNull() as? JsonObject)?.get("content") as? JsonArray

private fun imageUrlOf(part: JsonElement): String? {
    val obj = part as? JsonObject ?: return null
    if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "image_url") return null
    return ((obj["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
}

private fun utf8Size(s: String): Long = s.encodeToByteArray().size.toLong()

/**
 * The exact UTF-8 size of [body] once [expandImageRefs] replaced each image reference by a data URL of the given byte
 * count, in order. Base64 and the data URL label are ASCII and need no JSON escape.
 */
fun expandedSize(body: String, imageBytes: List<Long>): Long {
    val urls = if (imageBytes.isEmpty()) emptyList() else imageUrls(Json.parseToJsonElement(body) as JsonObject)
    require(urls.size == imageBytes.size) { "image count" }
    return utf8Size(body) + urls.zip(imageBytes).sumOf { (url, n) -> DATA_URL.length + 4 * ((n + 2) / 3) - (utf8Size(JsonPrimitive(url).toString()) - 2) }
}

/**
 * [body] with the `url` of each image part replaced by the data URL of its file; null when the parts are not exactly
 * [digests] in order, a file is missing, or a file's bytes no longer hash to its digest. A message text that looks like
 * a reference is never touched. Without digests [body] itself is returned, uncopied.
 */
fun expandImageRefs(body: String, digests: List<String>, bytes: (sha256: String) -> ByteArray?): String? {
    if (digests.isEmpty()) return body
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
    val content = contentOf(root) ?: return null
    if (imageUrls(root) != digests.map { IMAGE_REF + it }) return null
    var next = 0
    val parts = content.map { part ->
        if (imageUrlOf(part) == null) return@map part
        val d = digests[next++]
        val b = bytes(d)?.takeIf { sha256(it) == d } ?: return null
        JsonObject((part as JsonObject) + ("image_url" to JsonObject(mapOf("url" to JsonPrimitive(DATA_URL + Base64.getEncoder().encodeToString(b))))))
    }
    val input = root["input"] as JsonArray
    val message = input.last() as JsonObject
    val expanded = JsonArray(input.dropLast(1) + JsonObject(message + ("content" to JsonArray(parts))))
    return JsonObject(root + ("input" to expanded)).toString()
}

private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
