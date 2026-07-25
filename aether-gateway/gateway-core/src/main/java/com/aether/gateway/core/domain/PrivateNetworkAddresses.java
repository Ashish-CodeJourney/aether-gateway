package com.aether.gateway.core.domain;

import java.net.InetAddress;

/**
 * F9.3: pure classification of whether a resolved address falls inside
 * a private/reserved range - RFC 1918 (10/8, 172.16/12, 192.168/16),
 * loopback (127/8), link-local (169.254/16), and the unspecified/any
 * address (0.0.0.0). {@link InetAddress}'s own {@code isSiteLocalAddress}
 * already implements the exact RFC 1918 boundaries, so this is a thin,
 * deliberately conservative composition rather than hand-rolled CIDR
 * math. Given an already-resolved address only (no DNS lookup here, no
 * I/O at all) - the caller (gateway-router's provider base URL
 * validation, since resolving a hostname is genuinely impure) owns
 * turning a configured hostname into the {@link InetAddress} this
 * checks.
 *
 * <p>Deliberately not exhaustive for IPv6: covers loopback (::1) and
 * link-local (fe80::/10) via the JDK's own predicates, but does not
 * separately check the IPv6 unique-local range (fc00::/7) - disclosed,
 * not silently assumed covered, since the JDK's {@code isSiteLocalAddress}
 * does not reliably classify ULA the way it does RFC 1918 for IPv4.
 */
public final class PrivateNetworkAddresses {

    private PrivateNetworkAddresses() {
    }

    public static boolean isPrivateOrReserved(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress();
    }
}
