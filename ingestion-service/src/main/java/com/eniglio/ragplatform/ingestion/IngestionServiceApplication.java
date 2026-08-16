package com.eniglio.ragplatform.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

// platform-common's classes live in com.eniglio.ragplatform.common, a sibling
// package @SpringBootApplication's default scan (rooted at
// com.eniglio.ragplatform.ingestion) would not reach on its own.
@SpringBootApplication
@ComponentScan(basePackages = {"com.eniglio.ragplatform.ingestion", "com.eniglio.ragplatform.common"})
@ConfigurationPropertiesScan(basePackages = {"com.eniglio.ragplatform.ingestion", "com.eniglio.ragplatform.common"})
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
