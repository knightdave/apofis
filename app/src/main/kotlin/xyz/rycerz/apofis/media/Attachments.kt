package xyz.rycerz.apofis.media

import xyz.rycerz.apofis.BuildConfig
import xyz.rycerz.apofis.ui.chat.lf

enum class AttachmentKind { PICTURE, FILE }

data class Attachment(val path: String, val kind: AttachmentKind)

data class AttachmentScan(val attachments: List<Attachment>, val tagOnly: Boolean)

/**
 * The file paths an assistant reply's stored [text] names, by the line grammar of spec 6.1: `MEDIA:` tag lines and bare
 * picture paths in prose, in order of first appearance, at most [MAX_ATTACHMENTS]. Presentation only: it authorizes
 * nothing on the server.
 */
fun scanAttachments(text: String): AttachmentScan {
    val found = LinkedHashMap<String, AttachmentKind>()
    var fence = false
    var nonBlank = 0
    var tagAdded = false
    val t = lf(text)
    var start = 0
    while (start <= t.length) { // line by line, so nothing after the 16th path is read; a final LF leaves an empty line
        val end = t.indexOf('\n', start).let { if (it < 0) t.length else it }
        if (BuildConfig.DEBUG) attachmentScanSteps += end - start + 1
        val line = t.substring(start, end)
        start = end + 1
        if (line.any { !isWs(it) }) nonBlank++
        // 1. Fence, stripped as the block parser strips (Char.isWhitespace, which includes U+00A0).
        val f = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
        val backticks = line.startsWith(FENCE, f)
        if (fence) {
            if (backticks && (f + FENCE.length until line.length).all { line[it].isWhitespace() }) fence = false
            continue
        }
        if (backticks) { fence = true; continue }
        // 2. Length.
        if (line.length > MAX_LINE) continue
        // 3. Marker, from the line stripped of WS only.
        val i = afterMarker(line, skipWs(line, 0))
        // 4. Quote and shell prompt.
        if (isQuoteOrShell(line, i)) continue
        // 5. Tag line: it owns the whole line, also when malformed.
        if (line.startsWith(TAG, i)) {
            val c = tagCandidate(line.substring(i + TAG.length))
            if (c != null && !c.contains(TAG)) kindOf(c, spaces = true)?.let { if (found.putIfAbsent(c, it) == null) tagAdded = true }
        } else {
            prose(line, i) { path -> found.putIfAbsent(path, AttachmentKind.PICTURE); found.size < MAX_ATTACHMENTS }
        }
        // 7. Stop. A tag line adds at most one path, so a text that reaches 16 is never tag-only.
        if (found.size >= MAX_ATTACHMENTS) return AttachmentScan(found.attachments(), false)
    }
    return AttachmentScan(found.attachments(), nonBlank == 1 && tagAdded)
}

/** Characters read for lines, the backtick index and closer searches. Debug builds only, for the work-bound tests. */
internal var attachmentScanSteps = 0L

private const val MAX_ATTACHMENTS = 16
private const val MAX_LINE = 4096
private const val MAX_PATH = 1024
private const val TAG = "MEDIA:"
private const val FENCE = "```"
private const val MASK = '\u0000'
private const val PUNCT = ".,;:!?"
private val PICTURE_SUFFIXES = setOf("png", "jpg", "jpeg", "gif", "webp")

private fun Map<String, AttachmentKind>.attachments() = map { (path, kind) -> Attachment(path, kind) }

private fun isWs(c: Char) = c == ' ' || c == '\t'

private fun isBoundary(c: Char) = isWs(c) || c == '|'

private fun skipWs(s: String, from: Int): Int {
    var k = from
    while (k < s.length && isWs(s[k])) k++
    return k
}

/** Past one list marker at [i] (`-`, `*`, `+`, or one to nine digits then `.` or `)`) and the WS after it, else [i]. */
private fun afterMarker(s: String, i: Int): Int {
    var j = i
    if (j < s.length && s[j] in "-*+") {
        j++
    } else {
        while (j < s.length && s[j] in '0'..'9') j++
        if (j - i !in 1..9 || j >= s.length || s[j] !in ".)") return i
        j++
    }
    return if (j < s.length && isWs(s[j])) skipWs(s, j) else i
}

/** Whether the line from [i] is a quote (`>`) or starts with a shell prompt (`$`, `user@host:~$ `). */
private fun isQuoteOrShell(s: String, i: Int): Boolean {
    if (s.startsWith(">", i) || (i == s.length - 1 && s[i] == '$')) return true
    var j = i
    while (j < s.length && !isWs(s[j])) j++
    return j < s.length && j - i in 1..64 && s[j - 1] == '$'
}

/** The tag's candidate path from [rest], the line after `MEDIA:`, or null when its quoted form is malformed. */
private fun tagCandidate(rest: String): String? {
    var a = 0
    var b = rest.length
    while (a < b && isWs(rest[a])) a++
    while (b > a && isWs(rest[b - 1])) b--
    val r = rest.substring(a, b)
    val q = r.firstOrNull()
    if (q == '"' || q == '`') return if (r.length >= 2 && r.indexOf(q, 1) == r.length - 1) r.substring(1, r.length - 1) else null
    return if (r.isNotEmpty() && r.last() in PUNCT) r.dropLast(1) else r
}

/** The kind of [p] when it passes the path test of spec 6.1, else null. U+0020 is allowed only when [spaces]. */
private fun kindOf(p: String, spaces: Boolean): AttachmentKind? {
    if (p.length !in 2..MAX_PATH || p[0] != '/' || p[1] == '/') return null
    var k = 0
    while (k < p.length) {
        val cp = p.codePointAt(k)
        if (refused(cp) && !(spaces && cp == ' '.code)) return null
        k += Character.charCount(cp)
    }
    val name = p.substring(p.lastIndexOf('/') + 1)
    val dot = name.lastIndexOf('.')
    val suffix = name.substring(dot + 1)
    if (dot < 1 || suffix.length !in 1..8 || !suffix.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return null
    return if (suffix.lowercase() in PICTURE_SUFFIXES) AttachmentKind.PICTURE else AttachmentKind.FILE
}

private fun refused(cp: Int): Boolean = when (cp) {
    '"'.code, '`'.code, 0x115F, 0x1160, 0x3164, 0xFFA0 -> true
    in 0x00..0x1F, in 0x7F..0x9F -> true
    in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code -> true // a paired surrogate arrives as one code point above them
    else -> when (Character.getType(cp).toByte()) {
        Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }
}

/**
 * Step 6: the line from [i] with each code, quoted and link span masked to one [MASK], split at WS and `|`. [add] gets
 * each bare PICTURE path and each whole-path span, in order, and returns false to stop. Linear: the backtick runs are
 * indexed once, and a quote or link search that finds no closer is remembered, so no later opener of that kind searches.
 */
private fun prose(line: String, i: Int, add: (String) -> Boolean) {
    val m = StringBuilder(line.length - i)
    val spans = HashMap<Int, String>() // index in m of a whole-path span's MASK, to its path
    val closer = HashMap<Int, Int>() // start of a maximal backtick run, to the end of the next run of the same length
    val lastOfLength = HashMap<Int, Int>() // run length, to the start of the latest run of that length
    var k = i
    while (k < line.length) {
        if (line[k] != '`') { k++; continue }
        val e = runEnd(line, k)
        lastOfLength.put(e - k, k)?.let { closer[it] = e }
        k = e
    }
    if (BuildConfig.DEBUG) attachmentScanSteps += line.length - i
    var noQuote = false
    var noSingle = false
    var noParen = false
    var p = i
    while (p < line.length) {
        val c = line[p]
        var spanEnd = -1 // where a span ends, or -1 for plain text
        var inner: String? = null // a code or quoted span's content
        when {
            c == '`' -> {
                val run = runEnd(line, p)
                val close = closer[p]
                if (close == null) { m.append(line, p, run); p = run; continue }
                spanEnd = close; inner = line.substring(run, close - (run - p))
            }
            c == '"' && !noQuote -> {
                val close = line.indexOf('"', p + 1)
                if (BuildConfig.DEBUG) attachmentScanSteps += (if (close < 0) line.length else close) - p
                if (close < 0) noQuote = true else { spanEnd = close + 1; inner = line.substring(p + 1, close) }
            }
            c == '\'' && !noSingle && (p == 0 || isBoundary(line[p - 1]) || line[p - 1] == '(') -> {
                var close = p + 1
                while (close < line.length && !(line[close] == '\'' && (close + 1 == line.length || isBoundary(line[close + 1]) || line[close + 1] in PUNCT))) close++
                if (BuildConfig.DEBUG) attachmentScanSteps += close - p
                if (close == line.length) noSingle = true else { spanEnd = close + 1; inner = line.substring(p + 1, close) }
            }
            c == ']' && !noParen && line.startsWith("](", p) -> {
                val close = line.indexOf(')', p + 2)
                if (BuildConfig.DEBUG) attachmentScanSteps += (if (close < 0) line.length else close) - p
                if (close < 0) noParen = true else spanEnd = close + 1 // a link target is never a whole-path span
            }
        }
        if (spanEnd < 0) { m.append(c); p++; continue }
        if (inner != null && '|' !in inner && kindOf(inner, spaces = false) == AttachmentKind.PICTURE) spans[m.length] = inner
        m.append(MASK)
        p = spanEnd
    }
    var a = 0
    while (a < m.length) {
        var b = a
        while (b < m.length && !isBoundary(m[b])) b++
        val next = b + 1
        if (b > a && m[b - 1] in PUNCT) b--
        // A whole-path span counts only as a token of its own: a BOUNDARY before it, and after it one too, or one PUNCT.
        val path = if (b - a == 1 && m[a] == MASK) spans[a] else m.substring(a, b).takeIf { kindOf(it, spaces = false) == AttachmentKind.PICTURE }
        if (path != null && !add(path)) return
        a = next
    }
}

private fun runEnd(s: String, from: Int): Int {
    var k = from
    while (k < s.length && s[k] == '`') k++
    return k
}
