# ADR-003: Enforcement strictness: warn-only, new-code enforce, ratchet

**Status**: Proposed
**Date**: 2026-10-11
**Deciders**: Repo owner (Tyler Stapler)
**Related**: ADR-001, ADR-002, `../implementation/plan.md` Phases A to C, decisions D1 to D3

## Context

Gates that fail on day one against legacy gaps get bypassed; gates that never fail get ignored (`../research/pitfalls.md` section 1). The repo already has the ratchet pattern (`.github/workflows/ci.yml:157-166`, the detekt baseline) and a bad exemption precedent: 10 of 15 `Screen` subclasses are `@HelpExempt`, and the policy text it cites does not exist (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/HelpExempt.kt:11`). Requirements set a noise budget of fewer than 1 false-positive failure per ~20 feature-touching PRs (`../requirements.md` Success Metrics).

Facts that shape the rollout: the PR-time test gate is Bazel `business_tests` (`.github/workflows/bazel-ci.yml:138`); `checkDocCoverage` is Gradle-only and unused by workflows; the migration (Story A1.3/A1.1) registers all 15 Screens, 13 settings categories and 24 command ids immediately, so registration integrity has no legacy debt, while content coverage does (about 12 features with no demo page and 7 with no site page by the `../research/features.md` A3 matrix, to be recomputed by T-A1.1.6).

## Decision

**Two axes, three phases.**

| Rule | Phase A | Phase B | Phase C |
|---|---|---|---|
| R2 entry covers nothing; R3 exemption invalid or expired; R4 duplicate id; R5 double-claimed candidate | FAIL (no legacy debt) | FAIL | FAIL |
| R1 candidate with no entry/exemption | WARN (report only) | FAIL (D2) | FAIL |
| Content: registered `demoPage`/`sitePage` missing, empty, not parsing, dangling wikilink | WARN for ids in baseline; WARN for others | FAIL for ids not in baseline (new code); baseline ids stay WARN | baseline may only shrink (FAIL on growth) |
| Baseline growth vs merge-base | FAIL from day one (the baseline exists at merge) | FAIL | FAIL |
| Exemption count growth vs merge-base | report | report | FAIL (C1.1) |
| Staleness (`sources` changed, docs/registry untouched) | WARN, job summary + comment | WARN; FAIL only for PRs titled `feat...` (D3) | same |

**Promotion criteria (measurable):** Phase A to B needs at least 20 feature-touching PRs under warn (counted by `scripts/docs-gate-report.sh`), FP rate (PRs labelled `docs-gate-fp` over feature-touching PRs) under 1 per 20, registry test green on both runners for the last 10 main commits, and owner approval D2. Staleness `--fail` needs its own sample of at least 20 feature-touching PRs including at least 10 `feat` PRs with the same FP bound and owner approval D3.

**Exemption policy.** Closed reason enum (`INTERNAL_SURFACE`, `NOT_USER_INITIATED`, `PLATFORM_SPECIFIC`, `PENDING_DEMO_WITH_ISSUE`, `DEFERRED_WITH_ISSUE`) + non-blank detail + owner + ISO expiry date; expiry is a warning within 14 days on PRs and a failure only in a scheduled `--nocache_test_results` run (Bazel caches test results, so a date-dependent PR failure would appear on an unrelated PR); `*_WITH_ISSUE` reasons require an issue reference in the detail. Exempt ids are mirrored in `docs/registry/feature-exemptions-baseline.txt`, checked equal to the registry by test and ratcheted against merge-base in CI. Migrated `@HelpExempt` reasons keep their text.

**Staleness gate.** Stateless git-diff: warn when a file matching a row's `sources` changed and none of that row's `demoPage`, `sitePage` or registry file changed. Ignore `*Test*.kt` and whitespace-only diffs (`git diff -w`). Skip `release-please[bot]`, `dependabot[bot]` and PRs touching no mapped source. Escape hatches: commit trailer `Docs-Exempt: <non-empty reason>`, PR label `docs-exempt`, registry `exempt`; every use is echoed to the job summary. Content-hash staleness and commitlint are rejected (below).

**Baseline reclassification.** Applying the owner's audience decision (D1) can add `:site` gaps. This is the one allowed baseline growth, permitted only on a PR carrying the owner-applied label `baseline-reclassification` (ratchet honours it).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Enforce everything immediately | Pre-mortem 1 and 2: legacy red, blanket exemptions (`../research/pitfalls.md` section 3) |
| Warn-only forever | The existing `DemoGraphCoverageTest` coverage-summary test never fails and was already stale (`DemoGraphCoverageTest.kt:61-91`) |
| Content-hash of source files stored in the manifest | Flips on every refactor, needs bump commits, makes the manifest a conflict hotspot (`../research/architecture.md` section 4) |
| Hard dependency on commit linting (`feat:` gate via commitlint) | No commit lint exists (`release-please-config.json` has no rules); changes contributor workflow. Use the PR title prefix as an escalation signal only |
| Free-text exemption reasons | Rubber-stamp path (`../research/pitfalls.md` section 1) |
| External link checking in the PR gate | Flaky third-party availability; scheduled non-blocking workflow instead (plan Story A4.2) |

## Consequences

Positive: legacy gaps are visible without blocking; new gaps fail with a message that names the file and exemption syntax; exemptions are bounded, expire, and cannot silently accumulate; promotions rest on counted evidence.

Negative: four rule classes and two baseline files to explain (mitigated by the CLAUDE.md section, Story A5.1); promotion waits on PR traffic and owner decisions (D1 to D3), so calendar duration is set by those, not by agent work; the label-gated baseline reclassification is one more owner touchpoint. `Docs-Exempt:` trailers still permit gaming; the exemption log in the job summary makes it countable, not impossible.

Open decisions for the owner (not resolved here): D1 audience classification, D2 and D3 promotions, D4 Help menu.
