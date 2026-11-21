package xyz.rycerz.apofis.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.settings.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class SheetTest {
    @get:Rule
    val rule = createComposeRule()

    private var open by mutableStateOf(true)
    private var dismissals = 0
    private val calls = mutableListOf<Int>()
    private var clicks = 0

    private fun sheet() = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            CompositionLocalProvider(LocalHaptics provides ViewHaptics({ calls += it; true }, { true })) {
                if (open) {
                    ApofisSheet(onDismiss = { open = false; dismissals++ }) {
                        Text("Sheet body", Modifier.testTag("body"))
                        SheetRow(R.drawable.lucide_pencil, "Rename", onClick = { clicks++ })
                        SheetRow(R.drawable.lucide_trash, "Delete", onClick = { clicks++ }, enabled = false)
                    }
                }
            }
        }
    }

    @Test
    fun rowsGiveOneSelectionPerTap() {
        sheet()
        rule.onNodeWithText("Rename")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, clicks)
        assertEquals(listOf(HapticFeedbackConstants.CLOCK_TICK), calls)
        rule.onNodeWithText("Delete").performClick() // disabled: nothing, no feedback
        assertEquals(1, clicks)
        assertEquals(1, calls.size)
    }

    @Test
    fun dragDismisses() {
        sheet()
        rule.onNodeWithTag("body").performTouchInput { swipeDown(startY = top, endY = bottom + 2000f) }
        rule.waitForIdle()
        assertEquals(1, dismissals)
        assertEquals(0, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "body")).fetchSemanticsNodes().size)
    }

    @Test
    fun scrimTapDismisses() {
        sheet()
        rule.onAllNodes(isRoot()).fetchSemanticsNodes() // the sheet's window is the last root
        rule.onAllNodes(isRoot())[rule.onAllNodes(isRoot()).fetchSemanticsNodes().size - 1].performTouchInput { click(Offset(centerX, 10f)) }
        rule.waitForIdle()
        assertEquals(1, dismissals)
    }

    @Test
    fun backDismisses() {
        sheet()
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(1, dismissals)
    }
}
