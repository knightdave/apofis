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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val composer = Composer(scope)

    @After
    fun tearDown() = scope.cancel()

    /** A user edit made on the current state. */
    private fun type(key: String, text: String) = composer.edit(key, text, composer.current(key).revision)

    private fun fail(key: String, text: String) = runBlocking {
        type(key, text)
        withTimeout(10_000) { composer.send(key) { throw IllegalStateException("not stored") }!!.join() }
    }

    @Test
    fun aFailureNeverTouchesTheDraftAndFailuresAreJoined() {
        fail("a", "first")
        type("a", "typing")
        fail("a", "typing") // the draft "typing" is sent and fails too
        assertEquals("", composer.current("a").draft)
        assertEquals("first\n\ntyping", composer.current("a").unsent?.text)
        assertEquals(ComposeError.NOT_STORED, composer.current("a").unsent?.error)
    }

    @Test
    fun putBackGoesBeforeTheDraftAndReplacesABlankOne() {
        fail("a", "first")
        type("a", "second")
        composer.putBack("a")
        assertEquals("first\nsecond", composer.current("a").draft)
        assertNull(composer.current("a").unsent)

        fail("b", "only")
        type("b", "  ")
        composer.putBack("b")
        assertEquals("only", composer.current("b").draft)
    }

    @Test
    fun anEditFromBeforePutBackIsDropped() {
        fail("a", "first")
        type("a", "second")
        val shown = composer.current("a").revision // the frame the text field drew
        composer.putBack("a")
        composer.edit("a", "second!", shown) // a keystroke from that frame, delivered after Put back
        assertEquals("first\nsecond", composer.current("a").draft)

        composer.edit("a", "first\nsecond!", composer.current("a").revision) // typed on the redrawn field
        assertEquals("first\nsecond!", composer.current("a").draft)
    }

    @Test
    fun anEditFromBeforeASendIsDropped() = runBlocking {
        type("a", "hello")
        val shown = composer.current("a").revision
        val release = CompletableDeferred<Unit>()
        val send = composer.send("a") { release.await() }!!
        composer.edit("a", "hello!", shown) // the field still showed the sent text
        assertEquals("", composer.current("a").draft)
        release.complete(Unit)
        withTimeout(10_000) { send.join() }
        assertEquals("", composer.current("a").draft)
    }

    @Test
    fun discardNeedsTheEntryTheUserSaw() {
        fail("a", "same")
        val first = composer.current("a").unsent!!.id
        composer.discard("a", first)
        assertNull(composer.current("a").unsent)

        fail("a", "same") // a new entry with the same text
        assertNotEquals(first, composer.current("a").unsent!!.id)
        composer.discard("a", first) // the old confirmation counts for nothing
        assertEquals("same", composer.current("a").unsent?.text)

        val joined = composer.current("a").unsent!!.id
        fail("a", "more") // joining makes a new entry too
        composer.discard("a", joined)
        assertEquals("same\n\nmore", composer.current("a").unsent?.text)
    }

    @Test
    fun deletingManyChatsLeavesNoEntries() {
        repeat(200) { i ->
            type("k$i", "draft $i")
            composer.remove("k$i")
        }
        assertTrue(composer.keys.isEmpty())
    }

    @Test
    fun aDeletedChatIsRememberedOnlyWhileItsSendIsInFlight() = runBlocking {
        type("a", "hello")
        val release = CompletableDeferred<Unit>()
        val send = composer.send("a") { release.await(); throw IllegalStateException("chat gone") }!!
        composer.remove("a")
        type("a", "late edit") // nothing may recreate a deleted chat
        assertEquals(setOf("a"), composer.keys)
        release.complete(Unit)
        withTimeout(10_000) { send.join() }
        assertTrue(composer.keys.isEmpty())
        assertEquals("", composer.current("a").draft)
    }

    @Test
    fun clearEmptiesEverythingAndEndsSendsInFlight() = runBlocking {
        fail("a", "unsent")
        type("b", "draft")
        type("c", "sending")
        val never = CompletableDeferred<Unit>()
        val send = composer.send("c") { never.await(); throw IllegalStateException("not stored") }!!

        composer.clear()

        withTimeout(10_000) { send.join() }
        assertTrue(composer.keys.isEmpty())
        assertFalse(composer.job.children.any { it.isActive })
        assertEquals("", composer.current("c").draft)
        assertNull(composer.current("c").unsent)
    }
}
