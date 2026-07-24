package com.eniglio.ragplatform.ingestion.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ingestionServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ingestion Service API")
                        .description("Uploads, parses, chunks and embeds documents into the shared pgvector store")
                        .version("v0.1.0"));
    }
}
