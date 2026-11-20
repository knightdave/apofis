package xyz.rycerz.apofis.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApiError

private const val REFRESH_INTERVAL_MS = 30_000L
const val TITLE_MAX = 120

enum class PromptKind { ACTIONS, RENAME, DELETE }

/** Why an action failed, shown as one line in the sheet or dialog that started it. */
enum class ActionError { NETWORK, KEY_REJECTED, NOT_FOUND, CLOSED, FAILED }

/** The open long-press sheet or dialog for one chat; [running] means a reply is in progress (delete stops it first). */
data class Prompt(
    val kind: PromptKind,
    val key: String,
    val title: String,
    val pinned: Boolean,
    val archived: Boolean,
    val running: Boolean,
    val error: ActionError? = null,
    val busy: Boolean = false,
)

/**
 * The chat list and the open chat ([selected]). The open chat lives here, not in the chat screen, so the drawer can
 * reuse it for a new chat and replace it when it is deleted or archived. [now] and [zone] are injected for tests.
 */
class DrawerViewModel(
    connected: Connected,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel(), DrawerActions, PromptActions {
    private val repo = connected.repo
    private val follower = connected.follower
    private val db = connected.db

    private val sessions = repo.sessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val archivedSessions = repo.sessions(archived = true).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Session key to the statuses of its active runs. */
    private val runs = db.activeRunDao().observeAll()
        .map { all -> all.groupBy({ it.sessionKey }, { it.status }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val queryFlow = MutableStateFlow("")
    val query: StateFlow<String> = queryFlow.asStateFlow()

    private val selectedFlow = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = selectedFlow.asStateFlow()

    private val refreshFailedFlow = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = refreshFailedFlow.asStateFlow()

    private val promptFlow = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = promptFlow.asStateFlow()

    private val tick = MutableStateFlow(0) // bumped on open, so "Today" follows the date even without a data change

    val rows: StateFlow<List<DrawerRow>> = combine(sessions, runs, queryFlow, selectedFlow, tick) { s, r, q, sel, _ ->
        val zone = zone()
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        groupSessions(s, q, sel, today, zone).map { if (it is DrawerRow.Chat) it.withRuns(r[it.key]) else it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Archived chats, most recently active first. */
    val archived: StateFlow<List<DrawerRow.Chat>> = combine(archivedSessions, runs) { s, r ->
        s.sortedByDescending { it.lastActiveAt ?: it.startedAt }
            .map { DrawerRow.Chat(it.key, it.title.orEmpty(), selected = false, running = false, needsInput = false).withRuns(r[it.key]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var lastRefresh: Long? = null
    private var refreshing = false
    private val opened = HashSet<String>() // chats shown since this view model started; their chat screen exists

    init {
        // The most recently active chat, or a new local one when there is none.
        viewModelScope.launch {
            try {
                val latest = repo.sessions().first().maxByOrNull { it.lastActiveAt ?: it.startedAt }?.key ?: repo.createLocalSession()
                if (selectedFlow.compareAndSet(null, latest)) opened += latest
            } catch (_: IllegalStateException) {
                // closed by a server switch: this view model is about to go away
            }
        }
    }

    fun onQuery(q: String) {
        queryFlow.value = q
    }

    /** The drawer opened: refreshes the list at most every 30 s, or always while it is empty; a failure keeps it. */
    fun onOpen() {
        tick.update { it + 1 }
        val t = now()
        val last = lastRefresh
        if (refreshing || (sessions.value.isNotEmpty() && last != null && t - last < REFRESH_INTERVAL_MS)) return
        lastRefresh = t
        refreshing = true
        viewModelScope.launch {
            try {
                repo.refreshSessions()
                refreshFailedFlow.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                refreshFailedFlow.value = true
            } finally {
                refreshing = false
            }
        }
    }

    /** Shows [key]. A chat shown before keeps its screen, so a stale one is fetched here (the first time, the screen does it). */
    override fun onSelect(key: String) {
        selectedFlow.value = key
        if (opened.add(key)) return
        viewModelScope.launch {
            try {
                val s = db.sessionDao().get(key) ?: return@launch
                if (s.existsOnServer && (s.stale || db.messageDao().rows(key).isEmpty())) repo.syncMessages(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the chat screen shows the cached rows; the next open tries again
            }
        }
    }

    /** Reuses the open chat while it is local-only and empty, else creates a local chat (no request) and opens it. */
    suspend fun onNewChat(): String {
        selectedFlow.value?.let { current ->
            val s = db.sessionDao().get(current)
            if (s != null && !s.existsOnServer && !s.archived && db.messageDao().rows(current).isEmpty() && db.pendingSendDao().rows(current).isEmpty()) {
                return current
            }
        }
        return openNew()
    }

    /** [onNewChat] for a button: a closed connection is ignored, as the screen is about to go away. */
    fun newChat() {
        viewModelScope.launch {
            try {
                onNewChat()
            } catch (_: IllegalStateException) {
            }
        }
    }

    override fun onLongPress(key: String) = show(PromptKind.ACTIONS, key)

    override fun onRenameRequest(key: String) = show(PromptKind.RENAME, key)

    override fun onDeleteRequest(key: String) = show(PromptKind.DELETE, key)

    override fun onDismiss() {
        promptFlow.update { if (it?.busy == true) it else null }
    }

    override fun onRename(key: String, title: String) {
        val clean = title.trim().take(TITLE_MAX)
        if (clean.isEmpty()) return
        act(key) { repo.rename(key, clean) }
    }

    override fun onPin(key: String, pinned: Boolean) = act(key) { repo.setPinned(key, pinned) }

    override fun onArchive(key: String, archived: Boolean) = act(key) {
        repo.setArchived(key, archived)
        if (archived && selectedFlow.value == key) openNew()
    }

    /** Deletes on the server and here. A reply in progress was announced in the dialog: it is stopped first. */
    override fun onDelete(key: String) = act(key) {
        if (runs.value[key] != null) follower.stop(key) // a failed stop throws: nothing is deleted
        repo.delete(key)
        if (selectedFlow.value == key) openNew()
    }

    private suspend fun openNew(): String = repo.createLocalSession().also {
        opened += it
        selectedFlow.value = it
    }

    private fun show(kind: PromptKind, key: String) {
        promptFlow.value = promptFor(kind, key) ?: return
    }

    private fun promptFor(kind: PromptKind, key: String): Prompt? {
        val s = (sessions.value + archivedSessions.value).firstOrNull { it.key == key } ?: return null
        return Prompt(kind, key, s.title.orEmpty(), s.pinned, s.archived, running = runs.value[key] != null)
    }

    /**
     * Runs an action on [key]: closes the open prompt on success, shows the error in it on failure. An action started
     * without a prompt (an accessibility action on the row) shows its error in the chat's action sheet.
     */
    private fun act(key: String, block: suspend () -> Unit) {
        if (promptFlow.value?.busy == true) return
        promptFlow.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                when (e) {
                    is ApiError.Network -> ActionError.NETWORK
                    ApiError.Unauthorized -> ActionError.KEY_REJECTED
                    ApiError.NotFound -> ActionError.NOT_FOUND
                    else -> ActionError.FAILED
                }
            } catch (_: IllegalStateException) {
                ActionError.CLOSED
            } catch (_: Exception) {
                ActionError.FAILED
            }
            promptFlow.update {
                if (error == null) null else (it?.takeIf { p -> p.key == key } ?: promptFor(PromptKind.ACTIONS, key))?.copy(busy = false, error = error)
            }
        }
    }
}

private fun DrawerRow.Chat.withRuns(statuses: List<String>?): DrawerRow.Chat {
    if (statuses.isNullOrEmpty()) return this
    val asking = "waiting_for_approval" in statuses
    return copy(running = !asking, needsInput = asking)
}
