package xyz.rycerz.apofis.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.notify.NotificationAccess
import xyz.rycerz.apofis.notify.Runtime
import xyz.rycerz.apofis.notify.notificationAccess
import xyz.rycerz.apofis.notify.rememberAskNotificationsOnce
import xyz.rycerz.apofis.push.PushRegistrar
import xyz.rycerz.apofis.push.PushState
import xyz.rycerz.apofis.push.PushUi
import xyz.rycerz.apofis.push.TestOutcome
import xyz.rycerz.apofis.push.TestPush
import xyz.rycerz.apofis.push.TestPushResult
import xyz.rycerz.apofis.ui.theme.ApofisDialog
import xyz.rycerz.apofis.ui.theme.ApofisSheet
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.SheetRow

/** What Settings shows about notifications: system access, the push state as the registrar reports it, the last test. */
data class NotificationsUi(val access: NotificationAccess, val push: PushUi, val test: TestPushResult?)

internal enum class RowTap { ASK, SYSTEM_SETTINGS, SHEET, GET_NTFY, ENABLE }

internal enum class SheetAction { TEST, RETRY, SYSTEM_SETTINGS, TURN_OFF }

private val NotificationsUi.state get() = push.state
private val NotificationsUi.pushEnabled get() = state is PushState.Registering || state is PushState.On || state is PushState.ServerError
private val NotificationsUi.runtimeBlocked get() = access.runtime == Runtime.NEVER_ASKED || access.runtime == Runtime.DENIED || !access.appEnabled
private val NotificationsUi.channelBlocked get() = !access.repliesEnabled || !access.answersEnabled
private val NotificationsUi.allowed get() = !runtimeBlocked && !channelBlocked

/** The row's value; access problems come first, then the push state. */
internal fun valueOf(ui: NotificationsUi): Int = when {
    ui.runtimeBlocked -> R.string.settings_notif_blocked
    ui.channelBlocked -> R.string.settings_notif_channel_blocked
    else -> when (ui.state) {
        PushState.Off -> R.string.settings_notif_off
        PushState.NoDistributor -> R.string.settings_notif_no_distributor
        PushState.Registering -> R.string.settings_notif_registering
        is PushState.ServerError -> R.string.settings_notif_server_error
        is PushState.On -> R.string.settings_notif_on
    }
}

internal fun summaryOf(ui: NotificationsUi): Int? =
    R.string.settings_notif_fallback.takeIf { ui.state == PushState.Off || ui.state == PushState.NoDistributor }

internal fun tapOf(ui: NotificationsUi): RowTap = when {
    ui.access.runtime == Runtime.NEVER_ASKED -> RowTap.ASK // an explicit user action
    ui.runtimeBlocked -> if (ui.pushEnabled) RowTap.SHEET else RowTap.SYSTEM_SETTINGS
    ui.channelBlocked -> RowTap.SHEET
    ui.state == PushState.NoDistributor -> RowTap.GET_NTFY
    ui.state == PushState.Off -> RowTap.ENABLE
    else -> RowTap.SHEET
}

internal fun actionsOf(ui: NotificationsUi): List<SheetAction> = buildList {
    if (!ui.allowed) add(SheetAction.SYSTEM_SETTINGS)
    else if (ui.state is PushState.On) add(SheetAction.TEST)
    else if (ui.state is PushState.ServerError) add(SheetAction.RETRY)
    if (ui.pushEnabled) add(SheetAction.TURN_OFF)
}

private fun lines(value: String, summary: String?) = listOfNotNull(value, summary).joinToString("\n")

/** The test window's ticker runs only while the sheet is open and a test window is: SENT is the only result with one. */
internal fun ticks(open: Boolean, last: TestOutcome?) = open && last?.result == TestPushResult.SENT

/**
 * The process's push commands, owned by the container for the lifetime of the running job: one admitted at a time, and
 * [busy] is what every Settings screen shows, whatever connection it was entered on. Only [message] belongs to one
 * connection's screen. Admission runs on the main thread; the command runs in [scope], which outlives every screen.
 */
class PushCommands(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set

    /** A message for the screen of one connection: (connection generation, string). */
    var message by mutableStateOf<Pair<Long, Int>?>(null)

    /** Admits [block] unless a command runs; false when it was not admitted. */
    fun run(block: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true
        message = null
        val job = scope.launch { block() }
        job.invokeOnCompletion { busy = false } // also when cancelled before its first dispatch
        return true
    }
}

/** The holder's test-push action: for the owner the sheet drew, sent only to the target that registration owns. */
internal fun sendTest(commands: PushCommands, push: PushRegistrar, test: TestPush, shown: String?) {
    if (shown != null) commands.run { push.testEndpoint(shown)?.let { test.send(it, shown) } }
}

/** The bell only for push On with every part of access allowed. */
internal fun iconOf(ui: NotificationsUi): Int = if (ui.state is PushState.On && ui.allowed) R.drawable.lucide_bell else R.drawable.lucide_bell_off

/** The Settings row: the state, and under it the fallback line while push is off; one selection feedback per tap. */
@Composable
fun NotificationsRow(ui: NotificationsUi, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = ApofisTheme.haptics
    val value = lines(stringResource(valueOf(ui)), summaryOf(ui)?.let { stringResource(it) })
    // Unavailable: faded as a disabled sheet row is (0.38), its value and fallback line still there to read.
    RowLayout(
        Modifier.clickable(enabled = enabled, role = Role.Button) { haptics.selection(); onClick() }.alpha(if (enabled) 1f else 0.38f),
        iconOf(ui),
        stringResource(R.string.settings_notifications),
        value,
    ) {
        Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Before the registrar and the access have been read off the main thread: the title only, no state and no action. */
@Composable
private fun PendingRow() = RowLayout(Modifier, R.drawable.lucide_bell, stringResource(R.string.settings_notifications)) {}

@Composable
private fun ErrorLine(message: Int, modifier: Modifier) = Text(
    stringResource(message),
    modifier.semantics { liveRegion = LiveRegionMode.Polite },
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.error,
)

/**
 * The state as the registrar reports it, the last test result while On, [message] (a command that was not applied, an
 * activity that could not open) and the actions of [actionsOf], disabled while a command is [busy]. "Turn off push"
 * asks first. The sheet stays open after a command, so its outcome shows here.
 */
@Composable
fun NotificationsSheet(
    ui: NotificationsUi,
    onTest: (owner: String?) -> Unit,
    onRetry: () -> Unit,
    onTurnOff: () -> Unit,
    onSystemSettings: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    message: Int? = null,
) {
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    ApofisSheet(onDismiss) {
        // Scrolls, so the last action stays reachable in a short window with a large font.
        Column(Modifier.testTag(SHEET_TAG).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.settings_notifications), Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
            Text(
                lines(stringResource(valueOf(ui)), summaryOf(ui)?.let { stringResource(it) }),
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val test = ui.test?.takeIf { ui.state is PushState.On && ui.allowed }
            if (test != null) {
                Text(
                    stringResource(
                        when (test) {
                            TestPushResult.SENT -> R.string.settings_notif_test_sent
                            TestPushResult.SEND_FAILED -> R.string.settings_notif_test_failed
                            TestPushResult.WAKE_ARRIVED -> R.string.settings_notif_test_received
                            TestPushResult.NO_WAKE -> R.string.settings_notif_test_none
                        },
                    ),
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (message != null) ErrorLine(message, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
            val enabled = !busy
            for (action in actionsOf(ui)) {
                when (action) {
                    // The owner this sheet drew, not whatever the state holds when the click runs.
                    SheetAction.TEST -> SheetRow(R.drawable.lucide_bell, stringResource(R.string.settings_notif_test), { onTest(ui.push.testOwner) }, enabled = enabled)
                    SheetAction.RETRY -> SheetRow(R.drawable.lucide_server, stringResource(R.string.settings_notif_retry), onRetry, enabled = enabled)
                    SheetAction.SYSTEM_SETTINGS -> SheetRow(R.drawable.lucide_settings, stringResource(R.string.settings_notif_system), onSystemSettings, enabled = enabled)
                    SheetAction.TURN_OFF -> SheetRow(R.drawable.lucide_bell_off, stringResource(R.string.settings_notif_turn_off), { confirmOff = true }, enabled = enabled)
                }
            }
        }
    }
    if (confirmOff) {
        ApofisDialog(
            title = stringResource(R.string.settings_notif_turn_off_title),
            confirmLabel = stringResource(R.string.settings_notif_turn_off),
            onConfirm = { confirmOff = false; onTurnOff() },
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { confirmOff = false },
            destructive = true,
            confirmEnabled = !busy,
        ) { Text(stringResource(R.string.settings_notif_turn_off_text)) }
    }
}

internal const val SHEET_TAG = "notifications.sheet"

/**
 * The Notifications row and its sheet, wired to the registrar and the test push. The lazily built [push] and [testPush]
 * are resolved and the access is read off the main thread; on every resume the access is read again and the registrar
 * recomputes its state in turn with its operations. One command at a time runs through the container's [commands];
 * its outcome shows in the sheet.
 */
@Composable
fun NotificationsSetting(push: () -> PushRegistrar, testPush: () -> TestPush, notifyPrefs: SharedPreferences, commands: PushCommands, connection: Long = 0) {
    val context = LocalContext.current
    val local = rememberCoroutineScope()
    var parts by remember { mutableStateOf<Pair<PushRegistrar, TestPush>?>(null) }
    var access by remember { mutableStateOf<NotificationAccess?>(null) }
    // Resume, the dialog's answer and the stored mark each read the access; only the latest read started may publish.
    val reads = remember { intArrayOf(0) }
    suspend fun readAccess() {
        val read = ++reads[0]
        val got = withContext(Dispatchers.IO) { notificationAccess(context, notifyPrefs) }
        if (read == reads[0]) access = got
    }
    LifecycleResumeEffect(Unit) {
        val job = local.launch {
            val (p, _) = parts ?: withContext(Dispatchers.IO) { push() to testPush() }.also { parts = it }
            readAccess()
            p.refreshNow()
        }
        onPauseOrDispose { job.cancel() }
    }
    // The dialog's answer and the stored mark are read back at once, so the next tap acts on them.
    val ask = rememberAskNotificationsOnce(notifyPrefs, onSettled = { local.launch { readAccess() } })
    val (p, t) = parts ?: return PendingRow()
    val a = access ?: return PendingRow()

    val pushUi by p.ui.collectAsState()
    val last by t.lastResult.collectAsState()
    // A result counts only for the registration it was sent for, which the registrar reports with its state.
    val ui = NotificationsUi(a, pushUi, last?.takeIf { it.owner == pushUi.testOwner }?.result)
    var open by rememberSaveable { mutableStateOf(false) }
    // Busy is the process's: no screen offers a push action while one runs. A message belongs to its connection's screen.
    val busy = commands.busy
    val message = commands.message?.takeIf { it.first == connection }?.second
    fun command(block: suspend () -> Unit) = commands.run(block)
    fun say(text: Int?) {
        commands.message = text?.let { connection to it }
    }
    fun start(failure: Int, activity: () -> Unit) {
        say(null)
        if (runCatching(activity).isFailure) say(failure) // no app for it: say so, stay here
    }
    val uriHandler = LocalUriHandler.current
    val ntfyUrl = stringResource(R.string.settings_ntfy_url)
    val systemSettings = {
        start(R.string.settings_notif_system_unavailable) {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    NotificationsRow(ui, enabled = !(busy && tapOf(ui) == RowTap.ENABLE)) {
        if (!busy) say(null)
        when (tapOf(ui)) {
            RowTap.ASK -> ask()
            RowTap.SYSTEM_SETTINGS -> systemSettings()
            RowTap.SHEET -> open = true
            RowTap.GET_NTFY -> start(R.string.settings_link_no_app) { uriHandler.openUri(ntfyUrl) }
            RowTap.ENABLE -> command { p.enableNow() } // awaited, so a second tap meanwhile is refused
        }
    }
    if (!open) message?.let { ErrorLine(it, Modifier.padding(start = 48.dp, end = 12.dp, bottom = 11.dp)) }
    if (ticks(open, last)) {
        // Resumed only; the first tick on every resume closes a window that expired meanwhile.
        LifecycleResumeEffect(t) {
            val ticker = local.launch {
                while (true) {
                    t.tick()
                    delay(1_000)
                }
            }
            onPauseOrDispose { ticker.cancel() }
        }
    }
    if (open) {
        NotificationsSheet(
            ui,
            onTest = { shown -> sendTest(commands, p, t, shown) },
            onRetry = { command { p.verify(force = true) } },
            // The outcome is disable()'s own; a cancellation or an exception reports nothing.
            onTurnOff = { command { if (!p.disable()) say(R.string.settings_notif_turn_off_failed) } },
            onSystemSettings = systemSettings,
            onDismiss = {
                open = false
                if (!busy) say(null)
            },
            busy = busy,
            message = message,
        )
    }
}
