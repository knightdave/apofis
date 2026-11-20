package xyz.rycerz.apofis.ui

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.connect.ConnectScreen
import xyz.rycerz.apofis.ui.connect.ConnectViewModel
import xyz.rycerz.apofis.ui.settings.SettingsScreen
import xyz.rycerz.apofis.ui.theme.ApofisTheme

// Goldens are recorded on one fixed SDK and device so they are byte-stable; change both only together with a re-record.
// The small threshold absorbs antialiasing noise between machines, not layout changes.
private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ScreenshotTest {
    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png", options) {
            ApofisTheme(mode = mode, dynamicColor = false, pureBlack = false, content = content)
        }

    private fun connectVm() = ConnectViewModel(current = null, willClearCache = { false }, save = {})

    @Test
    fun connect_light() {
        val vm = connectVm()
        shot("connect_light", ThemeMode.LIGHT) { ConnectScreen(vm, onBack = null) }
    }

    @Test
    fun connect_dark() {
        val vm = connectVm().apply { onUrlChange("http://[200:1234::1]:8642") }
        shot("connect_dark", ThemeMode.DARK) { ConnectScreen(vm, onBack = null) }
    }

    @Test
    fun connect_error() = runTest {
        val vm = connectVm()
        vm.onUrlChange("hermes.local:8642")
        vm.onKeyChange("sk-123")
        vm.connect()?.join()
        shot("connect_error", ThemeMode.LIGHT) { ConnectScreen(vm, onBack = null) }
    }

    @Composable
    private fun Settings() = SettingsScreen(
        serverUrl = "https://hermes.example:8642/",
        themeMode = ThemeMode.SYSTEM,
        dynamicColor = true,
        pureBlack = false,
        versionName = "0.1.0",
        onConnection = {},
        onThemeMode = {},
        onDynamicColor = {},
        onPureBlack = {},
        onBack = {},
    )

    @Test
    fun settings_light() = shot("settings_light", ThemeMode.LIGHT) { Settings() }

    @Test
    fun settings_dark() = shot("settings_dark", ThemeMode.DARK) { Settings() }
}
