package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unifiedpush.android.connector.UnifiedPush
import xyz.rycerz.apofis.api.ApiError

internal fun recordId(installId: String, seq: Int) = "apofis-push-$installId-$seq"
internal const val VERIFY_EVERY_MS = 3_600_000L
internal const val ROTATE_AFTER_MS = 30L * 24 * 3_600_000L
internal const val MAX_SEQ = 999_999
internal const val MAX_DEBT = 64
internal const val DEBT_RESERVE = 2 // publishes stop at 62, so turning off (record plus pending) always fits
private const val MAX_CREATES = 3
private const val SWITCH_DRAIN_MS = 5_000L
private const val TAG = "Apofis"

/** The `push` prefs. Only [ENDPOINT_NEW], [ENDPOINT_NEW_INSTANCE] and [UNREGISTERED_INSTANCE] are written by PushReceiver. */
internal object Keys {
    const val INSTALL_ID = "install_id"
    const val GENERATION = "generation" // the connector instance is "g<generation>"
    const val SEQ = "seq"
    const val ENABLED = "enabled"
    const val DISTRIBUTOR = "distributor"
    const val SERVER = "server" // the server of the record and the pending id
    const val RECORD = "record"
    const val RECORD_TITLE = "record_title"
    const val ENDPOINT = "endpoint" // the record's endpoint
    const val PUBLISHED_AT = "published_at"
    const val VERIFIED_AT = "verified_at"
    const val PENDING_ID = "pending_id"
    const val PENDING_ENDPOINT = "pending_endpoint"
    const val PENDING_TITLE = "pending_title"
    const val PENDING_ATTEMPTED = "pending_attempted"
    const val REVOKE_PHASE = "revoke_phase" // absent (NONE), UNREGISTER or DELETE
    const val REVOKE_INSTANCE = "revoke_instance"
    const val DEBT = "debt" // {server: [{id, title}]}: records to delete there, each only if its title still matches
    const val ENDPOINT_NEW = "endpoint_new"
    const val ENDPOINT_NEW_INSTANCE = "endpoint_new_instance"
    const val UNREGISTERED_INSTANCE = "unregistered_instance"
}

private const val UNREGISTER = "UNREGISTER"
private const val DELETE = "DELETE"

internal fun instanceOf(prefs: SharedPreferences) = "g" + prefs.getLong(Keys.GENERATION, 0)

/** True while PublishWorker has something to do: a new endpoint, a revocation, an unregistration or debt. */
internal fun needsWork(prefs: SharedPreferences): Boolean {
    val new = prefs.getString(Keys.ENDPOINT_NEW, null)
    return prefs.contains(Keys.REVOKE_PHASE) || prefs.contains(Keys.UNREGISTERED_INSTANCE) || readDebt(prefs).isNotEmpty() ||
        (new != null && new != prefs.getString(Keys.ENDPOINT, null))
}

@Serializable
internal data class DebtEntry(val id: String, val title: String)

private fun readDebt(prefs: SharedPreferences): Map<String, List<DebtEntry>> =
    prefs.getString(Keys.DEBT, null)?.let { Json.decodeFromString<Map<String, List<DebtEntry>>>(it) }.orEmpty()

/** The connector calls the registrar needs; every one passes [PlainKeyManager]. */
interface UnifiedPushPort {
    fun distributors(c: Context): List<String>
    fun saved(c: Context): String?
    fun acked(c: Context): String?
    fun save(c: Context, d: String)
    fun register(c: Context, instance: String)
    fun unregister(c: Context, instance: String)
}

object RealUnifiedPush : UnifiedPushPort {
    override fun distributors(c: Context): List<String> = UnifiedPush.getDistributors(c)
    override fun saved(c: Context) = UnifiedPush.getSavedDistributor(c)
    override fun acked(c: Context) = UnifiedPush.getAckDistributor(c)
    override fun save(c: Context, d: String) = UnifiedPush.saveDistributor(c, d)
    override fun register(c: Context, instance: String) = UnifiedPush.register(c, instance, null, null, PlainKeyManager)
    override fun unregister(c: Context, instance: String) = UnifiedPush.unregister(c, instance, PlainKeyManager)
}

/**
 * Registers this install with the allowed distributor and keeps its endpoint in one hidden record on the connected
 * server. Every intent is committed before the side effect it leads to, so a restart can finish any sequence:
 * a record is created only under a reserved id and per-operation title, adopted or deleted only when that title and the
 * `api_server` source prove it is ours, and kept as debt until its deletion is confirmed. All operations run under one
 * mutex and stop at their next step when the connection changes.
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

    internal suspend fun enableNow() = op { _, live ->
        finishRevocation { drainCurrent(live) }
        val d = policy.distributors(context).firstOrNull { it in port.distributors(context) } ?: return@op
        if (!enabled()) {
            commit { putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, generation() + 1).putString(Keys.DISTRIBUTOR, d) }
        }
        failed = false
        port.save(context, d)
        port.register(context, currentInstance())
    }

    suspend fun disable() = op { _, live ->
        finishRevocation { drainCurrent(live) }
        if (enabled()) turnOff(live)
    }

    /** Finishes revocations, handles the receiver's events, publishes and drains debt. True when nothing is left to retry. */
    suspend fun publishPending(): Boolean = op { a, live ->
        finishRevocation { drainCurrent(live) }
        handleEvents(live)
        if (a != null && enabled()) publish(a, live, force = false)
        if (a != null) drain(a, live)
        !failed && (a == null || readDebt(prefs)[a.server].isNullOrEmpty())
    } ?: false

    /** Hourly unless [force]: distributor still there, record present and hidden, rotation due, debt drained. */
    suspend fun verify(force: Boolean = false) {
        op { a, live ->
            val last = prefs.getLong(Keys.VERIFIED_AT, 0)
            if (!force && prefs.contains(Keys.VERIFIED_AT) && clock() - last in 0 until VERIFY_EVERY_MS) return@op
            finishRevocation { drainCurrent(live) }
            handleEvents(live)
            if (enabled()) {
                when {
                    port.saved(context) == null -> turnOff(live) // the distributor was uninstalled
                    port.acked(context) == null -> port.register(context, currentInstance()) // not answered yet: ask again
                    a != null -> check(a, live)
                }
            }
            if (a != null) drain(a, live)
            commit { putLong(Keys.VERIFIED_AT, clock()) }
        }
    }

    /**
     * Called by AppContainer.connect before the old connection closes ([old] null when there is none): the current
     * registration becomes revocation debt of its server, the instance rotates, and, only if push is on, the allowed
     * distributor is selected again and the new instance registered. Its endpoint is used once its NEW_ENDPOINT arrives.
     */
    suspend fun forgetServer(old: RecordApi?) {
        mutex.withLock {
            try {
                val drainOld: suspend () -> Unit = { if (old != null) withTimeoutOrNull(SWITCH_DRAIN_MS) { drain(old) { true } } }
                finishRevocation(drainOld)
                if (enabled() || prefs.contains(Keys.RECORD) || prefs.contains(Keys.PENDING_ID)) {
                    commit { beginRevocation(old?.server) }
                    finishRevocation(drainOld)
                } else {
                    drainOld()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(e)
            } finally {
                state.value = compute()
            }
        }
    }

    private suspend fun <T> op(block: suspend (RecordApi?, () -> Boolean) -> T): T? = mutex.withLock {
        val start = connectionGeneration()
        try {
            block(api()) { connectionGeneration() == start }
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

    private fun enabled() = prefs.getBoolean(Keys.ENABLED, false)
    private fun generation() = prefs.getLong(Keys.GENERATION, 0)

    private fun commit(edit: SharedPreferences.Editor.() -> Unit) {
        if (!prefs.edit().apply(edit).commit()) throw IOException("push prefs not written")
    }

    private suspend fun drainCurrent(live: () -> Boolean) {
        api()?.takeIf { live() }?.let { drain(it, live) }
    }

    /**
     * One commit: push off for the old instance, the record and the pending id (with their titles) into their server's
     * debt, the next generation. The reserve keeps room for both.
     */
    private fun SharedPreferences.Editor.beginRevocation(fallbackServer: String?) {
        val server = prefs.getString(Keys.SERVER, null) ?: fallbackServer
        val add = listOfNotNull(entry(Keys.RECORD, Keys.RECORD_TITLE), entry(Keys.PENDING_ID, Keys.PENDING_TITLE))
        if (server != null && add.isNotEmpty()) putDebt(server, readDebt(prefs)[server].orEmpty() + add)
        putString(Keys.REVOKE_PHASE, UNREGISTER).putString(Keys.REVOKE_INSTANCE, currentInstance()).putLong(Keys.GENERATION, generation() + 1)
        for (k in listOf(Keys.SERVER, Keys.RECORD, Keys.RECORD_TITLE, Keys.ENDPOINT, Keys.PUBLISHED_AT, Keys.ENDPOINT_NEW, Keys.ENDPOINT_NEW_INSTANCE)) remove(k)
        removePending()
    }

    private fun entry(id: String, title: String): DebtEntry? = prefs.getString(id, null)?.let { DebtEntry(it, prefs.getString(title, null) ?: return null) }

    private fun SharedPreferences.Editor.putDebt(server: String, entries: List<DebtEntry>) {
        val all = readDebt(prefs).toMutableMap()
        if (entries.isEmpty()) all.remove(server) else all[server] = entries
        if (all.isEmpty()) remove(Keys.DEBT) else putString(Keys.DEBT, Json.encodeToString(all))
    }

    private fun SharedPreferences.Editor.removePending() {
        for (k in listOf(Keys.PENDING_ID, Keys.PENDING_ENDPOINT, Keys.PENDING_TITLE, Keys.PENDING_ATTEMPTED)) remove(k)
    }

    private suspend fun turnOff(live: () -> Boolean) {
        commit {
            beginRevocation(api()?.server)
            putBoolean(Keys.ENABLED, false).remove(Keys.UNREGISTERED_INSTANCE)
        }
        failed = false
        finishRevocation { drainCurrent(live) }
    }

    /** Repeats whatever step of a revocation was persisted but not confirmed; the re-registration comes before NONE. */
    private suspend fun finishRevocation(drain: suspend () -> Unit) {
        if (prefs.getString(Keys.REVOKE_PHASE, null) == UNREGISTER) {
            prefs.getString(Keys.REVOKE_INSTANCE, null)?.let { port.unregister(context, it) }
            commit { putString(Keys.REVOKE_PHASE, DELETE) }
        }
        if (prefs.getString(Keys.REVOKE_PHASE, null) == DELETE) {
            drain()
            if (enabled()) {
                // Unregistering the last instance made the connector forget the saved distributor.
                port.save(context, prefs.getString(Keys.DISTRIBUTOR, null) ?: NTFY_PACKAGE)
                port.register(context, currentInstance())
            }
            commit { remove(Keys.REVOKE_PHASE).remove(Keys.REVOKE_INSTANCE) }
        }
    }

    /** What PushReceiver committed: an unregistration of the current instance turns push off; stale events are dropped. */
    private suspend fun handleEvents(live: () -> Boolean) {
        val gone = prefs.getString(Keys.UNREGISTERED_INSTANCE, null)
        if (gone != null) {
            if (gone == currentInstance() && enabled()) turnOff(live) else commit { remove(Keys.UNREGISTERED_INSTANCE) }
        }
        if (prefs.contains(Keys.ENDPOINT_NEW) && prefs.getString(Keys.ENDPOINT_NEW_INSTANCE, null) != currentInstance()) {
            commit { remove(Keys.ENDPOINT_NEW).remove(Keys.ENDPOINT_NEW_INSTANCE) }
        }
    }

    private suspend fun check(a: RecordApi, live: () -> Boolean) {
        val record = prefs.getString(Keys.RECORD, null) ?: return publish(a, live, force = false)
        // Gone, or retitled by someone else (then never deleted by us either): a new record replaces it.
        if (a.read(record) != (prefs.getString(Keys.RECORD_TITLE, null) to "api_server")) return publish(a, live, force = true)
        if (!live()) return
        if (!a.isHiddenUnpinned(record)) a.hide(record)
        if (clock() - prefs.getLong(Keys.PUBLISHED_AT, 0) >= ROTATE_AFTER_MS) publish(a, live, force = true)
    }

    private suspend fun publish(a: RecordApi, live: () -> Boolean, force: Boolean) {
        val new = prefs.getString(Keys.ENDPOINT_NEW, null)?.takeIf { prefs.getString(Keys.ENDPOINT_NEW_INSTANCE, null) == currentInstance() }
        val target = new ?: prefs.getString(Keys.ENDPOINT, null) ?: return // waiting for NEW_ENDPOINT
        val checked = checkEndpoint(target, policy.origins(context), policy.loopbackOrigin(context))
        val endpoint = checked.url ?: return fail("endpoint refused: ${checked.reason}")
        if (prefs.getString(Keys.SERVER, null).let { it != null && it != a.server }) return fail("record of another server")
        val record = prefs.getString(Keys.RECORD, null)
        if (!force && record != null && !prefs.contains(Keys.PENDING_ID) && prefs.getString(Keys.ENDPOINT, null) == endpoint) {
            failed = false
            return
        }
        if (prefs.contains(Keys.PENDING_ID) && prefs.getString(Keys.PENDING_ENDPOINT, null) != endpoint) {
            val pending = entry(Keys.PENDING_ID, Keys.PENDING_TITLE)
            commit {
                if (pending != null) putDebt(a.server, readDebt(prefs)[a.server].orEmpty() + pending)
                removePending()
            }
        }
        repeat(MAX_CREATES) {
            if (!live()) return
            val id = prefs.getString(Keys.PENDING_ID, null) ?: reserve(a.server, endpoint) ?: return fail("no id or debt room left")
            val title = prefs.getString(Keys.PENDING_TITLE, null)!!
            if (!prefs.getBoolean(Keys.PENDING_ATTEMPTED, false)) commit { putBoolean(Keys.PENDING_ATTEMPTED, true) }
            val ours = try {
                a.create(id, title, prompt(endpoint)) || a.read(id) == (title to "api_server")
            } catch (e: ApiError.Conflict) {
                if (e.code != "session_deleted") throw e
                false // retired: never reused
            }
            if (ours) return finish(a, live, id, title, endpoint, record)
            commit { removePending() } // a stranger's row or a retired id: never adopted, never debt
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
            putBoolean(Keys.PENDING_ATTEMPTED, false)
            policy.recordIntent(prefs, this, id, title)
        }
        return id
    }

    private suspend fun finish(a: RecordApi, live: () -> Boolean, id: String, title: String, endpoint: String, old: String?) {
        if (!live()) return
        a.hide(id)
        val oldEntry = entry(Keys.RECORD, Keys.RECORD_TITLE)?.takeIf { old != id }
        commit {
            putString(Keys.RECORD, id).putString(Keys.RECORD_TITLE, title).putString(Keys.ENDPOINT, endpoint)
            putString(Keys.SERVER, a.server).putLong(Keys.PUBLISHED_AT, clock())
            if (oldEntry != null) putDebt(a.server, readDebt(prefs)[a.server].orEmpty() + oldEntry)
            removePending()
        }
        failed = false
    }

    /** Deletes this server's debt entries whose record still carries the recorded title; stops at the first failure. */
    private suspend fun drain(a: RecordApi, live: () -> Boolean) {
        for (e in readDebt(prefs)[a.server].orEmpty()) {
            if (!live()) return
            try {
                if (a.read(e.id) == (e.title to "api_server")) a.delete(e.id) // otherwise gone or not ours: drop, never delete
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                logFailure(x)
                return
            }
            commit { putDebt(a.server, readDebt(prefs)[a.server].orEmpty() - e) }
        }
    }

    private fun prompt(endpoint: String) = buildJsonObject { put("v", 1); put("endpoint", endpoint) }.toString()

    private fun fail(reason: String) {
        Log.w(TAG, "Push not published: $reason")
        failed = true
    }

    private fun compute(): PushUi {
        val distributor = prefs.getString(Keys.DISTRIBUTOR, null) ?: NTFY_PACKAGE
        val new = prefs.getString(Keys.ENDPOINT_NEW, null)
        val s = when {
            !enabled() -> if (policy.distributors(context).none { it in port.distributors(context) }) PushState.NoDistributor else PushState.Off
            failed -> PushState.ServerError(distributor)
            prefs.contains(Keys.RECORD) && !prefs.contains(Keys.PENDING_ID) && (new == null || new == prefs.getString(Keys.ENDPOINT, null)) ->
                PushState.On(distributor)
            else -> PushState.Registering
        }
        return PushUi(s, prefs.contains(Keys.REVOKE_PHASE) || readDebt(prefs).isNotEmpty())
    }

    // Server and transport text can carry the endpoint or ids: only the class name is ever logged.
    private fun logFailure(e: Throwable) = Log.w(TAG, "Push step failed: ${e.javaClass.name}")
}
