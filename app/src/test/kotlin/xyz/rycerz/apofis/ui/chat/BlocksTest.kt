package xyz.rycerz.apofis.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class BlocksTest {
    private fun split(text: String) = ArrayList<Block>().also { splitBlocks(text, 0, it) }
        .map { Triple(text.substring(it.start, it.end), it.code, it.lang) }

    @Test
    fun paragraphsAndFencedCode() {
        val text = "First line\nsame paragraph\n\n```kotlin\nval x = 1\n\nval y = 2\n```\nAfter code"
        assertEquals(
            listOf(
                Triple("First line\nsame paragraph", false, ""),
                Triple("val x = 1\n\nval y = 2", true, "kotlin"),
                Triple("After code", false, ""),
            ),
            split(text),
        )
    }

    @Test
    fun unclosedFenceRunsToTheEnd() {
        assertEquals(listOf(Triple("Intro", false, ""), Triple("ls -la\n", true, "")), split("Intro\n```\nls -la\n"))
    }

    @Test
    fun streamingMatchesAFullSplit() {
        val full = "One\n\n```sh\necho hi\n```\n\nTwo words here\n\nThree"
        val live = LiveBlocks()
        for (end in 1..full.length) live.update(full.substring(0, end))
        assertEquals(split(full).map { it.first }, live.blocks.indices.map { live.text(it) })
    }
}
