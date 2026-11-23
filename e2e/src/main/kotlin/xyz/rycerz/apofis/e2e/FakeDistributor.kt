package xyz.rycerz.apofis.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

private const val REGISTER = "org.unifiedpush.android.distributor.REGISTER"
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
        Log.i("ApofisE2e", "distributor: ${intent.action?.substringAfterLast('.')} from $from") // never the token or endpoint
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

/** The fake distributor's side of a push scenario: what the app registered, and the broadcasts a distributor sends. */
class Push(private val app: AppDriver) {
    private val base = "http://127.0.0.1:${app.pushPort}"

    init {
        synchronized(FakeDistributor.events) { FakeDistributor.events.clear() }
        FakeDistributor.endpoint = "$base/${app.pushTopic}?up=1"
    }

    fun events(action: String) = synchronized(FakeDistributor.events) { FakeDistributor.events.filter { it.action == action && it.from == APP } }

    fun registrations() = events(REGISTER)

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

    /** The oldest message the fake ntfy holds for the topic, removed there; null when there is none. */
    fun poll(): ByteArray? {
        val c = URL("$base/${app.pushTopic}/poll").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        return try {
            when (val code = c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }
                204 -> null
                else -> fail("The fake ntfy answered the poll with $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /** Asks the fake ntfy to run the real hook once against its synthetic database (gate 2 only). */
    fun trigger() {
        val c = URL("$base/trigger").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) fail("The fake ntfy's trigger answered ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }
}
