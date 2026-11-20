package xyz.rycerz.apofis.e2e

import org.json.JSONArray
import org.json.JSONObject

/** One accessibility node of a screen snapshot. [id] is the depth-first position. [resId] is the app's test tag. */
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
    val resId: String? = null,
)

/**
 * One chat item as the app tags it (`user:<key>`, `reply:<replyKey>/<row>`, `step:<replyKey>:<index>`,
 * `notice:<key>`): [kind] is user, reply or notice; a reply's steps and text rows share the reply's [key].
 */
data class Item(val kind: String, val key: String, val node: UiNode)

/**
 * A snapshot of the app's accessibility tree and the decisions the tests make from it. Pure: the self-tests feed it
 * canned screens. Chat items are identified only by the app's test tags, never by layout or by text elsewhere.
 */
class UiTree(val nodes: List<UiNode>) {
    private val byId = nodes.associateBy { it.id }

    fun children(n: UiNode) = nodes.filter { it.parent == n.id }

    /** The node's own text and the texts below it, in tree order. */
    fun texts(n: UiNode): List<String> = listOfNotNull(n.text) + children(n).flatMap(::texts)

    /** The node's own content description and those below it. */
    fun descs(n: UiNode): List<String> = listOfNotNull(n.desc) + children(n).flatMap(::descs)

    fun tagged(tag: String) = nodes.filter { it.resId == tag }

    /** Whether an approval block is on screen: its heading, by the app's own string. The harness never answers one. */
    fun approvalShown(title: String) = nodes.any { it.text == title }

    /**
     * The tagged chat items in list order. The order of the list is the only use of position: items are sorted by
     * their top edge, which in a vertical list is the order of the items themselves.
     */
    fun items(): List<Item> = nodes.mapNotNull { n ->
        val id = n.resId ?: return@mapNotNull null
        when {
            id.startsWith("user:") -> Item("user", id.removePrefix("user:"), n)
            id.startsWith("reply:") -> Item("reply", id.removePrefix("reply:").substringBefore('/'), n)
            id.startsWith("step:") -> Item("reply", id.removePrefix("step:").substringBeforeLast(':'), n)
            id.startsWith("notice:") -> Item("notice", id.removePrefix("notice:"), n)
            else -> null
        }
    }.sortedBy { it.node.top }

    /** The one user message item whose own bubble text is exactly [text]; null when absent or shown more than once. */
    fun user(text: String): Item? = items().filter { it.kind == "user" && text in texts(it.node) }.singleOrNull()

    /** The key of the reply item right after the user message [text] (no other item in between), or null. */
    fun replyAfter(text: String): String? {
        val items = items()
        val u = user(text) ?: return null
        val next = items.getOrNull(items.indexOf(u) + 1) ?: return null
        return next.key.takeIf { next.kind == "reply" }
    }

    /** The text rows of reply [key], joined; empty when none is shown. */
    fun replyText(key: String): String =
        nodes.filter { it.resId?.startsWith("reply:$key/") == true }.sortedBy { it.top }.joinToString("\n") { texts(it).joinToString("") }

    /** The reply right after the user message [text], when its text contains [marker]. */
    fun replyWith(text: String, marker: String): String? = replyAfter(text)?.takeIf { marker in replyText(it) }

    /** The steps of reply [key] in [state] that show exactly [command] as one of their own texts. */
    fun steps(key: String, state: String, command: String): List<UiNode> = nodes.filter { n ->
        val id = n.resId ?: return@filter false
        id.startsWith("step:$key:") && id.removePrefix("step:$key:").all(Char::isDigit) && n.state == state && command in texts(n)
    }
}

// ---- the server's record

fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

private fun JSONObject.toolCalls(): List<JSONObject> = (optJSONArray("tool_calls") ?: JSONArray()).let { a -> (0 until a.length()).map(a::getJSONObject) }

private fun JSONObject.arguments(): JSONObject? = optJSONObject("function")?.let { fn ->
    val args = fn.opt("arguments")
    args as? JSONObject ?: (args as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
}

private fun JSONObject.isTerminal() = optJSONObject("function")?.str("name") == "terminal"

/** The terminal commands the assistant called in [rows], from each tool call's own arguments. */
fun terminalCommands(rows: List<JSONObject>): List<String> = rows.filter { it.str("role") == "assistant" }
    .flatMap { it.toolCalls() }.filter { it.isTerminal() }.mapNotNull { it.arguments()?.str("command")?.trim() }

/**
 * True when a terminal call of exactly [command] has its own result (the tool row with that call's id) saying it was
 * interrupted: exit code 130 or Hermes' "[Command interrupted]" output.
 */
fun interruptedCall(rows: List<JSONObject>, command: String): Boolean {
    val ids = rows.filter { it.str("role") == "assistant" }.flatMap { it.toolCalls() }
        .filter { it.isTerminal() && it.arguments()?.str("command")?.trim() == command }.mapNotNull { it.str("id") }.toSet()
    return rows.any { row ->
        row.str("role") == "tool" && row.str("tool_call_id") in ids &&
            runCatching { JSONObject(row.str("content") ?: "") }.getOrNull()?.let { it.optInt("exit_code") == 130 || it.str("output") == "[Command interrupted]" } == true
    }
}

private const val STEER_OPEN = "[OUT-OF-BAND USER MESSAGE"
private const val STEER_CLOSE = "\n[/OUT-OF-BAND USER MESSAGE]"

/** The inner texts of the steer rows in [rows] (Hermes wraps each delivered steer; see docs/hermes-api-notes.md). */
fun steers(rows: List<JSONObject>): List<String?> = rows.filter { it.str("role") == "user" && it.str("display_kind") == "steer" }.map { row ->
    val c = row.str("content") ?: return@map null
    val start = c.indexOf("]\n")
    if (!c.startsWith(STEER_OPEN) || !c.endsWith(STEER_CLOSE) || start < 0 || start + 2 > c.length - STEER_CLOSE.length) null
    else c.substring(start + 2, c.length - STEER_CLOSE.length)
}

/** Final assistant answers in [rows] (the stored reply text of each turn). */
fun finals(rows: List<JSONObject>) =
    rows.filter { it.str("role") == "assistant" && it.str("finish_reason") == "stop" }.mapNotNull { it.str("content") }.filter(String::isNotBlank)
