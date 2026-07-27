package com.eniglio.ragplatform.chat.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Overrides the auto-configured default {@code ChatMemory} bean (which uses a fixed
 * default window) so the window size is this project's own configured
 * {@code chat.max-messages}, not whatever Spring AI ships as a default.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, ChatProperties chatProperties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(chatProperties.maxMessages())
                .build();
    }
}
