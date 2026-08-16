package com.eniglio.ragplatform.common.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security Phase 2 (rate limiting). Every service configures its own list of
 * {@link Rule}s — there's no one-size-fits-all limit across auth-service's
 * login/register (unauthenticated, keyed by IP) and ingestion/rag/chat-service's
 * upload/ask/chat/diagram/conversation endpoints (authenticated, keyed by tenant).
 *
 * @param enabled          global off-switch, defaults on; tests that don't care about
 *                         rate limiting set this false rather than tolerating 429s.
 * @param trustedProxyHops how many trusted reverse-proxy hops sit in front of this
 *                         service. {@code 0} (the default, safe for local/docker-compose
 *                         where nothing legitimate sits in front) means
 *                         {@code X-Forwarded-For} is never consulted at all —
 *                         {@link RateLimitFilter} always uses
 *                         {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}.
 *                         Only the public demo deployment (behind Render's own edge,
 *                         ADR 0020) sets this to a real value, and only after confirming
 *                         exactly how many hops that edge actually adds - a client can
 *                         forge as many fake entries as it wants at the *left* of the
 *                         header, so only counting from the *right* by a known,
 *                         fixed hop count is safe.
 * @param rules            evaluated in order; the first whose {@code pathPattern}
 *                         matches the request wins. A request matching no rule is not
 *                         rate limited at all.
 */
@ConfigurationProperties(prefix = "security.rate-limit")
public record RateLimitProperties(boolean enabled, int trustedProxyHops, List<Rule> rules) {

    public RateLimitProperties {
        rules = rules == null ? List.of() : rules;
    }

    /**
     * @param name         identifies this rule in the {@code security.rate_limit.blocked}
     *                     metric's {@code rule} tag and in bucket keys - must be unique.
     * @param pathPattern  an Ant-style pattern ({@link org.springframework.util.AntPathMatcher}),
     *                     e.g. {@code "/api/v1/auth/*"}.
     * @param keyByIp      {@code true} for unauthenticated endpoints (login/register -
     *                     there's no principal yet to key by); {@code false} keys by the
     *                     authenticated tenant ID instead, falling back to the client IP
     *                     if the request somehow reaches the filter unauthenticated.
     * @param capacity     tokens in the bucket - the burst size.
     * @param refillPeriod how long it takes to refill the bucket back to {@code capacity},
     *                     refilled at a steady per-nanosecond rate (a "greedy" refill),
     *                     not in one lump sum at the end of the period.
     */
    public record Rule(String name, String pathPattern, boolean keyByIp, int capacity, Duration refillPeriod) {
    }
}
