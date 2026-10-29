# ADR-003: Repo-wide coverage program: JaCoCo as the number, tiered PIT, per-tier ratchet, map artifact

**Status**: Accepted; targets are INFERRED until the Story 0.1 baseline runs, then amended here | **Date**: 2026-10-27

## Context
Existing gates: JaCoCo 0.8.12 reports (`kmp/build.gradle.kts:1521`), `diffCoverage` threshold 80, PIT 1.17.4 change-scoped with `mutate.maxClasses` 40, report-only in CI. Numbers on disk are stale and partial (JVM 33.9% lines, Android 12.0%, from change-scoped runs dated 2026-10-16); no mutation number exists. Bazel is canonical for tests but has no JaCoCo. CI memory is a first-class constraint (Bazel Android dex kills, `jvmTest` OOM/hang history).

## Decision
- **Measurement of record**: Gradle JaCoCo JVM + Robolectric XMLs, merged by package by `scripts/ci/coverage-map.py`. XMLs from Bazel are refused (not comparable).
- **Tiers** T1-T4 by risk (see plan Epic 11); score = uncovered lines x tier weight x log2(2 + commits in 90 days).
- **Mutation**: PIT mutates JVM bytecode only. T1 packages run nightly, report-only until 3 stable runs, then gating; T2 stays per-PR on changed classes; no UI mutation. A timed baseline run (Task 0.1c) confirms the cadence before it is fixed.
- **Gating**: PR path keeps `diffCoverage` 80 only. A nightly `CoverageRatchet` fails when a T1/T2 package falls below its recorded floor and files an issue. No repo-wide percentage gate on PRs.
- **Artifact**: `docs/coverage/coverage-map.json` (schema in `SCHEMA.md`) and rendered `.md`, committed so gaps are diffable.
- **Reachable vs unreachable** coverage is reported separately so deleting dead code does not read as a regression.
- **Test placement and flake rules** are enforced by `TestPlacementAuditTest` and a baselined `TestHygieneAuditTest`.

## Alternatives rejected
Kover (second engine, two disagreeing numbers); Bazel coverage (unproven for `rules_kotlin`); Arcmutate and SaaS (paid or upload confidential source); repo-wide PR gate (CI memory, punishes unrelated PRs); LLM-estimated coverage (numbers must come from tools).

## Consequences
First map exists after one full baseline run (CI wall-clock). Targets are replaced by measured-informed ones in an addendum to this ADR.

## Addendum 2026-10-29: coverage is per pull request, not nightly

The owner asked for coverage to be incremental per PR instead of a nightly job. `.github/workflows/ci.yml` already runs a per-PR job, "Incremental coverage + mutation (changed files)": `:kmp:diffCoverage` fails a PR whose changed lines are under 80% covered (jacoco over `jvmTest` and `testDebugUnitTest`, selected test classes only), and PIT runs report-only on changed classes. That job is the coverage gate.

What changes against the original decision:
- No nightly `CoverageRatchet`, no nightly issue filing and no scheduled Tier 1 PIT run. The U0a baseline run of PIT over Tier 1 timed out at 1h20m with no survivor count, so a scheduled run was not workable as written; PIT stays per PR on changed classes.
- `coverage-baseline.yml` keeps `workflow_dispatch` only, for producing a full-package map on demand.
- Story 11.1 and 11.2 still produce the package map and `coverage-tiers.yml`, generated from Gradle XMLs; Story 11.3 reduces to the existing PR path plus the wiring gate. Story 11.4 waves are driven by the map and merge as ordinary PRs, each under the 80% changed-line gate.

Observed limits of the per-PR job (PRs #507, #515, #520): it runs 200-400 selected test classes under jacoco on a 2-core runner. It produced `ComposeTimeoutException` in unrelated Compose UI tests and one `OutOfMemoryError` in the Kotlin compiler on a very large composable lambda. Treat a red result there as a signal to root-cause (extract composables, fix the slow test), not to retry until green.
