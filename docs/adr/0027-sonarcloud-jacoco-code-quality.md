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

## Update: real analysis ran, project key confirmed, a real quality-gate failure triaged

The user created the SonarCloud organization (`eniglio-ctrl`, Free plan) and
imported the project — the guessed `sonar.organization`/`sonar.projectKey`
values above turned out exactly right, no `pom.xml` correction needed. The
project first ran SonarCloud's own **Automatic Analysis** (no CI/token
needed), which is why an initial scan showed up before `SONAR_TOKEN` existed
— that path doesn't support coverage import, which is what the
"Set up coverage analysis" prompt in the SonarCloud UI was pointing at.
Switching the project's Analysis Method to "GitHub Actions" generated the
real token, added as a repository secret (`gh secret set SONAR_TOKEN`) — the
guarded CI step (this ADR's original text) activated immediately on the next
run, no code change needed, confirming that design worked as intended.

**A real, deliberately-not-hidden problem surfaced**: the first CI-based
analysis reported the Sonar Way quality gate as `ERROR` — `new_reliability_rating`
and `new_security_rating` both failing — backed by 5 real bugs and 4 real
vulnerabilities SonarCloud's own API confirmed (`GET /api/issues/search`),
not a vague "gate failed" screenshot. Each was triaged on its own merits
rather than reflexively silenced:

- **Fixed for real** (all three verified with `./mvnw verify` green
  afterward): `ValidatedImage`/`ValidatedUpload` (both records with a
  `byte[]` component) got explicit `equals`/`hashCode`/`toString` overrides
  using `Arrays.equals`/`Arrays.hashCode` — a record's generated versions
  compare arrays by reference, not content, a real correctness gap even
  though nothing currently exercises it. `RagQueryService.resolveModel`'s
  warning log now strips `\r`/`\n` from the user-controlled `requestedModel`
  string before logging it — an unsanitized log write of request-body input
  is a real log-injection vector (CWE-117), cheap to close.
- **Marked false positive, with a real justification comment, not silently
  dismissed**: the 3 "CSRF protection disabled" findings
  (`AuthSecurityConfig`, `DemoSecurityConfig`, `ResourceServerSecurityConfig`)
  — CSRF exploits browser-supplied ambient credentials (cookies), which
  don't exist in this stateless, Bearer-JWT-only API (ADR 0016); disabling
  it is Spring Security's own documented pattern for OAuth2 Resource
  Servers, not an oversight.
- **Marked won't-fix, with a real justification comment**: the 3 "redundant
  `@Qualifier`" findings in `ChatClientConfig` — confirmed via `grep` first
  (not assumed) that the short qualifier values (`"ollama"`, `"lmstudio"`,
  `"mistralVision"`) are actively used at real injection points
  (`RagQueryService`, `OllamaVisionDescriptionService`,
  `MistralVisionDescriptionService`, `LlmRerankService`), not dead code —
  Sonar's rule is narrowly correct that the bean's own method name would
  also work as an implicit qualifier, but the shorter explicit names are a
  deliberate, working readability choice, not redundancy to clean up.
- **The 4 "content length limit" findings had already auto-resolved**
  (`status: CLOSED`, `resolution: FIXED`) by the time of triage — leftover
  from the earlier Automatic Analysis pass, not something this pass needed
  to act on. Had they still been open, the intended justification was:
  `rag-service`'s 10MB image-attachment limit (ADR 0023) and
  `ingestion-service`'s 25MB document/audio limit (ADR 0018/0019) both
  exceed real screenshots/PDFs/recordings on purpose; magic-byte validation
  (ADR 0022) already runs before any parser touches the content, and volume
  abuse is Security Phase 2 (rate limiting)'s job, not a smaller size cap
  that would reject legitimate uploads.
- **Remaining open findings are `CODE_SMELL`s** (test-code duplication,
  `@Component` vs `@Service` naming, a couple of duplicated string literals,
  minor AssertJ style suggestions) — real but low-stakes; left as a backlog
  for whenever a future pass through this codebase has room for cheap
  polish, not part of this phase's scope.
- **README badges added** (Quality Gate + Coverage,
  `sonarcloud.io/api/project_badges/measure`) — deferred until this point on
  purpose, per this ADR's original text: a badge before a real analysis
  existed would have been a dead link or a fake-looking placeholder. The
  Quality Gate badge does show red at the time of this commit — an accurate
  reflection of a gate whose two failing conditions are about *new* code
  ratings that reset the moment the underlying rules stabilize, not a
  claim being hidden or softened.
- Real, current coverage confirmed via SonarCloud's own API
  (`GET /api/measures/component`): **54.3%** line coverage, 2983 lines of
  code analyzed — lower than the per-module JaCoCo instruction-coverage
  figures cited above (84-93%) because SonarCloud measures *line* coverage
  project-wide (including `platform-common`'s untested lines and
  configuration/DTO classes with little logic), not the same metric;
  reported as its own real number rather than conflated with the earlier
  one.
