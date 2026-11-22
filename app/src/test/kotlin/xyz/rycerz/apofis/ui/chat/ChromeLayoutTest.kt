package xyz.rycerz.apofis.ui.chat

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.DrawerButton
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** The chrome's layout against the window: what the overlays cover, insets, large text, the keyboard. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChromeLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun edgeToEdge() = rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }

    /** Window insets in px, as the system would dispatch them. */
    private fun insets(ime: Int = 0, left: Int = 0, right: Int = 0, top: Int = 0) {
        rule.runOnUiThread {
            val i = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(left, 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, right, 0))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, i)
        }
        rule.waitForIdle()
    }

    private lateinit var list: LazyListState
    private var screen: androidx.compose.runtime.MutableState<ChatUiState>? = null // the shown state, for tests that change it
    private val retried = ArrayList<String>()
    private var retryLoads = 0

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(state: ChatUiState, fontScale: Float = 1f, rtl: Boolean = false, initialIndex: Int = 0, initialOffset: Int = 0) {
        val actions = object : ChatActions {
            override fun onRetry(idempotencyKey: String) {
                retried += idempotencyKey
            }
        }
        val newChat: @Composable RowScope.() -> Unit = {
            ApofisIconButton(onClick = {}, contentDescription = "New chat") { Icon(painterResource(R.drawable.lucide_square_pen), null) }
        }
        val holder = androidx.compose.runtime.mutableStateOf(state).also { screen = it }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                    androidx.compose.foundation.layout.Box(Modifier.semantics { testTagsAsResourceId = true }) {
                        list = rememberLazyListState(initialIndex, initialOffset)
                        ChatScreen(
                            holder.value, actions, onOpenConnection = {}, onRetryLoad = { retryLoads++ },
                            navigationIcon = { DrawerButton {} }, appBarActions = newChat,
                            zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun chat(items: List<ChatItem>, status: StatusLine = StatusLine.IDLE, live: LiveReply? = null, title: String = "Largest files") =
        ChatUiState("apofis-1", title, status, withLive(items, live) { FIXED_NOW }, if (live != null) ComposerMode.STOP else ComposerMode.SEND_DISABLED, "", "hermes.example", live)

    private fun bounds(m: SemanticsMatcher): Rect = rule.onNode(m, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** A node's full layout rectangle in the root, not clipped by its parents. */
    private fun SemanticsNode.unclipped(): Rect {
        val p = layoutInfo.coordinates.positionInRoot()
        return Rect(p.x, p.y, p.x + size.width, p.y + size.height)
    }

    private fun tagged(prefix: String) = SemanticsMatcher("tag $prefix*") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    private fun barBottom() = bounds(hasTestTag("chat.topbar.row")).bottom

    private fun bottomAreaTop() = bounds(hasTestTag("chat.bottom")).top

    private fun px(dp: Int) = with(rule.density) { dp.dp.toPx() }

    // ---- I3: what the overlays cover takes no taps and no accessibility focus ----

    private val failed: List<ChatItem> = (1..30).flatMap {
        listOf(
            UserMessage("u$it", "Message $it", false, FIXED_NOW, SendState.FAILED, "k$it", error = "unconfirmed", attempted = true),
            AssistantReply("r$it", emptyList(), "Answer $it.", FIXED_NOW),
        )
    }

    /** The texts of the accessibility tree that are visible to the user (TalkBack skips the rest). */
    private fun accessibilityTexts(): List<String> {
        val manager = rule.activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        rule.waitForIdle()
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        val compose = (content.getChildAt(0) as ViewGroup).getChildAt(0)
        val provider = compose.accessibilityNodeProvider
        val childId = AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.java).apply { isAccessible = true }
        val out = ArrayList<String>()
        fun walk(id: Int) {
            val n = provider.createAccessibilityNodeInfo(id) ?: return
            if (n.isVisibleToUser) (n.text ?: n.contentDescription)?.let { out += it.toString() }
            for (i in 0 until n.childCount) walk(((childId.invoke(n, i) as Long) shr 32).toInt())
        }
        walk(View.NO_ID)
        return out
    }

    private fun scrollUntil(found: () -> Boolean) {
        for (index in 0..40) for (offset in 0..400 step 20) {
            rule.runOnUiThread { kotlinx.coroutines.runBlocking { list.scrollToItem(index, offset) } }
            rule.waitForIdle()
            if (found()) return
        }
        error("nothing fits")
    }

    private fun retryNodes() = rule.onAllNodes(hasText("Retry"), useUnmergedTree = true).fetchSemanticsNodes()

    /** Short assistant rows ("Answer N."): one line, so a whole row (one accessibility node) fits under a bar. */
    private fun messageNodes() = rule.onAllNodes(hasText("Answer ", substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    private fun SemanticsNode.text() = config[SemanticsProperties.Text].joinToString()

    /** The key of the user row holding [node]. */
    private fun keyOf(node: SemanticsNode): String {
        var n: SemanticsNode? = node
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("user:") }?.let { return "k" + it.removePrefix("user:u") }
            n = n.parent
        }
        error("no row")
    }

    @Test
    fun coveredRowsTakeNoTapsAndNoFocus() {
        show(chat(failed))
        val bar = barBottom()
        val covered: (Rect) -> Boolean = { it.top >= 0f && it.bottom <= bar }
        scrollUntil { retryNodes().any { covered(it.unclipped()) } }
        val hiddenRetry = retryNodes().first { covered(it.unclipped()) }
        rule.onRoot().performTouchInput { click(hiddenRetry.unclipped().center) } // a tap on the opaque bar, over a hidden Retry
        rule.waitForIdle()
        assertFalse("the tap went through the bar", keyOf(hiddenRetry) in retried)
        val visible: (Rect) -> Boolean = { it.top > bar + px(24) && it.bottom < bottomAreaTop() }
        val exposedRetry = retryNodes().first { visible(it.unclipped()) }
        rule.onRoot().performTouchInput { click(exposedRetry.unclipped().center) }
        rule.waitForIdle()
        assertTrue(keyOf(exposedRetry) in retried)

        scrollUntil { messageNodes().any { covered(it.unclipped()) } }
        val hiddenText = messageNodes().first { covered(it.unclipped()) }.text()
        val exposedText = messageNodes().first { visible(it.unclipped()) }.text()
        val texts = accessibilityTexts()
        assertFalse("hidden '$hiddenText' is in the accessibility tree", hiddenText in texts)
        assertTrue("exposed '$exposedText' missing", exposedText in texts)
        // TalkBack order, as Compose sorts it (Robolectric emits no traversal links to observe): within the screen's
        // traversal group the bar comes first, then the list (index 0), then the bottom area, whatever the drawing order.
        fun index(tag: String) = rule.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        assertTrue(rule.onNode(hasTestTag("chat.key:apofis-1"), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.IsTraversalGroup])
        assertTrue("bar ${index("chat.topbar")} < 0 < bottom ${index("chat.bottom")}", index("chat.topbar") < 0f && index("chat.bottom") > 0f)
        // An index orders a container's descendants as a unit only when the container is a traversal group itself.
        for (tag in listOf("chat.topbar", "chat.bottom")) {
            assertTrue("$tag is a traversal group", rule.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.IsTraversalGroup])
        }
    }

    @Test
    fun rowsUnderTheComposerTakeNoFocus() {
        show(chat(failed))
        val height = rule.onRoot().fetchSemanticsNode().size.height
        val covered: (Rect) -> Boolean = { it.top >= bottomAreaTop() && it.bottom <= height }
        scrollUntil { retryNodes().any { covered(it.unclipped()) } && messageNodes().any { covered(it.unclipped()) } }
        val hiddenRetry = retryNodes().first { covered(it.unclipped()) }
        val hiddenText = messageNodes().first { covered(it.unclipped()) }.text()
        rule.onRoot().performTouchInput { click(hiddenRetry.unclipped().center) }
        rule.waitForIdle()
        assertFalse(keyOf(hiddenRetry) in retried)
        assertFalse(hiddenText in accessibilityTexts())
    }

    // ---- I4: at the bottom the last text and enabled controls are clear of the fade ----

    private fun contrastIn(r: Rect): Double {
        val image = rule.onRoot().captureToImage().toPixelMap()
        var lo = 1.0
        var hi = 0.0
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) {
            val l = image[x, y].luminance().toDouble()
            lo = minOf(lo, l)
            hi = maxOf(hi, l)
        }
        return (hi + 0.05) / (lo + 0.05)
    }

    private val question = UserMessage("u1", "How do I find the largest files?", steered = false, timestamp = FIXED_NOW)
    private val answer = AssistantReply("r2", emptyList(), "Use du and sort.\n\nThe biggest entry is your Downloads folder, at 4 GB.", FIXED_NOW)

    /** Over the paragraph's last line only (the lowest 16 dp of its text). */
    private fun lastTextContrast(): Double = bounds(hasText("4 GB", substring = true)).let { contrastIn(Rect(it.left, it.bottom - px(16), it.right, it.bottom)) }

    @Test
    fun lastTextIsClearOfTheFade() {
        show(chat(listOf(question, answer)))
        assertTrue("contrast ${lastTextContrast()}", lastTextContrast() >= 4.5)
        insets(ime = 600)
        assertTrue("with the keyboard: contrast ${lastTextContrast()}", lastTextContrast() >= 4.5)
    }

    @Test
    fun approvalButtonsAreClearOfTheFade() {
        val live = LiveReply("run_1", "running", "", listOf(ToolStep("c1", "terminal", "ls", "ok", false, error = false, running = false)), PendingControl("approval", "ctl_1", 2, "rm -rf ~/old", null), Connection.LIVE)
        show(chat(listOf(question.copy(runId = "run_1")), StatusLine.WORKING, live))
        rule.mainClock.advanceTimeBy(2000)
        val allow = bounds(hasText("Allow once"))
        assertTrue("contrast ${contrastIn(allow)}", contrastIn(allow) >= 4.5)
    }

    // ---- I5: the header grows with large text ----

    private fun layoutOf(m: SemanticsMatcher): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        rule.onNode(m, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    @Test
    fun headerGrowsWithLargeText() {
        show(chat(failed, title = "Files"), fontScale = 2f)
        val title = bounds(hasTestTag("chat.title"))
        val status = bounds(hasText("hermes.example"))
        assertFalse("title clipped", layoutOf(hasTestTag("chat.title")).didOverflowHeight)
        assertFalse("status clipped", layoutOf(hasText("hermes.example")).didOverflowHeight)
        assertTrue(title.bottom <= status.top + 1f && status.bottom <= barBottom() + 1f)
        // The first row, at the very top of the transcript, starts below the bar.
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { list.scrollToItem(list.layoutInfo.totalItemsCount - 1) } }
        rule.waitForIdle()
        val top = rule.onAllNodes(tagged("user:"), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.unclipped().top }
        assertTrue("first row $top under the bar ${barBottom()}", top >= barBottom())
    }

    // ---- I6: horizontal insets ----

    private fun checkSideInsets(rtl: Boolean) {
        val left = 96f // px: a side navigation bar
        val right = 40f // px: a cutout on the other side
        insets(left = 96, right = 40)
        show(chat(failed), rtl = rtl)
        insets(left = 96, right = 40)
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val drawer = bounds(hasContentDescription("Open chat list"))
        val newChat = bounds(hasContentDescription("New chat"))
        for (r in listOf(drawer, newChat)) assertTrue("$r inside the insets", r.left >= left - 1f && r.right <= width - right + 1f)
        val rows = rule.onAllNodes(tagged("user:") or tagged("reply:"), useUnmergedTree = true).fetchSemanticsNodes().map { it.unclipped() }
        for (r in rows) assertTrue("row $r inside the insets", r.left >= left - 1f && r.right <= width - right + 1f)
        rule.mainClock.autoAdvance = false // the button hides 2 s after scrolling: look while it is shown
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performTouchInput { swipeDown(startY = top + height / 4, endY = bottom - height / 4) } // inside the uncovered area
        repeat(10) { rule.mainClock.advanceTimeByFrame() }
        val button = rule.onAllNodes(hasContentDescription("Scroll to the latest message"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, button.size)
        val b = button.single().boundsInRoot
        // At the end edge: its inset plus the 2 dp end gap of the 48 dp target (12 dp to the 28 dp circle).
        val gap = px(2)
        if (rtl) assertEquals("RTL: the button's distance to the left (end) edge", left + gap, b.left, 1.5f)
        else assertEquals("the button's distance to the right (end) edge", right + gap, width - b.right, 1.5f)
    }

    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun sideInsetsLandscape() = checkSideInsets(rtl = false)

    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun sideInsetsLandscapeRtl() = checkSideInsets(rtl = true)

    // ---- I7: the load failure lies in the uncovered area and its Retry can be tapped ----

    /** I7: with no room left for it (landscape, status bar, keyboard), no second, unreachable Retry is shown; the title's is. */
    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun loadFailureWithoutRoomOffersOnlyTheTitleRetry() {
        insets(top = 48, ime = 480)
        show(ChatUiState("", "", StatusLine.LOAD_FAILED, emptyList(), ComposerMode.SEND_DISABLED, ""))
        insets(top = 48, ime = 480) // 24 dp status bar, 240 dp keyboard in a 360 dp window
        assertEquals(0, rule.onAllNodes(hasText("Try again") and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().size)
        val titleRetry = rule.onAllNodes(SemanticsMatcher("retry from the title") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Try again" })
            .fetchSemanticsNodes()
        assertEquals(1, titleRetry.size)
    }

    @Test
    @Config(qualifiers = "w360dp-h400dp-xhdpi")
    fun loadFailureRetryIsReachableWithTheKeyboard() {
        show(ChatUiState("", "", StatusLine.LOAD_FAILED, emptyList(), ComposerMode.SEND_DISABLED, ""))
        insets(ime = 320) // 160 dp keyboard in a 400 dp window: a small region, still room for a Retry (scrolled to)
        val retry = hasText("Try again") and androidx.compose.ui.test.hasClickAction()
        rule.onNode(retry).performScrollTo()
        rule.waitForIdle()
        val r = rule.onNode(retry).fetchSemanticsNode().boundsInRoot
        assertTrue("retry $r below the bar ${barBottom()} and above the composer ${bottomAreaTop()}", r.top >= barBottom() && r.bottom <= bottomAreaTop())
        rule.onRoot().performTouchInput { click(r.center) }
        rule.waitForIdle()
        assertEquals(1, retryLoads)
    }

    // ---- Task 6: the run-end announcer sits in the uncovered transcript and is no focus stop while idle ----

    /** The announcer as the accessibility provider exports it: the 1 dp polite live region. Null if not exported. */
    private fun announcerInfo(): Pair<Int, AccessibilityNodeInfo>? {
        val manager = rule.activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        val compose = (content.getChildAt(0) as ViewGroup).getChildAt(0)
        val provider = compose.accessibilityNodeProvider
        val childId = AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.java).apply { isAccessible = true }
        var found: Pair<Int, AccessibilityNodeInfo>? = null
        fun walk(id: Int) {
            val n = provider.createAccessibilityNodeInfo(id) ?: return
            val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            if (n.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE && r.height() <= px(2)) found = id to n
            for (i in 0 until n.childCount) walk(((childId.invoke(n, i) as Long) shr 32).toInt())
        }
        walk(View.NO_ID)
        return found
    }

    @Test
    @Config(qualifiers = "w360dp-h400dp-xhdpi")
    fun theAnnouncerIsVisibleAboveTheKeyboardAndNoIdleFocusStop() {
        val user = UserMessage("u1", "Hello", false, FIXED_NOW, runId = "A")
        show(chat(listOf(user), StatusLine.WORKING, LiveReply("A", "running", "Working on it", emptyList(), null, Connection.LIVE)))
        insets(ime = 320) // 160 dp keyboard in a 400 dp window: the window centre is under the composer, the top is not
        rule.mainClock.autoAdvance = false
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        val (idleId, idle) = announcerInfo() ?: error("no announcer exported")
        assertTrue("visible before the run ends", idle.isVisibleToUser)
        assertFalse("no focus stop while idle", idle.isScreenReaderFocusable)
        assertEquals(null, idle.stateDescription)
        val r = android.graphics.Rect().also { idle.getBoundsInScreen(it) }
        assertTrue("below the bar ${barBottom()}, above the composer ${bottomAreaTop()}: $r", r.top >= barBottom() && r.bottom <= bottomAreaTop())
        screen!!.value = chat(listOf(user.copy(runId = null), AssistantReply("r1", emptyList(), "Done", FIXED_NOW)))
        repeat(4) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeByFrame()
        }
        val (holdId, hold) = announcerInfo() ?: error("no announcer exported during the hold")
        assertEquals("the same node", idleId, holdId)
        assertTrue("visible during the hold", hold.isVisibleToUser)
        assertEquals("Reply ended", hold.stateDescription?.toString())
    }
}
