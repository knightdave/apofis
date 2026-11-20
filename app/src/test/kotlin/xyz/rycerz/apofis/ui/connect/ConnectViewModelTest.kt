package xyz.rycerz.apofis.ui.connect

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.HoldingQueue
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.settings.Credentials

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectViewModelTest {
    private lateinit var server: MockWebServer
    private val holding = HoldingQueue()
    private val saved = mutableListOf<Credentials>()
    private var clearsCache = false

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = holding
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun vm(current: Credentials? = null) =
        ConnectViewModel(current, willClearCache = { clearsCache }, save = { saved += it })

    private fun ConnectViewModel.submit(url: String, key: String) = runTest {
        onUrlChange(url)
        onKeyChange(key)
        connect()?.join()
    }

    @Test
    fun malformedUrl() {
        val vm = vm()
        vm.submit("hermes.example:8642", "k")
        assertEquals(ConnectState.Failed(Field.URL, Reason.MISSING_SCHEME), vm.state)
        vm.submit("https://bad host/", "k")
        assertEquals(ConnectState.Failed(Field.URL, Reason.MALFORMED_URL), vm.state)
        assertTrue(saved.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unreachable() {
        val url = server.url("/").toString()
        server.close()
        val vm = vm()
        vm.submit(url, "k")
        assertEquals(ConnectState.Failed(Field.URL, Reason.UNREACHABLE), vm.state)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun keyRejected() {
        server.enqueue(MockResponse.Builder().code(401).body(fixture("error_401.json")).build())
        val vm = vm()
        vm.submit(server.url("/").toString(), "wrong")
        assertEquals(ConnectState.Failed(Field.KEY, Reason.KEY_REJECTED), vm.state)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun notHermes() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html><body>Hello</body></html>").build())
        val vm = vm()
        vm.submit(server.url("/").toString(), "k")
        assertEquals(ConnectState.Failed(Field.URL, Reason.NOT_HERMES), vm.state)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun successSavesCredentials() {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val url = server.url("/").toString()
        val vm = vm()
        vm.submit("  $url  ", "sk-123")
        assertEquals(ConnectState.Connected, vm.state)
        assertEquals(listOf(Credentials(url, "sk-123")), saved)
        assertEquals("/v1/capabilities", server.takeRequest().url.encodedPath)
    }

    @Test
    fun httpUrlShowsWarning() {
        val vm = vm()
        vm.onUrlChange("https://hermes.example/")
        assertFalse(vm.httpWarning)
        vm.onUrlChange(" HTTP://[200:1234::1]:8642/")
        assertTrue(vm.httpWarning)
    }

    @Test
    fun emptyKeyKeepsCurrentKeyOnlyForTheSameServer() {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val url = server.url("/").toString()
        val vm = vm(current = Credentials(url, "sk-old"))
        assertEquals(url, vm.url)
        vm.submit(url, "")
        assertEquals(listOf(Credentials(url, "sk-old")), saved)
        assertEquals("Bearer sk-old", server.takeRequest().headers["Authorization"])

        // The stored key is never sent to a different server.
        val other = vm(current = Credentials("https://other.example/", "sk-old"))
        other.submit(url, "")
        assertEquals(ConnectState.Failed(Field.KEY, Reason.KEY_REQUIRED), other.state)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverChangeAsksBeforeClearingCache() {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        clearsCache = true
        val vm = vm()
        vm.submit(server.url("/").toString(), "k")
        assertEquals(ConnectState.ConfirmServerChange, vm.state)
        assertTrue(saved.isEmpty())
        runTest { vm.confirmServerChange()?.join() }
        assertEquals(ConnectState.Connected, vm.state)
        assertEquals(1, saved.size)
    }

    @Test
    fun saveFailureShowsErrorInsteadOfCrashing() {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val failing = ConnectViewModel(null, willClearCache = { clearsCache }, save = { throw java.io.IOException("disk full") })
        failing.submit(server.url("/").toString(), "k")
        assertEquals(ConnectState.Failed(Field.KEY, Reason.SAVE_FAILED), failing.state)

        clearsCache = true
        failing.submit(server.url("/").toString(), "k")
        assertEquals(ConnectState.ConfirmServerChange, failing.state)
        runTest { failing.confirmServerChange()?.join() }
        assertEquals(ConnectState.Failed(Field.KEY, Reason.SAVE_FAILED), failing.state)
    }

    @Test
    fun urlWithCredentialsQueryOrFragmentIsRefused() {
        val vm = vm()
        for (url in listOf("https://user:token@hermes.example/", "https://hermes.example/?key=sk-1", "https://hermes.example/#sk-1", "https://hermes.example/?")) {
            vm.submit(url, "k")
            assertEquals(url, ConnectState.Failed(Field.URL, Reason.URL_EXTRA_PARTS), vm.state)
        }
        assertTrue(saved.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyThatCannotBeAHeaderIsRefused() {
        val vm = vm()
        for (key in listOf("sk-1\nX-Evil: 1", "klucz-\u017c")) {
            vm.submit(server.url("/").toString(), key)
            assertEquals(ConnectState.Failed(Field.KEY, Reason.KEY_INVALID), vm.state)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cacheLookupFailureIsAFieldError() {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val vm = ConnectViewModel(null, willClearCache = { throw IllegalStateException("database closed") }, save = { saved += it })
        vm.submit(server.url("/").toString(), "k")
        assertEquals(ConnectState.Failed(Field.KEY, Reason.SAVE_FAILED), vm.state)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun leavingTheScreenCancelsTheProbeAndForgetsTheKey() = runTest {
        val response = holding.hold(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { vm() } })[ConnectViewModel::class]
        vm.onUrlChange(server.url("/").toString())
        vm.onKeyChange("sk-secret")
        val probe = vm.connect()!!
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        store.clear()
        probe.join()
        response.countDown()
        withContext(Dispatchers.IO) { Thread.sleep(800) } // past the moment the response arrives

        assertTrue(saved.isEmpty())
        assertEquals("", vm.apiKey.text.toString())
    }

    @Test
    fun backCancelsTheProbeBeforeLeaving() = runTest {
        val response = holding.hold(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val vm = vm()
        vm.onUrlChange(server.url("/").toString())
        vm.onKeyChange("sk-1")
        vm.connect()
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }

        assertTrue(vm.cancel()) // synchronous: nothing to wait for before navigating
        assertEquals(ConnectState.Idle, vm.state)
        response.countDown()
        withContext(Dispatchers.IO) { Thread.sleep(800) } // past the moment the response arrives
        assertTrue(saved.isEmpty())
        assertEquals(ConnectState.Idle, vm.state) // a late result is ignored
    }

    @Test
    fun backIsIgnoredWhileSaving() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val release = CompletableDeferred<Unit>()
        val vm = ConnectViewModel(null, willClearCache = { false }, save = {
            release.await()
            saved += it
        })
        vm.onUrlChange(server.url("/").toString())
        vm.onKeyChange("sk-1")
        val job = vm.connect()!!
        withContext(Dispatchers.IO) { while (vm.state != ConnectState.Saving) Thread.sleep(10) }

        assertFalse(vm.cancel())
        release.complete(Unit)
        job.join()
        assertEquals(ConnectState.Connected, vm.state)
        assertEquals(1, saved.size)
    }

    @Test
    fun backHolderFollowsTheCheckAndCancelsIt() = runTest {
        val response = holding.hold(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val back = ConnectBack()
        val vm = ConnectViewModel(null, willClearCache = { false }, save = { saved += it }, back = back)
        assertFalse(back.busy.value)
        vm.onUrlChange(server.url("/").toString())
        vm.onKeyChange("sk-1")
        vm.connect()

        assertTrue(back.busy.value) // set synchronously by connect()
        assertTrue(back.cancel())
        assertEquals(ConnectState.Idle, vm.state)
        assertFalse(back.busy.value)
        response.countDown()
        withContext(Dispatchers.IO) { Thread.sleep(800) } // past the moment the response arrives
        assertTrue(saved.isEmpty())
    }

    @Test
    fun backHolderRefusesWhileSaving() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val back = ConnectBack()
        val release = CompletableDeferred<Unit>()
        val vm = ConnectViewModel(null, willClearCache = { false }, save = {
            release.await()
            saved += it
        }, back = back)
        vm.onUrlChange(server.url("/").toString())
        vm.onKeyChange("sk-1")
        val job = vm.connect()!!
        withContext(Dispatchers.IO) { while (vm.state != ConnectState.Saving) Thread.sleep(10) }

        assertTrue(back.busy.value)
        assertFalse(back.cancel())
        release.complete(Unit)
        job.join()
        assertEquals(ConnectState.Connected, vm.state)
        assertEquals(1, saved.size)
        assertFalse(back.busy.value)
    }

    @Test
    fun aResultArrivingAfterCancelNeverSaves() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        val inCheck = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A step that ignores cancellation, so only the save step's own check can stop the save.
        val vm = ConnectViewModel(null, willClearCache = {
            withContext(NonCancellable) {
                inCheck.complete(Unit)
                release.await()
            }
            false
        }, save = { saved += it })
        vm.onUrlChange(server.url("/").toString())
        vm.onKeyChange("sk-1")
        val job = vm.connect()!!
        inCheck.await()

        assertTrue(vm.cancel())
        release.complete(Unit)
        job.join()
        assertTrue(saved.isEmpty())
        assertEquals(ConnectState.Idle, vm.state)
    }

    @Test
    fun interruptedEarlierSwitchShowsTheSaveError() {
        val vm = ConnectViewModel(null, willClearCache = { false }, save = {}, failedBefore = true)
        assertEquals(ConnectState.Failed(Field.KEY, Reason.SAVE_FAILED), vm.state)
    }

    @Test
    fun editingClearsError() {
        val vm = vm()
        vm.submit("nope", "k")
        vm.onUrlChange("https://hermes.example/")
        assertEquals(ConnectState.Idle, vm.state)
        assertNull(saved.firstOrNull())
    }
}
