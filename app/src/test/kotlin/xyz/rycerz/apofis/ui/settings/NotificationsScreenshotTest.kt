package xyz.rycerz.apofis.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.notify.NotificationAccess
import xyz.rycerz.apofis.notify.Runtime
import xyz.rycerz.apofis.push.NTFY_PACKAGE
import xyz.rycerz.apofis.push.PushState
import xyz.rycerz.apofis.push.PushUi
import xyz.rycerz.apofis.push.TestPushResult
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.windowShot

private val ALLOWED = NotificationAccess(Runtime.GRANTED, appEnabled = true, repliesEnabled = true, answersEnabled = true)
private val ON = PushState.On(NTFY_PACKAGE)

/** Same device and SDK as ScreenshotTest, so these goldens are byte-stable with the others. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class NotificationsScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Settings(push: PushState) = SettingsScreen(
        serverUrl = "https://hermes.example:8642/",
        themeMode = ThemeMode.SYSTEM,
        dynamicColor = true,
        pureBlack = false,
        blur = false,
        chatFontScale = 1f,
        enterToSend = false,
        haptics = true,
        versionName = "0.1.0",
        notifications = { NotificationsRow(NotificationsUi(ALLOWED, PushUi(push), null)) {} },
        onConnection = {},
        onThemeMode = {},
        onDynamicColor = {},
        onPureBlack = {},
        onBlur = {},
        onChatFontScale = {},
        onEnterToSend = {},
        onHaptics = {},
        onLicenses = {},
        onBack = {},
        scrollState = rememberScrollState(Int.MAX_VALUE), // scrolled to the end: the Notifications and About cards
    )

    @Composable
    private fun OnSheet(test: TestPushResult) =
        NotificationsSheet(NotificationsUi(ALLOWED, PushUi(ON), test), onTest = {}, onRetry = {}, onTurnOff = {}, onSystemSettings = {}, onDismiss = {})

    @Test
    fun settings_notifications_off_light() = rule.shot("settings_notifications_off_light", ThemeMode.LIGHT) { Settings(PushState.Off) }

    @Test
    fun settings_notifications_on_dark() = rule.shot("settings_notifications_on_dark", ThemeMode.DARK) { Settings(ON) }

    @Test
    fun settings_notifications_sheet() =
        rule.windowShot("settings_notifications_sheet", hasText("Test sent, waiting")) { OnSheet(TestPushResult.SENT) }

    @Test
    @Config(qualifiers = "pl-w360dp-h760dp-xhdpi")
    fun settings_notifications_sheet_pl() =
        rule.windowShot("settings_notifications_sheet_pl", hasText("Żaden push nie dotarł")) { OnSheet(TestPushResult.NO_WAKE) }
}
