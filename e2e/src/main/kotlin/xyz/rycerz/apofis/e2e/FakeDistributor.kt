package xyz.rycerz.apofis.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val REGISTER = "org.unifiedpush.android.distributor.REGISTER"
private const val UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
private const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
private const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
private const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
private const val TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"

/**
 * The e2e APK's UnifiedPush distributor, which only the app's debug build accepts. It records every REGISTER and
 * UNREGISTER it receives with the sending package as the platform reports it (API 34+), and answers a REGISTER from the
 * app with NEW_ENDPOINT naming the fake ntfy topic (`http://127.0.0.1:<port>/<topic>?up=1`, through adb reverse and the
 * harness's tunnel). It runs in the tests' own process, so [Push] reads what it recorded.
 */
class FakeDistributor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
        val token = runCatching { intent.getStringExtra("token") }.getOrNull()
        synchronized(events) { events += Event(intent.action.orEmpty(), from, token) }
        distributorLog(intent.action, from)?.let { Log.i("ApofisE2e", it) }
        val endpoint = endpoint
        if (intent.action == REGISTER && from == APP && token != null && endpoint != null) {
            context.sendBroadcast(Intent(NEW_ENDPOINT).setPackage(APP).putExtra("token", token).putExtra("endpoint", endpoint))
        }
    }

    data class Event(val action: String, val from: String?, val token: String?)

    companion object {
        val events = mutableListOf<Event>()

        /** The endpoint a REGISTER from the app is answered with; null answers nothing. */
        @Volatile var endpoint: String? = null
    }
}

/** A message body of at most 4096 bytes (the fake's own limit); more fails. */
private fun readBounded(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(1024)
    while (out.size() <= 4096) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        out.write(buf, 0, n)
    }
    fail("The fake ntfy answered more than 4096 bytes")
}

/**
 * The log line for a broadcast the distributor received: only a known action from the accepted sender (the app), by a
 * fixed name; nothing for anything else (an explicit broadcast from any app can reach this exported receiver).
 */
fun distributorLog(action: String?, from: String?): String? =
    if (from == APP) mapOf(REGISTER to "REGISTER", UNREGISTER to "UNREGISTER")[action]?.let { "distributor: $it from the app" } else null

/** The fake distributor's side of a push scenario: what the app registered, and the broadcasts a distributor sends. */
class Push(private val app: AppDriver) {
    private val base = "http://127.0.0.1:${app.pushPort}"

    init {
        synchronized(FakeDistributor.events) { FakeDistributor.events.clear() }
        FakeDistributor.endpoint = "$base/${app.pushTopic}?up=1"
    }

    fun events(action: String) = synchronized(FakeDistributor.events) { FakeDistributor.events.filter { it.action == action && it.from == APP } }

    fun registrations() = events(REGISTER)

    fun unregistrations() = events(UNREGISTER)

    /** The endpoint the app is given, as the fake ntfy topic's loopback URL. */
    val endpoint get() = "$base/${app.pushTopic}?up=1"

    /** The token of the app's last REGISTER: its current registration. */
    fun token(): String = registrations().lastOrNull()?.token ?: fail("The app never registered with the e2e distributor")

    fun deliver(body: ByteArray) = send(Intent(MESSAGE).putExtra("bytesMessage", body))

    fun unregister() = send(Intent(UNREGISTERED))

    /** A distributor moving the registration to [to]: UNREGISTERED naming the new distributor. */
    fun migrate(to: String) = send(Intent(UNREGISTERED).putExtra("newDistributor", to))

    fun tempUnavailable(to: String) = send(Intent(TEMP_UNAVAILABLE).putExtra("newDistributor", to))

    private fun send(intent: Intent) {
        app.instrumentation.context.sendBroadcast(intent.setPackage(APP).putExtra("token", token()))
        app.log("distributor sent ${intent.action?.substringAfterLast('.')}")
    }

    /** The oldest message the fake ntfy holds for the topic, removed there; null when there is none. At most 4096 bytes. */
    fun poll(): ByteArray? {
        val c = URL("$base/${app.pushTopic}/poll").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        return try {
            when (val code = c.responseCode) {
                200 -> c.inputStream.use(::readBounded)
                204 -> null
                else -> fail("The fake ntfy answered the poll with $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Asks the fake ntfy to run the real hook once against its synthetic database (gate 2 only). It answers 200 only with
     * proof that this invocation's child posted exactly once to it (fake_ntfy.py).
     */
    fun trigger() {
        val c = URL("$base/trigger").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.instanceFollowRedirects = false
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) fail("The fake ntfy's trigger answered ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }
}
