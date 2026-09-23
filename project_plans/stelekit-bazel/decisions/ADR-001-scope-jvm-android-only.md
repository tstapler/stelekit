# ADR-001: Scope Bazel Migration to JVM + Android Only (Phase 1)

## Status
Accepted

## Date
2026-05-17

## Context

SteleKit targets four platforms from a single KMP codebase: Desktop (JVM), Android, iOS
(Kotlin/Native), and Web (Kotlin/WASM). The goal of the Bazel migration is faster
incremental builds and a remote-cache-backed CI pipeline.

Two hard constraints prevent a full cross-platform Bazel migration:

1. **No `rules_kmp` exists.** The `rules_kotlin` ruleset (v2.3.20, the current stable
   release on the Bazel Central Registry) provides `kt_jvm_*` and `kt_android_*` rules
   only. There are no `kt_multiplatform_*` rules, no `expect`/`actual` dispatch, and no
   source-set hierarchy support. GitHub issue #567 ("Kotlin Bazel Cross-Platform Roadmap")
   has been open since 2021 with no concrete implementation shipped.

2. **iOS and WASM/JS targets cannot be compiled in Bazel.** Kotlin/Native iOS compilation
   requires a macOS host and the Kotlin/Native toolchain distribution (`~/.konan/`). Bazel
   forbids network downloads during sandboxed builds, and no stable Bazel ruleset can
   declare the Kotlin/Native toolchain as a hermetic repository dependency. Similarly,
   Kotlin/WASM has no Bazel support. `rules_kotlin`'s `kt_js_library` is limited and does
   not cover the `wasm-js` target.

3. **Compose Multiplatform K2 wiring is actively broken in Bazel.** `rules_kotlin` issue
   #1388 (filed October 2025) documents that Compose BOM 2025.x + Kotlin 2.x fails to
   activate the Compose compiler plugin when using `kt_compiler_plugin`. This affects
   Desktop and iOS Compose targets. Jetpack Compose on Android (non-multiplatform) works
   with a `suppressKotlinVersionCompatibilityCheck` workaround, but the multiplatform
   variant does not.

The JVM (Desktop) and Android source sets — `commonMain`, `jvmMain`, `androidMain` — are
pure JVM bytecode targets. They compile correctly today with `kt_jvm_library` and
`kt_android_library`. These source sets represent approximately 80% of the SteleKit
codebase by line count and are the primary targets for incremental-build improvement.

## Decision

Phase 1 of the Bazel migration covers **JVM and Android targets only**:
- `commonMain` compiled as `kt_jvm_library` (JVM flavor) and `kt_android_library`
  (Android flavor)
- `jvmMain` compiled as `kt_jvm_library`; desktop binary as `kt_jvm_binary`
- `androidMain` compiled as `kt_android_library`; APK via `android_binary`
- `jvmTest` and `businessTest` compiled as `kt_jvm_test`
- `androidUnitTest` compiled as `android_local_test` with Robolectric

**iOS (`iosMain`) and WASM (`wasmJsMain`) targets remain in Gradle for Phase 1.**

Phase 2 (future, no committed timeline) will reassess iOS/WASM once a usable KMP Bazel
ruleset or JetBrains Build Tools API (KEEP-421) integration is available.

## Consequences

**Positive:**
- Unblocks the migration immediately using only stable, production-ready rulesets.
- The JVM and Android targets gain Bazel's incremental build graph, remote caching, and
  parallel action execution.
- Reduces the blast radius of migration risk; iOS/WASM developers are not disrupted.

**Negative:**
- CI must run two build systems in parallel: Bazel for JVM/Android, Gradle for iOS/WASM.
  This increases CI complexity and job count.
- Developers must understand which targets belong to which system, creating a split mental
  model.
- Gradle must be kept healthy for iOS/WASM even after JVM/Android are migrated; Gradle
  cannot be retired until Phase 2 is complete.
- `commonMain` sources are compiled twice by Bazel (once per platform flavor), slightly
  increasing JVM/Android build time compared to a single KMP compilation.

## Alternatives Considered

**Full KMP migration now**: Infeasible. No stable Bazel ruleset supports Kotlin/Native or
WASM. Custom Starlark rules for the Kotlin/Native toolchain are a multi-month engineering
effort with high maintenance burden.

**Keep everything in Gradle**: Viable but foregoes the incremental build and remote-cache
benefits that motivated this migration. Does not address the CI speed goals in the
requirements.

**Migrate to Buck2 instead of Bazel**: Buck2 (Meta) has stronger Kotlin incremental
compilation via KEEP-421 (announced August 2025), but shares the same fundamental gap:
no KMP Native/WASM support. Switching to Buck2 would incur the same Phase 1 scope and
add a steeper learning curve with a smaller community than Bazel.
