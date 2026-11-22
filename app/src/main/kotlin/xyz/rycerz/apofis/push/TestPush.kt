package xyz.rycerz.apofis.push

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val TEST_WINDOW_MS = 60_000L
private const val SEND_EVERY_MS = 10_000L
private val BODY = """{"kind":"test"}""".toByteArray()

enum class TestPushResult { SENT, SEND_FAILED, WAKE_ARRIVED, NO_WAKE }

/** A result and the registration it belongs to ([testOwner]). */
data class TestOutcome(val owner: String, val result: TestPushResult)

/** The non-secret identity of a registration: the connection epoch and the connector instance. */
internal fun testOwner(epoch: Long, instance: String) = "$epoch:$instance"

/**
 * The Settings test push, body-blind: the app POSTs `{"kind":"test"}` to its own endpoint, and a wake delivered within
 * [TEST_WINDOW_MS] after a 2xx answer counts as "a push arrived". Accepted limit: a wake delivered before that answer is
 * not counted (the user sends again). A test belongs to the registration it was sent for: its window, its result and the
 * wake that completes it count only for that owner. Nothing here reads a push or posts a notification. [elapsed] is a monotonic clock;
 * [validate] is the endpoint rule with the build's origins; [client] should be dedicated to this.
 */
class TestPush(private val elapsed: () -> Long, client: OkHttpClient, private val validate: (String) -> String?) {
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val result = MutableStateFlow<TestOutcome?>(null)
    val lastResult: StateFlow<TestOutcome?> = result.asStateFlow()

    // Guarded by this.
    private var sending = false
    private var sentAt: Long? = null
    private var windowFrom: Long? = null
    private var windowOwner: String? = null

    /** For [owner], taken when the user tapped. False without sending when a send is in flight or the last one started under 10 s ago. */
    suspend fun send(endpoint: String, owner: String): Boolean {
        synchronized(this) {
            val now = elapsed()
            if (sending || sentAt?.let { now - it < SEND_EVERY_MS } == true) return false
            sending = true
            sentAt = now
            windowFrom = null
            result.value = null
        }
        var ok = false
        try {
            val url = validate(endpoint)
            ok = url != null && try {
                val request = Request.Builder().url(url).header("X-UnifiedPush", "1").post(BODY.toRequestBody("application/json".toMediaType())).build()
                withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.isSuccessful } }
            } catch (_: IOException) {
                false
            }
            synchronized(this) {
                if (ok) {
                    windowFrom = elapsed()
                    windowOwner = owner
                }
                result.value = TestOutcome(owner, if (ok) TestPushResult.SENT else TestPushResult.SEND_FAILED)
            }
        } finally {
            synchronized(this) { sending = false } // also when cancelled: the next send must work
        }
        return ok
    }

    /** Called by PushReceiver for every delivered message of the current registration, [owner]; only its own test completes. */
    fun onWake(owner: String) = synchronized(this) {
        tick()
        if (windowFrom != null && windowOwner == owner) {
            windowFrom = null
            result.value = TestOutcome(owner, TestPushResult.WAKE_ARRIVED)
        }
    }

    /** Closes an expired window as [TestPushResult.NO_WAKE]; the Settings sheet calls it while open. */
    fun tick() = synchronized(this) {
        val from = windowFrom ?: return@synchronized
        if (elapsed() - from >= TEST_WINDOW_MS) {
            windowFrom = null
            result.value = windowOwner?.let { TestOutcome(it, TestPushResult.NO_WAKE) }
        }
    }
}
