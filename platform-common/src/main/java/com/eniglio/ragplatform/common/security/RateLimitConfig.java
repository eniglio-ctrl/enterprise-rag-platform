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
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties properties, MeterRegistry meterRegistry) {
        return new RateLimitFilter(properties, meterRegistry);
    }
}
