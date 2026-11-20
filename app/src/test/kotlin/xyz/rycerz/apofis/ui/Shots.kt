package xyz.rycerz.apofis.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
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

fun shot(name: String, mode: ThemeMode = ThemeMode.LIGHT, seed: SeedSchemes? = null, pureBlack: Boolean = false, content: @Composable () -> Unit) =
    captureRoboImage("src/test/screenshots/$name.png", options) { GoldenTheme(mode, seed, pureBlack, content) }

/** Captures every window, so dialogs and bottom sheets are in the image. */
fun windowShot(name: String, mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    activity.setContent { GoldenTheme(mode, seed = null, pureBlack = false, content = content) }
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)) // sheet and dialog enter animations end
    captureScreenRoboImage("src/test/screenshots/$name.png", options)
}
