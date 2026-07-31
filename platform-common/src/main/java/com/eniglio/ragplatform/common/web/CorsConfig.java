package com.eniglio.ragplatform.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${web-ui.allowed-origin}")
    private String allowedOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST")
                // Security Phase 3: only the two headers any real client here ever
                // sends - a wildcard let any header through, wider than anything
                // this API actually needs (origin and methods were already this
                // narrow before this phase).
                .allowedHeaders("Authorization", "Content-Type");
    }
}
