package xyz.rycerz.apofis.api

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A chat's requested model, provider, reasoning level and fast mode; each is sent only when set (H7). */
data class ChatModel(val model: String? = null, val provider: String? = null, val reasoning: String? = null, val fast: Boolean? = null)

/** A stored body names each image `apofis-image:<sha256>`; [expandImageRefs] turns it into a data URL at each attempt. */
const val IMAGE_REF = "apofis-image:"
const val MAX_BODY_BYTES = 9_000_000 // H3 allows 10,000,000
const val MAX_IMAGES = 10 // H1: the server commits at most 10 images per message

// The longest expanded body an attempt builds, in characters. Above every admitted body once its session id and choice
// are added (at most about 9,006,000), and low enough that the posted bytes stay under the server's 10,000,000 when the
// caption's characters take several bytes each (at most about 500,000 more, given the row budget).
const val MAX_EXPANDED_LENGTH = 9_400_000

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

/** A reference in a stored body: [IMAGE_REF] and a 64 character SHA-256 digest, all ASCII. */
private const val REF_LENGTH = 13 + 64

// The builder writes each image part as exactly this text around its reference. `{"type":"image_url"` cannot occur
// inside a JSON string value (a quote there is escaped), so every occurrence is a part.
private const val PART = """{"type":"image_url""""
private const val URL_START = """{"type":"image_url","image_url":{"url":""""
private const val URL_END = """"}}"""

/**
 * The exact UTF-8 size of [body] once [expandImageRefs] replaced each reference by a data URL of the given byte count, in
 * order: the data URL and the reference it replaces are ASCII and need no JSON escape.
 */
fun expandedSize(body: String, imageBytes: List<Long>): Long =
    body.encodeToByteArray().size + imageBytes.sumOf { DATA_URL.length + 4 * ((it + 2) / 3) - REF_LENGTH }

/**
 * [body] with each image part's reference replaced by the data URL of its file; null unless the image parts are exactly
 * the builder's parts for [digests] in order, every file is there, its bytes hash to its digest and the result stays
 * within [maxLength] characters (checked as each file is read, before the next is read or anything is built). A caption
 * that looks like a part is escaped JSON and never matches. Without digests [body] itself is returned, uncopied.
 */
fun expandImageRefs(body: String, digests: List<String>, maxLength: Int = MAX_EXPANDED_LENGTH, bytes: (sha256: String) -> ByteArray?): String? {
    if (digests.isEmpty()) return body
    val urls = IntArray(digests.size) // where each reference starts
    var from = 0
    for ((i, d) in digests.withIndex()) {
        val at = body.indexOf(PART, from)
        val token = URL_START + IMAGE_REF + d + URL_END
        if (at < 0 || !body.startsWith(token, at)) return null
        urls[i] = at + URL_START.length
        from = at + token.length
    }
    if (body.indexOf(PART, from) >= 0) return null
    // Fixed text first, then each file's base64: the total only grows, so the first total over the maximum is final.
    var length = body.length + digests.size.toLong() * (DATA_URL.length - REF_LENGTH)
    if (length > maxLength) return null // the fixed text alone is too long: no file is read
    val files = ArrayList<ByteArray>(digests.size)
    for (d in digests) {
        val b = bytes(d)?.takeIf { sha256(it) == d } ?: return null
        length += 4 * ((b.size + 2L) / 3)
        if (length > maxLength) return null
        files += b
    }
    val out = StringBuilder(length.toInt())
    var copied = 0
    for ((i, b) in files.withIndex()) {
        out.append(body, copied, urls[i]).append(DATA_URL).append(Base64.getEncoder().encodeToString(b))
        copied = urls[i] + REF_LENGTH // a digest whose file hashed to it is 64 characters
    }
    return out.append(body, copied, body.length).toString()
}

private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
