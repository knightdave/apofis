package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors
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

private const val TAG = "Apofis"
private const val EXTRA_TOKEN = "token"
private const val EXTRA_NEW_DISTRIBUTOR = "newDistributor"
private val REVOCATIONS = setOf("org.unifiedpush.android.connector.UNREGISTERED", "org.unifiedpush.android.connector.REGISTRATION_FAILED")

/** Takes a delivered push, which carries no body here. True only after a checked durable commit of the wake demand. */
fun interface PushSink {
    fun onPush(context: Context): Boolean
}

/** Implemented by the Application class. */
interface PushSinkProvider {
    val pushSink: PushSink?
    val testPush: TestPush? get() = null
    val pushRegistrar: PushRegistrar? get() = null
}

// One thread for every event, so they are handled in delivery order; nothing on it waits on other work.
private val events = Executors.newSingleThreadExecutor()

/**
 * The only UnifiedPush receiver (the library's own is removed in the manifest). An event is trusted only when its token
 * is the one journaled for the current instance with an allowed distributor. The connector checks the token and sends
 * its ACK after the callback returns, so everything learned is committed before that; a revocation is committed even
 * before the connector drops its registration. A message is only a wake: its bytes are never read.
 */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result
class PushReceiver : MessagingReceiver() {
    private var changed = false
    private var trusted = false

    override fun getKeyManager(context: Context): KeyManager = PlainKeyManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        events.execute { receive(context, intent, finish = { pending?.finish() }) }
    }

    internal fun receive(context: Context, intent: Intent, finish: () -> Unit, schedule: (Context) -> Unit = PublishWorker::schedule) {
        try {
            val prefs = pushPrefs(context)
            if (!PushStore.flush(prefs)) { // a lost write may sit in memory: touch nothing
                lost("prefs not written")
                return
            }
            val token = runCatching { intent.getStringExtra(EXTRA_TOKEN) }.getOrNull()
            val r = registrationOf(prefs)
            trusted = token != null && r != null && token == r.token && r.instance == instanceOf(prefs) && r.distributor in pushPolicy.distributors(context)
            if (trusted && intent.action in REVOCATIONS) {
                if (!PushStore.commit(prefs) { putString(Keys.UNREGISTERED_INSTANCE, r!!.instance) }) {
                    lost("prefs not written")
                    return
                }
                changed = true
            }
            // Distributor migrations are never followed: UNREGISTERED stays an unregistration, TEMP_UNAVAILABLE does nothing.
            val migration = runCatching { intent.getStringExtra(EXTRA_NEW_DISTRIBUTOR) }.getOrNull() != null
            super.onReceive(context, if (migration) Intent(intent).apply { removeExtra(EXTRA_NEW_DISTRIBUTOR) } else intent)
            if (changed) schedule(context) // not awaited: the commit is the hand-off, recovery re-schedules
        } catch (e: Exception) {
            Log.w(TAG, "PushReceiver: event not handled: ${e.javaClass.name}")
        } finally {
            finish()
        }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val prefs = pushPrefs(context)
        if (dropStale(context, instance)) return
        changed = PushStore.locked {
            trusted && instance == instanceOf(prefs) &&
                (PushStore.commit(prefs) { putString(Keys.ENDPOINT_NEW, endpoint.url).putString(Keys.ENDPOINT_NEW_INSTANCE, instance) } || lost("prefs not written"))
        }
    }

    // A trusted revocation was committed before the connector ran; others concern no current registration.
    override fun onUnregistered(context: Context, instance: String) {}

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        dropStale(context, instance)
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        if (dropStale(context, instance)) return
        val app = context.applicationContext as? PushSinkProvider ?: return
        // Under the push lock: no generation change can fall between the instance check and the wake's commit.
        PushStore.locked {
            if (!trusted || instance != instanceOf(pushPrefs(context))) return@locked
            app.testPush?.onWake()
            val sink = app.pushSink ?: return@locked
            val handedOff = try {
                sink.onPush(context)
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

    /** A callback for an instance that is no longer current: ignored, and that instance unregistered. */
    private fun dropStale(context: Context, instance: String): Boolean {
        if (instance == instanceOf(pushPrefs(context))) return false
        RealUnifiedPush.unregister(context, Registration(instance, null, null))
        return true
    }

    private fun lost(what: String): Boolean {
        Log.w(TAG, "PushReceiver: event lost, $what")
        return false
    }
}
