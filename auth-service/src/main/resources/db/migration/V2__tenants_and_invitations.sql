-- `tenants.id` stays TEXT, not UUID, on purpose: it has to hold every
-- tenant_id value already sitting in `users` from before this table existed
-- (free-text strings like "acme" - ADR 0016's original simplified model),
-- and casting those to a UUID type would fail or silently reinterpret real
-- production/demo data. Only *new* tenants created going forward (by
-- AuthService, after this migration) get a random UUID string as their id -
-- the column type doesn't need to enforce that shape.
CREATE TABLE IF NOT EXISTS tenants (
    id TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tenants (id, created_at)
SELECT DISTINCT tenant_id, now() FROM users
ON CONFLICT (id) DO NOTHING;

ALTER TABLE users
    ADD CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

CREATE TABLE IF NOT EXISTS invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    email TEXT NOT NULL,
    token TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS invitations_token_idx ON invitations (token);
