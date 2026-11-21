package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
private const val EXTRA_NEW_DISTRIBUTOR = "newDistributor"

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

// One thread for every event, so they are handled in delivery order.
private val events = Executors.newSingleThreadExecutor()

/**
 * The only UnifiedPush receiver (the library's own is removed in the manifest). The connector checks the token before
 * anything happens and sends its ACK after the callback returns, so each callback commits what it learned before
 * returning. A message is only a wake: its bytes are never read.
 */
@SuppressLint("UseKtx") // the KTX edit drops commit()'s result, which every write here must check
class PushReceiver : MessagingReceiver() {
    private var changed = false

    override fun getKeyManager(context: Context): KeyManager = PlainKeyManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        events.execute { receive(context, intent, finish = { pending?.finish() }) }
    }

    internal fun receive(context: Context, intent: Intent, finish: () -> Unit, enqueue: (Context) -> Unit = PublishWorker::enqueue) {
        try {
            // A migration to a package the policy does not allow is reduced to the plain action: after the connector's
            // token check, UNREGISTERED is an unregistration and TEMP_UNAVAILABLE does nothing.
            val foreign = runCatching { intent.getStringExtra(EXTRA_NEW_DISTRIBUTOR) }.getOrNull()?.takeIf { it !in pushPolicy.distributors(context) }
            super.onReceive(context, if (foreign != null) Intent(intent).apply { removeExtra(EXTRA_NEW_DISTRIBUTOR) } else intent)
            if (changed) enqueue(context)
        } catch (e: Exception) {
            Log.w(TAG, "PushReceiver: event not handled: ${e.javaClass.name}")
        } finally {
            finish()
        }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val prefs = pushPrefs(context)
        if (stale(context, prefs, instance)) return
        changed = commit(prefs.edit().putString(Keys.ENDPOINT_NEW, endpoint.url).putString(Keys.ENDPOINT_NEW_INSTANCE, instance))
    }

    override fun onUnregistered(context: Context, instance: String) {
        val prefs = pushPrefs(context)
        // The connector has already dropped the registration; only the current instance matters to the app.
        if (instance == instanceOf(prefs)) changed = commit(prefs.edit().putString(Keys.UNREGISTERED_INSTANCE, instance))
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        val prefs = pushPrefs(context)
        if (stale(context, prefs, instance)) return
        changed = commit(prefs.edit().putString(Keys.UNREGISTERED_INSTANCE, instance))
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        if (stale(context, pushPrefs(context), instance)) return
        val app = context.applicationContext as? PushSinkProvider ?: return
        app.testPush?.onWake()
        val sink = app.pushSink ?: return
        if (!sink.onPush(context)) {
            Log.w(TAG, "PushReceiver: wake lost")
            val wake = context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
            commit(wake.edit().putLong(WAKE_LOST, wake.getLong(WAKE_LOST, 0) + 1))
        }
    }

    /** A callback for an instance that is no longer current: ignored, and that instance unregistered. */
    private fun stale(context: Context, prefs: SharedPreferences, instance: String): Boolean {
        if (instance == instanceOf(prefs)) return false
        RealUnifiedPush.unregister(context, instance)
        return true
    }

    private fun commit(editor: SharedPreferences.Editor): Boolean = editor.commit().also {
        if (!it) Log.w(TAG, "PushReceiver: event lost, prefs not written")
    }
}
