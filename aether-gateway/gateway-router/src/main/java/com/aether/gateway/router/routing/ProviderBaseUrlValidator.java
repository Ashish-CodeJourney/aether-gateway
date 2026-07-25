package com.aether.gateway.router.routing;

import com.aether.gateway.core.domain.PrivateNetworkAddresses;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * F9.3: rejects a provider base URL whose host resolves to a private or
 * reserved address, before {@link RoutingPolicyRepository} ever commits
 * it into the live routing table - "no request is ever dispatched to
 * that address" per this phase's own Cucumber scenario. DNS resolution
 * (the impure part) lives here, in gateway-router, next to the other
 * config-loading I/O {@link RoutingPolicyRepository} already does
 * directly; the actual private-range classification is the pure
 * {@link PrivateNetworkAddresses} predicate in gateway-core.
 *
 * <p>Resolves every address a hostname maps to (not just the first) and
 * rejects if *any* of them is private - a DNS record can return multiple
 * A/AAAA records, and an attacker only needs one of them to be internal
 * for SSRF to succeed.
 */
public class ProviderBaseUrlValidator {

    public void validate(String providerName, String baseUrl) {
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            throw new SsrfProtectionException(
                    "Provider '" + providerName + "' has an unparseable base URL: " + baseUrl, e);
        }
        if (host == null) {
            throw new SsrfProtectionException(
                    "Provider '" + providerName + "' has an unparseable base URL: " + baseUrl);
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfProtectionException(
                    "Provider '" + providerName + "' base URL host could not be resolved: " + host, e);
        }
        for (InetAddress address : resolved) {
            if (PrivateNetworkAddresses.isPrivateOrReserved(address)) {
                throw new SsrfProtectionException(
                        "Provider '" + providerName + "' base URL resolves to a private/reserved address ("
                                + address.getHostAddress() + "), rejected per F9.3");
            }
        }
    }
}
