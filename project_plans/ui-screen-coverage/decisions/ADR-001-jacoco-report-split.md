# ADR-001: JaCoCo Report Split — Android vs. JVM

**Date**: 2026-10-13
**Status**: Accepted (with unresolved question on AC-8 target)

## Context

The project generates two separate JaCoCo coverage reports:

- `jacocoTestReport` — JVM-only; driven by `./gradlew :kmp:jvmTest` / Gradle JVM test task. Covers JVM and desktop code paths.
- `jacocoAndroidTestReport` — Android (Robolectric); driven by `./gradlew :kmp:testDebugUnitTest`. Covers Android code paths including Compose UI tests.

The incremental diff-coverage gate introduced in PR #402 unions both reports to flag 0%-covered diff lines at CI time.

Acceptance Criterion AC-8 in `requirements.md` reads: *"Overall `ui.screens` line coverage ≥ 55% (up from 37.5%) as reported by `./gradlew :kmp:jacocoTestReport`."*

This plan's new tests are all Robolectric tests (`androidUnitTest`). They feed `jacocoAndroidTestReport`, **not** `jacocoTestReport`. The JVM report baseline (37.5%) will not move from these tests alone — it measures JVM test runs, not Android.

## Decision

1. All new tests in this project are written as Robolectric `androidUnitTest` tests and feed `jacocoAndroidTestReport`.
2. Implementation proceeds as planned — the diff-coverage gate in PR #402 will capture the delta regardless of which individual report is checked.
3. AC-8's reference to `jacocoTestReport` is treated as a likely authoring error. Before measuring the ≥55% target, Tyler must confirm which report (JVM or Android) the gate actually compares against.
4. If the gate checks the JVM report only, some tests in this plan may need to be ported to `jvmTest` (as `businessTest` or Compose UI tests, if a display is available) to move that number. This is flagged as an unresolved question in `plan.md`.

## Consequences

- New tests are not added to `jvmTest` unless the AC-8 clarification requires it.
- Coverage measurement in Story 3.1.1 uses `./gradlew :kmp:testDebugUnitTest :kmp:jacocoAndroidTestReport` as the primary signal.
- If Tyler confirms the gate checks `jacocoTestReport`, Story 3.1.1 must be revisited and a subset of pure-logic tests may need to move to `businessTest` or `commonTest` (which feeds the JVM report).
