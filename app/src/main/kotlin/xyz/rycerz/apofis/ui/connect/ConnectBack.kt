package xyz.rycerz.apofis.ui.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owned by the container, so it outlives rotation and holds nothing of an Activity. The current [ConnectViewModel]
 * reports here synchronously whether a check or save runs ([busy]); each Activity enables its own Back callback from
 * [busy] and calls [cancel] on Back.
 */
class ConnectBack {
    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    // Main thread only.
    internal var target: ConnectViewModel? = null
        private set

    /** Cancels a running check and returns true; false while saving (Back is consumed) or with nothing to cancel. */
    fun cancel(): Boolean = target?.cancel() == true

    internal fun attach(vm: ConnectViewModel) {
        target = vm
        busyFlow.value = false
    }

    internal fun update(vm: ConnectViewModel, busy: Boolean) {
        if (target === vm) busyFlow.value = busy
    }

    internal fun detach(vm: ConnectViewModel) {
        if (target !== vm) return
        target = null
        busyFlow.value = false
    }
}
