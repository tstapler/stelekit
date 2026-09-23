# ADR-001: Platform-Core + Tiered Per-Package Bazel Target Architecture

**Status**: Accepted
**Date**: 2026-09-04
**Deciders**: Tyler Stapler (via SDD Phase 3 planning)
**Supersedes/refines**: requirements.md's provisional 11 hub / 9 zero-risk / 18 hub-touching split

## Context

`research/features.md` ran an independent Tarjan strongly-connected-components analysis over a
verified 129-edge package import graph (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/`, 38
top-level packages, 609 files) and cross-checked it against
`mcp__kibitzer__architecture_assessment` at finer (sub-package) granularity. Both methods agree
on the same set of packages forming one strongly-connected component (SCC).

**Correction to features.md's own headline number**: features.md's prose states "17 of the 38
packages" but its own sub-classification in the same document ("7 of the 11 hub packages... and
11 of the 18 hub-touching packages" are cycle members) sums to 7 + 11 = **18**, and the
enumerated package list it prints (`asset, calibration, db, domain, editor, export, git, llm,
migration, performance, platform, repository, search, sections, tags, transfer, ui, voice`) also
contains 18 tokens, not 17. Independently, summing that document's own per-package `.kt` file
counts for exactly those 18 packages (asset 15 + calibration 2 + db 39 + domain 13 + editor 30 +
export 10 + git 37 + llm 19 + migration 17 + performance 32 + platform 50 + repository 35 +
search 3 + sections 9 + tags 9 + transfer 21 + ui 178 + voice 16) totals **535**, which exactly
matches features.md's own "535 of 609 files (87.8%)" figure. This is conclusive: **the cycle is
18 packages, not 17** — features.md's headline count is a simple off-by-one in its own summary
sentence, not a disagreement in the underlying data. This plan uses **18** throughout and treats
the correction as verified (re-derivable from features.md's own tables), not a new open question.

Bazel forbids target-level cycles (a hard, structural constraint — not a style preference), so
these 18 packages cannot become 18 independent `kt_jvm_library`/`kt_android_library` targets as
directory-structured today.

## Decision

Adopt a **three-tier target architecture**:

- **Tier 1** (9 packages: `benchmark`, `docs`, `error`, `logging`, `parsing`, `resilience`,
  `rtc`, `service`, `stats`, 24 files) — one independent Bazel target per package. Zero cycle
  involvement, zero hub imports.
- **Tier 2** (11 packages: `model`, `cache`, `coroutines`, `util`, `command`, `clipboard`,
  `flashcard`, `loader`, `outliner`, `parser`, `vault`, 50 files) — one independent Bazel target
  per package, modulo 2 confirmed cross-tier `internal`-visibility fixes (`cache`→`performance`,
  `util`→`model`/`util`→`ui`, both consumers living in Tier 3 — see plan.md Epic 4.2).
- **Tier 3** (18 packages, 535 files, 87.8% of `commonMain`) — **one merged Bazel target**
  (`//kmp/src/commonMain/kotlin:commonmain_cycle_cluster` or platform-equivalent), absorbing the
  cycle internally rather than resolving it. This is Rejected Alternative (b) below, deferred.

`:platform-core` (the ~109 expect/actual-bearing files, per requirements.md/architecture.md)
remains textually included via `common_srcs` in both `android_main`/`jvm_main_lib`-equivalent
compilation units regardless of tier, per ADR-005's existing constraint (unchanged by this ADR).

### Alternatives considered (Step 0.5 creative pass)

| Alternative | Strength | Weakness | Verdict |
|---|---|---|---|
| (A) Single flat target per tier (3 targets total, not per-package) | Minimal BUILD churn; zero `associates`-at-scale risk | Defeats the entire premise — a change to any file in a 9-package "tier-1 bundle" still invalidates the whole bundle; fails the Success Metrics' per-package invalidation-scope requirement | Rejected |
| (B) Recursive sub-package splitting (target down to `ui.gallery`, `editor.blocks` granularity, attempting even inside Tier 3 via kibitzer's finer-grained nodes) | Maximum invalidation-scope win; matches kibitzer's own file-level graph resolution | Multiplies target count from ~29 to 100+, directly reproducing the rules_kotlin #1653 O(N²) analysis-phase risk (pitfalls.md) and the unverified `associates`-at-scale risk at a much larger scale than even the 18-hub-touching-package original ask; blows the Large (3-6 week) appetite | Rejected |
| (C) Platform-core + per-top-level-package (Tier 1, Tier 2) + Tier-3 merged cluster | Matches the verified dependency graph exactly (Tarjan SCC done at top-level-package granularity — the only granularity Bazel targets are being drawn at); keeps target count tractable (~20 new targets, not 100+); staged, per-package reversibility | Tier 3 (87.8% of files) gets **zero** invalidation-scope improvement in this plan — an acknowledged, not hidden, limitation | **Chosen** |

### Tier-3 sub-decision: (a) merge vs. (b) dependency-inversion refactor

Requirements.md's own Feasibility Risk #2 and Rabbit Holes section anticipated needing exactly
this choice. Two options:

- **(a) Ship the 18-package cluster as one merged Bazel target for now.** Preserves today's
  actual architecture (the cycle already exists in the merged monolith; this ADR does not
  introduce it, just draws a smaller boundary around it). Zero behavior/semantic risk — this is
  a pure BUILD-file change. Still a real win: Tier 1 + Tier 2 (20 packages, 74 files, plus
  whatever `:platform-core` narrowing achieves) come out of the monolith.
- **(b) Dependency-inversion refactor first** — break specific edges (`platform.ml` no longer
  importing `ui.annotate`; relocate `repository`-shaped interfaces `db` depends on to a neutral
  package so `db`↛`repository`; etc.) before drawing target boundaries. This is a genuine
  architecture change to production code semantics, not a build-tooling change — it touches
  types engineers actively depend on, has its own correctness risk (behavior-preserving refactor
  of 87.8% of the codebase's import graph), and does not fit inside this project's Large
  (3-6 week) appetite alongside everything else already scoped (tooling, rules_kotlin upgrade,
  worker caps, tiers 1-2 rollout).

**Decision: (a).** Ship Tier 3 as one merged target. Recommend (b) be scoped as its own,
separately-chartered follow-on project (its own requirements.md/ADR/appetite), not folded into
this migration's Phase 5 implementation task list. Rationale: (a) delivers immediate, low-risk
value now; (b) is high-value but high-risk architecture work that deserves its own risk
tolerance decision, not a rider on a build-tooling migration. This mirrors the user's explicit
instruction that a dependency-inversion refactor "must be treated as a distinct, separately-scoped
follow-on effort... NOT silently folded into this migration's task list."

## Consequences

- Tier 3's single merged target means a change to any file among the 535 (e.g. `ui/App.kt`)
  still invalidates and recompiles the full 535-file unit — no improvement over today's ~800(609)
  -file monolith for that slice. The 71s number will shrink roughly proportionally to the file
  count removed (Tier 1 + Tier 2 = 74 of 609 files, ~12%), not to zero.
- Tier 3's merged target still needs its own `BUILD.bazel` wiring (deps on `:platform_core`,
  Tier 1, and Tier 2 targets it consumes) — this is in scope for a future implementation phase,
  not this plan's Phase 5 (which only produces requirements/research/plan/validation artifacts
  per requirements.md's stated non-goal for phases 1-4; the ADR records the decision now so
  Phase 5 implementation of Tiers 1-2 isn't blocked waiting on a Tier 3 answer).
- A future ADR (out of scope here) will record the (b) dependency-inversion project's own
  scope/appetite/architecture if and when it's chartered.
- **Tracked follow-on** (architecture review concern #6): so this debt doesn't silently disappear
  once this migration ships, plan.md's Epic 5.1 (Task 5.1.1c) tasks opening a GitHub tracking
  issue for the (b) refactor, referencing this ADR, even with placeholder scope.
- **Tier-3 merged target must self-document its own tier rationale.** Whoever eventually
  implements `commonmain_cycle_cluster` (out of this plan's own implementation scope — see Epic
  5.1 — but its `BUILD.bazel` shape should be anticipated now) must add a comment explaining *why*
  18 packages (87.8% of `commonMain`) are merged into one target: a genuine Tarjan strongly
  connected component per this ADR and `research/features.md`, not developer laziness or an
  unexamined default. This will be the single largest, most visually surprising target in the
  tree, and a future engineer encountering it for the first time needs the "why" at the point of
  encounter, e.g.: `# Tier 3: one Tarjan SCC absorbing an unbroken import cycle across 18 packages
  (ADR-001) — not splittable without the dependency-inversion refactor ADR-001 defers to a
  separate project; see tier_manifest.bzl:TIER_3_PACKAGES.` This mirrors the same per-package
  rationale-comment convention Tier 1/2 targets carry (plan.md's Common Per-Package Extraction
  Recipe, step 4).

## Reconsideration triggers

- If `research/build-vs-buy.md`'s earlier kibitzer run (which reported **0** import-cycle
  findings on an unscoped, full-`commonMain` run — directly contradicting features.md's 2
  findings including the 18-package cycle) is ever explained as a genuine tool behavior
  difference (not a stale/misconfigured run), re-verify the cycle with a fresh, from-scratch
  Tarjan pass before trusting this ADR's Tier 3 membership list. Until then, the manual
  Tarjan-over-verified-edge-list result stands on its own (it does not depend on kibitzer), so
  this ADR's decision is not blocked by that discrepancy — but Phase 5 should re-run the edge
  extraction once more immediately before implementation, since both research passes are now
  several sessions old relative to any commits that may land on `main` in the interim.
