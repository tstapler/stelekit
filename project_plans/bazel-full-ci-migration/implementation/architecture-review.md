# Architecture Review: bazel-full-ci-migration
**Date**: 2026-06-24
**Reviewer**: Claude (architecture-review subagent)
**Verdict**: CONCERNS (0 blockers, 5 concerns, 3 nitpicks)

No architecture constitution (`ADR-000`) exists; review applies standard Clean Architecture,
SOLID, and build-system hermiticity principles grounded in the existing codebase conventions.

---

## Blockers

_None._

---

## Concerns

- [ ] **Story 1.2.1** — `buildSrc/BUILD.bazel` `kt_jvm_library` lists `detekt-api` as a
  regular `deps` entry, but the Gradle `build.gradle.kts` declares it `compileOnly`. In
  Bazel `kt_jvm_library`, `deps` are propagated transitively; using `deps` instead of
  `neverlink = True` (or splitting to `exports`) will embed `detekt-api` (which pulls in
  `kotlin-compiler-embeddable`) onto the runtime classpath of any target that depends on
  `//buildSrc:detekt_rules`. The `detekt_test` rule provides its own Detekt runtime, so
  the duplicate `detekt-api` JAR on the plugin classpath may cause `LinkageError` or
  `ClassCastException` for `RuleSetProvider` instances. **Recommendation**: add
  `neverlink = True` to the `@maven//:io_gitlab_arturbosch_detekt_detekt_api` dep entry
  inside the `kt_jvm_library`, mirroring the Gradle `compileOnly` semantics exactly.

- [ ] **Story 1.2.1 / Unresolved Question 1** — Version skew: custom rules are compiled
  against `detekt-api:1.23.7`; `rules_detekt 0.8.1.13` bundles Detekt 1.23.8. The ADR
  acknowledges this but defers validation. The concern is structural: if any of the 14
  custom rules override methods whose signatures changed between `.7` and `.8`, Detekt
  will silently skip those rules (it catches `NoSuchMethodError` internally and disables
  the rule without a CI-visible failure). This produces a false-green lint result. The
  plan has no step that verifies the custom rules actually fired (e.g., a test that
  introduces a known violation and asserts the run fails). **Recommendation**: add a
  Story 1.4.x smoke-check step: introduce a synthetic rule violation on a feature branch,
  run `bazel test //kmp:detekt`, and assert the exit code is non-zero before the
  `continue-on-error` phase is removed in Epic 1.5.

- [ ] **Story 1.3.1** — The `detekt_test` glob excludes `src/generated/**` but the
  actual generated source paths in this repo are `src/generated/sqldelight/` and
  `src/generated/sqldelight-telemetry/` (both under `kmp/src/`). The glob pattern
  `src/generated/**` is relative to `kmp/`; Bazel evaluates globs relative to the
  package root, so this should correctly match `kmp/src/generated/**`. However, the
  plan's glob does not include `src/wasmJsMain/kotlin/**` in the analysed sources. If
  the Detekt CI job currently analyses wasmJsMain (check `kmp/build.gradle.kts`
  `detekt { source = ... }`), the Bazel target will produce a narrower analysis than
  Gradle, creating a hidden coverage gap. **Recommendation**: before Story 1.3.1, read
  the Gradle Detekt source-set configuration and ensure the Bazel glob list matches it
  exactly. Add `src/wasmJsMain/kotlin/**/*.kt` to the glob if it is currently covered.

- [ ] **Story 2.2.1 / Epic 2.2** — The `bazel-roborazzi` CI job is described as
  "main-branch push only, `continue-on-error: true`", running `bazel run
  //kmp:record_android_screenshots`. `sh_binary` with `tags=["local"]` runs inside the
  Bazel server working directory, not the workspace root; the embedded shell script must
  locate the Gradle wrapper via `$BUILD_WORKSPACE_DIRECTORY` (set by Bazel for
  `bazel run` targets) rather than a hard-coded relative `./gradlew`. The existing
  `//kmp:web_dist` genrule uses `$(dirname $(realpath gradlew))` which only works in
  `genrule` context (not `sh_binary` context). If the shell script uses `./gradlew`
  directly it will fail when `bazel run` is invoked from a directory other than the
  workspace root. **Recommendation**: the `record_*.sh` scripts must begin with
  `cd "$BUILD_WORKSPACE_DIRECTORY"` before invoking `./gradlew`, and Story 2.1.1 /
  2.1.2 must document this requirement explicitly.

- [ ] **Phase 0, Story 0.1.1** — The plan adds `permissions: checks: write` at the
  workflow level of `bazel-ci.yml`. The existing `bazel-ci.yml` has `permissions:
  contents: read` at the workflow level with no job-level overrides. Adding `checks:
  write` at the workflow level grants it to every job, including `bazel-android` (APK
  build only — no test results) and `bazel-web` (genrule — no test results). GHA's
  principle of least privilege recommends scoping `checks: write` to only the jobs that
  actually upload test reports (`bazel-jvm`, `bazel-android-tests`, and the new
  `bazel-detekt`). **Recommendation**: move `checks: write` to job-level `permissions`
  blocks on the three test jobs rather than the workflow level, matching the pattern
  already in use in `ci.yml`.

---

## Nitpicks

- The plan refers to "14 custom Detekt rules" in the glossary and "16 files in
  `buildSrc/src/main/kotlin/dev/stapler/detekt/`" in ADR-008. The actual file count on
  disk is 16 (14 rule files + `SteleKitRuleSetProvider.kt` + one more). The discrepancy
  is cosmetic but will confuse implementers. Update the glossary to say "15 rule
  implementation files + 1 provider file = 16 source files total".

- Story 1.5.1 ("Graduate Detekt — remove `continue-on-error`, delete Gradle `lint` job")
  has no graduation criterion beyond "3 green runs". Define this more precisely: 3
  consecutive green runs on main-branch pushes (not PRs), since PR caches can hide
  Detekt plugin-loading failures that only surface on a cold cache.

- The `.bazelrc` stub planned in Story 3.1.1 (`build:emulator`) should use `build:` not
  `test:` prefix so the stub is consistent with how `build:android` is defined in the
  existing `.bazelrc`. The plan's text says `--config=emulator` which is a `build:`
  namespace; ensure the stub line reads `build:emulator # reserved` not `test:emulator`.
