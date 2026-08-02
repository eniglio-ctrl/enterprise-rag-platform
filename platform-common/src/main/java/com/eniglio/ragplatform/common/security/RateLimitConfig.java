package com.eniglio.ragplatform.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link RateLimitFilter} as a bean so every {@code SecurityFilterChain}
 * (auth-service's own, {@link ResourceServerSecurityConfig}, {@link DemoSecurityConfig})
 * can wire it in with {@code .addFilterAfter(rateLimitFilter, AuthorizationFilter.class)} -
 * one shared instance per service, not one per chain (there's only ever one active
 * chain per service anyway, {@code @Profile}-selected).
 * <p>
 * Also exposes {@link AuditingAuthenticationEntryPoint}/{@link
 * AuditingAccessDeniedHandler} (Security Phase 5) - both need the exact same
 * trusted-proxy-hops config {@link RateLimitFilter} already reads for its own
 * per-IP keying, so they're defined alongside it rather than duplicating a second
 * {@code @EnableConfigurationProperties(RateLimitProperties.class)} elsewhere.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties properties, MeterRegistry meterRegistry) {
        return new RateLimitFilter(properties, meterRegistry);
    }

    @Bean
    public AuditingAuthenticationEntryPoint auditingAuthenticationEntryPoint(RateLimitProperties properties) {
        return new AuditingAuthenticationEntryPoint(properties);
    }

    @Bean
    public AuditingAccessDeniedHandler auditingAccessDeniedHandler(RateLimitProperties properties) {
        return new AuditingAccessDeniedHandler(properties);
    }
}
