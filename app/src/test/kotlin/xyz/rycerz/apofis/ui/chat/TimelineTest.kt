package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertFalse
import android.icu.text.BreakIterator
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import xyz.rycerz.apofis.chat.TOOL_RESULT_DISPLAY_LIMIT
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ReasoningStep
import xyz.rycerz.apofis.chat.Step
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class TimelineTest {
    @get:Rule
    val rule = createComposeRule()

    private fun tool(i: Int, name: String = "terminal", preview: String = "command=echo $i", running: Boolean = false) =
        ToolStep("c$i", name, preview, "ok", resultTruncated = false, error = false, running = running)

    private fun tools(n: Int): List<Step> = List(n) { tool(it) }

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

    private fun nodes(prefix: String) = rule.onAllNodes(
        SemanticsMatcher("tag starts with $prefix") { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun frame() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    // ---- visibleSteps ----

    @Test
    fun twoOrFewerShowAll() {
        for (n in 0..2) {
            val steps = tools(n)
            for (expanded in listOf(false, true)) {
                val v = visibleSteps(steps, expanded)
                assertEquals(0, v.hidden)
                assertEquals((0 until n).toList(), v.shown.map { it.index })
                assertEquals(steps, v.shown.map { it.value })
            }
        }
    }

    @Test
    fun collapsesToLastTwo() {
        val steps = tools(5)
        val collapsed = visibleSteps(steps, expanded = false)
        assertEquals(3, collapsed.hidden)
        assertEquals(listOf(3, 4), collapsed.shown.map { it.index })
        assertEquals(steps.subList(3, 5), collapsed.shown.map { it.value })
        val expanded = visibleSteps(steps, expanded = true)
        assertEquals(0, expanded.hidden)
        assertEquals((0..4).toList(), expanded.shown.map { it.index })
    }

    @Test
    fun thousandStepsCollapsedBuildTwo() {
        val all = tools(1000)
        var reads = 0
        val counted = object : AbstractList<Step>() {
            override val size get() = all.size
            override fun get(index: Int): Step = all[index].also { reads++ }
        }
        val v = visibleSteps(counted, expanded = false)
        assertEquals("hidden steps are never read", 2, reads)
        assertEquals(998, v.hidden)
        assertEquals(listOf(998, 999), v.shown.map { it.index })
    }

    // ---- the card ----

    @Test
    fun collapsedCardComposesOnlyShownStepsAndTokensComposeNone() {
        rule.mainClock.autoAdvance = false
        val steps = tools(1000)
        var reply by mutableStateOf(AssistantReply("r1", steps, "", 0.0, live = true))
        Work.timelineRows = 0
        rule.setContent { Themed { TimelineCard(reply, "step:r1", onStep = {}) } }
        frame()
        assertEquals(2, nodes("step:r1:").size)
        assertEquals(2L, Work.timelineRows)
        // Streamed text with the same steps list composes no step row again.
        repeat(50) {
            reply = reply.copy(text = reply.text + "x")
            frame()
        }
        assertEquals(2L, Work.timelineRows)
        // A new step does reach the card (so the frames above did recompose) and builds only the new pair.
        reply = reply.copy(steps = steps + tool(1000))
        frame()
        assertEquals(listOf("step:r1:999:terminal", "step:r1:1000:terminal"), nodes("step:r1:").map { it.config[SemanticsProperties.TestTag] })
        assertTrue(Work.timelineRows in 3L..4L)
    }

    @Test
    fun expandedCardInTheListDoesNotRecomposeRowsForTokens() {
        Work.timelineRows = 0
        // Through MessageList: its step handler is a new lambda per update, which must not reach the rows.
        rule.mainClock.autoAdvance = false
        val steps = tools(300)
        var items by mutableStateOf<List<ChatItem>>(Joined(emptyList(), listOf(AssistantReply("r1", steps, "", 0.0, live = true))))
        rule.setContent { Themed { Messages(items) } }
        frame()
        rule.onNodeWithText("Show 298 more steps").performClick()
        repeat(20) { frame() }
        val composed = Work.timelineRows
        assertTrue("an expanded long run shows one page", composed in 1L..STEP_PAGE + 2L) // plus the two shown while folded
        repeat(20) {
            items = Joined(emptyList(), listOf(AssistantReply("r1", steps, "x".repeat(it + 1), 0.0, live = true)))
            frame()
        }
        rule.onNodeWithText("x".repeat(20), useUnmergedTree = true).assertExists() // the tokens did arrive
        assertEquals(composed, Work.timelineRows)
    }

    @Composable
    private fun Messages(items: List<ChatItem>) = MessageList(
        items, null, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = {},
        listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY,
    )

    @Test
    fun expandRowTogglesAndExposesItsState() {
        val tapped = ArrayList<Int>()
        rule.setContent { Themed { TimelineCard(AssistantReply("r1", tools(5), "", 0.0), "step:r1", onStep = { tapped += it }) } }
        val more = rule.onNodeWithText("Show 3 more steps")
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasStateDescription("Collapsed"))
            .assertHeightIsAtLeast(48.dp)
        assertEquals(0, rule.onAllNodes(hasTestTag("step:r1:0:terminal"), useUnmergedTree = true).fetchSemanticsNodes().size)
        more.performClick()
        rule.onNodeWithText("Show fewer steps").assert(hasStateDescription("Expanded"))
        assertEquals(5, nodes("step:r1:").size)
        rule.onNodeWithTag("step:r1:0:terminal").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(0), tapped) // the original index, also once earlier steps were hidden
        rule.onNodeWithText("Show fewer steps").performClick()
        assertEquals(listOf("step:r1:3:terminal", "step:r1:4:terminal"), nodes("step:r1:").map { it.config[SemanticsProperties.TestTag] })
    }

    @Test
    fun reasoningOpensAndClosesWithState() {
        val reply = AssistantReply("r1", listOf(ReasoningStep("first line\nsecond line"), tool(1)), "", 0.0)
        val tapped = ArrayList<Int>()
        rule.setContent { Themed { TimelineCard(reply, "step:r1", onStep = { tapped += it }) } }
        val step = rule.onNodeWithTag("step:r1:0")
        step.assert(hasStateDescription("Collapsed")).assertHeightIsAtLeast(48.dp)
        assertEquals(0, rule.onAllNodes(hasText("second line", substring = true)).fetchSemanticsNodes().size)
        step.performClick()
        step.assert(hasStateDescription("Expanded"))
        rule.onNodeWithText("first line\nsecond line", useUnmergedTree = true).assertExists()
        step.performClick()
        step.assert(hasStateDescription("Collapsed"))
        assertTrue("a reasoning tap opens no sheet", tapped.isEmpty())
    }

    @Test
    fun hostileStepsNeverThrow() {
        val huge = "x".repeat(1_000_000)
        val lines = (1..2000).joinToString("\n") { "line $it" }
        val odd = listOf(
            ToolStep("", "", "", null, false, error = false, running = false),
            ToolStep("a", huge, huge, huge, true, error = true, running = true, durationSeconds = Double.NaN),
            ToolStep("b", lines, lines, lines, false, error = true, running = false, durationSeconds = -1.0),
            ToolStep("c", "t", "p", null, false, error = false, running = false, durationSeconds = Double.POSITIVE_INFINITY),
            ReasoningStep(""),
            ReasoningStep(lines),
            ReasoningStep(huge),
        )
        var reply by mutableStateOf(AssistantReply("r1", emptyList(), "", 0.0))
        rule.setContent { Themed { TimelineCard(reply, "step:r1", onStep = {}, initiallyExpanded = true, initiallyOpenReasoning = setOf(4, 5, 6)) } }
        for (steps in listOf(emptyList(), odd.take(1), odd, odd.reversed(), tools(1000), odd.map { if (it is ToolStep) ReasoningStep(it.name) else tool(9) }, emptyList())) {
            reply = reply.copy(steps = steps)
            rule.waitForIdle()
            assertEquals(minOf(steps.size, STEP_PAGE), nodes("step:r1:").size)
            assertTrue("no text laid out whole", longestText() <= TEXT_PAGE_CHARS)
        }
    }

    // ---- streamed equals stored ----

    @Test
    fun expandedStateSurvivesTheSwapToStoredRows() {
        val live = AssistantReply("r2", tools(5), "partial", 0.0, live = true)
        val stored = AssistantReply("r2", tools(5), "partial and done", 0.0)
        var items by mutableStateOf<List<ChatItem>>(Joined(emptyList(), listOf(live)))
        rule.setContent { Themed { Messages(items) } }
        rule.onNodeWithText("Show 3 more steps").performClick()
        assertEquals(5, nodes("step:r2:").size)
        items = listOf(stored)
        rule.waitForIdle()
        assertEquals("the stored reply keeps the card expanded", 5, nodes("step:r2:").size)
        rule.onNodeWithText("Show fewer steps").assertExists()
    }

    /** The card in a scrolling page, as in the list, so rows below the screen can be scrolled to. */
    @Composable
    private fun Scrolling(content: @Composable () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState())) { content() }

    private fun longestText() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().maxOfOrNull { n -> n.config[SemanticsProperties.Text].maxOf { it.length } } ?: 0

    private fun counting(all: List<Step>, reads: IntArray) = object : AbstractList<Step>() {
        override val size get() = all.size
        override fun get(index: Int): Step = all[index].also { reads[0]++ }
    }

    // ---- fix round 1: bounded expansion, bounded reasoning, state kept, subject kept ----

    @Test
    fun expandedLongRunIsPagedAndEveryStepIsReachable() {
        rule.mainClock.autoAdvance = false
        val reads = IntArray(1)
        val all = tools(300)
        // Referential policy: the test's own state must not compare the lists element by element.
        var reply by mutableStateOf(AssistantReply("r1", counting(all, reads), "", 0.0, live = true), referentialEqualityPolicy())
        rule.setContent { Themed { Scrolling { TimelineCard(reply, "step:r1", onStep = {}) } } }
        frame()
        rule.onNodeWithText("Show 298 more steps").performClick()
        frame()
        Work.timelineRows = 0
        reads[0] = 0
        frame()
        val seen = HashSet<String>()
        fun page() = nodes("step:r1:").map { it.config[SemanticsProperties.TestTag] }.also { seen += it }
        assertEquals("the newest page: the short, growing one", (280..299).map { "step:r1:$it:terminal" }, page())

        // A new step and the completion of the last one each visit one page, not the run.
        reads[0] = 0
        Work.timelineRows = 0
        reply = reply.copy(steps = counting(all + tool(300), reads))
        frame()
        assertTrue("visited ${reads[0]}", reads[0] <= STEP_PAGE + 2)
        assertTrue("composed ${Work.timelineRows}", Work.timelineRows <= 3)
        reads[0] = 0
        Work.timelineRows = 0
        reply = reply.copy(steps = counting(all + tool(300).copy(result = "done"), reads))
        frame()
        assertTrue("visited ${reads[0]}", reads[0] <= STEP_PAGE + 2)
        assertTrue("composed ${Work.timelineRows}", Work.timelineRows <= 3)

        // Pages are fixed chunks of 40 counted from the oldest step; the newest (300..300) is short. Earlier walks
        // back through them, Later walks forward again, never overlapping.
        rule.mainClock.autoAdvance = true // scrolling to the rows below needs frames
        fun range(r: IntRange) = r.map { "step:r1:$it:terminal" }
        assertEquals(range(280..300), page())
        val pages = mutableListOf(page())
        while (rule.onAllNodes(hasText("Earlier steps", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Earlier steps", substring = true).performScrollTo().performClick()
            pages += page()
            assertTrue(pages.size < 20)
        }
        assertEquals((7 downTo 0).map { range(it * 40 until minOf(it * 40 + 40, 301)) }, pages)
        val forward = mutableListOf(page())
        while (rule.onAllNodes(hasText("Later steps", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Later steps", substring = true).performScrollTo().performClick()
            forward += page()
            assertTrue(forward.size < 20)
        }
        assertEquals(pages.reversed(), forward)
    }

    @Test
    fun growthNeverShiftsAPageAndAShrinkNeverNeedsTwoTaps() {
        val all = tools(200)
        var reply by mutableStateOf(AssistantReply("r1", all, "", 0.0, live = true), referentialEqualityPolicy())
        rule.setContent { Themed { Scrolling { TimelineCard(reply, "step:r1", onStep = {}) } } }
        rule.onNodeWithText("Show 198 more steps").performClick()
        fun page() = nodes("step:r1:").map { it.config[SemanticsProperties.TestTag] }
        fun range(r: IntRange) = r.map { "step:r1:$it:terminal" }
        fun earlier() = rule.onNodeWithText("Earlier steps", substring = true).performScrollTo().performClick()
        fun later() = rule.onNodeWithText("Later steps", substring = true).performScrollTo().performClick()
        assertEquals("200 steps: the newest full page", range(160 until 200), page())
        val more = tools(5).map { (it as ToolStep).copy(callId = "n${it.callId}") }
        reply = reply.copy(steps = all + more) // following the newest page: the new short page shows
        rule.waitForIdle()
        assertEquals(range(200 until 205), page())
        earlier()
        assertEquals(range(160 until 200), page())
        reply = reply.copy(steps = all + more + more.map { it.copy(callId = "m${it.callId}") })
        rule.waitForIdle()
        assertEquals("a page the reader chose stays put as steps arrive", range(160 until 200), page())
        later()
        assertEquals("Later shows only the steps after it", range(200 until 210), page())
        repeat(4) { earlier() }
        assertEquals(range(40 until 80), page())
        // The stored reply has 41 steps: the page index is clamped on read, one Later tap moves.
        reply = AssistantReply("r1", tools(41), "done", 0.0)
        rule.waitForIdle()
        assertEquals("page 1 of 41 steps", range(40 until 41), page())
        earlier()
        assertEquals(range(0 until 40), page())
        later()
        assertEquals(range(40 until 41), page())
    }

    @Test
    fun longReasoningOpensOnePageAtATime() {
        val text = (1..400).joinToString("\n") { "thought $it " + "x".repeat(it % 90) }
        rule.setContent { Themed { Scrolling { TimelineCard(AssistantReply("r1", listOf(ReasoningStep(text)), "", 0.0), "step:r1", onStep = {}, initiallyOpenReasoning = setOf(0)) } } }
        val parts = StringBuilder()
        var guard = 0
        while (true) {
            val body = rule.onNodeWithTag("reasoning:0.page", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
            assertTrue(body.length <= TEXT_PAGE_CHARS)
            parts.append(body)
            val next = rule.onNodeWithContentDescription("Next part")
            if (SemanticsProperties.Disabled in next.fetchSemanticsNode().config) break
            next.performScrollTo().performClick()
            assertTrue(++guard < 1000)
        }
        assertEquals("the pages together are the whole trace", text, parts.toString())
        rule.onNodeWithContentDescription("Previous part").performScrollTo().performClick()
        rule.onNodeWithText("Part $guard", substring = true).assertExists()
        // What TalkBack navigates: the merged step node carries the whole current part; the paging buttons are
        // labelled actions of their own.
        val merged = rule.onNodeWithTag("step:r1:0").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
        val part = rule.onNodeWithTag("reasoning:0.page", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
        assertTrue(merged.contains(part))
        rule.onNodeWithContentDescription("Previous part").assert(hasClickAction())
        rule.onNodeWithContentDescription("Next part").assert(hasClickAction())
    }

    private fun pagesOf(text: CharSequence): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (true) {
            val end = pageEnd(text, start)
            out += text.substring(start, end)
            if (end >= text.length) return out
            assertTrue("no progress at $start", end > start)
            start = end
            assertTrue(out.size < 100_000)
        }
    }

    private fun boundaries(text: String): Set<Int> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = HashSet<Int>()
        var b = it.first()
        while (b != BreakIterator.DONE) { out += b; b = it.next() }
        return out
    }

    private fun breaks(page: String): Int {
        var n = 0
        var i = 0
        while (i < page.length) {
            if (page[i] == '\r') { n++; if (i + 1 < page.length && page[i + 1] == '\n') i++ } else if (page[i] == '\n') n++
            i++
        }
        return n
    }

    @Test
    fun pagesCutOnlyBetweenGraphemesAndCountEveryKindOfLineBreak() {
        val pad = { n: Int -> "a".repeat(n) }
        val inputs = listOf(
            "", "a", "\n".repeat(5000), "q\r".repeat(500), "q\r\n".repeat(500), "x".repeat(100_000), "😀".repeat(3000),
            pad(1499) + "e\u0301z" + pad(10), pad(1498) + "👩\u200D💻" + pad(10), pad(1499) + "🇵🇱🇩🇪🇺🇦" + pad(10),
            pad(1498) + "🇵🇱🇩🇪🇺🇦" + pad(10), pad(1499) + "\r\n" + pad(10), (1..23).joinToString("") { "l\n" } + "l\r\nz",
            "a\n" + "😀".repeat(1500), "🇵🇱".repeat(2000), pad(1435) + "🇵🇱".repeat(30) + "z",
        )
        for (text in inputs) {
            val pages = pagesOf(text)
            assertEquals(text, pages.joinToString(""))
            val cuts = pages.runningFold(0) { at, page -> at + page.length }
            val ok = boundaries(text)
            for (cut in cuts) assertTrue("cut at $cut inside a grapheme of ${text.take(40)}", cut in ok)
            for (page in pages) {
                assertTrue(page.length <= TEXT_PAGE_CHARS)
                assertTrue("${breaks(page)} breaks", breaks(page) <= TEXT_PAGE_LINES)
            }
        }
        assertEquals("the reviewer's flag run: the last boundary at or before 1500", 1499, pageEnd(pad(1435) + "🇵🇱".repeat(30) + "z", 0))
    }

    /** The last whole-text grapheme boundary at or before [cap], by ICU over the whole text. */
    private fun lastBoundary(text: String, cap: Int): Int {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        return if (cap >= text.length) text.length else it.preceding(cap + 1)
    }

    @Test
    fun theCutIsTheLastGraphemeBoundaryAtOrBeforeTheCap() {
        val pieces = listOf("a", "Z", " ", "\n", "\r", "\r\n", "e\u0301", "\u0301", "😀", "👍🏽", "👩\u200D💻", "👨\u200D👩\u200D👧", "🇵🇱", "🇩🇪", "\u200D", "가", "\u1100\u1161\u11A8", "\uFE0F")
        val random = java.util.Random(4)
        val generated = List(60) { (0 until 5 + random.nextInt(60)).joinToString("") { pieces[random.nextInt(pieces.size)] } }
        val reviewer = listOf("a".repeat(1435) + "🇵🇱".repeat(30) + "z", "a".repeat(19930) + "e" + "\u0301".repeat(100) + "!")
        for (text in generated + reviewer) {
            val caps = if (text.length > 2000) (1..text.length step 7) + (text.length - 120 until text.length).filter { it > 0 } + 1499 + 1500 + 20000 else 1..text.length
            for (cap in caps) {
                val cut = cutAt(text, 0, cap)
                val expected = lastBoundary(text, cap)
                assertTrue("cap $cap exceeded: $cut", cut <= cap)
                if (expected > 0) assertEquals("cap $cap in ${text.take(30)}", expected, cut)
                else assertFalse(cut in 1 until text.length && Character.isLowSurrogate(text[cut]))
            }
        }
        assertEquals("a strict prefix: the crossing cluster is left out", 19930, displayPrefix("a".repeat(19930) + "e" + "\u0301".repeat(100) + "!", 20000).length)
    }

    @Test
    fun anOversizedGraphemeIsCutOnlyAtACodePointAndPagingEnds() {
        for (text in listOf("e" + "\u0301".repeat(5000) + "z", "😀" + "\uFE0F".repeat(3000))) {
            val pages = pagesOf(text)
            assertEquals(text, pages.joinToString(""))
            for (page in pages) {
                assertTrue(page.length <= TEXT_PAGE_CHARS)
                assertFalse(Character.isLowSurrogate(page.first()))
                assertFalse(Character.isHighSurrogate(page.last()))
            }
        }
    }

    @Test
    fun theLivePreviewCapNeverEndsInsideAGrapheme() {
        val pad = "a".repeat(TOOL_RESULT_DISPLAY_LIMIT - 1)
        for (tail in listOf("😀", "e\u0301", "👩\u200D💻", "🇵🇱", "\r\n")) {
            val text = pad + tail + "after"
            val shown = displayPrefix(text, TOOL_RESULT_DISPLAY_LIMIT)
            assertTrue(text.startsWith(shown))
            assertTrue(shown.length <= TOOL_RESULT_DISPLAY_LIMIT)
            assertTrue("cut inside ${tail.length}-unit grapheme", shown.length in boundaries(text))
        }
        assertEquals("short", displayPrefix("short", TOOL_RESULT_DISPLAY_LIMIT))
    }

    // ---- fix round 3: one saved page index; boundaries walked from 0 and cached for the text ----

    private fun pageText(tag: String) = rule.onNodeWithTag("$tag.page", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    @Composable
    private fun Pager(text: String) = Scrolling { PagedText(text, "p", androidx.compose.ui.graphics.Color.Black) { part, m -> androidx.compose.material3.Text(part, m) } }

    @Test
    fun showingPageKMeasuresAtMostKPlusOnePagesOnce() {
        val text = "word ".repeat(200_000) // 1,000,000 characters
        var shown by mutableStateOf(text)
        Work.pageEnds = 0
        rule.setContent { Themed { Pager(shown) } }
        assertTrue("opening measured ${Work.pageEnds} pages", Work.pageEnds <= 1)
        repeat(2) { rule.onNodeWithContentDescription("Next part").performScrollTo().performClick() }
        assertTrue("page 3 measured ${Work.pageEnds} pages", Work.pageEnds <= 3)
        rule.onNodeWithContentDescription("Previous part").performScrollTo().performClick()
        shown = text // the same text again
        rule.waitForIdle()
        assertTrue("going back measured again: ${Work.pageEnds}", Work.pageEnds <= 3)
        shown = text + "more" // appended: the measured pages are kept
        rule.waitForIdle()
        assertTrue("an append measured again: ${Work.pageEnds}", Work.pageEnds <= 4)
    }

    @Test
    fun repeatedPreviousOrNextBeforeAFrameMovesOnePageAndNeverCrashes() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Themed { Pager("x".repeat(6_000)) } }
        rule.mainClock.advanceTimeByFrame()
        val next = rule.onNodeWithContentDescription("Next part")
        next.performSemanticsAction(SemanticsActions.OnClick)
        next.performSemanticsAction(SemanticsActions.OnClick) // before the frame that disables or moves it
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Part 2", substring = true).assertExists()
        val previous = rule.onNodeWithContentDescription("Previous part")
        previous.performSemanticsAction(SemanticsActions.OnClick)
        previous.performSemanticsAction(SemanticsActions.OnClick)
        previous.performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(100)
        assertEquals("x".repeat(TEXT_PAGE_CHARS), pageText("p"))
    }

    @Test
    fun aReplacedTextOfTheSameLengthIsPagedAfresh() {
        var text by mutableStateOf("a".repeat(3_000))
        rule.setContent { Themed { Pager(text) } }
        rule.onNodeWithContentDescription("Next part").performScrollTo().performClick()
        text = "a".repeat(1499) + "😀" + "x".repeat(1499) // same length, different text
        rule.waitForIdle()
        val second = pageText("p")
        assertFalse("page 2 starts inside a character", Character.isLowSurrogate(second.first()))
        rule.onNodeWithContentDescription("Previous part").performScrollTo().performClick()
        assertTrue("the first two pages are the start of the text", text.startsWith(pageText("p") + second))
    }

    @Test
    fun aRestoredPagerKeepsItsPageAndRemeasuresTheText() {
        val tester = StateRestorationTester(rule)
        var text by mutableStateOf("a".repeat(3_000))
        tester.setContent { Themed { Pager(text) } }
        rule.onNodeWithContentDescription("Next part").performScrollTo().performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Part 2", substring = true).assertExists()
        assertEquals("a".repeat(1500), pageText("p"))
        text = "a".repeat(1499) + "😀" + "x".repeat(1499)
        tester.emulateSavedInstanceStateRestore() // restored with a different text: measured again from 0
        assertFalse(Character.isLowSurrogate(pageText("p").first()))
        text = "short"
        rule.waitForIdle()
        assertEquals("the index is clamped to the pages there are", "short", pageText("p"))
    }

    @Test
    fun foldingTheCardKeepsAReasoningStepOpen() {
        val reply = AssistantReply("r1", listOf(ReasoningStep("why"), tool(1), tool(2), tool(3), tool(4)), "", 0.0)
        rule.setContent { Themed { TimelineCard(reply, "step:r1", onStep = {}) } }
        rule.onNodeWithText("Show 3 more steps").performClick()
        rule.onNodeWithTag("step:r1:0").performClick()
        rule.onNodeWithTag("step:r1:0").assert(hasStateDescription("Expanded"))
        rule.onNodeWithText("Show fewer steps").performClick()
        rule.onNodeWithText("Show 3 more steps").performClick()
        rule.onNodeWithTag("step:r1:0").assert(hasStateDescription("Expanded"))
    }

    @Test
    fun aPressedToolDoesNotOpenAnotherCallAfterTheSwap() {
        val opened = ArrayList<StepSubject>()
        val live = AssistantReply("r2", listOf(tool(1)), "", 0.0, live = true)
        var items by mutableStateOf<List<ChatItem>>(Joined(emptyList(), listOf(live)))
        rule.setContent {
            Themed {
                MessageList(
                    items, null, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = { opened += it },
                    listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY,
                )
            }
        }
        rule.onNodeWithTag("step:r2:0:terminal").performTouchInput { down(center) }
        items = listOf(AssistantReply("r2", listOf(tool(7), tool(1)), "done", 0.0)) // another call now sits at index 0
        rule.waitForIdle()
        rule.onNodeWithTag("step:r2:0:terminal").performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("opened $opened", opened.isEmpty())
        rule.onNodeWithTag("step:r2:0:terminal").performClick()
        assertEquals(listOf(StepSubject("r2", 0, "c7", "terminal")), opened)
    }

    @Test
    fun aCallIdNeverCollidesWithTheKeyOfAStepWithoutOne() {
        val opened = ArrayList<StepSubject>()
        val noId = ToolStep("", "terminal", "command=ls", null, false, error = false, running = true)
        var items by mutableStateOf<List<ChatItem>>(Joined(emptyList(), listOf(AssistantReply("r2", listOf(noId), "", 0.0, live = true))))
        rule.setContent { Themed { MessageList(items, null, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = { opened += it }, listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.onNodeWithTag("step:r2:0:terminal").performTouchInput { down(center) }
        items = listOf(AssistantReply("r2", listOf(noId.copy(callId = "#terminal", running = false)), "done", 0.0)) // a real id spelled like the fallback
        rule.waitForIdle()
        rule.onNodeWithTag("step:r2:0:terminal").performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("opened $opened", opened.isEmpty())
    }

    // ---- fix round 2: the list's own delivery path reads no step on a step update ----

    @Test
    fun aStepUpdateThroughTheFoldedListReadsNoHiddenStep() = stepUpdatesThroughTheList(expanded = false)

    @Test
    fun aStepUpdateThroughTheExpandedListReadsOnlyItsPage() = stepUpdatesThroughTheList(expanded = true)

    private fun stepUpdatesThroughTheList(expanded: Boolean) {
        val reads = IntArray(1)
        val all = tools(300)
        // Referential policy in the test's own holder, so only MessageList's delivery path is measured.
        var items by mutableStateOf<List<ChatItem>>(Joined(emptyList(), listOf(AssistantReply("r1", counting(all, reads), "", 0.0, live = true))), referentialEqualityPolicy())
        rule.setContent { Themed { Messages(items) } }
        rule.waitForIdle()
        if (expanded) rule.onNodeWithText("Show 298 more steps").performClick()
        rule.waitForIdle()
        for ((label, steps) in listOf(
            "same steps, more text" to null,
            "last step completes" to all.dropLast(1) + (all.last() as ToolStep).copy(result = "done"),
            "a step starts" to all + tool(300),
        )) {
            reads[0] = 0
            val old = items.last() as AssistantReply
            items = Joined(emptyList(), listOf(old.copy(steps = steps?.let { counting(it, reads) } ?: old.steps, text = old.text + "x")))
            rule.waitForIdle()
            val bound = if (expanded) STEP_PAGE + 2 else 4
            assertTrue("expanded=$expanded, $label: read ${reads[0]} steps", reads[0] <= bound)
        }
    }
}
