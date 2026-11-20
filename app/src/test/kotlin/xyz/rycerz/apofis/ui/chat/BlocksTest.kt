package xyz.rycerz.apofis.ui.chat

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply

class BlocksTest {
    private fun blocks(text: String) = ArrayList<Block>().also { splitBlocks(text, 0, it) }

    private fun split(text: String) = blocks(text).map { Triple(text.substring(it.start, it.end), it.code, it.lang) }

    private fun LiveBlocks.texts() = blocks.indices.map { text(it) }

    @Test
    fun paragraphsAndFencedCode() {
        val text = "First line\nsame paragraph\n\n```kotlin\nval x = 1\n\nval y = 2\n```\nAfter code"
        assertEquals(
            listOf(
                Triple("First line\nsame paragraph", false, ""),
                Triple("val x = 1\n\nval y = 2", true, "kotlin"),
                Triple("After code", false, ""),
            ),
            split(text),
        )
    }

    @Test
    fun unclosedFenceRunsToTheEnd() {
        assertEquals(listOf(Triple("Intro", false, ""), Triple("ls -la\n", true, "")), split("Intro\n```\nls -la\n"))
    }

    @Test
    fun streamingMatchesAFullSplit() {
        val full = "One\n\n```sh\necho hi\n```\n\nTwo words here\n\nThree"
        val live = LiveBlocks()
        for (end in 1..full.length) live.update("r1", full.substring(0, end))
        assertEquals(split(full).map { it.first }, live.texts())
    }

    @Test
    fun anotherReplyStartsOver() {
        val live = LiveBlocks()
        live.update("live", "Old first\n\nOld second\n\nOld third")
        live.update("r9", "Old first\n\nNew")
        assertEquals(listOf("Old first", "New"), live.texts())
    }

    @Test
    fun blocksParsedPerTokenAreBounded() {
        val live = LiveBlocks()
        var text = "word ".repeat(19).trimEnd().plus("!!!!\n\n").repeat(1000) // 1000 paragraphs, 100000 characters
        live.update("live", text)
        val kept = live.blocks.first()
        var most = 0
        repeat(1000) {
            text += if (it % 50 == 49) "\n\n" else "more "
            live.update("live", text)
            most = maxOf(most, live.parsed)
        }
        assertTrue("parsed $most blocks for one token", most <= 2)
        assertSame(kept, live.blocks.first()) // earlier blocks are reused, not rebuilt
    }

    @Test
    fun longSingleParagraphIsChunked() {
        val text = "word ".repeat(20_000) // 100000 characters, no line break
        val chunks = blocks(text)
        assertTrue(chunks.all { it.end - it.start <= CHUNK })
        assertEquals(text, chunks.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(listOf(true), chunks.map { it.first }.distinct().take(1))
        assertTrue(chunks.drop(1).none { it.first })

        val live = LiveBlocks()
        var most = 0
        for (end in 5..text.length step 5) {
            live.update("live", text.substring(0, end))
            most = maxOf(most, live.parsed)
        }
        assertTrue(most <= 2)
        assertEquals(text, live.texts().joinToString(""))
    }

    @Test
    fun longCodeBlockIsChunkedAndStaysCode() {
        val body = (1..3000).joinToString("\n") { "line $it of the listing" }
        val text = "```txt\n$body\n```\nDone"
        val chunks = blocks(text)
        val code = chunks.filter { it.code }
        assertTrue(code.size > 1)
        assertTrue(code.all { it.end - it.start <= CHUNK && it.lang == "txt" })
        assertEquals(body, code.joinToString("\n") { text.substring(it.start, it.end) })
        assertEquals("Done", text.substring(chunks.last().start, chunks.last().end))

        val live = LiveBlocks()
        for (end in 1..text.length step 7) live.update("live", text.substring(0, end))
        live.update("live", text)
        assertEquals(chunks.map { text.substring(it.start, it.end) }, live.texts())
    }

    @Test
    fun historyIsNotMappedAgainWhenOnlyLiveTextChanges() {
        val history = (1..500).flatMap {
            listOf(UserMessage("u$it", "question $it", false, 1.0), AssistantReply("r$it", emptyList(), "answer $it", 1.0))
        } + UserMessage("u999", "latest", false, 1.0)
        val merger = LiveMerger()
        val rows = ChatRows(ZoneId.of("UTC"))
        var text = ""
        rows.rows(merger.merge(history, LiveReply("run", "running", text, emptyList(), null, Connection.LIVE)))
        val mapped = rows.mapped
        repeat(200) {
            text += "token "
            rows.rows(merger.merge(history, LiveReply("run", "running", text, emptyList(), null, Connection.LIVE)))
        }
        assertEquals(mapped, rows.mapped)
    }
}
