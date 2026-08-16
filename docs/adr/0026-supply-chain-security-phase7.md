# ADR 0026: Supply-chain security (Security Phase 7)

## Status
Accepted

## Context
`docs/SECURITY-HARDENING-ROADMAP.md` Phase 7 and `docs/ROADMAP.md` Tier 1 #2 —
secret scanning, dependency/CVE scanning, and static analysis, none of which
depend on any other pending phase. Distinct from SonarQube (code
quality/maintainability), which stays tracked separately in
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 14 to avoid the same gap being
tracked twice.

## Decision
- **Secret scanning: nothing to add.** Checked the repo's actual settings via
  `gh api repos/eniglio-ctrl/enterprise-rag-platform` rather than assuming —
  both `secret_scanning` and `secret_scanning_push_protection` were already
  `enabled`, GitHub's default for public repositories. No config needed.
- **`.github/dependabot.yml`**: version-update PRs on a weekly schedule for
  Maven, Docker base images, and GitHub Actions.
- **One Maven entry (`directory: "/"`), not one per module — corrected after
  seeing it get it wrong first.** The first version of this file had a
  separate `maven` entry per module directory, reasoning (wrongly) that
  Dependabot doesn't recurse into a reactor's submodules. Real evidence after
  the first run said otherwise: every "per-module" PR Dependabot opened
  (`... in /rag-service`, `... in /auth-service`, etc.) turned out, on
  inspection, to be an **empty diff against the exact same line in the root
  `pom.xml`** the `"/"` entry had already bumped — because every version in
  this project (`spring-boot.version`, `spring-ai-bom`, `resilience4j-bom`,
  `springdoc.version`, `maven-surefire-plugin`) is declared exactly once in
  the root POM's `<properties>`/`dependencyManagement`/`pluginManagement` and
  inherited by every module via `<parent>`, with no module re-declaring its
  own version. Confirmed by diffing the actual merged commits, not assumed.
  Removed the 5 redundant per-module entries; kept `docker` per-directory
  (those genuinely are separate `Dockerfile`s) and `github-actions` at root.
- **`ignore` rules for 3 specific major-version bumps**, added only after
  they proved themselves real breaks, not preemptively: Spring Boot
  (`spring-boot-dependencies`), Spring AI (`spring-ai-bom`), and springdoc
  (`springdoc-openapi-starter-webmvc-ui`), each scoped to
  `update-types: ["version-update:semver-major"]` only — patch/minor updates
  for these three are still proposed normally. A framework major-version
  migration is a deliberate, planned decision, not something to re-propose
  every week until someone merges it by accident.
- **`.github/workflows/codeql.yml`**: `java-kotlin` static analysis via
  `github/codeql-action`, on push/PR to `main` plus a weekly cron, `build-mode:
  manual` using the same `actions/setup-java` + `./mvnw` pattern `ci.yml`
  already uses (autobuild can't be trusted to replicate that setup).

## Consequences
- **Verified for real against the live repo, not just files committed.**
  Pushed the two new files to `main`; Dependabot opened **33 real PRs**
  within seconds (11 Maven patch/minor bumps × the then-6 directory entries,
  plus GitHub Actions and Docker bumps), and CodeQL completed a real run,
  visible in the repo's Security tab — both "done when" criteria from the
  roadmap satisfied by direct observation, not by trusting the config alone.
- **Triaged all 33 PRs on their actual CI result, not their label**: 15
  passed CI for real (`resilience4j-bom` 2.3.0→2.4.0, `maven-surefire-plugin`
  3.5.3→3.5.6, `actions/checkout` 4→7, `actions/setup-java` 4→5,
  `github/codeql-action` 3→4) — merged, then re-ran
  `./mvnw -B clean verify` locally after pulling: `BUILD SUCCESS` across all
  5 modules. 14 failed CI for real (Spring Boot 3.5.3→**4.1.0**, Spring AI
  **1.0.0→2.0.0**, springdoc 2.8.6→**3.0.3** — genuine framework
  major-version jumps) — closed with an explanatory comment, and suppressed
  via the `ignore` rules above so they don't reopen weekly. 4 Docker JRE
  bumps (`eclipse-temurin` 21-jre→25-jre) were left open deliberately: `ci.yml`
  never builds the Docker image, so their "passing" CI check doesn't actually
  validate the image — merging on that false signal would be worse than
  leaving them for a manual `docker build` test.
- **`vulnerability-alerts`/"Dependabot security updates" is still off** at
  the repo-settings level (checked via `gh api .../vulnerability-alerts` →
  404 disabled) — a repo settings change, deliberately left for the user to
  flip explicitly rather than mutated via `gh api` unasked; doesn't block
  this phase's "done when" criteria, which only require the scheduled
  version-update PRs and CodeQL to be real and working, both confirmed.
- **A real, unrelated branch mix-up caught and fixed during this phase**: a
  commit briefly landed on a stray local branch
  (`appmod/java-upgrade-20260729200626`, created by a separate, unrelated
  automated tool run in this same working directory) instead of `main`.
  Caught via `git log`/`git branch` before pushing anything, fixed with a
  fast-forward merge back onto `main` — `main` itself was never at risk.
