package xyz.rycerz.apofis.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.chat.ChatRows
import xyz.rycerz.apofis.ui.chat.TextBlock
import xyz.rycerz.apofis.ui.chat.TextRow
import xyz.rycerz.apofis.ui.shot

/** Markdown rows alone: the production rows of one reply, rendered by [TextBlock] on the page color. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class MarkdownScreenshotTest {
    private fun md(name: String, text: String, mode: ThemeMode = ThemeMode.LIGHT) {
        val rows = ChatRows(ZoneOffset.UTC).rows(listOf(AssistantReply("r1", emptyList(), text, FIXED_NOW))).filterIsInstance<TextRow>()
        shot(name, mode) {
            Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                for (row in rows) TextBlock(row)
            }
        }
    }

    @Test
    fun md_headings() = md(
        "md_headings",
        "# Heading one\n## Heading two\n### Heading three\n#### Heading four\n##### Heading five\n###### Heading six\n\nBody text under the headings.",
    )

    @Test
    fun md_lists_tasks() = md(
        "md_lists_tasks",
        "- First bullet\n- Second bullet\n  - Nested bullet\n    - Deeper bullet\n\n" +
            "9. Ninth\n10. Tenth\n11. Eleventh\n\n" +
            "- [ ] Open task\n- [x] Finished task\n- [ ] Another open one\n\n" +
            "1. A loose item\n\n   with a second paragraph.\n\n2. The next loose item",
    )

    @Test
    fun md_quote_rule() = md(
        "md_quote_rule",
        "Before the quote.\n\n> A quoted line that is long enough to wrap onto a second line here.\n> > And a nested quote.\n\n---\n\nAfter the rule.",
    )

    @Test
    fun md_inline() = md(
        "md_inline",
        "Some **strong**, *emphasis*, ~~struck~~ and `inline code` in one line, then a [link to a site](https://example.com) " +
            "and code that wraps: `a fairly long inline code span that crosses the line end` done.\n\n" +
            "Raw <b>HTML</b> stays literal, [unsafe](javascript:alert(1)) is plain.",
    )

    private val table = "Totals:\n\n| Item | Qty | Price |\n|:--|:-:|--:|\n| Apples | 3 | 1.20 |\n| A much longer item name | 12 | 10.00 |\n| **Total** | 15 | 11.20 |"

    @Test
    fun md_table_light() = md("md_table_light", table)

    @Test
    fun md_table_dark() = md("md_table_dark", table, ThemeMode.DARK)

    @Test
    fun md_image_placeholder() = md(
        "md_image_placeholder",
        "A picture:\n\n![A diagram of the system](https://example.com/diagram.png)\n\n![](https://example.com/no-alt.png)",
    )
}
