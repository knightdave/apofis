package xyz.rycerz.apofis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The one serialized path for resumes, and the only caller of [resume]. [stop] calls [pause] itself before it returns,
 * so the follower's gate is closed by then even while a slow resume is in flight. A resume checks [started] before it
 * opens anything ([resume] gets that check to repeat) and again once it returns, and pauses again if a stop came in
 * meanwhile. Whether a stream may run is the follower's own gate; this class only orders the calls that open and close it.
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

    // true: resume if started; a Barrier: completed once everything before it has run
    private val events = Channel<Any>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    when {
                        event is Barrier -> event.done.complete(Unit)
                        started -> try {
                            resume { started }
                        } finally {
                            if (!started) pause() // a stop during the resume may have run before the gate opened
                        }
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

    /** Called on the main thread; returns with the gate closed. */
    fun stop() {
        started = false
        try {
            pause()
        } catch (e: Exception) {
            onError(e)
        }
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
