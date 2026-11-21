package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import xyz.rycerz.apofis.connectionEpoch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.keys.KeyManager

// The `wake` prefs Task 4's sink keeps: monotonic demand and done counters, wakes lost to a failed commit, and the
// connection epoch the demand belongs to.
internal const val WAKE_PREFS = "wake"
internal const val WAKE_DEMAND = "demand"
internal const val WAKE_DONE = "done"
internal const val WAKE_LOST = "lost"
internal const val WAKE_EPOCH = "epoch"

/** Every broadcast's pending result is finished at most this long after receipt (Android allows 10 s in the foreground). */
internal const val BUDGET_MS = 8_000L

private const val TAG = "Apofis"
private const val EXTRA_TOKEN = "token"
private const val EXTRA_NEW_DISTRIBUTOR = "newDistributor"
private val REVOCATIONS = setOf("org.unifiedpush.android.connector.UNREGISTERED", "org.unifiedpush.android.connector.REGISTRATION_FAILED")

/**
 * Takes a delivered push, which carries no body here. [onPush] runs under the push lock, after the receiver checked that
 * the registration belongs to [epoch], the persisted connection epoch (`connection_epoch`), which only changes under
 * that lock. It returns true only after a checked durable commit of the wake demand for that epoch. [recover] runs at
 * every process start and hands on demand of the current epoch that is not done yet.
 */
fun interface PushSink {
    fun onPush(context: Context, epoch: Long): Boolean
    fun recover(context: Context) {}
}

/** Implemented by the Application class. */
interface PushSinkProvider {
    val pushSink: PushSink?
    val testPush: TestPush? get() = null
    val pushRegistrar: PushRegistrar? get() = null
}

/** Process start: push work left by the last process, then the sink's own. */
internal fun recoverPush(context: Context, sink: PushSink?) {
    PublishWorker.recover(context)
    sink?.recover(context)
}

// One thread for every event, so they are handled in delivery order; nothing on it waits on other work.
private val events = Executors.newSingleThreadExecutor()
private val deadlines = Executors.newSingleThreadScheduledExecutor()

/**
 * Runs [work] on [lane] and finishes the broadcast exactly once, at the latest [BUDGET_MS] after receipt: when the
 * deadline comes first for an event the lane has not started, that event is abandoned (not handled, not ACKed: the
 * distributor delivers again); an event already started is finished on time and its work goes on.
 */
internal fun admit(lane: Executor, deadline: (Runnable) -> Unit, finish: () -> Unit, work: (finish: () -> Unit) -> Unit) {
    val finished = AtomicBoolean()
    val claimed = AtomicBoolean()
    val finishOnce = { if (finished.compareAndSet(false, true)) finish() }
    deadline {
        if (claimed.compareAndSet(false, true)) Log.w(TAG, "PushReceiver: event lost, late")
        finishOnce()
    }
    lane.execute { if (claimed.compareAndSet(false, true)) work(finishOnce) }
}

/**
 * The only UnifiedPush receiver (the library's own is removed in the manifest). An event reaches the connector only
 * when its token is the one journaled for the current instance with an allowed distributor; any other is dropped
 * before the connector could change a registration. The connector sends its ACK after the callback returns, so
 * everything learned is committed before that, and a failed commit throws so nothing is ACKed. A revocation is
 * committed before the connector drops its registration. A message is only a wake: its bytes are never read.
 */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result
class PushReceiver : MessagingReceiver() {
    private var changed = false
    private var token: String? = null

    override fun getKeyManager(context: Context): KeyManager = PlainKeyManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        admit(events, { deadlines.schedule(it, BUDGET_MS, TimeUnit.MILLISECONDS) }, { pending?.finish() }) { finish -> receive(context, intent, finish) }
    }

    internal fun receive(context: Context, intent: Intent, finish: () -> Unit, schedule: (Context) -> Unit = PublishWorker::schedule) {
        try {
            val prefs = pushPrefs(context)
            val wasDirty = PushStore.isDirty(prefs)
            if (!PushStore.flush(prefs)) return lost("prefs not written") // a lost write may sit in memory: touch nothing
            if (wasDirty) changed = true // what failed before is on disk now: the registrar acts on it
            token = runCatching { intent.getStringExtra(EXTRA_TOKEN) }.getOrNull()
            val trusted = PushStore.locked {
                trusted(context) && (intent.action !in REVOCATIONS || commit(prefs) { putString(Keys.UNREGISTERED_INSTANCE, instanceOf(prefs)) })
            }
            if (!trusted) return
            if (intent.action in REVOCATIONS) changed = true
            // Distributor migrations are never followed: UNREGISTERED stays an unregistration, TEMP_UNAVAILABLE does nothing.
            val migration = runCatching { intent.getStringExtra(EXTRA_NEW_DISTRIBUTOR) }.getOrNull() != null
            super.onReceive(context, if (migration) Intent(intent).apply { removeExtra(EXTRA_NEW_DISTRIBUTOR) } else intent)
        } catch (e: Exception) {
            Log.w(TAG, "PushReceiver: event not handled: ${e.javaClass.name}")
            if (PushStore.isDirty(pushPrefs(context))) {
                // What it learned is not on disk: leave On now, and recover after the next write (and at the next start).
                (context.applicationContext as? PushSinkProvider)?.pushRegistrar?.refresh()
                changed = true
            }
        } finally {
            if (changed) runCatching { schedule(context) } // not awaited: the commit is the hand-off, recovery re-schedules
            finish()
        }
    }

    /**
     * Under the push lock: the token is the journaled one of the current instance, with an allowed distributor, made under
     * the current connection epoch.
     */
    private fun trusted(context: Context): Boolean {
        val r = currentRegistration(pushPrefs(context), pushPolicy.distributors(context))
        return r != null && token != null && token == r.token && r.epoch == connectionEpoch(context)
    }

    private fun commit(prefs: android.content.SharedPreferences, edit: android.content.SharedPreferences.Editor.() -> Unit): Boolean =
        PushStore.commit(prefs, edit) || throw IOException("push prefs not written")

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val prefs = pushPrefs(context)
        PushStore.locked {
            if (!trusted(context) || instance != instanceOf(prefs)) return@locked
            // A failed commit throws: the connector then sends no ACK, and the registration is not confirmed.
            commit(prefs) { putString(Keys.ENDPOINT_NEW, endpoint.url).putString(Keys.ENDPOINT_NEW_TOKEN, token) }
            changed = true
        }
    }

    // A trusted revocation was committed before the connector ran.
    override fun onUnregistered(context: Context, instance: String) {}

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {}

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val app = context.applicationContext as? PushSinkProvider ?: return
        // Under the push lock: no generation or connection change can fall between this check and the wake's commit.
        PushStore.locked {
            if (!trusted(context) || instance != instanceOf(pushPrefs(context))) return@locked
            app.testPush?.onWake()
            val sink = app.pushSink ?: return@locked
            val handedOff = try {
                sink.onPush(context, connectionEpoch(context))
            } catch (e: Exception) {
                false
            }
            if (!handedOff) {
                lost("wake lost")
                val wake = context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
                if (!wake.edit().putLong(WAKE_LOST, wake.getLong(WAKE_LOST, 0) + 1).commit()) lost("loss count not written")
            }
        }
    }

    private fun lost(what: String) {
        Log.w(TAG, "PushReceiver: event lost, $what")
    }
}
