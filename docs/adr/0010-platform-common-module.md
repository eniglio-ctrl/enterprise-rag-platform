# ADR 0010: Extract `platform-common` for the code every service duplicated

## Status
Accepted

## Context
`CorsConfig` was byte-for-byte identical between `ingestion-service` and `rag-service`
(confirmed with `diff` — only the `package` line differed), and `ErrorResponse`/
`GlobalExceptionHandler`/`OpenApiConfig` followed the same pattern, with
`GlobalExceptionHandler` getting *more* duplicated once ADR 0009 added two identical
Resilience4j exception handlers to both copies. With `chat-service` (Fase 3) and
`auth-service` (Fase 4) both coming, this was on track to become four copies silently
drifting apart if not fixed before they arrive.

## Decision
New module `platform-common` (`packaging: jar`, no `spring-boot-maven-plugin`),
`com.eniglio.ragplatform.common.web` package:
- `CorsConfig` — moved as-is (already parameterized via `${web-ui.allowed-origin}`).
- `ErrorResponse` — moved as-is.
- `OpenApiConfig` — now parameterized via a new `OpenApiProperties`
  (`@ConfigurationProperties(prefix = "platform.openapi")`, `title`/`description`)
  instead of being hardcoded per service; each service sets these two values in its
  own `application.yml`.
- `GlobalExceptionHandlerSupport` — an **abstract base class** (not itself
  `@RestControllerAdvice`) holding the handlers that are identical everywhere
  (`CallNotPermittedException`, `ResourceAccessException`, the generic
  `Exception` catch-all, and the `build(...)` helper). Each service's own
  `GlobalExceptionHandler extends` it and adds only what's specific to that service
  (validation errors in `rag-service`; upload/IO errors in `ingestion-service`).
  Spring resolves `@ExceptionHandler` methods declared on a superclass exactly like
  ones declared directly on the advice bean, so this works without any extra wiring.
- `testcontainers-bom`'s version management moved from being duplicated in each
  service's own `pom.xml` to the root `pom.xml`.

## Consequences — three real problems found while doing this, not hypothetical

1. **Component scanning gap.** `platform-common`'s classes live in
   `com.eniglio.ragplatform.common`, a *sibling* of `com.eniglio.ragplatform.rag`/
   `.ingestion`, not a sub-package. `@SpringBootApplication`'s default component scan
   is rooted at the annotated class's own package, so it would never have found
   `CorsConfig`/`OpenApiConfig` from the dependency — they'd have silently not
   registered, with no error, just missing CORS headers and default (wrong) OpenAPI
   metadata. Fixed by adding an explicit
   `@ComponentScan(basePackages = {"<service-package>", "com.eniglio.ragplatform.common"})`
   (and the equivalent `@ConfigurationPropertiesScan`) to both `*Application` classes.

2. **`testcontainers-bom` version silently downgraded.** Moving the bom import from
   each service's own `dependencyManagement` (where a *child's own* declaration always
   wins over anything the parent brings in) up to the shared root `pom.xml` put it in
   direct competition with `spring-boot-dependencies`, which *also* imports
   `testcontainers-bom` internally (pinned to an old 1.x via its own
   `${testcontainers.version}` property). Within a single `dependencyManagement`
   block, Maven resolves competing managed versions for the same artifact by
   declaration order — first wins. `spring-boot-dependencies` was listed first, so its
   old version silently won; the build didn't fail, it just quietly ran the tests
   against Testcontainers 1.21.2 instead of 2.0.5 (the version this project needs —
   see ADR-adjacent context in `03-PROBLEMAS-E-SOLUCOES.md`), and only building with
   `docker compose` (a clean environment) rather than trusting a possibly-cached local
   result would have caught it. Tried overriding just the `${testcontainers.version}`
   property first — that seemed like the "correct" Spring Boot pattern, but it broke
   worse: two Testcontainers 2.x-only module coordinates ended up with *no* managed
   version at all, rather than the wrong one. Fixed by keeping an explicit
   `testcontainers-bom` import but declaring it **before**
   `spring-boot-dependencies` in the same `dependencyManagement` block, so first-wins
   works in this project's favor instead of against it.
3. **New module invisible to the Docker build.** Both `Dockerfile`s only `COPY`
   `ingestion-service/pom.xml` and `rag-service/pom.xml` before running
   `./mvnw -pl <service> -am ...` — Maven's reactor validates that *every* module
   declared in the root `pom.xml` has a readable `pom.xml` on disk, even ones not
   being actively built, so introducing `platform-common` without copying its files
   into the build context broke the Docker build immediately (`Child module
   /workspace/platform-common ... does not exist`) even though the exact same code
   built fine with plain `./mvnw clean verify` on the host. Fixed by adding
   `COPY platform-common/pom.xml` (for reactor validation) and
   `COPY platform-common/src` (since it's now an actual compile dependency, not just a
   name in the reactor) to both Dockerfiles.

`./mvnw clean verify` is green on all three modules (unit and integration tests,
including the two `@SpringBootTest`-based ITs that only pass if `platform-common`'s
beans are genuinely being picked up by the real Spring context, not just present on
the classpath).

**Verification status: fully verified.** After the network failure described in ADR
0009 eventually cleared, `docker compose up -d --build ingestion-service rag-service`
succeeded — proving problem #3 above is genuinely fixed, not just correct on paper —
and all 5 services report `healthy`, including the OpenAPI metadata now coming from
`platform.openapi.*` properties instead of hardcoded strings.
