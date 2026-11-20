package xyz.rycerz.apofis.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val composer = Composer(scope)

    @After
    fun tearDown() = scope.cancel()

    private fun fail(key: String, text: String) = runBlocking {
        composer.edit(key, text)
        withTimeout(10_000) { composer.send(key) { throw IllegalStateException("not stored") }!!.join() }
    }

    @Test
    fun aFailureNeverTouchesTheDraftAndFailuresAreJoined() {
        fail("a", "first")
        composer.edit("a", "typing")
        fail("a", "typing") // the draft "typing" is sent and fails too
        assertEquals("", composer.current("a").draft)
        assertEquals(UnsentText("first\n\ntyping", ComposeError.NOT_STORED), composer.current("a").unsent)
    }

    @Test
    fun putBackGoesBeforeTheDraftAndReplacesABlankOne() {
        fail("a", "first")
        composer.edit("a", "second")
        composer.putBack("a")
        assertEquals(ComposerState(draft = "first\nsecond"), composer.current("a"))

        fail("b", "only")
        composer.edit("b", "  ")
        composer.putBack("b")
        assertEquals(ComposerState(draft = "only"), composer.current("b"))
    }

    @Test
    fun discardRemovesOnlyTheTextTheUserSaw() {
        fail("a", "first")
        fail("a", "second")
        composer.discard("a", "first") // the dialog showed only the first failure
        assertEquals("first\n\nsecond", composer.current("a").unsent?.text)
        composer.discard("a", "first\n\nsecond")
        assertNull(composer.current("a").unsent)
        assertTrue(composer.keys.isEmpty())
    }

    @Test
    fun deletingManyChatsLeavesNoEntries() {
        repeat(200) { i ->
            composer.edit("k$i", "draft $i")
            composer.remove("k$i")
        }
        assertTrue(composer.keys.isEmpty())
    }

    @Test
    fun aDeletedChatIsRememberedOnlyWhileItsSendIsInFlight() = runBlocking {
        composer.edit("a", "hello")
        val release = CompletableDeferred<Unit>()
        val send = composer.send("a") { release.await(); throw IllegalStateException("chat gone") }!!
        composer.remove("a")
        composer.edit("a", "late edit") // nothing may recreate a deleted chat
        assertEquals(setOf("a"), composer.keys)
        release.complete(Unit)
        withTimeout(10_000) { send.join() }
        assertTrue(composer.keys.isEmpty())
        assertEquals(ComposerState(), composer.current("a"))
    }
}
