-- gen_random_uuid() is PostgreSQL core (13+) — see chat-service's V2__conversations.sql
-- for the same reasoning (avoids a cross-schema reference to uuid-ossp's function,
-- which lives in `public`, out of reach of this connection's `auth`-only search_path).

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS users_email_idx ON users (lower(email));
