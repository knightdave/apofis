package xyz.rycerz.apofis.ui.connect

import androidx.activity.OnBackPressedCallback

/**
 * System Back while a connect check or save runs, added by the activity in onCreate so it works before any frame.
 * The [ConnectViewModel] it is given to enables it synchronously as it enters Testing or Saving. Back then cancels the
 * check and calls [leave]; while saving it is consumed and does nothing.
 */
class ConnectBack(private val leave: () -> Unit) : OnBackPressedCallback(false) {
    internal var target: ConnectViewModel? = null

    override fun handleOnBackPressed() {
        if (target?.cancel() == true) leave()
    }
}
