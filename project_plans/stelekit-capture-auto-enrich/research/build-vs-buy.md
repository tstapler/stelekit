# Research: Build vs. Buy — stelekit-capture-auto-enrich

Agent 6 (Build vs. Buy). Scope: confirm/challenge the "wire existing in-house
machinery" framing, and evaluate the one genuinely new piece — the race-safe,
single-flight, lazily-constructed per-graph coordinator (AC #8).

**Headline verdict: the requirements doc's framing is correct.** Every piece this
feature touches — matching, extraction, enrichment abstraction, and (per this
research) even the concurrency idiom the new coordinator needs — already exists in
this repo, tested, and in production use by `ImportViewModel`/`TagSuggestionEngine`.
There is nothing here that justifies pulling in an OSS library, a SaaS API, or an
LLM-generated implementation of anything algorithmic.

## 1. Race-safe single-flight lazy construction (AC #8)

**Question:** is there a standard `kotlinx-coroutines-core` idiom/library for
"memoized, single-flight, race-safe lazy async construction" beyond raw `Mutex` +
nullable field, or `CompletableDeferred`?

### Option A — `kotlinx-coroutines-core` built-in

- **Pros:** zero new code; `Mutex`, `CompletableDeferred`, and `Deferred` are already
  first-party, already on the classpath (`kmp/build.gradle.kts:94`, pinned 1.10.2 per
  `research/stack.md`).
- **Cons:** the library ships the *primitives* (`Mutex`, `CompletableDeferred`), not a
  packaged "memoized async supplier" combinator. There's no `Deferred.memoize { }` or
  `singleFlight { }` helper in `kotlinx-coroutines-core` as of 1.10.2 — you still write
  the guard yourself.
- **Verdict: Recommended** (this is what "build it" resolves to — see below). The
  primitives are the buy-vs-build answer for the mechanism; the 5–10 line wrapper
  around them is not something a library should own.

### Option B — third-party single-flight library (e.g. a Kotlin port of Go's
`golang.org/x/sync/singleflight`, or a coroutines "AsyncCache")

- **Pros:** none specific to this problem — the entire implementation surface is
  "one `Mutex`, one nullable field or one `CompletableDeferred`, checked/set inside
  the lock." A library adds an artifact, a version to pin, and an API to learn for
  something the repo can express in under 10 lines.
- **Cons:** unnecessary dependency for local, single-process, single-JVM/Android
  coordination (no cross-process or distributed concern here — this is in-memory
  per-`CaptureActivity`-process state); another supply-chain surface; KMP-target
  compatibility risk (any candidate would need JVM + Android targets to match this
  module's `androidMain`/`jvmMain` split, and most Go-singleflight ports don't target
  Kotlin at all).
- **Verdict: Not recommended.**

### Option C — hand-rolled `Mutex`-guarded lazy init (the repo's existing idiom)

This is not a novel pattern for this codebase — it already exists in two forms,
independently converged on by different authors, which is itself evidence it's the
right level of abstraction here rather than something under-engineered:

1. **`RequestCoalescer<K, V>`**
   (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/RequestCoalescer.kt:39-74`) —
   a general-purpose `Mutex` + `HashMap<K, CompletableDeferred<V>>` single-flight
   coalescer, already used for DB reads. Its shape is the closest existing analog, but
   its semantics don't transfer directly: `execute()` removes the key from `inflight`
   once the loader completes, so the *next* call starts a fresh load — correct for
   repeated DB reads, wrong for a coordinator meant to live for the rest of the
   capture session. `coordinatorFor` needs *permanent* memoization (build once per
   graph, never re-trigger construction), not *transient* coalescing.
2. **Mutex-guarded `getOrPut` on a `MutableMap`** — `GraphLoader.getFileLock()`
   (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt:1310-1317`,
   `fileLocksMutex.withLock { fileLocks.getOrPut(path) { Mutex() } }`) and
   `BlockStateManager.contentMutationMutex()`
   (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt:813-817`)
   both use "construct-once-under-lock" for per-key resources that must persist for
   the life of the owning object — exactly `coordinatorFor`'s requirement.

- **Pros:** matches an established, reviewed, tested idiom already in the codebase
  twice over; trivially small (a `Mutex` + nullable field, or a memoized
  `CompletableDeferred`, both ~10 lines); the requirements doc's own AC #8 explicitly
  names "`Mutex` or a memoized `Deferred`" as the accepted shape, ruling out the
  double-checked-nullable-field-outside-the-lock anti-pattern the AC is written to
  close.
- **Cons:** none identified — the only risk is implementing it wrong (checking the
  field outside the lock), which AC #8's wording already guards against by requiring
  the check happen under the `Mutex`/inside the `Deferred`.
- **Verdict: Recommended.** `research/stack.md` (Agent 1, §2) independently reaches
  the same conclusion with a worked code sketch — this is corroboration from a
  different research angle, not duplicated work: a `coordinatorMutex.withLock { }`
  guarding a nullable `coordinator` field (or an equivalent memoized
  `CompletableDeferred<CaptureEnrichmentCoordinator>`), keyed per active graph, is the
  correct and sufficient implementation. No new abstraction, no new dependency.

## 2. SaaS/managed API

Not applicable. `coordinatorFor` construction is in-process, in-memory coordination
between coroutines inside a single `CaptureActivity` process (guarding against two
`PageNameIndex` builds racing on the same device) — there is no network boundary, no
shared state across devices/processes, and no case where a managed service could
substitute for a local `Mutex`. Moving on.

## 3. LLM-generated implementation vs. battle-tested library — Aho-Corasick matcher
   and topic-extraction heuristics

This dimension doesn't apply as "build vs buy" either — both pieces already exist
in-house and are already tested; the only question is whether there's any reason to
touch them for this feature. There is not.

- `AhoCorasickMatcher` —
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcher.kt`,
  covered by `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcherTest.kt`
  (251 lines).
- `PageNameIndex` (wraps the matcher, rebuilds it reactively from
  `PageRepository.getPageNameEntries()`) —
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/PageNameIndex.kt`, covered by
  `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/PageNameIndexTest.kt`
  (367 lines) plus `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/PageNameIndexResilienceTest.kt`
  (OOM/closed-DB resilience) and reuse in
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngineTest.kt` /
  `TagSuggestionViewModelTest.kt` (already a second production consumer besides
  Import).
- `TopicExtractor` (noun-phrase/concept heuristic scoring) —
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/TopicExtractor.kt`, covered
  by `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/TopicExtractorTest.kt`.
- `ImportService.scan()` (the pure function that composes matcher + extractor into
  `ScanResult`) — covered by
  `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/ImportServiceTest.kt` and
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/domain/ImportServiceTest.kt`.

- **Pros of reuse as-is:** zero regression risk to `ImportViewModel`/
  `TagSuggestionEngine` (both already depend on these exact classes — a behavior
  change here is a behavior change for two shipped features, not just capture);
  `PageNameIndex` already carries hard-won correctness fixes specific to this
  domain — OOM-safe trie construction (`PageNameIndex.kt:52-56`, catches `Throwable`
  around the trie build), closed-DB resilience, journal-page exclusion, stopword
  filtering, stem/parenthetical-alias matching — re-deriving any of this (by hand or
  by LLM) would mean re-discovering bugs already fixed once.
- **Cons of reuse:** none — the requirements doc explicitly scopes "no new
  matching/suggestion algorithms" as out-of-scope, and nothing about the capture
  context (short-lived Activity, smaller text, same graph backends) requires
  different matching/extraction behavior than Import already exercises.
- **Cons of an LLM-generated or newly-written replacement:** would duplicate ~600
  lines of existing test coverage from scratch, forfeit the OOM/closed-DB hardening
  above, and risk subtly different matching behavior between Import and Capture for
  the same input text — directly contradicting the "No regression in
  `ImportViewModel`/`TagSuggestionEngine` behavior" success metric.
- **Verdict: Recommended (reuse verbatim) / Not recommended (any replacement).** This
  is purely a wiring task for already-correct, already-tested algorithmic code —
  confirmed, not just assumed.

## 4. Fork/adapt `ImportViewModel` vs. new purpose-built `CaptureEnrichmentCoordinator`

### Option A — extend/wrap `ImportViewModel` directly (inheritance or holding an
instance)

- **Pros:** would reuse `ImportViewModel`'s `runScan()`/enrichment coroutine
  structure without re-deriving the wiring order.
- **Cons — decisive:**
  - `ImportViewModel`'s constructor
    (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt:110-120`)
    takes `matcherFlow: StateFlow<AhoCorasickMatcher?>` as an **injected** dependency —
    it does not build a `PageNameIndex` itself. AC #8's entire problem (nothing
    guarantees a `PageNameIndex` exists when `CaptureActivity` cold-starts) is
    upstream of anything `ImportViewModel` does; there is no logic in
    `ImportViewModel` to extend for it. `ImportViewModel` is instantiated (with a
    ready-made `matcherFlow`) by whatever already owns a live
    `StelekitViewModel`/`PageNameIndex` — precisely the assumption AC #8 says capture
    cannot make.
  - `ImportState`
    (`ImportViewModel.kt:59-81`) carries a large surface of full-screen-review-only
    state the capture bottom sheet has no use for and must not expose: `pageName`,
    `pageNameError`, `urlInput`, `activeTab`/`ImportTab`, `rawHtml`, `isFetching`/
    `fetchError` (URL-fetch tab), `savedPageName`, `undoBuffer`/`showUndoSnackbar`/
    `undoLinkedText` (multi-page undo — capture only ever touches one block).
    Inheriting or wrapping this class means either dragging all of it along
    (violates "no new abstractions beyond what the task requires" and bloats the
    capture sheet's state) or overriding/stubbing most of it, which is more code and
    more risk than composing the two or three functions capture actually needs.
  - `ImportViewModel` is a `androidx.lifecycle`-agnostic KMP class already, but its
    lifecycle assumptions (long-lived screen, `Job`-based cancellation on navigation
    away, undo-buffer cleanup "after confirm or navigation away") don't map onto a
    short-lived, possibly-cold-started Activity with its own AC #9 (post-save chip
    acceptance on an already-`finish()`-pending sheet) — a scope `ImportViewModel`
    was never designed to support.
- **Verdict: Not recommended.**

### Option B — new, small, purpose-built `CaptureEnrichmentCoordinator` (as named in
the acceptance criteria) that composes the same primitives `ImportViewModel` uses

- **Pros:** matches the acceptance criteria's own naming and scope; can depend on
  exactly what capture needs — `PageNameIndex`/`AhoCorasickMatcher` (constructed
  race-safely per AC #8, since `ImportViewModel` doesn't do this), `ImportService.scan()`,
  `TopicEnricher`, `GraphWriter.savePage`/`PageSaver`, `DatabaseWriteActor` — without
  inheriting any full-screen-review state; keeps `ImportViewModel` untouched (directly
  satisfies the "No regression in `ImportViewModel` behavior" success metric, since
  nothing about it changes); mirrors the constraint in the requirements doc verbatim
  ("Any new capture-specific type ... exists only to bind these together per capture
  session, not to introduce a parallel suggestion algorithm or a parallel LLM
  abstraction").
- **Cons:** the fire-and-forget/timeout-bounded LLM-enrichment coroutine pattern
  `ImportViewModel` already implements (mirrored per requirements §3) has to be
  re-expressed in the new class rather than inherited — but this is a handful of
  lines around `TopicEnricher.enhance()` with a `withTimeout`, not algorithmic logic,
  and duplicating it is cheaper and safer than coupling capture's lifecycle to
  `ImportViewModel`'s.
- **Verdict: Recommended.** Build `CaptureEnrichmentCoordinator` as a small new class
  that owns exactly: (a) race-safe `PageNameIndex` construction/memoization per graph
  (AC #8, `Mutex`-guarded per §1 above), (b) a thin call into `ImportService.scan()`,
  (c) the fire-and-forget `TopicEnricher` pass, and (d) the per-suggestion
  `GraphWriter.savePage` + failure-isolated write-back (AC #7, AC #9). It composes
  existing functions/classes; it does not fork or subclass `ImportViewModel`.

## Summary

| Dimension | Verdict |
|---|---|
| 1. Coordinator concurrency primitive | Build — `Mutex`/`CompletableDeferred`, matching two existing in-repo idioms (`RequestCoalescer`, `GraphLoader.getFileLock`/`BlockStateManager.contentMutationMutex`). No library. |
| 2. SaaS/managed API | N/A — local in-process coordination only. |
| 3. Matcher/extraction algorithms | Reuse verbatim, already tested (`AhoCorasickMatcherTest`, `PageNameIndexTest`, `PageNameIndexResilienceTest`, `TopicExtractorTest`, `ImportServiceTest`). No LLM-generated or new implementation. |
| 4. Wiring class shape | New small `CaptureEnrichmentCoordinator` composing existing pieces — not a fork/subclass of `ImportViewModel`, which doesn't even own the construction problem AC #8 is solving and carries unrelated full-screen-review state. |

No dimension of this feature supports introducing a new dependency, algorithm, or
inheritance relationship. The entire net-new surface is one small coordinator class
using a concurrency idiom this codebase already has in two other places.
