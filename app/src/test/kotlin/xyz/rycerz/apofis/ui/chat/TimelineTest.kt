package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
        // Through MessageList: its step handler is a new lambda per update, which must not reach the rows.
        rule.mainClock.autoAdvance = false
        val steps = tools(300)
        var items by mutableStateOf<List<ChatItem>>(Joined(emptyList(), listOf(AssistantReply("r1", steps, "", 0.0, live = true))))
        rule.setContent { Themed { Messages(items) } }
        frame()
        rule.onNodeWithText("Show 298 more steps").performClick()
        repeat(20) { frame() }
        val composed = Work.timelineRows
        assertTrue(composed >= 300)
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
        )
        var reply by mutableStateOf(AssistantReply("r1", emptyList(), "", 0.0))
        rule.setContent { Themed { TimelineCard(reply, "step:r1", onStep = {}, initiallyExpanded = true, initiallyOpenReasoning = setOf(4, 5)) } }
        for (steps in listOf(emptyList(), odd.take(1), odd, odd.reversed(), tools(1000), odd.map { if (it is ToolStep) ReasoningStep(it.name) else tool(9) }, emptyList())) {
            reply = reply.copy(steps = steps)
            rule.waitForIdle()
            assertEquals(steps.size, nodes("step:r1:").size)
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
}
