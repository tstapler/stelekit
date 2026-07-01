# ADR-008: Bazel Detekt via `rules_detekt` (BCR native rule)

**Date**: 2026-06-24
**Status**: Accepted
**Deciders**: Tyler Stapler
**Context**: bazel-full-ci-migration Epic 1

---

## Context

SteleKit's CI runs Detekt under Gradle (`./gradlew :kmp:detekt`). The goal of
Epic 1 is to replace this with a Bazel target so that `bazel test //...` covers
lint without any Gradle invocation.

Three approaches were evaluated:

| Approach | Key Strength | Key Weakness |
|----------|-------------|-------------|
| A: `detekt_test` from BCR `rules_detekt 0.8.1.13` | Hermetic, cacheable, first-class Bazel test with JUnit XML output | Requires building `buildSrc` JAR as `kt_jvm_library`; slight version skew (Detekt 1.23.7 → 1.23.8) |
| B: `genrule` wrapping `./gradlew :kmp:detekt` | Zero new module dependencies; identical Gradle behaviour | Must be `tags=["local"]` — no Bazel caching benefit; defeats the point of migration |
| C: Detekt CLI JAR via `rules_jvm_external` + `java_binary` genrule | No new BCR dep | High implementation complexity; must replicate plugin loading logic; not a standard pattern |

## Decision

Use **Approach A**: `detekt_test` from BCR module `rules_detekt 0.8.1.13`.

Specific implementation choices:
1. `bazel_dep(name = "rules_detekt", version = "0.8.1.13")` in `MODULE.bazel`.
2. `buildSrc/BUILD.bazel` with a `kt_jvm_library(name = "detekt_rules", ...)` compiling all 16 files in `buildSrc/src/main/kotlin/dev/stapler/detekt/` against `@maven//:io_gitlab_arturbosch_detekt_detekt_api`.
3. `detekt_test(name = "detekt", ...)` in `kmp/BUILD.bazel` with `plugins = ["//buildSrc:detekt_rules", "@maven//:io_nlopez_compose_rules_detekt"]`.
4. Compose rules added to Maven: `io.nlopez.compose.rules:detekt:0.4.27`.

## Consequences

**Positive**:
- `bazel test //kmp:detekt` is hermetically cached; unmodified source sets skip re-analysis.
- JUnit XML emitted automatically under `bazel-testlogs/` — compatible with `mikepenz/action-junit-report@v4`.
- No Gradle daemon startup cost in the Detekt CI job.

**Negative / Risks**:
- `rules_detekt 0.8.1.13` ships Detekt 1.23.8 internally; our custom rules are compiled against `detekt-api:1.23.7`. Patch-version skew (7→8) is low risk (Detekt API is stable across patch versions) but must be validated locally before merging.
- `SteleKitRuleSetProvider` must be discoverable via `META-INF/services/`. If the services file is absent from the `buildSrc` source tree (not auto-generated since `@AutoService` is not a dependency), it must be added as a hand-maintained resource file.
- Detekt silently skips plugin JARs that fail to load — add a smoke-check step locally (`bazel test //kmp:detekt` on a branch with a known-bad rule) to confirm plugin loading.

## Compliance

`rules_detekt` is Apache-2.0 licensed. No additional licensing concerns.
