package com.eniglio.ragplatform.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

// platform-common's classes live in com.eniglio.ragplatform.common, a sibling
// package @SpringBootApplication's default scan (rooted at
// com.eniglio.ragplatform.chat) would not reach on its own.
@SpringBootApplication
@ComponentScan(basePackages = {"com.eniglio.ragplatform.chat", "com.eniglio.ragplatform.common"})
@ConfigurationPropertiesScan(basePackages = {"com.eniglio.ragplatform.chat", "com.eniglio.ragplatform.common"})
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
