package com.eniglio.ragplatform.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client IP for {@link RateLimitFilter}'s per-IP buckets, without
 * trusting a client-supplied header by default. {@code X-Forwarded-For} is a plain
 * request header — any caller can set it to whatever they want, including a fake IP
 * chosen specifically to land in a different rate-limit bucket than their real one,
 * trivially defeating an IP-based limit if the header is read naively (e.g. always
 * taking its first entry).
 * <p>
 * The only safe way to use it is to know exactly how many real reverse-proxy hops sit
 * in front of this service, then read the entry that many positions from the *right*
 * of the comma-separated list - each trusted hop appends the address it received the
 * request from, so the trusted hops' own entries are always the rightmost ones. A
 * client can prepend as many forged entries as it wants at the left; that only pushes
 * their fake IPs further from the position this resolver actually reads.
 */
final class ClientIpResolver {

    private ClientIpResolver() {
    }

    static String resolve(HttpServletRequest request, int trustedProxyHops) {
        if (trustedProxyHops <= 0) {
            return request.getRemoteAddr();
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] entries = header.split(",");
        int indexFromRight = trustedProxyHops - 1;
        if (indexFromRight >= entries.length) {
            // Fewer entries than configured trusted hops - malformed or truncated
            // header. Fall back to the immediate peer rather than guessing.
            return request.getRemoteAddr();
        }
        return entries[entries.length - 1 - indexFromRight].trim();
    }
}
