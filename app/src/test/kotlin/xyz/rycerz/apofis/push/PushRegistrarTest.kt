package xyz.rycerz.apofis.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
private val NTFY_ONLY = setOf(NTFY_PACKAGE)

@RunWith(RobolectricTestRunner::class)
class PushRegistrarTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Everything that survives a process death (prefs on disk, the server, the connector); registrars are rebuilt from it. */
    private inner class World(install: String = INSTALL) {
        val log = EventLog()
        val a = FakeServer("https://a.example/")
        val b = FakeServer("https://b.example/")
        var apiA = FakeRecordApi(a, log)
        val apiB = FakeRecordApi(b, log)
        var current: RecordApi? = apiA
        var connection = 0L
        var now = 1_000_000_000L
        val port = FakePort(log)
        var scheduled = 0
        var epoch = 0L // the persisted connection epoch
        var prefs = FakePrefs(mutableMapOf(Keys.INSTALL_ID to install)) { log.step("commit ${it.sorted()}"); true }

        /** A new process: memory is read back from disk. */
        fun restart() {
            prefs = prefs.restart()
        }

        fun TestScope.registrar(policy: PushPolicy = TestPolicy()) =
            PushRegistrar(app, prefs, { current }, { connection }, { now }, backgroundScope, policy, port, { epoch }) { scheduled++ }

        /** What PushReceiver commits for a NEW_ENDPOINT of the current registration (or of [token]). */
        fun endpointFor(url: String = VALID, token: String? = registrationOf(prefs)?.token) {
            assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, url).putString(Keys.ENDPOINT_NEW_TOKEN, token).commit())
        }

        fun ours(s: FakeServer) = s.rows.keys.filter { it.startsWith("apofis-push-") }.sorted()
    }

    private suspend fun World.turnedOn(r: PushRegistrar, url: String = VALID) {
        r.enableNow()
        endpointFor(url)
        r.publishPending()
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    private suspend fun PushRegistrar.switch() {
        prepareSwitch()
        reconcileLocal() // what the local revocation job does
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
            assertTrue("marked title", TITLE.matches(row.title!!) && row.title.startsWith("${id(1)} op-"))
            assertTrue(row.hidden)
            assertEquals(VALID, r.endpoint)
        }
    }

    @Test
    fun rotationLeavesTheOldRecord() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            now += ROTATE_AFTER_MS - 1
            r.verify(force = true)
            assertEquals(listOf(id(1)), ours(a))
            now += 1
            r.verify(force = true)
            assertEquals(listOf(id(1), id(2)), ours(a)) // never deleted: the hook prefers the newest per install
            assertEquals(id(2), prefs.getString(Keys.RECORD, null))
            assertEquals("""{"v":1,"endpoint":"$VALID"}""", a.created.last().second)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun pendingRetriedOnlyForSameEndpoint() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor()
            apiA.sendFails = true
            assertFalse("the worker retries a failed create", r.publishPending())
            assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
            apiA.sendFails = false
            r.publishPending()
            assertEquals(listOf("create ${id(1)}", "create ${id(1)}", "hide ${id(1)}"), apiA.calls)
            // A failed pending id whose endpoint was replaced is dropped; the new endpoint gets the next id.
            endpointFor(VALID2)
            apiA.sendFails = true
            r.publishPending()
            endpointFor(VALID3)
            apiA.sendFails = false
            apiA.calls.clear()
            r.publishPending()
            assertEquals(listOf("create ${id(3)}", "hide ${id(3)}"), apiA.calls)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun collisionNeverAdoptedWithoutMarker() = runTest {
        with(World()) {
            a.rows[id(1)] = Row("someone else", "api_server")
            val r = registrar()
            turnedOn(r)
            assertEquals(listOf("create ${id(1)}", "read ${id(1)}", "create ${id(2)}", "hide ${id(2)}"), apiA.calls)
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
        }
        // Retry: the first send never arrived and a stranger's row took the id meanwhile.
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor()
            apiA.sendFails = true
            r.publishPending()
            apiA.sendFails = false
            a.rows[id(1)] = Row("someone else", "api_server")
            r.publishPending()
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
            assertEquals(id(2), prefs.getString(Keys.RECORD, null))
        }
    }

    @Test
    fun retryAdoptsOnlyWithMarker() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor()
            val lost = object : RecordApi by apiA { // the create reached the server, the answer was lost
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
        with(World()) { // the same title from another source is not ours
            val r = registrar()
            r.enableNow()
            endpointFor()
            apiA.sendFails = true
            r.publishPending()
            apiA.sendFails = false
            a.rows[id(1)] = Row(prefs.getString(Keys.PENDING_TITLE, null), "cli")
            r.publishPending()
            assertEquals("cli", a.rows.getValue(id(1)).source)
            assertEquals(id(2), prefs.getString(Keys.RECORD, null))
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
        }
    }

    @Test
    fun seqExhausted() = runTest {
        with(World()) {
            prefs.edit().putInt(Keys.SEQ, MAX_SEQ).commit()
            val r = registrar()
            r.enableNow()
            endpointFor()
            r.publishPending()
            assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
            assertEquals(emptyList<String>(), apiA.calls)
        }
    }

    @Test
    fun offUnregistersAtTheDistributorAndLeavesTheRecord() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.calls.clear()
            apiA.calls.clear()
            r.disable()
            assertEquals(listOf("unregister g1 tok-g1"), port.calls)
            assertEquals(emptyList<String>(), apiA.calls) // no server request at all
            assertEquals(listOf(id(1)), ours(a))
            assertEquals(PushState.Off, r.ui.value.state)
            assertTrue(port.registered.isEmpty())
            assertNull(prefs.getString(Keys.REVOKE, null))
        }
    }

    @Test
    fun switchRotatesTopic() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.calls.clear()
            connection++
            r.switch()
            current = apiB
            assertEquals(listOf("unregister g1 tok-g1", "save $NTFY_PACKAGE", "register g2"), port.calls)
            assertEquals(listOf(id(1)), ours(a)) // left on the old server, whose endpoint now leads nowhere
            // The new instance is not assumed to have an endpoint, and the old one's is never reused.
            r.publishPending()
            assertEquals(emptyList<String>(), apiB.calls)
            assertEquals(PushState.Registering, r.ui.value.state)
            endpointFor(VALID, token = "tok-g1")
            r.publishPending()
            assertEquals(emptyList<String>(), apiB.calls)
            endpointFor(VALID2)
            r.publishPending()
            assertEquals(listOf(id(2) to """{"v":1,"endpoint":"$VALID2"}"""), b.created)
        }
    }

    @Test
    fun switchWhileDisabledRegistersNothing() = runTest {
        with(World()) {
            val r = registrar()
            connection++
            r.switch()
            assertEquals(emptyList<String>(), port.calls)
            turnedOn(r)
            r.disable()
            port.calls.clear()
            connection++
            r.switch()
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
            endpointFor(VALID, token = "tok-g1")
            prefs.edit().putString(Keys.UNREGISTERED_INSTANCE, "g1").commit()
            r.publishPending()
            assertEquals(emptyList<String>(), apiA.calls)
            assertEquals(PushState.Registering, r.ui.value.state)
            endpointFor(VALID)
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
            port.calls.clear()
            r.verify(force = true)
            assertEquals(PushState.Registering, r.ui.value.state)
            assertTrue(port.calls.contains("register g1"))
            port.installed.clear() // ntfy uninstalled: push turns itself off
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
            now += VERIFY_EVERY_MS // the publication itself proved the record
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
        val other = "9f8e7d6c-5b4a-4321-8fed-cba987654321"
        val one = World()
        val two = World(install = other)
        two.apiA = FakeRecordApi(one.a, two.log)
        two.current = two.apiA
        val r1 = with(one) { registrar() }
        val r2 = with(two) { registrar() }
        one.turnedOn(r1)
        two.turnedOn(r2)
        assertEquals(listOf(id(1), recordId(other, 1)).sorted(), one.ours(one.a))
        r1.disable()
        assertEquals(PushState.On(NTFY_PACKAGE), r2.ui.value.state)
    }

    @Test
    fun endpointNeverLogged() = runTest {
        ShadowLog.clear()
        with(World()) {
            val r = registrar()
            turnedOn(r)
            endpointFor("https://evil.example/upAbC123xyz_-9?up=1") // refused
            val before = apiA.calls.size
            r.publishPending()
            assertEquals("refused endpoint: server error", PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
            assertEquals("refused endpoint: no request", before, apiA.calls.size)
            endpointFor(VALID2)
            apiA.failWith = ApiError.Network(java.io.IOException("ntfy.sh/upZyX987abc_-1"))
            r.publishPending()
            r.disable()
            connection++
            r.switch()
        }
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable}" }
        assertTrue(logged.isNotEmpty())
        listOf("upAbC123xyz", "upZyX987abc", "ntfy.sh/", "evil.example").forEachIndexed { i, secret -> assertFalse("secret $i logged", secret in logged) }
    }

    @Test
    fun missingOrChangedRecordReplacedNeverTouched() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            a.rows.remove(id(1))
            r.verify(force = true)
            assertEquals(listOf(id(2)), ours(a))
            // A turn ran in it: hiding it would reach rows the app never made, so it is left alone and replaced.
            a.rows.getValue(id(2)).apply { messageCount = 3; hidden = false }
            apiA.calls.clear()
            r.verify(force = true)
            assertFalse(apiA.calls.contains("hide ${id(2)}"))
            assertFalse(a.rows.getValue(id(2)).hidden)
            assertEquals(id(3), prefs.getString(Keys.RECORD, null))
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    // Step 2b (gate I4), without server deletes.

    @Test
    fun deathAfterReservationBeforeCreate() = runTest {
        with(World()) {
            a.rows[id(1)] = Row("someone else", "api_server")
            val r = registrar()
            r.enableNow()
            endpointFor()
            log.killAt = log.count() + 2 // the reservation commit, then death before the create request
            runCatching { r.publishPending() }.onSuccess { error("no death") }
            log.killAt = 0
            restart()
            val restarted = registrar()
            apiA.calls.clear()
            restarted.publishPending()
            assertEquals(listOf("create ${id(1)}", "read ${id(1)}", "create ${id(2)}", "hide ${id(2)}"), apiA.calls)
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
            restarted.disable()
            assertEquals(Row("someone else", "api_server"), a.rows[id(1)])
        }
    }

    @Test
    fun deathAtEveryOffStep() = runTest {
        var k = 1
        while (true) {
            val w = World()
            val r = with(w) { registrar() }
            w.turnedOn(r)
            w.log.killAt = w.log.count() + k
            val died = runCatching { r.disable() }.isFailure
            w.log.killAt = 0
            w.restart()
            val restarted = with(w) { registrar() }
            if (w.prefs.getBoolean(Keys.ENABLED, false)) {
                assertEquals("death before the Off commit only", 1, k)
                restarted.disable()
            } else {
                restarted.publishPending() // startup finishes the committed revocation
            }
            assertEquals("kill at $k", emptySet<String>(), w.port.registered)
            assertNull(w.prefs.getString(Keys.REVOKE, null))
            assertEquals(PushUi(PushState.Off), restarted.ui.value)
            if (!died) break
            k++
        }
        assertTrue("steps reached: $k", k >= 3)
    }

    @Test
    fun deathAtEverySwitchStep() = runTest {
        var k = 1
        while (true) {
            val w = World()
            val r = with(w) { registrar() }
            w.turnedOn(r)
            w.log.killAt = w.log.count() + k
            w.connection++
            val died = runCatching { r.switch() }.isFailure
            w.log.killAt = 0
            w.current = w.apiB
            val r2 = if (died) {
                w.restart()
                with(w) { registrar() }.also { if (!w.prefs.contains(Keys.REVOKE)) it.switch() } // death before its first commit: the switch runs again
            } else {
                r
            }
            r2.publishPending() // startup work: finishes any committed revocation without help
            val events = "kill at $k"
            assertNull(events, w.prefs.getString(Keys.REVOKE, null))
            assertEquals(events, setOf(r2.currentInstance()), w.port.registered)
            assertTrue(events, "g1" !in w.port.registered)
            assertTrue(events, w.apiB.calls.none { it.startsWith("create") }) // only after its own NEW_ENDPOINT
            w.endpointFor(VALID2)
            r2.publishPending()
            assertEquals(events, PushState.On(NTFY_PACKAGE), r2.ui.value.state)
            if (!died) break
            k++
        }
        assertTrue("side effects in the switch: $k", k >= 5)
    }

    @Test
    fun connectionChangeStopsBeforeTheNextRequestOrResult() = runTest {
        // A read in flight when the switch starts: no hide or create after it.
        with(World()) {
            val r = registrar()
            turnedOn(r)
            a.rows.getValue(id(1)).hidden = false
            apiA.beforeCall = { if (it.startsWith("read")) connection++ }
            apiA.calls.clear()
            r.verify(force = true)
            assertEquals(listOf("read ${id(1)}"), apiA.calls)
        }
        // A conflict that comes back after the switch: the reservation is not advanced.
        with(World()) {
            a.retired += id(1)
            val r = registrar()
            r.enableNow()
            endpointFor()
            apiA.beforeCall = { if (it.startsWith("create")) connection++ }
            r.publishPending()
            assertEquals(id(1), prefs.getString(Keys.PENDING_ID, null))
        }
        // A hide that returns after the switch: the record is not promoted.
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor()
            apiA.beforeCall = { if (it.startsWith("hide")) connection++ }
            r.publishPending()
            assertNull(prefs.getString(Keys.RECORD, null))
            assertEquals(id(1), prefs.getString(Keys.PENDING_ID, null))
        }
    }

    @Test
    fun anOperationQueuedAcrossASwitchBelongsToTheOldConnection() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            apiA.beforeCall = { if (!entered.isCompleted) { entered.complete(Unit); gate.await() } }
            val first = launch(kotlinx.coroutines.Dispatchers.IO) { r.publishPending() } // holds the mutex inside its create
            entered.await()
            // Admitted under connection 0 while the first holds the mutex; the connection changes before it runs.
            val second = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { r.publishPending() }
            connection++
            gate.complete(Unit)
            first.join()
            second.join()
            assertNull(prefs.getString(Keys.RECORD, null))
            assertEquals(listOf("create ${id(1)}"), apiA.calls)
        }
    }

    @Test
    fun failedCommitFencesUntilWritten() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.calls.clear()
            prefs.onCommit = { false } // the disk stops taking writes
            r.disable()
            assertNull(prefs.disk[Keys.REVOKE])
            assertEquals("not confirmed while memory and disk differ", PushState.Registering, r.ui.value.state)
            r.publishPending()
            r.verify(force = true)
            assertEquals("no side effect from a state that is not on disk", emptyList<String>(), port.calls)
            prefs.onCommit = { true } // the disk recovers: the intent is written first, then acted on
            r.publishPending()
            assertEquals("unregister g1 tok-g1", port.calls.first())
            assertEquals(PushUi(PushState.Off), r.ui.value)
        }
        with(World()) { // a restart instead reads the disk, where push is still on
            val r = registrar()
            turnedOn(r)
            prefs.onCommit = { false }
            r.disable()
            prefs.onCommit = { true }
            restart()
            val restarted = registrar()
            restarted.publishPending()
            assertEquals(PushState.On(NTFY_PACKAGE), restarted.ui.value.state)
        }
    }

    @Test
    fun aLostEndpointIsNeverShownOnAndIsAskedForAgain() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            // ntfy sends a new endpoint; its commit fails (Android keeps it in memory only).
            prefs.onCommit = { false }
            assertFalse(PushStore.commit(prefs) { putString(Keys.ENDPOINT_NEW, VALID2).putString(Keys.ENDPOINT_NEW_TOKEN, "tok-g1") })
            r.verify(force = true)
            assertEquals(PushState.Registering, r.ui.value.state)
            // Death: the disk holds the obsolete endpoint. The next process asks the distributor again.
            prefs.onCommit = { true }
            restart()
            port.calls.clear()
            assertTrue(needsWork(prefs, NTFY_ONLY, epoch) || prefs.getBoolean(Keys.ENABLED, false))
            registrar().publishPending()
            assertTrue(port.calls.contains("register g1"))
        }
    }

    @Test
    fun aRegistrationTheConnectorLostIsNotOn() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.lostRegistration = true
            port.acked = false
            r.verify(force = true)
            assertEquals("the published endpoint belongs to the lost token", PushState.Registering, r.ui.value.state)
            endpointFor(VALID2)
            r.publishPending()
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun startupFindsEveryIntent() = runTest {
        // Enable committed, death before the distributor was saved.
        with(World()) {
            val r = registrar()
            log.killAt = log.count() + 2 // the enable commit, then death before save
            runCatching { r.enableNow() }.onSuccess { error("no death") }
            log.killAt = 0
            restart()
            assertTrue(needsWork(prefs, NTFY_ONLY, epoch))
            registrar().publishPending()
            assertEquals(setOf("g1"), port.registered)
        }
        // Registration journaled, death before its REGISTER broadcast left the app.
        with(World()) {
            val r = registrar()
            log.killAt = log.count() + 5 // enable commit, save, register, journal commit, death before REGISTER is sent
            runCatching { r.enableNow() }.onSuccess { error("no death") }
            log.killAt = 0
            assertTrue("the distributor never heard of it", port.subscribed.isEmpty())
            restart()
            assertTrue(needsWork(prefs, NTFY_ONLY, epoch))
            registrar().publishPending()
            assertEquals(setOf("tok-g1"), port.subscribed)
        }
        // A 30-day renewal of the same endpoint, death before its create.
        with(World()) {
            val r = registrar()
            turnedOn(r)
            now += ROTATE_AFTER_MS
            log.killAt = log.count() + 6 // register: save, REGISTER, journal, sent; then the reservation, death before the create
            runCatching { r.verify(force = true) }.onSuccess { error("no death") }
            log.killAt = 0
            restart()
            assertTrue(needsWork(prefs, NTFY_ONLY, epoch))
            registrar().publishPending()
            assertEquals(id(2), prefs.getString(Keys.RECORD, null))
            assertFalse(needsWork(prefs, NTFY_ONLY, epoch))
        }
    }

    @Test
    fun offOfAnInterruptedEnableRegistersNothing() = runTest {
        with(World()) {
            val r = registrar()
            log.killAt = log.count() + 2 // the enable commit, then death before save
            runCatching { r.enableNow() }
            log.killAt = 0
            restart()
            port.calls.clear()
            log.events.clear()
            registrar().disable()
            assertTrue("Off is committed first", log.events.first().startsWith("commit"))
            assertTrue(port.calls.none { it.startsWith("register") || it.startsWith("save") })
            assertFalse(prefs.getBoolean(Keys.ENABLED, true))
        }
    }

    @Test
    fun withoutConnectionTheWorkerRetries() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor()
            current = null // a switch is running
            assertFalse(r.publishPending())
            current = apiA
            assertTrue(r.publishPending())
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun userOperationsScheduleWorkLeftBehind() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow() // waits for NEW_ENDPOINT
            assertTrue(scheduled > 0)
        }
    }

    @Test
    fun verifyDerivesItsOutcomeAfresh() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            apiA.failWith = ApiError.Network(java.io.IOException("down"))
            r.verify(force = true)
            assertEquals("unreachable proves nothing: the last proof stands", PushState.On(NTFY_PACKAGE), r.ui.value.state)
            apiA.failWith = null
            r.verify(force = true)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
            endpointFor(VALID2) // a failed publish of a new endpoint is resumed by verify too
            apiA.sendFails = true
            r.publishPending()
            apiA.sendFails = false
            r.verify(force = true)
            assertEquals(id(2) to """{"v":1,"endpoint":"$VALID2"}""", a.created.last())
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun registrationOfAnotherPolicyIsReplaced() = runTest {
        with(World()) {
            port.installed += "xyz.rycerz.apofis.e2e"
            assertTrue(prefs.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
                .putString(Keys.REGISTRATION, "g1\txyz.rycerz.apofis.e2e\ttok-e2e\t0").commit())
            assertNull("the current epoch, yet outside the policy", currentRegistration(prefs, NTFY_ONLY, 0))
            val r = registrar() // release-shaped policy: ntfy only
            r.publishPending()
            assertEquals(listOf("unregister g1 tok-e2e", "save $NTFY_PACKAGE", "register g2"), port.calls)
            assertEquals(NTFY_PACKAGE, registrationOf(prefs)?.distributor)
        }
        with(World()) { // the connector would send REGISTER outside the policy: nothing is journaled or sent
            port.journalAs = "com.example.other"
            val r = registrar()
            r.enableNow()
            assertNull(registrationOf(prefs))
            assertTrue(port.subscribed.isEmpty())
        }
    }

    @Test
    fun switchHonoursACommittedRevocation() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            assertTrue(prefs.edit().putString(Keys.UNREGISTERED_INSTANCE, "g1").commit())
            port.calls.clear()
            connection++
            r.switch()
            assertTrue(port.calls.none { it.startsWith("register") })
            assertEquals(PushUi(PushState.Off), r.ui.value)
        }
    }

    @Test
    fun switchFailsWithoutDurableIntent() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.calls.clear()
            prefs.onCommit = { false }
            connection++
            assertTrue(runCatching { r.prepareSwitch() }.exceptionOrNull() is java.io.IOException)
            assertEquals(emptyList<String>(), port.calls)
        }
    }

    // Fix round 3.

    @Test
    fun adoptionRefusesARepurposedRow() = runTest {
        with(World()) {
            val r = registrar()
            r.enableNow()
            endpointFor()
            val lost = object : RecordApi by apiA { // the create reached Hermes, its answer was lost
                override suspend fun create(id: String, title: String, prompt: String): Boolean {
                    apiA.create(id, title, prompt)
                    throw ApiError.Network(java.io.IOException("reset"))
                }
            }
            current = lost
            r.publishPending()
            current = apiA
            // Someone used the visible row: a run, then compression (Hermes would hide and unpin the whole lineage).
            a.rows.getValue(id(1)).apply { messageCount = 12; endReason = "compression" }
            apiA.calls.clear()
            r.verify(force = true)
            assertFalse("no PATCH to a row that is not provably our pristine record", apiA.calls.contains("hide ${id(1)}"))
            assertFalse(a.rows.getValue(id(1)).hidden)
            assertEquals(id(2), prefs.getString(Keys.RECORD, null))
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun aDisplacedTokenIsRevoked() = runTest {
        // The connector lost its database; ntfy kept the subscription. The next start's REGISTER gets a new token.
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.lostRegistration = true
            restart()
            val restarted = registrar()
            restarted.publishPending()
            assertEquals(setOf("tok-g1-new0"), port.subscribed)
            assertTrue(port.calls.contains("unregister g1 tok-g1 token-only"))
            restarted.disable() // before the new endpoint arrived
            assertEquals("no token ever registered stays subscribed", emptySet<String>(), port.subscribed)
        }
        // Death right after the replacement was journaled: the displaced token is in the same commit, so it is revoked.
        with(World()) {
            val r = registrar()
            turnedOn(r)
            port.lostRegistration = true
            restart()
            log.killAt = log.count() + 4 // save, register, journal commit, death before REGISTER is sent
            runCatching { registrar().publishPending() }.onSuccess { error("no death") }
            log.killAt = 0
            restart()
            assertTrue(revocationsOf(prefs).any { it.token == "tok-g1" })
            val again = registrar()
            again.publishPending()
            again.disable()
            assertEquals(emptySet<String>(), port.subscribed)
        }
    }

    @Test
    fun offIsNotShownUntilHandedToTheDistributor() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            val failing = object : UnifiedPushPort by port {
                override fun unregister(c: android.content.Context, r: Registration, connector: Boolean) = throw IllegalStateException("broadcast failed")
            }
            val withFailingPort = PushRegistrar(app, prefs, { current }, { connection }, { now }, backgroundScope, TestPolicy(), failing) { scheduled++ }
            scheduled = 0
            withFailingPort.disable()
            assertTrue(prefs.contains(Keys.REVOKE))
            assertEquals(PushState.Registering, withFailingPort.ui.value.state)
            assertTrue(scheduled > 0)
            registrar().publishPending()
            assertEquals(PushUi(PushState.Off), registrar().ui.value)
        }
    }

    // Fix round 4.

    @Test
    fun aRegistrationMadeBeforeTheEpochAdvancedIsNotOn() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            connection++
            r.prepareSwitch()
            current = null
            // A queued worker runs while connect waits before the marker: it registers g2 under the old epoch, and
            // ntfy's NEW_ENDPOINT for that token arrives.
            r.publishPending()
            assertEquals(0L, registrationOf(prefs)?.epoch)
            endpointFor(VALID2)
            epoch++ // the marker commit advances the epoch
            current = apiB
            r.publishPending()
            assertEquals(1L, registrationOf(prefs)?.epoch)
            assertEquals("a new instance, never the old token under the new epoch", "g3", registrationOf(prefs)?.instance)
            assertEquals(PushState.Registering, r.ui.value.state)
            endpointFor(VALID3)
            r.publishPending()
            assertTrue("the receiver accepts its token", currentRegistration(prefs, NTFY_ONLY, epoch) != null)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
        with(World()) { // without re-registration it would never show On
            val r = registrar()
            turnedOn(r)
            epoch++
            assertEquals(PushState.Registering, registrar().ui.value.state)
        }
    }

    // Fix round 5.

    @Test
    fun theOldServerNeverLearnsTheNewConnectionsTopic() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            connection++
            r.prepareSwitch()
            // A queued worker takes the mutex right after the rotation, with server A still reachable: it registers g2
            // under A's epoch, its endpoint arrives, and it publishes it to A.
            r.publishPending()
            endpointFor(VALID2)
            r.publishPending()
            val toldA = a.created.map { it.second }
            // connect resumes: A is gone, the marker advances the epoch, the local job runs, then B's connection.
            current = null
            epoch++
            // What production schedules for that state: the local job too, without a network constraint.
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder()
                .setTaskExecutor(androidx.work.testing.SynchronousExecutor()).setExecutor { }.build())
            PublishWorker.recover(app, prefs)
            val local = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.LOCAL_NAME).get().single()
            assertEquals(androidx.work.NetworkType.NOT_REQUIRED, local.constraints.requiredNetworkType)
            r.reconcileLocal() // what that job runs
            current = apiB
            endpointFor(VALID3)
            r.publishPending()
            val bToken = currentRegistration(prefs, NTFY_ONLY, epoch)?.token!!
            assertTrue("B's topic is a new one", "tok-g2" != bToken)
            assertTrue("A never received B's endpoint", toldA.none { VALID3 in it } && a.created.none { VALID3 in it.second })
            assertTrue("the topic A may know is unsubscribed", "tok-g2" !in port.subscribed)
            assertEquals(listOf("""{"v":1,"endpoint":"$VALID3"}"""), b.created.map { it.second })
            assertEquals(b.created.single().first, prefs.getString(Keys.RECORD, null))
            assertTrue(bToken in port.subscribed)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    // Merge review fixes, round 2.

    @Test
    fun offAndASwitchRemoveTheProofWithTheRecord() = runTest {
        with(World()) {
            val r = registrar()
            turnedOn(r)
            assertTrue(prefs.contains(Keys.VERIFIED_AT))
            r.disable()
            assertTrue(!prefs.contains(Keys.VERIFIED_AT) && !prefs.contains(Keys.RECORD))
        }
        with(World()) {
            val r = registrar()
            turnedOn(r)
            connection++
            r.prepareSwitch()
            assertTrue(!prefs.contains(Keys.VERIFIED_AT) && !prefs.contains(Keys.RECORD))
        }
    }
}
