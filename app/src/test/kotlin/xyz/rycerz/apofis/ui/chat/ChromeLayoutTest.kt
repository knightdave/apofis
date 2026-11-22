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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
    private fun insets(ime: Int = 0, left: Int = 0, right: Int = 0, top: Int = 0, bottom: Int = 0) {
        rule.runOnUiThread {
            val i = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(left, 0, 0, bottom))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, right, 0))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, i)
        }
        rule.waitForIdle()
    }

    private lateinit var list: LazyListState
    private val retried = ArrayList<String>()
    private var retryLoads = 0
    private var sendClicks = 0
    private val cancelled = ArrayList<String>()
    private var drawerTaps = 0
    private var discardAsked = 0
    private lateinit var inputMode: androidx.compose.ui.input.InputModeManager
    private val stopped = ArrayList<String>()
    private var newChatTaps = 0
    private var shown by androidx.compose.runtime.mutableStateOf<ChatUiState?>(null) // the state on screen; set again to change it

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(state: ChatUiState, fontScale: Float = 1f, rtl: Boolean = false, initialIndex: Int = 0, initialOffset: Int = 0) {
        shown = state
        val actions = object : ChatActions {
            override fun onRetry(idempotencyKey: String) {
                retried += idempotencyKey
            }

            override fun onSendClick(): Boolean {
                sendClicks++
                return true
            }

            override fun onCancel(idempotencyKey: String) {
                cancelled += idempotencyKey
            }

            override fun onPutBack() { // as the holder does: the unsent text back in the field
                val s = shown!!
                shown = s.copy(draft = s.unsent!!.text, unsent = null, composer = ComposerMode.SEND)
            }

            override fun onDiscardUnsent() {
                discardAsked++
            }

            override fun onStopClick(runId: String) {
                stopped += runId
            }
        }
        val newChat: @Composable RowScope.() -> Unit = {
            ApofisIconButton(onClick = { newChatTaps++ }, contentDescription = "New chat") { Icon(painterResource(R.drawable.lucide_square_pen), null) }
        }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                    inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
                    androidx.compose.foundation.layout.Box(Modifier.semantics { testTagsAsResourceId = true }) {
                        list = rememberLazyListState(initialIndex, initialOffset)
                        ChatScreen(
                            shown!!, actions, onOpenConnection = {}, onRetryLoad = { retryLoads++ },
                            navigationIcon = { DrawerButton { drawerTaps++ } }, appBarActions = newChat,
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

    // ---- Task 8: at every composer height the last text and controls rest clear of the fade and the composer ----

    private fun withDraft(state: ChatUiState, lines: Int) = state.copy(composer = ComposerMode.SEND, draft = (1..lines).joinToString("\n") { "Draft line $it" })

    @Test
    fun lastTextIsClearAtEveryComposerHeight() {
        show(chat(listOf(question, answer)))
        for (lines in listOf(1, 5, 12)) {
            shown = withDraft(chat(listOf(question, answer)), lines)
            rule.waitForIdle()
            for (ime in listOf(0, 600)) {
                insets(ime = ime)
                val text = bounds(hasText("4 GB", substring = true))
                assertTrue("$lines lines, keyboard $ime: text $text under the composer ${bottomAreaTop()}", text.bottom <= bottomAreaTop())
                assertTrue("$lines lines, keyboard $ime: contrast ${lastTextContrast()}", lastTextContrast() >= 4.5)
            }
            insets(ime = 0)
        }
    }

    @Test
    fun approvalButtonsAreClearAtEveryComposerHeight() {
        val live = LiveReply("run_1", "running", "", listOf(ToolStep("c1", "terminal", "ls", "ok", false, error = false, running = false)), PendingControl("approval", "ctl_1", 2, "rm -rf ~/old", null), Connection.LIVE)
        show(chat(listOf(question.copy(runId = "run_1")), StatusLine.WORKING, live))
        rule.mainClock.advanceTimeBy(2000)
        for (lines in listOf(1, 5)) {
            shown = withDraft(chat(listOf(question.copy(runId = "run_1")), StatusLine.WORKING, live), lines).copy(composer = ComposerMode.STEER)
            rule.waitForIdle()
            val allow = bounds(hasText("Allow once"))
            assertTrue("$lines lines: $allow under the composer", allow.bottom <= bottomAreaTop())
            assertTrue("$lines lines: contrast ${contrastIn(allow)}", contrastIn(allow) >= 4.5)
        }
    }

    private fun px(dp: Float) = with(rule.density) { dp.dp.toPx() }

    /** The node's bounds clipped to the window and its parents, as the accessibility service and touch see them. */
    private fun visible(m: SemanticsMatcher): Rect = rule.onNode(m, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /**
     * Expanded, the field takes everything the transcript's 64 dp leave between the bar and the keyboard; collapsed, the
     * last row is fully visible.
     */
    @Test
    fun expandedTakesTheBudgetAndCollapsedShowsTheLastRow() {
        show(withDraft(chat(failed), 3))
        rule.onNode(hasContentDescription("Expand the editor")).performClick()
        rule.waitForIdle()
        val card = px(16f) // the card's margin and padding
        for (ime in listOf(0, 600)) {
            insets(ime = ime)
            val height = rule.onRoot().fetchSemanticsNode().size.height
            val field = visible(hasTestTag("composer.field"))
            assertEquals("keyboard $ime: the field starts 64 dp below the bar", barBottom() + px(64f) + card, field.top, 1.5f)
            assertEquals("keyboard $ime: the field ends above the keyboard", height - ime - card, field.bottom, 1.5f)
            assertEquals("keyboard $ime: the bottom area starts where the card does", field.top - card, bottomAreaTop(), 1.5f)
        }
        insets(ime = 0)
        rule.onNode(hasContentDescription("Collapse the editor")).performClick()
        rule.waitForIdle()
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { list.scrollToItem(0) } }
        rule.waitForIdle()
        val last = visible(tagged("reply:r30"))
        assertTrue("the last row $last is fully between the bar ${barBottom()} and the composer ${bottomAreaTop()}", last.top >= barBottom() && last.bottom <= bottomAreaTop() && last.height > px(10f))
    }

    /** Every composer node lies inside the bottom area as reported, and above the keyboard. */
    /** The chat screen's root node (a text handle can add a second window root). */
    private fun screen() = rule.onNode(tagged("chat.key:"), useUnmergedTree = true)

    /** A button by its text, as one merged clickable node (a 40 dp Material button), clipped as touch and accessibility see it; whole or it fails. */
    private fun button(text: String): Rect {
        val node = rule.onNode(hasText(text) and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNode()
        assertEquals("$text is cut off", node.unclipped().height, node.boundsInRoot.height, 0.5f)
        return node.boundsInRoot
    }

    private fun assertComposerInsideTheBottomArea(ime: Int, case: String) {
        val height = screen().fetchSemanticsNode().size.height
        val area = bounds(hasTestTag("chat.bottom"))
        for (m in listOf(hasTestTag("composer.field"), hasTestTag("composer.send"))) {
            val r = rule.onNode(m, useUnmergedTree = true).fetchSemanticsNode().unclipped()
            assertTrue("$case: $m at $r inside the bottom area $area, above the keyboard (height $height)", r.top >= area.top - 0.5f && r.bottom <= height - ime + 0.5f)
        }
        val send = visible(hasTestTag("composer.send"))
        assertTrue("$case: Send is a whole 48 dp target: $send", send.height >= px(48f) - 0.5f && send.width >= px(48f) - 0.5f)
    }

    private fun barInAccessibility() = accessibilityTexts().any { it == "Open chat list" || it == "Otwórz listę czatów" }

    /** 360 dp landscape with a 200 dp keyboard and the status bar, font scale 1.0: the bar and the smallest composer exactly fit. */
    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun landscapeWithTheKeyboardKeepsTheBarWhenItFits() {
        show(withDraft(chat(failed), 1))
        insets(top = 48, ime = 400)
        assertTrue("the bar is shown", barInAccessibility())
        rule.onRoot().performTouchInput { click(visible(hasContentDescription("Open chat list")).center) }
        rule.waitForIdle()
        assertEquals(1, drawerTaps)
        assertComposerInsideTheBottomArea(400, "scale 1.0")
        rule.onRoot().performTouchInput { click(visible(hasTestTag("composer.send")).center) }
        rule.waitForIdle()
        assertEquals(1, sendClicks)
    }

    /**
     * The same window at font scale 2.0: the bar and the smallest composer do not fit together above the keyboard, so the
     * bar is not shown (not placed, not tappable, not in the accessibility tree); the field and Send stay usable, nothing
     * lies under the keyboard; the bar returns when the keyboard closes.
     */
    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun landscapeWithTheKeyboardHidesTheBarWhenItDoesNotFit() {
        show(withDraft(chat(failed), 1), fontScale = 2f)
        insets(top = 48, ime = 480) // 96 dp of safe band: the bare row (48 dp) and the 66 dp bar do not fit together
        assertFalse("the bar is in the accessibility tree", barInAccessibility())
        val announcer = announcerInfo()?.second ?: error("no announcer exported with the bar hidden")
        val at = android.graphics.Rect().also { announcer.getBoundsInScreen(it) }
        assertEquals("the announcer sits at the status inset, in its own slot", 48, at.top)
        for (m in listOf(hasTestTag("composer.field"), hasTestTag("composer.send"))) {
            assertTrue("$m below the status inset", visible(m).top >= 48f)
        }
        assertTrue("the bar is placed", rule.onAllNodes(hasTestTag("chat.topbar"), useUnmergedTree = true).fetchSemanticsNodes().none { it.layoutInfo.isPlaced })
        screen().performTouchInput { click(androidx.compose.ui.geometry.Offset(px(40f), px(40f))) } // where the drawer button was
        rule.waitForIdle()
        assertEquals(0, drawerTaps)
        assertComposerInsideTheBottomArea(480, "scale 2.0")
        screen().performTouchInput { click(visible(hasTestTag("composer.send")).center) }
        screen().performTouchInput { click(visible(hasTestTag("composer.field")).center) }
        rule.waitForIdle()
        assertEquals(1, sendClicks)
        assertTrue(rule.onNode(hasTestTag("composer.field")).fetchSemanticsNode().config[SemanticsProperties.Focused])
        insets(top = 48, ime = 0)
        assertTrue("the bar returns with the keyboard closed", barInAccessibility())
        screen().performTouchInput { click(visible(hasContentDescription("Open chat list")).center) }
        rule.waitForIdle()
        assertEquals(1, drawerTaps)
    }

    private val unsent = xyz.rycerz.apofis.chat.UnsentText(7, "Which of these can I safely delete?", xyz.rycerz.apofis.chat.ComposeError.NOT_STORED)

    /** A 220 dp window, status bar, keyboard closed, a failed send: the notice row is visible and Put back recovers the text. */
    @Test
    @Config(qualifiers = "w360dp-h220dp-xhdpi")
    fun shortWindowKeepsTheUnsentRowAndPutBackWorks() {
        show(chat(failed).copy(unsent = unsent))
        insets(top = 48)
        val putBack = button("Put back")
        assertTrue("Put back $putBack visible between the bar ${barBottom()} and the composer", putBack.top >= barBottom() - 0.5f && putBack.height >= px(40f) - 0.5f && putBack.bottom <= visible(hasTestTag("composer.field")).top + 0.5f)
        assertComposerInsideTheBottomArea(0, "220 dp")
        rule.onRoot().performTouchInput { click(putBack.center) }
        rule.waitForIdle()
        assertEquals(unsent.text, rule.onNode(hasTestTag("composer.field")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    @Config(qualifiers = "w360dp-h220dp-xhdpi")
    fun shortWindowKeepsTheUnsentRowAndDiscardWorks() {
        show(chat(failed).copy(unsent = unsent))
        insets(top = 48)
        val discard = button("Discard")
        assertTrue("Discard $discard visible", discard.top >= barBottom() - 0.5f && discard.height >= px(40f) - 0.5f)
        rule.onRoot().performTouchInput { click(discard.center) }
        rule.waitForIdle()
        assertEquals(1, discardAsked)
    }

    /** A short window (240 dp) with the status bar, keyboard closed: the transcript's last row and its cancel action are reached and taken. */
    @Test
    @Config(qualifiers = "w360dp-h240dp-xhdpi")
    fun shortWindowReachesTheLastRowsCancel() {
        val queued = UserMessage("uq", "The last message, queued", false, FIXED_NOW, SendState.QUEUED, "kq")
        show(withDraft(chat(failed.take(6) + queued), 6)) // six lines: only the 64 dp share keeps the transcript
        insets(top = 48)
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { list.scrollToItem(0) } }
        rule.waitForIdle()
        val cancel = visible(hasContentDescription("Cancel sending"))
        assertTrue("cancel $cancel visible between the bar ${barBottom()} and the composer ${bottomAreaTop()}", cancel.top >= barBottom() - 0.5f && cancel.bottom <= bottomAreaTop() + 0.5f && cancel.height >= px(48f) - 0.5f)
        rule.onRoot().performTouchInput { click(cancel.center) }
        rule.waitForIdle()
        assertEquals(listOf("kq"), cancelled)
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

    /** Retry nodes that are placed (the bar's copy for measuring is never placed and has no semantics). */
    private fun placedRetries() = rule.onAllNodes(SemanticsMatcher("retry") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Try again" || it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Try again" } == true })
        .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced && it.config.contains(SemanticsActions.OnClick) }

    /**
     * I7 and I11: after a failed load with the keyboard open in landscape, the bar (and its title's retry) is hidden; the
     * body offers a compact Retry wherever its 48 dp target fits: placed, exported by the accessibility provider and
     * tappable. With a taller keyboard no 48 dp target fits and none is offered (none can be tapped); with the keyboard
     * closed the bar returns with its own retry.
     */
    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun loadFailureKeepsAReachableRetry() {
        insets(top = 48, ime = 480)
        show(ChatUiState("", "", StatusLine.LOAD_FAILED, emptyList(), ComposerMode.SEND_DISABLED, ""))
        insets(top = 48, ime = 480) // 24 dp status, 240 dp keyboard: 96 dp of safe band, the bar hidden, 48 dp of body
        assertFalse("the bar is shown", barInAccessibility())
        val retry = placedRetries().single()
        assertTrue("the compact Retry is exported to accessibility", "Try again" in accessibilityTexts())
        assertTrue("its target ${retry.boundsInRoot} is whole and below the status inset", retry.boundsInRoot.height >= px(40f) - 0.5f && retry.boundsInRoot.top >= 48f)
        screen().performTouchInput { click(retry.boundsInRoot.center) }
        rule.waitForIdle()
        assertEquals(1, retryLoads)
        insets(top = 48, ime = 560) // 280 dp keyboard: 56 dp of safe band, the bare row takes 48; no 48 dp target fits
        assertEquals("a Retry where no 48 dp target fits", 0, placedRetries().size)
        insets(top = 48, ime = 0)
        assertTrue("the bar returns", barInAccessibility())
        val title = placedRetries().first { it.boundsInRoot.top < barBottom() }
        screen().performTouchInput { click(title.boundsInRoot.center) }
        rule.waitForIdle()
        assertEquals(2, retryLoads)
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
        shown = chat(listOf(user.copy(runId = null), AssistantReply("r1", emptyList(), "Done", FIXED_NOW)))
        repeat(4) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeByFrame()
        }
        val (holdId, hold) = announcerInfo() ?: error("no announcer exported during the hold")
        assertEquals("the same node", idleId, holdId)
        assertTrue("visible during the hold", hold.isVisibleToUser)
        assertEquals("Reply ended", hold.stateDescription?.toString())
    }

    /** With the editor expanded over the transcript, the announcer is still exported, visible, and says "Reply ended". */
    @Test
    fun theAnnouncerSpeaksWithTheEditorExpanded() {
        val user = UserMessage("u1", "Hello", false, FIXED_NOW, runId = "A")
        val live = LiveReply("A", "running", "Working on it", emptyList(), null, Connection.LIVE)
        show(withDraft(chat(listOf(user), StatusLine.WORKING, live), 3).copy(composer = ComposerMode.STEER))
        rule.onNode(hasContentDescription("Expand the editor")).performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Collapse the editor")).assertExists()
        rule.mainClock.autoAdvance = false
        shown = withDraft(chat(listOf(user.copy(runId = null), AssistantReply("r1", emptyList(), "Done", FIXED_NOW))), 3)
        repeat(4) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeByFrame()
        }
        rule.onNode(hasContentDescription("Collapse the editor")).assertExists()
        val (_, hold) = announcerInfo() ?: error("no announcer exported with the editor expanded")
        assertTrue("visible with the editor expanded", hold.isVisibleToUser)
        assertEquals("Reply ended", hold.stateDescription?.toString())
        val r = android.graphics.Rect().also { hold.getBoundsInScreen(it) }
        assertEquals("in its root slot at the bar's bottom edge, not in the list below it", barBottom(), r.top.toFloat(), 1f)
    }

    // ---- Fix round 3: boundary cases on the real safe bounds ----

    /** The bottom area's placed geometry equals the allocation computed from the measured inputs. */
    private fun assertPlacedAsAllocated(status: Int, bottomInset: Int, notes: Int, unsent: Boolean, lines: Int) {
        val height = screen().fetchSemanticsNode().size.height
        val d = rule.density.bottomDims(androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 1.3.em))
        val barOwn = bounds(hasTestTag("chat.topbar.row")).height.toInt()
        val s = bottomShares(height - status - bottomInset, barOwn, notes, unsent, lines >= 3, null, d) { room -> minOf(room, d.field(minOf(lines, 5))) }
        val area = bounds(hasTestTag("chat.bottom"))
        assertEquals("the bottom area holds the notes and the composer", (height - bottomInset - s.notes - s.composer).toFloat(), area.top, 1f)
        val field = rule.onNode(hasTestTag("composer.field"), useUnmergedTree = true).fetchSemanticsNode().unclipped()
        val composerTop = area.top + s.notes
        assertEquals("the field ends at the row's bottom", composerTop + s.marginTop + s.paddingTop + s.row, field.bottom, 1f)
        assertEquals("the field's share", s.field.toFloat(), field.height, 1f)
        assertEquals("the transcript's share", s.transcript.toFloat(), area.top - barBottom(), 1f)
    }

    /** 200 dp, a 24 dp status inset and an 80 dp bar: the transcript has its 64 dp and the last row's Cancel is tapped. */
    @Test
    @Config(qualifiers = "w360dp-h200dp-xhdpi")
    fun at200dpTheTranscriptKeepsItsShare() {
        val queued = UserMessage("uq", "The last message, queued", false, FIXED_NOW, SendState.QUEUED, "kq")
        show(withDraft(chat(failed.take(6) + queued), 1))
        insets(top = 48)
        assertTrue(barInAccessibility())
        assertEquals("64 dp of transcript", px(64), bottomAreaTop() - barBottom(), 1f)
        assertPlacedAsAllocated(status = 48, bottomInset = 0, notes = 0, unsent = false, lines = 1)
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { list.scrollToItem(0) } }
        rule.waitForIdle()
        val cancel = visible(hasContentDescription("Cancel sending"))
        assertTrue("cancel $cancel whole between the bar ${barBottom()} and the composer ${bottomAreaTop()}", cancel.top >= barBottom() - 0.5f && cancel.bottom <= bottomAreaTop() + 0.5f && cancel.height >= px(48) - 0.5f)
        screen().performTouchInput { click(cancel.center) }
        rule.waitForIdle()
        assertEquals(listOf("kq"), cancelled)
    }

    /** Put back and Discard of the visible notice row, by accessibility action (as TalkBack activates them). */
    private fun activate(text: String) = rule.onNode(hasText(text) and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    /** 220 dp with a 24 dp status inset, 24 dp navigation and a failed send: the whole 54 dp row; Discard by accessibility, Put back by touch. */
    @Test
    @Config(qualifiers = "w360dp-h220dp-xhdpi")
    fun at220dpWithNavigationTheWholeNoticeRowShows() {
        show(chat(failed).copy(unsent = unsent))
        insets(top = 48, bottom = 48)
        assertTrue(barInAccessibility())
        val notesHeight = bottomAreaTop().let { top -> visible(hasTestTag("composer.field")).top - top }
        assertTrue("the notice row has its 54 dp: $notesHeight", notesHeight >= px(54) - 1f)
        val put = button("Put back")
        val discard = button("Discard")
        assertTrue("both exported", accessibilityTexts().containsAll(listOf("Put back", "Discard")))
        assertTrue(put.top >= barBottom() && discard.top >= barBottom())
        activate("Discard")
        rule.waitForIdle()
        assertEquals(1, discardAsked)
        screen().performTouchInput { click(put.center) }
        rule.waitForIdle()
        assertEquals(unsent.text, rule.onNode(hasTestTag("composer.field")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    /** The same, Put back by accessibility and Discard by touch. */
    @Test
    @Config(qualifiers = "w360dp-h220dp-xhdpi")
    fun at220dpWithNavigationPutBackByAccessibility() {
        show(chat(failed).copy(unsent = unsent))
        insets(top = 48, bottom = 48)
        screen().performTouchInput { click(button("Discard").center) }
        rule.waitForIdle()
        assertEquals(1, discardAsked)
        activate("Put back")
        rule.waitForIdle()
        assertEquals(unsent.text, rule.onNode(hasTestTag("composer.field")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    /** An unsent notice and a second note: the guaranteed row is the unsent one; the note follows when there is room. */
    @Test
    @Config(qualifiers = "w360dp-h220dp-xhdpi")
    fun anUnsentNoticeWithASecondNoteKeepsItsRow() {
        show(chat(failed).copy(unsent = unsent, composerNote = ComposerNote.CANCEL_TOO_LATE))
        insets(top = 48, bottom = 48)
        val put = button("Put back")
        assertTrue("Put back $put whole in the guaranteed row", put.top >= bottomAreaTop() - 0.5f)
        screen().performTouchInput { click(put.center) }
        rule.waitForIdle()
        assertEquals(unsent.text, rule.onNode(hasTestTag("composer.field")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    /** The editor expanded with a note: the note keeps its natural height above the card. */
    @Test
    fun theExpandedEditorKeepsTheNotes() {
        show(withDraft(chat(failed), 3).copy(composerNote = ComposerNote.REMOVED_MAY_HAVE_SENT))
        rule.onNode(hasContentDescription("Expand the editor")).performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Collapse the editor")).assertExists()
        val note = visible(hasText("Removed from this phone", substring = true))
        assertTrue("the note is shown whole: $note", note.height >= px(16) && note.bottom <= visible(hasTestTag("composer.field")).top)
        assertTrue("and exported", accessibilityTexts().any { it.startsWith("Removed from this phone") })
    }

    /** A running reply, the bar hidden (landscape, keyboard): Stop is below the status inset and takes a real tap. */
    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun stopWorksWithTheBarHidden() {
        val user = UserMessage("u1", "Hello", false, FIXED_NOW, runId = "A")
        val live = LiveReply("A", "running", "Working on it", emptyList(), null, Connection.LIVE)
        show(chat(listOf(user), StatusLine.WORKING, live))
        insets(top = 48, ime = 480)
        assertFalse(barInAccessibility())
        val stop = visible(hasTestTag("composer.stop"))
        assertTrue("Stop $stop whole below the status inset and above the keyboard", stop.top >= 48f && stop.height >= px(48) - 0.5f && stop.bottom <= screen().fetchSemanticsNode().size.height - 480 + 0.5f)
        screen().performTouchInput { click(stop.center) }
        rule.waitForIdle()
        assertEquals(listOf("A"), stopped)
    }

    /** I10: New chat focused by keyboard, then the bar hides: Enter creates no chat; the restored bar works and took no focus. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun aHiddenBarTakesNoKeys() {
        show(withDraft(chat(failed), 1))
        insets(top = 48)
        rule.runOnUiThread { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) } // a hardware keyboard
        rule.onNode(hasContentDescription("New chat")).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        assertTrue("New chat has keyboard focus", rule.onNode(hasContentDescription("New chat")).fetchSemanticsNode().config[SemanticsProperties.Focused])
        insets(top = 48, ime = 480) // the bar hides
        assertFalse(barInAccessibility())
        rule.runOnUiThread {
            val compose = (rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
            compose.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            compose.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
        rule.waitForIdle()
        assertEquals("a hidden New chat took Enter", 0, newChatTaps)
        insets(top = 48)
        assertTrue(barInAccessibility())
        assertFalse("the returning bar took focus", rule.onNode(hasContentDescription("New chat")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        screen().performTouchInput { click(visible(hasContentDescription("New chat")).center) }
        rule.waitForIdle()
        assertEquals(1, newChatTaps)
    }

    /** The scroll-to-bottom button is placed only where its 48 dp target fits in the uncovered transcript band. */
    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun theScrollButtonNeedsRoomForItsTarget() {
        show(withDraft(chat(failed), 1))
        insets(top = 48)
        rule.mainClock.autoAdvance = false // the button hides 2 s after scrolling: look while it is shown
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performTouchInput { swipeDown(startY = top + height / 4, endY = bottom - height / 4) }
        repeat(10) { rule.mainClock.advanceTimeByFrame() }
        fun placedButtons() = rule.onAllNodes(hasContentDescription("Scroll to the latest message"), useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
        assertEquals("shown with room", 1, placedButtons().size)
        insets(top = 48, ime = 560) // 56 dp of safe band: the bare row leaves the transcript 8 dp
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        assertEquals("placed without room for its target", 0, placedButtons().size)
    }
}
