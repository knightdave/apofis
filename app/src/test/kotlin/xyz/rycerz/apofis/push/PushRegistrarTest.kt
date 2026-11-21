package xyz.rycerz.apofis.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import xyz.rycerz.apofis.api.ApiError

private const val INSTALL = "0b0e9c1e-2f4a-4c55-9d1e-7a3b5c6d7e8f"
private fun id(seq: Int) = recordId(INSTALL, seq)
private val TITLE = Regex("""apofis-push-$INSTALL-\d+ op-[0-9a-f]{16}""")

@RunWith(RobolectricTestRunner::class)
class PushRegistrarTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Everything that survives a process death (prefs, servers, the connector); registrars are rebuilt from it. */
    private inner class World(prefsName: String = PUSH_PREFS, install: String? = INSTALL) {
        val log = EventLog()
        val a = FakeServer("https://a.example/")
        val b = FakeServer("https://b.example/")
        var apiA = FakeRecordApi(a, log)
        val apiB = FakeRecordApi(b, log)
        var current: RecordApi? = apiA
        var connection = 0L
        var now = 1_000_000_000L
        val port = FakePort(log)
        val commits = mutableListOf<Set<String>>()
        val prefs = HookedPrefs(app.getSharedPreferences(prefsName, 0).also { p ->
            p.edit().clear().apply { if (install != null) putString(Keys.INSTALL_ID, install) }.commit()
        }) { log.step("commit ${it.sorted()}"); commits += it; true }

        fun TestScope.registrar(policy: PushPolicy = TestPolicy()) =
            PushRegistrar(app, prefs, { current }, { connection }, { now }, backgroundScope, policy, port)

        /** What PushReceiver commits for a NEW_ENDPOINT of the current instance. */
        fun endpointFor(r: PushRegistrar, url: String = VALID, instance: String = r.currentInstance()) {
            assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, url).putString(Keys.ENDPOINT_NEW_INSTANCE, instance).commit())
        }

        fun debt() = prefs.getString(Keys.DEBT, null).orEmpty()
        fun ours(s: FakeServer) = s.rows.keys.filter { it.startsWith("apofis-push-") }.sorted()

        /** Debt entries for server B that A's connection can never drain. */
        fun fillDebtOfB(n: Int) {
            val entries = (1..n).joinToString(",") { """{"id":"apofis-push-other-$it","title":"t$it"}""" }
            assertTrue(prefs.edit().putString(Keys.DEBT, """{"${b.url}":[$entries]}""").commit())
        }
    }

    private suspend fun World.turnedOn(r: PushRegistrar, url: String = VALID) {
        r.enableNow()
        endpointFor(r, url)
        r.publishPending()
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    @Test
    fun enableUsesNtfyOnly() = runTest {
        with(World()) {
            port.installed.clear()
            port.installed += listOf("com.example.other", NTFY_PACKAGE)
            val r = registrar()
            r.enableNow()
            assertEquals(listOf("save $NTFY_PACKAGE", "register g1"), port.calls)
            assertEquals(PushState.Registering, r.ui.value.state)
        }
        with(World()) {
            port.installed.clear()
            port.installed += "com.example.other"
            val r = registrar()
            r.enableNow()
            assertEquals(emptyList<String>(), port.calls)
            assertEquals(PushState.NoDistributor, r.ui.value.state)
        }
    }

    @Test
    fun firstPublish() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            assertEquals(listOf("create ${id(1)}", "hide ${id(1)}"), apiA.calls)
            assertEquals(listOf(id(1) to """{"v":1,"endpoint":"$VALID"}"""), a.created)
            val row = a.rows.getValue(id(1))
            assertTrue(row.title!!, TITLE.matches(row.title) && row.title.startsWith("${id(1)} op-"))
            assertTrue(row.hidden)
            assertFalse(r.ui.value.cleanupPending)
            assertEquals(VALID, r.endpoint)
        }
    }

    @Test
    fun rotation() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            now += ROTATE_AFTER_MS
            r.verify()
            assertEquals(listOf(id(2)), ours(a))
            assertEquals(listOf(id(1)), a.deleted)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
            assertFalse(r.ui.value.cleanupPending)
            assertEquals("", debt().removeSurrounding("{", "}"))
        }
    }

    @Test
    fun missingOrRetitledRecordReplaced() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            a.rows.remove(id(1))
            r.verify(force = true)
            assertEquals(listOf(id(2)), ours(a))
            a.rows[id(2)] = Row("renamed by someone", "api_server", hidden = true)
            r.verify(force = true)
            assertEquals(listOf(id(2), id(3)), ours(a)) // the retitled row is no longer provably ours: never deleted
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
            assertFalse(r.ui.value.cleanupPending)
        }
    }

    @Test
    fun sameEndpointRenewalAfterThirtyDays() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            now += ROTATE_AFTER_MS - 1
            r.verify(force = true)
            assertEquals(listOf(id(1)), ours(a))
            now += 1
            r.verify(force = true)
            assertEquals(listOf(id(2)), ours(a))
            assertEquals("""{"v":1,"endpoint":"$VALID"}""", a.created.last().second)
        }
    }

    @Test
    fun pendingRetriedOnlyForSameEndpoint() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            apiA.sendFails = true
            r.publishPending()
            assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
            apiA.sendFails = false
            r.publishPending()
            assertEquals(listOf("create ${id(1)}", "create ${id(1)}", "hide ${id(1)}"), apiA.calls)

            // A failed pending id whose endpoint was replaced goes to debt; the new endpoint gets the next id.
            endpointFor(r, VALID2)
            apiA.sendFails = true
            r.publishPending()
            assertEquals("create ${id(2)}", apiA.calls.last())
            endpointFor(r, VALID3)
            apiA.sendFails = false
            apiA.calls.clear()
            r.publishPending()
            assertEquals(listOf("create ${id(3)}", "hide ${id(3)}", "read ${id(2)}", "read ${id(1)}", "delete ${id(1)}"), apiA.calls)
            assertEquals(listOf(id(3)), ours(a))
            assertFalse(r.ui.value.cleanupPending)
        }
    }

    @Test
    fun collisionNeverAdoptedWithoutMarker() = runTest {
        // First attempt.
        with(World()) {
            a.rows[id(1)] = Row("someone else", "api_server")
            val r = registrar()
            turnedOn(r)
            assertEquals(listOf("create ${id(1)}", "read ${id(1)}", "create ${id(2)}", "hide ${id(2)}"), apiA.calls)
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
            assertFalse(id(1) in debt())
            assertFalse(r.ui.value.cleanupPending)
        }
        // Retry: the first send never arrived and a stranger's row took the id meanwhile.
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            apiA.sendFails = true
            r.publishPending()
            apiA.sendFails = false
            a.rows[id(1)] = Row("someone else", "api_server")
            r.publishPending()
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
            assertEquals(listOf(id(1), id(2)), ours(a))
            r.disable()
            assertEquals(listOf(id(1)), ours(a))
            assertEquals(listOf(id(2)), a.deleted)
        }
    }

    @Test
    fun retryAdoptsOnlyWithMarker() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            // The create reached the server, the answer was lost.
            apiA.failWith = null
            val lost = object : RecordApi by apiA {
                override suspend fun create(id: String, title: String, prompt: String): Boolean {
                    apiA.create(id, title, prompt)
                    throw ApiError.Network(java.io.IOException("reset"))
                }
            }
            current = lost
            r.publishPending()
            current = apiA
            r.publishPending()
            assertEquals(listOf(id(1)), ours(a))
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
            assertTrue(a.rows.getValue(id(1)).hidden)
        }
        // The same title from another source is not ours.
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            apiA.sendFails = true
            r.publishPending()
            apiA.sendFails = false
            a.rows[id(1)] = Row(prefs.getString(Keys.PENDING_TITLE, null), "cli")
            r.publishPending()
            assertEquals("cli", a.rows.getValue(id(1)).source)
            assertEquals(prefs.getString(Keys.RECORD, null), id(2))
        }
    }

    @Test
    fun deletedIdNeverReused() = runTest {
        with(World()) {
            a.retired += id(1)
            val r = registrar()
            turnedOn(r)
            assertEquals(id(2), prefs.getString(Keys.RECORD, null))
            r.disable()
            turnedOn(r)
            assertEquals(id(3), prefs.getString(Keys.RECORD, null))
            val ids = a.created.map { it.first }
            assertEquals(ids.distinct(), ids)
        }
    }

    @Test
    fun seqExhausted() = runTest {
        with(World()) {
            prefs.edit().putInt(Keys.SEQ, MAX_SEQ).commit()
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            r.publishPending()
            assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
            assertEquals(emptyList<String>(), apiA.calls)
        }
    }

    @Test
    fun debtFullStopsPublishing() = runTest {
        with(World()) {
            fillDebtOfB(MAX_DEBT - DEBT_RESERVE)
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            r.publishPending()
            assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
            assertEquals(emptyList<String>(), apiA.calls)
            assertEquals(MAX_DEBT - DEBT_RESERVE, Regex("apofis-push-other-").findAll(debt()).count())
        }
    }

    @Test
    fun turnOffAtDebtCapacity() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            fillDebtOfB(MAX_DEBT - DEBT_RESERVE - 1)
            endpointFor(r, VALID2)
            apiA.failWith = ApiError.Network(java.io.IOException("down")) // pending reserved, attempted, server unreachable
            r.publishPending()
            assertEquals(id(2), prefs.getString(Keys.PENDING_ID, null))
            fillDebtOfB(MAX_DEBT - DEBT_RESERVE)
            r.disable()
            assertEquals(PushState.Off, r.ui.value.state)
            assertTrue(r.ui.value.cleanupPending)
            assertTrue(id(1) in debt() && id(2) in debt())
            assertEquals(MAX_DEBT, Regex(""""id":""").findAll(debt()).count())
            assertEquals(listOf(id(1)), ours(a))
        }
    }

    @Test
    fun oldRowSurvivesDisable() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            apiA.failWith = ApiError.Unavailable(null)
            r.disable()
            assertEquals(PushState.Off, r.ui.value.state)
            assertTrue(r.ui.value.cleanupPending)
            assertEquals(listOf(id(1)), ours(a)) // still there: never forgotten
            assertTrue(id(1) in debt())
            assertEquals(emptySet<String>(), port.registered)
        }
    }

    @Test
    fun debtDrainedWhileDisabled() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            apiA.failWith = ApiError.Network(java.io.IOException("down"))
            r.disable()
            apiA.failWith = null
            r.publishPending()
            assertEquals(emptyList<String>(), ours(a))
            assertFalse(r.ui.value.cleanupPending)
            assertEquals(PushState.Off, r.ui.value.state)
        }
    }

    @Test
    fun cleanupPendingShown() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            now += ROTATE_AFTER_MS
            val failingDelete = object : RecordApi by apiA {
                override suspend fun delete(id: String) = throw ApiError.Unavailable(null)
            }
            current = failingDelete
            r.verify(force = true)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
            assertTrue(r.ui.value.cleanupPending)
            current = apiA
            r.verify(force = true)
            assertFalse(r.ui.value.cleanupPending)
            assertEquals(listOf(id(2)), ours(a))
        }
    }

    @Test
    fun failedSwitchDeleteKept() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            apiA.failWith = ApiError.Network(java.io.IOException("down"))
            connection++
            r.forgetServer(apiA)
            current = apiB
            assertTrue(id(1) in debt())
            endpointFor(r)
            r.publishPending()
            r.verify(force = true)
            assertTrue(apiB.calls.none { id(1) in it })
            assertTrue(id(1) in debt())
            // Back on A: its debt is drained with A's connection.
            apiA.failWith = null
            connection++
            r.forgetServer(apiB)
            current = apiA
            r.publishPending()
            assertEquals(emptyList<String>(), ours(a))
            assertEquals(listOf(id(2)), b.deleted) // B's record goes the same way, with B's connection
            assertFalse(r.ui.value.cleanupPending)
        }
    }

    @Test
    fun sameUrlKeyChangeKeepsDebt() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            apiA.failWith = ApiError.Network(java.io.IOException("down"))
            connection++
            r.forgetServer(apiA)
            assertTrue(id(1) in debt())
            apiA = FakeRecordApi(a, log) // same URL, new key
            current = apiA
            r.publishPending()
            assertEquals(emptyList<String>(), ours(a))
            assertFalse(r.ui.value.cleanupPending)
        }
    }

    @Test
    fun switchRotatesTopic() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.calls.clear()
            connection++
            r.forgetServer(apiA)
            current = apiB
            assertEquals(listOf("unregister g1", "save $NTFY_PACKAGE", "register g2"), port.calls)
            assertEquals(listOf(id(1)), a.deleted)
            // The new instance is not assumed to have a new endpoint, and the old one's endpoint is never reused.
            r.publishPending()
            assertEquals(emptyList<String>(), apiB.calls)
            assertEquals(PushState.Registering, r.ui.value.state)
            endpointFor(r, VALID, instance = "g1")
            r.publishPending()
            assertEquals(emptyList<String>(), apiB.calls)
            endpointFor(r, VALID2)
            r.publishPending()
            assertEquals(listOf(id(2) to """{"v":1,"endpoint":"$VALID2"}"""), b.created)
        }
    }

    @Test
    fun switchWhileDisabledRegistersNothing() = runTest {
        with(World()) {
            val r = registrar()
            connection++
            r.forgetServer(apiA)
            assertEquals(emptyList<String>(), port.calls)
            turnedOn(r)
            r.disable()
            port.calls.clear()
            connection++
            r.forgetServer(apiA)
            current = apiB
            assertTrue(port.calls.none { it.startsWith("register") || it.startsWith("save") })
            assertEquals(PushState.Off, r.ui.value.state)
        }
    }

    @Test
    fun reEnableIgnoresDelayedCallbacks() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            r.disable()
            r.enableNow()
            assertEquals("g3", r.currentInstance())
            endpointFor(r, VALID, instance = "g1")
            prefs.edit().putString(Keys.UNREGISTERED_INSTANCE, "g1").commit()
            r.publishPending()
            assertEquals(emptyList<String>(), apiA.calls)
            assertEquals(PushState.Registering, r.ui.value.state)
            endpointFor(r, VALID)
            r.publishPending()
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun savedButNotAcked() = runTest {
        with(World()) {
            port.acked = false
            val r = registrar()
            r.enableNow()
            r.verify(force = true)
            assertEquals(PushState.Registering, r.ui.value.state)
            assertEquals(listOf("save $NTFY_PACKAGE", "register g1", "register g1"), port.calls)
            // ntfy uninstalled: the saved distributor is gone, so push turns itself off.
            port.installed.clear()
            r.verify(force = true)
            assertEquals(PushState.NoDistributor, r.ui.value.state)
            assertFalse(prefs.getBoolean(Keys.ENABLED, true))
        }
    }

    @Test
    fun verifyThrottleAndForce() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            r.verify()
            val afterFirst = apiA.calls.size
            assertTrue(afterFirst > 2)
            now += VERIFY_EVERY_MS - 1
            r.verify()
            assertEquals(afterFirst, apiA.calls.size)
            r.verify(force = true)
            val afterForced = apiA.calls.size
            assertTrue(afterForced > afterFirst)
            now += VERIFY_EVERY_MS
            r.verify()
            assertTrue(apiA.calls.size > afterForced)
        }
    }

    @Test
    fun twoInstalls() = runTest {
        val one = World()
        val two = World("push-two", install = "9f8e7d6c-5b4a-4321-8fed-cba987654321")
        two.apiA = FakeRecordApi(one.a, two.log)
        two.current = two.apiA
        val r1 = with(one) { registrar() }
        val r2 = with(two) { registrar() }
        one.turnedOn(r1)
        two.turnedOn(r2)
        assertEquals(2, one.ours(one.a).size)
        r1.disable()
        assertEquals(listOf(recordId("9f8e7d6c-5b4a-4321-8fed-cba987654321", 1)), one.ours(one.a))
        assertEquals(PushState.On(NTFY_PACKAGE), r2.ui.value.state)
    }

    @Test
    fun endpointNeverLogged() = runTest {
        ShadowLog.clear()
        with(World()) {
            val r = registrar()
            turnedOn(r)
            endpointFor(r, "https://evil.example/upAbC123xyz_-9?up=1") // refused
            r.publishPending()
            endpointFor(r, VALID2)
            apiA.failWith = ApiError.Network(java.io.IOException("ntfy.sh/upZyX987abc_-1"))
            r.publishPending()
            r.disable()
            connection++
            r.forgetServer(apiA)
        }
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable}" }
        assertTrue(logged.isNotEmpty())
        for (secret in listOf("upAbC123xyz", "upZyX987abc", "ntfy.sh/", "evil.example")) assertFalse(logged, secret in logged)
    }

    // Step 2b (gate I4).

    @Test
    fun disableAfterReservationBeforeSendAgainstExistingRow() = runTest {
        with(World()) {
            a.rows[id(1)] = Row("someone else", "api_server")
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            log.killAt = log.count() + 2 // reservation commit, then death before the attempted commit
            runCatching { r.publishPending() }.onSuccess { error("no death") }
            assertEquals(id(1), prefs.getString(Keys.PENDING_ID, null))
            log.killAt = 0
            val restarted = registrar()
            restarted.disable()
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
            assertEquals(emptyList<String>(), a.deleted)
            assertFalse(id(1) in debt())
            assertFalse(restarted.ui.value.cleanupPending)
        }
    }

    @Test
    fun deathAfterAttemptedBeforeIo() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor(r)
            log.killAt = log.count() + 3 // reservation commit, attempted commit, death before the create request
            runCatching { r.publishPending() }.onSuccess { error("no death") }
            assertTrue(prefs.getBoolean(Keys.PENDING_ATTEMPTED, false))
            assertEquals(emptyList<String>(), apiA.calls)
            a.rows[id(1)] = Row("someone else", "api_server")
            log.killAt = 0
            val restarted = registrar()
            restarted.publishPending()
            assertEquals(listOf("create ${id(1)}", "read ${id(1)}", "create ${id(2)}", "hide ${id(2)}"), apiA.calls)
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
            assertEquals(emptyList<String>(), a.deleted)
        }
    }

    @Test
    fun deathAtEveryDisablePhase() = runTest {
        var k = 1
        while (true) {
            val w = World()
            val r = with(w) { registrar() }
            w.turnedOn(r)
            w.log.killAt = w.log.count() + k
            val died = runCatching { r.disable() }.isFailure
            w.log.killAt = 0
            val restarted = with(w) { registrar() }
            if (w.prefs.getBoolean(Keys.ENABLED, false)) {
                // Death before the first commit: nothing was revoked yet and push is still on; the user turns it off again.
                assertEquals("kill at $k", 1, k)
                assertEquals(PushState.On(NTFY_PACKAGE), restarted.ui.value.state)
                restarted.disable()
            } else {
                restarted.publishPending()
            }
            assertEquals("kill at $k: ${w.log.events}", emptyList<String>(), w.ours(w.a))
            assertEquals(emptySet<String>(), w.port.registered)
            assertNull(w.prefs.getString(Keys.REVOKE_PHASE, null))
            assertEquals(PushUi(PushState.Off, false), restarted.ui.value)
            if (!died) break
            k++
        }
        assertTrue("phases reached: $k", k > 5)
    }

    @Test
    fun switchDeathBetweenEachSideEffect() = runTest {
        for (sameUrl in listOf(false, true)) {
            var k = 1
            while (true) {
                val w = World()
                val r = with(w) { registrar() }
                w.turnedOn(r)
                val target: FakeRecordApi = if (sameUrl) FakeRecordApi(w.a, w.log) else w.apiB
                w.log.killAt = w.log.count() + k
                w.connection++
                val died = runCatching { r.forgetServer(w.apiA) }.isFailure
                w.log.killAt = 0
                val r2 = if (died) {
                    // AppContainer starts disconnected after a death during a switch; the user then connects again.
                    with(w) { registrar() }.also { w.current = null; w.connection++; it.forgetServer(null) }
                } else r
                w.current = target
                r2.publishPending()
                val events = "kill at $k same URL $sameUrl: ${w.log.events}"
                assertEquals(events, null, w.prefs.getString(Keys.REVOKE_PHASE, null))
                assertTrue(events, w.port.registered == setOf(r2.currentInstance()))
                assertNotEquals(events, "g1", r2.currentInstance())
                assertTrue(events, target.calls.none { it.startsWith("create") }) // only after NEW_ENDPOINT
                w.endpointFor(r2, VALID2)
                r2.publishPending()
                assertEquals(events, PushState.On(NTFY_PACKAGE), r2.ui.value.state)
                // Every old record is deleted on its own server, once connected there again; nothing else is touched.
                if (!sameUrl) {
                    w.connection++
                    r2.forgetServer(w.apiB)
                    w.current = w.apiA
                    r2.publishPending()
                }
                assertFalse(events, id(1) in w.ours(w.a))
                assertEquals(events, listOf(id(1)), w.a.deleted)
                assertTrue(events, w.b.deleted.isEmpty() || !sameUrl)
                assertFalse(events, r2.ui.value.cleanupPending)
                if (!died) break
                k++
            }
            assertTrue("side effects in the switch: $k", k > 4)
        }
    }
}
