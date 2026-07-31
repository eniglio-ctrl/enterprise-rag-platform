# ADR 0027: Code quality analysis via SonarCloud + JaCoCo

## Status
Accepted

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 14 / `docs/ROADMAP.md` Tier 1
#3. Distinct from CodeQL (ADR 0026): CodeQL is security-focused static
analysis; nothing in this project previously measured code quality,
maintainability, duplication, or test coverage. The `docs/architecture.md`
half of Phase 14 turned out to already be done — it was refreshed to cover
all 4 services in an earlier, unrelated commit found while starting this
phase, not written as part of this ADR.

## Decision
- **SonarCloud, not a self-hosted SonarQube instance**: free for public
  repositories, no infrastructure to run or maintain — matches this
  project's already-public GitHub repo and the same "free tier, portfolio
  scope" reasoning ADR 0020/0026 already used.
- **JaCoCo added first, as its own real signal, not just SonarCloud fuel**:
  `jacoco-maven-plugin` in the root `pom.xml`'s `pluginManagement`
  (`prepare-agent` executes by default; `report` bound to the `verify`
  phase, deliberately *after* both Surefire executions — the unit-test one
  and the `*IT` integration-test one added in ADR 0021/Fase 1 — so coverage
  reflects everything that actually ran, not unit tests alone). Each of the
  4 service modules (`auth-service`, `ingestion-service`, `rag-service`,
  `chat-service`) adds a bare `<plugin>` reference to inherit it; JaCoCo
  isn't a default-lifecycle-bound plugin like Surefire, so it needs that
  explicit reference in each module, unlike Surefire's pluginManagement
  executions which apply automatically. `platform-common` was left out
  deliberately — it has zero tests today, so a coverage report there would
  be an empty no-op.
- **`sonar.organization`/`sonar.projectKey`/`sonar.coverage.jacoco.xmlReportPaths`
  as root `pom.xml` properties**, not passed via `-D` flags on every
  invocation — SonarCloud's default project-key convention
  (`<github-org>_<repo-name>`) was used as a starting guess
  (`eniglio-ctrl_enterprise-rag-platform`); verify both match exactly what
  SonarCloud actually assigned once the project was imported, and correct
  here if not.
- **`.github/workflows/ci.yml` gains a guarded analysis step**, not an
  unconditional one: `if: ${{ secrets.SONAR_TOKEN != '' }}` on both the
  SonarCloud cache step and the analysis step itself, so CI stays green
  (the step simply doesn't run) in the window between this commit landing
  and `SONAR_TOKEN` actually being added as a repository secret — avoids
  turning CI red for every push until a manual, external (SonarCloud
  account creation, GitHub OAuth, token generation) step is done, which
  only the user can do (account creation is never something this assistant
  does on someone's behalf).
- **`actions/checkout@v7` gains `fetch-depth: 0`** (full history) — required
  for SonarCloud's new-code-vs-baseline analysis to diff against a real
  base rather than a single shallow commit.

## Consequences
- **Verified for real, locally, before touching CI**: `./mvnw -B verify`
  green across all 5 modules, and a real `jacoco.xml` generated for each of
  the 4 instrumented services. Actual instruction coverage found (not
  guessed): `auth-service` 91.2%, `ingestion-service` 90.7%, `rag-service`
  84.8%, `chat-service` 93.7% — a genuinely strong baseline, worth citing
  as-is rather than a vague "well tested" claim.
- **The SonarCloud analysis itself has NOT run yet** — it needs a real
  SonarCloud project (GitHub OAuth login, import this repo, generate a
  token) that only the user can create; account/OAuth creation on someone's
  behalf is out of scope for this assistant, same boundary already applied
  to OpenAI/Anthropic/Gemini key creation in the Multi-LLM fallback
  planning. Once `SONAR_TOKEN` is added as a repo secret (either by the
  user directly, or handed to this assistant to add via `gh secret set`),
  the guarded CI step activates automatically — no further code change
  needed.
- **No README badge added yet** — SonarCloud only issues a valid badge URL
  once the project actually exists there; adding one now would either be a
  dead link or, worse, a badge that looks live but reflects nothing real.
  Added once the first real analysis run completes.
- **`platform-common` has no coverage measured** — an honest gap, not
  hidden: it has zero tests today. Adding tests there is a separate,
  future decision, not bundled into this ADR.
