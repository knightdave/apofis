package xyz.rycerz.apofis

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.share.SHARED_T
import xyz.rycerz.apofis.share.Share
import xyz.rycerz.apofis.share.ShareFixture
import xyz.rycerz.apofis.share.ShareIntent

private const val TMP = "apofis-tmp-1"

/**
 * A share is the third kind of intent MainActivity acts on ([shareGate]), under the same two conditions as a tap: fresh
 * and not replayed from Recents. Before Accept it changes nothing: the activity cases run the app's real, disconnected
 * container (no keystore under Robolectric); the connected cases run the chat route over a container of their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ShareGateTest : ShareFixture() {
    private val apofis get() = app as ApofisApp
    private val shareSlot get() = apofis.container.pendingShare

    private fun send(text: String = "hello") =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setClass(app, MainActivity::class.java)

    private var activity: ActivityController<MainActivity>? = null

    /** Runs [body] with the app's own container over its database file, seeded with an open temporary chat. */
    private fun withActivity(body: () -> Unit) {
        val file = Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").allowMainThreadQueries().build()
        try {
            runBlocking {
                file.sessionDao().upsert(SessionEntity(TMP, TMP, "", "Title", false, false, 1.0, null, 1, null, false, existsOnServer = true))
                file.messageDao().insertAll(listOf(MessageEntity(TMP, 0, 1, "user", null, "hello", null, null, null, null, 1.0, null)))
            }
            apofis.container.preferences.lastChat = TMP
            body()
        } finally {
            activity?.pause()?.stop()?.destroy()
            file.close()
        }
    }

    /** The share the activity offered, once its read has ended; null when it offered none. */
    private fun offered(): Share? {
        val job = apofis.container.shareJob ?: return null
        runBlocking { withTimeout(10_000) { job.join() } }
        shadowOf(Looper.getMainLooper()).idle()
        return shareSlot.value
    }

    private fun fileTables(): List<String> {
        val file = Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").allowMainThreadQueries().build()
        return try {
            file.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { names ->
                generateSequence { if (names.moveToNext()) names.getString(0) else null }.toList()
            }.flatMap { t ->
                file.query("SELECT * FROM `$t`", null).use { c ->
                    generateSequence { if (c.moveToNext()) t + (0 until c.columnCount).map { c.getString(it) } else null }.toList()
                }
            }
        } finally {
            file.close()
        }
    }

    @Test
    fun coldExternalShareChangesNothing() {
        val cold = send().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(ShareIntent.Text("hello"), shareGate(cold, fresh = true))
        assertFalse(gate(cold, true, app.packageName)) // never a tap

        withActivity {
            val before = fileTables()
            activity = Robolectric.buildActivity(MainActivity::class.java, cold).setup()
            val share = checkNotNull(offered())
            assertEquals("hello", share.text)
            assertNull(activity!!.get().intent.action) // handed over once, as a tap is
            assertEquals(TMP, apofis.container.preferences.lastChat)
            assertEquals(before, fileTables())
            apofis.container.cancelShare(share.version)
            assertNull(shareSlot.value)
            assertEquals(before, fileTables())
        }

        // Connected: the temporary chat is selected under the sheet, no request beyond a plain start's reads, no row changed.
        newContainer()
        try {
            connect(SHARED_T) {
                session(SHARED_T, onServer = true)
                message(SHARED_T)
            }
            val before = tables()
            container.offerShare(checkNotNull(shareGate(cold, fresh = true)))
            runBlocking { container.pendingShare.await { it != null } }
            compose()
            waitSelected { it == SHARED_T }
            waitVisible(str(R.string.share_deletes_temporary))
            rule.waitForIdle()
            assertEquals(emptyList<String>(), writes())
            assertEquals(before, tables())

            rule.onNodeWithText(str(R.string.share_cancel)).performClick()
            waitGone(str(R.string.share_title))
            assertEquals(SHARED_T, drawer().selected.value)
            assertEquals(emptyList<String>(), writes())
            assertEquals(before, tables())
        } finally {
            finish()
        }
    }

    @Test
    fun shareWhileTemporaryReplyRunsChangesNothingBeforeAccept() = inRoute(SHARED_T, {
        session(SHARED_T, onServer = true)
        message(SHARED_T)
        run(SHARED_T, "run-1")
    }) {
        waitSelected { it == SHARED_T }
        rule.waitForIdle()
        val before = tables()
        container.offerShare(checkNotNull(shareGate(send("warm"), fresh = true))) // as onNewIntent hands it over
        waitVisible(str(R.string.share_deletes_temporary))
        assertTrue(visible("warm"))
        rule.waitForIdle()
        assertEquals(SHARED_T, drawer().selected.value)
        assertEquals(emptyList<String>(), stops())
        assertEquals(emptyList<String>(), deletes())
        assertEquals(emptyList<String>(), writes())
        assertEquals(before, tables())
        assertEquals(1, runBlocking { db.activeRunDao().allForSession(SHARED_T) }.size)
    }

    @Test
    fun recreationDoesNotRepeat() {
        assertNull(shareGate(send(), fresh = false))
        withActivity {
            // A rotation or a restore delivers the old share again, with saved state.
            activity = Robolectric.buildActivity(MainActivity::class.java, send()).setup(Bundle())
            assertNull(offered())
            activity!!.newIntent(send("later")) // control: a fresh share to the same activity is taken
            assertEquals("later", offered()?.text)
        }
    }

    @Test
    fun launchedFromHistoryIgnored() {
        val replayed = send().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(shareGate(replayed, fresh = true))
        withActivity {
            activity = Robolectric.buildActivity(MainActivity::class.java, replayed).setup()
            assertNull(offered())
            activity!!.newIntent(replayed)
            assertNull(offered())
            activity!!.newIntent(send("fresh")) // control
            assertNotNull(offered())
            assertEquals("fresh", shareSlot.value?.text)
        }
    }
}
