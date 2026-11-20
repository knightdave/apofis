package xyz.rycerz.apofis.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RunBodyTest {
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
}
