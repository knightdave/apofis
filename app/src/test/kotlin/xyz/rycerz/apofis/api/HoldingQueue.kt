package xyz.rycerz.apofis.api

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest

/**
 * A [QueueDispatcher] whose [hold] responses are served only once the test opens their gate, so a test can act while
 * a request is in flight without racing a real-time delay. A gate never opened is opened by [close] (server close).
 */
internal class HoldingQueue : QueueDispatcher() {
    private val gates = Collections.synchronizedMap(IdentityHashMap<MockResponse, CountDownLatch>())

    fun hold(response: MockResponse): CountDownLatch = CountDownLatch(1).also {
        gates[response] = it
        enqueue(response)
    }

    override fun dispatch(request: RecordedRequest): MockResponse =
        super.dispatch(request).also { gates[it]?.await(10, TimeUnit.SECONDS) }

    override fun close() {
        synchronized(gates) { gates.values.forEach { it.countDown() } }
        super.close()
    }
}
