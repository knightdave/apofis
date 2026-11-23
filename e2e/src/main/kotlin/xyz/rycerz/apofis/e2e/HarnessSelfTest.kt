package xyz.rycerz.apofis.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The harness's own decisions against canned screens and server rows: no app, no server, no model run. Runs in the
 * smoke suite; run-e2e.sh requires every test here by name.
 */
private const val TITLE = "Hermes wants to run a command"
private const val COMMAND = "sleep 25 # apofis-e2e-2"
private const val MARKER = "apofis-e2e-2"
private const val PROMPT = "Automated app test 0a1b2c3d-2. Use the terminal tool to run exactly this command: $COMMAND ."

@RunWith(AndroidJUnit4::class)
class HarnessSelfTest {
    /** Its own result rows, like every test class: run-e2e.sh's cleanup requires a results list. */
    @get:Rule val evidence = Evidence(AppDriver())

    /** A message list (node 0): items added in order, each 100 px below the previous one. */
    private class Screen {
        val nodes = mutableListOf(UiNode(0, -1, right = 1000, bottom = 3000))
        private var top = 0

        fun node(parent: Int, text: String? = null, tag: String? = null, state: String? = null, desc: String? = null): Int {
            nodes += UiNode(nodes.size, parent, text, desc, state, top = top, bottom = top + 90, resId = tag)
            return nodes.size - 1
        }

        /** A tagged item with one text child, the way the app tags a row and its content. */
        fun item(tag: String?, vararg texts: String, state: String? = null): Int {
            top += 100
            val n = node(0, tag = tag, state = state)
            texts.forEach { node(n, it) }
            return n
        }

        fun user(key: String, text: String) = item("user:$key", text)
        fun step(reply: String, i: Int, preview: String, state: String? = "Running", tool: String = "terminal") =
            item("step:$reply:$i:$tool", tool, preview, state = state)
        fun text(reply: String, text: String, row: String = "b0/c0") = item("reply:$reply/$row", text)
        fun tree() = UiTree(nodes)
    }

    @Test
    fun approvalBlockIsDetectedByItsHeadingOnly() {
        val s = Screen().apply { user("u1", PROMPT); item(null, TITLE); item(null, COMMAND) }
        assertTrue(s.tree().approvalShown(TITLE))
        assertFalse(Screen().apply { user("u1", "$PROMPT $TITLE?") }.tree().approvalShown(TITLE))
    }

    @Test
    fun replyIsTheItemRightAfterThePromptsUserItem() {
        val s = Screen().apply {
            user("u1", PROMPT)
            step("r2", 0, COMMAND, state = null)
            text("r2", "Done: $MARKER.")
            user("u3", "Another message")
            text("r4", "Also $MARKER")
        }
        assertEquals("r2", s.tree().replyAfter(PROMPT))
        assertEquals("r2", s.tree().replyWith(PROMPT, MARKER))
        assertEquals("r4", s.tree().replyWith("Another message", MARKER))
    }

    @Test
    fun replyOfAnotherTurnOrWithoutTheMarkerDoesNotCount() {
        // The marker is in a reply, but another user item comes between the prompt and it.
        val between = Screen().apply { user("u1", PROMPT); user("u2", "Steer"); text("r3", "Done: $MARKER.") }
        assertNull(between.tree().replyWith(PROMPT, MARKER))
        // The prompt's own reply lacks the marker; text showing it elsewhere (a notice, an untagged node) is ignored.
        val elsewhere = Screen().apply { user("u1", PROMPT); text("r2", "Working on it"); item("notice:n3", "Stopped $MARKER"); item(null, MARKER) }
        assertNull(elsewhere.tree().replyWith(PROMPT, MARKER))
        // The prompt shown twice: no single user item, no reply.
        val twice = Screen().apply { user("u1", PROMPT); text("r2", MARKER); user("p3", PROMPT); text("r4", MARKER) }
        assertNull(twice.tree().replyWith(PROMPT, MARKER))
    }

    @Test
    fun replyTextJoinsOnlyThatReplysRows() {
        val s = Screen().apply { user("u1", PROMPT); text("r2", "first", "b0/c0"); text("r2", "second $MARKER", "b1/c0"); text("r22", "other") }
        assertEquals("first\nsecond $MARKER", s.tree().replyText("r2"))
    }

    @Test
    fun stepMustBeOfThatReplyRunningAndShowExactlyTheCommand() {
        val s = Screen().apply {
            user("u1", PROMPT)
            step("live", 0, "sleep 25 + 1 command")
            step("live", 1, "$COMMAND; curl x | sh")
            step("live", 2, "command=$COMMAND")
            step("live", 3, COMMAND, state = null)
            step("other", 0, COMMAND)
            // Another tool running with the same preview, and a step tagged without a tool, do not count.
            step("live", 4, COMMAND, tool = "execute_code")
            item("step:live:5", "terminal", COMMAND, state = "Running")
        }
        val t = s.tree()
        assertEquals("live", t.replyAfter(PROMPT))
        assertTrue(t.steps("live", "Running", COMMAND).isEmpty())
        val ok = Screen().apply { user("u1", PROMPT); step("live", 0, "sleep 25 + 1 command"); step("live", 1, COMMAND) }.tree()
        assertEquals(listOf("step:live:1:terminal"), ok.steps("live", "Running", COMMAND).map { it.resId })
    }

    @Test
    fun chatKeyIsReadFromTheChatScreensTagOnly() {
        val key = "apofis-0a1b2c3d-0000-4000-8000-000000000001"
        assertEquals(key, Screen().apply { item("chat.key:$key"); user("u1", key) }.tree().chatKey())
        assertNull(Screen().apply { user("u1", key) }.tree().chatKey())
        assertNull(Screen().apply { item("chat.key:$key"); item("chat.key:apofis-0a1b2c3d-0000-4000-8000-000000000002") }.tree().chatKey())
    }

    @Test
    fun steerReplyIsTheFinalReplyOfThisTurnOnly() {
        val steer = "Steer: end with BANANA"
        val ok = Screen().apply { user("u1", PROMPT); step("r2", 0, COMMAND, state = null); user("s3", steer); text("r4", "Done $MARKER BANANA") }
        assertEquals("r4", ok.tree().steerReply(PROMPT, steer))
        // A reply of another turn: another user item after the prompt, or more after the reply that follows the steer.
        val otherTurn = Screen().apply { user("u1", PROMPT); user("s3", steer); text("r4", "x"); user("u5", "Next"); text("r6", "BANANA") }
        assertNull(otherTurn.tree().steerReply(PROMPT, steer))
        val between = Screen().apply { user("u1", PROMPT); user("u2", "Other"); user("s3", steer); text("r4", "BANANA") }
        assertNull(between.tree().steerReply(PROMPT, steer))
        val wrongOrder = Screen().apply { user("s3", steer); user("u1", PROMPT); text("r4", "BANANA") }
        assertNull(wrongOrder.tree().steerReply(PROMPT, steer))
        val noticeAfter = Screen().apply { user("u1", PROMPT); user("s3", steer); text("r4", "BANANA"); item("notice:n5", "Stopped") }
        assertNull(noticeAfter.tree().steerReply(PROMPT, steer))
    }

    @Test
    fun steeredTurnOnTheServerMustBeInOrderAndAlone() {
        val steer = "Steer: end with BANANA"
        val wrapped = "[OUT-OF-BAND USER MESSAGE - a direct message from the user]\n$steer\n[/OUT-OF-BAND USER MESSAGE]"
        fun user(c: String) = JSONObject().put("role", "user").put("content", c)
        fun steerRow() = JSONObject().put("role", "user").put("display_kind", "steer").put("content", wrapped)
        fun call() = JSONObject(
            """{"role":"assistant","content":"","tool_calls":[{"id":"a","function":{"name":"terminal","arguments":"{\"command\": \"$COMMAND\"}"}}]}""",
        )
        fun final(c: String) = JSONObject().put("role", "assistant").put("finish_reason", "stop").put("content", c)
        val good = listOf(user(PROMPT), call(), steerRow(), final("Done $MARKER BANANA"))
        assertTrue(steeredTurn(good, PROMPT, COMMAND, steer, MARKER))
        // The final before the steer, a new user turn in between, or a later final of another turn do not count.
        assertFalse(steeredTurn(listOf(user(PROMPT), call(), final("Done $MARKER BANANA"), steerRow()), PROMPT, COMMAND, steer, MARKER))
        assertFalse(steeredTurn(listOf(user(PROMPT), call(), steerRow(), user("Another"), final("Done $MARKER BANANA")), PROMPT, COMMAND, steer, MARKER))
        assertFalse(steeredTurn(good + final("Something else"), PROMPT, COMMAND, steer, MARKER))
        assertFalse(steeredTurn(listOf(user(PROMPT), steerRow(), final("Done $MARKER BANANA")), PROMPT, COMMAND, steer, MARKER))
    }

    @Test
    fun terminalCommandsComeFromToolCallArguments() {
        val row = JSONObject(
            """{"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"terminal","arguments":"{\"command\": \"$COMMAND\", \"timeout\": 60}"}},
               {"id":"c2","function":{"name":"read_file","arguments":{"command":"nope"}}}]}""",
        )
        val user = JSONObject("""{"role":"user","content":"run $COMMAND"}""")
        assertEquals(listOf(COMMAND), terminalCommands(listOf(user, row)))
    }

    @Test
    fun interruptedResultMustBelongToTheCommandsOwnCall() {
        fun call(id: String, cmd: String) = JSONObject(
            """{"role":"assistant","content":"","tool_calls":[{"id":"$id","function":{"name":"terminal","arguments":"{\"command\": \"$cmd\"}"}}]}""",
        )
        fun result(id: String, exit: Int, output: String) = JSONObject().put("role", "tool").put("tool_call_id", id)
            .put("content", JSONObject().put("output", output).put("exit_code", exit).toString())
        assertTrue(interruptedCall(listOf(call("a", COMMAND), result("a", 130, "[Command interrupted]")), COMMAND))
        // Another call's interrupted result, or this call's result saying something else, does not count.
        assertFalse(interruptedCall(listOf(call("a", COMMAND), call("b", "sleep 1"), result("b", 130, "[Command interrupted]"), result("a", 0, "")), COMMAND))
        assertFalse(interruptedCall(listOf(call("a", COMMAND), result("a", 0, "not interrupted")), COMMAND))
    }

    @Test
    fun steerRowsAreUnwrapped() {
        val wrapped = "[OUT-OF-BAND USER MESSAGE - a direct message from the user]\nSteer text\n[/OUT-OF-BAND USER MESSAGE]"
        val rows = listOf(
            JSONObject().put("role", "user").put("content", "Steer text"),
            JSONObject().put("role", "user").put("display_kind", "steer").put("content", wrapped),
        )
        assertEquals(listOf<String?>("Steer text"), steers(rows))
        assertEquals(listOf<String?>(null), steers(listOf(JSONObject().put("role", "user").put("display_kind", "steer").put("content", "Steer text"))))
        assertTrue(SurvivalE2eTest.BANANA.containsMatchIn("Done apofis-e2e-6. BANANA."))
        assertFalse(SurvivalE2eTest.BANANA.containsMatchIn("Done apofis-e2e-6. NOBANANA"))
    }
    // ---- the push scenarios' decisions (PushE2eTest.kt)

    private val install = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val origin = "http://127.0.0.1:18650"

    private fun prefsXml(vararg kv: Pair<String, String>) = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
        kv.joinToString("") { (k, v) -> "    <string name=\"$k\">$v</string>\n" } + "</map>\n"

    @Test
    fun pushIdentityCountsOnlyWhenReadBackWhole() {
        val whole = prefsXml("install_id" to install, "e2e_nonce" to "0a1b2c3d", "e2e_origin" to origin)
        assertTrue(injectionConfirmed(whole, install, "0a1b2c3d", origin))
        assertFalse("an interrupted write, cut off", injectionConfirmed(whole.dropLast(8), install, "0a1b2c3d", origin))
        assertFalse("nothing written", injectionConfirmed("", install, "0a1b2c3d", origin))
        assertFalse("run-as refused", injectionConfirmed("run-as: package not debuggable", install, "0a1b2c3d", origin))
        assertFalse("another install", injectionConfirmed(whole, "1f8fad5b-d9cb-469f-a165-70867728950e", "0a1b2c3d", origin))
        assertFalse("a value missing", injectionConfirmed(prefsXml("install_id" to install, "e2e_nonce" to "0a1b2c3d"), install, "0a1b2c3d", origin))
    }

    @Test
    fun pushIntentsAreJournaledOrMissingForEveryInstall() {
        val id1 = "apofis-push-$install-1"
        val id2 = "apofis-push-$install-2"
        val intents = "$id1\t$id1 op-0123456789abcdef e2e-0a1b2c3d&#10;$id2\t$id2 op-fedcba9876543210 e2e-0a1b2c3d"
        val read = prefsXml("install_id" to install, "test_intents" to intents) + "\n@@READ\n"
        val both = listOf("a", "b")
        assertEquals(listOf("pushintent $id1 $id1 op-0123456789abcdef e2e-0a1b2c3d", "pushintent $id2 $id2 op-fedcba9876543210 e2e-0a1b2c3d"), pushIntentLines(read, both))
        assertTrue(pushIntentLines(read, both).all(PUSH_INTENT::matches))
        assertEquals("no file: nothing to export", emptyList<String>(), pushIntentLines("@@NONE\n", both))
        assertEquals("no intent yet", emptyList<String>(), pushIntentLines(prefsXml("install_id" to install) + "@@READ", both))
        val missing = listOf("pushintent-missing a", "pushintent-missing b")
        assertEquals("run-as failed", missing, pushIntentLines("run-as: unknown package\n", both))
        assertEquals("cut off before its end mark", missing, pushIntentLines(read.substringBefore("@@READ").dropLast(10), both))
        assertEquals("a malformed file", missing, pushIntentLines("<map><string name=\"test_intents\">x\n@@READ", both))
        val forged = prefsXml("test_intents" to "$id1\tapofis-push-$install-9 op-0123456789abcdef e2e-0a1b2c3d") + "@@READ"
        assertEquals("an intent whose title names another id", missing, pushIntentLines(forged, both))
    }

    @Test
    fun pushDiagnosticsNeverCarryTheBytesRead() {
        val secret = "SENTINELendpoint0123"
        val token = "SENTINELtoken4567"
        val prefs = prefsXml("install_id" to install, "endpoint" to "http://127.0.0.1:18650/$secret?up=1", "registration" to "g1\t$token")
        val reads = listOf(prefs, prefs.dropLast(20), prefs.replace("<map>", "<map><string name=\"x\">$secret"), "$secret\n$token", "")
        for (r in reads) {
            // Only a fixed category, whatever was read: no byte of it can reach the log.
            assertTrue(identityDiagnostic(r), identityDiagnostic(r) in setOf("not a whole prefs file", "values differ"))
            for (d in listOf(exportDiagnostic(r), exportDiagnostic("$r\n@@READ"))) {
                assertTrue(d, d in setOf("no prefs file", "unreadable", "malformed", "read"))
                assertFalse(d, secret in d || token in d || install in d)
            }
        }
        assertEquals("malformed", exportDiagnostic(prefs.dropLast(20) + "\n@@READ"))
    }

    @Test
    fun distributorLogsOnlyKnownActionsFromTheApp() {
        val secret = "SENTINELaction0123"
        assertEquals("distributor: REGISTER from the app", distributorLog("org.unifiedpush.android.distributor.REGISTER", "xyz.rycerz.apofis"))
        assertNull("an action from another app", distributorLog("org.unifiedpush.android.distributor.REGISTER", "com.example.$secret"))
        assertNull("no sender identity", distributorLog("org.unifiedpush.android.distributor.REGISTER", null))
        assertNull("an unknown action", distributorLog("org.unifiedpush.android.distributor.$secret", "xyz.rycerz.apofis"))
    }

    @Test
    fun quietDeliveriesNeedANewPushCheckThatTookNoRun() {
        assertTrue(quietPushChecked(listOf("cause=push runs=0 settled=0 failed=0 outcome=none", "cause=push runs=0 settled=0 failed=0 outcome=DONE")))
        assertFalse("no line at all", quietPushChecked(emptyList()))
        assertFalse("only a periodic check: the push was not seen", quietPushChecked(listOf("cause=periodic runs=0 settled=0 failed=0 outcome=DONE")))
        assertFalse("a run taken", quietPushChecked(listOf("cause=push runs=0 settled=0 failed=0 outcome=none", "cause=push runs=1 settled=0 failed=0 outcome=DONE")))
        assertFalse("a line of another form", quietPushChecked(listOf("cause=push runs=0 settled=0 failed=0")))
        assertEquals(WakeLine("periodic", 3, 1, 2), wakeLine("cause=periodic runs=3 settled=1 failed=2 outcome=RETRY"))
    }

    /** The precondition's SQL against the app's real states, on an in-memory SQLite database, through its validator. */
    @Test
    fun wakePreconditionPassesTheStoredRunsSendingRowOnly() {
        val key = "apofis-0a1b2c3d-0000-4000-8000-000000000001"
        val run = "run_0123456789abcdef0123456789abcdef"
        fun verdict(runs: List<String>, sends: List<String>, outbox: Int): String? {
            val db = android.database.sqlite.SQLiteDatabase.create(null)
            try {
                db.execSQL("CREATE TABLE ActiveRunEntity (runId TEXT PRIMARY KEY, sessionKey TEXT NOT NULL)")
                db.execSQL("CREATE TABLE PendingSendEntity (idempotencyKey TEXT PRIMARY KEY, sessionKey TEXT NOT NULL, state TEXT NOT NULL, runId TEXT)")
                db.execSQL("CREATE TABLE NoticeOutboxEntity (eventId TEXT PRIMARY KEY)")
                runs.forEach { db.execSQL("INSERT INTO ActiveRunEntity VALUES ('$it', '$key')") }
                sends.forEachIndexed { i, st -> db.execSQL("INSERT INTO PendingSendEntity VALUES ('s$i', '$key', '$st', ${if (st == "sending") "'$run'" else "NULL"})") }
                repeat(outbox) { db.execSQL("INSERT INTO NoticeOutboxEntity VALUES ('e$it')") }
                db.execSQL("INSERT INTO PendingSendEntity VALUES ('other', 'apofis-0a1b2c3d-0000-4000-8000-000000000009', 'queued', NULL)")
                return db.rawQuery(WAKE_PRECONDITION_SQL(key), null).use { c ->
                    c.moveToFirst()
                    listOf((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "" })
                }.let(::wakePrecondition)
            } finally {
                db.close()
            }
        }
        assertEquals("the stored run with its own sending row: the healthy state passes", run, verdict(listOf(run), listOf("sending"), 0))
        assertEquals(run, verdict(listOf(run), listOf("failed"), 0))
        assertNull("a queued send", verdict(listOf(run), listOf("sending", "queued"), 0))
        assertNull("a steering send", verdict(listOf(run), listOf("steering"), 0))
        assertNull("no stored run", verdict(emptyList(), emptyList(), 0))
        assertNull("two stored runs", verdict(listOf(run, "run_fedcba9876543210fedcba9876543210"), emptyList(), 0))
        assertNull("an outbox row", verdict(listOf(run), emptyList(), 1))
        assertNull("a failed query (no row)", wakePrecondition(emptyList()))
        assertNull("unexpected output", wakePrecondition(listOf("1|$run|0|0", "extra")))
        assertEquals(run, storedRun(listOf("1|$run|1|0")))
    }

    @Test
    fun pushProofNeedsOnePushSettlementAfterTheDelivery() {
        val settled = "cause=push runs=1 settled=1 failed=0 outcome=DONE"
        val quiet = "cause=push runs=1 settled=0 failed=0 outcome=DONE"
        assertTrue(provedByPush(emptyList(), listOf(settled)))
        assertTrue("a check that settled nothing does not count", provedByPush(listOf(quiet), listOf(quiet, settled)))
        assertFalse("settled by the periodic check", provedByPush(emptyList(), listOf("cause=periodic runs=1 settled=1 failed=0 outcome=DONE")))
        assertFalse("settled by a recheck", provedByPush(emptyList(), listOf("cause=recheck runs=1 settled=1 failed=0 outcome=DONE")))
        assertFalse("settled before the delivery", provedByPush(listOf(settled), listOf(settled)))
        assertFalse("two settling checks", provedByPush(emptyList(), listOf(settled, "cause=periodic runs=1 settled=1 failed=0 outcome=DONE")))
        assertFalse("no settling check", provedByPush(emptyList(), listOf(quiet)))
        assertFalse("an unparsed line", provedByPush(emptyList(), listOf(settled, "something else")))
    }

    @Test
    fun recordMatchesTheAppsOwnIntentExactly() {
        val id = "apofis-push-$install-1"
        val title = "$id op-0123456789abcdef e2e-0a1b2c3d"
        val endpoint = "http://127.0.0.1:18650/upAbC123xyz09q?up=1"
        val prefs = prefsXml("test_intents" to "$id\t$title", "record" to id, "endpoint" to endpoint)
        fun server(i: String = id, src: String = "api_server", hidden: Boolean = true, t: String = title) =
            JSONObject().put("id", i).put("source", src).put("hidden", hidden).put("title", t)
        assertTrue(recordMatches(prefs, server(), id, endpoint, current = true))
        assertFalse("another stored endpoint", recordMatches(prefs, server(), id, "http://127.0.0.1:18650/upOTHER0000000?up=1", current = true))
        assertTrue("an earlier record: its endpoint is not compared", recordMatches(prefs, server(), id, "x", current = false))
        assertFalse("another id answered", recordMatches(prefs, server(i = "apofis-push-$install-2"), id, endpoint, current = true))
        assertFalse("another source", recordMatches(prefs, server(src = "cli"), id, endpoint, current = true))
        assertFalse("visible", recordMatches(prefs, server(hidden = false), id, endpoint, current = true))
        assertFalse("another operation marker", recordMatches(prefs, server(t = "$id op-ffffffffffffffff e2e-0a1b2c3d"), id, endpoint, current = true))
        assertFalse("no intent for the id", recordMatches(prefsXml("record" to id, "endpoint" to endpoint), server(), id, endpoint, current = true))
        assertFalse("not the current record", recordMatches(prefsXml("test_intents" to "$id\t$title", "record" to "apofis-push-$install-2", "endpoint" to endpoint), server(), id, endpoint, current = true))
    }

    /** A list of [size] rows shown [window] at a time; [appearsAt]: the target row exists only from that scroll on. */
    private class FakeList(val target: Int, val appearsAt: Int, val size: Int = 10, val window: Int = 3, val boundsOnly: Boolean = false) : ScrollList<Int> {
        var offset = 0
        var scrolls = 0
        override fun find() = target.takeIf { scrolls >= appearsAt }
        override fun visible(n: Int) = n in offset until offset + window
        override fun scroll(forward: Boolean): Boolean {
            val next = if (forward) offset + 1 else offset - 1
            if (next < 0 || next + window > size) return false
            offset = next
            scrolls++
            return true
        }
        // What is shown: the rows' texts and bounds; with boundsOnly every row reads the same and only the bounds move.
        override fun shown(): Any = (offset until offset + window).map { (if (boundsOnly) "row" else "row $it") to it * 100 }
    }

    @Test
    fun revealScrollsFirstAndJudgesProgressByWhatIsShown() {
        // The section heading (row 2) is visible at once, but the row (5) does not exist until the second scroll.
        val settings = FakeList(target = 5, appearsAt = 2)
        assertEquals(5 to 3, revealIn(settings))
        // The same for a sheet action that exists only once the sheet has scrolled twice.
        assertEquals(4 to 2, revealIn(FakeList(target = 4, appearsAt = 2, size = 6)))
        // Only bounds move (every row reads the same): still progress.
        assertEquals(7 to 5, revealIn(FakeList(target = 7, appearsAt = 0, boundsOnly = true)))
        // Nothing shown changes although the scroll was accepted: that direction stops at once.
        val stuck = object : ScrollList<Int> {
            var asked = 0
            override fun find(): Int? = null
            override fun visible(n: Int) = false
            override fun scroll(forward: Boolean) = true.also { asked++ }
            override fun shown(): Any = "same"
        }
        assertEquals(null to 2, revealIn(stuck))
        assertEquals(2, stuck.asked)
        // Not found anywhere: bounded both ways.
        assertNull(revealIn(FakeList(target = 99, appearsAt = 99)).first)
    }

    @Test
    fun anObservationFinishingAfterTheDeadlineDoesNotCount() {
        var clock = 0L
        assertTrue("seen in time", observedBy(1_000, { clock }, { clock += 100 }) { clock >= 500 })
        clock = 0L
        assertFalse("seen, but the probe ended after the deadline", observedBy(1_000, { clock }, { clock += 100 }) { (clock >= 900).also { if (it) clock += 300 } })
        clock = 0L
        assertFalse("never seen", observedBy(1_000, { clock }, { clock += 100 }) { false })
        clock = 2_000L
        assertFalse("past the deadline before the first probe", observedBy(1_000, { clock }, { clock += 100 }) { true })
    }

    @Test
    fun settingsRowValueIsAWholeSegment() {
        val off = segments(listOf("Notifications, Off\nWithout push, the app checks about every 15 minutes while a reply is running"))
        assertEquals(listOf("Notifications", "Off"), off.take(2))
        val missing = segments(listOf("Notifications", "ntfy is not installed"))
        assertFalse("ntfy" in missing.drop(1))
        assertTrue("ntfy" in segments(listOf("Notifications, ntfy")).drop(1))
    }
}
