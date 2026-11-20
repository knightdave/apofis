package xyz.rycerz.apofis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.R
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
                    back = container.connectBack,
                )
            }
            LaunchedEffect(vm.state) { if (vm.state == ConnectState.Connected) chosen = Screen.CHAT }
            // Back cancels a running check synchronously before returning to Settings; while saving, cancel() refuses
            // and Back is swallowed. Without a screen to return to, the activity's callback handles a busy Back.
            val canGoBack = reconnect && conn != null
            val leave = { if (vm.cancel()) chosen = Screen.SETTINGS }
            BackHandler(enabled = canGoBack, onBack = leave)
            ConnectScreen(vm, onBack = if (canGoBack) leave else null)
        }
        Screen.CHAT -> ChatPlaceholder(conn, onSettings = { chosen = Screen.SETTINGS })
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

/** Replaced by the chat screen in the next task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPlaceholder(conn: Connected?, onSettings: () -> Unit) {
    val host = conn?.credentials?.baseUrl?.toHttpUrlOrNull()?.host.orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.lucide_settings), contentDescription = stringResource(R.string.settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chat_placeholder, host), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
