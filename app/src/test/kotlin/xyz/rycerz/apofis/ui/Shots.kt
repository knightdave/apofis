package xyz.rycerz.apofis.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.LocalDate
import java.time.ZoneOffset
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.SeedSchemes

// Goldens are recorded on one fixed SDK and device (the test class's @Config) so they are byte-stable; change both
// only together with a re-record. The small threshold absorbs antialiasing noise between machines, not layout changes.
private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

/** The date every golden shows as today; fixtures use timestamps on it, in UTC. */
val FIXED_TODAY: LocalDate = LocalDate.of(2026, 3, 10)

/** 2026-03-10T11:00Z in epoch seconds. */
val FIXED_NOW: Double = FIXED_TODAY.atTime(11, 0).toEpochSecond(ZoneOffset.UTC).toDouble()

/** No motion, no blur; a [seed] turns dynamic color on with that scheme. */
@Composable
private fun GoldenTheme(mode: ThemeMode, seed: SeedSchemes?, pureBlack: Boolean, content: @Composable () -> Unit) =
    ApofisTheme(mode, dynamicColor = seed != null, pureBlack = pureBlack, reduceMotion = true, blur = false, seed = seed, content = content)

/**
 * Captures [content] alone. The rule owns the activity, the composition and the clock: frames run until idle and a frame
 * is drawn before the capture, so a golden never shows content that has not been composed or drawn yet.
 */
fun ComposeContentTestRule.shot(name: String, mode: ThemeMode = ThemeMode.LIGHT, seed: SeedSchemes? = null, pureBlack: Boolean = false, content: @Composable () -> Unit) {
    setContent { GoldenTheme(mode, seed, pureBlack) { Box(Modifier.testTag(GOLDEN)) { content() } } }
    waitForIdle()
    // Roborazzi's own view capture sometimes read nothing Compose had drawn (a page-colored golden, in long runs);
    // Compose's capture waits for a drawn frame, so the golden is that bitmap.
    onNodeWithTag(GOLDEN).captureToImage().asAndroidBitmap().captureRoboImage("src/test/screenshots/$name.png", options)
}

private const val GOLDEN = "golden"

/**
 * Captures every window, so dialogs and bottom sheets are in the image. The rule owns the activity, the composition and
 * the clock: frames run until idle (enter animations end), then [shown], a node only the modal has, must be displayed
 * before the capture, so a modal that is missing or still hidden fails the test instead of becoming a baseline.
 */
fun ComposeContentTestRule.windowShot(name: String, shown: SemanticsMatcher, mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    setContent { GoldenTheme(mode, seed = null, pureBlack = false, content = content) }
    waitForIdle()
    onNode(shown).assertIsDisplayed()
    val roots = onAllNodes(isRoot())
    roots.fetchSemanticsNodes().forEachIndexed { i, root ->
        if (root.size.width > 0 && root.size.height > 0) roots[i].captureToImage() // every window drawn before the capture
    }
    captureScreenRoboImage("src/test/screenshots/$name.png", options)
}
