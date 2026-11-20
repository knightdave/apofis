package xyz.rycerz.apofis.ui.theme

import android.view.HapticFeedbackConstants

/** App feedback events; one per gesture, always through [LocalHaptics]. */
interface Haptics {
    fun light()
    fun selection()
}

/** View feedback with ordinary flags (no VIBRATE permission), gated by the user's switch at each call. */
internal class ViewHaptics(private val perform: (Int) -> Boolean, private val enabled: () -> Boolean) : Haptics {
    override fun light() {
        if (enabled()) perform(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun selection() {
        if (enabled()) perform(HapticFeedbackConstants.CLOCK_TICK)
    }
}
