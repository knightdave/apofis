package xyz.rycerz.apofis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The one serialized path for lifecycle work. Events run one at a time in the order they arrive, so a slow [resume]
 * always finishes before the [pause] of a stop that follows it, and a resume that runs after a stop does nothing.
 */
internal class Foreground(
    scope: CoroutineScope,
    private val resume: suspend () -> Unit,
    private val pause: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    @Volatile
    var started = false
        private set

    private val events = Channel<Boolean>(Channel.UNLIMITED) // true: resume if started; false: pause

    init {
        scope.launch {
            for (resumeEvent in events) {
                try {
                    if (!resumeEvent) pause() else if (started) resume()
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
}
