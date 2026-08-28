# Adversarial Review: stelekit-capture-auto-enrich

**Date**: 2026-08-27
**Verdict**: CLEAN

## Resolved since prior review

- **Blocker 1 (graph-identity gap)** — genuinely fixed. `SavedCaptureContext` (Task
  2.3.2a, plan.md:541-557) now carries `graphId`, `pageRepository`, `blockRepository`
  captured at save time. `acceptSuggestionPostSave()` (Task 4.2.1a, plan.md:788-811)
  compares `ctx.graphId` against `GraphManager.getActiveGraphId()` as its *first*
  statement and `return`s immediately on mismatch, before touching `ctx.pageRepository`,
  `ctx.writer`, or `ctx.writeActor` — a real short-circuit, not logged-and-continued.
  Every subsequent read/write in the method goes through `ctx`'s captured references,
  never a freshly-fetched "active" `RepositorySet`. Story 5.2.5 (plan.md:966-973) is a
  genuine regression test: it asserts `pageRepository.getPageByName`, `writer.savePage`,
  and `writeActor.saveBlock` are **never invoked** on a graph-id mismatch — a real
  zero-calls assertion via spies, not a restated acceptance criterion. ADR-002 constraint
  6 documents the same guard and explicitly disclaims the earlier "`SavedCaptureContext`
  existence alone is sufficient" framing the prior review's Minor flagged.
  Pre-save branch (Task 4.1.1a) legitimately still uses `getActiveRepositorySet()` —
  correct, since no `ctx` exists yet in that branch to compare against.

- **Blocker 2 (save/accept race)** — genuinely fixed, no new deadlock found. Task 2.3.1b
  adds `saveOpMutex` and instructs wrapping `save()`'s entire `scope.launch { }` body
  (performSave() call, `_saveState` update, `savedContext = it`) in
  `saveOpMutex.withLock { }`; Task 4.1.1a shows the literal wrap for `acceptSuggestion()`
  (plan.md:697-724, `scope.launch { saveOpMutex.withLock { ... } }`), including the
  pre-save branch and the `acceptSuggestionPostSave(ctx, term)` call. Traced both critical
  sections for reentrancy: neither `save()`'s body nor `acceptSuggestionPostSave()`/the
  pre-save accept branch calls back into the other, so a non-reentrant `Mutex` cannot
  deadlock here. Story 5.2.6 (plan.md:975-982) is a genuine race test — a
  controllable-delay fake stub-page write, `acceptSuggestion()` then `save()` launched
  before the accept's write resolves, asserting the **persisted text** includes the
  accepted `[[link]]` (a behavioral outcome), not just that a mutex field exists.

- **Blocker 3 (scan collector dies on one Throwable)** — genuinely fixed. Task 2.1.2b
  (plan.md:367-407) wraps each `collectLatest` iteration body in its own
  `try { } catch (e: CancellationException) { throw e } catch (e: Throwable) { ... }`,
  degrading `_scanState` to `NotReady` per-iteration, distinct from the outer
  `CoroutineExceptionHandler`-wrapped `scope` (Task 2.1.2a) which only guards process
  death. Story 5.2.7 (plan.md:984-991) is a genuine survival test: a fake `scan()` throws
  `OutOfMemoryError` on its first call and succeeds on its second; the test asserts the
  *second* text change still produces a normal `ScanState.Ready` — proving the collector
  survives, not just that one throw was caught once.

- **Concern: AC#5 has no verifying test** — fixed. Story 5.2.8 (plan.md:993-1002) uses a
  fake coordinator whose `scan()` suspends indefinitely via an unresolved
  `CompletableDeferred`, and asserts `save()` still reaches `Saved`/`Error` — a real
  synchronous proof `save()` never awaits coordinator/scan work, not an architectural
  argument.

- **Concern: AC#3 malformed LLM output unvalidated** — fixed. Task 1.1.1c's `sanitize()`
  (plan.md:179-186) drops blank/whitespace terms, clamps confidence to `0f..1f`, and
  dedupes case-insensitively; Task 2.2.1b's `mergeBySource()` does the equivalent
  normalized cross-check against local suggestions. Story 5.1.1 (plan.md:910-916) tests
  the sanitizer directly against blank-term/out-of-range-confidence/duplicate input.

- **Concern: AC#6 negative cases untested** — the specific gap named ("at least one
  stated negative case entirely untested") is fixed: Story 5.2.9 (plan.md:1004-1015) adds
  a real test with a spy `HapticFeedback` asserting no `performHapticFeedback` fires on
  the auto-link preview render, and that dismiss never fires the accept-shaped haptic.
  Downgraded to a Minor below only because the original recommendation's other half (a
  single consolidated visual comparison test) wasn't added — see Minors.

- **Concern: `coordinatorFor` no failure-eviction** — fixed. Task 1.2.1b
  (plan.md:259-288) evicts the failed cache entry under `coordinatorMutex` in the
  `catch (e: Throwable)` branch after `deferred.await()` fails, so the next call attempts
  a fresh construction. Story 1.2.1's AC (plan.md:235-236) has an explicit
  Given-When-Then for this.

- **Concern: `GraphManager` scope creep / duplicate `LlmProviderRegistry`** — documented
  as a deliberate, accepted decision, not silently dropped. Pattern Decisions table
  (plan.md:51) explains why `GraphManager` builds its own registry; ADR-001's Consequences
  section (lines 83-98) names the new `llmProviderRegistry` field as a direct consequence
  of the design and separately calls the analogous `PageNameIndex` duplication "a known,
  accepted, bounded cost... not a defect." Downgraded to Minor below only because the
  registry doesn't get that same explicit "second live instance when both Activities are
  alive" callout the `PageNameIndex` bullet gets — an asymmetry in the writeup, not a
  functional gap.

## Blockers

(none)

## Concerns

(none)

## Minors

- Task 2.3.1b instructs wrapping `save()`'s entire `scope.launch { }` body in
  `saveOpMutex.withLock { }` but gives no literal code snippet the way Task 4.1.1a does
  for `acceptSuggestion()`, and the phrasing ("save()'s entire `scope.launch { }` body")
  is imprecise — `save()` currently launches on `viewModelScope`, not the new
  `CoroutineExceptionHandler`-wrapped `scope` field Task 2.1.2a introduces for the
  scan/enrichment coroutines. Functionally this doesn't break the fix (the shared
  `Mutex` instance serializes correctly regardless of which `CoroutineScope` launched
  each coroutine, and `performSave()`'s existing `runCatching` already guards the
  process-crash risk independent of which scope is used) — but an implementer could
  reasonably read "scope.launch" as an instruction to migrate `save()` onto the new
  `scope` field, which isn't otherwise stated as intended. Worth a one-line clarification
  in the task text at implementation time.
- AC#6's original recommendation asked for one consolidated test spanning both the
  visual-distinguishability and haptic-negative-case facets (a Roborazzi screenshot
  comparing the chip against the auto-link preview line, plus the haptic interaction
  test). Story 5.2.9 adds the haptic half genuinely; the visual side-by-side comparison
  was not added — visual distinguishability is still only prose-asserted per-story
  (3.1.1, 3.2.1), not verified in a single test. The substantive gap (untested negative
  cases) is closed; the organizational "one test proves the whole property" goal is not.
- ADR-001's Consequences section documents the `PageNameIndex` duplication cost
  explicitly ("known, accepted, bounded cost... not a defect") but doesn't give the
  lazily-built `LlmProviderRegistry` the same explicit "this is a second live
  registry/credential-store instance whenever `MainActivity` and `CaptureActivity` are
  alive concurrently" framing, even though it's the identical shape of cost. A one-line
  addition would close the asymmetry; not blocking since the underlying decision is
  already documented as deliberate.
- Carried over, still unresolved: the `LlmProviderRegistry`/`LlmCredentialStore`/
  `CredentialStore` construction recipe remains duplicated verbatim between `App.kt`
  and `GraphManager` (Task 1.2.1a). Still just a suggested future shared-factory
  follow-up, not required for this feature.
- The `saveOpMutex.withLock` design (chosen over re-checking `savedContext` mid-accept,
  per the Pattern Decisions table's explicit reasoning) means a slow or stuck
  `acceptSuggestion()` write would block a concurrent `save()` tap for its full duration,
  not just for a snapshot read. This is an inherent, already-reasoned-about tradeoff of
  the chosen fix (and a strict improvement over the prior silent-data-loss bug), not a
  new defect — flagged only as a residual property worth knowing about if a future
  change makes the stub-page/block write markedly slower (e.g., a network-backed
  writer).
- `Task 4.2.1b`'s `@OptIn(DirectRepositoryWrite::class) ctx.blockRepository.saveBlock(...)`
  fallback was checked against the actual codebase: `DirectRepositoryWrite` is a real,
  existing annotation (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DirectRepositoryWrite.kt`)
  correctly gating `BlockWriteRepository.saveBlock` — confirmed correct, not an issue,
  noted here only because it was worth verifying given the codebase's two similarly-named
  write-gating annotations (`@DirectSqlWrite` for `RestrictedDatabaseQueries` vs.
  `@DirectRepositoryWrite` for repository interfaces).
