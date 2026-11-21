package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unifiedpush.android.connector.UnifiedPush
import xyz.rycerz.apofis.api.ApiError

internal fun recordId(installId: String, seq: Int) = "apofis-push-$installId-$seq"
internal const val VERIFY_EVERY_MS = 3_600_000L
internal const val ROTATE_AFTER_MS = 30L * 24 * 3_600_000L
internal const val MAX_SEQ = 999_999
private const val MAX_CREATES = 3
private const val TAG = "Apofis"
private const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
private const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"

/** The connector calls the registrar needs; every one passes [PlainKeyManager]. */
interface UnifiedPushPort {
    fun distributors(c: Context): List<String>
    fun saved(c: Context): String?
    fun acked(c: Context): String?
    fun save(c: Context, d: String)

    /** [journal] (distributor, token) runs before each REGISTER broadcast; if it throws, nothing is sent. */
    fun register(c: Context, instance: String, journal: (String, String) -> Unit)

    /** Unregisters [r]: from its journaled token first, then through the connector. */
    fun unregister(c: Context, r: Registration)
}

object RealUnifiedPush : UnifiedPushPort {
    override fun distributors(c: Context): List<String> = UnifiedPush.getDistributors(c)
    override fun saved(c: Context) = UnifiedPush.getSavedDistributor(c)
    override fun acked(c: Context) = UnifiedPush.getAckDistributor(c)
    override fun save(c: Context, d: String) = UnifiedPush.saveDistributor(c, d)
    override fun register(c: Context, instance: String, journal: (String, String) -> Unit) =
        UnifiedPush.register(Journaling(c, journal), instance, null, null, PlainKeyManager)

    override fun unregister(c: Context, r: Registration) {
        // The connector deletes its token before broadcasting (U/UnifiedPush.kt:320-336), so a death there would lose the
        // UNREGISTER; ours goes first from the journal. Distributors ignore a repeated UNREGISTER.
        if (r.distributor != null && r.token != null) broadcastUnregister(c, r.distributor, r.token)
        UnifiedPush.unregister(c, r.instance, PlainKeyManager)
    }

    // As the connector sends it (U/UnifiedPush.kt:338-361).
    private fun broadcastUnregister(c: Context, distributor: String, token: String) {
        val pi = PendingIntent.getBroadcast(c, 0, Intent("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE)
        val intent = Intent(ACTION_UNREGISTER).setPackage(distributor).putExtra("token", token).putExtra("pi", pi)
        if (Build.VERSION.SDK_INT >= 34) {
            c.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        } else {
            c.sendBroadcast(intent)
        }
    }
}

/** Runs the journal before the connector's REGISTER broadcast leaves the app. */
private class Journaling(base: Context, private val journal: (String, String) -> Unit) : ContextWrapper(base) {
    private fun before(i: Intent) {
        if (i.action == ACTION_REGISTER) journal(i.`package` ?: error("no distributor"), i.getStringExtra("token") ?: error("no token"))
    }
    override fun sendBroadcast(intent: Intent) = before(intent).also { super.sendBroadcast(intent) }
    override fun sendBroadcast(intent: Intent, receiverPermission: String?) = before(intent).also { super.sendBroadcast(intent, receiverPermission) }
    override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) =
        before(intent).also { super.sendBroadcast(intent, receiverPermission, options) }
}

/** The connection changed during an operation: it ends without another request or result. */
private class Stale : Exception()

/** Every call checks [live] before it starts and before its result, value or error, is used. */
private class Guarded(private val a: RecordApi, private val live: () -> Boolean) : RecordApi {
    override val server get() = a.server
    private suspend fun <T> g(block: suspend () -> T): T {
        if (!live()) throw Stale()
        val result = try {
            block()
        } catch (e: Exception) {
            if (!live() && e !is CancellationException) throw Stale()
            throw e
        }
        if (!live()) throw Stale()
        return result
    }
    override suspend fun create(id: String, title: String, prompt: String) = g { a.create(id, title, prompt) }
    override suspend fun read(id: String) = g { a.read(id) }
    override suspend fun hide(id: String) = g { a.hide(id) }
}

/**
 * Registers this install with an allowed distributor and keeps its endpoint in one hidden record on the connected
 * server. Off and a server switch take effect on the phone: the old registration is committed for revocation before
 * any other step and then unregistered at the distributor, so its endpoint leads nowhere. The app never deletes a
 * record; old ones stay hidden on their server and the hook prefers the newest per install. A record is created under
 * a reserved id and a per-operation title, and adopted after an uncertain create only when that title and the
 * `api_server` source prove it is ours. Every operation finishes the persisted intents first ([reconcile]), runs one
 * at a time off the main thread, and ends as soon as the connection it was started under changes.
 */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result, which every write here must check
class PushRegistrar(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val api: () -> RecordApi?,
    private val connectionGeneration: () -> Long,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val policy: PushPolicy = pushPolicy,
    private val port: UnifiedPushPort = RealUnifiedPush,
    /** Schedules PublishWorker; called when a user-started operation leaves persisted work behind. */
    private val scheduleWork: () -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var failed = false
    @Volatile private var registeredThisProcess = false
    private val state = MutableStateFlow(compute())

    val ui: StateFlow<PushUi> = state.asStateFlow()

    /** The published record's endpoint (for the test push); null without a record. */
    val endpoint: String? get() = prefs.getString(Keys.ENDPOINT, null)?.takeIf { prefs.contains(Keys.RECORD) }

    fun currentInstance() = instanceOf(prefs)

    fun enable() {
        scope.launch { enableNow() }
    }

    internal suspend fun enableNow() {
        op { a ->
            reconcile(a)
            if (!enabled()) {
                if (allowed().isEmpty()) return@op
                commitLive { putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, generation() + 1) }
            }
            register()
        }
        afterUserOp()
    }

    /** Off is committed (with the registration to revoke) before anything else happens. */
    suspend fun disable() {
        op { _ ->
            if (enabled()) rotate(off = true) else finishRevocations()
        }
        afterUserOp()
    }

    /** Finishes persisted intents and publishes. True when nothing is left for this connection to retry. */
    suspend fun publishPending(): Boolean = op { a ->
        reconcile(a)
        if (a == null) return@op !failed && !needsServer()
        if (enabled()) publish(a, force = false)
        !failed && !prefs.contains(Keys.PENDING_ID)
    } ?: false

    /** Hourly unless [force]: distributor still there and answering, record present, hidden and current. */
    suspend fun verify(force: Boolean = false) {
        op { a ->
            val last = prefs.getLong(Keys.VERIFIED_AT, 0)
            if (!force && prefs.contains(Keys.VERIFIED_AT) && clock() - last in 0 until VERIFY_EVERY_MS) return@op
            reconcile(a)
            if (enabled()) {
                if (allowed().isEmpty()) {
                    rotate(off = true) // the distributor was uninstalled
                } else {
                    if (port.saved(context) == null || port.acked(context) == null) register() // unanswered or forgotten: ask again
                    if (a != null) {
                        publish(a, force = false)
                        check(a)
                    }
                }
            }
            commitLive { putLong(Keys.VERIFIED_AT, clock()) }
        }
        afterUserOp()
    }

    /**
     * First half of a server switch, called by AppContainer.connect before its switch marker: commits the revocation of
     * the current registration and a fresh instance (a new ntfy topic). Throws when that cannot be committed; the switch
     * must not go on without it. [finishSwitch] or any later start performs it.
     */
    suspend fun prepareSwitch() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    flushOrThrow()
                    if (enabled() || prefs.contains(Keys.REGISTRATION) || prefs.contains(Keys.RECORD)) {
                        if (!PushStore.locked { PushStore.commit(prefs) { beginRevocation() } }) throw IOException("push prefs not written")
                    }
                } finally {
                    state.value = compute()
                }
            }
        }
    }

    /** Second half of a switch, after the marker: unregisters the old registration and, only if push is on, registers anew. */
    suspend fun finishSwitch() {
        op { _ -> finishRevocations() }
        afterUserOp()
    }

    private suspend fun <T> op(block: suspend (RecordApi?) -> T): T? {
        val start = connectionGeneration() // at admission: an operation queued across a switch belongs to the old connection
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                failed = false // every operation derives the outcome afresh
                live = { connectionGeneration() == start }
                try {
                    flushOrThrow()
                    block(api()?.let { a -> Guarded(a, live) })
                } catch (_: Stale) {
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure(e)
                    failed = true
                    null
                } finally {
                    live = { true }
                    state.value = compute()
                }
            }
        }
    }

    // The running operation's connection check; applying any result commits only while it holds.
    private var live: () -> Boolean = { true }

    private fun afterUserOp() {
        if (needsWork(prefs, policy.distributors(context))) scheduleWork()
    }

    private fun enabled() = prefs.getBoolean(Keys.ENABLED, false)
    private fun generation() = prefs.getLong(Keys.GENERATION, 0)

    /** The policy's distributors that are installed, in policy order; read at every use, never from storage. */
    private fun allowed(): List<String> = port.distributors(context).let { installed -> policy.distributors(context).filter { it in installed } }

    private fun confirmed() = confirmedEndpoint(prefs, policy.distributors(context))

    private fun needsServer() = prefs.contains(Keys.PENDING_ID) || (enabled() && confirmed().let { it != null && it != prefs.getString(Keys.ENDPOINT, null) })

    private fun flushOrThrow() {
        if (!PushStore.flush(prefs)) throw IOException("push prefs not written")
    }

    /** A commit that applies a result: under the push lock, only while the operation's connection is still current. */
    private fun commitLive(edit: SharedPreferences.Editor.() -> Unit) {
        val ok = PushStore.locked {
            if (!live()) throw Stale()
            PushStore.commit(prefs, edit)
        }
        if (!ok) throw IOException("push prefs not written")
    }

    /** Walks every persisted intent: revocations, the receiver's events, push on without a usable registration. */
    private suspend fun reconcile(a: RecordApi?) {
        finishRevocations()
        if (prefs.contains(Keys.UNREGISTERED_INSTANCE)) {
            if (prefs.getString(Keys.UNREGISTERED_INSTANCE, null) == currentInstance() && enabled()) rotate(off = true)
            else commitLive { remove(Keys.UNREGISTERED_INSTANCE) }
        }
        if (!enabled()) return
        val r = registrationOf(prefs)
        if (r?.instance == currentInstance() && r.distributor !in policy.distributors(context)) {
            rotate(off = false) // journaled by a build with another policy: revoke it, register with an allowed distributor
        } else if (!registeredThisProcess || confirmed() == null) {
            // Once per process, and until the distributor confirms: an endpoint update lost to a failed commit is resent.
            register()
        }
    }

    private fun register() {
        val d = allowed().firstOrNull() ?: return
        val instance = currentInstance()
        port.save(context, d)
        port.register(context, instance) { distributor, token ->
            check(distributor in policy.distributors(context)) { "distributor not allowed" }
            commitLive { putString(Keys.REGISTRATION, Registration(instance, distributor, token).encode()) }
        }
        registeredThisProcess = true
    }

    /**
     * One commit, before any other step: the current registration joins the revocations, the instance advances (a fresh
     * topic), the record is left behind, and a revocation the receiver committed for this instance is consumed with it.
     */
    private fun SharedPreferences.Editor.beginRevocation(off: Boolean = false) {
        val revoke = registrationOf(prefs)?.takeIf { it.instance == currentInstance() } ?: Registration(currentInstance(), null, null)
        putString(Keys.REVOKE, (revocationsOf(prefs) + revoke).joinToString("\n") { it.encode() })
        putLong(Keys.GENERATION, generation() + 1)
        if (off || prefs.getString(Keys.UNREGISTERED_INSTANCE, null) == currentInstance()) putBoolean(Keys.ENABLED, false)
        for (k in listOf(Keys.REGISTRATION, Keys.RECORD, Keys.RECORD_TITLE, Keys.ENDPOINT, Keys.PUBLISHED_AT, Keys.PENDING_ID,
            Keys.PENDING_ENDPOINT, Keys.PENDING_TITLE, Keys.ENDPOINT_NEW, Keys.ENDPOINT_NEW_TOKEN, Keys.UNREGISTERED_INSTANCE)) remove(k)
    }

    private fun rotate(off: Boolean) {
        commitLive { beginRevocation(off) }
        finishRevocations()
    }

    /** Unregisters every committed revocation; re-registers if push is on; then clears them. Repeating is harmless. */
    private fun finishRevocations() {
        val pending = revocationsOf(prefs)
        if (pending.isEmpty()) return
        for (r in pending) port.unregister(context, r)
        if (enabled()) register()
        commitLive { remove(Keys.REVOKE) }
    }

    private suspend fun check(a: RecordApi) {
        val record = prefs.getString(Keys.RECORD, null) ?: return
        val row = a.read(record)
        // Gone, retitled, or a turn ran in it (a hide would then reach rows the app never made): a new record replaces it.
        if (row == null || row.title != prefs.getString(Keys.RECORD_TITLE, null) || row.source != "api_server" ||
            row.messageCount != 0 || row.parent != null || row.endReason != null
        ) {
            return publish(a, force = true)
        }
        if (!row.hidden || row.pinned) a.hide(record)
        if (clock() - prefs.getLong(Keys.PUBLISHED_AT, 0) >= ROTATE_AFTER_MS) publish(a, force = true)
    }

    private suspend fun publish(a: RecordApi, force: Boolean) {
        val target = confirmed() ?: return // waiting for this registration's NEW_ENDPOINT
        val checked = checkEndpoint(target, policy.origins(context), policy.loopbackOrigin(context))
        val endpoint = checked.url ?: return fail("endpoint refused: ${checked.reason}")
        if (!force && prefs.contains(Keys.RECORD) && !prefs.contains(Keys.PENDING_ID) && prefs.getString(Keys.ENDPOINT, null) == endpoint) return
        if (prefs.contains(Keys.PENDING_ID) && prefs.getString(Keys.PENDING_ENDPOINT, null) != endpoint) commitLive { removePending() }
        repeat(MAX_CREATES) {
            val id = prefs.getString(Keys.PENDING_ID, null) ?: reserve(endpoint) ?: return fail("no id left")
            val title = prefs.getString(Keys.PENDING_TITLE, null)!!
            val ours = try {
                a.create(id, title, prompt(endpoint)) || a.read(id)?.let { it.title == title && it.source == "api_server" } == true
            } catch (e: ApiError.Conflict) {
                if (e.code != "session_deleted") throw e
                false // retired: never reused
            }
            if (ours) return finish(a, id, title, endpoint)
            commitLive { removePending() } // a stranger's row holds the id, or it is retired: never adopted
        }
        fail("no free id")
    }

    private fun SharedPreferences.Editor.removePending() {
        for (k in listOf(Keys.PENDING_ID, Keys.PENDING_ENDPOINT, Keys.PENDING_TITLE)) remove(k)
    }

    private fun reserve(endpoint: String): String? {
        val seq = prefs.getInt(Keys.SEQ, 0) + 1
        if (seq > MAX_SEQ) return null
        val install = prefs.getString(Keys.INSTALL_ID, null) ?: UUID.randomUUID().toString()
        val id = recordId(install, seq)
        val marker = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val title = policy.recordTitle(context, id, marker)
        commitLive {
            putString(Keys.INSTALL_ID, install).putInt(Keys.SEQ, seq)
            putString(Keys.PENDING_ID, id).putString(Keys.PENDING_ENDPOINT, endpoint).putString(Keys.PENDING_TITLE, title)
            policy.recordIntent(prefs, this, id, title)
        }
        return id
    }

    private suspend fun finish(a: RecordApi, id: String, title: String, endpoint: String) {
        a.hide(id)
        commitLive {
            putString(Keys.RECORD, id).putString(Keys.RECORD_TITLE, title).putString(Keys.ENDPOINT, endpoint)
            putLong(Keys.PUBLISHED_AT, clock())
            removePending()
        }
    }

    private fun prompt(endpoint: String) = buildJsonObject { put("v", 1); put("endpoint", endpoint) }.toString()

    private fun fail(reason: String) {
        Log.w(TAG, "Push not published: $reason")
        failed = true
    }

    private fun compute(): PushUi {
        val distributor = registrationOf(prefs)?.distributor?.takeIf { it in policy.distributors(context) }
            ?: allowed().firstOrNull() ?: NTFY_PACKAGE
        val s = when {
            PushStore.isDirty(prefs) -> PushState.Registering // memory may differ from disk: nothing is confirmed
            !enabled() -> if (allowed().isEmpty()) PushState.NoDistributor else PushState.Off
            failed -> PushState.ServerError(distributor)
            // The record holds the endpoint the distributor confirmed for the current registration's token.
            prefs.contains(Keys.RECORD) && !prefs.contains(Keys.PENDING_ID) && confirmed().let { it != null && it == prefs.getString(Keys.ENDPOINT, null) } ->
                PushState.On(distributor)
            else -> PushState.Registering
        }
        return PushUi(s)
    }

    // Server and transport text can carry the endpoint or ids: only the class name is ever logged.
    private fun logFailure(e: Throwable) = Log.w(TAG, "Push step failed: ${e.javaClass.name}")
}
