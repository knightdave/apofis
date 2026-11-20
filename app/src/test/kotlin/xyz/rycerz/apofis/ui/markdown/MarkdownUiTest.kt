package xyz.rycerz.apofis.ui.markdown

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneOffset
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.ui.chat.ChatRows
import xyz.rycerz.apofis.ui.chat.TextBlock
import xyz.rycerz.apofis.ui.chat.TextRow
import xyz.rycerz.apofis.ui.chat.copySource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.test.runTest
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class MarkdownUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val frames = 20 * 16L

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

    private fun same(a: ImageBitmap, b: ImageBitmap) = a.toPixelMap().buffer.contentEquals(b.toPixelMap().buffer)

    @Test
    fun linksShowPressAndKeyboardFocus() {
        rule.mainClock.autoAdvance = false
        val opened = ArrayList<String>()
        lateinit var inputMode: InputModeManager
        val chunk = DisplayChunk.Blocks(parseMarkdown("before [first](https://a.b) and [second](https://c.d) after"))
        rule.setContent {
            Themed {
                inputMode = LocalInputModeManager.current
                MarkdownChunk(chunk, { opened += it }, Modifier.testTag("md"))
            }
        }
        rule.mainClock.advanceTimeBy(frames)
        val md = rule.onNodeWithTag("md")
        val idle = md.captureToImage()
        val links = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
        assertEquals(2, links.fetchSemanticsNodes().size)

        links[0].performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(frames)
        val pressed = md.captureToImage()
        links[0].performTouchInput { up() }
        rule.mainClock.advanceTimeBy(frames)
        assertEquals(listOf("https://a.b"), opened)
        assertTrue("released link still looks pressed", same(idle, md.captureToImage()))

        rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
        links[0].requestFocus()
        rule.mainClock.advanceTimeBy(frames)
        val firstFocused = md.captureToImage()
        links[1].requestFocus()
        rule.mainClock.advanceTimeBy(frames)
        val secondFocused = md.captureToImage()

        assertFalse("pressed link looks idle", same(idle, pressed))
        assertFalse("focused link looks idle", same(idle, firstFocused))
        assertFalse("which link has focus is not visible", same(firstFocused, secondFocused))
        assertEquals(listOf("https://a.b"), opened) // focus alone opens nothing
    }

    @Test
    fun taskItemsExposeReadOnlyCheckedState() {
        val chunk = DisplayChunk.Blocks(parseMarkdown("- [ ] open\n- [x] done"))
        rule.setContent { Themed { MarkdownChunk(chunk, {}) } }
        fun state(value: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, value)
        assertEquals(1, rule.onAllNodes(state(ToggleableState.Off) and hasText("open")).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodes(state(ToggleableState.On) and hasText("done")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun copyButtonsCopyTheWholeBlockAndShowPressAndFocus() {
        rule.mainClock.autoAdvance = false
        lateinit var inputMode: InputModeManager
        val body = (1..300).joinToString("\n") { "val v$it = \"s$it\" // $it" }
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        val items = listOf(AssistantReply("r1", emptyList(), "```kotlin\n$body\n```\n\n$table", 0.0))
        val rows = ChatRows(ZoneOffset.UTC).rows(items).filterIsInstance<TextRow>()
        val code = rows.first { it.kind == BlockKind.CODE }
        assertTrue(rows.count { it.kind == BlockKind.CODE } > 1) // the block is split, Copy still takes all of it
        val tableRow = rows.first { it.kind == BlockKind.TABLE }
        rule.setContent {
            Themed {
                inputMode = LocalInputModeManager.current
                Column {
                    TextBlock(tableRow, source = { copySource(items, tableRow.key) })
                    TextBlock(code, source = { copySource(items, code.key) }) // only its header needs to be on screen
                }
            }
        }
        rule.mainClock.advanceTimeBy(frames)
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        for ((description, expected) in listOf("Copy code" to body, "Copy table" to table)) {
            val button = rule.onNodeWithContentDescription(description)
            button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assert(hasClickAction())
            val idle = button.captureToImage()
            button.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(frames)
            assertFalse("$description shows no press", same(idle, button.captureToImage()))
            button.performTouchInput { up() }
            rule.mainClock.advanceTimeBy(frames)
            rule.waitForIdle()
            assertEquals(description, expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertTrue("$description still looks pressed", same(idle, button.captureToImage()))
            rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
            button.requestFocus()
            rule.mainClock.advanceTimeBy(frames)
            assertFalse("$description shows no focus", same(idle, button.captureToImage()))
            rule.runOnUiThread { inputMode.requestInputMode(InputMode.Touch) }
        }
    }

    // ---- fix round 1: Copy within the clipboard's limits, with a message ----

    /** A clipboard that records what is put on it, or throws like a rejected Binder transaction. */
    private class FakeClipboard(val fail: Boolean = false) : Clipboard {
        var clip: String? = "previous"
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            if (fail) throw RuntimeException("android.os.TransactionTooLargeException")
            clip = clipEntry?.clipData?.getItemAt(0)?.text?.toString()
        }
        override val nativeClipboard get() = throw UnsupportedOperationException()
    }

    @Test
    fun copyRefusesWhatTheClipboardCannotCarry() = runTest {
        val ok = FakeClipboard()
        assertEquals(CopyResult.COPIED, copyText(ok, "x".repeat(MAX_CLIP_CHARS)))
        assertEquals("x".repeat(MAX_CLIP_CHARS), ok.clip)
        for (big in listOf("x\n".repeat(524_288), "😀".repeat(MAX_CLIP_CHARS / 2 + 1), "ł".repeat(MAX_CLIP_CHARS + 1))) {
            val clipboard = FakeClipboard()
            assertEquals(CopyResult.TOO_LARGE, copyText(clipboard, big))
            assertEquals("never truncated, the old clip stays", "previous", clipboard.clip)
        }
        val failing = FakeClipboard(fail = true)
        assertEquals(CopyResult.FAILED, copyText(failing, "small"))
        assertEquals("previous", failing.clip)
    }

    @Test
    fun copySaysWhatHappened() {
        rule.mainClock.autoAdvance = false
        var clipboard by mutableStateOf(FakeClipboard())
        var source by mutableStateOf("abc")
        val row = ChatRows(ZoneOffset.UTC).rows(listOf(AssistantReply("r1", emptyList(), "```kotlin\nval x = 1\n```", 0.0)))
            .filterIsInstance<TextRow>().single()
        rule.setContent {
            Themed { CompositionLocalProvider(LocalClipboard provides clipboard) { TextBlock(row, source = { source }) } }
        }
        rule.mainClock.advanceTimeBy(frames)
        fun tap(): String? {
            rule.onNodeWithContentDescription("Copy code").performClick()
            rule.mainClock.advanceTimeBy(frames)
            rule.waitForIdle()
            return clipboard.clip
        }
        assertEquals("abc", tap())
        rule.onNodeWithText("Copied").assertExists()
        rule.mainClock.advanceTimeBy(COPY_NOTE_MS)
        rule.onNodeWithText("Copied").assertDoesNotExist()

        source = "x".repeat(MAX_CLIP_CHARS + 1)
        assertEquals("previous", FakeClipboard().also { clipboard = it }.let { tap() })
        rule.onNodeWithText("Too large to copy").assertExists()

        clipboard = FakeClipboard(fail = true)
        source = "abc"
        rule.mainClock.advanceTimeBy(frames)
        assertEquals("previous", tap())
        rule.onNodeWithText("Could not copy").assertExists()
    }
}
