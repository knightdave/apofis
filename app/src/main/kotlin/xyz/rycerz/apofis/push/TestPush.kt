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

/**
 * The Settings test push, body-blind: the app POSTs `{"kind":"test"}` to its own endpoint, and the next wake delivered
 * within [TEST_WINDOW_MS] counts as "a push arrived". Nothing here reads a push or posts a notification.
 * [validate] is the endpoint rule with the build's origins.
 */
class TestPush(private val clock: () -> Long, client: OkHttpClient, private val validate: (String) -> String?) {
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val result = MutableStateFlow<TestPushResult?>(null)
    val lastResult: StateFlow<TestPushResult?> = result.asStateFlow()

    // Guarded by this. The window opens when the send starts: ntfy can deliver before its HTTP answer arrives.
    private var sentAt: Long? = null
    private var windowFrom: Long? = null
    private var wokeWhileSending = false

    suspend fun send(endpoint: String): Boolean {
        synchronized(this) {
            val now = clock()
            if (sentAt?.let { now - it in 0 until SEND_EVERY_MS } == true) return false
            sentAt = now
            windowFrom = now
            wokeWhileSending = false
            result.value = null
        }
        val url = validate(endpoint)
        val ok = url != null && try {
            val request = Request.Builder().url(url).header("X-UnifiedPush", "1").post(BODY.toRequestBody("application/json".toMediaType())).build()
            withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.isSuccessful } }
        } catch (_: IOException) {
            false
        }
        synchronized(this) {
            result.value = when {
                !ok -> TestPushResult.SEND_FAILED
                wokeWhileSending -> TestPushResult.WAKE_ARRIVED
                else -> TestPushResult.SENT
            }
            if (result.value != TestPushResult.SENT) windowFrom = null
        }
        return ok
    }

    /** Called by PushReceiver for every delivered message of the current registration. */
    fun onWake() = synchronized(this) {
        tick()
        if (windowFrom == null) return@synchronized
        if (result.value == TestPushResult.SENT) {
            result.value = TestPushResult.WAKE_ARRIVED
            windowFrom = null
        } else {
            wokeWhileSending = true
        }
    }

    /** Closes an expired window as [TestPushResult.NO_WAKE]; the Settings sheet calls it while open. */
    fun tick() = synchronized(this) {
        val from = windowFrom ?: return@synchronized
        if (clock() - from >= TEST_WINDOW_MS) {
            windowFrom = null
            if (result.value == TestPushResult.SENT) result.value = TestPushResult.NO_WAKE
        }
    }
}
