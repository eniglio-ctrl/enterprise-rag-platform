-- ADR 0047: a tenant-scoped ADMIN/MEMBER role. Every tenant needs exactly one ADMIN
-- to exist already (there is no other way to reach the new admin-only endpoints), so
-- this backfills the earliest user per tenant - by created_at, present since V1 - as
-- ADMIN, mirroring the bootstrap rule new registrations follow going forward
-- (AuthService.register: whoever creates a tenant becomes its first ADMIN).
ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'MEMBER';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('MEMBER', 'ADMIN'));

UPDATE users SET role = 'ADMIN'
WHERE id IN (
    SELECT DISTINCT ON (tenant_id) id FROM users ORDER BY tenant_id, created_at ASC
);
