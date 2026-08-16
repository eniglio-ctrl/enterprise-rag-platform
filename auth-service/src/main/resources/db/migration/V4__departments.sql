-- docs/adr/0059-department-based-sharing.md: a tenant-scoped registry of department
-- names an admin creates, so a user's own `department` column (V5) and a document's
-- `sharedWithDepartments` metadata (ingestion-service) always draw from a controlled
-- list instead of free-typed strings that could drift ("Financeiro" vs "financeiro").
-- Not a value anything else has a foreign key to - every consumer (User.department,
-- the JWT claim, document metadata) stores the department's name as plain text, the
-- same "no relational purity where it isn't needed" convention this schema already
-- follows for sharing (vector_store.metadata is JSON, not a join table).

CREATE TABLE IF NOT EXISTS departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS departments_tenant_name_idx ON departments (tenant_id, lower(name));
