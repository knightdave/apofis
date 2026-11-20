package xyz.rycerz.apofis.ui.connect

/**
 * Android 17 blocks apps that target it from the local network unless the user grants this runtime permission
 * (developer.android.com/privacy-and-security/local-network-permission). Only API 37 and later know it.
 */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
const val LOCAL_NETWORK_MIN_SDK = 37

private val IPV4_LITERAL = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

/**
 * Whether Connect asks for local network access before its first attempt. Only a literal IPv4 address in a private
 * (10/8, 172.16/12, 192.168/16) or link-local (169.254/16) range is asked about first, because that case is
 * unambiguous. Everything else (host names, IPv6, VPN routes) is tried without asking: whether the platform blocks it
 * depends on the route, which the app cannot see before trying. A failed attempt then offers the permission.
 */
fun asksForLocalNetworkFirst(host: String, granted: Boolean): Boolean {
    if (granted) return false
    val b = IPV4_LITERAL.matchEntire(host)?.groupValues?.drop(1)?.map(String::toInt) ?: return false
    if (b.any { it > 255 }) return false
    return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
}
