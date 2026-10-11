# Adversarial Review: commonmain-bazel-target-split

**Date**: 2026-09-04
**Verdict**: CLEAN
**Note**: This is iteration 2 — a scoped re-review verifying fixes to iteration 1's findings.

**Status update (2026-09-04, post Phase-4 triad repair loop)**: the Minors below (task-ID
citation drift `4.1.1a-pre`/`4.2.1a-pre`, and the single-snapshot vs. sampling-loop memory check)
were subsequently fixed in `plan.md` during the Phase 4 triad repair loop's third round — grep
`plan.md` for "-pre" (zero matches) and see Task 1.3.1b's continuous-sampling-loop wording to
verify directly. This file is kept as the original point-in-time record rather than rewritten.

## Blockers

(none — the original blocker is resolved, see Resolved section)

## Concerns

(none carried forward — all 6 iteration-1 concerns are genuinely resolved, see Resolved section)

## Resolved (verified)

- **BLOCKER — "no task ever creates `:platform_core`"**: genuinely resolved. `plan.md` Phase 1
  now has **Epic 1.6: Define and wire `:platform_core`** with three stories, each carrying
  concrete Given-When-Then acceptance criteria, not a one-line mention:
  - Story 1.6.1 runs the deferred expect/actual inventory grep
    (`grep -rlE '\b(expect|actual)\b' ... {model,platform,cache,coroutines,util,performance,db,llm,sections,transfer,ui}`)
    across all 11 candidate packages — covering `model`/`coroutines`/`cache`/`util` in full plus
    the `db`/`llm`/`sections`/`transfer`/`ui` subsets the original blocker specifically called out
    as missing.
  - Story 1.6.2 defines `:platform_core`'s concrete Bazel shape: a new `filegroup(name =
    "platform_core_srcs", ...)` in `kmp/src/commonMain/kotlin/BUILD.bazel`, wired additively into
    both `android_main`'s and `jvm_main_lib`'s `common_srcs`, with a build-verification
    Given-When-Then (`bazel build //kmp:desktop_app //kmp:android_app --config=android` succeeds,
    no expect/actual-same-module conflict).
  - Story 1.6.3 explicitly closes blocker part (c): it adds the same "resolve platform-core
    relationship" task that `model` (Task 4.1.2a) already had to **both** `cache` (new Task
    4.1.1a in Story 4.1.1) and `util` (new Task 4.2.1a in Story 4.2.1) — verified by reading
    those stories directly in Phase 4 (plan.md:1080-1091 and plan.md:1206-1213). `coroutines`
    (Story 4.1.3, in the compact table at plan.md:1149) is also flagged with "Also a
    `:platform_core` candidate ... same Task-(a) check as `model`" — so all four Tier-2
    `:platform-core`-file-source packages (`model`, `cache`, `coroutines`, `util`) now carry the
    check uniformly, closing the internal inconsistency the original blocker identified.
  - Verified `kt_srcs`/`kt_expect_srcs`/`common_srcs` are real, existing Bazel constructs in this
    repo (`kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`,
    `kmp/src/jvmMain/kotlin/BUILD.bazel` — confirmed via grep), so the new filegroup's design is
    grounded in the actual repo structure, not invented terminology.
- **Concern — stale `search` example in requirements.md**: resolved. Success Metrics now reads
  "**Post-research correction (see ADR-001)**: `search` was reclassified during Phase 2/3
  research as a member of the 18-package Tier-3 import cycle ... the pilot's actual worked,
  validated example is `stats`" — an explicit correction note, not left implicit.
- **Concern — stale Appetite section**: resolved. requirements.md's Appetite section now has an
  "**Actual delivered scope (post-research correction, see ADR-001)**" paragraph stating only 7
  of 18 hub-touching packages get independent targets, enumerating Tier 2's 11 packages, and
  stating the dependency-inversion refactor for Tier 3 is "**explicitly out of this appetite**,
  deferred to a separately-chartered future project."
- **Concern — missing Compose/serialization plugin-check step**: resolved. Epic 2.2's "Common
  recipe per package" now has an explicit step 3: "Check whether `<pkg>` uses
  `@Composable`/`@Serializable`: `grep -rl '@Composable\|@Serializable' ...` — set the macro's
  ... params accordingly (do not assume plugin-free ...)." Task 2.2.1a explicitly bundles this
  grep. Epic 4.1 states it reuses "Same recipe as Epic 2.2," so Tier 2 inherits the same step.
- **Concern — `androidApp` BUILD.bazel never inspected**: resolved. New Epic 1.7, Story 1.7.1
  ("Inspect `androidApp`'s BUILD.bazel for monolith-shape assumptions") has concrete
  Given-When-Then acceptance criteria for both possible outcomes (no assumption found / an
  assumption found becomes a new Feasibility Risk), and is wired into the Dependency
  Visualization as a **hard gate**: "Epic 1.7 ... HARD GATE: Epic 2.2 does not start until Story
  1.7.2 records PASS."
- **Concern — invalidation-scope win vs. real PR-touch patterns**: resolved. Story 3.1.1 gained
  an explicit "**Framing caveat (adversarial + architecture review)**" paragraph stating the
  PASS/FAIL criteria measure mechanism-correctness only, not the fraction of real historical PRs
  that would hit Tier 1/2 vs. Tier 3, plus an optional Task 3.1.1e to sample real PR-touch
  patterns via `git log --stat` against `tier_manifest.bzl`'s classification.
- **Bonus architecture concerns — spot-checked all 6, all genuinely resolved**:
  - Macro `uses_compose`/`uses_serialization` booleans → `extra_plugins` is now the real
    extensibility point (Story 1.1.1/Task 1.1.1b), with the two booleans kept only as sugar that
    append into `extra_plugins` — a documented, reasoned open/closed fix, not just renamed.
  - `tier_manifest.bzl` (new Story 1.1.2) defines `TIER_1_PACKAGES`/`TIER_2_PACKAGES`/
    `TIER_3_PACKAGES` as the single source of truth, cross-checked against ADR-001's lists.
  - CI duplicate-`.kt`-ownership check (new Epic 1.8) adds
    `scripts/check_no_duplicate_kt_ownership.py` with a fixture-based validation task and a CI
    wiring task.
  - Bidirectional internal-visibility audit (Epic 2.2 recipe step 2) now runs
    `check_internal_visibility.py` with `<pkg>` as **both** declaring and consuming package,
    explicitly distinguished from Story 2.3.1's declaring-only pass.
  - rules_kotlin ABI-stability spike (new Epic 1.7, Story 1.7.2) is framed as a **hard
    prerequisite gate before Epic 2.2**, with concrete pass/fail criteria tied to
    `bazel analyze-profile` re-analysis vs. re-execution.
  - Tier 3 tracking-issue task (Epic 5.1, Task 5.1.1c) tasks opening a GitHub issue referencing
    ADR-001's dependency-inversion alternative, cross-referenced from ADR-001's Consequences
    section ("Tracked follow-on").
- **Sanity check — epic numbering / Dependency Visualization**: no duplicate epic numbers (1.1
  through 1.8, 2.1-2.3, 3.1, 4.1-4.2, 5.1, all unique) and no dangling references — every epic
  named in the Dependency Visualization ASCII graph (including the newly inserted 1.6/1.7/1.8)
  has a corresponding section later in the document.

## Minors

- **Task-ID citation drift**: Story 1.6.3's acceptance criteria refer to the new cache/util tasks
  as "Task 4.1.1a-pre" and "Task 4.2.1a-pre" (plan.md ~line 1084, ~line 1210), but the actual
  tasks as written in Phase 4 are named "Task 4.1.1a" and "Task 4.2.1a" (no `-pre` suffix). Purely
  a cross-reference label mismatch — the tasks themselves exist and are correct — but worth a
  find/replace before this plan is executed, to avoid a subagent searching for a task ID that
  doesn't exist.
- **requirements.md's Open Questions section is now partially stale post-fix**: two entries —
  "Which specific files within `db`, `llm`, `sections`, `transfer`, `ui` carry the actual
  `expect`/`actual` declarations... (Phase 2 research.)" and "Exact pilot scope beyond the 9
  zero-risk packages... (Phase 3 planning decision.)" — are now answered by plan.md (Epic 1.6
  Story 1.6.1 for the first; ADR-001 + this plan's own tier split for the second), but neither
  question was marked resolved or cross-referenced to where the answer now lives. Not a
  functional gap (plan.md is the authoritative execution document), but a reader skimming only
  requirements.md would still see these as open.
- **Carried forward from iteration 1, unchanged, not part of this iteration's required fix
  scope**: Task 1.3.1b's `.bazelrc` worker-cap verification still proposes `free -h` samples
  before/after the build step rather than a mid-build sampling loop, which the original prior-OOM
  incident (a transient spike) could miss; and Task 1.2.1b (rules_kotlin version bump) still
  doesn't explicitly re-verify the `suppressKotlinVersionCompatibilityCheck` Compose-plugin
  workaround still applies on the newly pinned version. Both are belt-and-suspenders gaps, not
  missing safety nets (the plan has other backstops for each), and were flagged as Minors, not
  Concerns, in iteration 1 — carrying them forward for visibility, not as new findings.
