package xyz.rycerz.apofis.ui.chat

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.rycerz.apofis.api.ApprovalChoice

/**
 * Every outgoing action of the chat goes through the one covered gate. Enumerated, so a new [ChatActions] method or a
 * new callback parameter of [ChatScreen] fails here until it is gated or listed as harmless with its reason.
 */
class ChatGateTest {
    /**
     * Exempt, passed straight on: typing (dropping it could lose text) and the six confirm and dismiss callbacks of the
     * confirmation dialogs, which are their own windows above the drawer, seen whole and answered deliberately.
     */
    private val notActions = setOf(
        "onDraftChange",
        "onConfirmRetry", "onDismissRetry", "onConfirmCancel", "onDismissCancel", "onConfirmDiscard", "onDismissDiscard",
    )

    private val methods: List<Method> = ChatActions::class.java.methods.filter { it.declaringClass == ChatActions::class.java && !Modifier.isStatic(it.modifiers) }

    private fun recorder(calls: MutableList<String>): ChatActions = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ChatActions::class.java)) { _, m, _ ->
        calls += m.name
        if (m.returnType == java.lang.Boolean.TYPE) false else null
    } as ChatActions

    private fun args(m: Method): Array<Any?> = m.parameterTypes.map {
        when (it) {
            String::class.java -> "x"
            java.lang.Long.TYPE -> 0L
            java.lang.Boolean.TYPE -> false
            ApprovalChoice::class.java -> ApprovalChoice.ONCE
            else -> null
        }
    }.toTypedArray()

    private fun callAll(covered: Boolean): List<String> {
        val calls = ArrayList<String>()
        val gated = CoveredActions({ recorder(calls) }, Gate { covered })
        for (m in methods) m.invoke(gated, *args(m))
        return calls
    }

    @Test
    fun everyActionIsDroppedWhileCovered() {
        assertEquals(notActions, callAll(covered = true).toSet())
    }

    /** Uncovered, every method reaches the delegate once: a method left to the interface default would swallow it. */
    @Test
    fun everyActionPassesWhenUncovered() {
        assertEquals(methods.map { it.name }.sorted(), callAll(covered = false).sorted())
    }

    /** Send's acceptance: covered gives false without calling the delegate; uncovered passes the delegate's own result. */
    @Test
    fun sendResultPassesThroughOnlyWhenUncovered() {
        for (result in listOf(true, false)) {
            var calls = 0
            val delegate = object : ChatActions {
                override fun onSendClick(): Boolean {
                    calls++
                    return result
                }
            }
            assertEquals(false, CoveredActions({ delegate }, Gate { true }).onSendClick())
            assertEquals(0, calls)
            assertEquals(result, CoveredActions({ delegate }, Gate { false }).onSendClick())
            assertEquals(1, calls)
        }
    }

    /**
     * ChatScreen's callback parameters: onOpenConnection and onRetryLoad (gated); navigationIcon and appBarActions (slots
     * whose buttons use LocalChatGate: DrawerButton, NewChatButton); currentState (a read-only supplier, not an action);
     * coveredNow (the gate's own live read).
     */
    @Test
    fun everyCallbackOfChatScreenIsAccountedFor() {
        val screen = Class.forName("xyz.rycerz.apofis.ui.chat.ChatScreenKt").methods.single { it.name == "ChatScreen" }
        assertEquals(6, screen.parameterTypes.count { it.name.startsWith("kotlin.jvm.functions.Function") })
    }
}
