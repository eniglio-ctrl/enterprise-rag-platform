-- Tracks conversation ownership (tenantId/userId, ADR 0007) — SPRING_AI_CHAT_MEMORY
-- only knows conversation_id + messages, nothing about who a conversation belongs to.
-- gen_random_uuid() is PostgreSQL core (13+), not the uuid-ossp extension
-- ingestion-service's schema uses — that function lives in the `public` schema and
-- this connection's search_path only includes `chat`, so a core built-in avoids a
-- cross-schema function reference for no reason.

CREATE TABLE IF NOT EXISTS conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS conversations_tenant_id_idx ON conversations (tenant_id);
