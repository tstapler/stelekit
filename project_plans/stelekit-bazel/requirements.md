# SteleKit Bazel Migration — Requirements

## Summary

Migrate SteleKit's build system from Gradle to Bazel to improve incremental local build performance. The migration is a full replacement — Gradle is removed entirely once all targets are working under Bazel.

## Problem Statement

Gradle incremental builds are unreliable or too slow on the developer's machine, causing long feedback loops during development. Bazel's hermetic, action-graph-based incrementality should deliver faster local rebuilds by reusing cached actions correctly across builds.

## Goals

| # | Requirement | Priority |
|---|-------------|----------|
| G1 | All KMP targets build under Bazel: JVM/Desktop, Android, iOS, WASM/JS | Must Have |
| G2 | Full Gradle replacement — no `gradlew` or `build.gradle.kts` in the final state | Must Have |
| G3 | Local incremental builds are measurably faster than Gradle baseline | Must Have |
| G4 | Remote caching via GitHub Actions cache (actions/cache or bazel-remote) | Should Have |
| G5 | CI passes using Bazel commands (mirrors current `./gradlew ciCheck` workflow) | Must Have |
| G6 | Developer experience preserved: `run`, `test`, `package` equivalents available | Must Have |

## Non-Goals

- Multi-language monorepo support (not needed now)
- Replacing Bazel with Buck2/Pants (out of scope)
- Supporting Windows build hosts (Linux + macOS are primary)

## Constraints

- Kotlin Multiplatform project — `rules_kotlin` alone does not cover KMP; `rules_kmp` (or equivalent) required
- SQLDelight 2.3.2 code generation must be preserved — Bazel must invoke the code generator or check in generated sources
- Compose Multiplatform UI compiler plugin must be wired into Kotlin compilation
- iOS builds require macOS host (cross-compilation is not in scope for initial migration)

## Rulesets & Toolchain

- **Primary KT rules**: `rules_kotlin` (bazelbuild/rules_kotlin) — JVM + Android Kotlin
- **KMP overlay**: `rules_kmp` or experimental KMP support — to be validated in research
- **Remote cache**: GitHub Actions `actions/cache` keyed on Bazel content hash, or `bazel-remote` sidecar in CI

## Current Build Commands (Gradle → Bazel equivalents needed)

| Gradle command | Required Bazel equivalent |
|----------------|--------------------------|
| `./gradlew run` | `bazel run //kmp:desktop_app` (or similar) |
| `./gradlew jvmTest` | `bazel test //kmp:jvm_tests` |
| `./gradlew allTests` | `bazel test //...` |
| `./gradlew ciCheck` | `bazel test //... --config=ci` |
| `./gradlew installAndroid` | `bazel mobile-install //kmp:android_app` |
| `./gradlew packageDistributionForCurrentOS` | `bazel build //kmp:desktop_dist` |

## Success Criteria

1. `bazel build //...` succeeds from a clean checkout on Linux (JVM + Android targets at minimum)
2. Incremental rebuild after a single `.kt` file change takes < 5 s locally (vs. current Gradle baseline)
3. GitHub Actions CI passes using Bazel commands
4. All existing jvmTest tests pass under Bazel
5. SQLDelight-generated Kotlin is available to all targets (either via codegen rule or checked-in sources)

## Open Questions (for research)

- Is `rules_kmp` production-ready for Compose Multiplatform, or is a different approach needed?
- How does SQLDelight 2.3.2 integrate with Bazel (custom rule, Gazelle extension, or checked-in sources)?
- What is the migration path for the Compose compiler plugin (K2 + Bazel)?
- Is `bazel-remote` or native GHA cache the better fit for this project's CI budget?
