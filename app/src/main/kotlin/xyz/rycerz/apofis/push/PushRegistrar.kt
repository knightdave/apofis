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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unifiedpush.android.connector.UnifiedPush
import xyz.rycerz.apofis.api.ApiError

internal fun recordId(installId: String, seq: Int) = "apofis-push-$installId-$seq"
private val RECORD_ID = Regex("apofis-push-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[1-9][0-9]{0,5}")
internal const val VERIFY_EVERY_MS = 3_600_000L
internal const val ROTATE_AFTER_MS = 30L * 24 * 3_600_000L
internal const val MAX_SEQ = 999_999
internal const val MAX_DEBT = 64
internal const val DEBT_RESERVE = 2 // publishes stop at 62, so turning off (record plus pending) always fits
internal const val SETTLE_MS = 15 * 60_000L // a create sent this long ago has landed or never will (client timeout is 2 min)
private const val MAX_CREATES = 3
private const val SWITCH_DRAIN_MS = 5_000L
private const val TAG = "Apofis"
private const val UNREGISTER = "UNREGISTER"
private const val DELETE = "DELETE"
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

/** Every call checks [live] before it starts and before its result is used. */
private class Guarded(private val a: RecordApi, private val live: () -> Boolean) : RecordApi {
    override val server get() = a.server
    private suspend fun <T> g(block: suspend () -> T): T {
        if (!live()) throw Stale()
        return block().also { if (!live()) throw Stale() }
    }
    override suspend fun create(id: String, title: String, prompt: String) = g { a.create(id, title, prompt) }
    override suspend fun read(id: String) = g { a.read(id) }
    override suspend fun hide(id: String) = g { a.hide(id) }
    override suspend fun delete(id: String) = g { a.delete(id) }
}

/** The old connection during a switch: all its calls together get [SWITCH_DRAIN_MS]; past that they fail like I/O. */
private class TimeBoxed(private val a: RecordApi) : RecordApi {
    private val deadline = System.nanoTime() + SWITCH_DRAIN_MS * 1_000_000
    override val server get() = a.server
    private suspend fun <T> t(block: suspend () -> T): T = try {
        withTimeout(maxOf(0, (deadline - System.nanoTime()) / 1_000_000)) { block() }
    } catch (_: TimeoutCancellationException) {
        throw IOException("switch cleanup time used up")
    }
    override suspend fun create(id: String, title: String, prompt: String) = t { a.create(id, title, prompt) }
    override suspend fun read(id: String) = t { a.read(id) }
    override suspend fun hide(id: String) = t { a.hide(id) }
    override suspend fun delete(id: String) = t { a.delete(id) }
}

/**
 * Registers this install with an allowed distributor and keeps its endpoint in one hidden record on the connected
 * server. Every operation commits its intent before its first side effect, and every operation starts by finishing
 * whatever intent is persisted ([reconcile]), so a restart completes any sequence. A record is created only under a
 * reserved id and a per-operation title, adopted only when that title and the `api_server` source prove it is ours,
 * deleted only after a fresh read proves it is ours and empty, and kept as debt until then. Operations run one at a
 * time, off the main thread, and end as soon as the connection changes.
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
                commit { putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, generation() + 1) }
            }
            register()
        }
        afterUserOp()
    }

    suspend fun disable() {
        op { a ->
            reconcile(a)
            if (enabled()) turnOff(a)
        }
        afterUserOp()
    }

    /** Finishes persisted intents, publishes, drains debt. True when nothing is left for this connection to retry. */
    suspend fun publishPending(): Boolean = op { a ->
        reconcile(a)
        if (a == null) return@op !failed && !needsServer()
        if (enabled()) step { publish(a, force = false) }
        val drained = step { drain(a) } == true
        !failed && drained && !prefs.contains(Keys.PENDING_ID)
    } ?: false

    /** Hourly unless [force]: distributor still there and answering, record present, hidden and current, debt drained. */
    suspend fun verify(force: Boolean = false) {
        op { a ->
            val last = prefs.getLong(Keys.VERIFIED_AT, 0)
            if (!force && prefs.contains(Keys.VERIFIED_AT) && clock() - last in 0 until VERIFY_EVERY_MS) return@op
            reconcile(a)
            if (enabled()) {
                if (allowed().isEmpty()) {
                    turnOff(a) // the distributor was uninstalled
                } else {
                    if (port.saved(context) == null || port.acked(context) == null) register() // unanswered or forgotten: ask again
                    if (a != null) step { publish(a, force = false); check(a) }
                }
            }
            if (a != null) step { drain(a) }
            commit { putLong(Keys.VERIFIED_AT, clock()) }
        }
        afterUserOp()
    }

    /**
     * Called by AppContainer.connect after the switch marker is committed and before the old connection closes ([old]
     * null when there is none). The current registration becomes revocation debt of its server, the instance rotates,
     * and, only if push is still on, an allowed distributor is registered anew; its endpoint is used once its
     * NEW_ENDPOINT arrives. Throws when the intent cannot be committed: the switch must not go on without it.
     */
    suspend fun forgetServer(old: RecordApi?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    flushOrThrow()
                    val boxed = old?.let(::TimeBoxed)
                    finishRevocation(boxed)
                    handleEvents(boxed) // a revocation the receiver committed wins over re-registering
                    if (enabled() || prefs.contains(Keys.RECORD) || prefs.contains(Keys.PENDING_ID)) {
                        commit { beginRevocation(old?.server) }
                        finishRevocation(boxed)
                    } else if (boxed != null) {
                        drain(boxed)
                    }
                } finally {
                    state.value = compute()
                }
            }
        }
        afterUserOp()
    }

    private suspend fun <T> op(block: suspend (RecordApi?) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val start = connectionGeneration()
            failed = false // every operation derives the outcome afresh
            try {
                flushOrThrow()
                block(api()?.let { a -> Guarded(a) { connectionGeneration() == start } })
            } catch (_: Stale) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(e)
                failed = true
                null
            } finally {
                state.value = compute()
            }
        }
    }

    /** One step's failure is recorded and does not stop the next step. */
    private suspend fun <T> step(block: suspend () -> T): T? = try {
        block()
    } catch (e: Stale) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFailure(e)
        failed = true
        null
    }

    private fun afterUserOp() {
        if (needsWork(prefs, policy.distributors(context))) scheduleWork()
    }

    private fun enabled() = prefs.getBoolean(Keys.ENABLED, false)
    private fun generation() = prefs.getLong(Keys.GENERATION, 0)

    /** The policy's distributors that are installed, in policy order; read at every use, never from storage. */
    private fun allowed(): List<String> = port.distributors(context).let { installed -> policy.distributors(context).filter { it in installed } }

    private fun newEndpoint() = prefs.getString(Keys.ENDPOINT_NEW, null)?.takeIf { prefs.getString(Keys.ENDPOINT_NEW_INSTANCE, null) == currentInstance() }

    private fun needsServer() = readDebt(prefs).isNotEmpty() || prefs.contains(Keys.PENDING_ID) ||
        (enabled() && newEndpoint().let { it != null && it != prefs.getString(Keys.ENDPOINT, null) })

    private fun flushOrThrow() {
        if (!PushStore.flush(prefs)) throw IOException("push prefs not written")
    }

    private fun commit(edit: SharedPreferences.Editor.() -> Unit) {
        if (!PushStore.commit(prefs, edit)) throw IOException("push prefs not written")
    }

    /** Walks every persisted intent: revocation, the receiver's events, an enable without a usable registration. */
    private suspend fun reconcile(a: RecordApi?) {
        finishRevocation(a)
        handleEvents(a)
        if (!enabled()) return
        val r = registrationOf(prefs)
        if (r?.instance == currentInstance() && r.distributor !in policy.distributors(context)) {
            // Journaled by a build with another policy: revoke it and register with an allowed distributor.
            commit { beginRevocation(a?.server) }
            finishRevocation(a)
        } else if (r?.instance != currentInstance()) {
            register()
        }
    }

    private fun register() {
        val d = allowed().firstOrNull() ?: return
        val instance = currentInstance()
        port.save(context, d)
        port.register(context, instance) { distributor, token ->
            check(distributor in policy.distributors(context)) { "distributor not allowed" }
            commit { putString(Keys.REGISTRATION, Registration(instance, distributor, token).encode()) }
        }
    }

    /**
     * One commit: the current registration to revoke, the record and an attempted pending id (with their titles) into
     * their server's debt, the next generation. The reserve keeps room for both.
     */
    private fun SharedPreferences.Editor.beginRevocation(fallbackServer: String?) {
        val server = prefs.getString(Keys.SERVER, null) ?: fallbackServer
        val add = listOfNotNull(entry(Keys.RECORD, Keys.RECORD_TITLE, 0), pendingEntry())
        if (server != null && add.isNotEmpty()) putDebt(server, readDebt(prefs)[server].orEmpty() + add)
        val revoke = registrationOf(prefs)?.takeIf { it.instance == currentInstance() } ?: Registration(currentInstance(), null, null)
        putString(Keys.REVOKE_PHASE, UNREGISTER).putString(Keys.REVOKE_REGISTRATION, revoke.encode()).putLong(Keys.GENERATION, generation() + 1)
        for (k in listOf(Keys.SERVER, Keys.RECORD, Keys.RECORD_TITLE, Keys.ENDPOINT, Keys.PUBLISHED_AT, Keys.ENDPOINT_NEW, Keys.ENDPOINT_NEW_INSTANCE, Keys.REGISTRATION)) remove(k)
        removePending()
    }

    private fun entry(id: String, title: String, since: Long): DebtEntry? =
        prefs.getString(id, null)?.let { DebtEntry(it, prefs.getString(title, null) ?: return null, since) }

    /** A pending id goes to debt only if its create may have been sent; one never sent can never appear. */
    private fun pendingEntry() = prefs.getLong(Keys.PENDING_ATTEMPTED_AT, -1).takeIf { it >= 0 }?.let { entry(Keys.PENDING_ID, Keys.PENDING_TITLE, maxOf(it, 1)) }

    private fun SharedPreferences.Editor.putDebt(server: String, entries: List<DebtEntry>) {
        val all = readDebt(prefs).toMutableMap()
        if (entries.isEmpty()) all.remove(server) else all[server] = entries
        if (all.isEmpty()) remove(Keys.DEBT) else putString(Keys.DEBT, Json.encodeToString(all))
    }

    private fun SharedPreferences.Editor.removePending() {
        for (k in listOf(Keys.PENDING_ID, Keys.PENDING_ENDPOINT, Keys.PENDING_TITLE, Keys.PENDING_ATTEMPTED_AT)) remove(k)
    }

    private suspend fun turnOff(a: RecordApi?) {
        commit {
            beginRevocation(a?.server)
            putBoolean(Keys.ENABLED, false).remove(Keys.UNREGISTERED_INSTANCE)
        }
        finishRevocation(a)
    }

    /** Repeats whatever step of a revocation was persisted but not confirmed; the re-registration comes before NONE. */
    private suspend fun finishRevocation(a: RecordApi?) {
        if (prefs.getString(Keys.REVOKE_PHASE, null) == UNREGISTER) {
            Registration.decode(prefs.getString(Keys.REVOKE_REGISTRATION, null))?.let { port.unregister(context, it) }
            commit { putString(Keys.REVOKE_PHASE, DELETE) }
        }
        if (prefs.getString(Keys.REVOKE_PHASE, null) == DELETE) {
            if (a != null) drain(a)
            if (enabled()) register()
            commit { remove(Keys.REVOKE_PHASE).remove(Keys.REVOKE_REGISTRATION) }
        }
    }

    /** What PushReceiver committed: an unregistration of the current instance turns push off; stale events are dropped. */
    private suspend fun handleEvents(a: RecordApi?) {
        val gone = prefs.getString(Keys.UNREGISTERED_INSTANCE, null)
        if (gone != null) {
            if (gone == currentInstance() && enabled()) turnOff(a) else commit { remove(Keys.UNREGISTERED_INSTANCE) }
        }
        if (prefs.contains(Keys.ENDPOINT_NEW) && newEndpoint() == null) commit { remove(Keys.ENDPOINT_NEW).remove(Keys.ENDPOINT_NEW_INSTANCE) }
    }

    private suspend fun check(a: RecordApi) {
        val record = prefs.getString(Keys.RECORD, null) ?: return
        val row = a.read(record)
        // Gone, or retitled by someone else: a new record replaces it (the old one stays debt, never deleted unproved).
        if (row == null || row.title != prefs.getString(Keys.RECORD_TITLE, null) || row.source != "api_server") return publish(a, force = true)
        if (!row.hidden || row.pinned) a.hide(record)
        if (clock() - prefs.getLong(Keys.PUBLISHED_AT, 0) >= ROTATE_AFTER_MS) publish(a, force = true)
    }

    private suspend fun publish(a: RecordApi, force: Boolean) {
        val target = newEndpoint() ?: prefs.getString(Keys.ENDPOINT, null) ?: return // waiting for NEW_ENDPOINT
        val checked = checkEndpoint(target, policy.origins(context), policy.loopbackOrigin(context))
        val endpoint = checked.url ?: return fail("endpoint refused: ${checked.reason}")
        if (prefs.getString(Keys.SERVER, null).let { it != null && it != a.server }) return fail("record of another server")
        val record = prefs.getString(Keys.RECORD, null)
        if (!force && record != null && !prefs.contains(Keys.PENDING_ID) && prefs.getString(Keys.ENDPOINT, null) == endpoint) return
        if (prefs.contains(Keys.PENDING_ID) && prefs.getString(Keys.PENDING_ENDPOINT, null) != endpoint) {
            val pending = pendingEntry()
            commit {
                if (pending != null) putDebt(a.server, readDebt(prefs)[a.server].orEmpty() + pending)
                removePending()
            }
        }
        repeat(MAX_CREATES) {
            val id = prefs.getString(Keys.PENDING_ID, null) ?: reserve(a.server, endpoint) ?: return fail("no id or debt room left")
            val title = prefs.getString(Keys.PENDING_TITLE, null)!!
            if (!prefs.contains(Keys.PENDING_ATTEMPTED_AT)) commit { putLong(Keys.PENDING_ATTEMPTED_AT, clock()) }
            val ours = try {
                a.create(id, title, prompt(endpoint)) || a.read(id)?.let { it.title == title && it.source == "api_server" } == true
            } catch (e: ApiError.Conflict) {
                if (e.code != "session_deleted") throw e
                false // retired: never reused
            }
            if (ours) return finish(a, id, title, endpoint, record)
            // A stranger's row holds the id, or it is retired: a create of ours can never land there. Never adopted, never debt.
            commit { removePending() }
        }
        fail("no free id")
    }

    private fun reserve(server: String, endpoint: String): String? {
        val seq = prefs.getInt(Keys.SEQ, 0) + 1
        if (seq > MAX_SEQ || readDebt(prefs).values.sumOf { it.size } >= MAX_DEBT - DEBT_RESERVE) return null
        val install = prefs.getString(Keys.INSTALL_ID, null) ?: UUID.randomUUID().toString()
        val id = recordId(install, seq)
        val marker = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val title = policy.recordTitle(context, id, marker)
        commit {
            putString(Keys.INSTALL_ID, install).putInt(Keys.SEQ, seq).putString(Keys.SERVER, server)
            putString(Keys.PENDING_ID, id).putString(Keys.PENDING_ENDPOINT, endpoint).putString(Keys.PENDING_TITLE, title)
            policy.recordIntent(prefs, this, id, title)
        }
        return id
    }

    private suspend fun finish(a: RecordApi, id: String, title: String, endpoint: String, old: String?) {
        a.hide(id)
        val oldEntry = entry(Keys.RECORD, Keys.RECORD_TITLE, 0)?.takeIf { old != id }
        commit {
            putString(Keys.RECORD, id).putString(Keys.RECORD_TITLE, title).putString(Keys.ENDPOINT, endpoint)
            putString(Keys.SERVER, a.server).putLong(Keys.PUBLISHED_AT, clock())
            if (oldEntry != null) putDebt(a.server, readDebt(prefs)[a.server].orEmpty() + oldEntry)
            removePending()
        }
    }

    /** Settles this server's debt entries; stops at the first failure. True when none is left. */
    private suspend fun drain(a: RecordApi): Boolean {
        var left = false
        for (e in readDebt(prefs)[a.server].orEmpty()) {
            val settled = try {
                settle(a, e)
            } catch (x: Stale) {
                throw x
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                logFailure(x)
                return false
            }
            if (settled) commit { putDebt(a.server, readDebt(prefs)[a.server].orEmpty() - e) } else left = true
        }
        return !left
    }

    /** True when [e] needs nothing more: deleted after a fresh proof that it is ours, or provably never created. */
    private suspend fun settle(a: RecordApi, e: DebtEntry): Boolean {
        // 404 is final for a confirmed record; an attempted create may still land, so it waits SETTLE_MS.
        var row = a.read(e.id) ?: return e.since == 0L || clock() - e.since >= SETTLE_MS
        if (!ownedAndEmpty(e, row)) return false // not provably ours: never deleted, never forgotten
        if (!row.hidden || row.pinned) {
            a.hide(e.id)
            row = a.read(e.id) ?: return e.since == 0L
            if (!ownedAndEmpty(e, row) || !row.hidden || row.pinned) return false
        }
        // Known limit: Hermes has no conditional delete, so the row can still change between this read and the DELETE;
        // only a holder of the same API key can do that.
        a.delete(e.id)
        return true
    }

    // No messages, parent or end reason: no turn ever ran in it, so it has no delegate or continuation rows for the
    // DELETE to cascade to (Hermes delete_session).
    private fun ownedAndEmpty(e: DebtEntry, row: RecordRow) = RECORD_ID.matches(e.id) && row.title == e.title &&
        row.source == "api_server" && row.messageCount == 0 && row.parent == null && row.endReason == null

    private fun prompt(endpoint: String) = buildJsonObject { put("v", 1); put("endpoint", endpoint) }.toString()

    private fun fail(reason: String) {
        Log.w(TAG, "Push not published: $reason")
        failed = true
    }

    private fun compute(): PushUi {
        val distributor = registrationOf(prefs)?.distributor?.takeIf { it in policy.distributors(context) }
            ?: allowed().firstOrNull() ?: NTFY_PACKAGE
        val s = when {
            !enabled() -> if (allowed().isEmpty()) PushState.NoDistributor else PushState.Off
            failed -> PushState.ServerError(distributor)
            // The endpoint the record holds came from this instance's own NEW_ENDPOINT (which also means acknowledged).
            prefs.contains(Keys.RECORD) && !prefs.contains(Keys.PENDING_ID) && newEndpoint() == prefs.getString(Keys.ENDPOINT, null) ->
                PushState.On(distributor)
            else -> PushState.Registering
        }
        return PushUi(s, prefs.contains(Keys.REVOKE_PHASE) || readDebt(prefs).isNotEmpty())
    }

    // Server and transport text can carry the endpoint or ids: only the class name is ever logged.
    private fun logFailure(e: Throwable) = Log.w(TAG, "Push step failed: ${e.javaClass.name}")
}
