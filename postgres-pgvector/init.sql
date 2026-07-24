-- Owned by infra/DBA, independent of application schema migrations.
-- Spring AI's PgVectorStore also issues this statement when initialize-schema
-- is enabled, but provisioning the extension here keeps it explicit and
-- makes the database usable even before any service has started.
CREATE EXTENSION IF NOT EXISTS vector;
