package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.ui.shot

/** The composer alone over conversation text (fill-only glass, as in production). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ComposerScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun OverText(draft: String, mode: ComposerMode, expanded: Boolean = false, expandedHeight: Dp = Dp.Unspecified) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            Text(
                "The composer floats over the end of the conversation. Without blur its glass keeps a page backplate, so " +
                    "the text below it does not show through and the draft stays readable.",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Composer(draft, mode, {}, {}, {}, Modifier.align(Alignment.BottomCenter), expanded = expanded, expandedHeight = expandedHeight)
        }
    }

    @Test
    fun composer_multiline() = rule.shot("composer_multiline") {
        OverText("Find the largest files in my home folder\nand list them by size\nskipping caches", ComposerMode.SEND)
    }

    @Test
    fun composer_expanded() = rule.shot("composer_expanded") {
        OverText("A longer draft, written in the expanded editor.\nSecond line.", ComposerMode.SEND, expanded = true, expandedHeight = 400.dp)
    }

    @Test
    fun composer_steer() = rule.shot("composer_steer") {
        OverText("Also skip the Downloads folder", ComposerMode.STEER)
    }
}
