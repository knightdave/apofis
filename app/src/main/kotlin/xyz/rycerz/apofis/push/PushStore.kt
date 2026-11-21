package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The `push` prefs. Only [ENDPOINT_NEW], [ENDPOINT_NEW_INSTANCE] and [UNREGISTERED_INSTANCE] are written by PushReceiver. */
internal object Keys {
    const val INSTALL_ID = "install_id"
    const val GENERATION = "generation" // the connector instance is "g<generation>"
    const val SEQ = "seq"
    const val ENABLED = "enabled"
    const val REGISTRATION = "registration" // the current instance's connector registration, journaled before its REGISTER
    const val SERVER = "server" // the server of the record and the pending id
    const val RECORD = "record"
    const val RECORD_TITLE = "record_title"
    const val ENDPOINT = "endpoint" // the record's endpoint
    const val PUBLISHED_AT = "published_at"
    const val VERIFIED_AT = "verified_at"
    const val PENDING_ID = "pending_id"
    const val PENDING_ENDPOINT = "pending_endpoint"
    const val PENDING_TITLE = "pending_title"
    const val PENDING_ATTEMPTED_AT = "pending_attempted_at" // present once a create of the pending id may have been sent
    const val REVOKE_PHASE = "revoke_phase" // absent (NONE), UNREGISTER or DELETE
    const val REVOKE_REGISTRATION = "revoke_registration"
    const val DEBT = "debt" // {server: [{id, title, since}]}: records to delete there, each only after it is proved ours
    const val ENDPOINT_NEW = "endpoint_new"
    const val ENDPOINT_NEW_INSTANCE = "endpoint_new_instance"
    const val UNREGISTERED_INSTANCE = "unregistered_instance"
    const val FLUSH = "flush"
}

/** A connector registration as journaled before its REGISTER broadcast: what is needed to unregister it later. */
data class Registration(val instance: String, val distributor: String?, val token: String?) {
    fun encode() = listOfNotNull(instance, distributor, token).joinToString("\t")

    companion object {
        fun decode(s: String?) = s?.split('\t')?.let { Registration(it[0], it.getOrNull(1), it.getOrNull(2)) }
    }
}

/** [since]: 0 for a record that was confirmed created, else when its create may first have been sent. */
@Serializable
internal data class DebtEntry(val id: String, val title: String, val since: Long = 0)

internal fun instanceOf(prefs: SharedPreferences) = "g" + prefs.getLong(Keys.GENERATION, 0)
internal fun registrationOf(prefs: SharedPreferences) = Registration.decode(prefs.getString(Keys.REGISTRATION, null))
internal fun readDebt(prefs: SharedPreferences): Map<String, List<DebtEntry>> =
    prefs.getString(Keys.DEBT, null)?.let { Json.decodeFromString<Map<String, List<DebtEntry>>>(it) }.orEmpty()

/**
 * True while PushRegistrar has persisted work left: a revocation, an event from the receiver, an enable whose
 * registration is not journaled (or is not allowed by [allowed]), a pending id, a new endpoint, or debt.
 */
internal fun needsWork(prefs: SharedPreferences, allowed: Set<String>): Boolean {
    val enabled = prefs.getBoolean(Keys.ENABLED, false)
    val r = registrationOf(prefs)
    val new = prefs.getString(Keys.ENDPOINT_NEW, null)
    return prefs.contains(Keys.REVOKE_PHASE) || prefs.contains(Keys.UNREGISTERED_INSTANCE) || prefs.contains(Keys.PENDING_ID) ||
        readDebt(prefs).isNotEmpty() ||
        (enabled && (r?.instance != instanceOf(prefs) || r.distributor !in allowed)) ||
        (enabled && new != null && new != prefs.getString(Keys.ENDPOINT, null))
}

/**
 * Every push prefs commit, and the receiver's instance check with its hand-off, run under [lock] (short, never across
 * network I/O). Android keeps a failed commit's changes in memory, so after one the prefs are fenced: nothing may act on
 * them until a later commit has written the whole map to disk.
 */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result
internal object PushStore {
    private val lock = Any()
    private val dirty = Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    fun flush(prefs: SharedPreferences): Boolean = locked {
        if (prefs !in dirty) return@locked true
        prefs.edit().putLong(Keys.FLUSH, prefs.getLong(Keys.FLUSH, 0) + 1).commit().also { if (it) dirty -= prefs }
    }

    fun commit(prefs: SharedPreferences, edit: SharedPreferences.Editor.() -> Unit): Boolean = locked {
        flush(prefs) && prefs.edit().apply(edit).commit().also { if (!it) dirty += prefs }
    }
}
