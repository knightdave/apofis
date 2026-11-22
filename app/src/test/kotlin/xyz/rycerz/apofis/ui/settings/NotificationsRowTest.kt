package xyz.rycerz.apofis.ui.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.room.Room
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.connectionEpoch
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.push.PushPolicy
import xyz.rycerz.apofis.push.ROTATE_AFTER_MS
import xyz.rycerz.apofis.push.Registration
import xyz.rycerz.apofis.push.TestOutcome
import xyz.rycerz.apofis.push.UnifiedPushPort
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import xyz.rycerz.apofis.push.VALID2
import xyz.rycerz.apofis.push.registrationOf
import xyz.rycerz.apofis.push.validateEndpoint
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Haptics
import xyz.rycerz.apofis.ui.theme.LocalHaptics

private const val WATCHDOG_MS = 10_000L // real-time watchdog only: every wait ends on a state, not on time

/** A latch inside a production call: [pass] completes [entered] and blocks until [open]; both bounded by the watchdog. */
private class Gate {
    val entered = CompletableDeferred<Unit>()
    val open = CompletableDeferred<Unit>()
    fun pass() {
        entered.complete(Unit)
        runBlocking { withTimeout(WATCHDOG_MS) { open.await() } }
    }
}

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
    private val apiB = FakeRecordApi(FakeServer("https://b.example/"))
    private var current: FakeRecordApi = api
    private val fakePort = FakePort()
    private val port get() = fakePort

    // Gates a test can arm inside production calls: the connector, the policy and the record API (each fires once).
    private var distributorsGate: Gate? = null
    private var unregisterGate: Gate? = null
    private var policyGate: Gate? = null
    private var apiGate: Gate? = null
    private var postGate: Gate? = null
    private var registerGate: Gate? = null
    private var computed: CompletableDeferred<Unit>? = null // completed by the next calculation of the registrar's state
    private val gatedPort = object : UnifiedPushPort by fakePort {
        override fun distributors(c: Context): List<String> {
            distributorsGate?.let { distributorsGate = null; it.pass() }
            return fakePort.distributors(c)
        }
        override fun unregister(c: Context, r: Registration, connector: Boolean) {
            unregisterGate?.let { unregisterGate = null; it.pass() }
            fakePort.unregister(c, r, connector)
        }
        override fun register(c: Context, instance: String, journal: (String, String) -> Unit) {
            registerGate?.let { registerGate = null; it.pass() }
            fakePort.register(c, instance, journal)
        }
    }
    private val gatedPolicy = object : PushPolicy by TestPolicy() {
        override fun distributors(c: Context): Set<String> {
            policyGate?.let { policyGate = null; it.pass() }
            computed?.complete(Unit)
            return setOf(NTFY_PACKAGE)
        }
    }
    private val prefs = FakePrefs(mutableMapOf(Keys.INSTALL_ID to "0b0e9c1e-2f4a-4c55-9d1e-7a3b5c6d7e8f"))
    private var connection = 0L
    private var epoch = 0L
    private var now = 1_000_000_000L
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registrar by lazy {
        PushRegistrar(app, prefs, { apiGate?.let { apiGate = null; it.pass() }; current }, { connection }, { now }, background, gatedPolicy, gatedPort, { epoch })
    }

    // The commands the UI starts: Unconfined, so a command is admitted inside the tap; joined through [commands].
    private val commands = SupervisorJob()
    private val uiScope = CoroutineScope(commands + Dispatchers.Unconfined)

    // A held dispatcher, as Dispatchers.Default can be: nothing of a command runs until the test runs its dispatches.
    private val held = LinkedBlockingQueue<Runnable>()
    private val heldScope = CoroutineScope(commands + Executor { held.put(it) }.asCoroutineDispatcher())

    // The container's one command holder, over each scope.
    private val uiCommands by lazy { PushCommands(uiScope) }
    private val heldCommands by lazy { PushCommands(heldScope) }

    /** Runs the held dispatches on this (the main) thread until [done]; fails instead of hanging when none comes. */
    private fun runHeld(done: () -> Boolean) {
        while (!done()) (held.poll(WATCHDOG_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("no dispatch came")).run()
    }

    private fun commandsDone() = commands.children.none { it.isActive }

    // The test push, with an injected clock and a transport that never leaves the process.
    private var elapsed = 5_000_000L
    private var answer = 200
    private val sent = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        sent += chain.request().url.toString()
        postGate?.let { postGate = null; it.pass() }
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
        // PublishWorker.recover (called by the registrar and by AppContainer.connect) needs WorkManager; nothing runs its work.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder()
            .setTaskExecutor(androidx.work.testing.SynchronousExecutor()).setExecutor { }.build())
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

    /** The setting, once it has resolved the registrar and read the access off the main thread. */
    private fun setting(commands: PushCommands = uiCommands, push: () -> PushRegistrar = { registrar }, prefs: SharedPreferences = notifyPrefs) {
        themed { NotificationsSetting(push, { testPush }, prefs, commands) }
        waitReady()
    }

    private fun waitReady() =
        rule.waitUntil(WATCHDOG_MS) { rule.onAllNodes(hasText(text(R.string.settings_notifications)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

    /** Nodes with [what] inside the sheet's own window. */
    private fun inSheet(what: String) =
        rule.onAllNodes(hasText(what, substring = true) and hasAnyAncestor(hasTestTag(SHEET_TAG)), useUnmergedTree = true).fetchSemanticsNodes()

    private fun waitInSheet(what: String) = rule.waitUntil(WATCHDOG_MS) { inSheet(what).isNotEmpty() }

    private fun await(d: CompletableDeferred<Unit>) = runBlocking { withTimeout(WATCHDOG_MS) { d.await() } }

    /** To the record API B on a new connection, in AppContainer.connect's order. */
    private fun switchToB() = runBlocking {
        withTimeout(WATCHDOG_MS) {
            registrar.prepareSwitch() // returns before anything else moves
            connection++ // the generation fence
            epoch++ // with the switch marker
            current = apiB
            registrar.reconcileLocal() // local recovery: the old registration unregistered, a new one registered
        }
    }

    private fun subscribed(instance: String) = "tok-$instance" in fakePort.subscribed

    private fun text(id: Int) = app.getString(id)

    private fun waitFor(what: String) = rule.waitUntil(WATCHDOG_MS) { rule.onAllNodes(hasText(what, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun joinCommands() = runBlocking { commands.children.forEach { it.join() } }

    private fun endpointArrives(url: String = VALID) {
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, url).putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit())
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
        val off = R.drawable.lucide_bell_off
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
            // Both a runtime block and a blocked channel: the runtime block is shown and acted on.
            Case(ui(PushState.Off, runtime = Runtime.DENIED, answers = false), R.string.settings_notif_blocked, RowTap.SYSTEM_SETTINGS, listOf(SheetAction.SYSTEM_SETTINGS), fallback),
        )
        for (c in cases) {
            assertEquals("value of $c", text(c.value), text(valueOf(c.ui)))
            assertEquals("tap of $c", c.tap, tapOf(c.ui))
            assertEquals("sheet of $c", c.actions, actionsOf(c.ui))
            assertEquals("summary of $c", c.summary, summaryOf(c.ui))
            val bell = c.ui.push.state is PushState.On && c.ui.access.runtime.let { it == Runtime.GRANTED || it == Runtime.NOT_NEEDED } &&
                c.ui.access.appEnabled && c.ui.access.repliesEnabled && c.ui.access.answersEnabled
            assertEquals("icon of $c", if (bell) R.drawable.lucide_bell else off, iconOf(c.ui))
        }
        assertEquals(R.drawable.lucide_bell, iconOf(ui(on)))
        assertEquals(off, iconOf(ui(on, runtime = Runtime.DENIED)))
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
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertNull("never asked by itself", shadowOf(rule.activity).lastRequestedPermission)
        assertFalse(notifyPrefs.contains("permission_asked"))
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick() // an explicit user action asks
        rule.waitUntil(WATCHDOG_MS) { shadowOf(rule.activity).lastRequestedPermission != null }
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), shadowOf(rule.activity).lastRequestedPermission.requestedPermissions.toList())
        assertNotEquals("the ask is not a settings page", Settings.ACTION_APP_NOTIFICATION_SETTINGS, nextStarted()?.action)
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
        assertTrue("applied: no failure line", inSheet(text(R.string.settings_notif_turn_off_failed)).isEmpty())
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
        testPush.onWake(registrar.ui.value.testOwner!!) // what the receiver does for a delivered message of this registration
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

    // ---- Fix round 1 ----

    @Test
    fun beforeTheFirstReadTheRowIsNeutral() {
        val resolving = CompletableDeferred<Unit>()
        themed { NotificationsSetting({ await(resolving); registrar }, { testPush }, notifyPrefs, uiCommands) }
        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.settings_notifications)).assertIsDisplayed().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        assertTrue("no Off placeholder", rule.onAllNodes(hasText(text(R.string.settings_notif_off), substring = true)).fetchSemanticsNodes().isEmpty())
        resolving.complete(Unit)
        waitFor(text(R.string.settings_notif_off))
        rule.onNode(hasText(text(R.string.settings_notifications)) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    /** The registrar's own lock, read once to show that a paused refresh holds it. */
    private fun registrarLocked(): Boolean =
        (PushRegistrar::class.java.getDeclaredField("mutex").apply { isAccessible = true }.get(registrar) as Mutex).isLocked

    /** Every state the registrar published, with whether the distributor held a subscription at that moment. */
    private fun watchPublications(): MutableList<Pair<PushUi, Boolean>> {
        val seen = Collections.synchronizedList(mutableListOf<Pair<PushUi, Boolean>>())
        background.launch(Dispatchers.Unconfined) { registrar.ui.collect { seen += it to fakePort.subscribed.isNotEmpty() } }
        return seen
    }

    @Test
    fun aPausedRefreshCannotPublishOffOverAnEnable() {
        val r = registrar
        val seen = watchPublications()
        epoch = 7 // behind the registrar's back: the refresh's result differs from the state it replaces (its owner)
        val paused = Gate().also { distributorsGate = it }
        val refresh = background.launch { r.refreshNow() }
        await(paused.entered) // inside its calculation
        assertTrue("the paused refresh holds the registrar's lock", registrarLocked())
        val enable = background.launch { r.enableNow() }
        paused.open.complete(Unit)
        runBlocking { withTimeout(WATCHDOG_MS) { refresh.join(); enable.join() } }
        assertTrue(subscribed("g1"))
        assertEquals(PushState.Registering, r.ui.value.state)
        assertTrue("never Off while subscribed: $seen", seen.none { it.first.state == PushState.Off && it.second })
        // Published in order: the state before, the paused refresh's result, then the enable's, which is the last.
        assertEquals(listOf(PushUi(PushState.Off, "0:g0"), PushUi(PushState.Off, "7:g0"), PushUi(PushState.Registering, "7:g1")), seen.map { it.first })
    }

    @Test
    fun aPausedRefreshCannotPublishOffBeforeADisableHasUnregistered() {
        turnedOn()
        val r = registrar
        // Behind the registrar's back: its next calculation gives ServerError, so the refresh's result can be seen.
        assertTrue(prefs.edit().putBoolean(Keys.SERVER_UNDONE, true).commit())
        val seen = watchPublications()
        val paused = Gate().also { policyGate = it }
        val refresh = background.launch { r.refreshNow() }
        await(paused.entered)
        assertTrue("the paused refresh holds the registrar's lock", registrarLocked())
        val disable = background.launch { r.disable() }
        paused.open.complete(Unit)
        runBlocking { withTimeout(WATCHDOG_MS) { refresh.join(); disable.join() } }
        assertFalse(subscribed("g1"))
        assertEquals(PushState.Off, r.ui.value.state)
        assertTrue("never Off while subscribed: $seen", seen.none { it.first.state == PushState.Off && it.second })
        assertEquals("the state before, the paused refresh's result, then the disable's, which is the last",
            listOf(on, error, PushState.Off), seen.map { it.first.state })
    }

    /** Off confirmed after the switch's preparation returned and admitted before its fence, run after it: dropped. */
    private fun droppedOffThenRetry(blocked: Boolean) {
        turnedOn()
        if (blocked) denied()
        setting(commands = heldCommands)
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.prepareSwitch() } } // the switch starts first
        turnOffViaDialog() // confirmed: the command waits for its dispatch
        val inside = Gate().also { apiGate = it }
        (held.poll(WATCHDOG_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("no dispatch came")).run() // admitted here
        await(inside.entered) // the Off runs: inside its operation, before it commits
        connection++ // the generation fence
        epoch++ // with the switch marker
        inside.open.complete(Unit)
        runHeld(::commandsDone)
        current = apiB
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.reconcileLocal() } } // local recovery for the new connection

        waitInSheet(text(R.string.settings_notif_turn_off_failed))
        waitInSheet(text(if (blocked) R.string.settings_notif_blocked else R.string.settings_notif_registering))
        assertTrue("the old registration is gone", !subscribed("g1"))
        assertTrue("push stays on for the new connection", subscribed("g2") && prefs.getBoolean(Keys.ENABLED, false))
        rule.onNode(hasText(text(R.string.settings_notif_turn_off)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).assertIsEnabled()

        turnOffViaDialog() // tried again, under the new connection
        runHeld(::commandsDone)
        rule.waitUntil(WATCHDOG_MS) { inSheet(text(R.string.settings_notif_turn_off_failed)).isEmpty() }
        waitInSheet(text(if (blocked) R.string.settings_notif_fallback else R.string.settings_notif_off))
        assertEquals(PushState.Off, registrar.ui.value.state)
        assertFalse(subscribed("g2") || prefs.getBoolean(Keys.ENABLED, false))
    }

    @Test
    fun aDroppedOffIsShownAndCanBeRetried() = droppedOffThenRetry(blocked = false)

    @Test
    fun aDroppedOffIsShownWithBlockedAccess() = droppedOffThenRetry(blocked = true)

    @Test
    fun anOffDispatchedAfterARealSwitchIsApplied() {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val container = AppContainer(app, CredentialStore(app) { key }, gatedPort) { db }
        try {
            runBlocking { withTimeout(WATCHDOG_MS) { container.connect(Credentials("https://a.example/", "key-a")) } }
            val pushPrefs = app.getSharedPreferences("push", Context.MODE_PRIVATE)
            assertTrue(pushPrefs.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
                .putString(Keys.REGISTRATION, Registration("g1", NTFY_PACKAGE, "tok-g1", connectionEpoch(app)).encode()).commit())
            fakePort.connectorTokens["g1"] = "tok-g1"
            fakePort.subscribed += "tok-g1"
            setting(commands = heldCommands, push = { container.push })
            rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
            turnOffViaDialog() // confirmed before the switch; its dispatch comes late, as on Dispatchers.Default

            runBlocking { withTimeout(WATCHDOG_MS) { container.connect(Credentials("https://b.example/", "key-b")) } }
            runBlocking { withTimeout(WATCHDOG_MS) { container.push.reconcileLocal() } } // the local job
            assertTrue(!subscribed("g1") && subscribed("g2"))
            runHeld(::commandsDone) // admitted now, under the new connection

            waitInSheet(text(R.string.settings_notif_off))
            assertTrue("applied: no failure line", inSheet(text(R.string.settings_notif_turn_off_failed)).isEmpty())
            assertFalse(subscribed("g2") || pushPrefs.getBoolean(Keys.ENABLED, false))
        } finally {
            WorkManager.getInstance(app).cancelAllWork().result.get()
            db.close()
        }
    }

    @Test
    fun aSecondConfirmWhileTheFirstIsOutstandingDoesNothing() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        val unregistering = Gate().also { unregisterGate = it }
        turnOffViaDialog()
        await(unregistering.entered) // the first Off is outstanding
        rule.waitForIdle()
        for (action in listOf(R.string.settings_notif_turn_off, R.string.settings_notif_test)) {
            rule.onNode(hasText(text(action)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).assertIsNotEnabled()
        }
        rule.onNode(hasText(text(R.string.settings_notif_turn_off)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).performClick()
        assertTrue("no second dialog", rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off_text))).fetchSemanticsNodes().isEmpty())
        unregistering.open.complete(Unit)
        joinCommands()
        waitInSheet(text(R.string.settings_notif_off))
        assertEquals(1, fakePort.calls.count { it.startsWith("unregister") })
    }

    @Test
    fun aMissingNtfyPageIsSaidNotThrown() {
        shadowOf(app).checkActivities(true)
        port.installed.clear()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        waitFor(text(R.string.settings_link_no_app))
        rule.onNodeWithText(text(R.string.settings_notif_no_distributor), substring = true).assertIsDisplayed()
    }

    @Test
    fun missingSystemSettingsAreSaidNotThrown() {
        shadowOf(app).checkActivities(true)
        denied()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        waitFor(text(R.string.settings_notif_system_unavailable))
    }

    @Test
    fun theTickerRunsOnlyWhileResumedWithAWindowOpen() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick()
        joinCommands()
        waitInSheet(text(R.string.settings_notif_test_sent))
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // system settings on top
        elapsed += TEST_WINDOW_MS
        rule.mainClock.advanceTimeBy(5_000)
        assertEquals("nothing ticks while not resumed", TestPushResult.SENT, testPush.lastResult.value?.result)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitInSheet(text(R.string.settings_notif_test_none)) // one tick on re-entry
    }

    @Test
    fun aResultOnTheOldRegistrationIsNotShownOnTheNew() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick()
        joinCommands()
        testPush.onWake(registrar.ui.value.testOwner!!)
        waitInSheet(text(R.string.settings_notif_test_received))
        val a = registrar.ui.value.testOwner!!

        switchToB()
        endpointArrives()
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.publishPending() } }
        assertEquals(on, registrar.ui.value.state)
        assertNotEquals(a, registrar.ui.value.testOwner!!)
        rule.waitUntil(WATCHDOG_MS) { inSheet(text(R.string.settings_notif_test)).isNotEmpty() && inSheet(text(R.string.settings_notif_test_received)).isEmpty() }
        assertTrue(testResultLines().isEmpty())
    }

    @Test
    fun aPendingTestOnTheOldRegistrationCompletesNothingOnTheNew() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        val a = registrar.ui.value.testOwner!!
        val answering = Gate().also { postGate = it }
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick()
        await(answering.entered) // A's POST is on the wire

        switchToB()
        endpointArrives()
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.publishPending() } }
        answering.open.complete(Unit) // A's late completion
        joinCommands()
        testPush.onWake(registrar.ui.value.testOwner!!) // and a wake for B
        assertEquals(TestOutcome(a, TestPushResult.SENT), testPush.lastResult.value)
        rule.waitUntil(WATCHDOG_MS) { inSheet(text(R.string.settings_notif_test)).isNotEmpty() }
        rule.waitForIdle()
        assertTrue("no result on B", testResultLines().isEmpty())
    }

    @Test
    fun aRecordRotationOnTheSameRegistrationKeepsTheResult() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick()
        joinCommands()
        testPush.onWake(registrar.ui.value.testOwner!!)
        waitInSheet(text(R.string.settings_notif_test_received))
        val owner = registrar.ui.value.testOwner!!
        val record = prefs.getString(Keys.RECORD, null)
        now += ROTATE_AFTER_MS
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.verify(force = true) } }
        assertNotEquals("a new record", record, prefs.getString(Keys.RECORD, null))
        assertEquals("the same registration", owner, registrar.ui.value.testOwner!!)
        rule.waitForIdle()
        waitInSheet(text(R.string.settings_notif_test_received))
    }

    @Test
    fun aTestResultIsShownOnlyWhileOnAndAllowed() {
        var shown by mutableStateOf(ui(PushState.Off, test = TestPushResult.SENT))
        themed { NotificationsSheet(shown, onTest = {}, onRetry = {}, onTurnOff = {}, onSystemSettings = {}, onDismiss = {}) }
        assertTrue(testResultLines().isEmpty())
        shown = ui(on, runtime = Runtime.DENIED, test = TestPushResult.SENT)
        rule.waitForIdle()
        assertTrue(testResultLines().isEmpty())
        shown = ui(on, test = TestPushResult.SENT)
        waitInSheet(text(R.string.settings_notif_test_sent))
    }

    private fun testResultLines() = listOf(
        R.string.settings_notif_test_sent, R.string.settings_notif_test_failed, R.string.settings_notif_test_received, R.string.settings_notif_test_none,
    ).flatMap { inSheet(text(it)) }

    // ---- Fix round 2 ----

    @Test
    fun twoEnableTapsAdmitOneCommand() {
        setting()
        val registering = Gate().also { registerGate = it }
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        await(registering.entered) // the first enable is outstanding; the row still offers enable
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        registering.open.complete(Unit)
        joinCommands()
        waitFor(text(R.string.settings_notif_registering))
        assertEquals(1, fakePort.calls.count { it.startsWith("register") })
    }

    @Test
    fun settingsRecreatedDuringAHeldThenDroppedOff() {
        turnedOn()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalHaptics provides counting) { NotificationsSetting({ registrar }, { testPush }, notifyPrefs, heldCommands) }
            }
        }
        waitReady()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.prepareSwitch() } }
        turnOffViaDialog()
        val inside = Gate().also { apiGate = it }
        (held.poll(WATCHDOG_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("no dispatch came")).run() // admitted
        await(inside.entered)

        restoration.emulateSavedInstanceStateRestore() // Settings is built again while the Off is held
        waitReady()
        rule.onNode(hasText(text(R.string.settings_notif_turn_off)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).assertIsNotEnabled() // Registering: the only action
        rule.onNode(hasText(text(R.string.settings_notif_turn_off)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).performClick()
        assertTrue("no second dialog", rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off_text))).fetchSemanticsNodes().isEmpty())
        assertEquals("one admission", 1, commands.children.count())

        connection++
        epoch++
        inside.open.complete(Unit)
        runHeld(::commandsDone)
        waitInSheet(text(R.string.settings_notif_turn_off_failed)) // on the restored sheet
        rule.onNode(hasText(text(R.string.settings_notif_turn_off)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).assertIsEnabled()
    }

    @Test
    fun anAppliedOffWhoseStateIsNotOffShowsNoFailure() {
        turnedOn()
        setting()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        fakePort.installed.clear() // ntfy goes away: after Off the state is NoDistributor, not Off
        turnOffViaDialog()
        joinCommands()
        waitInSheet(text(R.string.settings_notif_no_distributor))
        assertEquals(PushState.NoDistributor, registrar.ui.value.state)
        assertFalse(prefs.getBoolean(Keys.ENABLED, false))
        assertTrue("applied: the outcome is disable()'s, not the state's", inSheet(text(R.string.settings_notif_turn_off_failed)).isEmpty())
    }

    @Test
    fun aTestTapForAReplacedRegistrationSendsNothing() {
        turnedOn()
        setting(commands = heldCommands)
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick() // tapped while the sheet shows A
        val a = registrar.ui.value.testOwner!!

        switchToB() // replaced before the tap's dispatch comes
        endpointArrives(VALID2)
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.publishPending() } }
        assertNotEquals(a, registrar.ui.value.testOwner)
        runHeld(::commandsDone)
        assertEquals("A's tap went nowhere, not to B's endpoint", emptyList<String>(), sent)
        assertNull(testPush.lastResult.value)

        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick() // B's own test
        runHeld(::commandsDone)
        assertEquals(listOf(VALID2), sent)
        val b = registrar.ui.value.testOwner!!
        assertEquals(TestOutcome(b, TestPushResult.SENT), testPush.lastResult.value)
        waitInSheet(text(R.string.settings_notif_test_sent))
        testPush.onWake(b)
        waitInSheet(text(R.string.settings_notif_test_received))
    }

    @Test
    fun anOlderAccessReadNeverOverwritesANewerOne() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) // never asked
        var readGate: Gate? = null
        val gated = object : SharedPreferences by notifyPrefs {
            override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                val value = notifyPrefs.getBoolean(key, defValue)
                if (key == "permission_asked") readGate?.let { readGate = null; it.pass() } // sampled, then held
                return value
            }
        }
        setting(prefs = gated)
        val old = Gate().also { readGate = it }
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(old.entered) // the resume's read has sampled "never asked" and is held

        rule.onNodeWithText(text(R.string.settings_notifications)).performClick() // asks
        rule.waitUntil(WATCHDOG_MS) { shadowOf(rule.activity).lastRequestedPermission != null && notifyPrefs.contains("permission_asked") }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val request = shadowOf(rule.activity).lastRequestedPermission
        rule.runOnUiThread {
            rule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        }
        waitFor(text(R.string.settings_notif_off)) // the newer read published: granted, push off

        val done = CompletableDeferred<Unit>().also { computed = it }
        old.open.complete(Unit) // the older read finishes now
        await(done) // its resume job went on to the refresh, so the read was handled
        rule.waitForIdle()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick() // once
        joinCommands()
        waitFor(text(R.string.settings_notif_registering))
        assertEquals("enabled, not asked again", 1, fakePort.calls.count { it.startsWith("register") })
    }

    @Test
    fun theTickerRunsOnlyWithTheSheetOpenAndAWindowOpen() {
        val owner = "1:g1"
        assertTrue(ticks(open = true, last = TestOutcome(owner, TestPushResult.SENT)))
        assertFalse(ticks(open = false, last = TestOutcome(owner, TestPushResult.SENT)))
        assertFalse(ticks(open = true, last = null))
        for (r in listOf(TestPushResult.SEND_FAILED, TestPushResult.WAKE_ARRIVED, TestPushResult.NO_WAKE)) {
            assertFalse(r.name, ticks(open = true, last = TestOutcome(owner, r)))
        }
    }

    // ---- Fix round 3 ----

    @Test
    fun aDroppedOffOfOneConnectionIsNotShownOnTheNext() {
        turnedOn()
        var conn by mutableStateOf(0L)
        themed { key(conn) { NotificationsSetting({ registrar }, { testPush }, notifyPrefs, heldCommands, conn) } }
        waitReady()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.prepareSwitch() } }
        turnOffViaDialog()
        val inside = Gate().also { apiGate = it }
        (held.poll(WATCHDOG_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("no dispatch came")).run() // admitted for A
        await(inside.entered)

        conn = 1 // Settings entered on the next connection while A's Off is held
        waitReady()
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        // A push command runs: B offers no push action either, so nothing can be confirmed and silently dropped.
        rule.onNode(hasText(text(R.string.settings_notif_turn_off)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).assertIsNotEnabled().performClick()
        assertTrue("no confirmation on B", rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off_text))).fetchSemanticsNodes().isEmpty())
        connection++
        epoch++
        inside.open.complete(Unit)
        runHeld(::commandsDone) // A's Off is dropped
        current = apiB
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.reconcileLocal() } }
        rule.waitForIdle()
        assertTrue("A's failure line is not B's", rule.onAllNodes(hasText(text(R.string.settings_notif_turn_off_failed))).fetchSemanticsNodes().isEmpty())
        rule.onNode(hasText(text(R.string.settings_notif_turn_off)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).assertIsEnabled() // A's job ended

        turnOffViaDialog() // B's own command runs
        runHeld(::commandsDone)
        waitInSheet(text(R.string.settings_notif_off))
        assertFalse(subscribed("g2") || prefs.getBoolean(Keys.ENABLED, false))
    }

    @Test
    fun aCommandCancelledBeforeItsFirstDispatchFreesTheGuard() {
        turnedOn()
        setting(commands = heldCommands)
        rule.onNodeWithText(text(R.string.settings_notifications)).performClick()
        rule.onNodeWithText(text(R.string.settings_notif_test)).performClick()
        commands.children.single().cancel() // before its first dispatch: its body never runs
        (held.poll(WATCHDOG_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("no dispatch came")).run() // it only completes the job
        rule.waitForIdle()
        rule.onNode(hasText(text(R.string.settings_notif_test)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).assertIsEnabled().performClick()
        runHeld(::commandsDone)
        assertEquals(listOf(VALID), sent)
    }

    @Test
    fun theSheetSendsTheOwnerItDrewEvenWhenTheStateMovedOn() {
        turnedOn()
        val drawn = registrar.ui.value // what the sheet drew: A
        switchToB()
        endpointArrives(VALID2)
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.publishPending() } }
        val b = registrar.ui.value.testOwner!!
        runBlocking { withTimeout(WATCHDOG_MS) { testPush.send(VALID2, b) } } // B's own test window is open
        elapsed += 10_000 // past the throttle: a stale tap could start a new send
        val allowed = NotificationAccess(Runtime.GRANTED, true, true, true)
        themed {
            // A's sheet, still on screen when the state is already B; the callback is NotificationsSetting's.
            NotificationsSheet(NotificationsUi(allowed, drawn, null), onTest = { owner ->
                runBlocking { withTimeout(WATCHDOG_MS) { registrar.testEndpoint(owner!!)?.let { testPush.send(it, owner) } } }
            }, onRetry = {}, onTurnOff = {}, onSystemSettings = {}, onDismiss = {})
        }
        rule.onNode(hasText(text(R.string.settings_notif_test)) and hasAnyAncestor(hasTestTag(SHEET_TAG))).performClick()
        assertEquals("no POST for the stale tap", listOf(VALID2), sent)
        assertEquals("B's window and result are untouched", TestOutcome(b, TestPushResult.SENT), testPush.lastResult.value)
        testPush.onWake(b)
        assertEquals(TestOutcome(b, TestPushResult.WAKE_ARRIVED), testPush.lastResult.value)
    }

    @Test
    fun anEpochChangeBeforeRecoveryGivesNoTarget() {
        turnedOn()
        connection++
        runBlocking {
            withTimeout(WATCHDOG_MS) {
                registrar.prepareSwitch()
                // A queued worker registers g2 under the old epoch, its endpoint arrives, and it publishes the record.
                registrar.publishPending()
                endpointArrives(VALID2)
                registrar.publishPending()
            }
        }
        assertEquals(VALID2, registrar.endpoint)
        epoch++ // the marker advances the epoch before recovery replaces g2
        assertNull("g2 is journaled under the old epoch: not the owner of a target", registrar.testEndpoint("1:g2"))
        assertNull(registrar.testEndpoint("0:g2"))
        assertTrue(sent.isEmpty())
    }

    // ---- Fix round 4 ----

    @Test
    fun twoScreensOverOneContainerAdmitOneCommand() {
        themed {
            Column {
                // Two screens, each with its own view model store (two activities in one process), one container.
                for (tag in listOf("first", "second")) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides rememberViewModelStoreOwner()) {
                        Box(Modifier.testTag(tag)) { NotificationsSetting({ registrar }, { testPush }, notifyPrefs, uiCommands) }
                    }
                }
            }
        }
        rule.waitUntil(WATCHDOG_MS) { rule.onAllNodes(hasText(text(R.string.settings_notif_off), substring = true)).fetchSemanticsNodes().size == 2 }
        val registering = Gate().also { registerGate = it }
        rule.onNode(hasText(text(R.string.settings_notifications)) and hasClickAction() and hasAnyAncestor(hasTestTag("first"))).performClick()
        await(registering.entered) // the first screen's enable is outstanding
        rule.waitForIdle()
        rule.onNode(hasText(text(R.string.settings_notifications)) and hasClickAction() and hasAnyAncestor(hasTestTag("second")))
            .assertIsNotEnabled().performClick() // the other screen offers no enable meanwhile
        registering.open.complete(Unit)
        joinCommands()
        waitFor(text(R.string.settings_notif_registering))
        assertEquals("one admission", 1, fakePort.calls.count { it.startsWith("register") })
    }

    @Test
    fun theHolderSendsTheTestForTheOwnerTheSheetDrew() {
        turnedOn()
        val drawn = registrar.ui.value.testOwner!! // the sheet drew A
        switchToB()
        endpointArrives(VALID2)
        runBlocking { withTimeout(WATCHDOG_MS) { registrar.publishPending() } }
        val b = registrar.ui.value.testOwner!!
        runBlocking { withTimeout(WATCHDOG_MS) { testPush.send(VALID2, b) } } // B's own test window is open
        elapsed += 10_000 // past the throttle: a stale tap could start a new send

        sendTest(uiCommands, registrar, testPush, drawn) // the holder's real callback, with the owner the sheet drew
        joinCommands()
        assertEquals("no POST for A's tap", listOf(VALID2), sent)
        assertEquals("B's window and result untouched", TestOutcome(b, TestPushResult.SENT), testPush.lastResult.value)
        testPush.onWake(b)
        assertEquals(TestOutcome(b, TestPushResult.WAKE_ARRIVED), testPush.lastResult.value)
    }

    // ---- Fix round 5 ----

    @Test
    fun theEnableRowLooksUnavailableWhileACommandRuns() {
        setting()
        val row = rule.onNode(hasText(text(R.string.settings_notifications)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val normal = row.captureToImage().asAndroidBitmap()
        val registering = Gate().also { registerGate = it }
        row.performClick() // enable: the command is held at REGISTER, push still shows Off
        await(registering.entered)
        rule.waitForIdle()
        row.assertIsNotEnabled()
            .assert(hasText(text(R.string.settings_notifications), substring = true)) // still labelled
            .assert(hasText(text(R.string.settings_notif_off), substring = true)) // its value and fallback still readable
            .assert(hasText(text(R.string.settings_notif_fallback), substring = true))
        assertFalse("drawn unavailable", normal.sameAs(row.captureToImage().asAndroidBitmap()))
        registering.open.complete(Unit)
        joinCommands()
        waitFor(text(R.string.settings_notif_registering))
        row.assertIsEnabled().assert(hasClickAction())
    }

    @Test
    fun theContainerRefusesASecondCommandWhileOneRuns() {
        turnedOn()
        val posting = Gate().also { postGate = it }
        assertTrue(uiCommands.run { testPush.send(VALID, registrar.ui.value.testOwner!!) })
        await(posting.entered) // the first command's POST is on the wire
        var secondRan = false
        assertFalse("not admitted", uiCommands.run { secondRan = true; testPush.send(VALID, registrar.ui.value.testOwner!!) })
        assertEquals("no second job", 1, commands.children.count())
        posting.open.complete(Unit)
        joinCommands()
        assertFalse(secondRan)
        assertEquals("one request", 1, sent.size)
        assertTrue("admitted again once it ended", uiCommands.run {})
    }

    @Test
    @Config(qualifiers = "pl-w320dp-h420dp-xhdpi")
    fun shortWindowLargeFontLongestPolish() {
        RuntimeEnvironment.setFontScale(2f)
        themed {
            NotificationsSheet(ui(on, test = TestPushResult.NO_WAKE), onTest = {}, onRetry = {}, onTurnOff = {}, onSystemSettings = {}, onDismiss = {})
        }
        rule.onNodeWithText("Powiadomienia").assertHeightIsAtLeast(32.dp) // the large font took effect (a 16 sp title)
        // Taller than half the window, the sheet opens half way, as every sheet does: the user drags it up.
        rule.onNodeWithText("Powiadomienia").performTouchInput { swipeUp() }
        rule.onNodeWithText("Żaden push nie dotarł").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Wyślij testowy push").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Wyłącz push").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Wyłączyć push?").assertIsDisplayed()
        rule.onNodeWithText("Odpowiedzi nadal będą sprawdzane mniej więcej co 15 minut, gdy któraś trwa.").assertIsDisplayed()
        rule.onAllNodes(hasText("Wyłącz push"))[1].assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Anuluj").assertIsDisplayed()
    }
}
