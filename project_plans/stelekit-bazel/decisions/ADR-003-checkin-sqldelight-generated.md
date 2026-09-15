# ADR-003: Check In SQLDelight Generated Sources

## Status
Accepted

## Date
2026-05-17

## Context

SteleKit uses SQLDelight 2.3.2 (`app.cash.sqldelight`) to generate type-safe Kotlin from
`.sq` schema files located at `kmp/src/commonMain/sqldelight/`. In a Gradle build,
SQLDelight's Gradle plugin runs automatically during compilation and emits generated Kotlin
into `kmp/build/generated/`.

Bazel requires that all build inputs be declared in the action graph before the build runs
(hermetic, sandboxed execution). There are two problems with the current Gradle-driven
code generation:

1. **`square/sqldelight_bazel_rules` is SQLDelight 1.x only.** The official Bazel rule
   (`sqldelight_codegen`) was written against `com.squareup.sqldelight` (the 1.x Maven
   group). SQLDelight 2.0 changed the group ID to `app.cash.sqldelight` — a breaking
   change. The `sqldelight_bazel_rules` repository has received no substantive commits
   after the 2.0 release and has no 2.x-compatible release. Using the 1.x rule against
   SteleKit's 2.3.2 schema would require forking the rule and porting it, which is
   significant engineering work.

2. **SQLDelight has no standalone CLI.** The SQLDelight code generator is tightly coupled
   to the Gradle plugin. A custom Bazel `genrule` invoking it would need to shell out to
   Gradle, defeating the purpose of a hermetic Bazel build. The README for
   `sqldelight_bazel_rules` acknowledges this: "SQLDelight doesn't presently have a CLI or
   non-Gradle entry point, so the Bazel tooling provides this front-end until it can be
   upgraded."

The practical alternative is to treat generated sources as committed source files that
Bazel builds as an ordinary `kt_jvm_library`, with Gradle responsible for regenerating
them when the schema changes.

## Decision

SQLDelight-generated Kotlin sources will be **checked into version control** under
`kmp/src/commonMain/generated/sqldelight/` and included in the `commonMain` BUILD
target's `srcs` glob.

**Workflow for schema changes:**
1. Developer modifies `.sq` files under `kmp/src/commonMain/sqldelight/`.
2. Developer runs `./gradlew generateCommonMainSteleDatabaseInterface` (or the equivalent
   Gradle task) to regenerate the Kotlin.
3. Developer copies the output from `kmp/build/generated/` into
   `kmp/src/commonMain/generated/sqldelight/` and commits both the schema change and the
   generated code.

**CI enforcement** (schema/code divergence prevention): A CI check runs
`./gradlew generateCommonMainSteleDatabaseInterface` and diffs the output against the
committed generated sources. If they differ, the check fails. This mirrors the existing
README sync check (`bash scripts/generate-readme.sh && git diff --exit-code README.md`).

**Pre-commit hook (optional, recommended)**: A pre-commit hook can run the same diff
check to catch divergence before push.

The `.gitignore` entry for `kmp/src/commonMain/generated/` must be removed (or the
directory must be explicitly un-ignored) so that generated sources are tracked by git.

## Consequences

**Positive:**
- Zero new Bazel rule complexity: the generated files are plain Kotlin source files
  compiled by the existing `kt_jvm_library` / `kt_android_library` rules.
- Bazel builds are fully hermetic — no network access or Gradle subprocess invocation
  during `bazel build`.
- Unblocks Phase 1 immediately without depending on a future SQLDelight 2.x Bazel rule.
- Code review sees both the `.sq` schema change and the resulting generated Kotlin in the
  same PR, which is useful for reviewers.

**Negative:**
- Generated source files live in version control, which is generally considered an
  anti-pattern. The repository size grows slightly with each schema change (though
  SQLDelight output is compact text).
- There is a risk of schema/generated-code divergence if a developer forgets to regenerate
  after a schema change. CI enforcement mitigates but does not eliminate this risk.
- The generation step requires Gradle and a local JVM to be available on the developer's
  machine even if they only use Bazel for building. This is not a new requirement (Gradle
  is already required for iOS/WASM builds), but it is worth documenting.
- If SQLDelight releases a 2.x-compatible Bazel rule in the future, migrating away from
  checked-in sources requires removing the generated directory, adding the Bazel rule, and
  cleaning up CI. This is straightforward work but adds a future migration step.

## Alternatives Considered

**Fork `sqldelight_bazel_rules` and port to 2.x**: Technically possible but requires
reverse-engineering how the Gradle plugin invokes the SQLDelight compiler, writing a
hermetic Bazel wrapper, and maintaining the fork across SQLDelight releases. Estimated
effort: 2–4 weeks. Not justified for Phase 1. Revisit if SQLDelight ships an official CLI.

**Custom `genrule` invoking the SQLDelight Gradle task**: Breaks Bazel's hermetic sandbox
model. Gradle runs Maven resolution at configure time, which violates the no-network-in-build
constraint. Rejected.

**Run Gradle entirely for commonMain sources and only use Bazel for jvmMain/androidMain**:
Overly complex split; Bazel's incremental graph benefits are maximized when `commonMain`
is also a Bazel target. Rejected.
