package com.quintz.wifi.core

import java.net.InetAddress

/** Global IPv6 and private IPv4/IPv6 addresses are usable; link-local alone is not IP readiness. */
fun isUsableWifiAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress

/** A redacted single Wi-Fi network is usable; multiple candidates need a unique identity match. */
internal fun <T> selectWifiNetwork(candidates: List<T>, isIdentityMatch: (T) -> Boolean): T? {
    val matches = candidates.filter(isIdentityMatch)
    return matches.singleOrNull() ?: candidates.singleOrNull().takeIf { matches.isEmpty() }
}
