package xyz.rycerz.apofis.api

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest

/**
 * A [QueueDispatcher] whose [hold] responses are served only once the test opens their gate, so a test can act while
 * a request is in flight without racing a real-time delay. The owning test calls [verify] at its end: a gate that
 * timed out (10 s) or was never opened fails it. [close] (server close) opens any gate left, so no thread hangs.
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

    fun verify() {
        if (timedOut.isNotEmpty()) throw AssertionError("held responses served only after the 10 s timeout: $timedOut")
        val closed = synchronized(gates) { gates.values.count { it.count > 0 } }
        if (closed > 0) throw AssertionError("$closed held responses were never released")
    }

    override fun close() {
        synchronized(gates) { gates.values.forEach { it.countDown() } }
        super.close()
    }
}
