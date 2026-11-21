package xyz.rycerz.apofis.push

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import org.robolectric.Shadows.shadowOf
import xyz.rycerz.apofis.api.ApiError

/** Simulated process death: an Error, so no production catch block swallows it. */
class Death : Error()

/**
 * Ordered record of side effects. [step] is called right before each one; when the count reaches [killAt] the process
 * "dies" there (before that effect), so every point between two side effects can be reached.
 */
class EventLog(var killAt: Int = 0) {
    val events = mutableListOf<String>()
    private var count = 0
    fun step(name: String) {
        count++
        if (count == killAt) {
            events += "death before $name"
            throw Death()
        }
        events += name
    }
    fun count() = count
}

data class Row(val title: String?, val source: String, var hidden: Boolean = false, var pinned: Boolean = false)

/** One Hermes server's sessions. Explicitly deleted ids are retired (409 session_deleted), as on Hermes. */
class FakeServer(val url: String) {
    val rows = mutableMapOf<String, Row>()
    val retired = mutableSetOf<String>()
    val created = mutableListOf<Pair<String, String>>() // (id, prompt)
    val deleted = mutableListOf<String>()
}

class FakeRecordApi(private val s: FakeServer, private val log: EventLog = EventLog()) : RecordApi {
    override val server get() = s.url
    var failWith: Exception? = null
    var sendFails = false // the create request fails on the wire
    val calls = mutableListOf<String>()

    private fun call(name: String) {
        log.step("${s.url} $name")
        calls += name
        failWith?.let { throw it }
    }

    override suspend fun create(id: String, title: String, prompt: String): Boolean {
        call("create $id")
        if (sendFails) throw ApiError.Network(java.io.IOException("down"))
        if (id in s.retired) throw ApiError.Conflict("session_deleted")
        if (id in s.rows) return false
        s.rows[id] = Row(title, "api_server")
        s.created += id to prompt
        return true
    }

    override suspend fun read(id: String): Pair<String?, String>? {
        call("read $id")
        return s.rows[id]?.let { it.title to it.source }
    }

    override suspend fun isHiddenUnpinned(id: String): Boolean {
        call("hidden? $id")
        return s.rows.getValue(id).let { it.hidden && !it.pinned }
    }

    override suspend fun hide(id: String) {
        call("hide $id")
        s.rows.getValue(id).apply { hidden = true; pinned = false }
    }

    override suspend fun delete(id: String) {
        call("delete $id")
        if (s.rows.remove(id) != null) s.deleted += id
        s.retired += id
    }
}

/** The connector seen through [UnifiedPushPort]: unregistering the last instance forgets the saved distributor. */
class FakePort(private val log: EventLog = EventLog()) : UnifiedPushPort {
    val installed = mutableListOf(NTFY_PACKAGE)
    var saved: String? = null
    var acked = true
    val registered = mutableSetOf<String>()
    val calls = mutableListOf<String>()

    override fun distributors(c: Context) = installed.toList()
    override fun saved(c: Context) = saved?.takeIf { it in installed }
    override fun acked(c: Context) = saved(c)?.takeIf { acked }
    override fun save(c: Context, d: String) {
        log.step("save $d"); calls += "save $d"; saved = d
    }
    override fun register(c: Context, instance: String) {
        log.step("register $instance"); calls += "register $instance"; registered += instance
    }
    override fun unregister(c: Context, instance: String) {
        log.step("unregister $instance"); calls += "unregister $instance"; registered -= instance
        if (registered.isEmpty()) saved = null
    }
}

/** The release policy's shape, with the distributor set chosen by the test. */
class TestPolicy(private val allowed: Set<String> = setOf(NTFY_PACKAGE), private val loopback: String? = null) : PushPolicy {
    override fun distributors(c: Context) = allowed
    override fun origins(c: Context) = setOf(NTFY_ORIGIN)
    override fun loopbackOrigin(c: Context) = loopback
    override fun recordTitle(c: Context, id: String, opMarker: String) = "$id op-$opMarker"
    override fun recordIntent(prefs: SharedPreferences, editor: SharedPreferences.Editor, id: String, title: String) {}
}

/** Prefs whose every commit passes [onCommit] first with the keys it writes; false fails the commit (nothing written). */
class HookedPrefs(private val base: SharedPreferences, var onCommit: (Set<String>) -> Boolean = { true }) : SharedPreferences by base {
    override fun edit(): SharedPreferences.Editor = HookedEditor(base.edit())

    private inner class HookedEditor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor by e {
        private val keys = mutableSetOf<String>()
        private fun k(key: String) = apply { keys += key }
        override fun putString(key: String, value: String?) = k(key).also { e.putString(key, value) }
        override fun putLong(key: String, value: Long) = k(key).also { e.putLong(key, value) }
        override fun putInt(key: String, value: Int) = k(key).also { e.putInt(key, value) }
        override fun putBoolean(key: String, value: Boolean) = k(key).also { e.putBoolean(key, value) }
        override fun remove(key: String) = k(key).also { e.remove(key) }
        override fun commit() = onCommit(keys) && e.commit()
        override fun apply() = throw AssertionError("apply() is never used: every write is a checked commit")
    }
}

/**
 * The receiver's context: records the connector's ACK (any broadcast) into [log], hands out [prefs] for the push prefs,
 * and is its own application context so tests choose the sink and the test push.
 */
class LogContext(
    base: Context,
    private val log: EventLog,
    private val prefs: SharedPreferences? = null,
    override val pushSink: PushSink? = null,
    override val testPush: TestPush? = null,
) : ContextWrapper(base), PushSinkProvider {
    val sent = mutableListOf<Intent>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        if (name == PUSH_PREFS && prefs != null) prefs else super.getSharedPreferences(name, mode)
    private fun record(intent: Intent) {
        log.step("broadcast ${intent.action?.substringAfterLast('.')} to ${intent.`package`}")
        sent += intent
    }
    override fun sendBroadcast(intent: Intent) = record(intent)
    override fun sendBroadcast(intent: Intent, receiverPermission: String?) = record(intent)
    override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) = record(intent)
}

/** An installed app with a receiver for the distributor REGISTER action. */
fun installDistributor(context: Context, pkg: String) {
    val pm = shadowOf(context.packageManager)
    pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg } })
    val cn = ComponentName(pkg, "$pkg.Distributor")
    pm.addReceiverIfNotPresent(cn).exported = true
    pm.addIntentFilterForReceiver(cn, IntentFilter("org.unifiedpush.android.distributor.REGISTER"))
}

/** The connector keeps its database in a process-wide singleton; each Robolectric test has a fresh app. */
fun resetConnectorStore() {
    val f = Class.forName("org.unifiedpush.android.connector.internal.DBStore").getDeclaredField("instance")
    f.isAccessible = true
    f.set(null, null)
}

const val VALID = "https://ntfy.sh/upAbC123xyz_-9?up=1"
const val VALID2 = "https://ntfy.sh/upZyX987abc_-1?up=1"
const val VALID3 = "https://ntfy.sh/upQwErTy123456?up=1"
