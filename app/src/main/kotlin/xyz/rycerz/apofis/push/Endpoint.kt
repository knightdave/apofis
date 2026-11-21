package xyz.rycerz.apofis.push

private const val MAX_URL = 512
private val URL_CHARS = Regex("[!#-\\[\\]-~]*") // printable ASCII without space, quote or backslash
private val PATH = Regex("/up[A-Za-z0-9_-]{12}")

/** The URL rebuilt from its checked parts, or the reason code of the first failed check (codes as in apofis_push.py). */
internal data class EndpointCheck(val url: String?, val reason: String?)

/**
 * The one endpoint rule shared with the hook script's `validate_endpoint` (vectors in server/apofis-push/vectors.json):
 * https with an origin in [origins], or http only for exactly [loopback]; no user info or fragment; path `/up` plus 12
 * characters; query empty or exactly `up=1`; at most 512 characters.
 */
internal fun checkEndpoint(url: String, origins: Set<String>, loopback: String?): EndpointCheck {
    fun refuse(reason: String) = EndpointCheck(null, reason)
    if (url.length > 4 * MAX_URL || url.codePointCount(0, url.length) > MAX_URL) return refuse("length") // code points, as Python counts
    if (!URL_CHARS.matches(url)) return refuse("parse")
    val sep = url.indexOf("://")
    val scheme = if (sep < 0) "" else url.substring(0, sep)
    if (scheme != "https" && (scheme != "http" || loopback == null)) return refuse("scheme")
    val rest = url.substring(sep + 3)
    val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    if ('@' in authority) return refuse("userinfo")
    val origin = "$scheme://$authority"
    if (if (scheme == "https") origin !in origins else origin != loopback) return refuse("origin")
    val tail = rest.substring(authority.length)
    if ('#' in tail) return refuse("fragment")
    val path = tail.substringBefore('?')
    if (!PATH.matches(path)) return refuse("path")
    val hasQuery = '?' in tail
    if (hasQuery && tail.substringAfter('?') != "up=1") return refuse("query")
    return EndpointCheck(origin + path + (if (hasQuery) "?up=1" else ""), null)
}

/** The canonical endpoint URL, or null when [url] breaks the rule. */
fun validateEndpoint(url: String, origins: Set<String>, loopback: String?): String? = checkEndpoint(url, origins, loopback).url
