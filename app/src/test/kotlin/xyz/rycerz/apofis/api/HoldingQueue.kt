package xyz.rycerz.apofis.api

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest

/**
 * A [QueueDispatcher] whose [hold] responses are served only once the test opens their gate, so a test can act while
 * a request is in flight without racing a real-time delay.
 *
 * Contract: the owning test ends its server with [closeAndVerify], never with `server.close()` alone. A gate that
 * timed out (10 s; recorded before its response is served) or was never opened then fails the test.
 */
internal class HoldingQueue : QueueDispatcher() {
    private val gates = Collections.synchronizedMap(IdentityHashMap<MockResponse, CountDownLatch>())
    private val timedOut = CopyOnWriteArrayList<String>()

    fun hold(response: MockResponse): CountDownLatch = CountDownLatch(1).also {
        gates[response] = it
        enqueue(response)
    }

    override fun dispatch(request: RecordedRequest): MockResponse = super.dispatch(request).also {
        val gate = gates[it]
        if (gate != null && !gate.await(10, TimeUnit.SECONDS)) timedOut += request.target
    }

    /**
     * Notes the gates never opened and opens them, closes [server] (which waits, bounded, until no dispatch is in
     * progress, so every timeout is recorded by then), and only then fails if any gate timed out or was never opened.
     */
    fun closeAndVerify(server: MockWebServer) {
        val unopened = synchronized(gates) { gates.values.count { it.count > 0 }.also { gates.values.forEach { g -> g.countDown() } } }
        server.close()
        if (timedOut.isNotEmpty()) throw AssertionError("held responses served only after the 10 s timeout: $timedOut")
        if (unopened > 0) throw AssertionError("$unopened held responses were never released")
    }
}
