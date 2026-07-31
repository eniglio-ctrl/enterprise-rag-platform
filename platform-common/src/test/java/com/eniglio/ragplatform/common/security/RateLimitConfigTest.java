package com.eniglio.ragplatform.common.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTest {

    @Test
    void exposesARateLimitFilterBean() {
        RateLimitProperties properties = new RateLimitProperties(true, 0, List.of());

        RateLimitFilter filter = new RateLimitConfig().rateLimitFilter(properties, new SimpleMeterRegistry());

        assertThat(filter).isNotNull();
    }
}
