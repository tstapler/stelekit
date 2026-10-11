# Build-vs-buy: observing `JournalService.ensureTodayJournal()` in the 3 rewritten tests

Question: how do the rewritten tests detect that `startMidnightBoundaryWatcher` called
`ensureTodayJournal()` (and, for the guard test, that it did *not*)?

## Facts gathered

- `JournalService` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt:41`)
  is a `final` class (no `open`). It implements one `fun interface`,
  `JournalDateResolver` (line 29), which exposes only `getPageByJournalDate` — `ensureTodayJournal`
  is not part of any interface.
- `StelekitViewModel` resolves its `journalService` field as
  `deps.journalService ?: JournalService(deps.pageRepository, deps.blockRepository)`
  (`StelekitViewModel.kt:103-104`). Both branches of that `?:` must be the same concrete type,
  so `StelekitViewModelDependencies.journalService` is typed as concrete `JournalService`, not an
  interface. A wrapper/spy could only be substituted here if it *is-a* `JournalService`, and
  `final` forbids subclassing.
- `kmp/build.gradle.kts` has no `mockk`, `mockito`, or `all-open` plugin on any source set —
  confirmed via `grep -in "mockk\|mockito\|all-open\|allopen" kmp/build.gradle.kts` (no output).
  The test classpath has no mocking library capable of mocking a final class today.
- The existing correct test (`GraphLoaderProgressiveTest.kt:841`, "via real
  startMidnightBoundaryWatcher") already observes `ensureTodayJournal`'s effect by polling
  `pageRepo.getJournalPageByDate(date).first().getOrNull()` on a real in-memory
  `InMemoryPageRepository`, with a real `JournalService` wired to a shared `FakeClock`.

## Option A — reuse the existing pattern (repository-state polling, no new code)

Observe via `pageRepo.getJournalPageByDate(date).first().getOrNull()`, exactly as line 841 does.

**Pros**
- Zero new code, zero new dependencies — matches Complexity-1 / YAGNI directive in
  requirements.md.
- Exercises the real call chain end-to-end (`startMidnightBoundaryWatcher` → `journalService` →
  `pageRepository`), so it satisfies AC-01 (fails if the method is deleted/broken) and AC-03
  (drives the real entry point) directly.
- Already proven to work and pass in this file — no new pitfalls to discover.

**Cons**
- Doesn't give an exact call *count* — it tells you "a page for date X exists" or not, not "was
  `ensureTodayJournal` invoked N times." For the skip-guard test (AC-04), this matters: calling
  `ensureTodayJournal()` twice on the same date is idempotent from the repository's point of view
  (it's an upsert-by-date), so repository state alone can't distinguish "called once" from "called
  twice on the same day."
- Requires the polling idiom (`withTimeout` + spin-loop on a real dispatcher) since the watcher
  runs on `Dispatchers.Default`, not the test scheduler — slightly more verbose than a direct
  assertion, but this is exactly the idiom the reference test already established.

**Verdict**: Sufficient for AC-01–AC-03 and for the cancellation test outright (assert no page
for the crossed date after `vmScope.cancel()`). For the guard test, sufficient as long as the
assertion is reframed around *distinct dates* rather than call count — see below.

## Option B — extract a `JournalDateResolver`-style interface, or add a mocking library

Either (a) widen `JournalService` into an interface so it can be faked/mocked, or (b) add mockk
(with `mockkClass`/relaxed mocks, which can mock final classes without an `open` change) or the
`all-open` Gradle plugin.

**Pros**
- A mock/spy would give an exact invocation count directly (`verify(exactly = 1) { ... }`),
  which most directly matches "assert `ensureTodayJournal` was called N times."

**Cons**
- (a) is a production-code interface extraction — explicitly out of scope per requirements.md
  ("Out of Scope: Broader refactors of `JournalService` into an interface for mocking... not
  expected").
- (b) adding mockk/all-open is a new test dependency for a Complexity-1, test-only fix; violates
  the YAGNI framing in requirements.md's Complexity section ("no new dependencies"). It would also
  need to flow through `StelekitViewModelDependencies.journalService`, whose type is concrete
  `JournalService` — a mockk relaxed mock of a final class works at the JVM level (mockk can
  mock final classes without `all-open`), so it's *technically* possible without touching
  production code, but it still means introducing an unused-elsewhere dependency into the build
  for 3 tests when Option A already meets every AC.
- Overkill relative to the existing reference test, which the requirements explicitly says to
  follow, not diverge from.

**Verdict**: Not needed. Both variants either violate explicit out-of-scope constraints (interface
extraction) or add a new dependency the requirements say to avoid, for a benefit (exact call
count) that Option A can also achieve by counting distinct journal dates created rather than
literal invocations.

## Option C — thin test-only counting wrapper around `JournalService`

Wrap-and-delegate (composition, not subclassing, since `JournalService` is final) around a real
`JournalService`, incrementing a counter on every `ensureTodayJournal()` call.

**Pros**
- Gives an exact call count without any new library.

**Cons**
- Not pluggable: `StelekitViewModelDependencies.journalService` is typed as concrete
  `JournalService` (see Facts above), and a wrapper built by composition is not a `JournalService`
  — it cannot be passed to that constructor parameter without production code changes (e.g.
  turning the field into an interface type, which is the same out-of-scope move as Option B(a)).
  A wrapper is therefore not viable *as a drop-in dependency* here — it's blocked by the same
  concrete-type constraint that rules out a subclass-based spy.
- Even if it were pluggable, it duplicates information already recoverable from repository state:
  each `ensureTodayJournal()` call in these tests corresponds 1:1 with a distinct journal date
  becoming observable in `pageRepo`, so a wrapper's counter would track the same signal Option A
  already gets for free.

**Verdict**: Not viable without a production-code change to `StelekitViewModelDependencies`, and
not needed even if it were, since AC-04 doesn't actually require a raw call count.

## Recommendation

**Use Option A**, matching the line-841 reference implementation exactly. For the specific ACs:

- **Cancellation test**: after `vmScope.cancel()` and advancing the clock past a midnight
  boundary, assert `pageRepo.getJournalPageByDate(crossedDate).first().getOrNull() == null`
  (poll with a short timeout to allow any in-flight coroutine to settle, then assert absence —
  or simply `delay` briefly since cancellation should prevent the write from ever starting).
- **`lastJournalDate` guard test**: seed the watcher per production behavior (starting the clock
  such that `lastJournalDate` is set to the date of the *first* crossing target), advance past
  that first boundary, and assert `pageRepo.getJournalPageByDate(firstCrossingDate)` is still
  `null` (proves the guard skipped it) using a bounded wait (e.g. `delay` + one check, since you
  can't poll for absence indefinitely). Then advance past a second boundary and assert
  `pageRepo.getJournalPageByDate(secondCrossingDate)` becomes non-null (proves the guard doesn't
  wrongly suppress the next real crossing). This uses *distinct dates* as the observable instead
  of a raw call count, which is exactly what's needed to distinguish "skipped" from "not yet run"
  per requirements.md's own note on this ambiguity — no counting wrapper required because the two
  crossings target different dates.
- **Simulated day-crossing test**: fold into the line-841 pattern with multiple boundary
  advances, asserting a distinct journal page appears after each crossing (May 29, May 30, May 31),
  per requirements.md's suggestion to merge rather than preserve test count.

No production code changes, no new interfaces, no new test dependencies.
