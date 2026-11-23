package xyz.rycerz.apofis.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult

/**
 * Every composed text (all windows) that is cut: taller than its box, ending in an ellipsis, or with a line wider than
 * its box. ([TextLayoutResult.didOverflowWidth] compares with the width the paragraph was laid out in, not its lines.)
 */
fun ComposeContentTestRule.clippedTexts(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        val r = results.singleOrNull() ?: return@mapNotNull null
        val widest = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) - r.getLineLeft(it) } ?: 0f
        val cut = r.didOverflowHeight || r.isLineEllipsized(r.lineCount - 1) || widest > r.size.width + 1
        r.layoutInput.text.text.takeIf { cut }
    }
