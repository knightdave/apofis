package xyz.rycerz.apofis

import android.app.Application
import android.util.Log
import xyz.rycerz.apofis.notify.ensureChannels
import xyz.rycerz.apofis.push.PushRegistrar
import xyz.rycerz.apofis.push.PushSink
import xyz.rycerz.apofis.push.PushSinkProvider
import xyz.rycerz.apofis.push.TestPush
import xyz.rycerz.apofis.push.Wake
import xyz.rycerz.apofis.push.WakeProvider
import xyz.rycerz.apofis.push.WakeTarget
import xyz.rycerz.apofis.push.recoverPush
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.SendQueueProvider

class ApofisApp : Application(), SendQueueProvider, PushSinkProvider, WakeProvider {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this) // also renames them after a language change
        // Push work and wake demand left by the last process.
        // Off the main thread: it reads preferences, and with push Off and nothing persisted it only does that.
        Thread { runCatching { recoverPush(this, pushSink) }.onFailure { Log.w("Apofis", "Push recovery failed: ${it.javaClass.name}") } }.start()
    }

    override val sendQueue: SendQueue? get() = container.connected()?.sendQueue
    override val pushSink: PushSink get() = Wake
    override val wakeTarget: WakeTarget? get() = container.connected()?.let { WakeTarget(it, it.generation, container.notifier, System::currentTimeMillis) }
    override val testPush: TestPush get() = container.testPush
    override val pushRegistrar: PushRegistrar get() = container.push
}
