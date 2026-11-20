package xyz.rycerz.apofis.ui.connect

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17 blocks apps that target it from the local network unless the user grants this runtime permission
 * (developer.android.com/privacy-and-security/local-network-permission). Only API 37 and later know it.
 */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
const val LOCAL_NETWORK_MIN_SDK = 37

/**
 * The addresses Android counts as local network (developer.android.com/privacy-and-security/local-network-definition):
 * link-local, CGNAT 100.64.0.0/10, RFC 1918, multicast and broadcast; for IPv6 link-local and, as the closest literal
 * test for "directly connected routes", unique-local fc00::/7.
 */
fun isLocalNetworkAddress(a: InetAddress): Boolean {
    val b = a.address.map { it.toInt() and 0xff }
    return a.isSiteLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress || when (a) {
        is Inet4Address -> (b[0] == 100 && b[1] and 0xc0 == 64) || b.all { it == 255 }
        is Inet6Address -> b[0] and 0xfe == 0xfc
        else -> false
    }
}

private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** True when [host] (as OkHttp's HttpUrl gives it) is a literal local address or an mDNS `.local` name. Never uses DNS. */
fun hostNeedsLocalNetwork(host: String): Boolean = when {
    host.endsWith(".local", ignoreCase = true) -> true
    IPV4_LITERAL.matches(host) || ':' in host -> runCatching { isLocalNetworkAddress(InetAddress.getByName(host)) }.getOrDefault(false)
    else -> false
}

/** For a host name: does it resolve to a local address? DNS is allowed without the permission. */
fun resolvesToLocalNetwork(host: String): Boolean =
    runCatching { InetAddress.getAllByName(host).any(::isLocalNetworkAddress) }.getOrDefault(false)
