package xyz.rycerz.apofis

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.Room
import androidx.work.WorkManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.Preferences
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.RunStarter
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.SendWorker
import xyz.rycerz.apofis.sync.withRepositoryLock

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
) {
    private val liveApi = api
    private val liveRepo = repo
    private val liveFollower = follower

    val isOpen: Boolean get() = open.get()
    val api: HermesApi get() = liveApi.also { checkOpen() }
    val repo: ChatRepository get() = liveRepo.also { checkOpen() }
    val follower: RunFollower get() = liveFollower.also { checkOpen() }

    internal suspend fun resume() {
        liveFollower.resumeAll()
        scope.launch { sendQueue.drain() } // may outlast a stop; the starter attaches no stream then
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

// ApiError causes can carry server or transport text, so only the class name is ever logged.
private fun logFailure(e: Throwable) = Log.w(TAG, "Background task failed: ${e.javaClass.name}")
private val logOnly = CoroutineExceptionHandler { _, e -> logFailure(e) }

/** The app's single object graph, owned by [ApofisApp]. [credentials] and [openDatabase] are replaceable for tests. */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result, which every write here must check
class AppContainer(
    private val app: Application,
    val credentials: CredentialStore = CredentialStore(app),
    openDatabase: () -> AppDatabase = { Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").build() },
) {
    val preferences = Preferences(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logOnly)

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

    private val foreground = Foreground(scope, resume = { connected()?.resume() }, pause = { current?.pause() }, onError = ::logFailure)

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

    /** True when [baseUrl] differs from the saved server; saving it then deletes that server's cache, so ask first. */
    suspend fun willClearCache(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val saved = connected()?.credentials?.baseUrl ?: cachePrefs.getString(CACHE_URL, null)
        saved != null && saved != baseUrl
    }

    /**
     * Switches to tested credentials: close the old graph, mark the switch, clear every table when the server
     * changes, write the credentials, build the new graph, record the cache owner, unmark. Any failure after the mark
     * ends disconnected with the credentials erased, and a mark left by process death forces that state on the next
     * start. Throws on failure. Not cancellable once started.
     */
    suspend fun connect(c: Credentials): Unit = withContext(NonCancellable + Dispatchers.IO) {
        switchLock.withLock {
            val old = synchronized(this@AppContainer) {
                loadLocked()
                switching = true
                current
            }
            old?.close()
            WorkManager.getInstance(app).cancelUniqueWork(SendWorker.WORK_NAME)
            try {
                if (!cachePrefs.edit().putBoolean(SWITCH_MARKER, true).commit()) {
                    publish(old?.credentials?.let(::build)) // nothing written yet: stay on the old server
                    throw IOException("switch marker not written")
                }
                var next: Connected? = null
                try {
                    if (cachePrefs.getString(CACHE_URL, null) != c.baseUrl) {
                        commitOrThrow(cachePrefs.edit().remove(CACHE_URL))
                        withRepositoryLock { db.clearAllTables() }
                    }
                    if (!credentials.save(c)) throw IOException("credentials not written")
                    next = build(c)
                    commitOrThrow(cachePrefs.edit().putString(CACHE_URL, c.baseUrl))
                    commitOrThrow(cachePrefs.edit().remove(SWITCH_MARKER))
                    publish(next)
                } catch (e: Exception) {
                    next?.close()
                    disconnect()
                    publish(null)
                    throw e
                }
            } finally {
                afterSwitch()
            }
        }
    }

    /** App is visible: follow active runs, then send what is queued. Failures are logged, never thrown. */
    fun onStart() = foreground.start()

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
            credentials.load()?.let(::build)
        }
    }

    /** Erases the credentials; the switch mark is removed only once that reached the disk, so a restart stays disconnected. */
    private fun disconnect() {
        val erased = runCatching { credentials.clear() }.getOrDefault(false)
        // A mark that cannot be removed is harmless: it only forces this same disconnected state again.
        if (erased && !cachePrefs.edit().remove(SWITCH_MARKER).commit()) Log.w(TAG, "Switch mark not removed")
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
        runCatching { if (current != null && db.pendingSendDao().allQueued().isNotEmpty()) SendWorker.schedule(app) }
            .onFailure(::logFailure)
        foreground.refresh()
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
        val starter = RunStarter { session, run ->
            // A run started while stopped or after the switch stays stored; the next start resumes it. A stop racing
            // this check is followed by its pause, which cancels the stream.
            if (foreground.started && open.get()) follower.onRunStarted(session, run)
        }
        val queue = SendQueue(api, db, starter, System::currentTimeMillis, { SendWorker.schedule(app) }, active = open::get)
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        follower = RunFollower(api, db, repo, runScope, onRunFinished = { if (open.get()) queue.drain() })
        return Connected(c, api, db, repo, queue, follower, open, runScope)
    }
}
