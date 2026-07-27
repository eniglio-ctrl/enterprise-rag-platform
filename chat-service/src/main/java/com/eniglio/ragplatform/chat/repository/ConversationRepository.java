package com.eniglio.ragplatform.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Tracks conversation ownership (tenantId/userId, ADR 0007) — Spring AI's own
 * SPRING_AI_CHAT_MEMORY table (ADR 0013) only knows conversation_id + messages,
 * nothing about who a conversation belongs to.
 */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String create(String tenantId, String userId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO conversations (id, tenant_id, user_id) VALUES (?, ?, ?)",
                id, tenantId, userId);
        return id.toString();
    }

    /**
     * A malformed (non-UUID) conversationId is treated the same as "not found" rather
     * than letting an invalid ::uuid cast throw — the caller shouldn't need to
     * distinguish the two.
     */
    public boolean belongsToTenant(String conversationId, String tenantId) {
        UUID id;
        try {
            id = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ? AND tenant_id = ?",
                Integer.class, id, tenantId);
        return count != null && count > 0;
    }
}
