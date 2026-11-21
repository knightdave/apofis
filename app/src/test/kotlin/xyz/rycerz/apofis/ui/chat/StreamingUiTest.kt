package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.PulseProbe
import xyz.rycerz.apofis.ui.theme.PulsingDot

/** The smoothed live tail, the run-end announcement and the infinite indicators, on a manual clock in whole frames. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class StreamingUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val letters = "abcdefghij"
    private val finished = "Reply finished"

    @Before
    fun manualClock() {
        rule.mainClock.autoAdvance = false
        PulseProbe.frames = 0
    }

    private fun frame(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications() // state written by the test reaches the composition in this frame
        rule.mainClock.advanceTimeByFrame()
    }

    @Composable
    private fun Themed(reduceMotion: Boolean = false, content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = reduceMotion, blur = false, content = content)

    @Composable
    private fun Messages(items: List<ChatItem>, live: LiveReply?, state: LazyListState = rememberLazyListState(), onRevealGrew: () -> Unit = {}) = MessageList(
        items, live, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = {},
        listState = state, contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY, onRevealGrew = onRevealGrew,
    )

    private fun run(id: String, text: String, connection: Connection = Connection.LIVE) = LiveReply(id, "running", text, emptyList(), null, connection)

    private fun liveItems(text: String, head: List<ChatItem> = emptyList(), key: String = "live"): List<ChatItem> =
        Joined(head, listOf(AssistantReply(key, emptyList(), text, FIXED_NOW, live = true)))

    private fun SemanticsNode.allText(): String =
        (config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString("") { it.text } ?: "") + children.joinToString("") { it.allText() }

    /** The text the rows of reply [key] show, top to bottom (one paragraph of letters: chunks join with nothing). */
    private fun shown(key: String): String = rule.onAllNodes(
        SemanticsMatcher("rows of $key") { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("reply:$key/") == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.joinToString("") { it.allText() }

    private fun liveRegions() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true).fetchSemanticsNodes()

    // ---- the smoother on the frame clock ----

    @Test
    fun fastDeltasDoNotStarve() {
        var target by mutableStateOf("")
        val seen = ArrayList<String>()
        rule.setContent { Themed { rememberSmoothedText("r1", target, snap = false).let { SideEffect { seen += it } } } }
        frame()
        val lengths = ArrayList<Int>()
        repeat(3000 / 16) { // 3 s of frames, 20 characters each
            target += letters + letters
            frame()
            val s = seen.last()
            assertTrue("a prefix of the target", target.startsWith(s))
            assertTrue("lag ${target.length - s.length}", target.length - s.length <= SMOOTH_LAG_BUDGET)
            lengths += s.length
        }
        // Every tick (50 ms, so within any 4 frames of 16 ms) shows more.
        for (i in 4 until lengths.size) assertTrue("grew by frame $i", lengths[i] > lengths[i - 4])
        assertTrue("still smoothing, not snapping: lag ${target.length - lengths.last()}", target.length - lengths.last() in 1..600)
    }

    @Test
    fun aBurstCatchesUpWithinOneTick() {
        var target by mutableStateOf("abc")
        var shownNow = ""
        rule.setContent { Themed { shownNow = rememberSmoothedText("r1", target, snap = false) } }
        frame()
        target = "abc" + letters.repeat(10_000) // 100,003 characters
        frame(4) // within one tick (50 ms) of the change: over the budget, so all of it
        assertEquals(target, shownNow)
    }

    @Test
    fun aCorrectionShowsInTheSameFrame() {
        var target by mutableStateOf("abc")
        var shownNow = ""
        rule.setContent { Themed { shownNow = rememberSmoothedText("r1", target, snap = false) } }
        frame()
        target = "abc" + letters.repeat(60)
        frame(5)
        assertTrue(shownNow.length in 4 until target.length)
        target = "abX" + letters.repeat(60)
        frame()
        assertEquals(target, shownNow)
    }

    // ---- the live tail in the list ----

    @Test
    fun sameRowKeyNewRunRestarts() {
        var items by mutableStateOf(liveItems("Hello"))
        var live by mutableStateOf<LiveReply?>(run("A", "Hello"))
        rule.setContent { Themed { Messages(items, live) } }
        frame()
        assertEquals("Hello", shown("live"))
        // Run B's live row has the same key, and its existing text even extends A's: it still appears at once.
        val b = "Hello" + letters.repeat(50)
        items = liveItems(b)
        live = run("B", b)
        frame()
        assertEquals(b, shown("live"))
    }

    @Test
    fun streamedTextIsRevealedInSlicesAndReportsGrowth() {
        var items by mutableStateOf(liveItems("Go"))
        var live by mutableStateOf<LiveReply?>(run("A", "Go"))
        var grew = 0
        rule.setContent { Themed { Messages(items, live, onRevealGrew = { grew++ }) } }
        frame()
        val long = "Go" + letters.repeat(80)
        items = liveItems(long)
        live = run("A", long)
        frame()
        assertEquals("Go", shown("live"))
        assertEquals(0, grew)
        frame(4)
        val first = shown("live")
        assertTrue("one slice: ${first.length}", first.length in 3 until long.length)
        assertTrue(long.startsWith(first))
        frame(80)
        assertEquals(long, shown("live"))
        assertTrue("each revealing tick reports growth: $grew", grew >= 3)
    }

    @Test
    fun pollingSnaps() = snapsWhen(reduceMotion = false, Connection.POLLING)

    @Test
    fun reduceMotionSnaps() = snapsWhen(reduceMotion = true, Connection.LIVE)

    private fun snapsWhen(reduceMotion: Boolean, connection: Connection) {
        var items by mutableStateOf(liveItems("Hi"))
        var live by mutableStateOf<LiveReply?>(run("A", "Hi", connection))
        rule.setContent { Themed(reduceMotion) { Messages(items, live) } }
        frame()
        val long = "Hi" + letters.repeat(70)
        items = liveItems(long)
        live = run("A", long, connection)
        frame()
        assertEquals(long, shown("live"))
    }

    @Test
    fun handoffShowsStoredText() {
        var items by mutableStateOf(liveItems("Start"))
        var live by mutableStateOf<LiveReply?>(run("A", "Start"))
        rule.setContent { Themed { Messages(items, live) } }
        frame()
        val long = "Start" + letters.repeat(80)
        items = liveItems(long)
        live = run("A", long)
        frame(5)
        assertTrue("mid reveal", shown("live").length < long.length)
        // The stored transcript replaces the live reply with other text: exactly it, on the next frame, and it stays.
        val stored = "Final" + "zyxwv".repeat(40)
        items = listOf(AssistantReply("live", emptyList(), stored, FIXED_NOW))
        live = null
        frame()
        assertEquals(stored, shown("live"))
        frame(30)
        assertEquals(stored, shown("live"))
    }

    @Test
    fun aStoppedRunShowsItsWholeStoredTextAtOnce() {
        var items by mutableStateOf(liveItems("Start"))
        var live by mutableStateOf<LiveReply?>(run("A", "Start"))
        rule.setContent { Themed { Messages(items, live) } }
        frame()
        val long = "Start" + letters.repeat(80)
        items = liveItems(long)
        live = run("A", long)
        frame(5)
        assertTrue(shown("live").length < long.length)
        items = listOf(AssistantReply("live", emptyList(), long, FIXED_NOW))
        live = null
        frame()
        assertEquals(long, shown("live"))
    }

    @Test
    fun recreatedScreenNeverReplays() {
        // Rotation and process recreation compose the list anew (nothing of the reveal is saved): a new key does the same.
        var generation by mutableStateOf(0)
        var items by mutableStateOf(liveItems("Start"))
        var live by mutableStateOf<LiveReply?>(run("A", "Start"))
        rule.setContent { Themed { key(generation) { Messages(items, live) } } }
        frame()
        val long = "Start" + letters.repeat(80)
        items = liveItems(long)
        live = run("A", long)
        frame(5)
        assertTrue(shown("live").length < long.length)
        generation++
        frame()
        assertEquals(long, shown("live"))
        assertEquals("", said())
    }

    @Test
    fun storedHeadNotRebuilt() {
        val head: List<ChatItem> = List(40) { i ->
            if (i % 2 == 0) UserMessage("u$i", "question $i", false, FIXED_NOW) else AssistantReply("r$i", emptyList(), "answer $i\n\nmore $i", FIXED_NOW)
        }
        // ChatRows itself: the stored head maps once, however the live text grows.
        val rowsOf = ChatRows(ZoneOffset.UTC)
        val target = (0 until 150).joinToString("") { "p$it\n\n" } // 790 characters: under the budget, so smoothed
        rowsOf.rows(liveItems("", head), "")
        val mapped = rowsOf.mapped
        for (end in 1..target.length step 37) rowsOf.rows(liveItems(target, head), target.substring(0, end))
        assertEquals(mapped, rowsOf.mapped)

        // Through the list: the whole reveal builds each revealed row about once, never the history or the reply again.
        var items by mutableStateOf(liveItems("", head))
        var live by mutableStateOf<LiveReply?>(run("A", ""))
        rule.setContent { Themed { Messages(items, live) } }
        frame()
        Work.rowsBuilt = 0
        items = liveItems(target, head)
        live = run("A", target)
        frame(200)
        assertTrue(Work.rowsBuilt >= 150)
        assertTrue("rows built ${Work.rowsBuilt} for 150 paragraphs", Work.rowsBuilt <= 150 + 2 * 30)
    }

    // ---- the run-end announcement ----

    /** The one persistent announcer: a polite live region with a state description, outside every row. */
    private fun announcer(): SemanticsNode = rule.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().single()

    private fun said() = announcer().config[SemanticsProperties.StateDescription]

    @Test
    fun announcesOnceWhenARunEnds() {
        val user = UserMessage("u1", "hi", false, FIXED_NOW)
        var items by mutableStateOf(liveItems("", listOf(user)))
        var live by mutableStateOf<LiveReply?>(run("A", ""))
        rule.setContent { Themed { Messages(items, live) } }
        frame()
        val node = announcer()
        assertTrue("present and visible", node.size.width > 0 && node.size.height > 0)
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        assertEquals("", said())
        var text = ""
        repeat(30) {
            text += letters
            items = liveItems(text, listOf(user))
            live = run("A", text)
            frame()
            assertEquals("streaming announces nothing", "", said())
            assertEquals("streamed text is not a live region: only the announcer is", 1, liveRegions().size)
        }
        items = listOf(user, AssistantReply("r1", emptyList(), text, FIXED_NOW))
        live = null
        frame(3)
        assertEquals(finished, said())
        assertEquals("the same node, which existed all along", node.id, announcer().id)
        rule.mainClock.advanceTimeBy(ANNOUNCE_MS - 5 * 16, ignoreFrameDuration = true)
        frame()
        assertEquals("held for ANNOUNCE_MS", finished, said())
        rule.mainClock.advanceTimeBy(6 * 16, ignoreFrameDuration = true)
        frame()
        assertEquals("", said())
        // Later updates of the same chat (a refresh, an edit) never repeat it.
        items = listOf(user, AssistantReply("r1", emptyList(), text + " edited", FIXED_NOW))
        frame(3)
        assertEquals("", said())
    }

    /** Counts the frames in which the announcer turned to "Reply finished", over [frames] frames. */
    private fun announcementsOver(frames: Int): Int {
        var count = 0
        var before = said()
        repeat(frames) {
            frame()
            val now = said()
            if (now == finished && before != finished) count++
            before = now
        }
        return count
    }

    @Test
    fun runAThenRunBDirectlyAnnouncesA() {
        var items by mutableStateOf(liveItems("a"))
        var live by mutableStateOf<LiveReply?>(run("A", "a"))
        rule.setContent { Themed { Messages(items, live) } }
        frame(2)
        items = liveItems("b")
        live = run("B", "b") // no frame without a run in between
        assertEquals(1, announcementsOver(5))
        // B starting inside an announcement window does not cut it short, and B ending announces again.
        live = null
        items = listOf(AssistantReply("r1", emptyList(), "b", FIXED_NOW))
        frame(3)
        live = run("C", "")
        items = liveItems("", items)
        frame(3)
        assertEquals(finished, said())
        // C ends after the window: announced again. (An end inside a running window only extends it: one line.)
        rule.mainClock.advanceTimeBy(ANNOUNCE_MS, ignoreFrameDuration = true)
        frame()
        assertEquals("", said())
        live = null
        items = listOf(AssistantReply("r1", emptyList(), "b", FIXED_NOW))
        assertEquals(1, announcementsOver(5))
    }

    @Test
    fun openingOrRecreatingAnnouncesNothing() {
        var generation by mutableStateOf(0)
        var live by mutableStateOf<LiveReply?>(run("B", "x"))
        rule.setContent { Themed { key(generation) { Messages(if (live == null) listOf(AssistantReply("r1", emptyList(), "done", FIXED_NOW)) else liveItems("x"), live) } } }
        assertEquals(0, announcementsOver(3))
        generation++ // recreated while B runs
        assertEquals(0, announcementsOver(3))
        live = null
        generation++ // recreated with B gone: nothing is remembered, so nothing ended
        assertEquals(0, announcementsOver(3))
    }

    @Test
    fun aPollingOnlyRunAndARunWithoutAReplyAnnounce() {
        val user = UserMessage("u1", "hi", false, FIXED_NOW, runId = "A")
        // As the merger polls: the stored transcript as it is plus an empty live item.
        var live by mutableStateOf<LiveReply?>(run("A", "", Connection.POLLING))
        var items by mutableStateOf(withLive(listOf(user), live) { FIXED_NOW })
        rule.setContent { Themed { Messages(items, live) } }
        frame(2)
        live = null
        items = listOf(user) // the run stored no reply
        assertEquals(1, announcementsOver(5))
    }

    @Test
    fun aStoredReplyArrivingAfterTheRunClearedAnnouncesOnce() {
        val older = AssistantReply("r0", emptyList(), "older", FIXED_NOW)
        val user = UserMessage("u1", "hi", false, FIXED_NOW, runId = "A")
        var items by mutableStateOf(liveItems("streamed", listOf(older, user)))
        var live by mutableStateOf<LiveReply?>(run("A", "streamed"))
        rule.setContent { Themed { Messages(items, live) } }
        frame(2)
        live = null
        items = listOf(older, user) // the run cleared before the final transcript arrived
        frame()
        items = listOf(older, user.copy(runId = null), AssistantReply("r1", emptyList(), "final", FIXED_NOW))
        assertEquals(1, announcementsOver(10))
    }

    @Test
    fun theNewestReplyOffScreenIsStillAnnounced() {
        val history: List<ChatItem> = List(40) { i ->
            if (i % 2 == 0) UserMessage("u$i", "question $i", false, FIXED_NOW) else AssistantReply("r$i", emptyList(), "answer $i\n\nmore $i", FIXED_NOW)
        }
        var items by mutableStateOf(liveItems("streamed", history))
        var live by mutableStateOf<LiveReply?>(run("A", "streamed"))
        var scope: CoroutineScope? = null
        lateinit var state: LazyListState
        rule.setContent {
            Themed {
                scope = rememberCoroutineScope()
                state = rememberLazyListState()
                Messages(items, live, state)
            }
        }
        frame(2)
        scope!!.launch { state.scrollToItem(60) } // far up the history
        frame(3)
        live = null
        items = history + AssistantReply("live", emptyList(), "streamed", FIXED_NOW)
        assertEquals(1, announcementsOver(5))
    }

    // ---- infinite indicators ----

    @Test
    fun indicatorsRedrawWithoutRecomposing() {
        fun changes() = Recomposer.runningRecomposers.value.sumOf { it.changeCount }
        rule.setContent {
            Themed {
                Column {
                    Dots(null)
                    MiniDots()
                    PulsingDot(Color.Red)
                }
            }
        }
        frame(2)
        val before = changes()
        val frames = PulseProbe.frames
        frame(60)
        assertTrue("the loops ran: ${PulseProbe.frames - frames}", PulseProbe.frames - frames >= 3 * 59)
        assertEquals("no composition changed", before, changes())
    }

    @Test
    fun reduceMotionRunsNoLoop() {
        rule.setContent {
            Themed(reduceMotion = true) {
                Column {
                    Dots(null)
                    MiniDots()
                    PulsingDot(Color.Red)
                    Messages(listOf(UserMessage("p1", "hi", false, FIXED_NOW, SendState.SENDING, idempotencyKey = "k")), null)
                }
            }
        }
        frame(30)
        assertEquals(0L, PulseProbe.frames)
    }

    @Test
    fun theSendingSpinnerTurns() {
        rule.setContent { Themed { Messages(listOf(UserMessage("p1", "hi", false, FIXED_NOW, SendState.SENDING, idempotencyKey = "k")), null) } }
        frame(2)
        val before = PulseProbe.frames
        frame(10)
        assertTrue(PulseProbe.frames - before in 8L..10L)
    }

    @Test
    fun indicatorsRunOnlyWhileShownActiveAndStarted() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        var scope: CoroutineScope? = null
        var state: LazyListState? = null
        var running by mutableStateOf(true)
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    scope = rememberCoroutineScope()
                    val list = rememberLazyListState().also { state = it }
                    LazyColumn(state = list) {
                        item { if (running) MiniDots() else Box(Modifier.height(3.dp)) }
                        items(60) { Box(Modifier.height(100.dp)) }
                    }
                }
            }
        }
        fun ticks(): Long {
            val before = PulseProbe.frames
            frame(10)
            return PulseProbe.frames - before
        }
        assertTrue("shown and started", ticks() in 8L..10L)
        owner.registry.currentState = Lifecycle.State.CREATED
        frame()
        assertEquals("in the background", 0L, ticks())
        owner.registry.currentState = Lifecycle.State.RESUMED
        frame()
        assertTrue("back", ticks() in 8L..10L)
        scope!!.launch { state!!.scrollToItem(30) }
        frame(2)
        assertNotEquals(0, state!!.firstVisibleItemIndex)
        assertEquals("scrolled off", 0L, ticks())
        scope!!.launch { state!!.scrollToItem(0) }
        frame(2)
        assertTrue("on screen again", ticks() in 8L..10L)
        running = false
        frame()
        assertEquals("the step finished", 0L, ticks())
    }

    @Test
    fun theReplyDotsStopOnceTextShows() {
        var items by mutableStateOf(liveItems(""))
        var live by mutableStateOf<LiveReply?>(run("A", ""))
        rule.setContent { Themed { Messages(items, live) } }
        frame(2)
        val before = PulseProbe.frames
        frame(10)
        assertTrue(PulseProbe.frames - before in 8L..10L)
        items = liveItems("Hello there")
        live = run("A", "Hello there")
        frame(40)
        assertEquals("Hello there", shown("live"))
        val after = PulseProbe.frames
        frame(10)
        assertEquals(0L, PulseProbe.frames - after)
    }

    @Test
    fun aRunningStepAnimatesUntilItCompletes() {
        fun reply(running: Boolean) = Joined(emptyList(), listOf(AssistantReply("live", listOf(ToolStep("c1", "terminal", "ls", null, false, false, running)), "", FIXED_NOW, live = true)))
        var items by mutableStateOf<List<ChatItem>>(reply(true))
        rule.setContent { Themed { Messages(items, run("A", "")) } }
        frame(2)
        val before = PulseProbe.frames
        frame(10)
        assertTrue("dots and mini dots: ${PulseProbe.frames - before}", PulseProbe.frames - before in 16L..20L)
        items = reply(false)
        frame(2)
        val after = PulseProbe.frames
        frame(10)
        assertTrue("only the reply dots remain", PulseProbe.frames - after in 8L..10L)
    }

    // ---- fix round 1 ----

    @Test
    fun theTickIsEvery50ms() {
        var target by mutableStateOf("abc")
        var shownNow = ""
        rule.setContent { Themed { shownNow = rememberSmoothedText("r1", target, snap = false) } }
        frame()
        target = "abc" + letters.repeat(60)
        frame()
        var grewOn = 0
        var before = shownNow.length
        repeat(12) {
            frame()
            if (shownNow.length > before) grewOn++
            before = shownNow.length
        }
        assertEquals("12 frames of 16 ms hold 3 ticks of 50 ms", 3, grewOn)
    }

    @Test
    fun everyInputFrameIsCheckedBetweenTicks() {
        val base = letters.repeat(5)
        var target by mutableStateOf(base)
        var shownNow = ""
        rule.setContent { Themed { shownNow = rememberSmoothedText("r1", target, snap = false) } }
        frame(2)
        // Caught up on base; "bad" arrives and is revealed, then is rewritten one frame later, before the next tick.
        target = base + "bad"
        frame(3)
        assertEquals("one slice of 2", base + "ba", shownNow)
        target = base + "fixed" + letters.repeat(40)
        frame()
        assertEquals("the shown text was no longer a prefix: all of it at once", target, shownNow)
        // Rewrites of the unrevealed part keep what is shown and stay a prefix on every frame.
        target = target + letters.repeat(60)
        frame(2)
        for (k in 0 until 6) {
            target = target.substring(0, target.length - 30) + "Q".repeat(30 + k)
            frame()
            assertTrue("frame $k", target.startsWith(shownNow))
        }
    }

    @Test
    fun aCaughtUpSmootherGoesIdle() {
        var target by mutableStateOf("abc")
        var shownNow = ""
        rule.setContent { Themed { shownNow = rememberSmoothedText("r1", target, snap = false) } }
        frame()
        target = "abc" + letters.repeat(60)
        frame(3)
        // A new string instance with exactly the shown content: caught up by length and content.
        target = String(shownNow.toCharArray())
        frame(3)
        val woke = Work.smoothFrames
        frame(20)
        assertEquals("no frames requested once caught up", woke, Work.smoothFrames)
        assertEquals(target, shownNow)
    }

    @Test
    fun aTerminalRunShowsAllAtOnce() {
        var items by mutableStateOf(liveItems("Start"))
        var live by mutableStateOf<LiveReply?>(run("A", "Start"))
        rule.setContent { Themed { Messages(items, live) } }
        frame()
        val long = "Start" + letters.repeat(80)
        items = liveItems(long)
        live = run("A", long)
        frame(5)
        assertTrue(shown("live").length < long.length)
        // Failed while the run is still present (before the final transcript): the buffered text shows whole, no reveal.
        live = LiveReply("A", "failed", long, emptyList(), null, Connection.LIVE)
        frame()
        assertEquals(long, shown("live"))
    }

    @Test
    fun anOffScreenLiveReplyIsNotRevealed() {
        val history: List<ChatItem> = List(40) { i ->
            if (i % 2 == 0) UserMessage("u$i", "question $i", false, FIXED_NOW) else AssistantReply("r$i", emptyList(), "answer $i\n\nmore $i", FIXED_NOW)
        }
        var items by mutableStateOf(liveItems("Go", history))
        var live by mutableStateOf<LiveReply?>(run("A", "Go"))
        var scope: CoroutineScope? = null
        lateinit var state: LazyListState
        rule.setContent {
            Themed {
                scope = rememberCoroutineScope()
                state = rememberLazyListState()
                Messages(items, live, state)
            }
        }
        frame(2)
        scope!!.launch { state.scrollToItem(60) }
        frame(3)
        val woke = Work.smoothFrames
        val long = "Go" + letters.repeat(80)
        items = liveItems(long, history)
        live = run("A", long)
        frame(10)
        assertEquals("no ticks while off screen", woke, Work.smoothFrames)
        scope!!.launch { state.scrollToItem(0) }
        frame(2)
        assertEquals("back on screen: all of it, no replay", long, shown("live"))
    }

    @Test
    fun aStoredUnfinishedToolCallIsStill() {
        val stuck = AssistantReply("r1", listOf(ToolStep("c1", "terminal", "ls", null, false, false, running = true)), "partial", FIXED_NOW)
        var live by mutableStateOf<LiveReply?>(null)
        var items by mutableStateOf<List<ChatItem>>(listOf(stuck))
        rule.setContent { Themed { Messages(items, live) } }
        frame(12)
        assertEquals("an idle chat animates nothing", 0L, PulseProbe.frames)
        // Another run going on (with text, so no reply dots) does not wake the old call either.
        live = run("B", "working")
        items = liveItems("working", listOf(stuck))
        frame(12)
        assertEquals(0L, PulseProbe.frames)
    }

    @Test
    fun theDotsStayUntilTextShows() {
        var items by mutableStateOf(liveItems(""))
        var live by mutableStateOf<LiveReply?>(run("A", ""))
        rule.setContent { Themed { Messages(items, live) } }
        frame(2)
        items = liveItems("Hello there, a reply")
        live = run("A", "Hello there, a reply")
        frame()
        assertEquals("", shown("live"))
        assertEquals(1, rule.onAllNodes(hasContentDescription("Hermes is replying"), useUnmergedTree = true).fetchSemanticsNodes().size)
        frame(3)
        assertTrue(shown("live").isNotEmpty())
        assertEquals(0, rule.onAllNodes(hasContentDescription("Hermes is replying"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun growthIsPerRunAndCoversPolling() {
        val user = UserMessage("u1", "hi", false, FIXED_NOW, runId = "A")
        var live by mutableStateOf<LiveReply?>(run("A", "", Connection.POLLING))
        var transcript by mutableStateOf<List<ChatItem>>(listOf(user))
        var grew = 0
        rule.setContent { Themed { Messages(withLive(transcript, live) { FIXED_NOW }, live, onRevealGrew = { grew++ }) } }
        frame(2)
        // Polling: the live tail is empty; the stored transcript grows.
        transcript = listOf(user, AssistantReply("r1", emptyList(), "part", FIXED_NOW))
        frame(2)
        assertEquals(1, grew)
        transcript = listOf(user, AssistantReply("r1", emptyList(), "part and more", FIXED_NOW))
        frame(2)
        assertEquals(2, grew)
        // Another run with more to show is not growth of the first.
        live = run("B", letters.repeat(30))
        frame(2)
        assertEquals(2, grew)
    }

    /** The indicator under [tag] captured now. */
    private fun capture(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()

    private fun differs(a: PixelMap, b: PixelMap): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) n++
        return n
    }

    private fun motionShows(reduceMotion: Boolean): List<Int> {
        rule.setContent {
            Themed(reduceMotion) {
                Column {
                    Box(Modifier.testTag("dots")) { Dots(null) }
                    Box(Modifier.testTag("mini")) { MiniDots() }
                    Messages(listOf(UserMessage("p1", "hi", false, FIXED_NOW, SendState.SENDING, idempotencyKey = "k")), null)
                }
            }
        }
        frame(2)
        val first = listOf(capture("dots"), capture("mini"), capture("user:p1"))
        frame(16) // a quarter of a second: the wave and the spinner have moved
        val second = listOf(capture("dots"), capture("mini"), capture("user:p1"))
        if (reduceMotion) {
            // The static versions are drawn: every dot's centre is painted (the page shows through elsewhere).
            val dots = first[0]
            val mini = first[1]
            for (i in 0..2) {
                assertNotEquals("dot $i", dots[2, 2], dots[49 + 30 * i, 33])
                assertNotEquals("mini dot $i", Color.Transparent, mini[3 + 10 * i, 3])
            }
        }
        return first.indices.map { differs(first[it], second[it]) }
    }

    @Test
    fun theIndicatorsVisiblyMove() {
        val changed = motionShows(reduceMotion = false)
        assertTrue("dots $changed", changed[0] > 0)
        assertTrue("mini dots $changed", changed[1] > 0)
        assertTrue("spinner $changed", changed[2] > 0)
    }

    @Test
    fun theStaticIndicatorsStayStill() {
        assertEquals(listOf(0, 0, 0), motionShows(reduceMotion = true))
    }
}
