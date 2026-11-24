package xyz.rycerz.apofis.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.sync.cleanTitle
import xyz.rycerz.apofis.sync.isTemporaryKey
import xyz.rycerz.apofis.sync.newSessionId

private const val REFRESH_INTERVAL_MS = 30_000L
private const val LEAVE_TIMEOUT_MS = 10_000L

enum class PromptKind { ACTIONS, RENAME, DELETE }

/** The bar's new-chat button: on a never-created empty chat it makes it temporary ([TEMPORARY_OFF]) or ordinary again. */
enum class NewChatState { NEW, TEMPORARY_OFF, TEMPORARY_ON }

/** The bar's button [state], computed for the chat [key]: a tap acts only on what it showed. */
data class NewChat(val key: String?, val state: NewChatState)

/** Why an action failed, shown (wrapping, never cut) in the sheet or dialog that started it. */
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
    val gone: Boolean = false, // the chat is gone from this phone; kept only to show a running action's outcome
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

    /** Temporary chats whose leave is in flight: not listed and not selectable until it ends ([leaveIf]). */
    private val leavingFlow = MutableStateFlow<Set<String>>(emptySet())

    val rows: StateFlow<List<DrawerRow>> = combine(sessions, runs, queryFlow, combine(selectedFlow, leavingFlow, ::Pair), tick) { s, r, q, (sel, leaving), _ ->
        val zone = zone()
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        groupSessions(s.filter { it.key !in leaving }, q, sel, today, zone).map { if (it is DrawerRow.Chat) it.withRuns(r[it.key]) else it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Archived chats, most recently active first. */
    val archived: StateFlow<List<DrawerRow.Chat>> = combine(archivedSessions, runs, leavingFlow) { all, r, leaving ->
        all.filter { it.key !in leaving }.sortedByDescending { it.lastActiveAt ?: it.startedAt }
            .map { DrawerRow.Chat(it.key, it.title.orEmpty(), selected = false, running = false, needsInput = false).withRuns(r[it.key]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val newChatFlow = MutableStateFlow(NewChat(null, NewChatState.NEW))
    val newChatButton: StateFlow<NewChat> = newChatFlow.asStateFlow()

    private var lastRefresh: Long? = null
    private var refreshing = false

    init {
        load()
        viewModelScope.launch { selectedFlow.filterNotNull().collect { onSelected(it) } }
        // The toggle applies only to a never-created empty chat (messages need no observer: such a chat is never synced).
        viewModelScope.launch {
            selectedFlow.collectLatest { key ->
                if (key == null) {
                    newChatFlow.value = NewChat(null, NewChatState.NEW)
                    return@collectLatest
                }
                combine(repo.session(key), db.pendingSendDao().observe(key), composer.state(key)) { s, pending, c ->
                    NewChat(
                        key,
                        when {
                            s == null || s.existsOnServer || s.createStarted || pending.isNotEmpty() || c.sending -> NewChatState.NEW
                            isTemporaryKey(key) -> NewChatState.TEMPORARY_ON
                            else -> NewChatState.TEMPORARY_OFF
                        },
                    )
                }.collect { newChatFlow.value = it }
            }
        }
        // A chat removed from this phone (a refresh, another screen) must not stay open. Every list Room delivers counts:
        // the sessions StateFlow swallows an empty list equal to its placeholder, which can be the removal itself.
        viewModelScope.launch { repo.sessions().collect { reconcile() } }
        // Nor may a prompt for it, whoever refreshed: it watches its own chat's row (gone: missing or hidden; an
        // archived chat is still here). An idle prompt closes; a busy one, or one showing a failure, stays to show its
        // outcome but offers nothing more. Scoped by token, so a newer prompt is never closed for an older one's chat.
        viewModelScope.launch {
            promptFlow.map { it?.let { p -> p.token to p.key } }.distinctUntilChanged().collectLatest { subject ->
                val (token, key) = subject ?: return@collectLatest
                repo.session(key).collect { row ->
                    if (row == null || row.hidden) {
                        promptFlow.update { p ->
                            when {
                                p?.token != token -> p
                                p.busy || p.error != null -> p.copy(gone = true)
                                else -> null
                            }
                        }
                    }
                }
            }
        }
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
        refreshJob = viewModelScope.launch {
            val failed = try {
                repo.refreshSessions(onCached = composer::retain) // idle drafts of chats gone from the phone are forgotten
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
                        else -> {
                            val previous = selectedFlow.value
                            selectedFlow.value = key
                            leaveIf(previous, key)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // closed by a server switch: this view model is about to go away
            }
        }
    }

    /**
     * A notification tap: under the same lock as every selection, selects and stores [key] only if the row is usable
     * and [current] (the tap still belongs to this live graph) holds, checked in the same step. True when it did.
     */
    suspend fun selectTapped(key: String, current: () -> Boolean): Boolean = try {
        ops.withLock {
            if (!usable(key) || !current()) return@withLock false
            val previous = selectedFlow.value
            if (previous == key) reopenedFlow.value = Reopen(key, tokens.incrementAndGet()) else selectedFlow.value = key
            leaveIf(previous, key)
            onSelected(key) // stored now, not later by the collector of a graph that may be closing
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false // closed by a server switch
    }

    /** A chat that can be shown: its row exists here, is not hidden and is not being left. */
    private suspend fun usable(key: String) = key !in leavingFlow.value && db.sessionDao().get(key)?.hidden == false

    /**
     * Reuses the open chat while it is an ordinary never-created empty chat, else creates a local chat (no request) and
     * opens it; a temporary chat left this way is deleted ([leaveIf]).
     */
    suspend fun onNewChat(): String = ops.withLock {
        val previous = selectedFlow.value
        previous?.let { current ->
            val s = db.sessionDao().get(current)
            if (s != null && !isTemporaryKey(current) && !s.existsOnServer && !s.createStarted && !s.archived &&
                !repo.hasCachedMessages(current) && db.pendingSendDao().rows(current).isEmpty() && !composer.current(current).sending
            ) {
                return@withLock current
            }
        }
        openNew().also { leaveIf(previous, it) }
    }

    /**
     * The bar's toggle, for what the button showed ([shown]): the open never-created empty chat under a new key,
     * temporary or ordinary again, its draft moved with it. Dropped when [shown] is no longer the button's state for the
     * open chat. The row is replaced first, with the draft still under the old key, so text typed while the repository
     * lock is busy stays; once replaced, the outcome is completed even if the caller is cancelled. The new key, or null
     * when nothing changed.
     */
    suspend fun toggleTemporary(shown: NewChat): String? = ops.withLock {
        val old = selectedFlow.value
        if (old == null || shown.state == NewChatState.NEW || shown.key != old || newChatFlow.value != shown || composer.current(old).sending) {
            return@withLock null
        }
        withContext(NonCancellable) {
            val new = newSessionId(temporary = !isTemporaryKey(old))
            if (!repo.replaceEmptyLocal(old, new)) return@withContext null
            // A send claimed before the replacement fails on the missing row; its text then moves as the unsent entry.
            // Waits for this chat's own send only, never for other chats' deliveries.
            while (!composer.move(old, new)) composer.state(old).first { !it.sending }
            selectedFlow.value = new
            new
        }
    }

    /**
     * Opens a fresh ordinary chat while [selected] is still the open chat, and only if [claim] holds, checked with no
     * suspension before the selection changes; a lost claim leaves no row behind. Runs [fill] on the new chat, then
     * leaves [selected] ([leaveIf]), also when cancelled. The new key, or null when nothing changed.
     */
    suspend fun openFreshChatIf(selected: String, claim: () -> Boolean, fill: suspend (newKey: String) -> Unit): String? = ops.withLock {
        if (selectedFlow.value != selected) return@withLock null
        // Not cancellable from the insert to the claim's outcome: a fresh row is either claimed and selected or removed.
        val k = withContext(NonCancellable) {
            val k = try {
                repo.createLocalSession()
            } catch (_: Exception) {
                return@withContext null // closed by a server switch
            }
            if (!claim()) {
                db.sessionDao().delete(k) // the DAO: it works while the repository is closed
                return@withContext null
            }
            selectedFlow.value = k
            k
        } ?: return@withLock null
        val leave = leaveIf(selected, k, startNow = false) // set aside now, with the selection; deleted after fill
        try {
            fill(k)
        } finally {
            leave?.start()
        }
        k
    }

    /**
     * New chat. With [shown] (the bar's button), a toggle state toggles the open chat instead, and [onToggled] runs once
     * when that changed the key; a stale toggle does nothing. Without it (the drawer's row, /new), always a new chat. A
     * failure leaves the open chat as it is.
     */
    fun newChat(shown: NewChat? = null, onToggled: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                if (shown == null || shown.state == NewChatState.NEW) onNewChat() else if (toggleTemporary(shown) != null) onToggled()
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
        val clean = cleanTitle(title) ?: return
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
            if (p.key in leavingFlow.value) throw IllegalArgumentException("being left") // its leave owns the Delete
            deleteChat(p.key, p.running)
            if (selectedFlow.value == p.key) openNew()
        }
    }

    /**
     * The drawer's Delete of [key], for [confirmDelete] and a leave: a run [runsMentioned] did not cover throws
     * [RunAppeared]; [awaitRuns] waits after the stop until the stored runs of the chat are gone (the follower removes
     * each when it ends), so the DELETE does not meet the running turn.
     */
    private suspend fun deleteChat(key: String, runsMentioned: Boolean, awaitRuns: Boolean = false) {
        repo.beginDelete(key)
        var deleted = false
        try {
            if (db.activeRunDao().allForSession(key).isNotEmpty()) {
                if (!runsMentioned) throw RunAppeared()
                follower.stopAll(key)
            }
            if (awaitRuns) db.activeRunDao().observeAll().first { rows -> rows.none { it.sessionKey == key } }
            repo.delete(key)
            deleted = true
            composer.remove(key)
        } finally {
            // Even when cancelled while waiting for the lock: a mark left behind would block the chat until restart.
            withContext(NonCancellable) { repo.endDelete(key) }
            if (!deleted) connection.launch { sendQueue.drain() }
        }
    }

    /**
     * Leaving the temporary chat [previous] for [newKey] deletes it: once, without a dialog, in a job of its own that
     * holds no selection lock (the next selection never waits for it, a cancelled caller does not stop it), bounded at
     * ten seconds. If that fails the chat stays, listed as it is no longer open, and [newKey] gets a note.
     */
    private fun leaveIf(previous: String?, newKey: String, startNow: Boolean = true): Job? {
        if (previous == null || previous == newKey || !isTemporaryKey(previous)) return null
        leavingFlow.update { it + previous } // before anything suspends: from now on it cannot be listed or reopened
        return viewModelScope.launch(start = if (startNow) CoroutineStart.DEFAULT else CoroutineStart.LAZY) {
            try {
                withTimeoutOrNull(LEAVE_TIMEOUT_MS) {
                    if (db.sessionDao().get(previous) != null) deleteChat(previous, runsMentioned = true, awaitRuns = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // kept: the note below says so
            }
            // The row decides, not the timeout: a Delete that committed while its cleanup overran sets no note. Nothing is
            // written into a chat that is gone, or for a closed connection (its composer is the next one's): the open flag
            // is read last, with nothing suspending before the write. A read that fails sets nothing (a database closed by
            // a switch): eligibility could not be established.
            try {
                if (db.sessionDao().get(previous) != null && db.sessionDao().get(newKey) != null && connection.isOpen) {
                    composer.setNote(newKey, ComposerNote(NoteKind.TEMPORARY_NOT_DELETED))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // no note
            }
        }.also { job -> job.invokeOnCompletion { leavingFlow.update { it - previous } } }
    }

    /** For tests: actions started and not yet applied. */
    internal fun busyActions(): Int = inFlight.get()

    /** For tests: returns once no leave is in flight, so after each one's note decision (the set empties on completion). */
    internal suspend fun leavesEnded() {
        leavingFlow.first { it.isEmpty() }
    }

    /** For tests: the last drawer refresh, to join. */
    internal var refreshJob: Job? = null
        private set

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
        promptFlow.update { p -> if (p?.token == token && !p.busy && !p.gone) promptFor(kind, p.key) ?: p else p }
    }

    private fun promptFor(kind: PromptKind, key: String): Prompt? {
        if (key in leavingFlow.value) return null
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
            if (p != null && p.token == token && p.kind == kind && !p.busy && !p.gone) p.copy(busy = true, error = null).also { started = it } else p
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
