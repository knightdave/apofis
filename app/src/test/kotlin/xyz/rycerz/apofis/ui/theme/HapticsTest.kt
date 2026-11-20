package xyz.rycerz.apofis.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.drawer.ChatRow
import xyz.rycerz.apofis.ui.drawer.DrawerRow

@RunWith(RobolectricTestRunner::class)
class HapticsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun mapsToConstantsAndRespectsPreference() {
        val calls = mutableListOf<Int>()
        var enabled = true
        val haptics = ViewHaptics({ calls += it; true }, { enabled })
        haptics.light()
        haptics.selection()
        assertEquals(listOf(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.CLOCK_TICK), calls)
        enabled = false
        haptics.light()
        haptics.selection()
        assertEquals(2, calls.size)
        enabled = true
        haptics.selection()
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, calls.last())
        assertEquals(3, calls.size)
    }

    @Test
    fun drawerLongPressFiresOnce() {
        val calls = mutableListOf<Int>()
        val builtIn = mutableListOf<HapticFeedbackType>()
        var enabled = true
        var longPresses = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(
                    LocalHaptics provides ViewHaptics({ calls += it; true }, { enabled }),
                    LocalHapticFeedback provides object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                            builtIn += hapticFeedbackType
                        }
                    },
                ) {
                    ChatRow(DrawerRow.Chat("k", "Garden beds", selected = false, running = false, needsInput = false), {}, { longPresses++ }, emptyList())
                }
            }
        }
        rule.onNodeWithText("Garden beds").performTouchInput { longClick() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf(HapticFeedbackConstants.VIRTUAL_KEY), calls)
        assertEquals(1, longPresses)
        assertEquals(emptyList<HapticFeedbackType>(), builtIn) // Compose's own long-press haptic is off

        enabled = false
        rule.onNodeWithText("Garden beds").performTouchInput { longClick() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, calls.size)
        assertEquals(2, longPresses)
        assertEquals(emptyList<HapticFeedbackType>(), builtIn)
    }
}
