package xyz.rycerz.apofis.push

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.sqlite.SQLiteOpenHelper
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

data class Row(
    val title: String?,
    val source: String,
    var hidden: Boolean = false,
    var pinned: Boolean = false,
    var messageCount: Int = 0,
    var parent: String? = null,
    var endReason: String? = null,
)

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
    var beforeCall: suspend (String) -> Unit = {} // suspends a call, for tests that change things meanwhile
    val calls = mutableListOf<String>()

    private suspend fun call(name: String) {
        beforeCall(name)
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

    override suspend fun read(id: String): RecordRow? {
        call("read $id")
        return s.rows[id]?.let { RecordRow(it.title, it.source, it.hidden, it.pinned, it.messageCount, it.parent, it.endReason) }
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
    var journalAs: String? = null // a connector that would send REGISTER to this package instead

    override fun distributors(c: Context) = installed.toList()
    override fun saved(c: Context) = saved?.takeIf { it in installed }
    override fun acked(c: Context) = saved(c)?.takeIf { acked }
    override fun save(c: Context, d: String) {
        log.step("save $d"); calls += "save $d"; saved = d
    }
    override fun register(c: Context, instance: String, journal: (String, String) -> Unit) {
        log.step("register $instance")
        journal(journalAs ?: saved ?: NTFY_PACKAGE, "tok-$instance")
        calls += "register $instance"; registered += instance
    }
    override fun unregister(c: Context, r: Registration) {
        log.step("unregister ${r.instance}"); calls += "unregister ${r.instance}" + (r.token?.let { " $it" } ?: ""); registered -= r.instance
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

/**
 * SharedPreferences as Android behaves: a commit changes memory first, then writes the whole map to [disk]; when
 * [onCommit] returns false the write fails and memory keeps the change. [restart] is a new process reading [disk].
 * [onCommit] gets the keys the commit touches and may throw [Death].
 */
class FakePrefs(val disk: MutableMap<String, Any?> = mutableMapOf(), var onCommit: (Set<String>) -> Boolean = { true }) : SharedPreferences {
    private val mem = disk.toMutableMap()
    fun restart() = FakePrefs(disk, onCommit)

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(key: String, default: T): T = (mem[key] as T?) ?: default
    override fun getAll(): Map<String, *> = mem.toMap()
    override fun getString(key: String, defValue: String?) = get(key, defValue)
    override fun getStringSet(key: String, defValues: Set<String>?) = get(key, defValues)
    override fun getInt(key: String, defValue: Int) = get(key, defValue)
    override fun getLong(key: String, defValue: Long) = get(key, defValue)
    override fun getFloat(key: String, defValue: Float) = get(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = get(key, defValue)
    override fun contains(key: String) = key in mem
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val ops = linkedMapOf<String, Any?>()
        private var clear = false
        private val removed = Any()
        private fun put(k: String, v: Any?) = apply { ops[k] = v }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, removed)
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) mem.clear()
            for ((k, v) in ops) if (v === removed || v == null) mem.remove(k) else mem[k] = v
            if (!onCommit(ops.keys)) return false
            disk.clear()
            disk.putAll(mem)
            return true
        }
        override fun apply() = throw AssertionError("apply() is never used: every write is a checked commit")
    }
}

/**
 * The receiver's context: records the connector's broadcasts (the ACK among them) into [log], hands out [prefs] for the
 * push prefs, and is its own application context so tests choose the sink and the test push.
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

/**
 * The connector keeps its database in a process-wide singleton: close it and delete its file, so the next use starts
 * from an empty store (each Robolectric test has a fresh app, loops inside one test do not).
 */
fun resetConnectorStore(context: Context) {
    val f = Class.forName("org.unifiedpush.android.connector.internal.DBStore").getDeclaredField("instance")
    f.isAccessible = true
    (f.get(null) as SQLiteOpenHelper?)?.close()
    f.set(null, null)
    context.deleteDatabase("unifiedpush-connector")
}

const val VALID = "https://ntfy.sh/upAbC123xyz_-9?up=1"
const val VALID2 = "https://ntfy.sh/upZyX987abc_-1?up=1"
const val VALID3 = "https://ntfy.sh/upQwErTy123456?up=1"
