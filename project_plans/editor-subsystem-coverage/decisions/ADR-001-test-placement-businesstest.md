# ADR-001: Place `editor.commands` tests in `businessTest`, not `commonTest`

**Status**: Accepted (2026-10-14) — reviewed with no objection by the architecture and adversarial reviews
**Context item**: editor-subsystem-coverage (backlog c808071d)

## Context

`requirements.md` says tests belong in `commonTest` "per CLAUDE.md" and notes that `AllBusinessTests` may need the class registered. `research/stack.md` section 1 (verified against `kmp/BUILD.bazel`, `kmp/src/businessTest/kotlin/BUILD.bazel`, `kmp/src/commonTest/kotlin/BUILD.bazel`) shows:

- `//kmp:business_tests` globs `businessTest/**/*.kt` only and runs `AllBusinessTests`, a `@Suite` that executes only listed classes.
- `commonTest` is not globbed by Bazel; a file there reaches Bazel only if listed in `business_common_tests` srcs and imported into `AllBusinessTests`.
- Gradle `jvmTest` auto-discovers both source sets.

## Decision

Put `CommandManagerTest`, `CommandSystemRegistryTest` and `EssentialCommandsTest` in `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/` and register them in `AllBusinessTests.kt`.

## Consequences

- One import and one `SuiteClasses` entry per class; `AllBusinessTestsCompletenessTest` enforces it.
- Runs under Bazel (canonical) and Gradle `jvmTest` (JaCoCo path) without editing a BUILD file.
- Tests are JVM-only; wasmJs and iOS do not execute them. Accepted: the logic has no platform API and `commonMain` compiles for those targets in CI.
- Sibling of the existing keeper tests `editor/LinkInsertionTest.kt` and `ImageAttachCallbackContractTest.kt`.

## Alternatives rejected

- `commonTest` plus a `business_common_tests` srcs entry: needs two build-file edits and buys multi-platform execution nobody asked for. Revisit if wasm/iOS coverage of `editor.commands` becomes a goal.
