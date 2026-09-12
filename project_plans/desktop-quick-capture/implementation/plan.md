# Implementation Plan: desktop-quick-capture

**Feature**: In-process global-hotkey quick-capture popup for Desktop (macOS/Linux/Windows), plus a pending-captures file format and poller that future out-of-process OS surfaces (macOS Services menu, Nautilus script, Windows registry handler) will write into.
**Date**: 2026-09-05
**Status**: Ready for implementation
**ADRs**: ADR-001-ipc-mechanism-for-out-of-process-capture, ADR-002-v1-scope-cut-in-process-popup-only

---

## Step 0.5 — Alternatives considered (creative pass)

Three distinct high-level shapes for the overall feature were considered before adopting the one below:

1. **"Everything now" — build all three v1 OS surfaces (macOS Share Extension, Linux hotkey-or-Nautilus, Windows registry handler) in one pass.**
   Strength: matches requirements.md's literal "Suggested scope" list, no follow-up epic needed.
   Weakness: research/pitfalls.md and research/stack.md independently show the three surfaces have wildly different risk profiles (JNativeHook/Wayland fragility, a mandatory native Swift `.appex` + notarization pipeline this repo doesn't have, WiX/registry authoring) — bundling them forces the lowest-risk piece (the hotkey popup) to wait on the highest-risk, highest-uncertainty pieces before anything ships.
2. **"Thin client of #232" — build nothing until the local REST API (#232) exists, then make every surface (including the in-process popup) call it.**
   Strength: single write path for literally every capture trigger, present and future, no bespoke IPC to design.
   Weakness: #232 doesn't exist in this codebase yet (confirmed, research/architecture.md §2) and requirements.md explicitly scopes it out as separate work — waiting on it blocks a feature that has a zero-IPC in-process solution available today, and requirements.md's own "Key architectural fact" says the hotkey popup never needed #232 in the first place.
3. **"In-process popup + pending-captures foundation now; OS-native out-of-process surfaces later" (adopted).**
   Strength: ships the highest JTBD-value, lowest-risk slice immediately (research/ux.md §5 ranks the hotkey popup #1 by value÷cost); the pending-captures directory format is trivial to add now (file writes only) and gives every future out-of-process surface somewhere to land without redesigning anything.
   Weakness: doesn't satisfy requirements.md's literal "all three surfaces" list in this pass — mitigated by recording the cut explicitly as ADR-002 rather than silently shipping less than asked.

Approach 3 is adopted. Rejected alternatives 1 and 2 are recorded in the Pattern Decisions table below and in ADR-002.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `CaptureRequest` | The raw captured text (plus implicit "now" timestamp) submitted for capture, regardless of which trigger produced it. | Represented in code simply as a `String` parameter to `CaptureWriter`/`PendingCaptureFile` — no dedicated class needed since v1 has no source-attribution metadata (contrast Android's `ShareContent`). |
| `CaptureResult` | Sealed outcome of a capture attempt: `Saved(page)`, `Failed(message)`, `NoActiveGraph`, `GraphLocked`. | Presentation-facing; wraps the `Either<DomainError,T>` results of the underlying repository calls plus two UI-only policy states. |
| `CaptureWriter` | commonMain object implementing the exact `ensureTodayJournal → DatabaseWriteActor.saveBlock (with the existing null-`writeActor` `@OptIn(DirectRepositoryWrite::class)` fallback) → GraphWriter.savePage` chain `CaptureViewModel.performSave()` already uses, plus `writeCaptureDirect(graphManager, fileSystem, text, captureId)` — a headless entry point combining `resolveCaptureAvailability` + `writeCapture` for non-UI callers. | Single write-path entry point for every trigger (hotkey popup, pending-capture replay, future socket listener) — `PendingCapturePoller` and `CaptureSocketListener` depend on `CaptureWriter` directly, not on `CaptureController` (see architecture-review). An optional `captureId` makes the resulting `Block.uuid` deterministic so replaying the same capture twice is an idempotent `INSERT OR REPLACE`, not a duplicate (see Story 1.1.1's idempotency AC). Android's `CaptureViewModel` is **not** modified to call it in v1 (see Pattern Decisions). |
| `resolveCaptureAvailability` | commonMain function checking `GraphManager.getActiveRepositorySet() == null` and `GraphInfo.isParanoidMode` before any capture UI is shown. | Mirrors `CaptureTileService.onClick()`'s precedent of routing to the main app instead of a lightweight capture surface when there's no unlocked active graph. |
| `CaptureController` | jvmMain class owning its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` + `CoroutineExceptionHandler`, holding the live `GraphManager` reference, exposing `StateFlow<CapturePopupState>`. | Instantiated once at application startup (not inside `remember { rememberCoroutineScope() }`) per the repo's coroutine-scope-ownership rule. Owns the popup's UI state and hotkey lifecycle only — `PendingCapturePoller` and `CaptureSocketListener` do **not** depend on this class; each holds its own `GraphManager` reference and calls `CaptureWriter.writeCaptureDirect` directly (architecture-review SRP/ISP finding). |
| `CapturePopupState` | Sealed UI state: `Hidden` or `Shown(text, saveState, captureResult)`. | `saveState` mirrors `CaptureViewModel.SaveState` (`Idle`/`Saving`/`Saved`/`Error`). |
| `CapturePopupWindow` | jvmMain `@Composable` rendering the always-on-top capture window, conditionally shown based on `CapturePopupState`. | Desktop analogue of Android's `CaptureScreen`. |
| `GlobalHotkeyListener` | jvmMain interface (Adapter pattern) abstracting the hotkey-registration backend. | Lets the JKeymaster implementation be swapped without touching `CaptureController`, given JKeymaster's own maintenance-risk flag in research/build-vs-buy.md. |
| `JKeymasterHotkeyListener` | Concrete `GlobalHotkeyListener` wrapping `tulskiy/jkeymaster`. | Registration failure (e.g. Wayland) is caught and logged, not thrown — degrades to "hotkey unavailable," not a crash. |
| `PendingCapturesDirectory` | Well-known path resolver: `~/.stelekit/pending-captures/`. | Shared by `PendingCaptureWriter` and `PendingCapturePoller` so both agree on location without duplicating the constant. |
| `PendingCaptureFile` | `@Serializable` data class (`captureId`, `text`, `capturedAt`) representing one not-yet-applied capture written to disk by a cold-start or out-of-process trigger; also the shared JSON envelope sent over `CaptureSocketListener`'s socket. | JSON via the already-on-classpath `kotlinx-serialization-json` — zero new dependency. `captureId` (a UUIDv7, also the filename stem `<captureId>.json`) is threaded through to `CaptureWriter.writeCaptureDirect` so a crash-and-replay or a lost-ack-then-fallback produces the identical `Block.uuid` both times — `INSERT OR REPLACE` (`insertBlock`, `SteleDatabase.sq:335-337`) makes the second write a no-op instead of a duplicate block. |
| `PendingCaptureWriter` | jvmMain utility that atomically writes a `PendingCaptureFile` (temp file + `ATOMIC_MOVE`) into `PendingCapturesDirectory`. | Atomic write prevents the poller from ever reading a half-written file. |
| `PendingCapturePoller` | jvmMain background loop (own `CoroutineScope`), depends only on `CaptureWriter` + its own attached `GraphManager` reference (no `CaptureController` dependency, per architecture-review), that scans `PendingCapturesDirectory` on startup and every 5s, replaying each file's `captureId`+`text` through `CaptureWriter.writeCaptureDirect(...)` and deleting the file on success. | 5s interval matches the existing `GraphFileWatcher.pollIntervalMs` convention already used elsewhere in this codebase. Replaying with the file's own `captureId` makes a re-scan after a crash (file not yet deleted, block already written) an idempotent no-op rather than a duplicate block. |
| `CaptureSocketListener` | jvmMain component binding a Unix-domain-socket (`~/.stelekit/stelekit.sock`) inside the already-running desktop process; depends only on `CaptureWriter` + its own attached `GraphManager` reference (no `CaptureController` dependency, per architecture-review); on message, decodes the same `PendingCaptureFile` JSON envelope the pending-captures path uses and calls `CaptureWriter.writeCaptureDirect(...)` directly (in-process — no pending-capture file needed when an instance is already live). | The "live fast path" from research/architecture.md option (a). Sharing `PendingCaptureFile`'s shape (not a bare string) means `CaptureSocketClient`'s lost-ack fallback reuses the same `captureId`, so a double-delivered capture collapses to one block instead of two. |
| `CaptureSocketClient` | jvmMain CLI-mode helper: generates one `captureId` up front, tries `CaptureSocketListener` first (short timeout) with that `captureId` embedded in the `PendingCaptureFile` payload, falls back to `PendingCaptureWriter.write(text, captureId)` — reusing the same `captureId` — on connection failure (cold start) or a lost/timed-out acknowledgement. | Invoked via `stelekit --capture-text "..."`, the entry point future OS-integration surfaces (Phase 2, deferred) will shell out to. Reusing `captureId` across both delivery attempts is what makes a lost-ack race idempotent instead of a duplicate capture. |
| `parseCaptureArgs` | Pure jvmMain function parsing `--capture-text <value>` out of `main(args)`'s argv. | Extracted as a standalone function so it's unit-testable without invoking `main()`/AWT. |
| `NautilusCaptureScript` / macOS Services-menu handler / Windows registry handler | The three out-of-process OS-integration surfaces from requirements.md's original scope. | **Not built in this plan** — Phase 2, deferred per ADR-002. Named here only because they are the eventual consumers of `PendingCaptureFile`'s format and `stelekit --capture-text`'s CLI contract. |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| `CaptureWriter` | Service Layer — one façade over the existing `JournalService`/`DatabaseWriteActor`/`GraphWriter` chain | PoEAA (Service Layer) | Inlining the 3-call chain separately in the hotkey popup, the poller, and the socket listener | requirements.md explicitly forbids "inventing a second write mechanism"; a single Service Layer keeps exactly one entry point regardless of how many triggers exist, matching ADR-003's ("one capture surface, one write path") precedent from `android-features-integration`. |
| `CaptureResult` | Value Object / sealed hierarchy (type-driven design) | Type-driven design; this repo's `Either<DomainError,T>` convention | Returning the raw `Either<DomainError,T>` from repository calls straight to the capture UI | The capture UI needs two UI-only policy states (`NoActiveGraph`, `GraphLocked`) that aren't `DomainError`s. `CaptureWriter` still uses `Either` internally for the DB/disk calls; `CaptureResult` is the one mapping boundary where those two extra states get added, so `Either`'s vocabulary doesn't leak UI concerns into commonMain repositories. |
| `CaptureController` | Controller owning its own `CoroutineScope` (Observer via `StateFlow`) | GoF Observer + this repo's `StelekitViewModel.scope`/`GraphLoader.parallelScope` convention | `remember { CaptureController(rememberCoroutineScope()) }` | Explicitly forbidden by this repo's coroutine-scope-ownership rule (root `CLAUDE.md`); a show/hide-repeatedly popup built this way throws `ForgottenCoroutineScopeException` on the second show, per research/pitfalls.md §3. |
| `GlobalHotkeyListener` | Adapter (GoF) wrapping the JKeymaster backend behind a small interface | GoF Adapter | Calling JKeymaster's `Provider` API directly from `CaptureController` | research/stack.md and research/build-vs-buy.md flag JKeymaster (and JNativeHook) as unmaintained-risk libraries; an Adapter interface lets the backend be swapped (or dropped to a no-op on Wayland) without touching call sites. |
| Out-of-process IPC (foundation only — see ADR-002 on what's *built* in v1) | Pending-captures directory (cold path, built now) + Unix-domain-socket fast path (built now, in-process only), one shared message shape via `CaptureSocketClient` | PoEAA Gateway (one client picks transport, same payload shape either way) | (a) embedded HTTP server (`ktor-server-*` or `com.sun.net.httpserver.HttpServer`); (b) lock-file + short-lived second-JVM relaunch | (a) requires a new `ktor-server-*` dependency (none exists today, research/architecture.md confirms) and blurs the line with the explicitly out-of-scope local REST API (#232) — the requirements doc calls this out as a line not to cross. (b) needs a JVM cold start just to check a lock file, and still needs (a)/(d) underneath for the actual payload once "an instance is running" is detected (research/architecture.md §2, option (c)) — strictly more moving parts for no benefit over the socket. See ADR-001. |
| `CaptureWriter` location | commonMain (`kmp/src/commonMain/.../capture/`), shared code available to a *future* Android refactor | Type-driven design / DRY, bounded by the additive-only constraint | jvmMain-only implementation | Acceptance criterion 4 (requirements.md) mandates **zero changes to `CaptureActivity`/`CaptureViewModel`/their tests** in v1. Placing `CaptureWriter` in commonMain avoids a second permanent copy of the write-chain logic living only in `jvmMain`, while `CaptureViewModel`'s migration to call it is explicitly deferred (not silently dropped) — see Unresolved Questions. |
| v1 OS-surface scope | In-process hotkey popup + pending-captures format/poller ship in v1; macOS Services-menu, Nautilus script, Windows registry handler deferred to a separate Phase 2 epic | ADR (ADR-002) | Building all three v1 surfaces in this pass, as requirements.md's "Suggested scope" literally lists | research/pitfalls.md, research/ux.md §5, and research/stack.md/build-vs-buy.md converge: JNativeHook/Wayland fragility, a mandatory native Swift `.appex` target + notarization pipeline this repo doesn't have, and Windows WiX/registry authoring are all real, independent-of-each-other costs — none of them block the hotkey popup, and requirements.md's own "whichever is lowest-effort first" instruction (for Linux) generalizes to "hotkey popup first, OS-extension surfaces after." |

---

## Migration Plan

N/A. No new `CREATE TABLE` is added to `SteleDatabase.sq` — `PendingCaptureFile` is a plain JSON file on disk (not a DB row), and every other new type (`CaptureController`, `CaptureResult`, `CaptureWriter`, etc.) is pure in-memory Kotlin. The `MigrationRunner.all` rule from CLAUDE.md does not apply because no schema changes exist for it to enforce.

## Observability Plan

- **Logs**: `CaptureController`, `PendingCapturePoller`, `CaptureSocketListener`, and `JKeymasterHotkeyListener` each use a `Logger(<ClassName>)` instance (matching `StelekitViewModel`/`GraphLoader`'s existing convention), logging hotkey-registration failure, poll-cycle errors, and socket-bind failures at `warn`/`error`. The `CoroutineExceptionHandler` on each owned scope routes any uncaught `Throwable` to the same logger (Task 1.2.1b, 2.2.1a, 3.1.1a).
- **Metrics**: None added in v1. `Main.kt` already wires `OtelSpanRecorder`/`OtelProvider` for the app; a `capture.write` span could be added later around `CaptureWriter.writeCapture` using the same hooks if capture latency becomes a support question, but no immediate need is identified — not built in this plan.
- **Alerts**: N/A — this is a local single-user desktop feature with no server-side component to alert on.

## Risk Control

- **Feature flag**: None built (no flag infra applies to this local desktop feature). The de facto flag is fail-open behavior: hotkey-registration failure (Task 1.3.1c) degrades to "popup unreachable via hotkey" without crashing the app, rather than gating the feature behind a toggle.
- **Rollback procedure**: Plain `git revert` of the desktop-quick-capture commits. No DB migration exists to roll back (see Migration Plan) and no persisted on-disk format from a prior release is touched — `~/.stelekit/pending-captures/` is new, so removing the code leaves at most a few orphaned JSON files, not a data-loss risk.
- **Staged rollout**: No staged-rollout infrastructure exists for this solo-maintainer desktop app. Per research/build-vs-buy.md's own recommendation, treat a manual hotkey smoke test on each target OS (macOS/Linux/Windows) as the release gate before tagging, the same cadence this repo already uses for `TargetFormat.Dmg/Msi/Deb/Rpm` releases.
- **Pre-mortem P1 mitigation (release-notes caveat)**: the v1 hotkey popup only works while the SteleKit process is already running — it does not close the "capture without opening the app first" gap the backlog item's title asks for. Ship an explicit "SteleKit must already be running" caveat in the release notes/README for this feature so the "hotkey does nothing" case is understood as documented behavior, not filed as a bug. Validation.md must include an explicit end-to-end test/manual step for the "SteleKit not running" case confirming today's actual (no-op) behavior, so this limitation is verified, not just asserted.

## Unresolved Questions

- **Resolved**: default hotkey key-combo is `Ctrl+Shift+Space` (matches design/ux.md's mockups; no known collision with macOS Spotlight's `Cmd+Space` or common Linux/Windows bindings) — hardcoded in Task 1.3.1c for v1. This value is user-configurable in a future settings UI (out of v1 scope), not before.
- [ ] Pre-mortem P1: should the cheap macOS custom URL-scheme handler (`research/build-vs-buy.md` §2 — `Desktop.setOpenURIHandler()`, no Xcode target or notarization needed) be folded into this v1 plan as a low-effort cold-start mitigation, given it was flagged as v1-feasible but dropped without an explicit rejection rationale in ADR-002 (also raised independently by architecture-review's Concern on the same gap)? Not resolved in this plan — flagging as the top open item for whoever picks up implementation, rather than silently shipping without it. Until resolved, the release-notes caveat above is the accepted v1 mitigation — owner: Tyler Stapler.
- [ ] Current `tulskiy/jkeymaster` version to pin — research/stack.md found a June-2025 push but no specific tag confirmed — blocks Task 1.3.1a — owner: implementer (check Maven Central at implementation time).
- [ ] Whether/when Android's `CaptureViewModel.performSave()` should be refactored to call the new commonMain `CaptureWriter` instead of its own inline chain (dedup opportunity noted in Pattern Decisions, deliberately deferred to respect AC4's additive-only constraint) — not blocking this plan — owner: Tyler Stapler, future project.
- [ ] Enrichment-pipeline (auto-link + tag-suggest) reuse, called out in requirements.md's "In scope (v1)" list, is **not built in this plan**: it is deferred until the companion Android enrichment issue (the one requirements.md's Open Question 4 refers to as "being wired into Android capture") actually lands. `CaptureWriter.writeCapture` writes the raw captured text with no enrichment step. Owner: Tyler Stapler, future project, tracked alongside the Android enrichment issue rather than as a desktop-specific follow-up.

## Dependency Visualization

```
Phase 1: In-Process Hotkey Capture
┌─────────────────────────────┐
│ Epic 1.1 CaptureWriter       │  (commonMain write-path Service Layer)
│  (Stories 1.1.1)             │
└──────────────┬───────────────┘
               │
     ┌─────────┴──────────┐
     ▼                     ▼
┌───────────────┐   ┌─────────────────────┐
│ Epic 1.2       │   │ Epic 1.3             │
│ CaptureController│  │ GlobalHotkeyListener │
└───────┬────────┘   └──────────┬──────────┘
        │                       │
        └───────────┬───────────┘
                     ▼
            ┌─────────────────┐
            │ Epic 1.4          │
            │ CapturePopupWindow│
            └────────┬──────────┘
                     ▼
            ┌─────────────────┐
            │ Epic 1.5          │
            │ Main.kt wiring    │
            └───────────────────┘

Phase 2: Pending-Captures Foundation (depends on Epic 1.1 — not Epic 1.2/CaptureController; the poller holds its own GraphManager reference, see architecture-review)
┌───────────────────┐     ┌────────────────────┐
│ Epic 2.1            │     │ Epic 2.3             │
│ PendingCaptureFile   │     │ CLI capture mode     │
│ + Writer             │     │ (parseCaptureArgs)   │
└──────────┬───────────┘     └──────────┬──────────┘
           ▼                            │
┌───────────────────┐                   │
│ Epic 2.2            │◄─────────────────┘
│ PendingCapturePoller │  (poller replays files;
└─────────────────────┘   CLI mode writes them)

Phase 3: Live IPC Fast Path (depends on Epic 1.1 + Phase 2 — not Epic 1.2/CaptureController; the socket listener holds its own GraphManager reference too)
┌───────────────────┐     ┌────────────────────┐
│ Epic 3.1            │     │ Epic 3.2             │
│ CaptureSocketListener│───▶│ CaptureSocketClient  │
└─────────────────────┘     └──────────────────────┘
   (in-process, resident)     (CLI-mode: try socket,
                                fall back to Epic 2.1's
                                PendingCaptureWriter)

Phase 2 (separate epic, deferred — see ADR-002, NOT tasked in this plan):
macOS Services-menu entry / Linux Nautilus script / Windows registry handler
  — each will shell out to `stelekit --capture-text` (Epic 2.3 / 3.2), depending
    on this plan's pending-captures format and CLI contract, sequenced strictly after.
```

---

## Phase 1: In-Process Hotkey Capture

### Epic 1.1: Shared Capture Write Path (commonMain)
**Goal**: Extract the exact write chain `CaptureViewModel.performSave()` already uses into one reusable, independently-testable entry point, without modifying Android code.

#### Story 1.1.1: `CaptureWriter` service and capture-availability policy
**As a** desktop capture trigger (hotkey popup, or later the poller/socket listener), **I want** one function that performs the entire "append to today's journal" write chain, **so that** every trigger uses the identical write path Android already validated, with no risk of a second bespoke mechanism.

**Acceptance Criteria**:
- `CaptureWriter.writeCapture(...)` performs `ensureTodayJournal() → saveBlock() → savePage()` and nothing else.
  - *Given* a `RepositorySet` whose `journalService` has no existing page for today's date, *When* `CaptureWriter.writeCapture(repoSet, fileSystem, graphPath, "Buy milk")` is called, *Then* it creates today's journal `Page` via `ensureTodayJournal()`, appends a new `Block(content = "Buy milk")` via `writeActor.saveBlock(...)`, flushes it to disk via `GraphWriter(fileSystem, writeActor).savePage(...)`, and returns `CaptureResult.Saved(page)`.
- The write path is provably identical to `CaptureViewModel.performSave()`, including its null-`writeActor` fallback, with no direct `RestrictedDatabaseQueries` access.
  - *Given* `CaptureWriter.kt`'s source, *When* inspected, *Then* it contains `journalService.ensureTodayJournal()`, `writeActor.saveBlock(block)` when `repoSet.writeActor != null`, the existing `@OptIn(DirectRepositoryWrite::class) repoSet.blockRepository.saveBlock(block)` fallback when `writeActor == null` (ported verbatim from `CaptureViewModel.kt:100-111`'s "Bug 1 mitigation" for the graph-switch race), and `GraphWriter(...).savePage(...)` — no `@OptIn(DirectSqlWrite::class)` anywhere in the file, and no direct `SteleDatabaseQueries` mutator call. `@OptIn(DirectRepositoryWrite::class)` is expected and required for the fallback branch; only `DirectSqlWrite` is banned.
- Replaying the same capture twice (crash-then-resume, or a lost-ack retry) does not create a duplicate block.
  - *Given* an optional `captureId: String?` parameter on `writeCapture(...)`, *When* it is non-null, *Then* the created `Block.uuid` is `BlockUuid(captureId)` (not a freshly generated UUID), so calling `writeCapture` twice with the same `captureId` and text resolves through `insertBlock`'s `INSERT OR REPLACE` (`SteleDatabase.sq:335-337`) to a single row, not two. *When* `captureId` is `null` (the live hotkey-popup path, which has no stable retry identity), a fresh `BlockUuid(UuidGenerator.generateV7())` is used, matching today's behavior.
- No changes to Android's capture path.
  - *Given* the full diff of this implementation, *When* `git diff --stat -- androidApp/` is run, *Then* it reports no changes.
- Capture is refused (not silently written) when no graph is active or the active graph is a locked paranoid-mode vault.
  - *Given* `GraphManager.getActiveRepositorySet() == null`, *When* `resolveCaptureAvailability(graphManager)` is called, *Then* it returns `CaptureResult.NoActiveGraph` without touching any repository.
  - *Given* `GraphManager.getActiveGraphInfo()?.isParanoidMode == true`, *When* `resolveCaptureAvailability(graphManager)` is called, *Then* it returns `CaptureResult.GraphLocked` without touching any repository.
- A single headless entry point exists for non-UI callers, independent of `CaptureController`.
  - *Given* `CaptureWriter.writeCaptureDirect(graphManager, fileSystem, text, captureId)`, *When* called, *Then* it runs `resolveCaptureAvailability(graphManager)` and, if `null` (capture permitted), resolves the active `RepositorySet`/graph path and calls `writeCapture(...)`, returning whichever `CaptureResult` results — with no reference to `CaptureController`, `PendingCapturePoller`, or `CaptureSocketListener` anywhere in `CaptureWriter.kt`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureResult.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureWriter.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureWriterTest.kt`

##### Task 1.1.1a: Create `CaptureResult` sealed hierarchy (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureResult.kt` with `sealed class CaptureResult { data class Saved(val page: Page) : CaptureResult(); data class Failed(val message: String) : CaptureResult(); data object NoActiveGraph : CaptureResult(); data object GraphLocked : CaptureResult() }`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureResult.kt`

##### Task 1.1.1b: Implement `CaptureWriter.writeCapture(...)` (~5 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureWriter.kt` with `suspend fun writeCapture(repoSet: RepositorySet, fileSystem: PlatformFileSystem, graphPath: String, text: String, captureId: String? = null): CaptureResult`, porting `CaptureViewModel.performSave()`'s body in full — fetch existing blocks, build `Block` via `FractionalIndexing.generateKeyBetween` with `uuid = captureId?.let { BlockUuid(it) } ?: BlockUuid(UuidGenerator.generateV7())`, then the same branch `CaptureViewModel.kt:100-111` uses: `writeActor.saveBlock(...)` with `ClosedSendChannelException` handling when `repoSet.writeActor != null`, else the existing `@OptIn(DirectRepositoryWrite::class) repoSet.blockRepository.saveBlock(...)` fallback ("Bug 1 mitigation") when it's `null` — before `GraphWriter(...).savePage(...)`. Return `CaptureResult.Saved`/`Failed` instead of `Result<Unit>`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureWriter.kt`

##### Task 1.1.1c: Implement `resolveCaptureAvailability(graphManager)` (~4 min)
- Append `fun resolveCaptureAvailability(graphManager: GraphManager): CaptureResult?` to `CaptureWriter.kt` — returns `NoActiveGraph` if `getActiveRepositorySet() == null`, `GraphLocked` if `getActiveGraphInfo()?.isParanoidMode == true`, else `null` (capture may proceed), mirroring `CaptureTileService.onClick()`'s precedent (`androidApp/src/main/kotlin/dev/stapler/stelekit/tile/CaptureTileService.kt:38-46`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureWriter.kt`

##### Task 1.1.1d: Unit test — happy-path write (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureWriterTest.kt` using `IN_MEMORY` repositories: assert a call to `writeCapture` appends exactly one `Block` to today's journal `Page` and returns `CaptureResult.Saved`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureWriterTest.kt`

##### Task 1.1.1e: Unit test — policy states (~4 min)
- Append tests to `CaptureWriterTest.kt`: `resolveCaptureAvailability` returns `NoActiveGraph` for a `GraphManager` with no active graph, and `GraphLocked` for one whose `GraphInfo.isParanoidMode == true`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureWriterTest.kt`

##### Task 1.1.1f: Implement `CaptureWriter.writeCaptureDirect(...)` — the shared headless entry point (~5 min)
- Append `suspend fun writeCaptureDirect(graphManager: GraphManager, fileSystem: PlatformFileSystem, text: String, captureId: String? = null): CaptureResult` to `CaptureWriter.kt`: calls `resolveCaptureAvailability(graphManager)`; if non-null, returns it directly; else resolves `graphManager.getActiveRepositorySet()!!` and `graphManager.getActiveGraphInfo()!!.path` and delegates to `writeCapture(repoSet, fileSystem, graphPath, text, captureId)`. This is the one entry point `CaptureController`, `PendingCapturePoller`, and `CaptureSocketListener` each call directly — per architecture-review's SRP/ISP finding, none of them call through `CaptureController` to reach it.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureWriter.kt`

##### Task 1.1.1g: Unit test — idempotent replay via `captureId` (~4 min)
- Append a test to `CaptureWriterTest.kt`: call `writeCapture(repoSet, fileSystem, graphPath, "Call dentist", captureId = "<fixed-uuid>")` twice in a row; assert today's journal page has exactly one `Block` with that uuid and content `"Call dentist"` after both calls (not two blocks), proving `insertBlock`'s `INSERT OR REPLACE` (`SteleDatabase.sq:335-337`) makes the second call a no-op overwrite.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureWriterTest.kt`

---

### Epic 1.2: Desktop Capture Controller
**Goal**: Own the popup's lifecycle and coroutine scope correctly per this repo's coroutine-scope-ownership rule, so a show/hide-repeatedly popup never throws `ForgottenCoroutineScopeException`.

#### Story 1.2.1: `CaptureController` lifecycle and state
**As a** desktop user, **I want** the capture popup's state (text, save-in-progress, errors) to survive being shown and hidden repeatedly without losing in-progress typing or crashing, **so that** pressing the hotkey twice in a row behaves predictably.

**Acceptance Criteria**:
- The controller's `CoroutineScope` is owned by the controller instance, not `rememberCoroutineScope()`.
  - *Given* `CaptureController` constructed once at application startup and its popup shown/hidden five times without recomposition teardown, *When* `save()` is called after the fifth cycle, *Then* no `ForgottenCoroutineScopeException` is thrown, because the scope is `CoroutineScope(SupervisorJob() + Dispatchers.Default)` held as a private field of `CaptureController`, never passed in from `rememberCoroutineScope()`.
- A second hotkey trigger while the popup is already open re-focuses it instead of clearing text or opening a second window.
  - *Given* `CapturePopupState.Shown(text = "partial thought", ...)`, *When* `CaptureController.show()` is called again, *Then* `captureText` remains `"partial thought"` and only one `CapturePopupState.Shown` exists (mirrors `CaptureViewModel.initializeText`'s idempotency guard).
- Dismissing the popup with non-blank text auto-saves instead of discarding it.
  - *Given* `CapturePopupState.Shown(text = "Draft idea", saveState = Idle)`, *When* `CaptureController.dismiss()` is called, *Then* `save()` runs before the state transitions to `Hidden` (mirrors `CaptureActivity`'s `BackHandler` auto-save-on-dismiss).
- Dismissing the popup while it's in the Error state copies the text to the clipboard and closes, instead of retrying the save.
  - *Given* `CapturePopupState.Shown(text = "Draft idea", saveState = Error, ...)`, *When* `CaptureController.dismiss()` is called (via `Escape` or the "Copy text & close" button), *Then* `text` is written to `java.awt.Toolkit.getDefaultToolkit().systemClipboard` and the state transitions directly to `Hidden` — `save()` is **not** called, so a broken save path is never retried involuntarily (matches design/ux.md's Save-error state, which requires a guaranteed non-destructive exit).

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupState.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureController.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureControllerTest.kt`

##### Task 1.2.1a: Define `CapturePopupState` (~3 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupState.kt`: `sealed class CapturePopupState { data object Hidden : CapturePopupState(); data class Shown(val text: String, val saveState: SaveState, val captureResult: CaptureResult? = null) : CapturePopupState() }` plus `enum class SaveState { Idle, Saving, Saved, Error }` (mirrors `CaptureViewModel.SaveState`).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupState.kt`

##### Task 1.2.1b: Create `CaptureController` with owned scope + exception handler (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureController.kt`: `class CaptureController(private val fileSystem: PlatformFileSystem)` holding `@Volatile private var graphManager: GraphManager? = null` (read from the controller's own scope, the hotkey callback thread, and UI recomposition — matches this repo's existing `@Volatile` convention for the same field shape, e.g. `GraphWriter.cryptoLayer`, `DatabaseWriteActor.onWriteSuccess`), `@Volatile private var notificationManager: NotificationManager? = null` (same volatile-attach-after-construction pattern as `graphManager`; see Task 1.2.1c's `attachNotificationManager` and Task 1.5.1b's wiring — used only to surface the Saved confirmation into the existing in-app Notifications history, AC12), `private val logger = Logger("CaptureController")`, `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logger.error("Uncaught in capture scope", e) })`, `private val _state = MutableStateFlow<CapturePopupState>(Hidden)`, `val state: StateFlow<CapturePopupState> = _state.asStateFlow()`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureController.kt`

##### Task 1.2.1c: Implement `attachGraphManager`, `show`, `updateText`, `save`, `dismiss`, `hide` (~5 min)
- Append methods: `attachGraphManager(gm: GraphManager)` stores the reference; `attachNotificationManager(nm: NotificationManager)` stores the reference (wired from `Main.kt` the same way as `attachGraphManager`, via a new `onNotificationManagerReady` callback added to `StelekitApp` mirroring the existing `onGraphManagerReady` pattern, `App.kt:177,267` — see Task 1.5.1b); `show()` captures the currently-focused window (`java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow`, stored as `priorFocusOwner`) before setting `Shown("", Idle, resolveCaptureAvailability(graphManager))` only if currently `Hidden` (idempotency, AC "second trigger"); `updateText(text)` updates `Shown.text` (leaving `saveState`/`captureResult` untouched, so typed text survives a prior failed save); `save()` launches on `scope`, reads the current `graphManager` (`@Volatile`), sets `saveState = Saving`, calls `CaptureWriter.writeCaptureDirect(graphManager, fileSystem, text, captureId = null)` (`captureId` is only used by replay paths — the live popup always writes a fresh block), and on `CaptureResult.Saved` sets `saveState = Saved`, logs `logger.info("Saved to today's journal")` (same `Logger("CaptureController")` instance from Task 1.2.1b, for developer diagnostics), **and** — the user-facing half of AC12 — calls `notificationManager?.show("Saved to today's journal", NotificationType.SUCCESS)` if a `NotificationManager` has been attached (Task 1.2.1b/1.5.1b); this reuses the app's existing, already-shipped in-app notification surface (`dev.stapler.stelekit.ui.NotificationManager`/`NotificationHistory`, already driven the same way from `StelekitViewModel.kt`, e.g. line 2514's rename-confirmation toast) rather than inventing new UI, and its `history` `StateFlow` (rendered by `NotificationHistory` at `ScreenRouter.kt:224`, reachable from Settings) is the actual "persistent trace" a screen-reader or slow-reading user can consult after the ~600ms auto-dismiss closes the popup — `Logger.info` alone is developer-only and doesn't satisfy that intent. The `notificationManager?.` null-safe call is a no-op when unattached (e.g. `CaptureControllerTest.kt`'s unit tests, which construct `CaptureController` without calling `attachNotificationManager`), so the existing `save_should_LogSavedConfirmationMessage_When_TransitioningToSavedState_ForPersistentTrace` test is unaffected. on `CaptureResult.Failed(message)` sets `saveState = Error` **and** `captureResult = CaptureResult.Failed(message)` (so the message is available to render) while leaving `text` unchanged, on `NoActiveGraph`/`GraphLocked` sets `captureResult` accordingly; `dismiss()` branches on the current state: *if* the state is `Shown` with `saveState == SaveState.Error`, it copies `text` to the system clipboard via `java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)` (a direct AWT call rather than Compose's `LocalClipboardManager`, since `CaptureController` is a plain jvmMain class with no composition context to read a `LocalClipboardManager` from) and sets `Hidden` directly — `save()` is not called; *otherwise* (any other `Shown` state) it calls `save()` first if `text.isNotBlank()`, else sets `Hidden` directly. `hide()` (called only by the Saved-state auto-close path, Task 1.4.1d) is a separate, explicitly-specified method — **not** an alias for `dismiss()`: it is a state-only transition straight to `Hidden` with no `save()` call. This is deliberate, not an oversight — by the time `CapturePopupWindow`'s auto-close effect calls `hide()`, `text` has already been written by the `save()` call that produced the current `Saved` state, and it is still non-blank; if `dismiss()` were called instead, its non-Error branch would call `save()` again on that same `text` with `captureId = null` (per this task's `save()` definition above), generating a *fresh* `BlockUuid` and inserting a second, duplicate block rather than overwriting the first. `hide()` performs the identical `priorFocusOwner` focus-restore `dismiss()` performs (see below) so AC10's focus-restore guarantee holds on this exit path too — it differs from `dismiss()` only in skipping the auto-save branch. On every path that transitions to `Hidden` (via either `dismiss()` or `hide()`), call `priorFocusOwner?.toFront()` / request focus on it (design/ux.md AC10's focus-restore requirement) so the previously-focused window can receive keystrokes immediately with no extra click. The complementary Tab-cycle focus trap (AC10's other half) is **not** a free side effect of Compose Desktop's `Window` — unlike a modal dialog, `Window` does not auto-trap focus. It is implemented explicitly in `CapturePopupWindow` (Task 1.4.1b) via `Modifier.onPreviewKeyEvent` on the window's root content: intercepting `Tab`/`Shift+Tab` before they reach the OS, and calling `LocalFocusManager.current.moveFocus(FocusDirection.Next / FocusDirection.Previous)` scoped to the popup's own composition — because the root content's only focusable descendants are the popup's own controls (text field, Retry/Copy&close/Open-SteleKit buttons depending on state), `moveFocus` never has anywhere else to go, so consuming the key event (returning `true`) before it reaches the AWT/OS default Tab-traversal handler is what keeps focus inside the window. This is a named, testable mechanism (`CapturePopupWindowUxTest.kt`'s `capturePopupWindow_should_CycleTabOnlyThroughOwnControls_When_TabIsPressedRepeatedly`, Task 1.4.1h), not an assumed byproduct of the window being undecorated/always-on-top.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureController.kt`

##### Task 1.2.1d: Unit test — idempotent `show()` + auto-save-on-dismiss (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureControllerTest.kt`: assert `show()` called twice keeps existing text; assert `dismiss()` with non-blank text triggers a `writeCapture` call before `state` becomes `Hidden`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureControllerTest.kt`

---

### Epic 1.3: Global Hotkey Registration
**Goal**: Register an OS-level global hotkey that opens the popup from any app, via a swappable Adapter (per research/build-vs-buy.md's JKeymaster maintenance-risk flag).

#### Story 1.3.1: `GlobalHotkeyListener` Adapter over JKeymaster
**As a** desktop user, **I want** a system-wide keyboard shortcut that opens the capture popup regardless of which app currently has focus, **so that** I can capture a thought without switching windows.

**Acceptance Criteria**:
- Hotkey registration failure degrades gracefully instead of crashing the app.
  - *Given* a Wayland session where JKeymaster's registration throws (per research/pitfalls.md §1), *When* `JKeymasterHotkeyListener.register { ... }` is called, *Then* the exception is caught, logged via `Logger("JKeymasterHotkeyListener").warn(...)`, and `main()`'s `application { }` block continues running with the main window fully functional (hotkey simply inert).
- Triggering the hotkey opens the popup.
  - *Given* a registered `JKeymasterHotkeyListener` and no popup currently shown, *When* the bound key combo fires, *Then* `CaptureController.show()` is invoked.

**Files**: `kmp/build.gradle.kts`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/GlobalHotkeyListener.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/JKeymasterHotkeyListener.kt`

##### Task 1.3.1a: Add JKeymaster dependency (~2 min)
- Edit `kmp/build.gradle.kts`'s `jvmMain` dependencies block (~line 156-189): add `implementation("com.github.tulskiy:jkeymaster:1.3")` with a comment citing research/build-vs-buy.md's recommendation of JKeymaster over JNativeHook (fresher, June-2025 push).
- Files: `kmp/build.gradle.kts`

##### Task 1.3.1b: Define `GlobalHotkeyListener` interface (~3 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/GlobalHotkeyListener.kt`: `interface GlobalHotkeyListener { fun register(onTriggered: () -> Unit); fun unregister() }`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/GlobalHotkeyListener.kt`

##### Task 1.3.1c: Implement `JKeymasterHotkeyListener` (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/JKeymasterHotkeyListener.kt` implementing `GlobalHotkeyListener` via `tulskiy.keymaster.global.Provider.getCurrentProvider(false)`, registering the hardcoded default combo `Ctrl+Shift+Space` (resolved in Unresolved Questions; matches design/ux.md's mockups — user-configurable in a future settings UI, not v1), wrapping `register()`'s body in try/catch logging and swallowing any exception.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/JKeymasterHotkeyListener.kt`

##### Task 1.3.1d: Wire listener lifecycle into `CaptureController` (~4 min)
- Append `fun start(hotkeyListener: GlobalHotkeyListener) { hotkeyListener.register { show() } }` and `fun stop(hotkeyListener: GlobalHotkeyListener) { hotkeyListener.unregister() }` to `CaptureController.kt`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureController.kt`

---

### Epic 1.4: Capture Popup UI (Compose Desktop)
**Goal**: Match Android's `CaptureScreen` behavioral contract (instant focus, save-or-discard-on-dismissal, inline error recovery, destination transparency) in a Compose Desktop window.

#### Story 1.4.1: `CapturePopupWindow` — keyboard-first capture UI
**As a** desktop user, **I want** the capture popup to auto-focus its text field and support keyboard-only save/dismiss, **so that** I never need to reach for the mouse to capture a note.

**Acceptance Criteria**:
- The text field autofocuses the instant the popup appears.
  - *Given* `CaptureController.state` transitions from `Hidden` to `Shown("", Idle, null)`, *When* `CapturePopupWindow` recomposes, *Then* a `LaunchedEffect(Unit) { focusRequester.requestFocus() }` runs and the `OutlinedTextField` has keyboard focus with no click required.
- Escape dismisses (with auto-save per Story 1.2.1); Ctrl/Cmd+Enter saves.
  - *Given* the popup is shown with text `"quick note"`, *When* the user presses `Escape`, *Then* `CaptureController.dismiss()` is called (which auto-saves per Story 1.2.1's AC).
  - *Given* the popup is shown with text `"quick note"`, *When* the user presses `Ctrl+Enter` (or `Meta+Enter` on macOS, branched via `System.getProperty("os.name")`), *Then* `CaptureController.save()` is called directly.
- `NoActiveGraph`/`GraphLocked` render a placeholder instead of a text field.
  - *Given* `CapturePopupState.Shown(captureResult = CaptureResult.NoActiveGraph)`, *When* rendered, *Then* the popup shows "No graph configured" + an "Open SteleKit" button instead of `OutlinedTextField` (mirrors Android's `NoGraphPlaceholderContent`).
  - *Given* `CapturePopupState.Shown(captureResult = CaptureResult.GraphLocked)`, *When* rendered, *Then* the popup shows "Vault is locked — open SteleKit to unlock" + an "Open SteleKit" button (mirrors `CaptureTileService`'s locked-vault precedent).
- A failed save preserves the typed text and offers retry instead of discarding input.
  - *Given* `saveState == SaveState.Error` and `captureResult == CaptureResult.Failed("Save failed: disk full")`, *When* `CapturePopupWindow` renders, *Then* the `OutlinedTextField` remains visible with its text unchanged (not cleared), a `Text` row below it shows the failure message in error styling, and a "Retry" `Button` (plus the existing `Ctrl+Enter` binding) calls `controller.save()` again without requiring the user to retype anything.
- Successful save names the destination before the popup closes.
  - *Given* `saveState` transitions to `Saved`, *When* rendered, *Then* the popup briefly shows "Saved to today's journal" (matching `VoiceCaptureActivity.DoneContent`'s destination-transparency pattern) before hiding.

- Losing OS focus (e.g. alt-tab away without using Escape or Ctrl+Enter) auto-saves non-blank text and hides the popup, identical to `Escape`.
  - *Given* the popup is shown with text `"quick note"`, *When* the window's `WindowFocusListener.windowLostFocus()` fires (the user alt-tabbed to another app), *Then* `controller.dismiss()` is called — the same auto-save-then-hide path `Escape` uses (Story 1.2.1's AC) — rather than leaving an orphaned always-on-top window with a stale draft on screen (design/ux.md's Edge cases section flagged this as an unaddressed gap; always-on-top alone does not prevent OS focus from moving away, so this listener is the actual fix).

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowScreenshotTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowUxTest.kt`

##### Task 1.4.1a: Create `CapturePopupWindow` shell (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`: `@Composable fun CapturePopupWindow(controller: CaptureController)` collecting `controller.state`, rendering an undecorated always-on-top `androidx.compose.ui.window.Window` only when `state != Hidden`, containing an `OutlinedTextField` bound to `controller::updateText` with `FocusRequester` autofocus via `LaunchedEffect(Unit)`. Apply `Modifier.semantics { contentDescription = "SteleKit Quick Capture"; role = Role.Dialog }` to the window's root content composable so assistive technology announces it as a dialog with an accessible name immediately on open (design/ux.md AC9), rather than requiring the user to explore the window first. Set the `OutlinedTextField`'s placeholder to `"Type a thought… (Enter for a new line, Ctrl+Enter to save)"` and render a persistent keyboard-hint `Text` row below the field reading exactly `"Esc save & close · Ctrl+Enter save now"` (design/ux.md's Empty/Typing mockups) — this is the mandatory hint distinguishing plain `Enter` (newline) from `Ctrl+Enter` (save) that design/ux.md requires explicitly in the field's placeholder or a first-open tooltip, since a visible "save" hint alongside standard Enter-inserts-newline behavior is a common source of "why didn't that save?" confusion.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`

##### Task 1.4.1b: Wire Escape/Ctrl+Enter/Tab key handling (~6 min)
- Append `onPreviewKeyEvent` handling to the `Window`'s content: `Escape` → `controller.dismiss()`; `Ctrl+Enter`/`Meta+Enter` (OS-branched) → `controller.save()`; `Tab`/`Shift+Tab` → `LocalFocusManager.current.moveFocus(FocusDirection.Next)` / `.moveFocus(FocusDirection.Previous)`, returning `true` (consuming the event) in all three cases so the OS/AWT default Tab-traversal handler never receives it. This last branch is the actual Tab-trap mechanism required by design/ux.md AC10 and named explicitly in Task 1.2.1c — Compose Desktop's `Window` does not auto-trap focus the way a modal dialog would, so without this the focus trap would not exist. Because the window's root content has no focusable descendants besides the popup's own controls (text field, Retry/Copy&close/Open-SteleKit buttons, per state), `moveFocus` has nowhere to go but back to the popup's own nodes.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`

##### Task 1.4.1c: Render `NoActiveGraph`/`GraphLocked` placeholders (~4 min)
- Append a `when (state.captureResult)` branch rendering the two placeholder states instead of the text field, each with an "Open SteleKit" `Button`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`

##### Task 1.4.1d: Render save-confirmation + auto-hide (~3 min)
- Append `LaunchedEffect(saveState) { if (saveState == Saved) { delay(600); controller.hide() } }` plus a "Saved to today's journal" `Text`. Calls `hide()`, not `dismiss()` — `hide()` is the state-only-transition-plus-focus-restore method Task 1.2.1c specifies precisely for this call site, to avoid a second `save()` (and a duplicate block) on already-saved, still-non-blank text.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`

##### Task 1.4.1e: Render `Error` state with typed text preserved + retry (~4 min)
- Append a `saveState == SaveState.Error` branch: keep rendering the `OutlinedTextField` (bound to `controller::updateText`, unchanged from the normal editing branch — text is never cleared on failure), add a `Text(captureResult.let { (it as? CaptureResult.Failed)?.message } ?: "Save failed", color = MaterialTheme.colors.error)` row beneath it, a "Retry" `Button(onClick = controller::save)`, and a "Copy text & close" `Button(onClick = controller::dismiss)` — mirrors design/ux.md Surface 1's two-explicit-exits Error-state design. `dismiss()`'s Error-state branch (Story 1.2.1/Task 1.2.1c) performs the actual clipboard write and hide, so this button and the `Escape` key (Task 1.4.1b) both resolve to the identical code path. `Ctrl+Enter`'s existing binding (Task 1.4.1b) already calls `controller.save()` too, so retry works from the keyboard with no new key handling. Use `MaterialTheme.colors` from this repo's existing `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/` (`Theme.kt`, `Color.kt`, `DynamicColorScheme.kt`) for every color in this state — including the dimmed/disabled Saving-field styling (Task 1.4.1a) and the `⚠`/`✓`/`🔒` status glyphs — rather than hand-picking new colors; the theme's existing color scheme already targets Material's WCAG AA contrast guarantees for `colors.error`/`colors.onSurface` in both light and dark variants (design/ux.md AC11).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`

##### Task 1.4.1f: Screenshot test for popup states (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowScreenshotTest.kt` (Roborazzi, Gradle-only per `TESTING_README.md` convention) covering `Shown` (blank), `Shown` (typed text), `NoActiveGraph`, `GraphLocked`, `Saved`, and `Error` (typed text retained + message shown) states.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowScreenshotTest.kt`

##### Task 1.4.1g: Auto-save on OS focus loss (alt-tab away) (~4 min)
- Append a `java.awt.event.WindowFocusListener` to the underlying AWT peer of `CapturePopupWindow`'s `androidx.compose.ui.window.Window` (added/removed alongside the window's show/hide lifecycle): on `windowLostFocus()`, call `controller.dismiss()` — the identical path `Escape` already uses (Task 1.4.1b), which auto-saves non-blank text and hides, or hides directly if blank. Always-on-top (Task 1.4.1a) keeps the popup visually on top but does not prevent OS keyboard focus from moving to another app on alt-tab, so this listener — not always-on-top — is what closes design/ux.md's previously-flagged focus-loss gap.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CapturePopupWindow.kt`

##### Task 1.4.1h: Create `CapturePopupWindowTest.kt` and `CapturePopupWindowUxTest.kt` (~8 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowTest.kt` (Compose UI component tests via `createComposeRule()`, fake `CaptureController`) with the test cases `implementation/validation.md`'s Requirement → Test Mapping table cites against this file:
  - `capturePopupWindow_should_AutofocusTextField_When_StateTransitionsToShown`
  - `capturePopupWindow_should_PreserveTypedTextAndShowRetryButton_When_SaveStateIsError`
  - `capturePopupWindow_should_CallControllerDismiss_When_EscapePressed`
  - `capturePopupWindow_should_CallControllerSave_When_CtrlEnterPressed`
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowUxTest.kt` with the test cases `implementation/validation.md`'s UX Acceptance Tests table cites against this file:
  - `captureFlow_should_CompleteInTwoKeystrokesWithZeroMouseClicks_When_HotkeyTypeCtrlEnterSequenceRuns`
  - `capturePopupWindow_should_GainFocusWithin150Ms_When_HotkeyFires`
  - `capturePopupWindow_should_ShowSpecificErrorMessageWithRetryAndCopyClose_When_SaveFails`
  - `capturePopupWindow_should_ExposeAtLeastOneOperableExit_When_EachNonTransientStateIsRendered`
  - `capturePopupWindow_should_PreserveOrExternalizeText_When_SaveFailsFocusIsLostOrCopyCloseIsUsed`
  - `capturePopupWindow_should_RenderNoEditableTextField_When_StateIsNoActiveGraphOrGraphLocked`
  - `capturePopupWindow_should_CycleTabOnlyThroughOwnControls_When_TabIsPressedRepeatedly`
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CapturePopupWindowUxTest.kt`

---

#### Story 1.4.2: First-run hotkey notice
**As a** desktop user who has never used this feature, **I want** to be told what the capture
hotkey is the first time it's available, **so that** I can discover and use it without reading
external documentation (design/ux.md Surface 2; Nielsen #1, visibility of system status).

**Acceptance Criteria** (mirrors design/ux.md Surface 2's acceptance criteria):
- Shown exactly once per install, the first time the main SteleKit window opens after this
  feature ships.
  - *Given* `PlatformSettings.getBoolean("capture.firstRunNoticeShown", false) == false`, *When*
    the main window's first composition after startup completes, *Then* the first-run notice
    renders; *when* "Got it" is clicked/`Enter`-activated, `PlatformSettings.putBoolean("capture.firstRunNoticeShown", true)` is called and the notice does not render on the next launch.
- The hotkey combo shown is sourced from the same constant `GlobalHotkeyListener`'s
  registration uses, never a separately hand-typed string, so the two cannot drift.
- Reachable again on demand via a "Keyboard Shortcuts" row in Settings (satisfies Nielsen #10 —
  revisitable help), independent of the persisted first-run flag.
- Dismissible via `Enter`/click on "Got it" or `Escape`; no auto-dismiss timer (non-urgent,
  low-frequency per design/ux.md).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/FirstRunHotkeyNotice.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/FirstRunHotkeyNoticeTest.kt`

##### Task 1.4.2a: Create `FirstRunHotkeyNotice` composable (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/FirstRunHotkeyNotice.kt`: `@Composable fun FirstRunHotkeyNotice(hotkeyCombo: String, onDismiss: () -> Unit)` rendering design/ux.md's Surface 2 layout (an `ℹ` info card: "New: Quick Capture — Press $hotkeyCombo anywhere to capture a note into today's journal." + a "Got it" `Button(onClick = onDismiss)`), dismissible via `Escape` as well.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/FirstRunHotkeyNotice.kt`

##### Task 1.4.2b: Wire persisted show-once flag + hotkey combo into `App.kt` (~5 min)
- Edit `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`: read `PlatformSettings().getBoolean("capture.firstRunNoticeShown", false)` on first composition; if `false`, render `FirstRunHotkeyNotice(hotkeyCombo = GlobalHotkeyListener.DEFAULT_COMBO_LABEL, onDismiss = { settings.putBoolean("capture.firstRunNoticeShown", true); ... })` above/over the normal app content. `GlobalHotkeyListener.DEFAULT_COMBO_LABEL` (a small addition to Task 1.3.1b's interface file, e.g. `const val DEFAULT_COMBO_LABEL = "Ctrl+Shift+Space"`) is the single source of truth both this notice and `JKeymasterHotkeyListener`'s registration (Task 1.3.1c) read from, so the two can't drift.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/GlobalHotkeyListener.kt`

##### Task 1.4.2c: Add revisitable "Keyboard Shortcuts" Settings row (~4 min)
- Add a read-only row to the existing Settings screen displaying `GlobalHotkeyListener.DEFAULT_COMBO_LABEL`, independent of the persisted first-run flag (satisfies Nielsen #10 / design/ux.md's revisitability AC).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 1.4.2d: Compose UI test — show-once + revisitable (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/FirstRunHotkeyNoticeTest.kt` with test `firstRunHotkeyNotice_should_ShowExactlyOnceThenBeRevisitableFromSettings_When_InstallFlagPersists`: (1) launch with no persisted flag → notice shown with the combo pulled from `GlobalHotkeyListener.DEFAULT_COMBO_LABEL`; (2) dismiss via "Got it" → flag persisted via `PlatformSettings`; (3) relaunch (fresh composition, same `PlatformSettings` instance) → notice not shown; (4) navigate to the Settings "Keyboard Shortcuts" row → same combo displayed on demand.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/FirstRunHotkeyNoticeTest.kt`

---

#### Story 1.4.3: Hotkey-conflict notice
**As a** desktop user whose hotkey registration silently failed (already in use, or an
unsupported desktop session), **I want** to be told why instead of discovering it only by
pressing the hotkey and getting nothing, **so that** I don't file "hotkey does nothing" as a
mystery bug (design/ux.md Surface 3; Nielsen #9).

**Acceptance Criteria** (mirrors design/ux.md Surface 3's acceptance criteria):
- Shown at most once per app session — registration succeeds or fails exactly once at
  startup (Task 1.3.1c), so there is no repeat-failure case to debounce.
  - *Given* `JKeymasterHotkeyListener.register { ... }` catches an exception (Task 1.3.1c),
    *When* the failure is caught, *Then* it is classified (already-in-use vs.
    unsupported-session, best-effort) and surfaced to `CaptureController`'s state, which the
    main window observes to render the notice exactly once.
- States *why* in plain language when the cause is known, not a generic "error occurred".
- Does not block app startup or steal focus from the main window on launch.
- The rest of the app remains fully functional — this notification is informational only.
- Supersedes Story 1.4.2's first-run notice when both would otherwise fire on the same launch (new install + registration failure happening together).
  - *Given* `PlatformSettings.getBoolean("capture.firstRunNoticeShown", false) == false` (first-run notice due) *and* `JKeymasterHotkeyListener` registration fails in that same launch, *When* `App.kt` (Task 1.4.3c) composes the two notices, *Then* only `HotkeyConflictNotice` renders for this launch — `FirstRunHotkeyNotice` is suppressed and its persisted flag is left `false` (not marked shown), so it still appears normally on a later launch where registration succeeds. Rationale: teaching the user a hotkey combo that doesn't currently work is worse than deferring that teaching moment.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/HotkeyConflictNotice.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/JKeymasterHotkeyListener.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/HotkeyConflictNoticeTest.kt`

##### Task 1.4.3a: Classify registration failure + surface it (~4 min)
- Edit `JKeymasterHotkeyListener` (Task 1.3.1c): on registration exception, best-effort classify the cause (e.g. `Provider`'s exception type/message distinguishing "already bound" vs. an unsupported session such as Wayland without a compositor global-shortcut portal) into a small `enum class HotkeyRegistrationFailure { AlreadyInUse, UnsupportedSession, Unknown }`, and expose it via a callback/`StateFlow` the caller (`CaptureController` or `Main.kt`) can observe, in addition to the existing catch-and-log behavior.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/JKeymasterHotkeyListener.kt`

##### Task 1.4.3b: Create `HotkeyConflictNotice` composable (~4 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/HotkeyConflictNotice.kt`: `@Composable fun HotkeyConflictNotice(failure: HotkeyRegistrationFailure, onDismiss: () -> Unit)` rendering design/ux.md's Surface 3 layout (`⚠ Quick Capture hotkey unavailable` + a cause-specific message string per `failure` — "already in use" vs. "this desktop session doesn't support global hotkeys" vs. a generic fallback for `Unknown` — + a "Dismiss" `Button`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/HotkeyConflictNotice.kt`

##### Task 1.4.3c: Wire into `App.kt` — shown once per session, non-blocking, supersedes first-run notice (~5 min)
- Edit `App.kt`: observe the failure state from Task 1.4.3a; if non-null, render `HotkeyConflictNotice` as a dismissible overlay (not a modal blocking startup, no focus steal) exactly once per process lifetime (an in-memory flag is sufficient — no `PlatformSettings` persistence needed, since a fresh process re-attempts registration and should re-report if it fails again). Precedence when both notices are due on the same launch (Story 1.4.3's supersession AC): guard Task 1.4.2b's `FirstRunHotkeyNotice` render condition with `&& hotkeyRegistrationFailure == null`, so a non-null failure state suppresses it for this launch without touching `capture.firstRunNoticeShown` — the flag stays `false` and the first-run notice is shown normally next time registration succeeds.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 1.4.3d: Compose UI test — cause-specific messaging (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/HotkeyConflictNoticeTest.kt` with test `hotkeyConflictNotice_should_ShowCauseSpecificMessage_When_RegistrationFailsInUseVsUnsupportedSession`: feed `HotkeyRegistrationFailure.AlreadyInUse` and `.UnsupportedSession` into the composable and assert the two distinct message strings render; assert the notice doesn't block main-window startup or steal focus.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/HotkeyConflictNoticeTest.kt`

---

### Epic 1.5: Application Wiring
**Goal**: Instantiate `CaptureController` and the hotkey listener once at process startup and thread the live `GraphManager` into it via `StelekitApp`'s existing `onGraphManagerReady` hook.

#### Story 1.5.1: Wire capture into `Main.kt`
**As a** desktop user, **I want** the capture feature active from the moment SteleKit starts, **so that** the hotkey works as soon as the app is running, without needing the main window focused.

**Acceptance Criteria**:
- The full end-to-end flow works: hotkey → popup → save → today's journal.
  - *Given* SteleKit is running with an active (unlocked) graph, *When* the user presses the hotkey, types `"Buy milk"`, and presses `Ctrl+Enter`, *Then* today's journal page gains a new block with content `"Buy milk"`, matching `CaptureActivity`'s save target (AC1 from requirements.md).
- The hotkey is unregistered cleanly on app shutdown.
  - *Given* the app is closing via `onCloseRequest`, *When* `exitApplication()` runs, *Then* `captureController.stop(hotkeyListener)` has already been called.
- Closing the main window's effect on capture is documented, not silent.
  - *Given* the user closes SteleKit's main window, *When* `onCloseRequest` runs `exitApplication()`, *Then* the whole process (hotkey listener, socket listener, poller) exits with it — this is the explicit, ADR-002-documented v1 tradeoff (no `Tray`-based background residency in v1), and Task 1.5.1d's code comment states it inline rather than leaving it to be rediscovered as a bug.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureController.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 1.5.1a: Instantiate `CaptureController` + hotkey listener in `Main.kt` (~5 min)
- Edit `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` inside `application { }` (near line 97's `fileSystem` construction): `val captureController = remember { CaptureController(fileSystem) }`, `val hotkeyListener = remember { JKeymasterHotkeyListener() }`, `LaunchedEffect(Unit) { captureController.start(hotkeyListener) }`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

##### Task 1.5.1b: Pass `onGraphManagerReady` (and a new `onNotificationManagerReady`) to `StelekitApp` (~4 min)
- Edit the existing `StelekitApp(...)` call (Main.kt line ~142) to add `onGraphManagerReady = { gm -> captureController.attachGraphManager(gm) }`. Also add a new optional `onNotificationManagerReady: ((NotificationManager) -> Unit)? = null` parameter to `StelekitApp` (`App.kt`, alongside `onGraphManagerReady` at line 177), invoked via `LaunchedEffect(notificationManager) { onNotificationManagerReady?.invoke(notificationManager) }` right after `val notificationManager = remember { NotificationManager() }` (`App.kt:385`) — same shape as the existing `onGraphManagerReady`/`graphManager` pattern one hundred lines above it. Wire `onNotificationManagerReady = { nm -> captureController.attachNotificationManager(nm) }` into the same `StelekitApp(...)` call, so `CaptureController`'s Saved-confirmation notification (Task 1.2.1c) reaches the same `NotificationManager` instance the main window's `NotificationOverlay`/`NotificationHistory` already render.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 1.5.1c: Render `CapturePopupWindow` alongside the main `Window` (~3 min)
- Add `CapturePopupWindow(captureController)` as a sibling composable inside the same `application { }` block as the main `Window { }` (line ~111-151).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

##### Task 1.5.1d: Unregister hotkey on shutdown (~2 min)
- Edit `onCloseRequest` (Main.kt line ~112-116) to call `captureController.stop(hotkeyListener)` before `exitApplication()`. Add a comment above `exitApplication()` noting that closing the window terminates the whole JVM — including the hotkey and socket listeners — until the app is relaunched; this is a documented, accepted v1 limitation, not a bug (see ADR-002 Consequences).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

---

## Phase 2: Pending-Captures Format + Cold-Start Foundation

### Epic 2.1: `PendingCaptureFile` format + atomic writer
**Goal**: Define the on-disk format future out-of-process surfaces (Phase 2, deferred) will write into, and build the writer + directory-path convention now.

#### Story 2.1.1: Atomic pending-capture file writes
**As a** future out-of-process capture trigger (or today's CLI mode), **I want** to durably record a capture even if SteleKit isn't running, **so that** the note is never lost regardless of process lifecycle.

**Acceptance Criteria**:
- A write never leaves a half-written file visible to the poller.
  - *Given* `PendingCaptureWriter.write("Call dentist")` is invoked, *When* the write completes, *Then* a file matching `~/.stelekit/pending-captures/<captureId>.json` exists containing `{"captureId":"<captureId>","text":"Call dentist","capturedAt":"<ISO-8601>"}`, and at no point during the write was a `.json`-suffixed file visible with partial content (achieved via write-to-`.tmp` + `Files.move(..., ATOMIC_MOVE)`).
- The `captureId` is caller-suppliable, not always freshly generated, so a socket-fallback write can reuse the id it already attempted over the socket.
  - *Given* `PendingCaptureWriter.write("Call dentist", captureId = "0193abc...")` is invoked, *When* the write completes, *Then* the file is named `0193abc....json` and its `captureId` field is exactly `"0193abc..."` — not a newly generated UUID — so a later replay produces the same `Block.uuid` as any earlier, partially-acknowledged attempt at delivering the same capture.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturesDirectory.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCaptureFile.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCaptureWriter.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/PendingCaptureWriterTest.kt`

##### Task 2.1.1a: Define `PendingCapturesDirectory` path resolver (~3 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturesDirectory.kt`: `object PendingCapturesDirectory { fun path(): String = "${System.getProperty("user.home")}/.stelekit/pending-captures" }` (matches this repo's existing `~/.stelekit/` convention, e.g. `FileLogSink`'s log location).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturesDirectory.kt`

##### Task 2.1.1b: Define `PendingCaptureFile` serialization format (~3 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCaptureFile.kt`: `@Serializable data class PendingCaptureFile(val captureId: String, val text: String, val capturedAt: String)` using the already-on-classpath `kotlinx-serialization-json:1.10.0`. `captureId` is the same value used both as the filename stem and (per Epic 3.2) the socket payload's identity — the one field that makes replay/retry idempotent end-to-end.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCaptureFile.kt`

##### Task 2.1.1c: Implement `PendingCaptureWriter.write(text, captureId)` (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCaptureWriter.kt`: `fun write(text: String, captureId: String = UuidGenerator.generateV7())` — encodes `PendingCaptureFile(captureId, text, Clock.System.now().toString())` via `Json.encodeToString`, writes to `<dir>/<captureId>.json.tmp`, then `Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)`. Creates `PendingCapturesDirectory` if absent. Defaulting `captureId` to a fresh UUID keeps today's callers (Task 2.3.1b) unchanged; `CaptureSocketClient` (Task 3.2.1b) is the caller that supplies its own.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCaptureWriter.kt`

##### Task 2.1.1d: Unit test round-trip (~4 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/PendingCaptureWriterTest.kt`: write to a temp dir override, assert the resulting file decodes back to the same `text` and `captureId` (both the default-generated case and an explicitly-passed `captureId`) and no `.tmp` file remains.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/PendingCaptureWriterTest.kt`

---

### Epic 2.2: `PendingCapturePoller`
**Goal**: Make cold-start a non-event — SteleKit's normal startup replays any pending captures through the exact same `CaptureWriter` chain the live popup uses.

#### Story 2.2.1: Startup scan + periodic drain
**As a** desktop user, **I want** any capture written while SteleKit wasn't running to appear in today's journal the next time I open the app, **so that** I never lose a captured thought to a cold-start race.

**Acceptance Criteria**:
- A pending capture is replayed and removed on success.
  - *Given* a `PendingCaptureFile` at `~/.stelekit/pending-captures/01930000-....json` containing `{"captureId":"01930000-...","text":"Call dentist","capturedAt":"2026-09-05T10:00:00Z"}`, *When* `PendingCapturePoller.scanOnce()` runs with a `GraphManager` already attached via `attachGraphManager(gm)`, *Then* `CaptureWriter.writeCaptureDirect(gm, fileSystem, "Call dentist", captureId = "01930000-...")` appends a new block with that uuid and content to today's journal and the file is deleted.
- A failing replay (e.g. no active graph yet) is retried, not lost.
  - *Given* the same file but no `GraphManager` has been attached yet (or `resolveCaptureAvailability` currently returns `NoActiveGraph`), *When* `scanOnce()` runs, *Then* the file is left in place (not deleted) and a warning is logged, so the next scan (5s later) retries it.
- A capture that was fully applied before a crash, but whose file wasn't yet deleted, is not duplicated on replay.
  - *Given* a `PendingCaptureFile` whose `captureId` already matches a `Block.uuid` present in today's journal (the write succeeded and was flushed before the process died, only the file deletion never ran), *When* `scanOnce()` replays it, *Then* `writeCaptureDirect`'s deterministic-uuid write resolves through `insertBlock`'s `INSERT OR REPLACE` (`SteleDatabase.sq:335-337`) to the identical single row, not a second block, and the file is deleted as normal.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturePoller.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/PendingCapturePollerTest.kt`

##### Task 2.2.1a: Create `PendingCapturePoller` with owned scope + `GraphManager` reference (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturePoller.kt`: `class PendingCapturePoller(private val fileSystem: PlatformFileSystem)` holding `@Volatile private var graphManager: GraphManager? = null` and `fun attachGraphManager(gm: GraphManager) { graphManager = gm }`, plus `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logger.error("Poller failure", e) })`. Depends only on `CaptureWriter` + its own `GraphManager` reference — no `CaptureController` dependency (architecture-review SRP/ISP finding).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturePoller.kt`

##### Task 2.2.1b: Implement `scanOnce()` (~5 min)
- Append `suspend fun scanOnce()`: if `graphManager == null`, returns immediately (nothing to replay against yet); else lists `*.json` files in `PendingCapturesDirectory.path()` sorted by filename (UUIDv7 prefix ⇒ chronological), decodes each via `Json.decodeFromString<PendingCaptureFile>`, calls `CaptureWriter.writeCaptureDirect(graphManager!!, fileSystem, file.text, captureId = file.captureId)`; on `CaptureResult.Saved` deletes the file, otherwise leaves it and logs a warning. Passing the file's own `captureId` through is what makes a re-scan after a crash idempotent (see Story 2.2.1's third AC). Each file's decode+write is wrapped in its own try/catch — a single malformed/corrupted file (bad JSON) is renamed to `<name>.failed` and skipped, logged at `warn`, rather than throwing out of `scanOnce()` and silently killing the poller loop for every subsequent file.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturePoller.kt`

##### Task 2.2.1c: Implement `start()` — startup scan + 5s interval (~4 min)
- Append `fun start() { scope.launch { while (isActive) { scanOnce(); delay(5_000) } } }`, matching `GraphFileWatcher.pollIntervalMs`'s existing 5s convention.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/PendingCapturePoller.kt`

##### Task 2.2.1d: Wire `PendingCapturePoller` into `Main.kt` startup + `onGraphManagerReady` (~4 min)
- Edit `Main.kt`: `val poller = remember { PendingCapturePoller(fileSystem) }`, `LaunchedEffect(Unit) { poller.start() }` alongside Task 1.5.1a's hotkey wiring; extend Task 1.5.1b's `onGraphManagerReady = { gm -> ... }` lambda to also call `poller.attachGraphManager(gm)` (in addition to `captureController.attachGraphManager(gm)`).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

##### Task 2.2.1e: Unit test drain + retry-on-failure + idempotent replay (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/PendingCapturePollerTest.kt` covering all three Acceptance Criteria above, including: a pre-existing `Block` with the file's `captureId` in today's journal before `scanOnce()` runs, asserting the journal still has exactly one block with that uuid afterward.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/PendingCapturePollerTest.kt`

---

### Epic 2.3: CLI Capture Mode
**Goal**: Give `Main.kt` a headless `--capture-text` mode — the entry point future out-of-process OS surfaces (Phase 2, deferred) will invoke, and the harness this plan's own tests use to exercise the pending-captures format end-to-end today.

#### Story 2.3.1: `--capture-text` argv branch
**As a** future OS-integration script (or a developer smoke-testing this plan), **I want** `stelekit --capture-text "..."` to record a capture without opening the full UI, **so that** a lightweight external trigger never needs to boot the whole Compose app just to write one note.

**Acceptance Criteria**:
- Passing `--capture-text` writes a pending capture and exits without launching the Compose window.
  - *Given* `main(arrayOf("--capture-text", "Remember to call mom"))` is invoked, *When* argument parsing runs, *Then* `parseCaptureArgs` returns `"Remember to call mom"`, `PendingCaptureWriter.write("Remember to call mom")` is called, and the process exits before `application { }` runs.
- Absence of the flag falls through to the normal app launch.
  - *Given* `main(arrayOf())`, *When* argument parsing runs, *Then* `parseCaptureArgs` returns `null` and `application { }` launches as today.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/CaptureCliArgs.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/desktop/CaptureCliArgsTest.kt`

##### Task 2.3.1a: Create `parseCaptureArgs(args)` pure function (~3 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/CaptureCliArgs.kt`: `fun parseCaptureArgs(args: Array<String>): String? { val i = args.indexOf("--capture-text"); return if (i >= 0 && i + 1 < args.size) args[i + 1] else null }`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/CaptureCliArgs.kt`

##### Task 2.3.1b: Wire headless branch into `main(args)` (~4 min)
- Edit `Main.kt`: change `fun main()` to `fun main(args: Array<String>)`; at the top, `parseCaptureArgs(args)?.let { text -> PendingCaptureWriter.write(text); println("Captured."); return }` before any of the existing logging/OTel setup.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

##### Task 2.3.1c: Unit test `parseCaptureArgs` (~3 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/desktop/CaptureCliArgsTest.kt` covering both Acceptance Criteria above.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/desktop/CaptureCliArgsTest.kt`

---

## Phase 3: Live IPC Fast Path (Unix Domain Socket)

### Epic 3.1: `CaptureSocketListener`
**Goal**: Avoid the poller's up-to-5s latency when SteleKit is already running, per research/architecture.md's recommended "(d) for cold-start + (a) as the live-instance fast path" combination.

#### Story 3.1.1: In-process socket listener
**As a** desktop user who already has SteleKit running, **I want** an external capture trigger to land near-instantly (not wait up to 5s for the poller), **so that** the live-instance case doesn't pay the cold-start-path's latency tax.

**Acceptance Criteria**:
- A message sent to the socket while SteleKit is running is written immediately, bypassing the poller entirely.
  - *Given* `CaptureSocketListener` bound at `~/.stelekit/stelekit.sock` inside a running SteleKit process with an active graph, *When* a client connects and writes a JSON `PendingCaptureFile` payload (`{"captureId":"...","text":"Buy milk","capturedAt":"..."}`) followed by a newline, *Then* `CaptureWriter.writeCaptureDirect(graphManager, fileSystem, "Buy milk", captureId = "...")` is called directly (in-process — no `PendingCaptureFile` file is ever written to disk for this path) using the listener's own attached `GraphManager` reference (no `CaptureController` involved), and the client receives an acknowledgement before disconnecting.
- Oversized payloads are rejected, not allowed to OOM the listener.
  - *Given* a client sends a payload exceeding 64KB, *When* the listener reads it, *Then* the connection is closed after 64KB with an error response, per research/pitfalls.md §2's payload-size-cap guidance.
- A stale socket file from a prior crash doesn't block startup.
  - *Given* a leftover `~/.stelekit/stelekit.sock` file with no live listener behind it, *When* `CaptureSocketListener` attempts to bind and gets `AddressAlreadyInUse`, *Then* it deletes the stale file and retries the bind once before giving up and logging an error (hotkey/UI capture remains fully functional either way).
- The socket payload shares the pending-captures file's exact message shape.
  - *Given* ADR-001's claim that both IPC paths use one shared shape, *When* `CaptureSocketListener`'s wire format is inspected, *Then* it is the same `PendingCaptureFile` JSON (`captureId`, `text`, `capturedAt`) the pending-captures directory uses, not a bare string — resolving architecture-review's message-shape concern and enabling `CaptureSocketClient`'s lost-ack fallback (Story 3.2.1) to reuse the same `captureId`.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketListener.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureSocketListenerTest.kt`

##### Task 3.1.1a: Create `CaptureSocketListener` binding a UDS (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketListener.kt`: `class CaptureSocketListener(private val fileSystem: PlatformFileSystem)` holding `@Volatile private var graphManager: GraphManager? = null` and `fun attachGraphManager(gm: GraphManager) { graphManager = gm }`, opening `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` bound to `UnixDomainSocketAddress.of(Path.of("${System.getProperty("user.home")}/.stelekit/stelekit.sock"))` (JEP-380, JDK 21 already the project's toolchain — zero new dependency), with its own `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler {...})`. Depends only on `CaptureWriter` + its own `GraphManager` reference — no `CaptureController` dependency (architecture-review SRP/ISP finding).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketListener.kt`

##### Task 3.1.1b: Implement accept-loop with a 64KB payload cap (~5 min)
- Append `fun start()` launching an accept loop on the listener's scope: reads at most 64KB per connection, decodes UTF-8, decodes it via `Json.decodeFromString<PendingCaptureFile>`, and — if `graphManager` is attached — calls `CaptureWriter.writeCaptureDirect(graphManager!!, fileSystem, file.text, captureId = file.captureId)`; writes back `"OK\n"` or `"ERR\n"` (including when `graphManager` isn't attached yet or JSON decoding fails), closes the channel. Wrap the per-connection body in its own try/catch so one malformed payload can't kill the accept loop.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketListener.kt`

##### Task 3.1.1c: Set socket file permissions to 0600 (~3 min)
- Append POSIX-only permission tightening after bind (`Files.setPosixFilePermissions(socketPath, setOf(OWNER_READ, OWNER_WRITE))`), guarded by `FileSystems.getDefault().supportedFileAttributeViews().contains("posix")` for Windows compatibility (per research/pitfalls.md §2's "socket file permissions provide access control for free" guidance).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketListener.kt`

##### Task 3.1.1d: Stale-socket cleanup on bind failure (~3 min)
- Append: catch `java.nio.channels.AlreadyBoundException`/`java.net.BindException`, delete the existing socket file, retry the bind once, else log an error and leave the listener inactive (fail-open, matching the hotkey's fail-open pattern from Task 1.3.1c).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketListener.kt`

##### Task 3.1.1e: Wire into `Main.kt` startup/shutdown (~3 min)
- Edit `Main.kt`: `val socketListener = remember { CaptureSocketListener(fileSystem) }`, `LaunchedEffect(Unit) { socketListener.start() }`, extend Task 1.5.1b's `onGraphManagerReady` lambda to also call `socketListener.attachGraphManager(gm)`, and stop the listener in `onCloseRequest` alongside Task 1.5.1d's hotkey teardown.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

##### Task 3.1.1f: Integration test — real UDS round trip in a temp dir (~5 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureSocketListenerTest.kt` binding to a temp-dir socket path, calling `attachGraphManager` with a test `GraphManager`, sending a real JSON `PendingCaptureFile` payload over `SocketChannel`, asserting a `Block` was written via `CaptureWriter.writeCaptureDirect` and the 64KB cap is enforced.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureSocketListenerTest.kt`

---

### Epic 3.2: `CaptureSocketClient`
**Goal**: Give the CLI capture mode (Epic 2.3) a fast path that tries the live socket first, only falling back to the pending-captures file (Epic 2.1) when no instance is listening — the "one Gateway, one message shape regardless of transport" decision from Pattern Decisions.

#### Story 3.2.1: Socket-first, file-fallback CLI capture
**As a** future OS-integration trigger (or the CLI capture mode used for smoke-testing), **I want** the fastest available delivery path tried automatically, **so that** captures land instantly when SteleKit is already running and are never lost when it isn't.

**Acceptance Criteria**:
- When a live listener exists, the socket path is used and no pending-capture file is written.
  - *Given* `CaptureSocketListener` is bound and running, *When* `CaptureSocketClient.trySend("Buy milk")` is called, *Then* it generates one `captureId`, sends a `PendingCaptureFile(captureId, "Buy milk", now)` payload, connects within 200ms, receives `"OK\n"`, returns `true`, and no file appears in `~/.stelekit/pending-captures/`.
- When no listener exists (SteleKit not running), the client falls back to the pending-capture file.
  - *Given* no process is listening on `~/.stelekit/stelekit.sock`, *When* `CaptureSocketClient.trySend("Call dentist")` is called, *Then* the connection attempt fails within 200ms, `trySend` returns `false`, and the CLI capture-mode branch (Task 3.2.1b) falls through to `PendingCaptureWriter.write("Call dentist", captureId)` — reusing the exact `captureId` `trySend` already generated.
- A lost acknowledgement does not double-record the capture.
  - *Given* `CaptureSocketListener` already called `CaptureWriter.writeCaptureDirect(...)` for a payload (the block was written and flushed) but the `"OK\n"` response is lost or the client times out before reading it, *When* `trySend` returns `false` and the CLI branch falls back to `PendingCaptureWriter.write(text, captureId)` with the same `captureId`, *Then* the poller's later replay of that file resolves to the identical `Block.uuid`, and `insertBlock`'s `INSERT OR REPLACE` (`SteleDatabase.sq:335-337`) collapses the two attempts into one row — no duplicate journal block, resolving the adversarial-review dedup blocker for the socket fast path specifically.

**Files**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketClient.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureSocketClientTest.kt`

##### Task 3.2.1a: Create `CaptureSocketClient.trySend(text)` (~5 min)
- Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketClient.kt`: `fun trySend(text: String): CaptureSendResult` (a small `data class CaptureSendResult(val delivered: Boolean, val captureId: String)`, so the caller always learns the `captureId` used, whether or not delivery succeeded) — generates `captureId = UuidGenerator.generateV7()` up front, encodes `PendingCaptureFile(captureId, text, Clock.System.now().toString())` via `Json.encodeToString`, attempts `SocketChannel.open(UnixDomainSocketAddress.of(...))` with a 200ms connect timeout, writes the payload, reads the `"OK\n"`/`"ERR\n"` response; any `IOException` (no listener, refused) or a non-`"OK\n"` response returns `delivered = false` with the same `captureId`.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/capture/CaptureSocketClient.kt`

##### Task 3.2.1b: Update CLI capture branch to try socket first (~4 min)
- Edit `Main.kt`'s headless branch (Task 2.3.1b): `val result = CaptureSocketClient.trySend(text); if (!result.delivered) PendingCaptureWriter.write(text, result.captureId)` — reusing the same `captureId` on fallback so a lost-ack race resolves to one block, not two (Story 3.2.1's dedup AC).
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`

##### Task 3.2.1c: Unit test fallback behavior + captureId reuse (~4 min)
- Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureSocketClientTest.kt`: assert `trySend` returns `delivered = true` against a real bound listener and `delivered = false` against an unbound path, within the test's timeout budget; assert the returned `captureId` is stable across a single `trySend` call so a caller's fallback write can reuse it.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureSocketClientTest.kt`

---

## Deferred (not tasked in this plan)

Per ADR-002, the following are explicitly out of scope for this plan and tracked as a separate, later project:

- **macOS Services-menu entry** ("Add to SteleKit") — invokes `stelekit --capture-text` (Epic 2.3/3.2) via `open -a` or a custom URL scheme; needs `Info.plist` `NSServices` entries merged into the jpackage build.
- **Linux Nautilus "Send to" script** — a shell script in `~/.local/share/nautilus/scripts/` invoking `stelekit --capture-text` (or `--capture-file` for file selections); packaging hook already exists via the Homebrew Formula's `post_install`.
- **Windows registry context-menu handler** — a static `HKEY_CURRENT_USER\Software\Classes\*\shell\SendToSteleKit\command` entry invoking `stelekit.exe --capture-file "%1"`, authored as a WiX fragment merged via `--resource-dir`.

Each of these depends only on this plan's `--capture-text` CLI contract (Epic 2.3) and the pending-captures file format (Epic 2.1) — no rework of Phases 1-3 is anticipated when that follow-up project starts.
