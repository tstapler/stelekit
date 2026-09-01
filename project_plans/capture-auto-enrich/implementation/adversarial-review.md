# Adversarial Review: capture-auto-enrich

**Date**: 2026-08-10
**Verdict**: BLOCKED

Reviewed against `implementation/plan.md`, `requirements.md`, all four ADRs, and `research/*.md`,
cross-checked line-by-line against the actual `CaptureViewModel.kt`, `CaptureActivity.kt`,
`ImportViewModel.kt`, `PageNameIndex.kt`, `GraphWriter.kt`, `TopicEnricher.kt`,
`ClaudeTopicEnricher.kt`, `LlmFeature.kt`, `LlmProviderRegistry.kt`, `PageRepository.kt`, and
`SteleDatabase.sq`. The plan's own line-number citations are unusually accurate (verified — every
spot-checked `File.kt:NNN` reference matched the real file), which raises confidence in most of
its factual claims but makes it more important to check the parts it *doesn't* cite evidence for.

## Blockers

- [ ] **Stub-page creation sits, unguarded, between the Bug-1 block write and the Bug-8 markdown
      flush, and its failure path is silently discarded end-to-end.** Task 2.1.2b inserts
      `coordinatorFor(repoSet).createAcceptedStubPages(...)` "immediately before the existing
      `writer.savePage(page, existingBlocks + newBlock, graphPath)` call" (`CaptureViewModel.kt:117`)
      — i.e. *after* `writeActor.saveBlock(newBlock)` (line 104, the actual DB write) but *before*
      the markdown flush the code's own comment calls a hard invariant: "Bug 8 mitigation: flush
      the Markdown file after every actor write" (`CaptureViewModel.kt:113`). Task 1.3.2a's
      `createAcceptedStubPages` has no exception isolation of its own, and everything in
      `performSave()` is one `runCatching { }` block — so any exception thrown while building a
      stub `Page` (e.g. `Page`'s constructor validation rejecting an LLM/heuristic-suggested term
      that isn't a legal page name) aborts the markdown flush *after* the DB write has already
      landed, reintroducing exactly the DB/file desync class Bug 8 was built to close, and reports
      `SaveState.Error` to the user even though their note text was already durably written.
      Separately, on the non-throwing failure path: `PageSaver.from(writer)`
      (`ui/screens/ImportViewModel.kt:91-93`) discards `GraphWriter.savePage`'s
      `Either<DomainError, Unit>` return value — confirmed by reading `PageSaver`'s signature
      (`suspend fun save(...)`, returns `Unit`) and `GraphWriter.savePage`
      (`db/GraphWriter.kt:206-212`, returns `Either`, logging failure only internally). Task
      1.3.2a mirrors `ImportViewModel.confirmImport()`'s stub-creation loop "line-for-line,"
      inheriting this exact silent-swallow behavior. But `research/ux.md` §4 — a document this
      plan cites throughout — explicitly singles this case out as the *one* failure mode that
      "should surface an error, because the user took an explicit action... revert the chip to
      its pre-accept state... 'Couldn't create page' snackbar," distinct from every other
      silent-fallback path. No Story or AC in Phase 2/3 implements that; none even acknowledges
      the discarded-`Either` mechanics. Recommendation: (1) wrap `createAcceptedStubPages`'s
      per-suggestion body so a thrown exception degrades that one suggestion (log + skip), never
      aborting the surrounding `performSave()`/markdown flush; (2) stop discarding
      `pageSaver.save`'s `Either` — surface at least a debug log per research's own Observability
      Plan, and ideally the snackbar `research/ux.md` asks for.

- [ ] **No `Throwable`/`CoroutineExceptionHandler` guard on the coordinator's launched
      coroutines — violates this repo's own explicit safety rule.** `CLAUDE.md` states, in a
      section written specifically to prevent this class of bug: "Every long-lived `CoroutineScope`
      that hosts user-path collectors or fire-and-forget launches must attach a
      `CoroutineExceptionHandler`" and "per-call-site `catch(Throwable)` is not sufficient" once a
      scope lacks one, because an uncaught `Throwable` (notably OOM) kills the *process* on
      Android. Task 1.1.2a's `scanJob = scope.launch { delay(300); ...; runScan(text, matcher) }`
      has no try/catch at all around `runScan` (which calls `ImportService.scan()` on
      user-supplied text). Task 1.2.1a's `launchLlmEnrichment` catches only
      `TimeoutCancellationException` and `Exception`, not `Throwable` — mirroring
      `ImportViewModel.runScan`'s Coroutine 2 exactly, which has the identical gap. In production
      this scope is `viewModelScope` (Task 2.1.1a/2.2.3a), which Task 2.2.3a itself confirms has
      no default `CoroutineExceptionHandler` — and then defers the fix: "if time permits within
      this story's budget, wrap the `state.collect{}` forwarding launch in a try/catch." That
      only covers the *forwarding* collector, not `scanJob`'s `runScan` call or
      `launchLlmEnrichment`'s coroutine — the two places most likely to see an OOM-class
      `Throwable` (matcher rebuild on a large graph is exactly the scenario `PageNameIndex.kt:52-56`
      already documents as OOM-capable, and this coordinator's own scan runs the same class of
      trie/text work on `Dispatchers.Default`). Requirements.md's "no partial/stalled saves"
      guarantee is not actually met if the failure mode is a process kill rather than a graceful
      fallback. Recommendation: make a `CoroutineExceptionHandler` on the coordinator's owned
      scope non-optional (not "if time permits"), and change `catch (e: Exception)` to
      `catch (e: Throwable)` (re-throwing `CancellationException`) in both `scanJob`'s scan body
      and `launchLlmEnrichment`, matching `PageNameIndex.matcher`'s own precedent in the same
      package.

## Concerns

- [ ] **`coordinatorFor()` memoizes permanently on first build, contradicting its own story's
      "once per active graph" framing.** Task 2.1.1a: `coordinator ?: CaptureEnrichmentCoordinator(
      pageRepository = repoSet.pageRepository, ...).also { coordinator = it }` — this ignores the
      `repoSet` argument on every call after the first. If the active graph changes while a
      `CaptureActivity` is open (`GraphManager` is a process-wide singleton; multi-graph switching
      is a supported feature per `CLAUDE.md`), `resolveForSave`/`createAcceptedStubPages` keep
      operating against the *old* graph's `PageNameIndex`/`pageRepository` even though
      `performSave()` re-fetches a fresh `repoSet` for the block write itself — auto-links and
      stub pages would be resolved against the wrong graph. Narrow window for a personal app, but
      a real mismatch between the story's stated intent and the memoization key. Fix: key the
      cache on `repoSet` (or graph id) instead of "ever built."

- [ ] **Duplicate stub-page TOCTOU race (`research/pitfalls.md` finding #5, point 4) is tested
      sequentially, not concurrently, and the plan never checks the schema-level safety net that
      actually matters.** Story 1.3.2's second AC and Task 4.1.2a construct scenario A completing
      *before* B starts — that's the case the live `getPageByName()` read already handles
      trivially. The race pitfalls.md actually flagged is "both read 'not found' before either
      write commits," which this plan's tests never exercise. Separately (found during this
      review, not in the plan): `SteleDatabase.sq` already has `UNIQUE(name, section_id)` on
      `pages` and `insertPage` uses `INSERT OR IGNORE` — so the true concurrent-write case likely
      *doesn't* produce a duplicate DB row today. But the plan never verifies this, never cites
      it, and doesn't address the still-open question of two concurrent
      `GraphWriter.savePage()` calls racing to write a markdown *file* for the same page name
      (the DB constraint says nothing about file-path collisions on disk). Given this is one of
      the plan's own headline "solved" pitfalls (Story 1.3.2's explicit AC), the gap between what
      it claims to close and what it actually tests is worth resolving before calling this done.

- [ ] **Cross-capture LLM cost/rate-limiting (`research/pitfalls.md` [P1] recommendation #5) has
      zero coverage** — no story, task, or Unresolved Question addresses it. Pitfalls.md explicitly
      asked the plan phase to "size the capture-in-a-burst cost scenario" (N rapid shares = N
      independent, un-cooldown'd LLM calls, unlike Import's single-document flow). Each capture is
      individually bounded (8s timeout, opt-in gate), but nothing bounds a burst of 10 captures
      fired in a row. Given the sole stakeholder is a solo user (per requirements.md), this is
      lower-stakes than it would be for a shared product, but the plan should at least record it
      as an accepted risk rather than silently dropping a research doc's explicit ask.

- [ ] **`LlmProviderRegistry.availableForFeature()`'s network-I/O status is still an open question
      at the end of planning.** Both `research/pitfalls.md` ("not resolved by this pass") and
      `research/architecture.md` (open question #3, implicitly) flag that `checkAvailability()`
      might do network I/O, which would matter because Task 1.2.2c/2.1.1a call it unconditionally
      once per `CaptureViewModel`/coordinator construction. Verified independently during this
      review: `LlmProviderRegistry.availableProviders()` (`llm/LlmProviderRegistry.kt:44-45`) is
      explicitly documented as "re-evaluated on every call, never a cached snapshot" — that's a
      real signal it's *designed* to be called repeatedly and cheaply, which weakens the concern,
      but the plan never actually reads `checkAvailability()`'s implementation to confirm it, so
      this remains an assumption carried through three research/planning passes without ever being
      checked.

- [ ] **Unresolved Question #1 self-contradicts its own "blocks" language.** The plan states the
      Settings-toggle question "blocks Story 1.2.2 — owner: Tyler," then immediately proceeds to
      spec Story 1.2.2's tasks against a hardcoded default ("always Auto, no dedicated toggle in
      v1") with no task or gate that actually waits on Tyler's answer. Either the dependency
      graph should show 1.2.2 blocked pending that decision, or the "blocks" language should be
      softened to "affects" since the plan already made the call.

## Minors

- `resolveForSave`'s "wrap the whole body so any unexpected exception... also falls back to text"
  behavior (Task 1.1.2c) is described in prose but Story 1.1.2's ACs only test "matcher not ready
  in budget," not "`ImportService.scan()` itself throws mid-call" — the two are different code
  paths through the same wrapper and only one has a named test.
- `getPageByName()` DB-read failures (`Either.Left`) are treated identically to "page not found"
  via `.getOrNull()` in both the existing `ImportViewModel.confirmImport()` pattern and this
  plan's mirrored `createAcceptedStubPages` — a transient DB read error could trigger a stub-page
  creation attempt rather than a safe abort. Pre-existing pattern, not newly introduced by this
  plan, but propagated to a second call site.
- Chip dismiss-icon touch-target sizing (WCAG 24×24/44dp guidance in `research/ux.md` §3) is
  explicitly deferred as "not this feature's to fix" (Task 3.2.2c) — reasonable scoping, already
  flagged as a pre-existing gap in the shared component, just noting it's a known, accepted debt
  rather than an oversight.
- ADR-001/Story 2.1.3's "4 existing comparison sites" undercounts by one against the actual file
  (`CaptureActivity.kt:218,219,234,305,310` — 5 sites across 4 cited locations, since 218/219 are
  grouped as one `is`-match block); cosmetic, doesn't change the verification task's validity.
