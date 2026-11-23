package xyz.rycerz.apofis.api

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

class RunBodyTest {
    private val one = byteArrayOf(1, 2, 3)
    private val two = byteArrayOf(4, 5, 6, 7)
    private val d1 = sha(one)
    private val d2 = sha(two)
    private val files = mapOf(d1 to one, d2 to two)

    @Test
    fun buildsValidJson() {
        assertEquals("""{"session_id":"apofis-1","input":"hi"}""", buildRunBody("apofis-1", "hi"))
    }

    @Test
    fun escapesQuotesAndNewlines() {
        val text = "say \"hi\"\nand\\more"
        val body = buildRunBody("apofis-1", text)
        assertEquals("""{"session_id":"apofis-1","input":"say \"hi\"\nand\\more"}""", body)
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals("apofis-1", parsed["session_id"]!!.jsonPrimitive.content)
        assertEquals(text, parsed["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun textOnlyUnchanged() { // control: today's bytes
        assertEquals("""{"session_id":"s","input":"hi"}""", buildRunBody("s", "hi", emptyList(), ChatModel()))
    }

    @Test
    fun imagesShape() {
        val part = { d: String -> """{"type":"image_url","image_url":{"url":"apofis-image:$d"}}""" }
        assertEquals(
            """{"session_id":"s","input":[{"role":"user","content":[{"type":"text","text":"look"},${part(d1)},${part(d2)}]}]}""",
            buildRunBody("s", "look", listOf(d1, d2)),
        )
        assertEquals(
            """{"session_id":"s","input":[{"role":"user","content":[{"type":"text","text":""},${part(d2)},${part(d1)}]}]}""",
            buildRunBody("s", "", listOf(d2, d1)),
        )
    }

    @Test
    fun choiceKeysOnlyWhenSet() {
        val base = """{"session_id":"s","input":"hi""""
        assertEquals("""$base,"model":"m"}""", buildRunBody("s", "hi", choice = ChatModel(model = "m")))
        assertEquals("""$base,"provider":"p"}""", buildRunBody("s", "hi", choice = ChatModel(provider = "p")))
        assertEquals("""$base,"model_options":{"reasoning_effort":"low"}}""", buildRunBody("s", "hi", choice = ChatModel(reasoning = "low")))
        assertEquals("""$base,"model_options":{"fast":true}}""", buildRunBody("s", "hi", choice = ChatModel(fast = true)))
        assertEquals(
            """$base,"model":"m","provider":"p","model_options":{"reasoning_effort":"none","fast":false}}""",
            buildRunBody("s", "hi", choice = ChatModel("m", "p", "none", false)),
        )
        assertEquals(
            """{"session_id":"s","input":[{"role":"user","content":[{"type":"text","text":"x"},""" +
                """{"type":"image_url","image_url":{"url":"apofis-image:$d1"}}]}],"model":"m"}""",
            buildRunBody("s", "x", listOf(d1), ChatModel(model = "m")),
        )
    }

    @Test
    fun expandReplacesRefs() {
        val body = buildRunBody("s", "x", listOf(d1))
        assertEquals(
            """{"session_id":"s","input":[{"role":"user","content":[{"type":"text","text":"x"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]}]}""",
            expandImageRefs(body, listOf(d1)) { files[it] },
        )
        val choice = buildRunBody("s", "x", listOf(d1, d2), ChatModel("m", "p", "high", true))
        assertEquals(
            choice.replace("apofis-image:$d1", "data:image/jpeg;base64,AQID").replace("apofis-image:$d2", "data:image/jpeg;base64,BAUGBw=="),
            expandImageRefs(choice, listOf(d1, d2)) { files[it] },
        )
    }

    @Test
    fun expandMissingIsNull() {
        assertNull(expandImageRefs(buildRunBody("s", "x", listOf(d1, d2)), listOf(d1, d2)) { if (it == d1) one else null })
    }

    @Test
    fun expandWrongDigestIsNull() {
        assertNull(expandImageRefs(buildRunBody("s", "x", listOf(d1)), listOf(d1)) { two }) // the file changed since it was stored
    }

    @Test
    fun expandCountMismatchIsNull() {
        assertNull(expandImageRefs(buildRunBody("s", "x", listOf(d1, d2)), listOf(d1)) { files[it] })
        assertNull(expandImageRefs(buildRunBody("s", "x", listOf(d1)), listOf(d1, d2)) { files[it] })
        assertNull(expandImageRefs(buildRunBody("s", "x", listOf(d1, d2)), listOf(d2, d1)) { files[it] }) // order counts
        assertNull(expandImageRefs(buildRunBody("s", "x"), listOf(d1)) { files[it] }) // a text body has no image part
    }

    @Test
    fun expandWithoutRefsReturnsSameInstance() {
        val body = buildRunBody("s", "apofis-image:$d1")
        assertSame(body, expandImageRefs(body, emptyList()) { error("read without images") })
    }

    @Test
    fun expandIsDeterministic() {
        val body = buildRunBody("s", "zażółć \"x\"", listOf(d1, d2), ChatModel("m", null, "low", null))
        val expected = """{"session_id":"s","input":[{"role":"user","content":[{"type":"text","text":"zażółć \"x\""},""" +
            """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}},""" +
            """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,BAUGBw=="}}]}],"model":"m","model_options":{"reasoning_effort":"low"}}"""
        repeat(2) { assertEquals(expected, expandImageRefs(body, listOf(d1, d2)) { files[it] }) }
    }

    @Test
    fun captionHoldingThePartsOwnTokenIsText() {
        val token = """{"type":"image_url","image_url":{"url":"apofis-image:$d1"}}"""
        val body = buildRunBody("s", token, listOf(d1))
        val escaped = token.replace("\"", "\\\"")
        assertEquals(
            """{"session_id":"s","input":[{"role":"user","content":[{"type":"text","text":"$escaped"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]}]}""",
            expandImageRefs(body, listOf(d1)) { files[it] },
        )
    }

    @Test
    fun anotherImagePartFailsClosed() {
        val body = buildRunBody("s", "x", listOf(d1))
        val extra = body.replace("]}]}", """,{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}]}""")
        val detail = body.replace(""""url":"apofis-image:$d1"}}""", """"url":"apofis-image:$d1","detail":"high"}}""")
        val before = body.replace("""{"type":"text"""", """{"type":"image_url","image_url":{"url":"apofis-image:$d2"}},{"type":"text"""")
        for (b in listOf(extra, detail, before)) assertNull(b, expandImageRefs(b, listOf(d1)) { files[it] })
    }

    @Test
    fun duplicateDigestsExpandEachPart() {
        val body = buildRunBody("s", "x", listOf(d1, d1))
        assertEquals(body.replace("apofis-image:$d1", "data:image/jpeg;base64,AQID"), expandImageRefs(body, listOf(d1, d1)) { files[it] })
        assertNull(expandImageRefs(body, listOf(d1)) { files[it] })
    }

    @Test
    fun textThatLooksLikeARefIsNotExpanded() {
        for (text in listOf("apofis-image:$d1", """{"url":"apofis-image:$d1"}""", """"},{"type":"image_url","image_url":{"url":"apofis-image:$d1"}}""")) {
            val body = buildRunBody("s", text, listOf(d2))
            val expanded = expandImageRefs(body, listOf(d2)) { files[it] }!!
            val content = Json.parseToJsonElement(expanded).jsonObject["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            assertEquals(text, content[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(2, content.size)
            assertEquals(body.replace("apofis-image:$d2", "data:image/jpeg;base64,BAUGBw=="), expanded)
            assertNull(expandImageRefs(body, listOf(d1)) { files[it] }) // the text is not one of the image parts
        }
    }

    @Test
    fun expandedSizeIsExact() {
        val sizes = listOf(0, 1, 2, 3, 4, 5, 6, 1000)
        val blobs = sizes.map { n -> ByteArray(n) { (it * 7).toByte() } }
        val digests = blobs.map(::sha)
        val byDigest = digests.zip(blobs).toMap()
        for (caption in listOf("", "a \"q\" \\ \n\t\u0001", "zażółć gęślą jaźń", "😀👍🏽 家", "x".repeat(5000))) {
            for (k in listOf(0, 1, digests.size)) {
                val body = buildRunBody("apofis-1", caption, digests.take(k), ChatModel("m", "p", "high", true))
                val expanded = expandImageRefs(body, digests.take(k)) { byDigest[it] }!!
                assertEquals(expanded.encodeToByteArray().size.toLong(), expandedSize(body, sizes.take(k).map { it.toLong() }))
            }
        }
    }
}
