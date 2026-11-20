package xyz.rycerz.apofis.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApiError

private const val REFRESH_INTERVAL_MS = 30_000L
const val TITLE_MAX = 120

enum class PromptKind { ACTIONS, RENAME, DELETE }

/** Why an action failed, shown as one line in the sheet or dialog that started it. */
enum class ActionError { NETWORK, KEY_REJECTED, NOT_FOUND, CLOSED, FAILED }

/**
 * The one open long-press sheet or dialog, for the chat [key]. [token] is new each time a prompt opens: every command
 * and every late completion names it, so nothing acts on a prompt the user no longer sees. [running]: the dialog says a
 * reply is in progress and will be stopped.
 */
data class Prompt(
    val kind: PromptKind,
    val key: String,
    val title: String,
    val pinned: Boolean,
    val archived: Boolean,
    val running: Boolean,
    val token: Long,
    val error: ActionError? = null,
    val busy: Boolean = false,
)

/** A run started after the delete dialog opened: the dialog now says so and needs confirming again. */
private class RunAppeared : Exception()

/**
 * The chat list and the open chat ([selected]). The open chat lives here so the drawer can reuse it for a new chat and
 * replace it when it is deleted, archived or gone after a refresh. New chat, selection and the actions that replace the
 * open chat run one at a time ([ops]). [lastChat] is the chat to restore; [onSelected] stores each new selection.
 */
class DrawerViewModel(
    private val connection: Connected,
    private val lastChat: String? = null,
    private val onSelected: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel(), DrawerActions, PromptActions {
    private val repo = connection.repo
    private val follower = connection.follower
    private val sendQueue = connection.sendQueue
    private val composer = connection.composer
    private val db = connection.db
    private val ops = Mutex()
    private val tokens = AtomicLong()
    private val inFlight = AtomicInteger()

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

    private val loadFailedFlow = MutableStateFlow(false)

    /** No chat could be chosen at start; [retryLoad] tries again. */
    val loadFailed: StateFlow<Boolean> = loadFailedFlow.asStateFlow()

    private val refreshFailedFlow = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = refreshFailedFlow.asStateFlow()

    /** A tap on the row of the chat already shown; [n] makes every tap a new value. */
    data class Reopen(val key: String, val n: Long)

    private val reopenedFlow = MutableStateFlow<Reopen?>(null)

    /** The chat on screen was opened again: it should run its "needs history?" check (see ChatViewModel.onOpened). */
    val reopened: StateFlow<Reopen?> = reopenedFlow.asStateFlow()

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

    init {
        load()
        viewModelScope.launch { selectedFlow.filterNotNull().collect { onSelected(it) } }
        // A chat removed from this phone (a refresh, another screen) must not stay open.
        viewModelScope.launch { sessions.drop(1).collect { reconcile() } }
    }

    fun retryLoad() = load()

    private fun load() {
        loadFailedFlow.value = false
        viewModelScope.launch {
            try {
                ops.withLock {
                    if (selectedFlow.value != null) return@withLock
                    val restored = lastChat?.takeIf { usable(it) }
                    selectedFlow.value = restored
                        ?: repo.sessions().first().maxByOrNull { it.lastActiveAt ?: it.startedAt }?.key
                        ?: repo.createLocalSession()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadFailedFlow.value = true
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
            val failed = try {
                repo.refreshSessions()
                false
            } catch (e: CancellationException) {
                refreshing = false
                throw e
            } catch (_: Exception) {
                true
            }
            refreshing = false
            refreshFailedFlow.value = failed
            if (!failed) reconcile()
        }
    }

    /**
     * The one way a row opens a chat. A chat gone from this phone or hidden is ignored (a stale row), or replaced by a new
     * local chat when it is the one shown; a tap on the chat already shown asks it to check its history again.
     */
    override fun onSelect(key: String) {
        viewModelScope.launch {
            try {
                ops.withLock {
                    when {
                        !usable(key) -> if (selectedFlow.value == key) openNew()
                        selectedFlow.value == key -> reopenedFlow.value = Reopen(key, tokens.incrementAndGet())
                        else -> selectedFlow.value = key
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // closed by a server switch: this view model is about to go away
            }
        }
    }

    /** A chat that can be shown: its row exists here and is not hidden. */
    private suspend fun usable(key: String) = db.sessionDao().get(key)?.hidden == false

    /** Reuses the open chat while it is local-only and empty, else creates a local chat (no request) and opens it. */
    suspend fun onNewChat(): String = ops.withLock {
        selectedFlow.value?.let { current ->
            val s = db.sessionDao().get(current)
            if (s != null && !s.existsOnServer && !s.archived && !repo.hasCachedMessages(current) && db.pendingSendDao().rows(current).isEmpty()) {
                return@withLock current
            }
        }
        openNew()
    }

    /** [onNewChat] for a button. A failure leaves the open chat as it is. */
    fun newChat() {
        viewModelScope.launch {
            try {
                onNewChat()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // closed by a server switch, or the database refused: nothing changed
            }
        }
    }

    // ---- prompts ----

    override fun onLongPress(key: String) {
        open(PromptKind.ACTIONS, key)
    }

    override fun onRenameRequest(key: String) {
        open(PromptKind.RENAME, key)
    }

    override fun onDeleteRequest(key: String) {
        open(PromptKind.DELETE, key)
    }

    /** An accessibility action: opens the chat's action sheet and pins from it, so an error shows there. */
    override fun onPin(key: String, pinned: Boolean) {
        open(PromptKind.ACTIONS, key)?.let { pin(it, pinned) }
    }

    /** Like [onPin], for Archive and Unarchive. */
    override fun onArchive(key: String, archived: Boolean) {
        open(PromptKind.ACTIONS, key)?.let { archive(it, archived) }
    }

    override fun openRename(token: Long) = reopen(token, PromptKind.RENAME)

    override fun openDelete(token: Long) = reopen(token, PromptKind.DELETE)

    override fun dismiss(token: Long) {
        promptFlow.update { if (it?.token == token && !it.busy) null else it }
    }

    override fun confirmRename(token: Long, title: String) {
        val clean = title.trim().take(TITLE_MAX)
        if (clean.isEmpty()) return
        act(token, PromptKind.RENAME) { repo.rename(it.key, clean) }
    }

    override fun pin(token: Long, pinned: Boolean) = act(token, PromptKind.ACTIONS) { repo.setPinned(it.key, pinned) }

    override fun archive(token: Long, archived: Boolean) = act(token, PromptKind.ACTIONS) { p ->
        ops.withLock {
            repo.setArchived(p.key, archived)
            if (archived && selectedFlow.value == p.key) openNew() // its draft stays for when it is unarchived
        }
    }

    /**
     * Deletes the chat of the open delete dialog [token], on the server and here. First the chat stops starting runs
     * (queued sends wait), then its active runs are read again: one the dialog did not mention turns the dialog into a
     * new prompt that says so and needs its own confirmation; otherwise every run is stopped, then DELETE is sent. If any
     * step fails, the chat can send again and what waited goes out.
     */
    override fun confirmDelete(token: Long) = act(token, PromptKind.DELETE) { p ->
        ops.withLock {
            repo.beginDelete(p.key)
            var deleted = false
            try {
                if (db.activeRunDao().allForSession(p.key).isNotEmpty()) {
                    if (!p.running) throw RunAppeared()
                    follower.stopAll(p.key)
                }
                repo.delete(p.key)
                deleted = true
                composer.remove(p.key)
            } finally {
                // Even when cancelled while waiting for the lock: a mark left behind would block the chat until restart.
                withContext(NonCancellable) { repo.endDelete(p.key) }
                if (!deleted) connection.launch { sendQueue.drain() }
            }
            if (selectedFlow.value == p.key) openNew()
        }
    }

    /** For tests: actions started and not yet applied. */
    internal fun busyActions(): Int = inFlight.get()

    /** Under [ops]: a new local chat, opened. */
    private suspend fun openNew(): String = repo.createLocalSession().also { selectedFlow.value = it }

    /** The open chat is gone from this phone (not merely archived): a new local chat replaces it. */
    private suspend fun reconcile() {
        try {
            ops.withLock {
                val current = selectedFlow.value ?: return@withLock
                if (!usable(current)) openNew()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // closed by a server switch: this view model is about to go away
        }
    }

    private fun open(kind: PromptKind, key: String): Long? {
        val prompt = promptFor(kind, key) ?: return null
        promptFlow.value = prompt
        return prompt.token
    }

    /** From the sheet to a dialog for the same chat, as a new prompt. */
    private fun reopen(token: Long, kind: PromptKind) {
        promptFlow.update { p -> if (p?.token == token && !p.busy) promptFor(kind, p.key) ?: p else p }
    }

    private fun promptFor(kind: PromptKind, key: String): Prompt? {
        val s = (sessions.value + archivedSessions.value).firstOrNull { it.key == key } ?: return null
        return Prompt(kind, key, s.title.orEmpty(), s.pinned, s.archived, running = runs.value[key] != null, token = tokens.incrementAndGet())
    }

    /**
     * Runs [block] for the open prompt [token] of [kind]; nothing happens when another prompt (or none) is open or it
     * is already busy. The outcome is applied only while that prompt is still the open one: success closes it, a
     * failure shows in it.
     */
    private fun act(token: Long, kind: PromptKind, block: suspend (Prompt) -> Unit) {
        var started: Prompt? = null
        promptFlow.update { p ->
            if (p != null && p.token == token && p.kind == kind && !p.busy) p.copy(busy = true, error = null).also { started = it } else p
        }
        val prompt = started ?: return
        inFlight.incrementAndGet()
        viewModelScope.launch {
            try {
                var error: ActionError? = null
                var runAppeared = false
                try {
                    block(prompt)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RunAppeared) {
                    runAppeared = true
                } catch (e: Exception) {
                    error = e.toActionError()
                }
                val outcome = { p: Prompt ->
                    when {
                        runAppeared -> p.copy(busy = false, running = true, token = tokens.incrementAndGet())
                        error != null -> p.copy(busy = false, error = error)
                        else -> null
                    }
                }
                promptFlow.update { if (it?.token == token) outcome(it) else it }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }
}

private fun Exception.toActionError() = when (this) {
    is ApiError.Network -> ActionError.NETWORK
    ApiError.Unauthorized -> ActionError.KEY_REJECTED
    ApiError.NotFound -> ActionError.NOT_FOUND
    is ApiError -> ActionError.FAILED
    is IllegalStateException -> ActionError.CLOSED
    else -> ActionError.FAILED
}

private fun DrawerRow.Chat.withRuns(statuses: List<String>?): DrawerRow.Chat {
    if (statuses.isNullOrEmpty()) return this
    val asking = "waiting_for_approval" in statuses
    return copy(running = !asking, needsInput = asking)
}
