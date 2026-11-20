package xyz.rycerz.apofis.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import xyz.rycerz.apofis.ui.chat.Work
import xyz.rycerz.apofis.ui.theme.readableOn

enum class Lang { KOTLIN, JAVA, SHELL, PYTHON, JAVASCRIPT, TYPESCRIPT, C, CPP, CSHARP, GO, RUST, RUBY, PHP, SWIFT, DART, PERL, COFFEESCRIPT }

/** The most code handed to the highlighter at once; a longer block shows unhighlighted. */
const val HIGHLIGHT_MAX_CHARS = 20000

/** The longest language label shown in a code block header (the first word of the fence's info string). */
const val LANG_MAX = 32

private val ALIASES: Map<String, Lang> = mapOf(
    Lang.KOTLIN to "kt kotlin kts", Lang.JAVA to "java", Lang.SHELL to "sh bash zsh shell console", Lang.PYTHON to "py python",
    Lang.JAVASCRIPT to "js javascript jsx mjs", Lang.TYPESCRIPT to "ts typescript tsx", Lang.C to "c h",
    Lang.CPP to "cpp c++ cc hpp cxx", Lang.CSHARP to "cs csharp", Lang.GO to "go golang", Lang.RUST to "rs rust",
    Lang.RUBY to "rb ruby", Lang.PHP to "php", Lang.SWIFT to "swift", Lang.DART to "dart", Lang.PERL to "pl perl",
    Lang.COFFEESCRIPT to "coffee coffeescript",
).flatMap { (lang, names) -> names.split(' ').map { it to lang } }.toMap()

/** The language of a fence's info string (case-insensitive, trimmed, text after the first space ignored); null = plain. */
internal fun languageOf(lang: String): Lang? = ALIASES[lang.trim().substringBefore(' ').lowercase()]

/**
 * The fence label for the info string text[from, end): its first word, at most [LANG_MAX] characters, never a split
 * surrogate pair and never a control character, so a long or strange info string never reaches layout.
 */
internal fun infoWord(text: CharSequence, from: Int, end: Int): String {
    var a = from
    while (a < end && text[a].isWhitespace()) a++
    var b = a
    while (b < end && b - a < LANG_MAX && !text[b].isWhitespace() && !text[b].isISOControl()) b++
    if (b > a && Character.isHighSurrogate(text[b - 1])) b--
    return text.substring(a, b)
}

/** The theme's syntax roles; the scanner produces [KEYWORD], [STRING], [LITERAL] (numbers) and [COMMENT]. */
internal enum class Tone { CODE, KEYWORD, STRING, LITERAL, COMMENT, METADATA, PUNCTUATION, MARK }

// In Tone order: GitHub (light) and Atom One Dark (dark).
private val LIGHT = longArrayOf(0xFF24292E, 0xFFD73A49, 0xFF032F62, 0xFF005CC5, 0xFF6A737D, 0xFF6F42C1, 0xFF24292E, 0xFFE36209)
private val DARK = longArrayOf(0xFFABB2BF, 0xFFC678DD, 0xFF98C379, 0xFFD19A66, 0xFF5C6370, 0xFF61AEEF, 0xFFABB2BF, 0xFFE06C75)

/** The syntax colors of a theme, in [Tone] order, each shifted toward [onSurface] until it reads 4.5:1 on [codeFill]. */
internal fun syntaxColors(dark: Boolean, codeFill: Color, onSurface: Color): List<Color> =
    (if (dark) DARK else LIGHT).map { readableOn(Color(it), codeFill, onSurface) }

private const val TAG = "syntax"

/** [tokens] (or plain text) colored with [colors] from [syntaxColors]. */
internal fun styled(tokens: AnnotatedString, colors: List<Color>): AnnotatedString = AnnotatedString.Builder(tokens.length).run {
    append(tokens.text)
    for (t in tokens.getStringAnnotations(TAG, 0, tokens.length)) addStyle(SpanStyle(color = colors[Tone.valueOf(t.item).ordinal]), t.start, t.end)
    toAnnotatedString()
}

/** [code] highlighted for the fence language [lang] in one theme; plain when the language is unknown or the code too long. */
internal fun highlight(code: String, lang: String, dark: Boolean, codeFill: Color, onSurface: Color): AnnotatedString =
    styled(languageOf(lang)?.let { tokens(code, it) } ?: AnnotatedString(code), syntaxColors(dark, codeFill, onSurface))

/**
 * [code] with its tokens marked by theme-free [Tone] annotations (rows outlive a theme change, so colors are applied
 * when drawn, by [styled]), or null when [code] is over [HIGHLIGHT_MAX_CHARS]. One forward pass: every character is
 * looked at a bounded number of times. Total: anything unexpected falls back to plain text (null) and is counted.
 */
internal fun tokens(code: CharSequence, lang: Lang): AnnotatedString? {
    if (code.length > HIGHLIGHT_MAX_CHARS) return null
    Work.highlights++
    Work.largestHighlight = maxOf(Work.largestHighlight, code.length)
    return try {
        scan(code.toString(), SYNTAX.getValue(lang), lang).takeIf { it.text.length == code.length } ?: run {
            Work.highlightFailures++
            null
        }
    } catch (_: RuntimeException) {
        Work.highlightFailures++
        null
    }
}

/** What the scanner needs to know about a language. */
private class Syntax(
    val slashes: Boolean, // `//` line comments and `/* */` block comments
    val hash: Boolean, // `#` line comments
    val hashAfterSpace: Boolean, // ... only at a word start (shell, Perl: `$#`, `${#x}` are not comments)
    val triple: Boolean, // `"""` and `'''` strings
    val backtick: Boolean, // backtick strings, which may span lines
    val charQuote: Boolean, // `'` only quotes a short character literal (Rust lifetimes are `'a`)
    words: String,
) {
    val keywords = words.split(' ').toHashSet()
}

private val SYNTAX: Map<Lang, Syntax> = run {
    fun c(words: String, triple: Boolean = false, backtick: Boolean = false, charQuote: Boolean = false) =
        Syntax(slashes = true, hash = false, hashAfterSpace = false, triple, backtick, charQuote, words)
    fun h(words: String, triple: Boolean = false, afterSpace: Boolean = false) =
        Syntax(slashes = false, hash = true, hashAfterSpace = afterSpace, triple, backtick = false, charQuote = false, words)
    val js = "async await break case catch class const continue default delete do else export extends false finally for " +
        "function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while yield"
    mapOf(
        Lang.KOTLIN to c(
            "abstract as break class companion const continue data do else enum false for fun if import in " +
                "interface internal is null object open override package private protected public return sealed super " +
                "suspend this throw true try val var when while",
            triple = true,
        ),
        Lang.JAVA to c(
            "abstract boolean break case catch char class continue default double else enum extends false final " +
                "finally for if implements import instanceof int interface long new null package private protected " +
                "public return static super switch this throw true try void while",
            triple = true,
        ),
        Lang.SHELL to h(
            "alias case cd do done echo elif else esac exit export fi for function if in local read return set shift " +
                "source then unset until while",
            afterSpace = true,
        ),
        Lang.PYTHON to h(
            "and as assert async await break class continue def del elif else except False finally for from global if " +
                "import in is lambda None nonlocal not or pass raise return self True try while with yield",
            triple = true,
        ),
        Lang.JAVASCRIPT to c(js, backtick = true),
        Lang.TYPESCRIPT to c(
            "abstract any as async await boolean break case catch class const continue default else enum export extends " +
                "false for from function if implements import in interface let new null number private public readonly " +
                "return string this true type void while",
            backtick = true,
        ),
        Lang.C to c(
            "auto break case char const continue default do double else enum extern float for goto if inline int long " +
                "register return short signed sizeof static struct switch typedef union unsigned void volatile while NULL",
        ),
        Lang.CPP to c(
            "auto bool break case catch char class const continue default delete do else enum false " +
                "float for if int long namespace new nullptr private protected public return static struct switch " +
                "template this throw true try typename using virtual void while",
        ),
        Lang.CSHARP to c(
            "abstract as async await base bool break case catch class const continue default else enum false " +
                "for foreach if in int interface namespace new null override private public return " +
                "static string this throw true try using var void while",
        ),
        Lang.GO to c(
            "break case chan const continue default defer else fallthrough false for func go goto if import interface " +
                "iota map nil package range return select struct switch true type var",
            backtick = true,
        ),
        Lang.RUST to c(
            "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod " +
                "move mut pub ref return self Self static struct super trait true type unsafe use where while",
            charQuote = true,
        ),
        Lang.RUBY to h(
            "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not " +
                "or redo rescue retry return self super then true undef unless until when while yield",
        ),
        Lang.PHP to c(
            "abstract array as break case catch class const continue default do echo else elseif extends false final " +
                "finally fn for foreach function if implements interface namespace new null private protected public " +
                "return static switch this throw true try use while",
        ),
        Lang.SWIFT to c(
            "as break case catch class continue default defer do else enum extension false fileprivate for func guard " +
                "if import in init internal let nil private protocol public repeat return self static struct switch " +
                "throw throws true try var where while",
            triple = true,
        ),
        Lang.DART to c(
            "abstract async await break case catch class const continue default do dynamic else enum extends false " +
                "final finally for if implements import in is late new null required return static super switch this " +
                "throw true try var void while with",
            triple = true,
        ),
        Lang.PERL to h(
            "and cmp continue do else elsif eq for foreach ge gt if last le local lt my ne next no not or our package " +
                "print redo require return sub unless until use while",
            afterSpace = true,
        ),
        Lang.COFFEESCRIPT to h(
            "and break by catch class continue else extends false finally for if in is isnt loop new no not null of off " +
                "on or return super switch then this throw true try unless until when while yes",
            triple = true,
        ),
    )
}

/** Keywords of [lang]; for tests. */
internal fun keywords(lang: Lang): Set<String> = SYNTAX.getValue(lang).keywords

private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

/**
 * Ordinary `'` and `"` strings honour backslash escapes and end at their line (an unterminated one colors no more than
 * that line). Raw forms end only at their own closer: Go backtick strings, shell single quotes, Rust `r"..."` and
 * `r#"..."#`, Dart `r'...'`, C# `@"..."` (where `""` is a quote), and triple-quoted strings. Python's `r"..."` keeps
 * the backslash rule, as Python does (`r"a\"b"` is one string).
 */
private fun scan(code: String, syn: Syntax, lang: Lang): AnnotatedString {
    val out = AnnotatedString.Builder(code.length)
    out.append(code)
    val n = code.length
    var steps = 0L
    fun mark(tone: Tone, start: Int, end: Int) = out.addStringAnnotation(TAG, tone.name, start, end)
    fun lineEnd(from: Int) = code.indexOf('\n', from).let { if (it < 0) n else it }
    fun closer(delimiter: String, from: Int) = code.indexOf(delimiter, from).let { if (it < 0) n else it + delimiter.length }
    // The end of the string quoted at [start] whose content starts at [from]; [multiline]: it may span lines.
    fun quoteEnd(start: Int, from: Int, limit: Int, escapes: Boolean, multiline: Boolean): Int {
        val q = code[start]
        var k = from
        while (k < limit) {
            val c = code[k]
            if (c == q) return k + 1
            if (c == '\n' && !multiline) return k
            k += if (c == '\\' && escapes) 2 else 1
        }
        return minOf(k, n)
    }
    // Rust `r"..."`, `r#"..."#` or `br"..."` at [i]: its end, or -1 when it is not one.
    fun rustRawEnd(i: Int): Int {
        var k = i + (if (code[i] == 'b') 2 else 1)
        val hashes = k
        while (k < n && code[k] == '#') k++
        steps += k - i
        if (k >= n || code[k] != '"') return -1
        return closer("\"" + "#".repeat(k - hashes), k + 1)
    }
    // A JS regex literal at [i] (after an operator or an opening bracket): its end, or -1. Reads at most 256 characters.
    fun regexEnd(i: Int): Int {
        var p = i - 1
        while (p >= 0 && i - p <= 64 && (code[p] == ' ' || code[p] == '\t')) p--
        if (p >= 0 && code[p] != '\n' && code[p] !in "(,=:[!&|?{};") return -1
        var k = i + 1
        var inClass = false
        val limit = minOf(n, i + 256)
        while (k < limit && code[k] != '\n') {
            when (code[k]) {
                '\\' -> k++
                '[' -> inClass = true
                ']' -> inClass = false
                '/' -> if (!inClass) return k + 1
            }
            k++
        }
        steps += k - i + i - p
        return -1
    }
    var i = 0
    while (i < n) {
        val c = code[i]
        val next = if (i + 1 < n) code[i + 1] else '\u0000'
        val start = i
        var regex = -1
        when {
            syn.slashes && c == '/' && next == '*' -> {
                i = closer("*/", i + 2)
                mark(Tone.COMMENT, start, i)
            }
            (syn.slashes && c == '/' && next == '/') ||
                (syn.hash && c == '#' && (!syn.hashAfterSpace || i == 0 || code[i - 1].isWhitespace() || code[i - 1] == ';')) -> {
                i = lineEnd(i)
                mark(Tone.COMMENT, start, i)
            }
            (lang == Lang.JAVASCRIPT || lang == Lang.TYPESCRIPT) && c == '/' && regexEnd(i).also { regex = it } > 0 -> i = regex
            syn.triple && (c == '"' || c == '\'') && next == c && i + 2 < n && code[i + 2] == c -> {
                i = closer(code.substring(i, i + 3), i + 3)
                mark(Tone.STRING, start, i)
            }
            lang == Lang.RUST && (c == 'r' || (c == 'b' && next == 'r')) && rustRawEnd(i).also { regex = it } > 0 -> {
                i = regex
                mark(Tone.STRING, start, i)
            }
            lang == Lang.DART && c == 'r' && (next == '"' || next == '\'') -> {
                val q = next.toString()
                i = if (code.startsWith(q.repeat(3), i + 1)) closer(q.repeat(3), i + 4) else quoteEnd(i + 1, i + 2, n, escapes = false, multiline = false)
                mark(Tone.STRING, start, i)
            }
            lang == Lang.CSHARP && c == '@' && next == '"' -> { // verbatim: "" is a quote, no escapes, may span lines
                i += 2
                while (i < n && !(code[i] == '"' && (i + 1 >= n || code[i + 1] != '"'))) i += if (code[i] == '"') 2 else 1
                i = minOf(n, i + 1)
                mark(Tone.STRING, start, i)
            }
            c == '"' || (c == '\'' && !syn.charQuote) || (c == '`' && syn.backtick) -> {
                val raw = (c == '`' && lang == Lang.GO) || (c == '\'' && lang == Lang.SHELL)
                i = quoteEnd(i, i + 1, n, escapes = !raw, multiline = c == '`')
                mark(Tone.STRING, start, i)
            }
            c == '\'' -> { // a character literal: 'a', a surrogate pair, or an escape like '\n'; else a lifetime ('a)
                val e = when {
                    next == '\\' -> quoteEnd(i, i + 1, minOf(n, i + 12), escapes = true, multiline = false).takeIf { it > i + 2 && code[it - 1] == '\'' }
                    next != '\n' && i + 2 < n && code[i + 2] == '\'' -> i + 3
                    Character.isHighSurrogate(next) && i + 3 < n && code[i + 3] == '\'' -> i + 4
                    else -> null
                }
                if (e != null) {
                    i = e
                    mark(Tone.STRING, start, i)
                } else {
                    steps += minOf(12, n - i) // the look ahead, read again from i + 1
                    i++
                }
            }
            c in '0'..'9' -> {
                i++
                while (i < n) {
                    val d = code[i]
                    val more = d.isAsciiWord() || (d == '.' && i + 1 < n && code[i + 1] in '0'..'9') ||
                        (d == '\'' && lang == Lang.CPP && i + 1 < n && code[i + 1].isAsciiWord()) // C++ 1'000
                    if (!more) break
                    i++
                }
                mark(Tone.LITERAL, start, i)
            }
            c.isLetter() || c == '_' -> {
                i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                if (i - start <= 16 && code.substring(start, i) in syn.keywords) mark(Tone.KEYWORD, start, i)
            }
            else -> i++
        }
        steps += i - start
    }
    Work.highlightSteps += steps
    return out.toAnnotatedString()
}
