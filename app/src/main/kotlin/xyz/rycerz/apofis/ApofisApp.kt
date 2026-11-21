package xyz.rycerz.apofis

import android.app.Application
import android.util.Log
import xyz.rycerz.apofis.push.PublishWorker
import xyz.rycerz.apofis.push.PushRegistrar
import xyz.rycerz.apofis.push.PushSink
import xyz.rycerz.apofis.push.PushSinkProvider
import xyz.rycerz.apofis.push.TestPush
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.SendQueueProvider

class ApofisApp : Application(), SendQueueProvider, PushSinkProvider {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // A push event whose work was never enqueued, or a revocation left by a process death.
        runCatching { PublishWorker.recover(this) }.onFailure { Log.w("Apofis", "Push recovery failed: ${it.javaClass.name}") }
    }

    override val sendQueue: SendQueue? get() = container.connected()?.sendQueue
    override val pushSink: PushSink? get() = null // Task 4: the wake
    override val testPush: TestPush get() = container.testPush
    override val pushRegistrar: PushRegistrar get() = container.push
}
