package xyz.rycerz.apofis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The one serialized path for lifecycle work, and the only caller of [resume]. Events run one at a time in the order
 * they arrive, so a slow [resume] always finishes before the [pause] of a stop that follows it, and a resume that runs
 * after a stop does nothing. [resume] gets a check to repeat before it opens anything. Whether a stream may run is the
 * follower's own gate; this class only orders the calls that open and close it.
 */
internal class Foreground(
    scope: CoroutineScope,
    private val resume: suspend (stillStarted: () -> Boolean) -> Unit,
    private val pause: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    @Volatile
    var started = false
        private set

    // true: resume if started; false: pause; a Barrier: completed once everything before it has run
    private val events = Channel<Any>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    when {
                        event is Barrier -> event.done.complete(Unit)
                        event == false -> pause()
                        started -> resume { started }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    fun start() {
        started = true
        events.trySend(true)
    }

    fun stop() {
        started = false
        events.trySend(false)
    }

    /** Resume again if started, for example after the graph was replaced. */
    fun refresh() {
        events.trySend(true)
    }

    /** Returns once every event sent before this call has run; for tests. */
    suspend fun idle() = Barrier().also { events.trySend(it) }.done.await()

    private class Barrier {
        val done = CompletableDeferred<Unit>()
    }
}
