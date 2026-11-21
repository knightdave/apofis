package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ReasoningStep
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.windowShot

/** The timeline card alone on the page color, and the tool detail sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class TimelineScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val du = ToolStep(
        "c1", "terminal", "command=du -ah ~ | sort -rh | head", "4.0G  /home/user/Downloads", false, error = false, running = false,
        durationSeconds = 1.3, args = "{\n  \"command\": \"du -ah ~ | sort -rh | head\"\n}",
    )
    private val reply = AssistantReply(
        "r2",
        listOf(
            ReasoningStep("The user wants the largest files.\nA du listing sorted by size answers that, limited to the top ten."),
            du,
            ToolStep("c2", "read_file", "path=~/Downloads/README.md", "# Downloads", false, error = false, running = false, durationSeconds = 0.4),
            ToolStep("c3", "terminal", "command=du -sh /root", "du: cannot read directory", false, error = true, running = false, durationSeconds = 0.1),
            ToolStep("c4", "terminal", "command=find ~ -size +1G", null, false, error = false, running = true),
        ),
        "",
        FIXED_NOW,
    )

    private fun card(name: String, expanded: Boolean = false, open: Set<Int> = emptySet()) = rule.shot(name) {
        Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
            TimelineCard(reply, "step:r2", onStep = {}, initiallyExpanded = expanded, initiallyOpenReasoning = open)
        }
    }

    @Test
    fun timeline_collapsed() = card("timeline_collapsed")

    @Test
    fun timeline_expanded() = card("timeline_expanded", expanded = true)

    @Test
    fun timeline_reasoning_open() = card("timeline_reasoning_open", expanded = true, open = setOf(0))

    @Test
    fun tool_detail_sheet() = rule.windowShot("tool_detail_sheet", hasText("Arguments")) { ToolDetailSheet(du, live = false, onDismiss = {}) }
}
