package xyz.rycerz.apofis.ui.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.notify.NotificationAccess
import xyz.rycerz.apofis.notify.Runtime
import xyz.rycerz.apofis.notify.ensureChannels
import xyz.rycerz.apofis.push.FakePort
import xyz.rycerz.apofis.push.FakePrefs
import xyz.rycerz.apofis.push.FakeRecordApi
import xyz.rycerz.apofis.push.FakeServer
import xyz.rycerz.apofis.push.Keys
import xyz.rycerz.apofis.push.NTFY_ORIGIN
import xyz.rycerz.apofis.push.NTFY_PACKAGE
import xyz.rycerz.apofis.push.PushRegistrar
import xyz.rycerz.apofis.push.PushState
import xyz.rycerz.apofis.push.PushUi
import xyz.rycerz.apofis.push.TEST_WINDOW_MS
import xyz.rycerz.apofis.push.TestPolicy
import xyz.rycerz.apofis.push.TestPush
import xyz.rycerz.apofis.push.TestPushResult
import xyz.rycerz.apofis.push.VALID
import xyz.rycerz.apofis.push.registrationOf
import xyz.rycerz.apofis.push.validateEndpoint
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Haptics
import xyz.rycerz.apofis.ui.theme.LocalHaptics

private const val WATCHDOG_MS = 10_000L // real-time watchdog only: every wait ends on a state, not on time

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class NotificationsRowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val notifyPrefs = app.getSharedPreferences("notifications-row-test", Context.MODE_PRIVATE)

    // The push world, as in PushRegistrarTest: real registrar, fake connector and server.
    private val server = FakeServer("https://a.example/")
    private val api = FakeRecordApi(server)
    private val port = FakePort()
    private val prefs = FakePrefs(mutableMapOf(Keys.INSTALL_ID to "0b0e9c1e-2f4a-4c55-9d1e-7a3b5c6d7e8f"))
    private var connection = 0L
    private var epoch = 0L
    private var now = 1_000_000_000L
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registrar by lazy { PushRegistrar(app, prefs, { api }, { connection }, { now }, background, TestPolicy(), port, { epoch }) }

    // The commands the UI starts: Unconfined, so a command is admitted inside the tap; joined through [commands].
    private val commands = SupervisorJob()
    private val uiScope = CoroutineScope(commands + Dispatchers.Unconfined)

    // The test push, with an injected clock and a transport that never leaves the process.
    private var elapsed = 5_000_000L
    private var answer = 200
    private val sent = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        sent += chain.request().url.toString()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(answer).message("x").body("".toResponseBody()).build()
    }.build()
    private val testPush = TestPush({ elapsed }, client) { validateEndpoint(it, setOf(NTFY_ORIGIN), null) }

    private var haptics = 0
    private val counting = object : Haptics {
        override fun light() { haptics++ }
        override fun selection() { haptics++ }
    }

    @Before
    fun setUp() {
        notifyPrefs.edit().clear().commit()
        ensureChannels(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        background.cancel()
        uiScope.cancel()
        assertTrue("never a notification", shadowOf(nm).allNotifications.isEmpty())
        assertTrue("nothing about the endpoint is logged", ShadowLog.getLogs().none { "upAbC123xyz" in it.msg || "tok-" in it.msg })
    }

    // ---- helpers ----

    private fun themed(content: @Composable () -> Unit) = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            CompositionLocalProvider(LocalHaptics provides counting, content = content)
        }
    }

    private fun setting() = themed { NotificationsSetting(registrar, testPush, notifyPrefs, uiScope) }

    private fun text(id: Int) = app.getString(id)

    private fun waitFor(what: String) = rule.waitUntil(WATCHDOG_MS) { rule.onAllNodes(hasText(what, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun joinCommands() = runBlocking { commands.children.forEach { it.join() } }

    private fun endpointArrives() {
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, VALID).putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit())
    }

    private fun turnedOn() = runBlocking {
        registrar.enableNow()
        endpointArrives()
        registrar.publishPending()
        assertEquals(PushState.On(NTFY_PACKAGE), registrar.ui.value.state)
    }

    private fun serverError() = runBlocking {
        registrar.enableNow()
        endpointArrives()
        api.sendFails = true
        registrar.publishPending()
        api.sendFails = false
        assertEquals(PushState.ServerError(NTFY_PACKAGE), registrar.ui.value.state)
    }

    /** The user has seen the system dialog once and declined it. */
    private fun denied() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifyPrefs.edit().putBoolean("permission_asked", true).commit()
    }

    private fun nextStarted(): Intent? = shadowOf(rule.activity).nextStartedActivity

    private fun allText(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun turnOffViaDialog() {
        rule.onNodeWithText(text(R.string.settings_notif_turn_off)).performScrollTo().performClick()
        rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off_text))).fetchSemanticsNodes().single()
        // The dialog's confirm button is the second node with the action's label (the first is the sheet row).
        rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off)))[1].performClick()
    }

    // ---- the table ----

    private fun ui(
        push: PushState,
        runtime: Runtime = Runtime.GRANTED,
        app: Boolean = true,
        replies: Boolean = true,
        answers: Boolean = true,
        test: TestPushResult? = null,
    ) = NotificationsUi(NotificationAccess(runtime, app, replies, answers), PushUi(push), test)

    private val on = PushState.On(NTFY_PACKAGE)
    private val error = PushState.ServerError(NTFY_PACKAGE)

    @Test
    fun valueAndTapPerCondition() {
        data class Case(val ui: NotificationsUi, val value: Int, val tap: RowTap, val actions: List<SheetAction>, val summary: Int?)
        val fallback = R.string.settings_notif_fallback
        val cases = listOf(
            Case(ui(on, runtime = Runtime.NEVER_ASKED), R.string.settings_notif_blocked, RowTap.ASK, listOf(SheetAction.SYSTEM_SETTINGS, SheetAction.TURN_OFF), null),
            Case(ui(PushState.Off, runtime = Runtime.DENIED), R.string.settings_notif_blocked, RowTap.SYSTEM_SETTINGS, listOf(SheetAction.SYSTEM_SETTINGS), fallback),
            Case(ui(on, runtime = Runtime.DENIED), R.string.settings_notif_blocked, RowTap.SHEET, listOf(SheetAction.SYSTEM_SETTINGS, SheetAction.TURN_OFF), null),
            Case(ui(PushState.Off, app = false), R.string.settings_notif_blocked, RowTap.SYSTEM_SETTINGS, listOf(SheetAction.SYSTEM_SETTINGS), fallback),
            Case(ui(PushState.Registering, app = false), R.string.settings_notif_blocked, RowTap.SHEET, listOf(SheetAction.SYSTEM_SETTINGS, SheetAction.TURN_OFF), null),
            Case(ui(PushState.Off, answers = false), R.string.settings_notif_channel_blocked, RowTap.SHEET, listOf(SheetAction.SYSTEM_SETTINGS), fallback),
            Case(ui(on, replies = false), R.string.settings_notif_channel_blocked, RowTap.SHEET, listOf(SheetAction.SYSTEM_SETTINGS, SheetAction.TURN_OFF), null),
            Case(ui(PushState.NoDistributor), R.string.settings_notif_no_distributor, RowTap.GET_NTFY, emptyList(), fallback),
            Case(ui(PushState.Off), R.string.settings_notif_off, RowTap.ENABLE, emptyList(), fallback),
            Case(ui(PushState.Registering), R.string.settings_notif_registering, RowTap.SHEET, listOf(SheetAction.TURN_OFF), null),
            Case(ui(error), R.string.settings_notif_server_error, RowTap.SHEET, listOf(SheetAction.RETRY, SheetAction.TURN_OFF), null),
            Case(ui(on), R.string.settings_notif_on, RowTap.SHEET, listOf(SheetAction.TEST, SheetAction.TURN_OFF), null),
            // API 31 has no runtime permission: NOT_NEEDED is never "blocked".
            Case(ui(on, runtime = Runtime.NOT_NEEDED), R.string.settings_notif_on, RowTap.SHEET, listOf(SheetAction.TEST, SheetAction.TURN_OFF), null),
            Case(ui(PushState.Off, runtime = Runtime.NOT_NEEDED), R.string.settings_notif_off, RowTap.ENABLE, emptyList(), fallback),
        )
        for (c in cases) {
            assertEquals("value of $c", text(c.value), text(valueOf(c.ui)))
            assertEquals("tap of $c", c.tap, tapOf(c.ui))
            assertEquals("sheet of $c", c.actions, actionsOf(c.ui))
            assertEquals("summary of $c", c.summary, summaryOf(c.ui))
        }
    }

    @Test
    fun offTapTurnsPushOn() {
        setting()
        rule.onNodeWithText(text(R.string.settings_notif_off), substring = true).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        waitFor(text(R.string.settings_notif_registering))
        assertEquals(listOf("save $NTFY_PACKAGE", "register g1"), port.calls)
        assertNull("no sheet for Off", rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off))).fetchSemanticsNodes().firstOrNull())
    }

    @Test
    fun noDistributorTapOpensTheNtfyPage() {
        port.installed.clear()
        setting()
        rule.onNodeWithText(text(R.string.settings_notif_no_distributor), substring = true).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        val intent = nextStarted()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://f-droid.org/packages/io.heckel.ntfy", intent.dataString)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun deniedWithPushOffOpensTheSystemSettings() {
        denied()
        setting()
        rule.onNodeWithText(text(R.string.settings_notif_blocked), substring = true).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        val intent = nextStarted()!!
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun permissionStatesOnApi33AndLater() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) // never asked
        setting()
        rule.onNodeWithText(text(R.string.settings_notif_blocked), substring = true).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick() // an explicit user action asks
        rule.waitUntil(WATCHDOG_MS) { shadowOf(rule.activity).lastRequestedPermission != null }
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), shadowOf(rule.activity).lastRequestedPermission.requestedPermissions.toList())
        assertNull("the ask is not a settings page", nextStarted())
        assertTrue(port.calls.isEmpty())
    }

    @Test
    @Config(sdk = [31])
    fun permissionStatesOnApi31() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) // ignored below 33
        setting()
        rule.onNodeWithText(text(R.string.settings_notif_off), substring = true).assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText(text(R.string.settings_notif_blocked), substring = true)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        waitFor(text(R.string.settings_notif_registering))
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun turnOffReachableWhenBlocked() {
        turnedOn()
        denied()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_system)).assertIsDisplayed()
        assertNull("the sheet, not the settings page", nextStarted())
        turnOffViaDialog()
        joinCommands()
        waitFor(text(R.string.settings_notif_fallback)) // still "blocked" first: access is shown before the push state
        assertEquals(PushState.Off, registrar.ui.value.state)
        assertTrue(port.calls.any { it.startsWith("unregister g1") })
        assertTrue(rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off))).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(text(R.string.settings_notif_system)).performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, nextStarted()!!.action)
    }

    @Test
    fun retryNowBypassesThrottle() {
        turnedOn()
        // A failed check leaves a fresh proof and the error flag: an hourly (unforced) verification would not run now.
        api.failWith = ApiError.Http(500, null)
        runBlocking { registrar.verify(force = true) }
        api.failWith = null
        assertEquals(error, registrar.ui.value.state)
        assertTrue(prefs.contains(Keys.VERIFIED_AT))
        api.calls.clear()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_retry)).performClick()
        joinCommands()
        waitFor(text(R.string.settings_notif_on))
        assertTrue("the server was asked again", api.calls.isNotEmpty())
        assertEquals(on, registrar.ui.value.state)
    }

    @Test
    fun sentArrivedOrNone() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        val send = rule.onNodeWithText(text(R.string.settings_notif_test))

        send.performClick()
        joinCommands()
        waitFor(text(R.string.settings_notif_test_sent))
        assertEquals(listOf(VALID), sent)
        testPush.onWake() // what the receiver does for a delivered message
        waitFor(text(R.string.settings_notif_test_received))

        elapsed += 10_000
        send.performClick()
        joinCommands()
        waitFor(text(R.string.settings_notif_test_sent))
        elapsed += TEST_WINDOW_MS // the window ends; the open sheet closes it on its next tick
        rule.mainClock.advanceTimeBy(1_100)
        waitFor(text(R.string.settings_notif_test_none))

        elapsed += 10_000
        answer = 500
        send.performClick()
        joinCommands()
        waitFor(text(R.string.settings_notif_test_failed))
        assertEquals(3, sent.size)
    }

    @Test
    fun refreshOnResume() {
        setting()
        rule.onNodeWithText(text(R.string.settings_notif_off), substring = true).assertIsDisplayed()

        port.installed.clear() // ntfy uninstalled while the app was away
        rule.onNodeWithText(text(R.string.settings_notif_off), substring = true).assertIsDisplayed()
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor(text(R.string.settings_notif_no_distributor))

        shadowOf(nm).setNotificationsEnabled(false) // blocked in system settings meanwhile
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor(text(R.string.settings_notif_blocked))
    }

    @Test
    fun oneFeedbackPerTap() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, haptics)
        rule.onNodeWithText(text(R.string.settings_notif_test)).assertHeightIsAtLeast(48.dp).performClick()
        joinCommands()
        assertEquals(2, haptics)
        rule.onNodeWithText(text(R.string.settings_notif_turn_off)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(3, haptics)
        rule.onNodeWithText(text(R.string.cancel)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(4, haptics)
        turnOffViaDialog()
        assertEquals(6, haptics)
        joinCommands()
        waitFor(text(R.string.settings_notif_off))
        assertEquals(6, haptics)
    }

    @Test
    fun sheetNeverShowsEndpoint() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick()
        joinCommands()
        waitFor(text(R.string.settings_notif_test_sent))
        val shown = allText()
        assertTrue(shown.isNotEmpty())
        val token = registrationOf(prefs)!!.token!!
        val record = prefs.getString(Keys.RECORD, null)!!
        for (secret in listOf(VALID, "upAbC123xyz_-9", "ntfy.sh", token, record)) {
            assertTrue("$secret is never shown", shown.none { secret in it })
        }
    }

    @Test
    fun offDuringAConnectionSwitchShowsTheRegistrarState() {
        turnedOn()
        // A running operation holds the registrar: the next command waits behind it.
        val inCall = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        api.beforeCall = { inCall.complete(Unit); gate.await() }
        val running = background.launch { registrar.verify(force = true) }
        runBlocking { inCall.await() }

        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        turnOffViaDialog() // Off is admitted under the current connection and queued

        // A server switch starts meanwhile (AppContainer.connect): its preparation, then the connection moves on.
        val switch: Job = background.launch { registrar.prepareSwitch() }
        connection++
        epoch++
        api.beforeCall = {}
        gate.complete(Unit)
        runBlocking { running.join(); switch.join() }
        joinCommands()
        runBlocking { registrar.reconcileLocal() } // the local job after the switch registers for the new connection

        rule.waitForIdle()
        val state = registrar.ui.value.state
        assertNotEquals("the queued Off belonged to the old connection", PushState.Off, state)
        assertTrue(prefs.getBoolean(Keys.ENABLED, false))
        waitFor(text(R.string.settings_notif_registering))
        assertFalse("never a false Off", allText().flatMap { it.lines() }.any { it == text(R.string.settings_notif_off) })
        rule.onNodeWithText(text(R.string.settings_notif_turn_off)).assertIsDisplayed() // still offered

        turnOffViaDialog() // pressed again, now under the new connection
        joinCommands()
        waitFor(text(R.string.settings_notif_off))
        assertEquals(PushState.Off, registrar.ui.value.state)
    }

    @Test
    @Config(qualifiers = "pl-w320dp-h420dp-xhdpi")
    fun shortWindowLargeFontLongestPolish() {
        RuntimeEnvironment.setFontScale(2f)
        themed {
            NotificationsSheet(ui(on, test = TestPushResult.NO_WAKE), onTest = {}, onRetry = {}, onTurnOff = {}, onSystemSettings = {}, onDismiss = {})
        }
        // Taller than half the window, the sheet opens half way, as every sheet does: the user drags it up.
        rule.onNodeWithText("Powiadomienia").performTouchInput { swipeUp() }
        rule.onNodeWithText("Nie dotarło żadne powiadomienie push").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Wyślij testowe powiadomienie").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Wyłącz push").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.onAllNodes(hasText("Wyłącz push"))[1].assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Anuluj").assertIsDisplayed()
    }
}
