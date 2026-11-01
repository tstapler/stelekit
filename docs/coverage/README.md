# Coverage program notes (Epic 11, Stories 11.1, 11.2)

`coverage-map.json` / `coverage-map.md` rank packages by gap x tier weight x churn. Regenerate from a `coverage-baseline` workflow artifact (`gh run download <id> -n coverage-baseline`) with the command in `SCHEMA.md`. Tiers live in `coverage-tiers.yml`.

## First map

Built from `coverage-baseline` run 40829801185 (scheduled run artifact, commit `a543aecb95`). Churn is `git log --since=90.days.ago` at generation time, not at the artifact's commit.

Line coverage by tier, summed over packages (from `coverage-map.json`):

| Tier | JVM (`jvmTest`) | Robolectric (`testDebugUnitTest`) | Plan target (line) |
|---|---|---|---|
| T1 | 19,318/24,515 = 78.8% | 7,113/25,376 = 28.0% | 85 |
| T2 | 6,319/7,560 = 83.6% | 2,721/6,547 = 41.6% | 70 |
| T3 | 20,490/37,248 = 55.0% | 14,008/38,961 = 36.0% | 40 |

Measured-informed notes (observations, not new gates):

- The two source sets are separate rows and are not summed. Robolectric runs a small subset of `jvmTest`, so its low numbers are not a gap in themselves. Targets apply to the `jvm` row; the `android` row ranks `androidMain` code that only that runner exercises.
- JVM T1 is 78.8% against 85. Below target on the `jvm` row: `db` 73.9%, `repository` 72.2%, `git` 82.1%, `migration` 79.8%. At or above: `merge` 93.1%, `vault` 90.8%, `parsing` 96.9%, `query` 93.5%, `outliner` 90.9%, `security` 100%.
- The plan's stale 2026-10-16 figure for `git/` (5%, 84 of 1,731 lines) is superseded: 82.1% (2,764/3,366) on the JVM row. Story 11.4 waves should come from `coverage-map.md`, where `db` and `repository` rank above `git`.
- T2 and T3 already meet the plan's 70 / 40 targets on the JVM row, so the plan targets are not tightened here.
- Mutation is not measured (PIT Tier-1 timed out at baseline), so every `mutation` field reads `measured: false`.

## Ratchet floors

Each package's measured line value in `coverage-map.json` is its floor. No ratchet job is built here: the per-PR `diffCoverage` gate (80) and report-only PIT remain the only coverage gates, and Story 11.3 (nightly ratchet) is deliberately not implemented.

## Tier granularity

One tier per top-level package. `asset` (T1 write paths, T2 pipeline) is T2 and `platform` (T2 `google`, T3 glue) is T3. `model`, `calibration` and `docs` are judgement calls (T3, T4, T4); reclassify by editing `coverage-tiers.yml`.

## ADR-003 addendum

`project_plans/unwired-features-and-test-coverage/` holds only `decisions/ADR-006-addendum-spike-results.md` in this branch, so the ADR-003 addendum is recorded here instead.
