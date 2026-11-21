package xyz.rycerz.apofis.ui.drawer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
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
class SessionActionsSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private var prompt by mutableStateOf<Prompt?>(Prompt(PromptKind.ACTIONS, "apofis-1", "Garden", pinned = false, archived = false, running = false, token = 1))
    private val deletes = ArrayList<Long>()

    /** The prompt rules of [DrawerViewModel]: dismiss is ignored while an action runs, a failure shows in the prompt. */
    private val actions = object : PromptActions {
        override fun pin(token: Long, pinned: Boolean) {
            prompt = prompt?.copy(busy = true, error = null)
        }
        override fun dismiss(token: Long) {
            if (prompt?.token == token && prompt?.busy == false) prompt = null
        }
        override fun openDelete(token: Long) {
            deletes += token
        }
    }

    private fun host() = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            prompt?.let { PromptHost(it, actions) }
        }
    }

    @Test
    fun aBusySheetStaysOpenSoItsErrorIsSeen() {
        host()
        rule.onNodeWithText("Pin").performClick()
        val roots = rule.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].performTouchInput { click(Offset(centerX, 10f)) } // the scrim
        rule.waitForIdle()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        prompt = prompt?.copy(busy = false, error = ActionError.NETWORK) // the pin fails
        rule.waitForIdle()
        rule.onNodeWithText("Can't reach server").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h220dp-xhdpi")
    fun inAShortWindowTheLastActionCanBeReached() {
        host()
        rule.onNodeWithText("Delete").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(1L), deletes)
    }
}
