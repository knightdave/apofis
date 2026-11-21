package xyz.rycerz.apofis

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.work.WorkManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.Composer
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MIGRATION_1_2
import xyz.rycerz.apofis.push.HermesRecordApi
import xyz.rycerz.apofis.push.PublishWorker
import xyz.rycerz.apofis.push.PushStore
import xyz.rycerz.apofis.push.PushRegistrar
import xyz.rycerz.apofis.push.RealUnifiedPush
import xyz.rycerz.apofis.push.UnifiedPushPort
import xyz.rycerz.apofis.push.TestPush
import xyz.rycerz.apofis.push.pushPolicy
import xyz.rycerz.apofis.push.pushPrefs
import xyz.rycerz.apofis.push.validateEndpoint
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.Preferences
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.RunStarter
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.SendWorker
import xyz.rycerz.apofis.sync.quarantineAttemptedSends
import xyz.rycerz.apofis.sync.withRepositoryLock
import xyz.rycerz.apofis.ui.chat.onlineFlow
import xyz.rycerz.apofis.ui.connect.ConnectBack

/**
 * Everything bound to one server and key. Closed as a whole when the credentials change. After [close]:
 * [api], [repo] and [follower] throw IllegalStateException("closed"); every request of the instance fails before it
 * leaves the phone; [sendQueue] refuses writes and drains nothing; its coroutines are cancelled.
 */
class Connected internal constructor(
    val credentials: Credentials,
    api: HermesApi,
    val db: AppDatabase,
    repo: ChatRepository,
    val sendQueue: SendQueue,
    follower: RunFollower,
    private val open: AtomicBoolean,
    private val scope: CoroutineScope,
    /** The composer of the cached chats: the container's one, shared by its connections. */
    val composer: Composer = Composer(scope),
) {
    private val liveApi = api
    private val liveRepo = repo
    private val liveFollower = follower

    val isOpen: Boolean get() = open.get()

    /** Runs [block] in the connection's scope: it outlives any screen and ends with the connection. */
    internal fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
    val api: HermesApi get() = liveApi.also { checkOpen() }
    val repo: ChatRepository get() = liveRepo.also { checkOpen() }
    val follower: RunFollower get() = liveFollower.also { checkOpen() }

    /** Only from the container's serialized lifecycle path; the follower's own gate decides what may stream. */
    internal suspend fun resume() {
        liveFollower.resumeAll()
        scope.launch { sendQueue.drain() } // may outlast a stop: the follower's closed gate then attaches no stream
        scope.launch { refreshQuietly(liveRepo, composer) } // other clients may have changed chats meanwhile
    }

    internal fun pause() = liveFollower.pauseAll()

    internal fun close() {
        open.set(false)
        liveFollower.pauseAll()
        scope.cancel()
    }

    private fun checkOpen() = check(isOpen) { "closed" }
}

private const val TAG = "Apofis"
internal const val CACHE_PREFS = "cache"
private const val CACHE_URL = "cache_base_url" // the server the cached rows came from
internal const val SWITCH_MARKER = "switch_in_progress" // present from the first write of a switch until its last

private val CACHED_TABLES = listOf("SessionEntity", "MessageEntity", "PendingSendEntity", "ActiveRunEntity", "ReplyOutcomeEntity")
private val ANY_CACHED_ROW = "SELECT " + CACHED_TABLES.joinToString(" OR ") { "EXISTS(SELECT 1 FROM $it)" }

/** Session metadata from the server; a failure changes nothing (no send rolled back, no history evicted). */
private suspend fun refreshQuietly(repo: ChatRepository, composer: Composer) {
    try {
        repo.refreshSessions(onCached = composer::retain)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // the next foreground, drawer open or finished run tries again
    }
}

// ApiError causes can carry server or transport text, so only the class name is ever logged.
private fun logFailure(e: Throwable) = Log.w(TAG, "Background task failed: ${e.javaClass.name}")
private val logOnly = CoroutineExceptionHandler { _, e -> logFailure(e) }

/** The app's single object graph, owned by [ApofisApp]. [credentials] and [openDatabase] are replaceable for tests. */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result, which every write here must check
class AppContainer(
    private val app: Application,
    val credentials: CredentialStore = CredentialStore(app),
    pushPort: UnifiedPushPort = RealUnifiedPush,
    openDatabase: () -> AppDatabase = { Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").addMigrations(MIGRATION_1_2).build() },
) {
    val preferences = Preferences(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logOnly)

    /** Device connectivity, one network callback for the whole app, registered only while someone listens. */
    val online: Flow<Boolean> by lazy { app.onlineFlow().shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1) }

    /** Back during a connect check; here so it outlives rotation. */
    val connectBack = ConnectBack()

    // One database for the app's lifetime; it outlives credential changes.
    private val db by lazy(openDatabase)
    private val cachePrefs = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    // Guarded by this.
    @Volatile private var current: Connected? = null
    private var loaded = false
    private var switching = false
    private var switchFailedBefore = false
    private val published = MutableStateFlow<Connected?>(null)
    private val switchLock = Mutex()

    // Incremented when a switch starts: push work begun on the old connection stops at its next step.
    private val connectionGeneration = AtomicLong()

    /** Push registration on the current server; its records live there, revocation debt per server. */
    val push by lazy {
        PushRegistrar(app, pushPrefs(app), api = { connected()?.let { HermesRecordApi(it.credentials.baseUrl, it.api) } },
            connectionGeneration = connectionGeneration::get, clock = System::currentTimeMillis, scope = scope, port = pushPort,
            scheduleWork = { PublishWorker.schedule(app) })
    }
    val testPush by lazy { TestPush(SystemClock::elapsedRealtime, OkHttpClient()) { validateEndpoint(it, pushPolicy.origins(app), pushPolicy.loopbackOrigin(app)) } }

    /**
     * Drafts, sends being stored and unsent text of the cached chats: shared by every connection, emptied only in the
     * step that clears the cached tables. Its own scope, so closing a connection does not cancel a send being stored.
     */
    internal val composer = Composer(CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + logOnly))

    /**
     * Test hooks: [beforeResume] runs on the lifecycle path after the started check, before the follower opens its gate;
     * [afterResume] once it has opened it; [beforeSwitch] at the start of a switch; [afterSwitchMarker] right after its marker.
     */
    internal var beforeResume: suspend () -> Unit = {}
    internal var afterResume: suspend () -> Unit = {}
    internal var beforeSwitch: () -> Unit = {}
    internal var afterSwitchMarker: () -> Unit = {}

    private val foreground = Foreground(scope, resume = { stillStarted ->
        val c = connected()
        if (c != null && stillStarted()) {
            beforeResume()
            c.resume()
            afterResume()
        }
    }, pause = { current?.pause() }, onError = ::logFailure)

    /** The current graph for the UI; null when not connected. Changes only when a switch has finished. */
    val connection: StateFlow<Connected?> = published.asStateFlow()

    /**
     * The current graph, reading the stored credentials on first use (call [load] from the main thread instead).
     * Null when not connected or while a switch is in progress. For SendQueueProvider: never a cached instance.
     */
    fun connected(): Connected? = synchronized(this) {
        if (switching) return null
        loadLocked()
        published.value = current
        current
    }

    /** [connected] off the main thread: the first call decrypts the stored key. */
    suspend fun load(): Connected? = withContext(Dispatchers.IO) { connected() }

    /** True once if the last process died during a switch (the app then starts disconnected); for the Connect screen. */
    fun consumeSwitchFailure(): Boolean = synchronized(this) {
        loadLocked()
        switchFailedBefore.also { switchFailedBefore = false }
    }

    /**
     * True when saving [baseUrl] needs the user's consent: a saved server exists and differs, or anything is cached
     * whose owner is unknown or differs. Saving then deletes that cache. Never cleared silently.
     */
    suspend fun willClearCache(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val owner = cachePrefs.getString(CACHE_URL, null)
        val saved = connected()?.credentials?.baseUrl ?: owner
        (saved != null && saved != baseUrl) || (owner != baseUrl && db.query(ANY_CACHED_ROW, null).use { it.moveToFirst() && it.getInt(0) == 1 })
    }

    /**
     * Switches to tested credentials: let sends being stored finish and close the old graph, mark the switch, clear
     * every table and the composer when the server changes, write the credentials, build the new graph, record the
     * cache owner, unmark. Any failure after the mark
     * ends disconnected with the credentials erased, and a mark left by process death forces that state on the next
     * start. Throws on failure. Not cancellable once started.
     */
    suspend fun connect(c: Credentials): Unit = withContext(NonCancellable + Dispatchers.IO) {
        switchLock.withLock {
            var old: Connected? = null
            var next: Connected? = null
            var done = false
            try {
                old = synchronized(this@AppContainer) {
                    loadLocked()
                    switching = true
                    current
                }
                PushStore.locked { connectionGeneration.incrementAndGet() } // ordered with the receiver's wake hand-off
                beforeSwitch()
                composer.settle() // sends being stored finish (or come back as unsent) before the old graph closes
                // The old registration's revocation is committed first (or the switch fails), then the marker, then the
                // push side effects: a death anywhere leaves the revocation for the next start to finish.
                push.prepareSwitch()
                commitOrThrow(cachePrefs.edit().putBoolean(SWITCH_MARKER, true))
                afterSwitchMarker()
                push.finishSwitch()
                old?.close()
                WorkManager.getInstance(app).cancelUniqueWork(SendWorker.WORK_NAME)
                if (cachePrefs.getString(CACHE_URL, null) != c.baseUrl) {
                    commitOrThrow(cachePrefs.edit().remove(CACHE_URL))
                    withRepositoryLock {
                        // Not clearAllTables(): it commits the deletes, then can fail in VACUUM before the composer is cleared.
                        db.runInTransaction { CACHED_TABLES.forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM `$it`") } }
                        composer.clear() // right after the commit: no draft outlives its chat, nothing survives into the new server
                    }
                    // Best effort: only shrinks the file; the cache counts as cleared whatever happens here.
                    runCatching {
                        db.query("PRAGMA wal_checkpoint(FULL)", null).close()
                        db.openHelper.writableDatabase.execSQL("VACUUM")
                    }.onFailure(::logFailure)
                } else if (old?.credentials?.apiKey != c.apiKey) {
                    // An unknown old key (an interrupted switch erased it) counts as changed. Under the marker, so a
                    // death before this commit forces another switch, which quarantines again.
                    quarantineAttemptedSends(db)
                }
                if (!credentials.save(c)) throw IOException("credentials not written")
                next = build(c)
                commitOrThrow(cachePrefs.edit().putString(CACHE_URL, c.baseUrl))
                commitOrThrow(cachePrefs.edit().remove(SWITCH_MARKER))
                publish(next)
                done = true
            } finally {
                if (!done) {
                    // Whatever failed (cancellation included, rethrown after this), the end state is disconnected with
                    // the credentials erased: published first, so no cleanup step can leave the container switching.
                    publish(null)
                    runCatching { old?.close() }.onFailure(::logFailure)
                    runCatching { next?.close() }.onFailure(::logFailure)
                    runCatching { WorkManager.getInstance(app).cancelUniqueWork(SendWorker.WORK_NAME) }.onFailure(::logFailure)
                    disconnect()
                }
                afterSwitch()
            }
        }
    }

    /** App is visible: follow active runs, then send what is queued. Failures are logged, never thrown. */
    fun onStart() = foreground.start()

    /** Returns once all lifecycle work queued so far has run; for tests. */
    internal suspend fun awaitLifecycle() = foreground.idle()

    /** App left the screen: stop every stream; the runs continue on the server. */
    fun onStop() = foreground.stop()

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        current = if (cachePrefs.contains(SWITCH_MARKER)) {
            switchFailedBefore = true
            disconnect()
            null
        } else {
            credentials.load()?.let(::build)?.also { scope.launch { scheduleQueuedSends() } }
        }
    }

    /** Erases the credentials; the switch mark is removed only once that reached the disk, so a restart stays disconnected. */
    private fun disconnect() {
        val erased = runCatching { credentials.clear() }.getOrDefault(false)
        // A mark that cannot be removed is harmless: it only forces this same disconnected state again.
        if (erased && !runCatching { cachePrefs.edit().remove(SWITCH_MARKER).commit() }.getOrDefault(false)) Log.w(TAG, "Switch mark not removed")
    }

    private fun publish(next: Connected?) {
        synchronized(this) {
            current = next
            loaded = true
            switching = false
        }
        published.value = next
    }

    /** Durable send work was cancelled for the switch: schedule it again for what is still queued, then resume. */
    private suspend fun afterSwitch() {
        runCatching { PublishWorker.recover(app) }.onFailure(::logFailure) // push work skipped while the switch ran
        if (current != null) scheduleQueuedSends()
        foreground.refresh()
    }

    // On every start with a connection and after every switch, so a process death between cancelling the durable
    // work and enqueueing it again cannot strand queued sends. Idempotent: unique work, KEEP.
    private suspend fun scheduleQueuedSends() {
        runCatching { if (db.pendingSendDao().allQueued().isNotEmpty()) SendWorker.ensureScheduled(app) }.onFailure(::logFailure)
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        if (!editor.commit()) throw IOException("preferences not written")
    }

    private fun build(c: Credentials): Connected {
        val open = AtomicBoolean(true)
        // Every request of a closed instance fails here, so a retained repository or follower cannot reach any server.
        val client = HermesApi.defaultClient().newBuilder()
            .addInterceptor { chain -> if (open.get()) chain.proceed(chain.request()) else throw IOException("connection closed") }
            .build()
        val api = HermesApi(c.baseUrl, c.apiKey, client)
        val runScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + logOnly)
        // Cycle: the queue needs a RunStarter (the follower), the follower needs the repository, the repository the
        // queue. The queue only calls the starter after a send, by which time the follower exists.
        lateinit var follower: RunFollower
        // The follower's own gate refuses a stream while the app is stopped; the run stays stored for the next start.
        val starter = RunStarter { session, run -> if (open.get()) follower.onRunStarted(session, run) }
        val queue = SendQueue(
            api, db, starter, System::currentTimeMillis, { SendWorker.schedule(app) }, active = open::get,
            settle = { if (open.get()) follower.settle(it) },
        )
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue, active = open::get)
        follower = RunFollower(api, db, repo, runScope, onRunFinished = {
            if (open.get()) {
                queue.drain()
                runScope.launch { refreshQuietly(repo, composer) } // not on the follower's own, non-cancellable path
            }
        }, active = open::get)
        return Connected(c, api, db, repo, queue, follower, open, runScope, composer)
    }
}
