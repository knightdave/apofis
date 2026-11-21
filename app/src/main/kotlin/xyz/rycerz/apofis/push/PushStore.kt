package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap

/** The `push` prefs. Only [ENDPOINT_NEW], [ENDPOINT_NEW_TOKEN] and [UNREGISTERED_INSTANCE] are written by PushReceiver. */
internal object Keys {
    const val INSTALL_ID = "install_id"
    const val GENERATION = "generation" // the connector instance is "g<generation>"
    const val SEQ = "seq"
    const val ENABLED = "enabled"
    const val REGISTRATION = "registration" // the current instance's connector registration, journaled before its REGISTER
    const val REVOKE = "revoke" // registrations still to unregister, one per line; committed before any other step
    const val RECORD = "record"
    const val RECORD_TITLE = "record_title"
    const val ENDPOINT = "endpoint" // the record's endpoint
    const val PUBLISHED_AT = "published_at"
    const val VERIFIED_AT = "verified_at"
    const val PENDING_ID = "pending_id"
    const val PENDING_ENDPOINT = "pending_endpoint"
    const val PENDING_TITLE = "pending_title"
    const val ENDPOINT_NEW = "endpoint_new"
    const val ENDPOINT_NEW_TOKEN = "endpoint_new_token" // the registration token the endpoint arrived for
    const val UNREGISTERED_INSTANCE = "unregistered_instance"
    const val FLUSH = "flush"
}

/**
 * A connector registration as journaled before its REGISTER broadcast: what is needed to unregister it later, and the
 * connection epoch it was made under (its wakes belong to that connection only).
 */
data class Registration(val instance: String, val distributor: String?, val token: String?, val epoch: Long? = null) {
    fun encode() = listOfNotNull(instance, distributor, token, epoch?.toString()).joinToString("\t")

    companion object {
        fun decode(s: String?) = s?.split('\t')?.let { Registration(it[0], it.getOrNull(1), it.getOrNull(2), it.getOrNull(3)?.toLongOrNull()) }
    }
}

internal fun instanceOf(prefs: SharedPreferences) = "g" + prefs.getLong(Keys.GENERATION, 0)
internal fun registrationOf(prefs: SharedPreferences) = Registration.decode(prefs.getString(Keys.REGISTRATION, null))
internal fun revocationsOf(prefs: SharedPreferences) = prefs.getString(Keys.REVOKE, null)?.lines()?.mapNotNull(Registration::decode).orEmpty()

/**
 * The current registration: journaled for the current instance, with a distributor in [allowed], under the current
 * connection [epoch]. One of an older epoch counts as not registered.
 */
internal fun currentRegistration(prefs: SharedPreferences, allowed: Set<String>, epoch: Long) = registrationOf(prefs)
    ?.takeIf { it.instance == instanceOf(prefs) && it.distributor in allowed && it.token != null && it.epoch == epoch }

/** The endpoint the distributor gave the current registration's token; anything else is not confirmed. */
internal fun confirmedEndpoint(prefs: SharedPreferences, allowed: Set<String>, epoch: Long): String? {
    val token = currentRegistration(prefs, allowed, epoch)?.token ?: return null
    return prefs.getString(Keys.ENDPOINT_NEW, null)?.takeIf { prefs.getString(Keys.ENDPOINT_NEW_TOKEN, null) == token }
}

/**
 * True while PushRegistrar has persisted work left: a revocation, an event from the receiver, a pending id, or push on
 * without a confirmed endpoint that the record holds.
 */
internal fun needsWork(prefs: SharedPreferences, allowed: Set<String>, epoch: Long): Boolean {
    if (prefs.contains(Keys.REVOKE) || prefs.contains(Keys.UNREGISTERED_INSTANCE) || prefs.contains(Keys.PENDING_ID)) return true
    if (!prefs.getBoolean(Keys.ENABLED, false)) return false
    val confirmed = confirmedEndpoint(prefs, allowed, epoch)
    return confirmed == null || confirmed != prefs.getString(Keys.ENDPOINT, null)
}

/**
 * Every push prefs commit, the receiver's trust check with its hand-off, and the connection generation change run under
 * [locked] (short, never across network I/O). Android keeps a failed commit's changes in memory, so after one the prefs
 * are fenced: nothing may act on them until a later commit has written the whole map to disk.
 */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result
internal object PushStore {
    private val lock = Any()
    private val dirty = Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    fun isDirty(prefs: SharedPreferences) = locked { prefs in dirty }

    fun flush(prefs: SharedPreferences): Boolean = locked {
        if (prefs !in dirty) return@locked true
        prefs.edit().putLong(Keys.FLUSH, prefs.getLong(Keys.FLUSH, 0) + 1).commit().also { if (it) dirty -= prefs }
    }

    fun commit(prefs: SharedPreferences, edit: SharedPreferences.Editor.() -> Unit): Boolean = locked {
        flush(prefs) && prefs.edit().apply(edit).commit().also { if (!it) dirty += prefs }
    }
}
