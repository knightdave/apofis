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
        assertTrue(t.send(endpoint))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/upWIRECHECK012?up=1", req.target)
        assertEquals("""{"kind":"test"}""", req.body!!.utf8())
        assertEquals("application/json", req.headers["Content-Type"])
        assertEquals("1", req.headers["X-UnifiedPush"])
        assertNull(req.headers["Authorization"])
        assertNull(req.headers["Cookie"])
        assertEquals(TestPushResult.SENT, t.lastResult.value)
    }

    @Test
    fun redirectAndInterruptedFail() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "$origin/upWIRECHECK013?up=1").build())
        val t = push()
        assertFalse(t.send(endpoint))
        assertEquals(TestPushResult.SEND_FAILED, t.lastResult.value)
        assertEquals(1, server.requestCount) // never followed

        val broken = OkHttpClient.Builder().addInterceptor { throw InterruptedIOException("timeout") }.build()
        val t2 = push(broken)
        assertFalse(t2.send(endpoint))
        assertEquals(TestPushResult.SEND_FAILED, t2.lastResult.value)

        val t3 = push()
        assertFalse(t3.send("https://evil.example/upAbC123xyz_-9?up=1"))
        assertEquals(TestPushResult.SEND_FAILED, t3.lastResult.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun wakeInsideTheWindowArrives() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        val t = push()
        t.send(endpoint)
        now += TEST_WINDOW_MS - 1
        t.tick()
        assertEquals(TestPushResult.SENT, t.lastResult.value)
        t.onWake()
        assertEquals(TestPushResult.WAKE_ARRIVED, t.lastResult.value)
        now += TEST_WINDOW_MS
        t.tick()
        assertEquals(TestPushResult.WAKE_ARRIVED, t.lastResult.value)
    }

    @Test
    fun noWakeUntilTheWindowEnds() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        val t = push()
        t.send(endpoint)
        now += TEST_WINDOW_MS
        t.tick()
        assertEquals(TestPushResult.NO_WAKE, t.lastResult.value)
        t.onWake()
        assertEquals(TestPushResult.NO_WAKE, t.lastResult.value)
    }

    @Test
    fun wakeWithoutWindowChangesNothing() = runTest {
        val t = push()
        t.onWake()
        t.tick()
        assertNull(t.lastResult.value)
        // A failed send opens no window either.
        server.enqueue(MockResponse.Builder().code(500).build())
        t.send(endpoint)
        t.onWake()
        assertEquals(TestPushResult.SEND_FAILED, t.lastResult.value)
    }

    @Test
    fun atMostOneSendPerTenSeconds() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).build()) }
        val t = push()
        assertTrue(t.send(endpoint))
        now += 9_999
        assertFalse(t.send(endpoint))
        assertEquals(1, server.requestCount)
        now += 1
        assertTrue(t.send(endpoint))
        assertEquals(2, server.requestCount)
    }
}
