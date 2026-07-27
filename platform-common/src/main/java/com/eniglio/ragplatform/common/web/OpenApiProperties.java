package com.eniglio.ragplatform.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.openapi")
public record OpenApiProperties(String title, String description) {
}
