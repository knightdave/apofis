package xyz.rycerz.apofis

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
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
 * Everything bound to one server and key. Closed as a whole when the credentials change: its coroutines stop and its
 * send queue refuses to send or write, so nothing of it can reach the next server's rows.
 */
class Connected internal constructor(
    val credentials: Credentials,
    val api: HermesApi,
    val db: AppDatabase,
    val repo: ChatRepository,
    val sendQueue: SendQueue,
    val follower: RunFollower,
    private val open: AtomicBoolean,
    private val scope: CoroutineScope,
) {
    val isOpen: Boolean get() = open.get()

    internal fun close() {
        open.set(false)
        follower.pauseAll()
        scope.cancel()
    }
}

private const val TAG = "Apofis"
private const val CACHE_URL = "cache_base_url" // the server the cached rows came from

private const val ANY_CACHED_ROW = "SELECT EXISTS(SELECT 1 FROM SessionEntity) OR EXISTS(SELECT 1 FROM MessageEntity) " +
    "OR EXISTS(SELECT 1 FROM PendingSendEntity) OR EXISTS(SELECT 1 FROM ActiveRunEntity) OR EXISTS(SELECT 1 FROM ReplyOutcomeEntity)"

// ApiError causes can carry server or transport text, so only the class name is ever logged.
private val logOnly = CoroutineExceptionHandler { _, e -> Log.w(TAG, "Background task failed: ${e.javaClass.name}") }

/** The app's single object graph, owned by [ApofisApp]. [credentials] and [openDatabase] are replaceable for tests. */
class AppContainer(
    private val app: Application,
    val credentials: CredentialStore = CredentialStore(app),
    openDatabase: () -> AppDatabase = { Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").build() },
) {
    val preferences = Preferences(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logOnly)

    // One database for the app's lifetime; it outlives credential changes.
    private val db by lazy(openDatabase)
    private val cachePrefs = app.getSharedPreferences("cache", Context.MODE_PRIVATE)

    // Guarded by this: the graph, whether stored credentials were read, and whether a switch is in progress.
    @Volatile private var current: Connected? = null
    private var loaded = false
    private var switching = false
    private val published = MutableStateFlow<Connected?>(null)
    private val switchLock = Mutex()

    // Lifecycle: [started] is guarded by [gate]; resumes are serialized by [lifecycleLock].
    private val gate = Any()
    private var started = false
    private val lifecycleLock = Mutex()

    /** The current graph for the UI; null when not connected. Changes only when a switch has finished. */
    val connection: StateFlow<Connected?> = published.asStateFlow()

    /**
     * The current graph, reading the stored credentials on first use (call [load] from the main thread instead).
     * Null when not connected or while a switch is in progress. For SendQueueProvider: never a cached instance.
     */
    fun connected(): Connected? = synchronized(this) {
        if (switching) return null
        connectedLocked()
        published.value = current
        current
    }

    /** [connected] off the main thread: the first call decrypts the stored key. */
    suspend fun load(): Connected? = withContext(Dispatchers.IO) { connected() }

    /** True when saving [baseUrl] would delete anything cached for another server (or of unknown origin). */
    suspend fun willClearCache(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        cachePrefs.getString(CACHE_URL, null) != baseUrl && db.query(ANY_CACHED_ROW, null).use { it.moveToFirst() && it.getInt(0) == 1 }
    }

    /**
     * Switches to tested credentials, in order: close the old graph, clear every table when the server changes,
     * write the credentials, build the new graph, record the cache owner last. Throws if any step fails; the app is
     * then still on the old server with its data (nothing destructive happened yet) or disconnected. Not cancellable
     * once started, so leaving the screen cannot stop it halfway.
     */
    @SuppressLint("UseKtx") // the KTX edit drops commit()'s result, which every step here must check
    suspend fun connect(c: Credentials): Unit = withContext(NonCancellable + Dispatchers.IO) {
        switchLock.withLock {
            val old = synchronized(this@AppContainer) {
                connectedLocked()
                switching = true
                current
            }
            var destructive = false
            try {
                old?.close()
                WorkManager.getInstance(app).cancelUniqueWork(SendWorker.WORK_NAME)
                if (cachePrefs.getString(CACHE_URL, null) != c.baseUrl) {
                    destructive = true
                    if (!cachePrefs.edit().remove(CACHE_URL).commit()) throw IOException("cache owner not written")
                    withRepositoryLock { db.clearAllTables() }
                }
                if (!credentials.save(c)) throw IOException("credentials not written")
                val next = build(c)
                if (!cachePrefs.edit().putString(CACHE_URL, c.baseUrl).commit()) {
                    next.close()
                    throw IOException("cache owner not written")
                }
                publish(next)
            } catch (e: Exception) {
                publish(if (destructive) disconnect() else restore(old?.credentials))
                throw e
            } finally {
                scope.launch { resumeIfStarted() }
            }
        }
    }

    /** App is visible: follow active runs, then send what is queued. Failures are logged by [scope], never thrown. */
    fun onStart() {
        synchronized(gate) { started = true }
        scope.launch { resumeIfStarted() }
    }

    /** App left the screen: stop every stream at once; the runs continue on the server. */
    fun onStop() = synchronized(gate) {
        started = false
        runCatching { current?.follower?.pauseAll() }.onFailure { Log.w(TAG, "Pause failed: ${it.javaClass.name}") }
    }

    private suspend fun resumeIfStarted() = lifecycleLock.withLock {
        val c = connected() ?: return@withLock
        if (!synchronized(gate) { started }) return@withLock
        c.follower.resumeAll()
        // A stop that arrived while resumeAll read the database: the streams it just attached must not stay.
        synchronized(gate) { if (!started || !c.isOpen) c.follower.pauseAll() }
        c.sendQueue.drain() // may finish in the background; the starter below attaches no stream after a stop
    }

    private fun connectedLocked() {
        if (!loaded) {
            loaded = true
            current = credentials.load()?.let(::build)
        }
    }

    private fun publish(next: Connected?) {
        synchronized(this) {
            current = next
            loaded = true
            switching = false
        }
        published.value = next
    }

    /** After a failure before anything was deleted: back to the old credentials if they are still the stored ones. */
    private fun restore(old: Credentials?): Connected? = runCatching {
        if (old != null && (credentials.load() == old || credentials.save(old))) build(old) else disconnect()
    }.getOrElse { disconnect() }

    private fun disconnect(): Connected? {
        runCatching { credentials.clear() }
        return null
    }

    private fun build(c: Credentials): Connected {
        val api = HermesApi(c.baseUrl, c.apiKey)
        val open = AtomicBoolean(true)
        val runScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + logOnly)
        // Cycle: the queue needs a RunStarter (the follower), the follower needs the repository, the repository the
        // queue. The queue only calls the starter after a send, by which time the follower exists.
        lateinit var follower: RunFollower
        val starter = RunStarter { session, run ->
            // A run started while stopped or after the switch is stored; the next start resumes it.
            synchronized(gate) { if (started && open.get()) follower.onRunStarted(session, run) }
        }
        val queue = SendQueue(api, db, starter, System::currentTimeMillis, { SendWorker.schedule(app) }, active = open::get)
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        follower = RunFollower(api, db, repo, runScope, onRunFinished = { if (open.get()) queue.drain() })
        return Connected(c, api, db, repo, queue, follower, open, runScope)
    }
}
