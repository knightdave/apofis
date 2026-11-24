package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.BuildConfig
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.ui.theme.pulseAlpha
import xyz.rycerz.apofis.ui.theme.rememberTimeMs

private val WIDTHS = listOf(0.62f, 0.48f, 0.70f, 0.55f)

/**
 * Four placeholder bars while a chat's history loads for the first time (UI map: the loading skeleton), start and end
 * aligned in turn, at the top of the space [modifier] leaves. The group pulses 0.45 to 1 over [Motion.PULSE_PERIOD], read
 * in its graphics layer so a frame recomposes nothing; static at full alpha under reduced motion. One accessibility node
 * ("Loading messages"); no pointer input, so a drag that starts on it reaches the drawer's drag area under it.
 */
@Composable
fun ChatSkeleton(modifier: Modifier = Modifier) {
    val reduce = ApofisTheme.reduceMotion
    val time = rememberTimeMs(Motion.PULSE_PERIOD)
    val fill = ApofisTheme.colors.skeleton
    val shape = MaterialTheme.shapes.large
    val label = stringResource(R.string.chat_loading)
    if (BuildConfig.DEBUG) SideEffect { ChromeProbe.skeletonCompositions++ }
    Column(
        modifier
            .wrapContentHeight(Alignment.Top)
            .clearAndSetSemantics { contentDescription = label }
            .graphicsLayer { alpha = if (reduce) 1f else pulseAlpha(time.value, Motion.PULSE_PERIOD, 0.45f, 1f) },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        WIDTHS.forEachIndexed { i, width ->
            Box(Modifier.align(if (i % 2 == 0) Alignment.Start else Alignment.End).fillMaxWidth(width).height(44.dp).background(fill, shape))
        }
    }
}
