# Findings: Pitfalls — Wiring Auto-Link/Suggest into `CaptureActivity`

**Date**: 2026-08-10
**Feature**: Capture Auto-Enrich (share-sheet capture → `ImportService.scan()` + `TopicEnricher`)
**Research method**: Codebase reading (`CaptureActivity.kt`, `CaptureViewModel.kt`, `PageNameIndex.kt`,
`ImportService.kt`, `ImportViewModel.kt`, `ClaudeTopicEnricher.kt`, `LlmProviderRegistry.kt`,
`DatabaseWriteActor.kt`) + prior project-plan research (`stelekit-import`, `import-topic-suggestions`,
`llm-service`)

---

## Summary

The single highest-impact finding is architectural, not hypothetical: **`PageNameIndex` is
currently instantiated exactly once in production, inside `StelekitViewModel`**
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:516`,
`val pageNameIndex = PageNameIndex(pageRepository, scope)`), scoped to that ViewModel's own
`CoroutineScope`. `CaptureActivity` is a separate `ComponentActivity` with its own
`AndroidViewModel` (`CaptureViewModel`) — it has **no reference** to `StelekitViewModel`'s
already-built matcher. There is no `GraphManager`/`RepositorySet`-level cache of the matcher
either (`grep "PageNameIndex(" kmp/src androidApp` finds only the one `StelekitViewModel` call
site plus tests). This confirms the "Stack" research dimension flagged as open in
`requirements.md`: this feature cannot cheaply "reuse" a matcher today — it must build its own,
which is precisely the scenario the "matcher-build-cost in `onCreate`" pitfall describes.

Six distinct risks, in descending order of how directly they threaten the stated success
criteria:

1. **Matcher-build cost in a translucent overlay `onCreate`** — real risk, not just
   hypothetical, because no shared matcher exists to inject.
2. **`viewModelScope` outliving the visible sheet** — `finish()` does not synchronously cancel
   `CaptureViewModel`'s coroutines; there's a real window for in-flight work to race the
   Activity teardown or a second capture.
3. **LLM cost/latency creeping onto every capture** — mitigated by an already-landed opt-in
   gate (`llm-service`'s `LlmProviderRegistry` + `NoOpTopicEnricher`/`ClaudeTopicEnricher`
   split), but only if `CaptureViewModel` is wired the same way `ImportViewModel` is.
4. **False-positive auto-linking on short capture text** — structurally the same risk already
   documented and mitigated for `stelekit-import`, but capture text is *shorter and more
   ambiguous* than a pasted article, which changes the risk profile.
5. **Duplicate stub-page creation across rapid captures** — `ImportViewModel.confirmImport()`
   already avoids relying on the (possibly stale) `PageNameIndex` snapshot for the actual
   create-vs-skip decision; a capture-path implementation must copy that pattern exactly, or
   duplicates are likely.
6. **Process death / config change** — narrow risk given `CaptureActivity`'s UI (no rotation
   handling, `ComponentActivity` default `android:configChanges`), but process death mid-LLM-call
   is a real data-loss vector because `CaptureViewModel` has no `SavedStateHandle` persistence.

---

## 1. Matcher-Build Cost vs. `focusRequester.requestFocus()`

**Code path**: `CaptureActivity.onCreate()` (`androidApp/.../CaptureActivity.kt:72-112`) calls
`setContent { ... }`, and `CaptureScreen`'s `LaunchedEffect(Unit) { focusRequester.requestFocus() }`
(`CaptureActivity.kt:228-230`) fires on first composition. `parseShareIntent()` at line 80 runs
synchronously in `onCreate()` before `setContent`, but it does no DB/matcher work — it's just
`Intent` extras parsing plus a synchronous file copy for images.

**The gap**: `performSave()` (`CaptureViewModel.kt:71-118`) currently does no scan at all. To
satisfy the "Must Have" requirement ("Run captured text through `ImportService.scan()` ... before
`performSave()` writes the block"), `CaptureViewModel` needs an `AhoCorasickMatcher` for the
active graph. Since no cached instance exists outside `StelekitViewModel`, the naive
implementation is to construct a fresh `PageNameIndex(pageRepository, viewModelScope)` in
`CaptureViewModel`'s init block (mirroring `StelekitViewModel.kt:516`), then wait for its first
`matcher` emission.

**Why this can visibly delay first-frame responsiveness**: `PageNameIndex.matcher` is
`StateFlow<AhoCorasickMatcher?>`, `stateIn(scope, SharingStarted.Eagerly, null)` — it starts as
`null` and only becomes non-null after `pageRepository.getPageNameEntries()` emits, a
`debounce(rebuildDebounceMs = 500L)` elapses, and the trie build itself completes on
`Dispatchers.Default` (`PageNameIndex.kt:61-73`). That's a **minimum ~500ms latency floor before
a matcher exists at all**, independent of graph size — this alone exceeds normal touch-to-focus
expectations (typically <100ms) if `focusRequester.requestFocus()` or `viewModel.save()` is made
to *wait* on the matcher.

The requirements doc already anticipates this ("Run captured text through `ImportService.scan()`
... time-box the enrichment pass so it never delays the initial save"), but the risk is that a
straightforward implementation blocks the wrong thing:
- **Safe**: `focusRequester.requestFocus()` and the `Save` button must never await the matcher —
  they don't touch it today, and must stay that way.
- **Unsafe pattern to avoid**: gating `Save`'s `enabled` state, or the scan call inside
  `performSave()`, on a *synchronous* wait for `matcherFlow.first { it != null }` with no
  timeout — on first capture after a cold app start (before `StelekitViewModel`/the main graph
  screen has ever run, so no index has ever been warmed), this could stall the save path by the
  500ms debounce plus however long `PageNameIndex.buildEntries()` + `AhoCorasickMatcher`
  construction takes on the real page count. The doc comment at `PageNameIndex.kt:52-56`
  explicitly notes "8 000+ page graphs produce hundreds of thousands of [trie] nodes" and that
  construction "can throw `OutOfMemoryError`" under memory pressure — the shipped
  `AhoCorasickBenchmark.kt` only benchmarks up to 500 synthetic pages
  (`kmp/src/jvmMain/.../benchmarks/AhoCorasickBenchmark.kt:12-34`), so there is no first-party
  timing data at the 8k-page scale this comment warns about, and no benchmark run inside a
  memory-constrained Android process (the overlay Activity runs in the same process as the main
  app if already warm, but is also the **first** thing to run if the app is cold-started purely
  to service the share sheet — meaning it pays full process-start + `GraphManager` init +
  `PageNameIndex` first-build cost with no warm cache to lean on).
- **Compounding factor — cold start via share sheet**: unlike the in-app Import screen (which
  only opens after the user has already navigated into a loaded graph, i.e., the matcher has
  almost certainly already been built by `StelekitViewModel`), `CaptureActivity` can be the
  *first* Activity launched in a fresh process (share sheet / widget / QS tile from outside the
  app). In that case there is no pre-warmed `StelekitViewModel` at all — `SteleKitApplication`
  owns `graphManager` (confirmed via `CaptureViewModel.kt:57`, `steleApp.graphManager`), but not
  `PageNameIndex`. A capture-path matcher build here is a true cold build against however many
  pages are already persisted, racing the translucent sheet's perceived-instant appearance.

**Recommendation direction** (for the architecture/plan phase, not decided here): treat the
scan as a background-only, best-effort enhancement to a save that has already been *scheduled* —
never block `requestFocus()`, `Save`'s enabled state, or `performSave()`'s actual write on
`matcherFlow` reaching non-null. If `matcherFlow.value` is still `null` when `save()` is called,
save the raw, unlinked text (same as today) and skip the scan for that capture, matching the
Success Criteria's own timeout/fallback language.

---

## 2. `viewModelScope` vs. `finish()` — Race on Accept/Save/Dismiss

**What actually happens today**: `CaptureViewModel` uses plain `viewModelScope.launch { ... }`
in `save()` (`CaptureViewModel.kt:63-69`). `viewModelScope` is backed by
`SupervisorJob() + Dispatchers.Main.immediate`, and is cancelled in `ViewModel.onCleared()`,
which the Android `ViewModelStore` calls when it is cleared — for an Activity-scoped
`by viewModels()` ViewModel, that happens when the hosting Activity's `onDestroy()` fires with
`isFinishing == true && !isChangingConfigurations`. **`finish()` does not synchronously cancel
`viewModelScope`** — it schedules Activity teardown, and `onDestroy()` (and therefore
`onCleared()`) can run a nontrivial window later (after exit animations, window removal, etc.),
during which any coroutine already `launch`ed on `viewModelScope` keeps running.

This is the opposite problem from the `rememberCoroutineScope` anti-pattern this repo's
`CLAUDE.md` warns about (a *composable-scoped* scope leaking into a long-lived object, causing
`ForgottenCoroutineScopeException`) — `CaptureViewModel` already does the "correct" thing
structurally (it's an `AndroidViewModel` that owns `viewModelScope`, not a scope threaded in from
Compose). The risk here is the reverse: **the scope survives longer than the visible UI**, which
is desired for `performSave()` (that's exactly why `writer.startAutoSave(viewModelScope)` at
`CaptureViewModel.kt:116` is intentionally tied to it — Bug 8 mitigation needs the flush to
survive the `LaunchedEffect`-triggered `finish()` that fires the instant `SaveState.Saved` is
observed at `CaptureActivity.kt:218`). But it is a **leak/race risk** for any *new* fire-and-forget
work this feature adds:

- **Chip-accept vs. Save race**: per `ADR-005`/the requirements doc, accepting a suggestion chip
  before Save must fold the new `[[wiki link]]` into the saved block content. If accept triggers
  an async stub-page write (`GraphWriter.savePage`, mirroring `ImportViewModel.confirmImport()`
  at `ImportViewModel.kt:365-410`) on `viewModelScope`, and the user taps **Save** in the same
  frame, `performSave()` reads `_captureText.value` — a plain synchronous snapshot
  (`CaptureViewModel.kt:54`). If the chip-accept coroutine hasn't yet updated `_captureText` (or
  whatever new state field carries "accepted terms") by the time `save()` reads it, the block is
  written *without* the link even though the stub page now exists — an orphaned page with zero
  incoming references, silently violating the "no silent page creation" success criterion's
  spirit (the page isn't silent, but its link is dropped).
- **Accept-then-dismiss**: `onDismiss = { finish() }` (`CaptureActivity.kt:107`) calls `finish()`
  directly with **no save-in-progress guard** beyond `BackHandler`'s `enabled =` check
  (`CaptureActivity.kt:233-237`), which only covers the hardware/gesture back action, not the
  "Dismiss" button or the dim-layer tap-to-dismiss (`CaptureActivity.kt:249`,
  `if (captureText.isBlank()) onDismiss() else viewModel.save()` — note the dim-layer tap *does*
  save if there's text, but the explicit "Dismiss" `TextButton` at line 303-306 does not, and is
  only `enabled = saveState != Saving`, not blocked while a chip-driven stub-page write or LLM
  call is in flight). A user who accepts a chip and immediately taps "Dismiss" can `finish()` the
  Activity while the stub-page write is still in flight on `viewModelScope` — since the scope
  isn't synchronously cancelled, the write likely *completes* (a stub page silently created after
  the sheet has visually closed, with no confirmation UI left to show for it), which is a
  quieter but still real deviation from "no silent writes, ever."
- **LLM enhancement vs. finish()**: same shape — `ClaudeTopicEnricher.enhance()` already has an
  internal 8s `withTimeout` per `ADR-003-claude-api-opt-in-architecture.md`, so a hung call is
  self-bounding, but there's no guarantee the enrichment coroutine is even *cancelled* if the
  user finishes the sheet mid-call; it may just keep running to update a `_state` `StateFlow`
  nobody is collecting anymore (harmless), or — if this feature attaches any write/side-effect to
  the LLM result (e.g., auto-creating stub pages for Claude-only "net-new concepts" without a
  fresh confirmation step) — that write could land after the user believes the interaction is
  over.

**Root cause to fix at design time, not code-review time**: any write triggered from chip
accept/LLM enrichment must (a) be explicitly cancelled or synchronously awaited before `finish()`
is allowed to proceed for that interaction, or (b) be written through the same serialized
`DatabaseWriteActor.execute()` path (`kmp/src/commonMain/.../db/DatabaseWriteActor.kt:726`) that
`performSave()` already uses, so that *ordering* is guaranteed even if *timing relative to
`finish()`* is not — and Save's own text-capture must happen *after* any pending accept has been
applied to state, not from a stale synchronous read.

---

## 3. LLM Latency/Cost/Rate-Limit Risk on Every Capture

**This is largely already solved upstream, contingent on correct wiring.** `llm-service` has
landed (not just planned) in `commonMain`:
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistry.kt` exists with
`suspend fun availableProviders(): List<LlmProvider>` (filters to providers whose
`checkAvailability()` is not `LlmProviderAvailability.Unavailable`) and
`suspend fun availableForFeature(...)`. `ADR-003-claude-api-opt-in-architecture.md` (accepted)
establishes the "zero API calls, zero cost when no key is configured" guarantee at the wiring
site: **`ClaudeTopicEnricher` is never constructed when no key is present — `NoOpTopicEnricher`
is used instead** (a Null Object, not a runtime `if` inside the enricher). This is the pattern to
mirror, not reinvent: `CaptureViewModel` must resolve its `TopicEnricher` the same way
`ImportViewModel`'s call site does (construct `NoOpTopicEnricher` when no provider is configured;
never construct a real network-backed enricher speculatively "just in case").

**Where a naive capture-path implementation could reintroduce cost risk**:
- **Checking availability on every capture instead of once per provider-config change**.
  `LlmProviderRegistry.availableProviders()`/`availableForFeature()` are `suspend fun`s —
  presumably cheap (local key-presence/config check, not a network call), but if a future
  provider's `checkAvailability()` does anything network-bound (e.g., a liveness ping), calling
  it unconditionally in `CaptureViewModel.init` or on every `save()` would add latency and
  possibly cost to captures that never even use the LLM tier. The `ImportViewModel` pattern of
  resolving the enricher once at construction (`topicEnricher: TopicEnricher = NoOpTopicEnricher()`
  constructor param, not re-resolved per scan) is the safer template.
- **The 8s timeout is per-call, not a rate limiter**: `ADR-003`'s `withTimeout(8_000)` plus
  single-retry-on-429 bounds a *single* enrichment call's worst-case latency, but nothing in the
  reviewed code caps *how often* captures can trigger a call. Share-sheet capture is explicitly
  called out in the requirements doc as "the highest-frequency, lowest-friction entry point" —
  if a user shares 10 links in a row (a plausible batch-triage session), a naive
  wire-up fires 10 independent Claude calls with no debounce/cooldown, unlike the in-app Import
  flow where `onRawTextChanged` already debounces 300ms before even the *local* scan
  (`ImportViewModel.kt:186-187`) and the Claude call is triggered from a stable, already-scanned
  text rather than on every capture event. This is a real gap the plan phase should size:
  10 launches × up to 8s worst-case timeout × real API cost per call, with no batch/queue
  awareness, versus capture's core promise of being fast and cheap.
- **On-device tiers (ML Kit/Gemini Nano) are not free of cost either** — they're free of *API*
  cost but not of *CPU/battery* cost inside a translucent overlay the user expects to dismiss in
  under a second. `llm-service`'s research docs (`project_plans/llm-service/research/`) should be
  consulted in the architecture phase for whatever on-device latency numbers exist there; this
  research pass did not find first-party benchmarks for on-device tiers inside this repo.

---

## 4. False-Positive Auto-Linking on Short, Ambiguous Capture Text

**Existing mitigation, and why it's weaker for capture than for import**: `PageNameIndex` already
carries a `DEFAULT_STOPWORDS` set (~35 common English function words,
`PageNameIndex.kt:180-185`) and a `minNameLength = 3` filter, exactly the mitigation
`stelekit-import/research/pitfalls.md` (FM: False-Positive Wiki-Link Matching) recommended and
landed. `ImportService.scan()` auto-applies every matcher hit as a `[[wiki link]]` with **no
confirmation gate** (`ImportService.kt:44-88` — matches are wrapped unconditionally; only the
*topic suggestion* candidates, not existing-page matches, go through accept/dismiss). That's
consistent with in-app Import's existing UX (the "Should Have" in this feature's requirements
doc explicitly preserves that distinction: matched-existing-page links auto-apply, new-page
suggestions require accept).

**Why capture text raises the risk profile even with the same stopword list**: the
`stelekit-import` pitfalls research reasoned about false positives against *full pasted
documents* (KB-scale text, where a spurious 3-letter match is one hit among thousands of words
and is comparatively low-consequence). Share-sheet capture text is frequently **very short** —
a single sentence, a URL + page title (`buildShareText()`, `CaptureActivity.kt:186-201`), or a
short quoted snippet. In a short capture:
- A single false-positive match dominates the whole note rather than being diluted — e.g.
  capturing "just bought a new Run" (someone's brand name, a movie title, a colloquialism) where
  the graph happens to have a page named "Run" (passes `minNameLength = 3`, not a stopword)
  auto-converts to "just bought a new [[Run]]" with no way to catch it before the block is
  already saved, since existing-page matches are not gated behind the accept/dismiss chip tray at
  all — they're applied unconditionally by `ImportService.scan()`.
  the shipped `EnglishInflector`-driven **stem-variant matching** (`PageNameIndex.kt:145-155`,
  "running" matches page "run") widens this further: short capture sentences are exactly where
  common gerunds/plurals of a short existing page name are most likely to appear incidentally.
- There is no "undo the last auto-link" affordance in `CaptureActivity` today — the sheet's only
  actions are Save/Dismiss, and once Saved, `onSaved()` immediately calls `finish()`
  (`CaptureActivity.kt:100-106`), closing the sheet before a user could visually catch and revert
  a bad auto-link the way they might scanning a full Import preview pane.
- In-app Import shows the user the *entire* scanned document with all auto-linked spans visible
  before commit (a review-stage UI, per the doc's own framing); the capture sheet's 3–8 line
  `OutlinedTextField` (`CaptureActivity.kt:287-296`) is not designed as a review surface — if
  auto-linking rewrites the text in place, the user may not even notice the rewrite happened
  before tapping Save reflexively (this is a fast, low-friction path by design).

**Implication for the plan phase**: the existing stopword/min-length filter is necessary but was
sized against import-document risk, not capture-snippet risk. Whether to widen the stopword list,
require a minimum confidence/context signal for capture specifically, or surface auto-applied
links visibly (e.g., inline highlight) before Save is a product decision — but the pitfall is real
and specific to this surface, not a rehash of the import-time finding.

---

## 5. Duplicate Stub-Page Creation Across Rapid Captures

**What `ImportViewModel` actually does — and why it's already fairly safe**:
`confirmImport()` does **not** trust the `PageNameIndex`/`existingNames` snapshot for the actual
create-vs-skip decision. It does two live, per-confirm DB reads:
`pageRepository.getPageByName(normalizedName).first().getOrNull()` for the target page name
(`ImportViewModel.kt:394-398`), and, for every accepted topic suggestion,
`pageRepository.getPageByName(suggestion.term).first().getOrNull()`
(`ImportViewModel.kt:406-408`) immediately before creating each stub — i.e., existence is checked
fresh at accept-time against the database, not against the (necessarily debounced,
`REBUILD_DEBOUNCE_MS = 500L`) in-memory `PageNameIndex` vocabulary. The `existingNames` set
passed into `ImportService.scan()` only suppresses *suggesting* a term that's already a page —
it is explicitly not the create-gate.

**Where the capture-path risk actually lives**: this feature spans *separate* `CaptureActivity`
instances far more than `ImportViewModel` ever does — each share/widget/tile capture that isn't a
`singleTop` re-entry into an already-open sheet is a **fresh `CaptureViewModel`**, which (per
finding #1) would need to build its **own fresh `PageNameIndex`** from scratch since none is
shared. Two captures fired close together (e.g., user shares two related articles back-to-back,
or taps the QS tile again right after dismissing) each:
1. Start a brand-new `PageNameIndex` with an empty `_entries` `StateFlow` until the first
   `getPageNameEntries()` emission + 500ms debounce elapses.
2. Independently run `TopicExtractor`, independently produce the "same" new-topic suggestion
   candidate for overlapping/similar capture text (plausible if the two captures share a topic).
3. If the user accepts the "same" suggestion chip in both, **as long as each accept path does the
   live `getPageByName().first().getOrNull()` check `ImportViewModel.confirmImport()` does**,
   the second accept will see the first's already-written page and skip creation. But if a
   capture-path implementation instead short-circuits on the (per-instance, independently-built,
   never-shared) `PageNameIndex.vocabularyNames()`/`existingNames` snapshot as its create-gate —
   which is the easier-looking but wrong pattern to copy — the second instance's snapshot has no
   way to know about a page the first instance just wrote, since the two `PageNameIndex`
   instances are unrelated objects with independent debounce timers, and duplicate stub pages
   result.
4. Even with a live `getPageByName` check, there is a narrower TOCTOU window if both accepts
   race within the same DB read/write cycle (both read "not found" before either write commits)
   — `DatabaseWriteActor` serializes writes but a read-then-write across two different actor
   calls is not atomic. This is the same class of race `ImportViewModel` itself would have for
   two concurrent `confirmImport()` calls, just far more likely here because concurrent
   `CaptureActivity` instances are an expected usage pattern (rapid successive shares) rather than
   an edge case.

**Recommendation direction**: the plan/architecture phase must specify that stub-page creation
in the capture path is gated by a fresh, per-accept `getPageByName()` DB read — copying
`ImportViewModel.confirmImport()`'s pattern exactly — never by the local `PageNameIndex`
snapshot, precisely because that snapshot is knowably staler and more fragmented here than in
the single-instance Import screen.

---

## 6. Process Death / Activity Recreation vs. In-Flight Enrichment

**Config-change surface is narrow but not zero**: `CaptureActivity` declares no
`android:configChanges` override (not inspected directly here but no `AndroidManifest.xml`
handling was found in the reviewed files), so a rotation or other config change during an
in-flight LLM call would destroy-and-recreate the Activity. Because `CaptureViewModel` is a
`by viewModels()`-scoped `AndroidViewModel`, a *pure config-change* recreation (not process death)
preserves the `ViewModelStore` — `viewModelScope` and any in-flight enrichment coroutine survive
that case fine, and `captureText`/`saveState` StateFlows would be re-collected by the recreated
Compose tree. This is a non-issue for config change alone.

**The real gap is process death**: `CaptureViewModel` has no `SavedStateHandle` usage anywhere in
the reviewed file — `initializeText()`'s own doc comment describes it as "idempotent for
singleTop re-launch," which handles the *intent redelivery* case, not process death. If Android
kills the process while the sheet is open (memory pressure — plausible, since a share-sheet
capture is often launched from within another memory-heavy app like a browser or Chrome custom
tab, exactly the scenario Android is most likely to reclaim background/foreground process memory
under), any unsaved `captureText`, any accepted-but-not-yet-flushed chip state, and any in-flight
LLM enrichment call are lost outright — there is no persisted draft to restore, and the risk is
symmetric with or without this feature (today's raw-text capture already has this gap). What this
feature adds is a **new false-confidence window**: if the design surfaces an "AI suggestions
loading…" or similar in-progress indicator, and the process dies mid-call, the user's last
visible state was "enrichment in progress," not "note lost" — worth flagging as a UX gap for the
architecture phase (e.g., should a partially-typed capture request a lightweight local draft save
before/independent of enrichment, so process death doesn't silently discard the note itself, only
the enrichment layer on top of it).

---

## Recommendations for the Plan Phase (Priority Order)

1. **[P0] Never make matcher availability or LLM completion a precondition for `Save` being
   enabled or for `focusRequester.requestFocus()` firing.** Both must fire immediately,
   independent of `PageNameIndex`/`TopicEnricher` state. `save()` should read whatever matcher
   state is available at call time (possibly `null`) and fall back to the raw-text save path —
   this is already the requirements doc's stated intent; call it out explicitly as a "must not
   regress" test case in validation, not just a design intent.
2. **[P0] Decide matcher sourcing before implementation, not during it.** Building a fresh
   `PageNameIndex` per `CaptureActivity` instance (current-easiest path) has real cold-start and
   duplicate-detection costs documented above (#1, #5). Sharing one `PageNameIndex` per active
   graph (e.g., promoting it to `GraphManager`/`SteleKitApplication`-level, keyed by graph, with
   `StelekitViewModel` and `CaptureViewModel` both reading the shared instance) removes both
   problems at once but is a bigger architectural change — this trade-off belongs in
   `implementation/plan.md`, not left implicit.
3. **[P0] Gate every write (stub-page creation, LLM-suggested page creation) behind a live,
   per-write `pageRepository.getPageByName(...).first().getOrNull()` check**, copying
   `ImportViewModel.confirmImport()` exactly — never gate on the in-memory `PageNameIndex`
   snapshot, which is provably staler and more fragmented across concurrent capture instances
   than it is in the single-instance Import screen.
4. **[P1] Specify explicit cancellation/ordering semantics for chip-accept vs. Save vs.
   Dismiss**, since `viewModelScope` does not synchronously die with `finish()`. At minimum:
   Save must read post-accept state (not a stale snapshot), and Dismiss should either cancel
   pending accept-driven writes or block until they complete — silently completing a write after
   the sheet visually closes violates "no silent writes, ever" even if it's not technically
   unconfirmed by the user.
5. **[P1] Size the "capture in a burst" cost scenario explicitly** (finding #3) — even with
   correct opt-in gating, N rapid captures with an LLM tier configured means up to N independent
   API calls with no shared debounce/cooldown, unlike Import's single-document-at-a-time flow.
6. **[P2] Treat capture-text auto-linking as its own false-positive risk class**, not a rehash of
   the import-time finding — short/ambiguous text plus the already-shipped stem-variant matching
   plus the lack of a review surface before auto-save is a meaningfully different (and likely
   higher-nuisance) risk profile than pasting a full document.
7. **[P2] Note, don't necessarily solve, the process-death gap** (finding #6) as a pre-existing
   condition this feature's "in-progress" UI states could make more visible/confusing, not a new
   defect this feature introduces.

---

## Open Questions Carried to Architecture Phase

- Should `PageNameIndex` become a per-graph, `GraphManager`-owned singleton so both
  `StelekitViewModel` and `CaptureViewModel` share one build instead of each Activity paying its
  own cold-build cost? (Directly affects finding #1 and #5.)
- What should `Save`/`Dismiss` do while a chip-accept-triggered write or LLM call is in flight —
  block, cancel, or race silently (current default, and the wrong answer per finding #2)?
- Does `llm-service`'s `LlmProviderRegistry.availableForFeature()` do any network I/O in
  `checkAvailability()`, or is it purely local config inspection? (Determines whether it's safe
  to call unconditionally per capture — not resolved by this pass; the registry file itself
  wasn't read in full, only its public surface.)
