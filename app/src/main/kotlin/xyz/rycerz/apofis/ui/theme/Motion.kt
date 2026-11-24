package xyz.rycerz.apofis.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

val EaseOutCubic: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/** Durations and periods in milliseconds. */
object Motion {
    const val PRESS_FADE = 180
    const val WASH = 200
    const val GLYPH_SWAP = 200
    const val TITLE_FADE = 200
    const val ACTION_ROW = 220
    const val CHEVRON = 220
    const val SWITCH = 220
    const val DRAWER = 250
    const val SCROLL_BUTTON_SLIDE = 280
    const val TIMELINE_SIZE = 300
    const val COMPOSER_EXPAND = 320
    const val GROUP_HEADER = 180
    const val SCROLL_BUTTON_HIDE = 2000
    const val FOLLOW_HOLD = 450
    const val DOTS_PERIOD = 1100
    const val PULSE_PERIOD = 1800
    const val SPINNER_PERIOD = 1000
    const val SMOOTH_TICK = 50
    const val VIEWER_IN = 300
    const val VIEWER_OUT = 240
}
