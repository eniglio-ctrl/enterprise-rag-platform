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
                // PATCH added for real (Multi-LLM roadmap, Cypress E2E phase): missing
                // here the whole time, invisible to every prior test because
                // MockMvc/RestAssured-style backend tests never go through a browser's
                // CORS preflight - only a real browser making a real PATCH fetch (e.g.
                // web-ui's document-sharing "Salvar" button) ever triggers it, and it
                // silently failed with a generic "Failed to fetch" with no server-side
                // log at all (the browser blocks the request before it's ever sent).
                // First real evidence this endpoint's browser path had never actually
                // been exercised: the first Cypress spec to click that exact button.
                // DELETE hit the exact same gap for real (docs/adr/0060-multi-department
                // -membership-and-approval.md's reject-a-request endpoint) - same
                // silent "Failed to fetch" in a real browser, caught during manual
                // verification against the live stack, not by MockMvc (which never
                // goes through CORS at all).
                .allowedMethods("GET", "POST", "PATCH", "DELETE")
                // Security Phase 3: only the two headers any real client here ever
                // sends - a wildcard let any header through, wider than anything
                // this API actually needs (origin and methods were already this
                // narrow before this phase).
                .allowedHeaders("Authorization", "Content-Type");

        // Actuator endpoints are served by their own WebMvcEndpointHandlerMapping,
        // not the RequestMappingHandlerMapping this registry configures - a mapping
        // added here for /actuator/** has no effect on them. See application.yml's
        // management.endpoints.web.cors.* for the browser-facing health badge's CORS.
    }
}
