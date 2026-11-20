package xyz.rycerz.apofis.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatViewModel
import xyz.rycerz.apofis.ui.chat.onlineFlow
import xyz.rycerz.apofis.ui.connect.ConnectScreen
import xyz.rycerz.apofis.ui.connect.ConnectState
import xyz.rycerz.apofis.ui.connect.ConnectViewModel
import xyz.rycerz.apofis.ui.settings.SettingsScreen

enum class Screen { CONNECT, CHAT, SETTINGS, RECONNECT, LICENSES }

/** State-based navigation: Connect when not connected, otherwise the chat; Settings and Connection from there. */
@Composable
fun AppNav(container: AppContainer) {
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.load() // decrypts the stored key off the main thread
        loaded = true
    }
    val connection by container.connection.collectAsState()
    var chosen by rememberSaveable { mutableStateOf<Screen?>(null) }
    if (!loaded) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    val conn = connection
    // Without a connection only Connect makes sense; a failed reconnect that ended disconnected stays on its screen.
    val screen = when {
        conn == null && chosen != Screen.RECONNECT -> Screen.CONNECT
        else -> chosen ?: Screen.CHAT
    }

    when (screen) {
        Screen.CONNECT, Screen.RECONNECT -> key(screen) {
            val reconnect = screen == Screen.RECONNECT
            // Scoped to this screen's presence: leaving clears the view model, which cancels its probe and drops the key.
            val owner = rememberViewModelStoreOwner()
            val vm = viewModel(viewModelStoreOwner = owner) {
                ConnectViewModel(
                    current = if (reconnect) conn?.credentials else null,
                    willClearCache = container::willClearCache,
                    save = container::connect,
                    failedBefore = !reconnect && container.consumeSwitchFailure(),
                )
            }
            LaunchedEffect(vm.state) { if (vm.state == ConnectState.Connected) chosen = Screen.CHAT }
            // Back cancels a running check synchronously before leaving; while saving, cancel() refuses and Back is
            // swallowed. On first run, leaving means sending the app to the background, as the system would.
            val activity = LocalActivity.current
            val canGoBack = reconnect && conn != null
            val busy = vm.state == ConnectState.Testing || vm.state == ConnectState.Saving
            val leave = {
                if (vm.cancel()) {
                    if (canGoBack) chosen = Screen.SETTINGS else activity?.moveTaskToBack(true)
                }
            }
            BackHandler(enabled = canGoBack || busy, onBack = leave)
            ConnectScreen(vm, onBack = if (canGoBack) leave else null)
        }
        Screen.CHAT -> if (conn != null) ChatRoute(conn, onSettings = { chosen = Screen.SETTINGS }, onConnection = { chosen = Screen.RECONNECT })
        Screen.SETTINGS -> {
            BackHandler { chosen = Screen.CHAT }
            val scope = rememberCoroutineScope()
            val prefs = container.preferences
            val context = LocalContext.current
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            SettingsScreen(
                serverUrl = conn?.credentials?.baseUrl.orEmpty(),
                themeMode = prefs.themeMode.collectAsState().value,
                dynamicColor = prefs.dynamicColor.collectAsState().value,
                pureBlack = prefs.pureBlack.collectAsState().value,
                versionName = version,
                onConnection = { chosen = Screen.RECONNECT },
                onThemeMode = { scope.launch { prefs.setThemeMode(it) } },
                onDynamicColor = { scope.launch { prefs.setDynamicColor(it) } },
                onPureBlack = { scope.launch { prefs.setPureBlack(it) } },
                onLicenses = { chosen = Screen.LICENSES },
                onBack = { chosen = Screen.CHAT },
            )
        }
        Screen.LICENSES -> {
            BackHandler { chosen = Screen.SETTINGS }
            LicensesScreen(onBack = { chosen = Screen.SETTINGS })
        }
    }
}

/** The chat: until the drawer exists, the most recent chat, or a new local one when there is none. */
@Composable
private fun ChatRoute(conn: Connected, onSettings: () -> Unit, onConnection: () -> Unit) {
    var chatKey by rememberSaveable(conn) { mutableStateOf<String?>(null) }
    LaunchedEffect(conn) {
        if (chatKey == null) {
            chatKey = runCatching { conn.repo.sessions().first().firstOrNull()?.key ?: conn.repo.createLocalSession() }.getOrNull()
        }
    }
    val sessionKey = chatKey
    if (sessionKey == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    key(conn, sessionKey) {
        // Scoped to this chat: switching chats or servers clears the view model and its flows.
        val owner = rememberViewModelStoreOwner()
        val context = LocalContext.current.applicationContext
        val vm = viewModel(viewModelStoreOwner = owner) { ChatViewModel(sessionKey, conn, context.onlineFlow()) }
        val state by vm.state.collectAsState()
        ChatScreen(
            state,
            vm,
            onOpenConnection = onConnection,
            appBarActions = {
                IconButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.lucide_settings), contentDescription = stringResource(R.string.settings_title), Modifier.size(20.dp))
                }
            },
        )
    }
}
