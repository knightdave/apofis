package xyz.rycerz.apofis.ui.chat

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.ui.markdown.BlockKind
import xyz.rycerz.apofis.ui.markdown.BlockMode
import xyz.rycerz.apofis.ui.markdown.DisplayChunk
import xyz.rycerz.apofis.ui.markdown.MdBlock

class BlocksTest {
    private fun split(text: String) = parseBlocks(text).map { Triple(text.substring(it.start, it.end), it.kind == BlockKind.CODE, it.lang) }

    /** The plain text a row shows (paragraphs only, which is all these tests use). */
    private val TextRow.text: String
        get() = when (val c = chunk) {
            is DisplayChunk.Literal -> c.text
            is DisplayChunk.Code -> c.text
            is DisplayChunk.Blocks -> c.blocks.joinToString("\n\n") { (it as MdBlock.Paragraph).text.text }
            is DisplayChunk.TableSlice -> error("no tables here")
        }

    /** What a fresh parse of [text] renders: chunk and kind per row. */
    private fun fresh(text: String) = ReplyText().update("x", text).map { it.chunk to it.kind }

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
            assertEquals("at $end", fresh(text), live.update("r1", text).map { it.chunk to it.kind })
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
            assertEquals(fresh(text + "x"), rows.map { it.chunk to it.kind })
            live.update("other", "") // start over for the next text
        }
    }

    /** Rows built when [before] grows to [after]; checks the result against a fresh parse and keeps the first [kept] rows. */
    private fun appendCost(before: String, after: String, kept: Int): Long {
        val live = ReplyText()
        val old = live.update("r1", before)
        val built = Work.rowsBuilt
        val rows = live.update("r1", after)
        val cost = Work.rowsBuilt - built
        assertEquals(fresh(after), rows.map { it.chunk to it.kind })
        for (i in 0 until kept) assertSame("row $i", old[i], rows[i])
        return cost
    }

    private val closed = "word ".repeat(19).plus("!!!!\n\n").repeat(1000) // 1000 closed paragraphs

    @Test
    fun aFenceOpeningOrClosingBuildsOnlyTheTail() {
        assertTrue(appendCost(closed + "``", closed + "```", kept = 1000) <= 2) // the third backtick opens a fence
        assertTrue(appendCost(closed + "```\ncode\n``", closed + "```\ncode\n```", kept = 1000) <= 2) // and one closes it
        assertTrue(appendCost(closed + "```\ncode\n```", closed + "```\ncode\n```x", kept = 1000) <= 2) // no longer a closing line
        assertTrue(appendCost(closed + "tail\n ", closed + "tail\n x", kept = 1000) <= 2) // the blank line turns back into prose
    }

    @Test
    fun aLongOpenParagraphEndingAtAFenceIsParsedOnce() {
        // Literal while open (over the limit); a fence on its last line makes it stable, so it is parsed once, whole
        // (it is within the parser's 20,000 characters; a longer one stays literal, see parserInputIsBounded).
        val para = "a line of prose\n".repeat(1_000) + "``"
        val live = ReplyText()
        val open = live.update("r1", para)
        assertTrue(open.all { it.chunk is DisplayChunk.Literal })
        val parses = Work.markdownParses
        val rows = live.update("r1", para + "`")
        assertEquals(1, Work.markdownParses - parses)
        assertEquals(fresh(para + "`"), rows.map { it.chunk to it.kind })
        assertEquals(BlockKind.CODE, rows.last().kind)
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
        assertEquals(fresh(full), rows.map { it.chunk to it.kind })
        assertEquals("plain" to BlockKind.TEXT, rows.last().let { it.text to it.kind })
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
        val code = rows.filter { it.kind == BlockKind.CODE }
        assertTrue(code.size > 1)
        assertTrue(code.all { it.text.length <= CHUNK && it.lang == "txt" })
        assertEquals(body, code.joinToString("\n") { it.text })
        assertEquals("Done" to BlockKind.TEXT, rows.last().let { it.text to it.kind })

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

    @Test
    fun boundedWorkPerToken() {
        for ((length, maxParses) in listOf(7000 to 1L, 9000 to 0L)) {
            val text = "Intro.\n\n" + "word ".repeat(length / 5)
            val live = ReplyText()
            live.update("r1", text)
            val open = parseBlocks(text).last()
            assertEquals(length, open.end - open.start)
            assertEquals(if (length > 8000) BlockMode.LITERAL else BlockMode.PARSED, open.mode)
            val parses = Work.markdownParses
            val rows = live.update("r1", text + "x")
            assertTrue("$length: ${Work.markdownParses - parses} parses", Work.markdownParses - parses <= maxParses)
            assertEquals(fresh(text + "x"), rows.map { it.chunk to it.kind })
        }
    }

    @Test
    fun fenceClosedAtEof() {
        val block = parseBlocks("```sh\nls\n```").single()
        assertEquals(BlockKind.CODE, block.kind)
        assertTrue(block.fenceClosed)
        assertFalse(block.stable)
        assertFalse(parseBlocks("```sh\nls\n``").single().fenceClosed)
        assertTrue(parseBlocks("```sh\nls\n```\n").single().stable)
    }

    @Test
    fun aLooseListStaysOneBlockUntilALineEndsIt() {
        assertEquals(1, parseBlocks("- a\n\n- b\n\n  more").size)
        assertFalse(parseBlocks("- a\n\n").single().stable) // the next line may still continue the list
        assertFalse(parseBlocks("- a\n\n1").single().stable) // "1" may become "1. b"
        val ended = parseBlocks("- a\n\n1 apple")
        assertEquals(listOf(true, false), ended.map { it.stable })
        assertTrue(parseBlocks("para\n\n").single().stable) // a paragraph without a list ends at the blank line
    }

    @Test
    fun longCodeLinesStayWholeUpToTheTableLimit() {
        val line = "y".repeat(5000)
        val text = "```\n$line\nshort\n```\n"
        val code = ReplyText().update("r1", text).map { (it.chunk as DisplayChunk.Code).text }
        assertEquals(listOf(line, "short"), code)
        val huge = "z".repeat(45_000)
        val pieces = ReplyText().update("r1", "```\n$huge").map { (it.chunk as DisplayChunk.Code).text }
        assertEquals(listOf(20_000, 20_000, 5_000), pieces.map { it.length })
        // Streamed, including a closing fence right after a long line: always what a fresh parse gives.
        val streamed = "```\n" + "y".repeat(1600) + "\n```\nafter"
        val live = ReplyText()
        for (end in 1..streamed.length) assertEquals("at $end", fresh(streamed.substring(0, end)), live.update("r1", streamed.substring(0, end)).map { it.chunk to it.kind })
    }

    /** Scanner steps, chunking steps and row references copied by one more update of a live reply, through ChatRows. */
    private fun workFor(before: String, after: String): Pair<Long, Long> {
        val rows = ChatRows(ZoneId.of("UTC"))
        fun items(text: String) = Joined(emptyList<ChatItem>(), listOf<ChatItem>(AssistantReply("r1", emptyList(), text, 0.0, live = true)))
        rows.rows(items(before))
        val scan = Work.scanSteps + Work.chunkSteps + Work.lfChars
        val copies = Work.rowCopies
        rows.rows(items(after))
        return (Work.scanSteps + Work.chunkSteps + Work.lfChars - scan) to (Work.rowCopies - copies)
    }

    @Test
    fun workPerAppendDoesNotGrowWithLength() {
        val para = "word ".repeat(19) + "end.\n\n"
        for (append in listOf("y", "\nmore", "\n\nnext", "\n- item")) {
            val small = workFor(para.repeat(100) + "tail", para.repeat(100) + "tail" + append)
            val large = workFor(para.repeat(5000) + "tail", para.repeat(5000) + "tail" + append)
            assertEquals("appending ${append.replace("\n", "\\n")}", small, large)
        }
        // A long open fence streamed line by line: scanning a line costs the same however long the fence already is.
        val line = "val x = compute(1, 2, 3)\n"
        val small = workFor("```kotlin\n" + line.repeat(100), "```kotlin\n" + line.repeat(101))
        val large = workFor("```kotlin\n" + line.repeat(20_000), "```kotlin\n" + line.repeat(20_001))
        assertEquals(small, large) // scanning and chunking, and row references copied
        assertTrue("scanned ${large.first} for one line", large.first <= 4L * line.length)
        assertTrue("copied ${large.second} rows for one line", large.second <= 2)
        // The same for a long open literal paragraph streamed line by line.
        val prose = "a line of prose that goes on\n"
        assertEquals(workFor(prose.repeat(400), prose.repeat(401)), workFor(prose.repeat(20_000), prose.repeat(20_001)))
    }

    @Test
    fun workPerAppendDoesNotGrowWithCrHistoryOrALongIndent() {
        // After CRLF text, one more character costs the same however long the reply already is.
        val crlf = "a short paragraph\r\n\r\n"
        for (append in listOf("x", "\r", "\n", "\r\nnext")) {
            assertEquals("appending ${append.replace("\r", "\\r").replace("\n", "\\n")}", workFor(crlf.repeat(100) + "tail", crlf.repeat(100) + "tail" + append), workFor(crlf.repeat(2000) + "tail", crlf.repeat(2000) + "tail" + append))
        }
        // A growing line of spaces, or text after a long indent, is not read again from its start on each token. (In a
        // fence the lengths stay under CHUNK: a longer code line is cut again from its piece start, at most 20,000.)
        for (prefix in listOf("para\n\n", "")) {
            assertEquals(prefix, workFor(prefix + " ".repeat(100), prefix + " ".repeat(101)), workFor(prefix + " ".repeat(5000), prefix + " ".repeat(5001)))
        }
        val code = "```\n"
        assertEquals(workFor(code + " ".repeat(100), code + " ".repeat(101)), workFor(code + " ".repeat(1400), code + " ".repeat(1401)))
        assertEquals(workFor(code + " ".repeat(100) + "x", code + " ".repeat(100) + "xy"), workFor(code + " ".repeat(1400) + "x", code + " ".repeat(1400) + "xy"))
    }

    @Test
    fun codeCutsConsumeWholeLineTerminators() {
        val code = { text: String -> ReplyText().update("r1", text).filter { it.kind == BlockKind.CODE }.map { (it.chunk as DisplayChunk.Code).text } }
        assertEquals(listOf("y".repeat(20_000), "short"), code("```\n" + "y".repeat(20_000) + "\nshort\n```\n"))
        assertEquals(listOf("x"), code("```\r\nx\r\n```\r\n"))
        assertEquals(listOf("y".repeat(20_000), "short"), code("```\r\n" + "y".repeat(20_000) + "\r\nshort\r\n```\r\n"))
        val crlf = "```\r\n" + "line of code\r\n".repeat(300) + "```\r\n"
        assertTrue(code(crlf).size > 1 && code(crlf).none { it.endsWith('\r') })
    }
}
