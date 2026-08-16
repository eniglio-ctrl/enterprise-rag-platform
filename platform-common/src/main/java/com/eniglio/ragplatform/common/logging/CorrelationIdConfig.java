package com.eniglio.ragplatform.common.logging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link CorrelationIdFilter} directly with the servlet container at the
 * highest possible precedence - deliberately NOT added via {@code HttpSecurity}
 * (unlike {@code RateLimitFilter}), so it runs before Spring Security's entire
 * {@code FilterChainProxy}, in every profile (real JWT validation or the demo's
 * synthetic tenant) and for every request, including ones Spring Security itself
 * will reject before this project's filters in {@code HttpSecurity} ever run.
 */
@Configuration
public class CorrelationIdConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
