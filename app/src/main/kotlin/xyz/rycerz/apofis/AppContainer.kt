package xyz.rycerz.apofis

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.room.Room
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.Preferences
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.SendWorker
import xyz.rycerz.apofis.sync.withRepositoryLock

/** Everything that needs the server credentials. Replaced as a whole when they change. */
class Connected(
    val api: HermesApi,
    val db: AppDatabase,
    val repo: ChatRepository,
    val sendQueue: SendQueue,
    val follower: RunFollower,
)

private const val TAG = "Apofis"
private const val CACHE_URL = "cache_base_url" // the server the cached rows came from

/** The app's single object graph, owned by [ApofisApp]. */
class AppContainer(private val app: Application) {
    val credentials = CredentialStore(app)
    val preferences = Preferences(app)

    // ApiError causes can carry server or transport text, so only the class name is ever logged.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Background task failed: ${e.javaClass.name}") },
    )

    // One database for the app's lifetime; it outlives credential changes.
    private val db by lazy { Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").build() }
    private val cachePrefs = app.getSharedPreferences("cache", Context.MODE_PRIVATE)
    private var current: Connected? = null

    /** The graph for the stored credentials, built on first use; null when not connected. Safe from any thread. */
    @Synchronized
    fun connected(): Connected? = current ?: credentials.load()?.let(::build)?.also { current = it }

    /** True when saving [baseUrl] would delete cached chats of a different server. */
    suspend fun willClearCache(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val owner = cachePrefs.getString(CACHE_URL, null)
        owner != null && owner != baseUrl && db.sessionDao().all().isNotEmpty()
    }

    /** Saves tested credentials and rebuilds the graph. A new server URL first clears every table, pending sends included. */
    suspend fun connect(c: Credentials) {
        withContext(Dispatchers.IO) {
            if (cachePrefs.getString(CACHE_URL, null) != c.baseUrl) {
                synchronized(this@AppContainer) { current?.follower?.pauseAll() }
                withRepositoryLock { db.clearAllTables() }
                cachePrefs.edit(commit = true) { putString(CACHE_URL, c.baseUrl) }
            }
            credentials.save(c)
            synchronized(this@AppContainer) {
                current?.follower?.pauseAll()
                current = null
            }
        }
        onForeground() // connect is only called from the visible UI
    }

    /** App is visible: follow active runs and send what is queued. Failures are logged by [scope], never thrown. */
    fun onForeground() {
        scope.launch {
            val c = connected() ?: return@launch
            c.follower.resumeAll()
            c.sendQueue.drain()
        }
    }

    /** App left the screen: stop streaming; the runs continue on the server. */
    fun onBackground() {
        runCatching { synchronized(this) { current }?.follower?.pauseAll() }
            .onFailure { Log.w(TAG, "Pause failed: ${it.javaClass.name}") }
    }

    private fun build(c: Credentials): Connected {
        val api = HermesApi(c.baseUrl, c.apiKey)
        // Cycle: the queue needs a RunStarter (the follower), the follower needs the repository, the repository the
        // queue. The queue only calls the starter after a send, by which time the follower exists.
        lateinit var follower: RunFollower
        val queue = SendQueue(api, db, { session, run -> follower.onRunStarted(session, run) }, System::currentTimeMillis) {
            SendWorker.schedule(app)
        }
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        follower = RunFollower(api, db, repo, scope, onRunFinished = { queue.drain() })
        return Connected(api, db, repo, queue, follower)
    }
}
