package com.eniglio.ragplatform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void ignoresXForwardedForWhenNoProxyHopIsTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");

        assertThat(ClientIpResolver.resolve(request, 0)).isEqualTo("10.0.0.1");
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("10.0.0.1");
    }

    @Test
    void readsTheEntryAppendedByTheTrustedHopWhenOneHopIsTrusted() {
        // Client -> [forged: 6.6.6.6] -> trusted proxy (appends the real client IP,
        // 1.2.3.4) -> this service. With one trusted hop, only the rightmost entry
        // (the trusted proxy's own append) is read - the client's forged entry to
        // its left is ignored.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 1.2.3.4");

        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("1.2.3.4");
    }

    @Test
    void readsTheSecondFromRightEntryWhenTwoHopsAreTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 1.2.3.4, 9.9.9.9");

        assertThat(ClientIpResolver.resolve(request, 2)).isEqualTo("1.2.3.4");
    }

    @Test
    void fallsBackToRemoteAddrWhenTheHeaderHasFewerEntriesThanTrustedHops() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(ClientIpResolver.resolve(request, 3)).isEqualTo("10.0.0.1");
    }

    @Test
    void fallsBackToRemoteAddrWhenTheHeaderIsBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "");

        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("10.0.0.1");
    }
}
