-- Exact DDL Spring AI's JdbcChatMemoryRepository would auto-create for PostgreSQL
-- (captured from spring-ai-model-chat-memory-repository-jdbc-1.0.0.jar's own
-- schema-postgresql.sql, not written from memory — see ADR 0013). Runs inside the
-- `chat` logical schema (spring.datasource.url's currentSchema=chat), keeping this
-- service's tables out of ingestion-service's `public` schema and its own
-- flyway_schema_history.
--
-- spring.ai.chat.memory.repository.jdbc.initialize-schema is left at its default
-- (EMBEDDED — only auto-initializes for embedded databases, never a real Postgres),
-- so there's no ordering race with Flyway the way ADR 0011 found for PgVectorStore.

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");
