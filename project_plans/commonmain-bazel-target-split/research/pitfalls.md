# Pitfalls Research: commonmain-bazel-target-split

**Date**: 2026-09-04

## Prior incident analysis: persistent-worker OOM (commits f8e7aa82e1, 6842053f8d)

**Exact sequence** (from `git show f8e7aa82e1`, `git show 6842053f8d`, and the current `.bazelrc:31-72`):

1. `f8e7aa82e1` ("ci(bazel): enable persistent Android dex/desugar/resource workers") added
   `build:android --persistent_android_dex_desugar` and
   `build:android --persistent_android_resource_processor` after profiling showed CI run
   `33891824754`'s Android build spending 1510s wall-clock vs. a 236s critical path (6.4x
   overhead), with 825/916 sampled actions running on `processwrapper-sandbox` (a cold `java`
   process per action) vs. only 91 on worker/multiplex-worker. **This was verified locally**
   ("adding them to `bazel build //kmp:android_app --config=android` shifted 466/809 actions
   from processwrapper-sandbox to worker with no failures") on Tyler's 61GB/24-core dev machine.
2. `6842053f8d` ("fix(bazel): cap Desugar/DexBuilder worker concurrency to stop OOM-kills") was
   filed by **the very next CI run** (`33929109869` — "this branch's own first CI run"): the
   `desugar_java8 --persistent_worker` process was OOM-killed mid-build ("Killed"), which
   cascaded into unrelated `KotlinCompile`/Dexing actions failing with "Worker process did not
   return a WorkResponse". The fix added
   `--worker_max_instances=Desugar=2` / `--worker_max_multiplex_instances=Desugar=2` and the
   same pair for `DexBuilder`.
3. **A second follow-up was needed** (visible directly in the current `.bazelrc:58-66` comment,
   which documents a fix beyond the two flagged commits): CI run `33931054162`, with the
   Desugar/DexBuilder cap already applied, *still* failed near the same point — this time taking
   down the whole runner ("Bazel caught terminate signal" / "The runner has received a shutdown
   signal") — because `--persistent_android_resource_processor` also makes
   `PackageAndroidResources` a persistent worker, and that mnemonic had never been capped. All
   three mnemonics are now capped at `worker_max_instances=2` / `worker_max_multiplex_instances=2`.

**Root cause, stated precisely**: persistent workers are long-lived JVM processes that hold their
heap **resident across actions** instead of releasing it on process exit like the one-shot
sandboxed processes they replace. Their Bazel-declared `resource_set` (used by
`--local_resources=memory=9000` for scheduling) **understated** their real steady-state memory
footprint, so Bazel's resource-based scheduler kept spinning up more concurrent worker instances
than the 16GB CI runner (GitHub-hosted) could hold, even though the existing memory budget
"looked" sufficient on paper. The 61GB/24-core dev machine never surfaced this because it has
~4x the memory headroom of the CI runner — the failure mode is a **fixed number of concurrent
worker JVMs times a per-worker heap that the resource model undercounts**, and it only exceeds
available memory once you cross that CI-specific ceiling. It was invisible locally not because
the mechanism was different, but because the memory margin was different.

**Why local "looked safe" and CI didn't**: three multiplicative factors, all of which apply
locally too but only cross the failure threshold on CI's tighter budget:
- Persistent workers accumulate heap that a resource-based scheduler doesn't see (understated
  `resource_set`).
- The scheduler had **no cap on worker count** for the newly-enabled persistent mnemonics — Bazel
  will keep starting new worker instances up to `--worker_max_instances` (default 4 per mnemonic,
  confirmed via Bazel docs — see Sources) as long as its resource model says there's room.
  Locally there is 61GB of "room" for the model to be wrong about; on the 16GB runner there is
  much less slack before the miscalculation becomes a real OOM.
- **Three separate mnemonics** (`Desugar`, `DexBuilder`, `PackageAndroidResources`) all became
  persistent-worker-eligible from the same two-line flag change, so the true peak concurrent
  worker-JVM count that needed capping wasn't obvious from the diff — it took two separate CI
  failures (one per mnemonic newly discovered to be the culprit) to fully bound it.

**Transferable lesson for this migration (stated explicitly, not gestured at)**: this incident is
about *concurrent persistent-worker instance count* exceeding a memory budget that undercounts
real per-worker footprint on a memory-constrained runner — it is **not** about worker count
scaling with *target* count directly, but the two are closely coupled for this migration because:

- **`KotlinCompile` currently has zero worker cap in this repo.** `grep -n
  "worker_max_instances\|worker_max_multiplex_instances" .bazelrc` shows caps only for
  `Desugar`, `DexBuilder`, `PackageAndroidResources` — no line mentions `KotlinCompile`. Bazel's
  documented default is 4 worker instances per **mnemonic+flag-set (`WorkerKey`)** combination
  (see Sources), and the flag set is a function of `kotlinc_opts`/`define_kt_toolchain`/plugins
  applied — so **every distinct combination of applied compiler plugins (Compose vs. no-Compose,
  serialization vs. not) is its own `WorkerKey` with its own independent pool of up to 4
  workers.** Today there are effectively 2 monolithic `KotlinCompile` actions
  (`android_main`, `jvm_main_lib`) sharing (at most) a couple of `WorkerKey`s. After the split,
  ~27+ independent per-package targets — several with different plugin combinations (Compose-only
  packages, serialization-only packages, plain packages) per the requirements.md scope — multiply
  the number of distinct `WorkerKey`s, and each one can independently spin up toward its own cap
  of 4 resident-heap workers. **The same class of bug (resource-model-undercounted persistent
  workers, uncapped, on a memory-constrained runner) is directly reproducible here if the pilot
  doesn't add an explicit `--worker_max_instances=KotlinCompile=<N>` cap (and ideally distinguish
  it per `WorkerKey`/plugin-set if rules_kotlin's worker mode is even in use) before or alongside
  the target split** — see the design-against checklist below.
- The precedent also establishes the **required verification bar for this migration**: "passes
  locally" is explicitly insufficient per this repo's own recent history (constraint already
  captured in requirements.md, confirmed here with exact evidence) — each rollout slice must be
  watched for CI runner memory headroom, not just green/red, exactly as it took two rounds of
  real CI failures (not local testing) to fully characterize and fix the analogous Android-worker
  incident.

## rules_kotlin worker memory/concurrency risk for many-small-targets

- Bazel's persistent-worker model creates one `WorkerKey` per (mnemonic, flags, tool
  environment) combination, and allows up to `--worker_max_instances` (**default: 4**) resident
  worker processes per `WorkerKey`. Each worker process holds its own JVM heap between actions
  rather than releasing it — this is true of `KotlinCompile` workers exactly as it was of the
  `Desugar`/`DexBuilder`/`PackageAndroidResources` workers in the prior incident, and is not
  Android-specific.
- Bazel's own guidance (surfaced during research; see Sources — Bazel persistent-workers docs)
  states the tradeoff directly: with a *small number of large targets*, a single worker gives the
  best tradeoff between JIT-warmup benefit and resource usage; for *incremental builds specifically*
  the benefit of multiple worker instances is smaller, while more, smaller targets require **more
  worker instances** and pay more repeated JVM-startup/cold-cache cost per instance — i.e. Bazel's
  own docs describe exactly the many-small-vs-few-large memory tradeoff this migration is
  choosing to make, framed as a tradeoff to configure deliberately (worker count caps, `-Xmx` per
  worker), not one that resolves itself.
- Directly relevant, though not identical: `bazel-contrib/rules_kotlin` issue #1043 ("Bazel OOMs
  during analysis phase when KSP provides Java outputs information") documents an **analysis-phase**
  (not execution-phase) OOM in rules_kotlin triggered by a large build graph with many paths
  between root and leaves causing Bazel to merge/copy hundreds of megabytes of transitive output
  info — a different mechanism than the worker-heap incident, but the same class of "graph shape
  change surfaces a latent OOM that a smaller/flatter graph never triggered." Splitting one
  `commonMain` compile into 27+ targets each depending on `:platform-core` (and, per
  requirements.md's own flagged rabbit hole, potentially on each other) increases the number of
  transitive-dependency paths through the graph — this is a second, independent OOM vector from
  the worker-heap one, worth a dedicated analysis-phase memory check during the pilot (`--profile`
  + heap sampling on the loading/analysis phase, not just execution).
- No public rules_kotlin issue was found stating "N small kt_jvm_library targets is worse than 1
  large one" as a blanket claim — the risk is conditional on worker/heap configuration (caps,
  `-Xmx`), not an inherent property of target count. That said, the repo has **zero existing
  KotlinCompile-specific configuration** to inherit any tuning from — the pilot is the first time
  this repo will need to reason about it at all.

## Documented industry pitfalls for Bazel Kotlin/JVM target splitting

- **BUILD file churn without codegen tooling**: this is a widely-documented failure mode, and
  every serious Bazel-at-scale writeup treats hand-maintained `srcs`/`deps` as untenable past a
  handful of targets. `bazel-contrib/bazel-gazelle` is the canonical generator-based answer for
  Go/proto; Aspect Build's Kotlin/Java gazelle extensions and `cirruslabs/bazel-project-generator`
  exist specifically because Kotlin/Java lack Gazelle's native support. Uber runs Gazelle across
  a ~1M-file monorepo with >10k commits/week and **enforces "no drift" in CI** by running Gazelle
  against a clean checkout and failing if BUILD files change. This directly validates
  requirements.md's insistence on a Starlark macro (`stelekit_kt_common_library`) plus the
  internal-usage-audit and cycle-detection scripts as first-class deliverables rather than
  hand-written BUILD files per package — the alternative (27+ hand-maintained BUILD files) is the
  exact anti-pattern the industry has repeatedly hit and built tooling to avoid. Note: this repo's
  scope is a Starlark *macro* wrapping the rule (standardizing `kotlinc_opts`/`associates`/
  visibility), not full Gazelle-style dependency-graph generation from source — `deps` still has
  to be hand-authored or separately tooled; that gap is not yet covered by the requirements.md
  scope and worth flagging to Phase 3.
- **Android resource/manifest merging in a monolithic-to-many-targets split**: documented Bazel
  issues (`bazelbuild/bazel#6904`, `#6645`, `#2919`, `#565`, `bazelbuild/rules_android#10`) show
  resource merging in Bazel's Android rules is mandatory (cannot be disabled, unlike Gradle where
  disabling it bought ~30% build-time reduction for some teams), manifest-merger errors are
  sometimes swallowed/dropped rather than surfaced clearly, and same-named resources across
  library targets fail to merge the way Gradle's Android plugin does. **This risk is likely
  low for this specific migration** because the split targets are `commonMain` Kotlin
  compilation units, not `android_library` targets carrying their own `res/`/`AndroidManifest.xml`
  — resources stay centralized under `androidMain`. Worth an explicit one-line confirmation in
  Phase 3 planning that none of the 38 `commonMain` packages carry Android resources, to close
  this off rather than leave it assumed.
- **CI cache cold-start cost on first migration build**: general Bazel caching literature
  (BuildBuddy, Depot.dev writeups — see Sources) confirms that a cold cache means every action is
  a miss and the analysis phase re-derives the full dependency graph from scratch, with cited
  analysis-phase overhead of 15-90s for large monorepos on a cold run. Splitting one target into
  27+ creates 27+ new cache keys that have never been populated in the shared disk-cache/
  repository-cache/remote-cache-proxy — **the first CI run after each rollout slice merges should
  be expected to be net slower or at best neutral**, with the expected win only visible on
  *subsequent* incremental builds once those new keys are warm. Requirements.md's success metrics
  already frame this correctly (re-profile after the pilot, treat as a hypothesis to confirm) but
  Phase 3 should explicitly budget for a "first build after merge is not representative" caveat
  when communicating results, so a cold first data point doesn't get mistaken for the split
  failing.
- **Analysis-phase overhead from a larger BUILD graph**: Bazel's own docs (see Sources) note
  package loading can be slowed by "an excessive amount of targets, complex macros, or recursive
  globs" and that analysis-phase share of total build time is much higher for full/clean builds
  (22-49% of total time) than incremental ones (1-3%). Going from ~2 top-level Kotlin targets to
  27+ increases the number of targets Bazel must load/analyze on every invocation (even ones that
  don't touch `commonMain`), which is a real but likely small tax relative to the compile-time win
  being pursued — worth a "clean build wall-clock" data point alongside the incremental-build
  measurement plan already in requirements.md, since requirements.md's Non-functional Requirements
  section already (correctly) says "full-graph clean-build time is not expected to improve" — this
  finding suggests it's worth confirming it doesn't measurably *regress*, either.
- **Compose/serialization compiler plugin scoping**: this repo's own root `BUILD.bazel` documents
  a live example of plugin-compatibility friction (`suppressKotlinVersionCompatibilityCheck` for
  the Compose plugin, referencing `rules_kotlin` issue #1388 and ADR-004) — i.e. the repo has
  *already* hit one plugin-scoping gotcha with rules_kotlin at the current 2-target scale. No
  additional public rules_kotlin issue specifically about per-target plugin misapplication at
  many-small-targets scale was found in this search pass, but requirements.md's own Rabbit Holes
  section already flags "determining exactly which of the 18 hub-touching packages need which
  compiler plugin... could balloon if not scoped early" as a live risk — this research did not
  surface a reason to downgrade that concern.

## Kotlin `internal`/`associates` specific pitfalls

- **`associates=` is the modern, correct mechanism** (rules_kotlin >= 1.5.0 per search results) —
  it supersedes an older, narrower `friends=` attribute on `kt_jvm_test()` only, which
  historically permitted (despite being named plural) only a **single** friend target
  (`bazelbuild/rules_kotlin` issue #211, "Broaden support for 'friends'"). `associates=` is the
  attribute this repo already uses (`common_test_fixtures`, and the four test BUILD files found —
  `commonTest`, `jvmTest`, `androidUnitTest`, `businessTest` — each pointing `associates` at
  exactly one monolithic target: `jvm_main_lib` or `android_main`).
- **Two-simultaneous-friend-targets question (a likely real need here)**: issue #211's discussion
  explicitly frames the generalization as "any `kt_jvm_*` target should be able to declare
  multiple friends," and the historical constraint was that friending was scoped to "the same
  logical kotlin module" (roots that don't themselves declare friendship) — i.e. there's a
  documented design tension around *transitive*/*multi-target* friending, not just a single-slot
  limitation. This research did not find a definitive, current statement that `associates` (the
  post-1.5.0 attribute this repo actually uses) is still capped at one target — the underlying
  rule implementation (`kotlin/internal/jvm/jvm.bzl`) accepts `associates` as a list attribute in
  the versions surfaced during search, suggesting multi-target friending is likely supported today,
  but this is **inferred from rule surface area, not verified against this rules_kotlin version's
  actual behavior**. **This needs a direct, hands-on verification in Phase 3/pilot** — e.g. a
  small test target with `associates = ["//...:stats", "//...:search"]` compiled against real
  cross-package `internal` symbols in both, in this repo's pinned rules_kotlin version — rather
  than being assumed from the attribute's plural name or from general web search results, given
  requirements.md's own Open Question 4 anticipates exactly this scenario (a test touching both
  `stats` and `search`).
- **IDE/tooling gap**: search results flagged that some IDE integrations do not correctly resolve
  `internal`-visibility access granted via `associates` (i.e. IntelliJ/Fleet may show a false
  compile error even though Bazel's actual `kotlinc` invocation succeeds with `-Xfriend-paths`).
  This is a developer-experience risk, not a build-correctness one, but worth flagging in Phase 3
  so engineers aren't confused by red squiggles that don't reflect real compile failures once
  `associates`-heavy targets exist.
- **Transitivity is not automatic**: `-Xfriend-paths` (which `associates` compiles down to) grants
  access to `internal` members of the *directly* associated target(s) only — it is not documented
  anywhere found in this search as being transitive through a chain of associates. If package `A`
  needs `internal` access to something in `:platform-core` **and** something `internal` in
  package `B`, `A`'s target needs `associates` listing both explicitly; it cannot get `B`'s
  internals for free by `B` itself being associated with `:platform-core`. This reinforces why the
  internal-usage-audit script (already scoped in requirements.md) must record the **exact set of
  cross-package internal dependencies per package**, not just "does this package use any
  cross-package internal" — the audit's output shape needs to be a per-pair adjacency list to
  drive correct `associates` lists, not a boolean per package.

## Recommended explicit design-against list

Phase 3 planning should design against each of these explicitly (favor a checklist item per
row, not prose):

1. **Add an explicit `--worker_max_instances=KotlinCompile=<N>` (and multiplex variant) cap in
   `.bazelrc` before or in the same slice as the pilot**, sized conservatively (start at 2,
   matching the precedent's final Desugar/DexBuilder/PackageAndroidResources value) and re-tuned
   with real CI memory-headroom data, not assumed safe by analogy to the local dev machine.
2. **Treat each distinct compiler-plugin combination (Compose / serialization / plain) as its own
   `WorkerKey`** when reasoning about worst-case concurrent KotlinCompile worker count — don't
   assume "N targets" caps at "N/4 rounds of workers"; it's N-per-distinct-flag-set, up to the cap,
   *simultaneously* across flag-sets.
3. **Watch CI runner memory headroom explicitly during every rollout slice**, not just red/green
   — per requirements.md's own Observability Requirements, and per this incident's lesson that
   the failure took **two separate CI runs** to fully characterize (each fix revealing the next
   uncapped mnemonic).
4. **Do not extrapolate "safe" from a local dev-machine run** — the 61GB/24-core machine masked
   this exact class of bug once already in this repo; the pilot's actual go/no-go evidence must
   come from CI runs on the 16GB runner.
5. **Verify `associates` with two simultaneous friend targets hands-on during the pilot** (a real
   compiled example, not inferred from rule surface area or GitHub issue discussion) before
   assuming the `stats`+`search` dual-friend test scenario (Open Question 4) works.
6. **Build the internal-usage-audit script's output as a per-package-pair adjacency list**, not a
   per-package boolean — `associates` grants access to directly-listed targets only, not
   transitively.
7. **Confirm no `commonMain` package carries Android resources/manifest fragments** before
   assuming the resource/manifest-merging pitfall class doesn't apply here.
8. **Budget explicitly for a cold-cache first build after each rollout slice merges** — expect
   flat-or-worse wall-clock on that first CI run post-merge; only trust the incremental-build
   measurement on a second, warm-cache run.
9. **Add a clean/full-build wall-clock check alongside the incremental-build measurement plan**
   already in requirements.md, to catch analysis-phase regression from the larger BUILD graph,
   even though it's explicitly a non-goal to *improve* clean-build time.
10. **Watch analysis-phase memory separately from execution-phase (worker) memory** — rules_kotlin
    issue #1043 is a documented, distinct OOM vector (transitive-output-info merging) from the
    persistent-worker-heap vector this repo already hit once; increasing dependency-graph paths
    through 27+ new targets touches this vector too.

## Sources

- This repo, verified directly: `git show --stat f8e7aa82e1`, `git show --stat 6842053f8d`,
  `git log -p -1 6842053f8d -- .bazelrc`, current `.bazelrc` (lines 31-72), root `BUILD.bazel`
  (kotlinc_opts/toolchain/plugin declarations), `kmp/src/{commonTest,jvmTest,androidUnitTest,
  businessTest}/kotlin/BUILD.bazel` (existing `associates` usage).
- [Persistent Workers — Bazel main](https://docs.bazel.build/versions/main/persistent-workers.html) — worker-count/memory tradeoff, default `--worker_max_instances=4`, WorkerKey model.
- [Global max worker instances limit · Issue #12165 · bazelbuild/bazel](https://github.com/bazelbuild/bazel/issues/12165)
- [Bazel OOMs during analysis phase when KSP provides Java outputs information · Issue #1043 · bazel-contrib/rules_kotlin](https://github.com/bazel-contrib/rules_kotlin/issues/1043)
- [Broaden support for "friends" (access to `internal` members) in rules_kotlin · Issue #211 · bazelbuild/rules_kotlin](https://github.com/bazelbuild/rules_kotlin/issues/211)
- [rules_kotlin/kotlin/internal/jvm/jvm.bzl at master · bazelbuild/rules_kotlin](https://github.com/bazelbuild/rules_kotlin/blob/master/kotlin/internal/jvm/jvm.bzl)
- [GitHub - bazel-contrib/bazel-gazelle](https://github.com/bazel-contrib/bazel-gazelle) — codegen-over-hand-maintained-BUILD-files pattern at scale (Uber ~1M files, >10k commits/week).
- [GitHub - cirruslabs/bazel-project-generator](https://github.com/cirruslabs/bazel-project-generator) — Kotlin/Java-specific BUILD-file generation gap.
- [Android manifest merger error messages are unnecessarily dropped · Issue #6904 · bazelbuild/bazel](https://github.com/bazelbuild/bazel/issues/6904)
- [using androidx.work libraries causes manifest merge issue · Issue #6645 · bazelbuild/bazel](https://github.com/bazelbuild/bazel/issues/6645)
- [bazel doesn't merge all the AndroidManifest.xml · Issue #2919 · bazelbuild/bazel](https://github.com/bazelbuild/bazel/issues/2919)
- [Android: failure depending on libraries that define a resource with the same name · Issue #565 · bazelbuild/bazel](https://github.com/bazelbuild/bazel/issues/565)
- [Permit disabling resource merging · Issue #10 · bazelbuild/rules_android](https://github.com/bazelbuild/rules_android/issues/10)
- [Why is my Bazel build so slow? | BuildBuddy](https://www.buildbuddy.io/blog/debugging-slow-bazel-builds/) — cold-cache/cold-analysis-phase cost.
- [Faster Bazel builds with remote cache — Depot.dev](https://depot.dev/blog/faster-bazel-builds-with-remote-cache)
- [Optimizing performance — Bazel main](https://docs.bazel.build/versions/main/skylark/performance.html) — analysis-phase share of build time, package-loading slowdowns from excessive target counts.
- [Breaking down build performance | Bazel](https://bazel.build/versions/8.7.0/advanced/performance/build-performance-breakdown)
- [Stabilize, Modularize, Modernize: Scaling Slack's Mobile Codebases | Engineering at Slack](https://slack.engineering/stabilize-modularize-modernize-scaling-slacks-mobile-codebases/)
- [Modernizing our Android build system: Part I, the planning — Dropbox](https://dropbox.tech/mobile/modernizing-our-android-build-system-part-i-the-planning)
