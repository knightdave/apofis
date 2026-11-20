package xyz.rycerz.apofis.ui.theme

import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReduceMotionTest {
    @get:Rule
    val rule = createComposeRule()

    /** Changes the setting, runs the observer posted to the main looper, then draws one frame. */
    private fun change(block: () -> Unit) {
        rule.runOnUiThread(block)
        shadowOf(Looper.getMainLooper()).idle()
        rule.runOnUiThread { Snapshot.sendApplyNotifications() } // the observer's state write reaches the recomposer
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun followsRemoveAnimationsWithoutRecreatingTheComposition() {
        rule.mainClock.autoAdvance = false
        val seen = mutableListOf<Boolean>()
        lateinit var set: (Float) -> Unit
        rule.setContent {
            val resolver = LocalContext.current.contentResolver
            set = { scale ->
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
                resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
            }
            seen += systemReduceMotion()
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(false, seen.last())

        change { set(0f) }
        assertEquals(true, seen.last())

        change { set(1f) }
        assertEquals(false, seen.last()) // one composition, both transitions
    }
}
