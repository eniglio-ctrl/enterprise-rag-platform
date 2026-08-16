# Cypress Sample Document

This is a small, real Markdown file used by the Cypress E2E suite to exercise
the actual upload -> validate -> chunk -> embed -> store pipeline against the
real ingestion-service, not a mock.

## The SAGA pattern

The SAGA pattern coordinates distributed transactions across multiple
services using either choreography or orchestration, avoiding a single
long-lived distributed lock.
