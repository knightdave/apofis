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

/** Code blocks alone: the production rows of one reply, rendered by [TextBlock] on the page color. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class CodeScreenshotTest {
    private fun code(name: String, text: String, mode: ThemeMode = ThemeMode.LIGHT, live: Boolean = false) {
        val rows = ChatRows(ZoneOffset.UTC).rows(listOf(AssistantReply("r1", emptyList(), text, FIXED_NOW, live = live))).filterIsInstance<TextRow>()
        shot(name, mode) {
            Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                for (row in rows) TextBlock(row)
            }
        }
    }

    private val kotlin = "```kotlin\n" +
        "/* Retries a request with backoff. */\n" +
        "suspend fun retry(times: Int = 3, block: suspend () -> String): String {\n" +
        "    var delayMs = 250L // first wait\n" +
        "    repeat(times - 1) {\n" +
        "        try {\n" +
        "            return block()\n" +
        "        } catch (e: IOException) {\n" +
        "            println(\"retry in \$delayMs ms\")\n" +
        "        }\n" +
        "        delay(delayMs); delayMs *= 2\n" +
        "    }\n" +
        "    return block() // last try: 0x1F, 3.14\n" +
        "}\n```"

    @Test
    fun code_block_kotlin_light() = code("code_block_kotlin_light", kotlin)

    @Test
    fun code_block_kotlin_dark() = code("code_block_kotlin_dark", kotlin, ThemeMode.DARK)

    @Test
    fun code_block_no_language() = code("code_block_no_language", "```\n" + "0123456789abcdefghij".repeat(10) + "\n```")

    @Test
    fun code_block_streaming_open() = code("code_block_streaming_open", "Here it is:\n\n```py\ndef main():\n    print('hi')  # greet\n    return 42", live = true)
}
