# Findings: Pitfalls — Capture Auto-Enrich

**Date**: 2026-08-27
**Feature**: stelekit-capture-auto-enrich
**Research method**: Codebase reading (this project's own prior incident docs + current `CaptureViewModel.kt`/`CaptureActivity.kt`), no training-knowledge speculation

---

## Summary

This feature doesn't invent a new failure surface — it grafts a proven pipeline
(`ImportService`/`PageNameIndex`/`TopicEnricher`) onto a host (`CaptureActivity`)
whose lifecycle, scope-ownership, and cold-start properties are much harsher than
the in-app Import screen the pipeline was built for. Every pitfall below is one of:
(a) a failure mode already documented for this exact pipeline in
`project_plans/import-topic-suggestions/research/pitfalls.md`/`architecture.md`
that still applies, (b) one of this repo's hard, test-enforced rules from the root
`CLAUDE.md` that the new capture wiring is at concrete risk of violating, or (c) a
new pitfall specific to `CaptureActivity`'s host characteristics: `singleTop`,
`excludeFromRecents="true"`, cold-startable without `MainActivity`/`StelekitViewModel`,
and (post-AC#9) an Activity that no longer `finish()`es immediately on save.

The two highest-severity, highest-likelihood risks are:
1. **`CaptureViewModel.viewModelScope` has no `CoroutineExceptionHandler`.** Any
   uncaught `Throwable` from the new scan/coordinator-build/LLM-enrichment
   coroutines added to it kills the process on Android — this is the exact
   documented failure class, and this ViewModel doesn't yet have the mitigation
   `StelekitViewModel.scope` already has.
2. **AC#8's coordinator race, if implemented as a nullable-field double-check,**
   reproduces a pattern this codebase has already built (and requirements.md
   explicitly names) the correct fix for — `RequestCoalescer`'s
   `Mutex` + `CompletableDeferred` single-flight idiom is the direct precedent to
   copy, not reinvent.

---

## Risk and Failure Modes

### PF-1: `viewModelScope` has no exception handler — new coroutines can crash the process

**Evidence**: `CaptureViewModel` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`)
extends `AndroidViewModel` and uses the stock `viewModelScope` (`SupervisorJob()` +
`Dispatchers.Main.immediate`, no `CoroutineExceptionHandler`). Its one existing
coroutine (`save()`'s `viewModelScope.launch { ... performSave(...) }`) is safe
today only because `performSave` is entirely wrapped in `runCatching`.

`StelekitViewModel`, by contrast, explicitly does **not** rely on this — its own
`scope` wraps `deps.scope.coroutineContext` with a `CoroutineExceptionHandler` and
a comment explaining why: *"the last line of defense for every coroutine launched
on [it]... large-graph crashes reproduced only on Android"* — see the
"Uncaught coroutine Throwables kill the process on Android" rule in this
worktree's root `CLAUDE.md`.

**What can go wrong here specifically**: the new work items are (a) building a
`PageNameIndex`/`CaptureEnrichmentCoordinator` on demand (which — per
`PageNameIndex.kt`'s own doc comment — can throw `OutOfMemoryError` while
building the Aho-Corasick trie on a large graph, an `Error` subtype, not an
`Exception`), (b) the `ImportService.scan()` call, and (c) the LLM enrichment
coroutine mirroring `ImportViewModel`'s fire-and-forget Claude call. If any of
these is `viewModelScope.launch { }`ed without an inner `try/catch(Throwable)` or
without wrapping the whole scope in a handler the way `StelekitViewModel` does,
an OOM (or any other uncaught throwable) during a background-thread trie build
kills the app process — on a *quick-capture* surface, meaning the user's
just-typed, not-yet-saved text is lost outright, which is a strictly worse
outcome than the "no partial or stalled saves" the requirements already call out.

**Mitigation to design against**: either (a) install a `CoroutineExceptionHandler`
on a wrapper scope the same way `StelekitViewModel.scope` does and launch all new
capture-enrichment coroutines on it instead of raw `viewModelScope`, or (b) copy
`PageNameIndex.matcher`'s own pattern verbatim — `catch (e: CancellationException) { throw e } catch (e: Throwable) { /* degrade */ }`
around every new coroutine body, matching what `performSave`'s `runCatching`
already does for the existing save path. Do not assume `runCatching` alone is
sufficient — `runCatching` only catches `Throwable` that isn't a
`CancellationException`-related concern by convention, but the discipline needs
to be applied to *every* new launch site, not just reused implicitly from
`performSave`.

---

### PF-2: AC#8's coordinator race — the anti-pattern is already visible elsewhere in this codebase; the correct idiom is `RequestCoalescer`

**Evidence**: requirements.md AC#8 explicitly names the anti-pattern to avoid
("plain nullable-field double-checked pattern") and the fix ("`Mutex` or a
memoized `Deferred`"). This codebase already has both patterns in the wild:

- **The anti-pattern, in production code today**: `AndroidPhotoPickerLauncher`
  (`kmp/src/androidMain/.../platform/sensor/AndroidPhotoPickerLauncher.kt:44`) uses
  `private var pendingResult: CompletableDeferred<...>? = null` — a bare nullable
  field, not mutex-guarded. It's safe *only* because the Android photo-picker
  contract guarantees at most one callback in flight at a time (single-purpose
  launcher, one call site). It is **not** a template to copy for
  `coordinatorFor` — `CaptureActivity`'s text field can fire concurrent
  change events (fast typing/paste) that would race this exact pattern if reused.
- **The correct idiom, already built and documented for this purpose**:
  `RequestCoalescer<K, V>` (`kmp/src/commonMain/.../cache/RequestCoalescer.kt`) —
  Go's `singleflight` ported to coroutines. Under a `Mutex`, it checks a
  `HashMap<K, CompletableDeferred<V>>` for an in-flight entry; the first caller
  becomes the "owner" that runs the loader and completes the `Deferred`, every
  other concurrent caller for the same key just `await()`s the same `Deferred`.
  Failure propagates to all waiters via `completeExceptionally`. Its own doc
  comment gives the exact use case this feature needs ("`getPageByName` called
  from N concurrent UI collectors").
- Other examples of the guarded-map-of-mutexes pattern in this codebase:
  `GraphLoader.fileLocksMutex` guarding a `fileLocks: MutableMap<Path, Mutex>`
  (`kmp/src/commonMain/.../db/GraphLoader.kt:1310-1316`), and
  `BlockStateManager.contentMutationMutexGuard` guarding per-block mutexes
  (`kmp/src/commonMain/.../ui/state/BlockStateManager.kt:813-817`) — same
  double-locking shape as `RequestCoalescer`, applied per-key instead of
  singleton.

**Mitigation to design against**: `CaptureEnrichmentCoordinator` construction
should be `RequestCoalescer<GraphId, CaptureEnrichmentCoordinator>.execute(graphId) { buildCoordinator(...) }`
(or an inlined `Mutex` + `CompletableDeferred` doing the same thing scoped to one
capture session, since there's only one key per session — a `Mutex`-guarded
nullable `Deferred<CaptureEnrichmentCoordinator>` field, checked/set only inside
`mutex.withLock`, is the minimal correct version of AC#8's ask). The failure
mode to explicitly test: two rapid `updateText()` calls (fast paste followed
immediately by another keystroke) both trigger `coordinatorFor()` before the
first `PageNameIndex` construction completes — assert only one `PageNameIndex`
instance (and therefore only one `getPageNameEntries()` collector) ever gets
created for the session.

---

### PF-3: The coordinator/enrichment coroutine must live in the ViewModel, not a composable's `remember { }` + `rememberCoroutineScope()`

**Evidence**: root `CLAUDE.md`'s "Coroutine scope ownership" rule is explicit:
*"Never pass a `rememberCoroutineScope()` result to a class that outlives the
composable... Any class instantiated inside `remember { }` must own its
`CoroutineScope` internally."* `CaptureScreen` in `CaptureActivity.kt` is a
`@Composable` that today only reads `StateFlow`s exposed by `CaptureViewModel`
(`collectAsState()`) and calls plain methods (`viewModel::updateText`,
`viewModel::save`) — it does not construct anything long-lived via `remember`.

**What can go wrong here specifically**: the natural (wrong) place to wire "scan
on text change, build the coordinator lazily, kick off LLM enrichment" is inside
`CaptureScreen` via `LaunchedEffect(captureText) { ... }` calling a
`remember { CaptureEnrichmentCoordinator(rememberCoroutineScope()) }` — this
reproduces exactly the forbidden pattern the rule calls out, and additionally
means the coordinator gets torn down and rebuilt on every recomposition
(defeating AC#8's single-flight requirement at a different layer: even a
perfectly race-safe `coordinatorFor` inside a class that itself gets recreated
per recomposition still ends up building `PageNameIndex` more than once per
capture session).

**Mitigation to design against**: `CaptureEnrichmentCoordinator` (or whatever
holds the on-demand `PageNameIndex` + enrichment state) must be owned by
`CaptureViewModel` — constructed once per ViewModel instance (i.e., once per
`CaptureActivity` instance, matching `PageNameIndex`'s existing constructor shape
of `(pageRepository, scope)` where `scope` is `viewModelScope`-derived, not a
composable scope). `LaunchedEffect(captureText)` in the composable should only
*call* ViewModel methods (`viewModel.onTextChanged(text)`), never construct or
hold coordinator state itself.

---

### PF-4: `CaptureActivity` no longer finishing on save (AC#9) changes the process-death risk profile

**Evidence**: today, `CaptureActivity.kt`'s `onSaved` callback
(`CaptureActivity.kt:100-106`) calls `finish()` immediately once `SaveState.Saved`
is observed. AC#9 requires the sheet to stay visible in a post-save "Done" state
so a pending chip can still be accepted — this is a real behavioral change to
when (or whether) `finish()` fires, not just an additive UI state.

Two properties of this Activity make the post-save window riskier than it would
be on a normal screen:
- `android:excludeFromRecents="true"` and `android:launchMode="singleTop"`
  (`androidApp/src/main/AndroidManifest.xml:55-60`) — a translucent,
  not-in-recents overlay is exactly the kind of process the OS's low-memory
  killer targets first when the user switches away (e.g., taps another app after
  sharing), because it isn't a "recent task" the user is likely to expect to
  return to.
- `viewModelScope` is tied to `CaptureViewModel`'s clearing, which happens at
  `onCleared()` — normally driven by `finish()`/`onDestroy()`. If the process is
  killed outright (not just the Activity finished), there's no `onCleared()` at
  all; any in-flight AC#9 second write (`writeActor.saveBlock` + the markdown
  flush) is abandoned mid-flight with no completion guarantee — the same shape
  of risk `performSave`'s "Bug 8 mitigation" comment already flags for the
  *first* write ("flush the Markdown file after every actor write"), except
  AC#9 introduces a second write window that's open for as long as the user
  lingers on the post-save sheet, i.e., an unbounded amount of time, not the
  single save() call's bounded window.

**Mitigation to design against**: keep the AC#9 second write's blast radius
exactly as narrow as requirements.md already specifies (one `saveBlock` + one
`savePage` call on the already-persisted `BlockUuid`, same `writeActor`/
`GraphWriter` instance) — do not let the post-save window introduce any new
buffered/debounced state that could be lost to a process kill. Since the block
was already durably saved before the post-save state was entered, the worst
case of losing the coordinator/chip-acceptance mid-write is "the suggested
stub page and its `[[link]]` never got created" — a lost enhancement, not lost
user content — provided the implementation is careful not to reorder this so
the *original* save is what's still in flight when the process dies. This
ordering constraint (chip-accept write only ever starts after the original
`performSave` has fully returned) is worth stating explicitly in the
project's ADR-002, since it's the property that makes the "narrowly-scoped
second write" safe to reason about independent of process death.

---

### PF-5: `ClosedSendChannelException` graph-switch race — does AC#9's second write reuse the guard, or reopen the gap?

**Evidence**: `performSave()`'s existing "Bug 1 mitigation"
(`CaptureViewModel.kt:100-107`) catches `ClosedSendChannelException` around the
*first* `writeActor.saveBlock()` call and rethrows it as a clear
`IllegalStateException("Graph switched during save — please retry")`. This
exists because `writeActor` is captured from `repoSet.writeActor` at the start
of `performSave`, and a graph switch mid-save can close that actor's channel out
from under the in-flight call.

AC#9 requires the second write to reuse "the same `writeActor`/`GraphWriter`
instance from the original save" — which is the right call for scope
discipline, but it means the second write is *more*, not less, exposed to this
race than the first: the time window between the original save completing and
the user tapping a chip on the post-save sheet is unbounded (see PF-4), so the
odds of a graph switch happening in that window (user switches active graph in
the main app while the capture overlay is still open, or `GraphManager` closes
the write actor for any other reason — e.g. app backgrounded and the graph is
torn down) are much higher than during the original save's short window.

**Mitigation to design against**: the second `writeActor.saveBlock()` call needs
the identical `try { } catch (e: ClosedSendChannelException) { ... }` guard the
first one has — and the failure needs isolated handling consistent with AC#7
(surface/log, don't crash the sheet), since by AC#9's post-save point the block
itself is already durably saved; a stale-actor failure on the *second* write
should degrade to "suggestion not applied" (with the chip's failure surfaced),
never resurrect the "please retry" full-save-retry UX the first write's
exception message implies — that message is written for the pre-save case and
would be misleading if reused verbatim for a post-save chip failure.

---

### PF-6: `onNewIntent` re-entry doesn't clear in-flight coordinator/scan state — a stale scan silently applies to the wrong content

**Evidence**: `CaptureActivity.onNewIntent()` (`CaptureActivity.kt:115-124`)
re-parses the share intent and calls `viewModel.initializeText(...)`, which is a
no-op once the text field is non-empty (`CaptureViewModel.kt:47-51`, its own doc
comment: *"idempotent for singleTop re-launch"*). Because `android:launchMode="singleTop"`,
a second share (or a duplicate intent delivery — a known Android quirk with some
share targets) while the sheet is still open reuses the same `CaptureViewModel`
instance rather than spawning a new one.

`initializeText`'s existing guard means the *text* can't be silently overwritten
mid-capture — but it says nothing about a scan/coordinator-build already in
flight for the first intent's text. If (per PF-2/PF-3) the coordinator or scan
job is keyed off `captureText` changes via something like
`viewModelScope.launch { captureText.collectLatest { scan(it) } }`,
`collectLatest` already cancels the stale scan correctly on any subsequent
emission — but if the scan/enrichment job is instead structured as an
independent one-shot `launch` per keystroke (not `collectLatest`/`flatMapLatest`
on the `StateFlow`), a second intent's no-op `initializeText` call still
wouldn't retrigger a fresh scan (since the `StateFlow` value doesn't change), so
this isn't a duplicate-scan risk — but it *is* a "first scan silently becomes
stale" risk in the opposite direction: if `initializeText` had fired (field was
actually empty, e.g., text was cleared by the user right before the second
share arrives), a first scan job racing a rapidly superseded second one needs
the same `collectLatest`-style cancellation-of-the-old-in-favor-of-the-new
discipline `ImportViewModel`'s existing `scanJob` reportedly follows (per
`import-topic-suggestions/research/pitfalls.md` FM-2's "Coroutine lifecycle" op
note — cancel-and-restart, not fire-and-forget-and-ignore).

**Mitigation to design against**: use `captureText.collectLatest { }` (or
equivalent explicit `Job.cancel()` on supersession) for the scan trigger, not a
bare `launch` per text-change event — this is required regardless of
`onNewIntent` specifically, but `onNewIntent`'s re-entry path is the concrete
scenario in this feature that actually exercises "text changes while a scan is
in flight" outside of normal typing, so it's worth an explicit test case (share
intent A arrives, scan starts; share intent B arrives before A's scan
completes and finds the field non-empty so no-ops; assert A's scan result is
still the one that lands — no result the user never saw applies to a session
they've moved past).

---

### PF-7: Duplicate `PageNameIndex` construction when the main app process is already alive — resource duplication on large graphs, not correctness

**Evidence**: `PageNameIndex` is documented in requirements.md as "already
instantiated once per active graph inside `StelekitViewModel`" — there is no
`GraphManager`/`RepositorySet`-level cache of a `PageNameIndex` shared across
consumers; it's a ViewModel-owned object. `CaptureActivity` is a separate
Android `Activity` in the *same process* as `MainActivity`/`StelekitViewModel`
when both happen to be alive simultaneously (e.g., user has SteleKit open in the
background, then shares something from another app — a very ordinary usage
pattern, not an edge case). Because `PageNameIndex`'s trie build is the
documented OOM risk on large graphs (see PF-1; `LargeGraphWarmStartCrashTest`
exercises an 8,030-page fixture per this worktree's root `CLAUDE.md`), building
a second, independent `PageNameIndex` for the capture session in that scenario
means two full trie builds and two live `getPageNameEntries()` collectors
against the same graph concurrently, in the same process — doubling both the
transient CPU/memory cost of the build and the standing memory footprint of the
trie for as long as both instances are alive.

This is not a correctness bug (both instances would converge to the same
matcher state) and requirements.md's "no new abstractions beyond what the task
requires" constraint argues against building a cross-ViewModel cache as part of
this feature — but it's worth naming explicitly as a design question for the
architecture phase: whether `coordinatorFor` should attempt to discover and
reuse an already-running `StelekitViewModel`'s `PageNameIndex` via
`GraphManager` when one exists in-process, versus always building its own. Per
requirements.md's own resolved answer ("Can capture always assume a
`PageNameIndex` already exists? No"), building its own is the documented
starting assumption — this pitfall is scoped to *when the assumption's "no" case
turns out to be false at runtime* (main app happens to be alive too), which
requirements.md doesn't appear to have considered as a distinct sub-case from
the cold-start "no" case.

---

### PF-8: Everything already documented for the reused pipeline still applies verbatim

The following, from `project_plans/import-topic-suggestions/research/pitfalls.md`,
transfer directly to capture with no new analysis needed — cited here so this
document doesn't have to re-derive them, but flagged because the capture
context makes several of them *more* acute, not equally acute:

- **FM-3 (suggestion list overload)** is *less* acute here: AC#2 already scopes
  capture to a compact chip tray with no "Accept All" — the cap/tiering
  machinery FM-3 recommends for Import's 15-item review screen is
  over-engineering for a quick-capture surface handling much shorter text
  (typically a share-sheet snippet, not a 5-10 KB article). Still worth capping
  defensively (a pasted multi-KB article can arrive via share too), but the UX
  weight FM-3 recommends (progressive disclosure, confidence tiers) is
  explicitly out of scope per requirements.md's "no Accept All... single-item
  accept only is acceptable."
- **FM-5 (accepting a suggestion that already exists — TOCTOU race)** is *more*
  acute for AC#9's post-save acceptance than for Import's review screen, because
  the post-save window is unbounded (PF-4) — the odds that `PageNameIndex`
  rebuilds and a suggested page name becomes non-novel between scan-time and
  accept-time rise with how long the user lingers on the "Done" sheet. The same
  pre-accept `pageRepository.getPageByName(name).first()` existence check
  FM-5 recommends for Import applies unchanged here, and is *more* necessary
  given AC#9's extended window.
- **FM-4 (LLM failure modes) / this project's `llm-service` pitfalls §3.1**:
  the opt-in LLM tier (AC#3) must degrade identically to `NoOpTopicEnricher`
  behavior on any failure — no error surfaced for "no provider configured,"
  silent fallback to local-only for every other failure class. `TagSuggestionEngine`'s
  `checkAvailability: (suspend () -> LlmProviderAvailability)?` non-blocking
  probe (cited in requirements.md as the pattern to follow) is the concrete
  precedent; do not block the coordinator's construction or the scan pass on an
  LLM availability check, only the optional enhancement pass.
- **`llm-service` pitfalls §2.1's AICore foreground-only / per-app-quota /
  256-token-output-cap findings** apply directly to AC#3's on-device tier:
  `CaptureActivity` is by definition foreground when the enrichment call would
  fire (the sheet is visible), so the foreground-only constraint isn't a
  blocker here the way it would be for a background-triggered synthesis job —
  but the 256-token output cap and `checkEligible()`/`format()` tri-state
  inconsistency (§0 of that doc) both apply unchanged: capture's LLM
  enrichment pass must treat `DOWNLOADABLE`/`DOWNLOADING` as "not currently
  usable," not "usable," or the first on-device-eligible-but-not-yet-downloaded
  user will see an immediate silent failure on every capture until the ~15-30
  minute background download finishes.

---

## Recommendation — prioritized for the architecture/plan phase

**[P0]** Wrap every new coroutine `CaptureViewModel` launches (coordinator build,
scan, LLM enrichment) in the same `catch (e: CancellationException) { throw e } catch (e: Throwable) { degrade }`
discipline `PageNameIndex.matcher` already uses, or install a
`CoroutineExceptionHandler`-wrapped scope the way `StelekitViewModel.scope` does.
This is the single highest-consequence gap — an unguarded `Throwable` here loses
the user's unsaved capture, not just a suggestion.

**[P0]** Implement AC#8's `coordinatorFor` using the `RequestCoalescer`
(`Mutex` + `CompletableDeferred`) idiom already in this codebase, not a nullable
field. Test explicitly for the "two concurrent callers, one `PageNameIndex`"
invariant.

**[P0]** Own the coordinator and its enrichment state in `CaptureViewModel`,
never in a composable's `remember { }`/`rememberCoroutineScope()` — per the
root `CLAUDE.md` rule this repo already enforces elsewhere.

**[P1]** Give AC#9's second write the same `ClosedSendChannelException` guard
`performSave()`'s first write has, with a degrade-not-retry failure message
distinct from the pre-save one, and write the project's ADR-002 to state
explicitly that the second write may only start after the first write has
fully completed (never interleaved/racing it).

**[P1]** Apply FM-5's pre-accept `getPageByName` existence check to AC#9's
post-save acceptance path specifically — the unbounded post-save window makes
this more likely to matter than in Import's bounded review screen.

**[P2]** Use `collectLatest` (or equivalent explicit cancellation) for the
scan trigger on `captureText`, and add a regression test for the `onNewIntent`
re-entry case (second share intent arrives while first intent's scan is
in-flight, field already non-empty from the first).

**[P3]** Note as an open design question (not a v1 blocker, per the "no new
abstractions beyond what the task requires" constraint): whether `coordinatorFor`
should attempt to reuse an already-running `StelekitViewModel`'s `PageNameIndex`
via `GraphManager` when the main app process is already alive, to avoid the
duplicate-trie-build cost on large graphs.
