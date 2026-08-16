-- docs/adr/0060-multi-department-membership-and-approval.md: replaces the
-- single, admin-only-assigned users.department column (V5) with a proper
-- membership table supporting several departments per user and a
-- pending/approved lifecycle. department_id is a real FK here (unlike
-- users.department/the JWT claim/document metadata, which all stay
-- name-based) because this table is 100% internal to auth-service - no
-- other service ever reads it directly, so there's no cross-service
-- name-vs-id tension to navigate. status has no CHECK constraint,
-- validated in the service layer instead, same convention V5 already used
-- for the department name itself.
CREATE TABLE IF NOT EXISTS user_departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id),
    department_id UUID NOT NULL REFERENCES departments (id),
    status TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);

-- One row per (user, department): a rejected request is a DELETE (no
-- history kept, by explicit product decision), so requesting again later
-- is just a fresh INSERT into the same unique slot.
CREATE UNIQUE INDEX IF NOT EXISTS user_departments_user_dept_idx
    ON user_departments (user_id, department_id);

-- Carry forward whatever was already assigned via the single-column model
-- as an already-approved membership - this feature shipped minutes ago
-- with no real usage, so this is a formality rather than a real
-- data-preservation concern, but it's cheap correctness.
INSERT INTO user_departments (user_id, department_id, status, decided_at)
SELECT u.id, d.id, 'APPROVED', now()
FROM users u
JOIN departments d ON d.tenant_id = u.tenant_id AND lower(d.name) = lower(u.department)
WHERE u.department IS NOT NULL;

ALTER TABLE users DROP COLUMN department;

-- docs/adr/0060: invite-time admin role grant - additive column on the
-- existing invitations table, folded into this same migration since both
-- changes are small and ship together in the same phase.
ALTER TABLE invitations ADD COLUMN role TEXT NOT NULL DEFAULT 'MEMBER';
