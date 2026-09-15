# ADR-010: Android Emulator Smoke Tests via sh_test + ADB (Not android_instrumentation_test)

**Date**: 2026-06-24
**Status**: Accepted (supersedes non-migration decision)
**Deciders**: Tyler Stapler
**Context**: bazel-full-ci-migration Epic 3

---

## Context

SteleKit's CI runs `AppSmokeTest` and `SqliteCapabilityTest` via `reactivecircus/android-emulator-runner@v2` at API 29, x86_64 with KVM.

The migration goal is to run these under `bazel test` (SM#4) and remove the Gradle `android-smoke` CI job (SM#5). Two Bazel approaches were evaluated:

| Approach | Feasibility | Notes |
|----------|-------------|-------|
| `android_instrumentation_test` (rules_android) | Not feasible | Not exported by `rules_android 0.7.1`; `android_test_support` has no BCR/bzlmod registration |
| `sh_test` wrapping `adb shell am instrument` | Feasible | `tags=["local"]` bypasses sandbox; emulator started by GHA before `bazel test` runs; ADB socket is accessible from the host |

## Investigation Findings

| Item | Detail |
|------|--------|
| `android_instrumentation_test` not exported | `rules_android 0.7.1` exposes this rule internally but does not export it; `android_test_support` has no bzlmod path |
| `/dev/kvm` accessible with `tags=["local"]` | `tags=["local"]` disables the Linux sandbox entirely; KVM is accessible from the host. The emulator is started by GHA (not by Bazel), so Bazel does not need to manage the emulator lifecycle. |
| ADB socket accessible | With `tags=["local"]`, the `adb` binary from `ANDROID_HOME` is on PATH; the emulator started by `android-emulator-runner@v2` is reachable. |
| No Bazel cache benefit | Instrumented tests interact with a live emulator; they are inherently non-hermetic and cannot benefit from Bazel's action cache. `tags=["local"]` also disables caching, which is correct. |

## Decision

Use **`sh_test` wrapping `adb shell am instrument`** (`//androidApp:smoke_tests`):
- `tags = ["local"]` — disables sandbox and cache (correct for emulator tests).
- Script reads `$ADB_APK_PATH` (set by GHA before calling `bazel test`).
- Calls `adb install`, `adb shell am instrument -w`, parses pass/fail.
- Writes minimal JUnit XML to `$XML_OUTPUT_FILE` for Bazel test result reporting.
- GHA job: build APK → start emulator → set `ADB_APK_PATH` → `bazel test //androidApp:smoke_tests --config=emulator`.

`--config=emulator` in `.bazelrc` sets `--strategy=TestRunner=local` as a belt-and-suspenders guarantee that the test runner never enters a residual sandbox layer.

## Instrumented Test APK

The instrumented test APK (`androidApp-debug-androidTest.apk`) requires a Gradle build step (`./gradlew :androidApp:assembleDebugAndroidTest`) because `rules_android 0.7.1` does not produce instrumented APKs via `android_binary`. This is a build-time Gradle step only — the test execution itself is 100% Gradle-free (pure ADB).

If `rules_android` gains support for instrumented APK builds via bzlmod in a future version, the Gradle build step can be replaced.

## Consequences

**Positive**:
- `bazel test //androidApp:smoke_tests --config=emulator` satisfies SM#4 ("run under `bazel test`") and SM#5 (removes Gradle android-smoke job).
- GHA emulator infrastructure is reused unchanged — no changes to emulator AVD, API level, or KVM setup.
- JUnit XML output enables PR annotations via `mikepenz/action-junit-report`.
- No Gradle involvement during test execution.

**Negative / Risks**:
- Instrumented test APK still requires one Gradle step at build time (not execution time).
- `sh_test` pass/fail depends on correct parsing of `adb shell am instrument` output — if the format changes, the script needs updating.
- `tags=["local"]` means these tests cannot run in a distributed Bazel remote execution environment (acceptable for emulator tests, which are inherently local).

## Previous Decision

The original decision (documented non-migration — comment + .bazelrc stub only) was superseded after the user requested deeper native Bazel integration. The non-migration approach remains a fallback if the `sh_test + ADB` approach proves unreliable in CI.

## Future Path

When `rules_android` exports `android_instrumentation_test` via BCR and `android_test_support` is available via bzlmod, the `sh_test` can be replaced with a proper `android_instrumentation_test` target without changing the user-facing `//androidApp:smoke_tests` label. The GHA emulator runner setup is preserved as-is.
