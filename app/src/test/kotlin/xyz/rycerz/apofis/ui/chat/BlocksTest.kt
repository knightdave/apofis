package xyz.rycerz.apofis.ui.chat

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply

class BlocksTest {
    private fun split(text: String) = parseBlocks(text).map { Triple(text.substring(it.start, it.end), it.code, it.lang) }

    /** What a fresh parse of [text] renders: (text, code) per row. */
    private fun fresh(text: String) = ReplyText().update("x", text).map { it.text to it.code }

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
    fun streamingMatchesAFreshParseAtEveryLength() {
        val full = "One\n\n```sh\necho hi\n```\n\nTwo words here\n  \nThree\n```\nopen\n  \n```\n\nab\n``\n``x\n `\n````\n" +
            "```py\ncode ``\n `\n  ```  \nafter\n```\nx\n```"
        val live = ReplyText()
        for (end in 1..full.length) {
            val text = full.substring(0, end)
            assertEquals("at $end", fresh(text), live.update("r1", text).map { it.text to it.code })
        }
    }

    @Test
    fun appendingOneCharacterKeepsEveryClosedRow() {
        val text = "word ".repeat(19).trimEnd().plus("!!!!\n\n").repeat(1000) // 1000 closed paragraphs
        val live = ReplyText()
        val before = live.update("r1", text)
        assertEquals(1000, before.size)
        val after = live.update("r1", text + "x")
        assertTrue("parsed ${live.parsed} blocks for one character", live.parsed <= 2)
        for (i in 0 until 999) assertSame("row $i", before[i], after[i])
        assertEquals("x", after.last().text)
        assertEquals((0..1000).map { "r1/b$it/c0" }, after.map { it.key })
    }

    @Test
    fun appendingBuildsRowsOnlyForTheChangedTail() {
        val live = ReplyText()
        for (text in listOf("word ".repeat(300_000), "```\n" + "a line of code\n".repeat(100_000), "word ".repeat(19).plus("!!!!\n\n").repeat(1000))) {
            live.update("r1", text)
            val built = Work.rowsBuilt
            val rows = live.update("r1", text + "x")
            assertTrue("built ${Work.rowsBuilt - built} rows for one character", Work.rowsBuilt - built <= 2)
            assertEquals(fresh(text + "x"), rows.map { it.text to it.code })
            live.update("other", "") // start over for the next text
        }
    }

    @Test
    fun chunkingIsOneForwardPass() {
        val line = "x".repeat(200_000)
        val before = Work.chunkSteps
        val pieces = chunks(line)
        assertTrue("inspected ${Work.chunkSteps - before}", Work.chunkSteps - before <= line.length)
        assertTrue(pieces.all { it.length <= CHUNK })
        assertEquals(line, pieces.joinToString(""))
    }

    @Test
    fun sameKeyReplacementSharesNothing() {
        val live = ReplyText()
        val before = live.update("r1", "AAA\n\nend\n\n")
        val after = live.update("r1", "BBB\n\nend!")
        assertEquals(listOf("BBB", "end!"), after.map { it.text })
        assertTrue(after.none { a -> before.any { it === a } })
    }

    @Test
    fun closingFenceAtAChunkBoundaryEndsTheCode() {
        val start = "```\n" + "x".repeat(CHUNK - 1) + "\n`"
        val full = start + "``\nplain"
        val live = ReplyText()
        live.update("r1", start)
        val rows = live.update("r1", full)
        assertEquals(fresh(full), rows.map { it.text to it.code })
        assertEquals("plain" to false, rows.last().let { it.text to it.code })
        assertFalse(rows.any { it.text.contains("```") })
    }

    @Test
    fun longSingleParagraphIsChunked() {
        val text = "word ".repeat(20_000) // 100000 characters, no line break
        val rows = ReplyText().update("r1", text)
        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.text.length <= CHUNK })
        assertEquals(text, rows.joinToString("") { it.text })
        assertEquals(listOf(true) + List(rows.size - 1) { false }, rows.map { it.first })
        assertEquals(List(rows.size - 1) { true } + false, rows.map { it.joinBelow })
    }

    @Test
    fun longCodeBlockIsChunkedAndStaysCode() {
        val body = (1..3000).joinToString("\n") { "line $it of the listing" }
        val text = "```txt\n$body\n```\nDone"
        val rows = ReplyText().update("r1", text)
        val code = rows.filter { it.code }
        assertTrue(code.size > 1)
        assertTrue(code.all { it.text.length <= CHUNK && it.lang == "txt" })
        assertEquals(body, code.joinToString("\n") { it.text })
        assertEquals("Done" to false, rows.last().let { it.text to it.code })

        val live = ReplyText()
        for (end in 1..text.length step 7) live.update("r1", text.substring(0, end))
        assertEquals(rows, live.update("r1", text))
    }

    @Test
    fun oneCharacterChangesOnlyTheTailRowsOfTheList() {
        val now = System.currentTimeMillis() / 1000.0 // the same day as the live item: no date row in between
        val history = (1..500).flatMap {
            listOf(UserMessage("u$it", "question $it", false, now), AssistantReply("r$it", emptyList(), "answer $it", now))
        } + UserMessage("u999", "latest", false, now, runId = "run")
        val merger = LiveMerger()
        val rows = ChatRows(ZoneId.of("UTC"))
        fun live(text: String) = LiveReply("run", "running", text, emptyList(), null, Connection.LIVE)
        var text = "paragraph\n\n".repeat(1000)
        val before = rows.rows(merger.merge(history, live(text)))
        val mapped = rows.mapped
        text += "x"
        val after = rows.rows(merger.merge(history, live(text)))
        assertEquals(mapped, rows.mapped) // history was not mapped again
        assertEquals(before.size + 1, after.size)
        val changed = after.indices.filter { i -> before.getOrNull(i) !== after[i] }
        assertTrue("changed rows $changed", changed.size <= 2) // the new paragraph and the reply's footer
    }
}
