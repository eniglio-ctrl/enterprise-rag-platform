package com.eniglio.ragplatform.common.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security Phase 2: per-key inbound rate limiting, shared across every service via
 * {@link RateLimitConfig}. In-memory buckets (one {@link Bucket} per rule+key pair, in
 * a {@link ConcurrentHashMap} that only ever grows) - correct and sufficient for this
 * project's actual deployment shape (a single instance per service, no horizontal
 * scaling, ADR 0028), but *not* distributed: if a service is ever scaled to multiple
 * replicas, each one enforces its own independent limit, multiplying the real ceiling
 * by the replica count. Accepted trade-off, not an oversight - same shape as ADR
 * 0002's shared-database simplification.
 * <p>
 * Registered via {@code .addFilterAfter(rateLimitFilter, AuthorizationFilter.class)} in
 * every {@code SecurityFilterChain} (auth-service's own, plus
 * {@link ResourceServerSecurityConfig}/{@link DemoSecurityConfig}) - deliberately after
 * authentication AND authorization both run, so a request keyed by tenant always sees
 * the real, already-resolved {@link JwtAuthenticationToken} if one exists, rather than
 * racing it.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitProperties.Rule rule = matchRule(request);
        if (!properties.enabled() || rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = rule.name() + ":" + resolveKey(request, rule);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(rule));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        log.warn("Rate limit '{}' exceeded for key '{}' on {} {}", rule.name(), key, request.getMethod(),
                request.getRequestURI());
        Counter.builder("security.rate_limit.blocked")
                .tag("rule", rule.name())
                .register(meterRegistry)
                .increment();
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("""
                {"status":429,"error":"Too Many Requests","message":"Limite de requisições excedido, tente novamente em instantes."}""");
    }

    private RateLimitProperties.Rule matchRule(HttpServletRequest request) {
        List<RateLimitProperties.Rule> rules = properties.rules();
        for (RateLimitProperties.Rule rule : rules) {
            if (PATH_MATCHER.match(rule.pathPattern(), request.getRequestURI())) {
                return rule;
            }
        }
        return null;
    }

    private String resolveKey(HttpServletRequest request, RateLimitProperties.Rule rule) {
        if (rule.keyByIp()) {
            return "ip:" + ClientIpResolver.resolve(request, properties.trustedProxyHops());
        }
        String tenantId = currentTenantId();
        return tenantId != null
                ? "tenant:" + tenantId
                : "ip:" + ClientIpResolver.resolve(request, properties.trustedProxyHops());
    }

    private String currentTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return JwtClaims.tenantId(jwt);
        }
        return null;
    }

    private Bucket newBucket(RateLimitProperties.Rule rule) {
        return bucketFor(rule.capacity(), rule.refillPeriod());
    }

    /** Package-private for {@code ClientIpResolverTest}-style direct bucket-math tests. */
    static Bucket bucketFor(int capacity, Duration refillPeriod) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, refillPeriod))
                .build();
    }
}
