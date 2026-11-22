package xyz.rycerz.apofis.ui

import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.ViewModelStoreOwner
import xyz.rycerz.apofis.ui.drawer.ArchivedScreen
import xyz.rycerz.apofis.ui.drawer.DrawerActions
import xyz.rycerz.apofis.ui.drawer.DrawerContent
import xyz.rycerz.apofis.ui.drawer.DrawerViewModel
import xyz.rycerz.apofis.ui.drawer.PromptHost
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
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatViewModel
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.foundation.layout.RowScope
import xyz.rycerz.apofis.ui.connect.ConnectScreen
import xyz.rycerz.apofis.ui.connect.ConnectState
import xyz.rycerz.apofis.ui.connect.ConnectViewModel
import xyz.rycerz.apofis.ui.connect.LOCAL_NETWORK_MIN_SDK
import xyz.rycerz.apofis.ui.connect.LOCAL_NETWORK_PERMISSION
import xyz.rycerz.apofis.ui.settings.SettingsScreen
import xyz.rycerz.apofis.ui.settings.NotificationsSetting
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.mutableIntStateOf
import xyz.rycerz.apofis.notify.rememberAskNotificationsOnce

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
    var taps by remember(conn) { mutableIntStateOf(0) } // taps this graph selected, for the chat route to show
    // Without a connection only Connect makes sense; a failed reconnect that ended disconnected stays on its screen.
    val screen = when {
        conn == null && chosen != Screen.RECONNECT -> Screen.CONNECT
        else -> chosen ?: Screen.CHAT
    }

    // Holds the drawer (open chat, drafts): outlives the chat screen (Settings, Connection), not the connection.
    val chatOwner = key(conn) { rememberViewModelStoreOwner() }

    // The one consumer of a tap. Its epoch is checked where the drawer selects and stores the chat, in the same step;
    // only a selection that happened changes the screen, so a stale tap changes nothing.
    if (conn != null) {
        val drawer = drawerOf(container, conn, chatOwner)
        // Keyed by the drawer only: acknowledging a tap must not cancel the selection it is making.
        LaunchedEffect(drawer) {
            container.consumeTaps(conn, drawer::selectTapped) {
                taps++
                chosen = Screen.CHAT
            }
        }
    }

    when (screen) {
        Screen.CONNECT, Screen.RECONNECT -> key(screen) {
            val reconnect = screen == Screen.RECONNECT
            // Scoped to this screen's presence: leaving clears the view model, which cancels its probe and drops the key.
            val owner = rememberViewModelStoreOwner()
            val context = LocalContext.current.applicationContext
            val vm = viewModel(viewModelStoreOwner = owner) {
                ConnectViewModel(
                    current = if (reconnect) conn?.credentials else null,
                    willClearCache = container::willClearCache,
                    save = container::connect,
                    failedBefore = !reconnect && container.consumeSwitchFailure(),
                    back = container.connectBack,
                    localNetworkGranted = {
                        Build.VERSION.SDK_INT < LOCAL_NETWORK_MIN_SDK ||
                            context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    },
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
        Screen.CHAT -> if (conn != null) ChatRoute(container, conn, chatOwner, taps, onSettings = { chosen = Screen.SETTINGS }, onConnection = { chosen = Screen.RECONNECT })
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
                notifications = { NotificationsSetting(container.push, container.testPush, container.notifyPrefs, container.scope) },
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

/**
 * The chat with the drawer. [owner] lives as long as the connection and holds the drawer, which owns the open chat. Only the chat on screen has a view model: switching chats clears the old one.
 * Choosing never leaves a blank screen: while loading, or after a failure (with Retry), the chat screen itself shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatRoute(container: AppContainer, conn: Connected, owner: ViewModelStoreOwner, taps: Int, onSettings: () -> Unit, onConnection: () -> Unit) {
    val prefs = container.preferences
    val drawer = drawerOf(container, conn, owner)
    val selected by drawer.selected.collectAsState()
    val loadFailed by drawer.loadFailed.collectAsState()
    val rows by drawer.rows.collectAsState()
    val archived by drawer.archived.collectAsState()
    val query by drawer.query.collectAsState()
    val refreshFailed by drawer.refreshFailed.collectAsState()
    val prompt by drawer.prompt.collectAsState()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue }.collect {
            if (it == DrawerValue.Open) {
                keyboard?.hide()
                focus.clearFocus()
                drawer.onOpen()
            }
        }
    }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    LaunchedEffect(showArchived, archived.isEmpty()) { if (showArchived && archived.isEmpty()) showArchived = false }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        showArchived = false // a tapped chat is shown, not the archive or the drawer over it
        drawerState.close()
    }
    val askNotifications = rememberAskNotificationsOnce(container.notifyPrefs) // this activity's launcher

    val open = object : DrawerActions by drawer {
        override fun onSelect(key: String) {
            drawer.onSelect(key)
            showArchived = false
            scope.launch { drawerState.close() }
        }
    }
    val drawerButton: @Composable () -> Unit = {
        DrawerButton {
            keyboard?.hide()
            scope.launch { drawerState.open() }
        }
    }
    val newChatButton: @Composable RowScope.() -> Unit = {
        ApofisIconButton(onClick = drawer::newChat, contentDescription = stringResource(R.string.drawer_new_chat)) {
            Icon(painterResource(R.drawable.lucide_square_pen), contentDescription = null, Modifier.size(20.dp))
        }
    }
    if (showArchived) {
        BackHandler { showArchived = false }
        ArchivedScreen(archived, open, onBack = { showArchived = false })
    } else {
        ModalNavigationDrawer(
            drawerContent = {
                ModalDrawerSheet(Modifier.fillMaxWidth(0.75f), drawerContainerColor = MaterialTheme.colorScheme.surface) {
                    DrawerContent(
                        rows,
                        query,
                        refreshFailed,
                        hasArchived = archived.isNotEmpty(),
                        actions = open,
                        onQuery = drawer::onQuery,
                        onNewChat = {
                            drawer.newChat()
                            scope.launch { drawerState.close() }
                        },
                        onArchived = { showArchived = true },
                        onSettings = onSettings,
                    )
                }
            },
            drawerState = drawerState,
        ) {
            val sessionKey = selected
            if (sessionKey == null) {
                ChatScreen(
                    ChatUiState("", "", if (loadFailed) StatusLine.LOAD_FAILED else StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, ""),
                    object : ChatActions {},
                    onOpenConnection = onConnection,
                    onRetryLoad = drawer::retryLoad,
                    navigationIcon = drawerButton,
                    appBarActions = newChatButton,
                )
            } else {
                key(sessionKey) {
                    // Scoped to this chat being on screen: leaving clears the view model, its collectors and its
                    // connectivity subscription. Its draft lives in the connection's holder, not here.
                    val chatOwner = rememberViewModelStoreOwner()
                    val vm = viewModel(viewModelStoreOwner = chatOwner) { ChatViewModel(sessionKey, conn, container.online) }
                    // The first send the composer accepts asks for the notification permission, once ever, through the
                    // launcher of the composition that is collecting (the view model holds none).
                    LaunchedEffect(vm, askNotifications) { vm.sendAccepted.collect { askNotifications() } }
                    LaunchedEffect(vm) { drawer.reopened.drop(1).filterNotNull().filter { it.key == sessionKey }.collect { vm.onOpened() } }
                    val state by vm.state.collectAsState()
                    // Its notification is never posted while it is on screen in a started activity.
                    LifecycleStartEffect(conn, sessionKey) {
                        container.chatShown(conn.generation, sessionKey, true)
                        onStopOrDispose { container.chatShown(conn.generation, sessionKey, false) }
                    }
                    ChatScreen(state, vm, onOpenConnection = onConnection, navigationIcon = drawerButton, appBarActions = newChatButton, currentState = { vm.state.value })
                }
            }
        }
    }
    prompt?.let { PromptHost(it, drawer) }
}

/** The chat bar's drawer button: a 20 dp glyph in a 40 dp box, 48 dp target. */
@Composable
internal fun DrawerButton(onClick: () -> Unit) {
    ApofisIconButton(onClick, contentDescription = stringResource(R.string.drawer_open), visualSize = 40.dp) {
        Icon(painterResource(R.drawable.lucide_menu), contentDescription = null, Modifier.size(20.dp))
    }
}

/** The drawer of [conn], held by [owner]; the same instance wherever it is asked for. */
@Composable
private fun drawerOf(container: AppContainer, conn: Connected, owner: ViewModelStoreOwner): DrawerViewModel {
    val prefs = container.preferences
    return viewModel(viewModelStoreOwner = owner) { DrawerViewModel(conn, lastChat = prefs.lastChat, onSelected = { prefs.lastChat = it }) }
}
