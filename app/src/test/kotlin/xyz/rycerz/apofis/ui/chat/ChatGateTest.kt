package xyz.rycerz.apofis.ui.chat

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.rycerz.apofis.api.ApprovalChoice

/**
 * The view model's side of the covered gate, by reflection over two surfaces: every [ChatActions] method passes
 * [CoveredActions] (dropped while covered, passed once uncovered, the exempt ones listed with their reason), and the
 * number of function parameters of [ChatScreen] is pinned, so a new callback fails here until it is accounted for. It
 * does not see the screen's own launchers (tool sheet, Select, links, the scroll button, Retry, Connection, a long
 * command's reader): a missing gate there passes this test. [ChatLaunchersGateTest] taps those on the hosted screen.
 */
class ChatGateTest {
    /**
     * Exempt, passed straight on: typing (dropping it could lose text), the six confirm and dismiss callbacks of the
     * confirmation dialogs, which are their own windows above the drawer, seen whole and answered deliberately, and the
     * choice sheets' dismissal, the sheet being its own window above the drawer too, and the picker's result
     * ([ChatActions.onImagesPicked]), which comes from a window above the chat like the dialogs' confirms: dropping it
     * would lose the user's pick.
     */
    private val notActions = setOf(
        "onDraftChange", "onImagesPicked",
        "onConfirmRetry", "onDismissRetry", "onConfirmCancel", "onDismissCancel", "onConfirmDiscard", "onDismissDiscard",
        "closeSheet",
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
            List::class.java -> emptyList<Any>()
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
