package xyz.rycerz.apofis.e2e

import kotlin.math.abs

/** One accessibility node of a screen snapshot. [id] is the depth-first position, so siblings keep screen order. */
data class UiNode(
    val id: Int,
    val parent: Int, // -1 for a window root
    val text: String? = null,
    val desc: String? = null,
    val state: String? = null,
    val cls: String? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val heading: Boolean = false,
    val liveRegion: Int = 0,
    val editable: Boolean = false,
)

sealed interface Approval {
    data object None : Approval
    data class Allow(val button: UiNode) : Approval
    data class Refuse(val why: String, val deny: UiNode?) : Approval
}

/**
 * A snapshot of the app's accessibility tree and the decisions the tests make from it. Pure: the self-tests feed it
 * canned screens. [dp] is the screen density in pixels per dp.
 */
class UiTree(val nodes: List<UiNode>, private val dp: Float) {
    private val byId = nodes.associateBy { it.id }

    fun children(n: UiNode) = nodes.filter { it.parent == n.id }
    fun parentOf(n: UiNode) = byId[n.parent]
    fun ancestors(n: UiNode) = generateSequence(parentOf(n)) { parentOf(it) }

    /** The node's own text and the texts below it, in screen order. */
    fun texts(n: UiNode): List<String> = listOfNotNull(n.text) + children(n).flatMap(::texts)

    /** What a button shows: its own text or content description, else its children's texts. */
    fun label(n: UiNode): String = n.text ?: n.desc ?: texts(n).joinToString(" ")

    /**
     * The approval block, judged by its own elements: the block's heading, the command field right after it, then its
     * Deny and Allow once buttons, all siblings in that order. Allow once is offered only when that command field
     * holds exactly [command]; text anywhere else on the screen (such as the prompt naming the command) is ignored.
     * Anything unexpected is refused, with the block's Deny button when there is one.
     */
    fun approval(title: String, denyLabel: String, onceLabel: String, command: String): Approval {
        val heads = nodes.filter { it.text == title }
        if (heads.isEmpty()) return Approval.None
        if (heads.size > 1) return Approval.Refuse("${heads.size} approval blocks at once", null)
        val head = heads.single()
        val parent = parentOf(head) ?: return Approval.Refuse("the approval heading has no container", null)
        val after = children(parent).dropWhile { it != head }.drop(1)
        val deny = after.firstOrNull { label(it) == denyLabel && it.clickable }
        val field = after.firstOrNull() ?: return Approval.Refuse("no command field after the heading", deny)
        val shown = texts(field)
        if (shown.size != 1 || field.clickable) return Approval.Refuse("unrecognised command field $shown", deny)
        if (deny == null) return Approval.Refuse("no Deny button in the block", null)
        val once = after.dropWhile { it != deny }.drop(1).firstOrNull()
        if (once == null || label(once) != onceLabel || !once.clickable || !once.enabled) return Approval.Refuse("no enabled Allow once right after Deny", deny)
        if (field.top < head.bottom || deny.top < field.bottom || once.top < field.bottom) return Approval.Refuse("the block's elements are out of order", deny)
        if (shown.single() != command) return Approval.Refuse("it asks for \"${shown.single()}\", not \"$command\"", deny)
        return Approval.Allow(once)
    }

    /** The user's own message bubbles showing exactly [text]. */
    fun bubbles(text: String) = nodes.filter { it.text == text && !it.editable }

    /**
     * Assistant reply paragraphs below the user message [after] that contain [marker]. A reply paragraph is a plain
     * text node laid out 20 dp from both edges of the message list (the app's reply text block); a user bubble, a
     * tool or reasoning step, a caption, a status notice, a date heading and the composer all fail that test.
     */
    fun replies(marker: String, after: UiNode): List<UiNode> = nodes.filter { n ->
        val t = n.text ?: return@filter false
        val list = parentOf(n) ?: return@filter false
        marker in t && n.cls == "android.widget.TextView" && !n.clickable && !n.editable && !n.heading && n.liveRegion == 0 &&
            n.state == null && ancestors(n).none { it.clickable || it.state != null } && n.top >= after.bottom &&
            near(n.left - list.left, 20) && near(list.right - n.right, 20)
    }

    /** Steps showing [state] whose own texts contain [command]. */
    fun steps(state: String, command: String) = nodes.filter { it.state == state && texts(it).any { t -> command in t } }

    private fun near(px: Int, dps: Int) = abs(px - dps * dp) <= 3
}
