package xyz.rycerz.apofis.push

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared vectors of the hook script, read as Task 1 wrote them; the app must give the same verdicts and reasons. */
class EndpointTest {
    private val vectors = Json.parseToJsonElement(File("../server/apofis-push/vectors.json").readText()).jsonObject
    private val release = setOf(NTFY_ORIGIN)

    @Test
    fun releaseVerdicts() {
        assertEquals(NTFY_ORIGIN, vectors.getValue("origin").jsonPrimitive.content)
        vectors.getValue("valid").jsonArray.map { it.jsonPrimitive.content }.forEachIndexed { i, v ->
            assertTrue("valid $i", validateEndpoint(v, release, null) == v) // compared, never printed
        }
        vectors.getValue("negatives").jsonArray.forEachIndexed { i, n ->
            val url = n.jsonObject.getValue("url").jsonPrimitive.content
            val got = checkEndpoint(url, release, null)
            assertTrue("negative $i", got.url == null)
            assertEquals("negative $i", n.jsonObject.getValue("reason").jsonPrimitive.content, got.reason)
        }
        val tooLong = vectors.getValue("tooLong").jsonObject
        val prefix = tooLong.getValue("prefix").jsonPrimitive.content
        val length = tooLong.getValue("length").jsonPrimitive.int
        val url = prefix + tooLong.getValue("pad").jsonPrimitive.content.repeat(length - prefix.length)
        assertEquals(length, url.length)
        assertEquals(EndpointCheck(null, tooLong.getValue("reason").jsonPrimitive.content), checkEndpoint(url, release, null))
        val max = vectors.getValue("maxLength").jsonPrimitive.int
        assertEquals("length is checked first", "length", checkEndpoint(" ".repeat(max + 1), release, null).reason)
        assertEquals("parse", checkEndpoint(" ".repeat(max), release, null).reason)
    }

    @Test
    fun loopbackOnlyWithItsExactOrigin() {
        val port = 41234
        fun fill(s: String) = s.replace("{port1}", "${port + 1}").replace("{port}", "$port")
        val lb = vectors.getValue("loopback").jsonObject
        val origin = fill(lb.getValue("httpOrigin").jsonPrimitive.content)
        val valid = fill(lb.getValue("valid").jsonPrimitive.content)
        assertTrue("loopback valid", validateEndpoint(valid, release, origin) == valid)
        assertEquals("scheme", checkEndpoint(valid, release, null).reason) // the release policy has no loopback origin
        assertNull(validateEndpoint(valid, release, fill("http://127.0.0.1:{port1}")))
        lb.getValue("negatives").jsonArray.forEachIndexed { i, n ->
            val o = n.jsonObject.getValue("httpOrigin").let { if (it is JsonNull) null else fill(it.jsonPrimitive.content) }
            val reason = n.jsonObject.getValue("reason").jsonPrimitive.content
            assertEquals("loopback negative $i", reason, checkEndpoint(fill(n.jsonObject.getValue("url").jsonPrimitive.content), release, o).reason)
        }
    }

    @Test
    fun lengthCountsCodePointsLikeTheScript() {
        val nonBmp = String(Character.toChars(0x1F600))
        assertEquals("parse", checkEndpoint(nonBmp.repeat(257), release, null).reason) // 257 code points, 514 UTF-16 units
        assertEquals("length", checkEndpoint(nonBmp.repeat(513), release, null).reason)
    }
}
