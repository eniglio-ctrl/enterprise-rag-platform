package com.eniglio.ragplatform.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ragServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RAG Service API")
                        .description("Retrieves relevant document chunks from pgvector and generates cited answers")
                        .version("v0.1.0"));
    }
}
