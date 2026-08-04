# ADR 0040: Mockito as an explicit Surefire Java agent (JDK-vendor portability)

## Status
Accepted

## Context
`docs/ROADMAP.md` Tier 1 #14. Every test run this entire session logged a
real, live warning:
```
Mockito is currently self-attaching to enable the inline-mock-maker. This will
no longer work in future releases of the JDK. Please add Mockito as an agent
to your build as described in Mockito's documentation.
...
WARNING: Dynamic loading of agents will be disallowed by default in a future
release.
```
Mockito's default inline mock maker self-attaches Byte Buddy at runtime — a
JVM mechanism the JDK itself is deprecating. Checked directly before treating
this as urgent: `./mvnw -pl platform-common test` passed clean on this
project's own machine (Java 21.0.7, Oracle Corporation, macOS) — the warning
never became an actual test failure here. But a real, confirmed blind spot
exists regardless: `.github/workflows/ci.yml` pins **Temurin**, not Oracle's
JDK, so CI has never exercised whatever vendor-specific self-attach behavior
a real Oracle-JDK machine (or a future JDK release that actually enforces the
warning) would hit. The build's test behavior was silently depending on which
JDK vendor/version happened to run it.

## Decision
Configure Mockito explicitly as a `-javaagent` on Surefire's `argLine` —
Mockito's own officially documented fix, linked directly from the warning
text itself — instead of relying on runtime self-attach:

- **`maven-dependency-plugin:properties`** (root `pom.xml`'s
  `pluginManagement`, bound to the `initialize` phase, well before Surefire's
  `test` phase reads `argLine`) resolves `org.mockito:mockito-core`'s own
  jar path into the Maven property `${org.mockito:mockito-core:jar}` — no
  new dependency needed, `mockito-core` is already transitively present via
  `spring-boot-starter-test` in every module.
- **Surefire's `argLine`** becomes
  `@{argLine} -javaagent:${org.mockito:mockito-core:jar}`. The `@{...}`
  delayed-evaluation syntax (not `${...}`) is the specific detail that makes
  this work: `jacoco-maven-plugin`'s `prepare-agent` goal also writes into
  this same `argLine` property (its own dynamic `-javaagent:jacoco-agent.jar=...`
  string) — `@{argLine}` defers resolution to the moment Surefire actually
  runs, picking up whatever JaCoCo already set by then, rather than racing it
  at POM-parse time the way a plain `${argLine}` self-reference would.
- **`maven-dependency-plugin` isn't a default-lifecycle-bound plugin**
  (same category as `jacoco-maven-plugin`, already documented as such in
  this same `pom.xml`) — each of the 5 modules needed its own bare
  `<plugin>` reference to actually activate the execution, mirroring the
  exact pattern already used for JaCoCo.
- **`maven-dependency-plugin` 3.11.0** — checked directly (web search, not
  assumed) for the actual latest stable release rather than guessing a
  version.

## Consequences

### Verified for real, not just configured
`./mvnw clean verify` across all 5 modules: 179 tests, 0 failures, 0 errors —
and **zero** occurrences of "self-attaching" or "Dynamic loading of agents"
anywhere in the full build log, where every previous run this session showed
that warning on every single module. All 5 modules' `target/site/jacoco/
jacoco.xml` reports were still generated correctly (non-trivial sizes,
32KB-108KB), confirming the two javaagents — JaCoCo's own coverage agent and
Mockito's inline-mock-maker agent — coexist on the same `argLine` without
conflict, exactly as the `@{argLine}` combination pattern is meant to
guarantee.

### Not yet independently verified on Temurin (CI)
This machine only has Oracle's JDK 21 to test against locally. The fix is
JDK-vendor-independent by construction (an explicit, statically-configured
`-javaagent` no longer depends on any vendor-specific self-attach behavior at
all), so it should behave identically on Temurin — but the roadmap's own
"done when" criterion named both vendors explicitly, and this ADR does not
claim to have observed CI's own log for this specific change. The next CI
run against this commit is the actual confirmation for the Temurin half.

### Scope: build configuration only, no test code changed
No test file needed any change — the fix lives entirely in `pom.xml` (root)
and the 5 modules' own `<build><plugins>` blocks. `RateLimitFilterTest`/
`CorrelationIdFilterTest` (the two tests named in the roadmap item as
already passing clean) continue to pass unchanged, now without the warning
their prior clean run still happened to log alongside.
