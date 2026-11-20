package xyz.rycerz.apofis

import android.app.Application
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.SendQueueProvider

class ApofisApp : Application(), SendQueueProvider {
    val container by lazy { AppContainer(this) }

    override val sendQueue: SendQueue? get() = container.connected()?.sendQueue
}
