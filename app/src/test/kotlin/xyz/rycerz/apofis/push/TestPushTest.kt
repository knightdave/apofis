package xyz.rycerz.apofis.push

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.InterruptedIOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val O = "1:g1" // the registration a test belongs to

@RunWith(RobolectricTestRunner::class)
class TestPushTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private var now = 5_000_000L
    private lateinit var origin: String
    private lateinit var endpoint: String

    @Before
    fun setUp() {
        server.start()
        origin = "http://127.0.0.1:${server.port}"
        endpoint = "$origin/upWIRECHECK012?up=1"
    }

    @After
    fun tearDown() {
        server.close()
        assertTrue("never a notification", shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications.isEmpty())
    }

    private fun push(client: OkHttpClient = OkHttpClient()) = TestPush({ now }, client) { validateEndpoint(it, setOf(NTFY_ORIGIN), origin) }

    @Test
    fun postsOnlyTheTestKind() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        val t = push()
        assertTrue(t.send(endpoint, O))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/upWIRECHECK012?up=1", req.target)
        assertEquals("""{"kind":"test"}""", req.body!!.utf8())
        assertEquals("application/json", req.headers["Content-Type"])
        assertEquals("1", req.headers["X-UnifiedPush"])
        assertNull(req.headers["Authorization"])
        assertNull(req.headers["Cookie"])
        assertEquals(TestPushResult.SENT, t.lastResult.value?.result)
    }

    @Test
    fun redirectAndInterruptedFail() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "$origin/upWIRECHECK013?up=1").build())
        val t = push()
        assertFalse(t.send(endpoint, O))
        assertEquals(TestPushResult.SEND_FAILED, t.lastResult.value?.result)
        assertEquals(1, server.requestCount) // never followed

        val broken = OkHttpClient.Builder().addInterceptor { throw InterruptedIOException("timeout") }.build()
        val t2 = push(broken)
        assertFalse(t2.send(endpoint, O))
        assertEquals(TestPushResult.SEND_FAILED, t2.lastResult.value?.result)

    }

    /** No network: answers [code] after running [during] (as if it happened while the POST was in flight). */
    private fun answering(code: Int = 200, during: () -> Unit = {}) = OkHttpClient.Builder().addInterceptor { chain ->
        calls++
        during()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body("".toResponseBody()).build()
    }.build()
    private var calls = 0

    @Test
    fun rejectedEndpointsMakeNoCall() = runTest {
        for (bad in listOf("https://evil.example/upAbC123xyz_-9?up=1", "http://127.0.0.1:1/upWIRECHECK012?up=1", "file:///upAbC123xyz_-9")) {
            val t = push(answering())
            assertFalse(t.send(bad, O))
            assertEquals(TestPushResult.SEND_FAILED, t.lastResult.value?.result)
        }
        assertEquals("no request attempted", 0, calls)
    }

    @Test
    fun wakeBeforeTheAnswerIsNotCounted() = runTest {
        lateinit var t: TestPush
        t = push(answering { t.onWake(O) })
        assertTrue(t.send(endpoint, O))
        assertEquals("known limit: the user sends again", TestPushResult.SENT, t.lastResult.value?.result)
    }

    @Test
    fun windowStartsAtTheAnswer() = runTest {
        val t = push(answering { now += 9_000 }) // a slow POST
        t.send(endpoint, O)
        now += TEST_WINDOW_MS - 1
        t.tick()
        assertEquals(TestPushResult.SENT, t.lastResult.value?.result)
        t.onWake(O)
        assertEquals(TestPushResult.WAKE_ARRIVED, t.lastResult.value?.result)
    }

    @Test
    fun oneSendAtATime() = runTest {
        lateinit var t: TestPush
        var second: Boolean? = null
        t = push(answering { if (calls == 1) { now += 10_000; second = runBlocking { t.send(endpoint, O) } } }) // past the 10 s throttle, still in flight
        assertTrue(t.send(endpoint, O))
        assertEquals(false, second)
        assertEquals(1, calls)
    }

    @Test
    fun wakeInsideTheWindowArrives() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        val t = push()
        t.send(endpoint, O)
        now += TEST_WINDOW_MS - 1
        t.tick()
        assertEquals(TestPushResult.SENT, t.lastResult.value?.result)
        t.onWake(O)
        assertEquals(TestPushResult.WAKE_ARRIVED, t.lastResult.value?.result)
        now += TEST_WINDOW_MS
        t.tick()
        assertEquals(TestPushResult.WAKE_ARRIVED, t.lastResult.value?.result)
    }

    @Test
    fun noWakeUntilTheWindowEnds() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        val t = push()
        t.send(endpoint, O)
        now += TEST_WINDOW_MS
        t.tick()
        assertEquals(TestPushResult.NO_WAKE, t.lastResult.value?.result)
        t.onWake(O)
        assertEquals(TestPushResult.NO_WAKE, t.lastResult.value?.result)
    }

    @Test
    fun wakeWithoutWindowChangesNothing() = runTest {
        val t = push()
        t.onWake(O)
        t.tick()
        assertNull(t.lastResult.value?.result)
        // A failed send opens no window either.
        server.enqueue(MockResponse.Builder().code(500).build())
        t.send(endpoint, O)
        t.onWake(O)
        assertEquals(TestPushResult.SEND_FAILED, t.lastResult.value?.result)
    }

    @Test
    fun atMostOneSendPerTenSeconds() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).build()) }
        val t = push()
        assertTrue(t.send(endpoint, O))
        now += 9_999
        assertFalse(t.send(endpoint, O))
        assertEquals(1, server.requestCount)
        now += 1
        assertTrue(t.send(endpoint, O))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aCancelledSendReleasesItsSlot() = runTest {
        val inFlight = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        lateinit var t: TestPush
        t = push(answering { if (calls == 1) { inFlight.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) } })
        val job = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Default) { t.send(endpoint, O) }
        assertTrue(inFlight.await(10, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel() // the Settings sheet closed while the POST was out
        release.countDown()
        job.join()
        now += 10_000
        assertTrue("the same instance sends again", t.send(endpoint, O))
        assertEquals(TestPushResult.SENT, t.lastResult.value?.result)
    }

    // Fix round 1 (Task 5): a test belongs to the registration it was sent for.

    @Test
    fun aTestCountsOnlyForItsRegistration() = runTest {
        val a = "1:g1"
        val b = "2:g2"
        val t = push(answering())
        assertTrue(t.send(endpoint, a))
        assertEquals(TestOutcome(a, TestPushResult.SENT), t.lastResult.value)
        t.onWake(b) // another registration's wake: it completes nothing
        assertEquals(TestOutcome(a, TestPushResult.SENT), t.lastResult.value)
        t.onWake(a)
        assertEquals(TestOutcome(a, TestPushResult.WAKE_ARRIVED), t.lastResult.value)
    }

    @Test
    fun aLateCompletionStaysWithItsRegistration() = runTest {
        val a = "1:g1"
        val b = "2:g2"
        lateinit var t: TestPush
        t = push(answering { t.onWake(b) }) // the registration changed while A's POST was answered; B's wake arrives
        assertTrue(t.send(endpoint, a))
        assertEquals("the late completion is A's, not B's", TestOutcome(a, TestPushResult.SENT), t.lastResult.value)
        t.onWake(b)
        assertEquals(TestOutcome(a, TestPushResult.SENT), t.lastResult.value)
        now += TEST_WINDOW_MS
        t.tick()
        assertEquals(TestOutcome(a, TestPushResult.NO_WAKE), t.lastResult.value)
    }
}
