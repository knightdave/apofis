package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * that lock. It returns true only after a checked durable commit of the wake demand for that epoch; a false return leaves
 * nothing of it for a later commit to persist. [recover] runs at every process start, and after each handed-off push
 * outside the lock, and hands on demand of the current epoch that is not done yet.
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

/** Process start: push work left by the last process, then the sink's own. Called off the main thread. */
internal fun recoverPush(context: Context, sink: PushSink?) {
    PublishWorker.recover(context)
    sink?.recover(context)
}

// One thread for every event, so they are handled in delivery order; nothing on it waits on other work.
private val events = Executors.newSingleThreadExecutor()
private val deadlines = Executors.newSingleThreadScheduledExecutor()

/**
 * Runs [work] on [lane] and finishes the broadcast exactly once. [work] claims the event right before its own first
 * durable write and reports [Handoff.done] when that write returned. When the [BUDGET_MS] deadline after receipt comes
 * before the claim, the event is abandoned: the claim fails, nothing is committed, handled or ACKed, and the distributor
 * delivers again. When it comes between the claim and the end of that write, the broadcast is finished as soon as the
 * write returned; after that, at the deadline at the latest.
 */
internal class Handoff(private val finish: () -> Unit) {
    private var claimed = false
    private var done = false
    private var late = false
    private var finished = false

    @Synchronized fun claim(): Boolean = (!claimed && !late).also { if (it) claimed = true }
    @Synchronized fun done() {
        done = true
        if (late) finishOnce()
    }
    @Synchronized fun deadline() {
        late = true
        if (!claimed && !finished) Log.w(TAG, "PushReceiver: event lost, late")
        if (!claimed || done) finishOnce()
    }
    @Synchronized fun finishOnce() {
        if (!finished) {
            finished = true
            finish()
        }
    }
    @Synchronized fun abandoned() = late && !claimed
}

internal fun admit(lane: Executor, deadline: (Runnable) -> Unit, finish: () -> Unit, work: (Handoff) -> Unit) {
    val h = Handoff(finish)
    deadline { h.deadline() }
    lane.execute { if (!h.abandoned()) work(h) }
}

/** The deadline came before the event's first durable commit: it is not handled (and so not ACKed). */
private class Abandoned : Exception()

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
    private var woken: PushSink? = null // took a wake demand; schedules its job once the broadcast's work is done

    override fun getKeyManager(context: Context): KeyManager = PlainKeyManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        admit(events, { deadlines.schedule(it, BUDGET_MS, TimeUnit.MILLISECONDS) }, { pending?.finish() }) { h ->
            receive(context, intent, h::finishOnce, handoff = h)
        }
    }

    private var handoff: Handoff? = null

    internal fun receive(
        context: Context,
        intent: Intent,
        finish: () -> Unit,
        schedule: (Context) -> Unit = { PublishWorker.recover(it) },
        handoff: Handoff? = null,
    ) {
        this.handoff = handoff
        try {
            val prefs = pushPrefs(context)
            token = runCatching { intent.getStringExtra(EXTRA_TOKEN) }.getOrNull()
            // A distributor's revocation is committed before anything else is done with it (the connector would drop the
            // registration). Under one lock: an earlier unwritten change is flushed first, then the event is claimed, and
            // the next disk write is the event's own. Past the deadline it is left unclaimed, for redelivery.
            val trusted = PushStore.locked {
                val wasDirty = PushStore.isDirty(prefs)
                if (!PushStore.flush(prefs)) throw IOException("push prefs not written") // touch nothing on a lost write
                if (wasDirty) changed = true // what failed before is on disk now: the registrar acts on it
                trusted(context) && (intent.action !in REVOCATIONS || durably(prefs) { putString(Keys.UNREGISTERED_INSTANCE, instanceOf(prefs)) })
            }
            if (!trusted) return
            if (intent.action in REVOCATIONS) changed = true
            // Distributor migrations are never followed: UNREGISTERED stays an unregistration, TEMP_UNAVAILABLE does nothing.
            val migration = runCatching { intent.getStringExtra(EXTRA_NEW_DISTRIBUTOR) }.getOrNull() != null
            super.onReceive(context, if (migration) Intent(intent).apply { removeExtra(EXTRA_NEW_DISTRIBUTOR) } else intent)
        } catch (_: Abandoned) {
            lost("late")
        } catch (e: Exception) {
            Log.w(TAG, "PushReceiver: event not handled: ${e.javaClass.name}")
            if (PushStore.isDirty(pushPrefs(context))) {
                // What it learned is not on disk: leave On now, and recover after the next write (and at the next start).
                (context.applicationContext as? PushSinkProvider)?.pushRegistrar?.refresh()
                changed = true
            }
        } finally {
            if (changed) runCatching { schedule(context) } // not awaited: the commit is the hand-off, recovery re-schedules
            woken?.let { runCatching { it.recover(context) } } // the wake job, after the hand-off and outside the push lock
            finish()
        }
    }

    /**
     * Under the push lock: the token is the journaled one of the current instance, with an allowed distributor, made under
     * the current connection epoch.
     */
    private fun trusted(context: Context): Boolean {
        val r = currentRegistration(pushPrefs(context), pushPolicy.distributors(context), connectionEpoch(context))
        return r != null && token != null && token == r.token
    }

    /**
     * The event's own first durable write, under the push lock: claimed right before it (past the deadline the claim fails
     * and this throws, so the connector sends no ACK), reported done when it returned.
     */
    private fun durably(prefs: android.content.SharedPreferences, edit: android.content.SharedPreferences.Editor.() -> Unit): Boolean {
        if (!PushStore.flush(prefs)) throw IOException("push prefs not written") // nothing unrelated after the claim
        if (handoff?.claim() == false) throw Abandoned()
        return try {
            commit(prefs, edit)
        } finally {
            handoff?.done() // also when the write failed: nothing after it keeps the broadcast open
        }
    }

    private fun commit(prefs: android.content.SharedPreferences, edit: android.content.SharedPreferences.Editor.() -> Unit): Boolean =
        PushStore.commit(prefs, edit) || throw IOException("push prefs not written")

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val prefs = pushPrefs(context)
        PushStore.locked {
            if (!trusted(context) || instance != instanceOf(prefs)) return@locked
            // The endpoint already on disk for this token (a reply to an idempotent REGISTER): nothing to do, and no
            // recovery that would replace the running verification that asked for it.
            if (!PushStore.isDirty(prefs) && prefs.getString(Keys.ENDPOINT_NEW, null) == endpoint.url &&
                prefs.getString(Keys.ENDPOINT_NEW_TOKEN, null) == token
            ) {
                return@locked
            }
            // A failed commit throws: the connector then sends no ACK, and the registration is not confirmed.
            durably(prefs) { putString(Keys.ENDPOINT_NEW, endpoint.url).putString(Keys.ENDPOINT_NEW_TOKEN, token) }
            changed = true
        }
    }

    // A trusted revocation was committed before the connector ran.
    override fun onUnregistered(context: Context, instance: String) {}

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {}

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val app = context.applicationContext as? PushSinkProvider ?: return
        val testPush = app.testPush // looked up before the lock and the claim: building them is not part of the hand-off
        val sink = app.pushSink
        // Under the push lock: no generation or connection change can fall between this check and the wake's commit.
        PushStore.locked {
            if (!trusted(context) || instance != instanceOf(pushPrefs(context))) return@locked
            if (handoff?.claim() == false) throw Abandoned() // the sink's commit is the event's own first write
            try {
                testPush?.onWake()
                if (sink == null) return@locked
                val handedOff = try {
                    sink.onPush(context, connectionEpoch(context))
                } catch (e: Exception) {
                    false
                }
                handoff?.done() // the sink's attempt is the hand-off: the loss count below does not hold the broadcast
                if (handedOff) woken = sink else {
                    lost("wake lost")
                    val wake = context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
                    if (!wake.edit().putLong(WAKE_LOST, wake.getLong(WAKE_LOST, 0) + 1).commit()) lost("loss count not written")
                }
            } finally {
                handoff?.done()
            }
        }
    }

    private fun lost(what: String) {
        Log.w(TAG, "PushReceiver: event lost, $what")
    }
}
