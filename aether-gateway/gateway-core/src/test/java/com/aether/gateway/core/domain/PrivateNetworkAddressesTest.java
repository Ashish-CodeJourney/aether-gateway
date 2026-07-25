package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateNetworkAddressesTest {

    private InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void rejectsRfc1918PrivateRanges() throws UnknownHostException {
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("10.0.0.5"))).isTrue();
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("172.16.5.1"))).isTrue();
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("192.168.1.1"))).isTrue();
    }

    @Test
    void rejectsLoopback() throws UnknownHostException {
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("127.0.0.1"))).isTrue();
    }

    @Test
    void rejectsLinkLocal() throws UnknownHostException {
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("169.254.1.1"))).isTrue();
    }

    @Test
    void rejectsAnyLocal() throws UnknownHostException {
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("0.0.0.0"))).isTrue();
    }

    @Test
    void allowsGenuinelyPublicAddresses() throws UnknownHostException {
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("8.8.8.8"))).isFalse();
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("1.1.1.1"))).isFalse();
    }

    @Test
    void rejectsRfc1918BoundaryJustInsideTheRangeButAllowsJustOutsideIt() throws UnknownHostException {
        // 172.16.0.0/12 spans 172.16.0.0 - 172.31.255.255; 172.32.0.0 is public.
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("172.15.255.255"))).isFalse();
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("172.16.0.0"))).isTrue();
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("172.31.255.255"))).isTrue();
        assertThat(PrivateNetworkAddresses.isPrivateOrReserved(ip("172.32.0.0"))).isFalse();
    }
}
