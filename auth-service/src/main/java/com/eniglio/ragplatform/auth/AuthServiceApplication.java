package com.eniglio.ragplatform.auth;

import com.eniglio.ragplatform.common.security.ResourceServerSecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Unlike every other service, auth-service is the token issuer, not a resource
 * server — it must stay reachable without a JWT (registration/login/JWKS are
 * necessarily public). platform-common's {@link ResourceServerSecurityConfig} is
 * excluded here for that reason: without a {@code jwk-set-uri} configured (there's
 * nothing for auth-service to validate tokens against but itself), that bean would
 * fail to build a working filter chain at startup. Its own
 * {@code config.AuthSecurityConfig} replaces it.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.eniglio.ragplatform.auth", "com.eniglio.ragplatform.common"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ResourceServerSecurityConfig.class))
@ConfigurationPropertiesScan(basePackages = {"com.eniglio.ragplatform.auth", "com.eniglio.ragplatform.common"})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
