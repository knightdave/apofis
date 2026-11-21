package xyz.rycerz.apofis

import android.app.Application
import xyz.rycerz.apofis.notify.ensureChannels
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.SendQueueProvider

class ApofisApp : Application(), SendQueueProvider {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this) // also renames them after a language change
    }

    override val sendQueue: SendQueue? get() = container.connected()?.sendQueue
}
