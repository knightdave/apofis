package xyz.rycerz.apofis.ui.settings

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import xyz.rycerz.apofis.settings.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.chat.withLive
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.clippedTexts
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ChromeProbe
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.FIELD_TAG
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import xyz.rycerz.apofis.ui.theme.ViewHaptics
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class SettingsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var scale by mutableStateOf(1f)
    private var hapticsOn = true
    private val calls = mutableListOf<Int>() // feedback constants performed on the view
    private val events = mutableListOf<Any>() // callbacks, in order
    private var switchedOn by mutableStateOf(setOf("dynamic", "haptics")) // the switch settings that are on
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    @Composable
    private fun Screen(storeScale: Boolean = true) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            CompositionLocalProvider(LocalHaptics provides ViewHaptics({ calls += it; true }, { hapticsOn })) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                SettingsScreen(
                    serverUrl = "https://hermes.example:8642/",
                    themeMode = themeMode,
                    dynamicColor = "dynamic" in switchedOn,
                    pureBlack = "pure" in switchedOn,
                    blur = "blur" in switchedOn,
                    chatFontScale = scale,
                    enterToSend = "enter" in switchedOn,
                    haptics = "haptics" in switchedOn,
                    versionName = "0.1.0",
                    notifications = {},
                    onConnection = { events += "connection" },
                    onThemeMode = { events += it; themeMode = it },
                    onDynamicColor = { events += "dynamic $it" },
                    onPureBlack = { events += "pure $it" },
                    onBlur = { events += "blur $it" },
                    onChatFontScale = { events += it; if (storeScale) scale = it },
                    onEnterToSend = { events += "enter $it" },
                    onHaptics = { events += "haptics $it" },
                    onLicenses = { events += "licenses" },
                    onBack = { events += "back" },
                )
                }
            }
        }

    private fun show(storeScale: Boolean = true) = rule.setContent { Screen(storeScale) }

    private val selection = HapticFeedbackConstants.CLOCK_TICK
    private fun slider() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
    private fun sheetRow(text: String) = rule.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun row(text: String) = rule.onNode(hasText(text) and hasClickAction()).performScrollTo()
    /**
     * The slider's own bounds are its 20 dp thumb row; its touch target reaches 48 dp around them: taps 22 dp above and
     * below its centre still move it. Leaves the stored value where it was.
     */
    private fun assertTouchTargetIs48(n: SemanticsNodeInteraction) {
        val before = events.size
        val kept = scale
        n.performTouchInput { click(Offset(width * 0.1f, centerY - 22.dp.toPx())) }
        n.performTouchInput { click(Offset(width * 0.9f, centerY + 22.dp.toPx())) }
        rule.waitForIdle()
        assertEquals("both taps moved the slider", before + 2, events.size)
        repeat(2) { events.removeAt(events.size - 1) }
        calls.clear()
        scale = kept
        rule.waitForIdle()
    }

    private fun state(n: SemanticsNodeInteraction) = n.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun themeRowOpensSheetAndPicks() {
        show()
        row("Theme").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(selection), calls)
        for (choice in listOf("Follow system", "Light", "Dark")) {
            sheetRow(choice).assertHeightIsAtLeast(48.dp)
        }
        sheetRow("Dark").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Any>(ThemeMode.DARK), events)
        assertEquals("the sheet row's one selection, no light impact", listOf(selection, selection), calls)
        assertTrue("the sheet closed", rule.onAllNodes(hasText("Light")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Dark", substring = true).assertExists() // the row shows the stored mode
    }

    /** In a short, narrow window at double size the theme choices scroll: the last one can be reached and picked. */
    @Test
    @Config(qualifiers = "w320dp-h200dp-xhdpi", fontScale = 2f)
    fun theLastThemeChoiceIsReachableInAShortWindow() {
        show()
        row("Theme").performClick()
        rule.waitForIdle()
        sheetRow("Dark").performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(listOf<Any>(ThemeMode.DARK), events)
    }

    /** Rotation or process death keeps the sheet open, and it always marks the stored mode. */
    @Test
    fun themeSheetSurvivesRecreationAndShowsTheStoredMode() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Screen() }
        row("Theme").performClick()
        tester.emulateSavedInstanceStateRestore()
        sheetRow("Follow system").assertIsSelected()
        sheetRow("Dark").assertIsNotSelected()
        themeMode = ThemeMode.DARK // stored elsewhere meanwhile
        rule.waitForIdle()
        sheetRow("Dark").assertIsSelected()
    }

    @Test
    fun switchRowToggles() {
        show()
        row("Vibration feedback")
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        assertEquals(listOf<Any>("haptics false"), events)
        assertEquals(listOf(selection), calls)
        // Each switch is one node: its summary is read with it, and nothing inside it is a separate control.
        assertEquals(1, rule.onAllNodes(hasText("Short vibrations on taps and gestures", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun everyNavigationAndSwitchRowGivesOneSelection() {
        show()
        val rows = listOf("Server", "Dynamic color", "Pure background", "Frosted glass", "Enter sends", "Vibration feedback", "Open-source licenses")
        for ((i, text) in rows.withIndex()) {
            row(text).assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(text, List(i + 1) { selection }, calls)
        }
        assertEquals(listOf<Any>("connection", "dynamic false", "pure true", "blur true", "enter true", "haptics false", "licenses"), events)
        // Rows that only show a value are not controls and give nothing.
        rule.onNodeWithText("Version").assertHasNoClickAction()
        rule.onNodeWithText("MIT").assertHasNoClickAction()
    }

    @Test
    fun hapticsOffSilencesEverything() {
        hapticsOn = false
        show()
        for (text in listOf("Server", "Dynamic color", "Pure background", "Frosted glass", "Enter sends", "Vibration feedback", "Open-source licenses", "Theme")) {
            row(text).performClick()
        }
        sheetRow("Light").performClick()
        rule.waitForIdle()
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        assertEquals("every tap landed", 9, events.size)
        assertEquals(emptyList<Int>(), calls)
    }

    @Test
    fun fontScaleSliderSteps() {
        show()
        val s = slider().performScrollTo()
        assertTouchTargetIs48(s)
        assertEquals("100 %", state(s))
        assertEquals("Chat text size", s.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        assertEquals(ProgressBarRangeInfo(1f, 0.85f..1.5f, 12), s.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        s.performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        assertEquals(listOf<Any>(1.5f), events)
        assertEquals(listOf(selection), calls)
        assertEquals("150 %", state(slider()))
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(1.12f) }
        assertEquals(listOf<Any>(1.5f, 1.1f), events)
        assertEquals("110 %", state(slider()))
        // The row above the slider is not a second stop: the slider itself says the label and the percentage.
        val stops = rule.onAllNodes(hasText("Chat text size") or hasText("110 %")).fetchSemanticsNodes()
        assertTrue("$stops ${stops.map { it.config }}", stops.isEmpty())
        assertEquals("Chat text size", slider().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
    }

    /** A drag across the whole track writes each step once, in order, with one selection each, never one per frame. */
    @Test
    fun aDragWritesOncePerStep() {
        show()
        slider().performScrollTo().performTouchInput {
            // The track starts and ends half a thumb (10 dp) inside the slider's bounds.
            val inset = 10.dp.toPx() + 1f
            down(Offset(inset, centerY))
            repeat(200) { moveBy(Offset((width - 2 * inset) / 200f, 0f)) }
            up()
        }
        rule.waitForIdle()
        val steps = (17..30).map { it / 20f } // 0.85 to 1.5
        assertEquals(steps, events)
        assertEquals(List(steps.size) { selection }, calls)
        assertEquals("150 %", state(slider()))
    }

    /** A write that is not stored does not show as applied: released, the slider shows the stored value again. */
    @Test
    fun aDragThatIsNotStoredShowsTheStoredValue() {
        show(storeScale = false)
        slider().performScrollTo().performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(width / 80f, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(1.2f, 1.25f, 1.3f, 1.35f, 1.4f, 1.45f, 1.5f), events)
        assertEquals("100 %", state(slider()))
    }

    /** The preview line keeps the height of its largest size: nothing under it moves, and the slider stays put. */
    @Test
    fun thePreviewNeverMovesTheSlider() {
        show()
        fun top(n: SemanticsNodeInteraction) = n.fetchSemanticsNode().boundsInRoot.top
        val slider = top(slider().performScrollTo())
        val below = top(rule.onNodeWithText("Behaviour"))
        for (s in listOf(0.85f, 1.5f, 1.2f)) {
            scale = s
            rule.waitForIdle()
            assertEquals("slider at $s", slider, top(slider()))
            assertEquals("next section at $s", below, top(rule.onNodeWithText("Behaviour")))
        }
    }

    /** Each switch row shows its own setting: one on at a time, every other row off. */
    @Test
    fun eachSwitchRowShowsItsOwnSetting() {
        switchedOn = emptySet()
        show()
        val rows = mapOf("dynamic" to "Dynamic color", "pure" to "Pure background", "blur" to "Frosted glass", "enter" to "Enter sends", "haptics" to "Vibration feedback")
        for (on in rows.keys) {
            switchedOn = setOf(on)
            rule.waitForIdle()
            for ((key, text) in rows) {
                row(text).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, if (key == on) ToggleableState.On else ToggleableState.Off))
            }
        }
    }

    private fun focusedSlider() = slider().performScrollTo().also { it.performSemanticsAction(SemanticsActions.RequestFocus) }

    /** Material calls back for End, and for Right, at the end; nothing is written and nothing ticks. */
    @Test
    fun endAtTheEndWritesNothing() {
        scale = 1.5f
        show()
        focusedSlider().performKeyInput { pressKey(Key.MoveEnd) }
        slider().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(emptyList<Any>(), events)
        assertEquals(emptyList<Int>(), calls)
    }

    @Test
    fun oneKeyStepWritesOnce() {
        show()
        focusedSlider().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(listOf<Any>(1.05f), events)
        assertEquals(listOf(selection), calls)
        assertEquals("105 %", state(slider()))
    }

    /** Mirrored: in a right-to-left layout Left raises the size, and End at the end is still a no-op. */
    @Test
    fun keysAreMirroredRightToLeft() {
        direction = LayoutDirection.Rtl
        show()
        focusedSlider().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(listOf<Any>(1.05f), events)
        assertEquals(listOf(selection), calls)
        scale = 1.5f
        rule.waitForIdle()
        slider().performKeyInput { pressKey(Key.MoveEnd) }
        rule.waitForIdle()
        assertEquals(listOf<Any>(1.05f), events)
        assertEquals(listOf(selection), calls)
    }

    @Test
    fun setProgressToTheCurrentValueWritesNothing() {
        show()
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.waitForIdle()
        assertEquals(emptyList<Any>(), events)
        assertEquals(emptyList<Int>(), calls)
    }

    /** A drag past the end of the track and back: one write and one tick per step reached, none while beyond the end. */
    @Test
    fun aDragBeyondTheTrackAndBackWritesOncePerStep() {
        show()
        slider().performScrollTo().performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(width / 40f, 0f)) } // from the middle to half a width past the end
            repeat(20) { moveBy(Offset(-width / 40f, 0f)) } // back to the end of the track
            repeat(19) { moveBy(Offset(-width / 40f, 0f)) } // and back to just short of the middle (1.2)
            up()
        }
        rule.waitForIdle()
        val steps = events.map { it as Float }
        assertEquals("$steps", listOf(1.2f, 1.25f, 1.3f, 1.35f, 1.4f, 1.45f, 1.5f, 1.45f, 1.4f, 1.35f, 1.3f, 1.25f, 1.2f), steps.take(13))
        // Every write is a new step next to the last one: none repeated while beyond the end or on the way back.
        steps.zipWithNext().forEach { (a, b) -> assertEquals("$steps", 0.05f, kotlin.math.abs(a - b), 0.001f) }
        assertEquals(List(events.size) { selection }, calls)
    }

    // ---- the real wiring: SettingsScreen bound to Preferences, as the app shows it ----

    private val prefs by lazy { Preferences(ApplicationProvider.getApplicationContext()) }

    @Composable
    private fun Bound(onBack: () -> Unit = {}) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            CompositionLocalProvider(LocalHaptics provides ViewHaptics({ calls += it; true }, { true })) {
                SettingsScreen(prefs, "https://hermes.example:8642/", "0.1.0", notifications = {}, onConnection = {}, onLicenses = {}, onBack = onBack)
            }
        }

    /** Two taps are two toggles: each toggles from what is shown, and each ticks once. */
    @Test
    fun twoTapsOnASwitchAreTwoToggles() {
        rule.setContent { Bound() }
        row("Enter sends").performClick()
        row("Enter sends").performClick()
        row("Enter sends").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertFalse(prefs.enterToSend.value)
        assertFalse(Preferences(ApplicationProvider.getApplicationContext()).enterToSend.value)
        assertEquals(listOf(selection, selection), calls)
    }

    /** A choice made as Settings is left in the same turn is what the app's flow and storage hold. */
    @Test
    fun aChoiceMadeWhileLeavingIsKept() {
        var inSettings by mutableStateOf(true)
        rule.setContent { if (inSettings) Bound() }
        val tap = row("Enter sends").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            tap()
            inSettings = false // the same turn: no frame between the tap and leaving
        }
        rule.waitForIdle()
        assertTrue(prefs.enterToSend.value)
        assertTrue(Preferences(ApplicationProvider.getApplicationContext()).enterToSend.value)
    }

    /** The longest Polish strings at double font size in a 320 dp window: no text is cut, every control stays 48 dp. */
    @Test
    @Config(qualifiers = "pl-w320dp-h760dp-xhdpi", fontScale = 2f)
    fun polishAtDoubleSizeInANarrowWindowIsNotClipped() {
        show()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), rule.clippedTexts())
        for (text in listOf("Serwer", "Motyw", "Kolory dynamiczne", "Czyste tło", "Matowe szkło", "Enter wysyła", "Wibracje", "Licencje open source")) {
            row(text).assertHeightIsAtLeast(48.dp)
        }
        assertTouchTargetIs48(slider().performScrollTo())
        row("Motyw").performClick()
        rule.waitForIdle()
        assertEquals("theme sheet", emptyList<String>(), rule.clippedTexts())
    }

    // ---- the chat ----

    private val question = UserMessage("u1", "How do I find the largest files?", steered = false, timestamp = FIXED_NOW)
    private val answer = AssistantReply("r2", emptyList(), "The biggest entry is your Downloads folder.", FIXED_NOW)

    private fun chat(live: LiveReply? = null) = ChatUiState(
        "apofis-1", "Largest files", if (live != null) StatusLine.WORKING else StatusLine.IDLE, withLive(listOf(question, answer), live) { FIXED_NOW },
        if (live != null) ComposerMode.STEER else ComposerMode.SEND, "typed", "hermes.example", live,
    )

    private var ui by mutableStateOf(chat())

    private fun showChat() = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            val now = ui
            ChatScreen(now, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, currentState = { now }, chatFontScale = scale)
        }
    }

    private fun layoutIn(tag: String): TextLayoutResult {
        val node = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first()
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    /** Rendered line heights in px (line heights are in em), so a size reached through density counts too. */
    private fun sizes() = listOf("reply:r2/b0/c0", "user:u1", FIELD_TAG, "chat.title").associateWith { layoutIn(it).let { l -> l.getLineBottom(0) - l.getLineTop(0) } }

    @Test
    fun fontScaleAppliesToMessagesOnly() {
        showChat()
        val normal = sizes()
        scale = 1.5f
        rule.waitForIdle()
        val large = sizes()
        assertEquals("the composer keeps its own text size", normal.getValue(FIELD_TAG), large.getValue(FIELD_TAG), 0.01f)
        assertEquals("the title keeps its own text size", normal.getValue("chat.title"), large.getValue("chat.title"), 0.01f)
        assertEquals(normal.getValue("reply:r2/b0/c0") * 1.5f, large.getValue("reply:r2/b0/c0"), 1.5f)
        assertEquals(normal.getValue("user:u1") * 1.5f, large.getValue("user:u1"), 1.5f)
    }

    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    /** A new text size recomposes the transcript once and nothing else; streaming at that size leaves the chrome alone. */
    @Test
    fun fontScaleChangesRecomposeTheTranscriptOnly() {
        rule.mainClock.autoAdvance = false
        ui = chat(LiveReply("run_1", "running", "Streaming", emptyList(), null, Connection.LIVE))
        showChat()
        frames(10)
        val bar = ChromeProbe.barCompositions
        val bottom = ChromeProbe.bottomCompositions
        val root = ChromeProbe.rootMeasures
        val before = layoutIn("reply:r2/b0/c0").layoutInput.style.fontSize.value
        scale = 1.5f
        frames(2)
        assertEquals("the transcript took the new size", before * 1.5f, layoutIn("reply:r2/b0/c0").layoutInput.style.fontSize.value, 0.01f)
        for (i in 1..100) {
            ui = chat(LiveReply("run_1", "running", "Streaming " + "word ".repeat(i), emptyList(), null, Connection.LIVE))
            frames()
        }
        assertEquals("header recomposed", bar, ChromeProbe.barCompositions)
        assertEquals("composer recomposed", bottom, ChromeProbe.bottomCompositions)
        assertEquals("root measured", root, ChromeProbe.rootMeasures)
        assertFalse(rule.onAllNodes(hasTestTag(FIELD_TAG)).fetchSemanticsNodes().isEmpty())
    }
}
