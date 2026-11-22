package xyz.rycerz.apofis.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.notify.NotificationAccess
import xyz.rycerz.apofis.notify.Runtime
import xyz.rycerz.apofis.notify.notificationAccess
import xyz.rycerz.apofis.notify.rememberAskNotificationsOnce
import xyz.rycerz.apofis.push.PushRegistrar
import xyz.rycerz.apofis.push.PushState
import xyz.rycerz.apofis.push.PushUi
import xyz.rycerz.apofis.push.TestPush
import xyz.rycerz.apofis.push.TestPushResult
import xyz.rycerz.apofis.ui.theme.ApofisSheet
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.SheetRow
import xyz.rycerz.apofis.ui.theme.wash

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

/** The Settings row: the state, and under it the fallback line while push is off; one selection feedback per tap. */
@Composable
fun NotificationsRow(ui: NotificationsUi, onClick: () -> Unit) {
    val haptics = ApofisTheme.haptics
    val value = lines(stringResource(valueOf(ui)), summaryOf(ui)?.let { stringResource(it) })
    val icon = if (ui.state is PushState.On && ui.allowed) R.drawable.lucide_bell else R.drawable.lucide_bell_off
    RowLayout(Modifier.clickable { haptics.selection(); onClick() }, icon, stringResource(R.string.settings_notifications), value) {
        Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The state as the registrar reports it, the last test result while On, and the actions of [actionsOf]. "Turn off push"
 * asks first. The sheet stays open after a command: what it shows next is the registrar's answer, so an Off that was not
 * applied (it was queued under a connection that a switch replaced) shows the state that is real, with Turn off again.
 */
@Composable
fun NotificationsSheet(ui: NotificationsUi, onTest: () -> Unit, onRetry: () -> Unit, onTurnOff: () -> Unit, onSystemSettings: () -> Unit, onDismiss: () -> Unit) {
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    ApofisSheet(onDismiss) {
        // Scrolls, so the last action stays reachable in a short window with a large font.
        Column(Modifier.verticalScroll(rememberScrollState())) {
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
            for (action in actionsOf(ui)) {
                when (action) {
                    SheetAction.TEST -> SheetRow(R.drawable.lucide_bell, stringResource(R.string.settings_notif_test), onTest)
                    SheetAction.RETRY -> SheetRow(R.drawable.lucide_server, stringResource(R.string.settings_notif_retry), onRetry)
                    SheetAction.SYSTEM_SETTINGS -> SheetRow(R.drawable.lucide_settings, stringResource(R.string.settings_notif_system), onSystemSettings)
                    SheetAction.TURN_OFF -> SheetRow(R.drawable.lucide_bell_off, stringResource(R.string.settings_notif_turn_off), { confirmOff = true })
                }
            }
        }
    }
    if (confirmOff) {
        // shortcut: the drawer's AlertDialog with washed text buttons; Milestone 2 Task 10 replaces it with ApofisDialog.
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            text = { Text(stringResource(R.string.settings_notif_turn_off_text)) },
            confirmButton = {
                DialogButton({ confirmOff = false; onTurnOff() }) {
                    Text(stringResource(R.string.settings_notif_turn_off), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { DialogButton({ confirmOff = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DialogButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val haptics = ApofisTheme.haptics
    val source = remember { MutableInteractionSource() }
    TextButton({ haptics.selection(); onClick() }, Modifier.heightIn(min = 48.dp).wash(source, ButtonDefaults.textShape), interactionSource = source) { content() }
}

/**
 * The Notifications row and its sheet, wired to the registrar and the test push. Access and the push state are read
 * again on every resume (system settings, an ntfy install). Commands run in [scope], which outlives this screen.
 */
@Composable
fun NotificationsSetting(push: PushRegistrar, testPush: TestPush, notifyPrefs: SharedPreferences, scope: CoroutineScope) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(notificationAccess(context, notifyPrefs)) }
    LifecycleResumeEffect(push) {
        access = notificationAccess(context, notifyPrefs)
        push.refresh()
        onPauseOrDispose {}
    }
    val pushUi by push.ui.collectAsState()
    val test by testPush.lastResult.collectAsState()
    val ui = NotificationsUi(access, pushUi, test)
    var open by rememberSaveable { mutableStateOf(false) }
    val ask = rememberAskNotificationsOnce(notifyPrefs)
    val uriHandler = LocalUriHandler.current
    val ntfyUrl = stringResource(R.string.settings_ntfy_url)
    val systemSettings = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    NotificationsRow(ui) {
        when (tapOf(ui)) {
            RowTap.ASK -> ask()
            RowTap.SYSTEM_SETTINGS -> systemSettings()
            RowTap.SHEET -> open = true
            RowTap.GET_NTFY -> runCatching { uriHandler.openUri(ntfyUrl) } // no browser or store: nothing to open
            RowTap.ENABLE -> push.enable()
        }
    }
    if (open) {
        LaunchedEffect(testPush) {
            while (true) {
                testPush.tick() // closes an expired test window as "No push arrived"
                delay(1_000)
            }
        }
        NotificationsSheet(
            ui,
            // Without a record the empty endpoint fails validation: "Test not sent", and nothing leaves the phone.
            onTest = { scope.launch { testPush.send(push.endpoint.orEmpty()) } },
            onRetry = { scope.launch { push.verify(force = true) } },
            onTurnOff = { scope.launch { push.disable() } },
            onSystemSettings = systemSettings,
            onDismiss = { open = false },
        )
    }
}
