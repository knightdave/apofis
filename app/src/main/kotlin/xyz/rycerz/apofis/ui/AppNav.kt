package xyz.rycerz.apofis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.connect.ConnectScreen
import xyz.rycerz.apofis.ui.connect.ConnectState
import xyz.rycerz.apofis.ui.connect.ConnectViewModel
import xyz.rycerz.apofis.ui.settings.SettingsScreen

enum class Screen { CONNECT, CHAT, SETTINGS, RECONNECT }

/** State-based navigation: Connect on first run, otherwise the chat; Settings and Connection from there. */
@Composable
fun AppNav(container: AppContainer) {
    var screen by rememberSaveable { mutableStateOf(if (container.credentials.load() == null) Screen.CONNECT else Screen.CHAT) }
    // Each visit gets a fresh view model, so a finished connect never replays.
    var connectVisit by rememberSaveable { mutableIntStateOf(0) }
    fun go(to: Screen) {
        if (to == Screen.CONNECT || to == Screen.RECONNECT) connectVisit++
        screen = to
    }

    when (screen) {
        Screen.CONNECT, Screen.RECONNECT -> {
            val reconnect = screen == Screen.RECONNECT
            val vm = viewModel(key = "connect-$connectVisit") {
                ConnectViewModel(
                    current = if (reconnect) container.credentials.load() else null,
                    willClearCache = container::willClearCache,
                    save = container::connect,
                )
            }
            LaunchedEffect(vm.state) { if (vm.state == ConnectState.Connected) screen = Screen.CHAT }
            if (reconnect) BackHandler { screen = Screen.SETTINGS }
            ConnectScreen(vm, onBack = if (reconnect) ({ screen = Screen.SETTINGS }) else null)
        }
        Screen.CHAT -> ChatPlaceholder(container, onSettings = { go(Screen.SETTINGS) })
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.CHAT }
            val scope = rememberCoroutineScope()
            val prefs = container.preferences
            val context = LocalContext.current
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            SettingsScreen(
                serverUrl = remember { container.credentials.load()?.baseUrl.orEmpty() },
                themeMode = prefs.themeMode.collectAsState().value,
                dynamicColor = prefs.dynamicColor.collectAsState().value,
                pureBlack = prefs.pureBlack.collectAsState().value,
                versionName = version,
                onConnection = { go(Screen.RECONNECT) },
                onThemeMode = { scope.launch { prefs.setThemeMode(it) } },
                onDynamicColor = { scope.launch { prefs.setDynamicColor(it) } },
                onPureBlack = { scope.launch { prefs.setPureBlack(it) } },
                onBack = { screen = Screen.CHAT },
            )
        }
    }
}

/** Replaced by the chat screen in the next task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPlaceholder(container: AppContainer, onSettings: () -> Unit) {
    val host = remember { container.credentials.load()?.baseUrl?.toHttpUrlOrNull()?.host.orEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
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
