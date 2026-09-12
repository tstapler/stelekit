# Architecture Review: desktop-quick-capture
**Date**: 2026-09-05
**Verdict**: CONCERNS

## Constitution Violations
- N/A — no `docs/adr/ADR-000-architecture-constitution.md` found in this repo (`docs/adr/` contains ADR-001 through ADR-017, none of them a constitution).

## Blockers

Both prior blockers are resolved in the current `plan.md`.

- ~~**Epic 1.1 / Story 1.1.1 / Task 1.1.1b** — missing null-`writeActor` fallback + unsatisfiable "no `@OptIn`" AC.~~ **Resolved.** Story 1.1.1's AC (`plan.md:156-157`) now explicitly requires "the existing `@OptIn(DirectRepositoryWrite::class) repoSet.blockRepository.saveBlock(block)` fallback when `writeActor == null` (ported verbatim from `CaptureViewModel.kt:100-111`'s 'Bug 1 mitigation')" and narrows the ban to "no `@OptIn(DirectSqlWrite::class)` anywhere in the file... `@OptIn(DirectRepositoryWrite::class)` is expected and required for the fallback branch; only `DirectSqlWrite` is banned." Task 1.1.1b (`plan.md:174-176`) implements both branches. The AC is now internally consistent and satisfiable.

- ~~**Epics 1.2 / 2.2 / 3.1** — `PendingCapturePoller` and `CaptureSocketListener` depended on the full `CaptureController` for one write method.~~ **Resolved.** Both classes now take only `fileSystem: PlatformFileSystem` in their constructors and hold their own `@Volatile private var graphManager: GraphManager? = null`, set via `attachGraphManager(gm)` (`PendingCapturePoller`: `plan.md:399`; `CaptureSocketListener`: `plan.md:469`), and call `CaptureWriter.writeCaptureDirect(...)` directly. `CaptureWriter.writeCaptureDirect` itself (`plan.md:191`) is documented as "the one entry point `CaptureController`, `PendingCapturePoller`, and `CaptureSocketListener` each call directly... none of them call through `CaptureController`." A grep of `plan.md` for `CaptureController` confirms no stale dependency reference remains in the Poller/SocketListener epics/stories/tasks — every hit in those sections is an explicit negative statement ("no `CaptureController` dependency, per architecture-review").

## Concerns

- [ ] **Epics 1.2 / 1.4 — `CapturePopupState` illegal states (`plan.md:204-206`)** — `Shown(text: String, saveState: SaveState, captureResult: CaptureResult? = null)` models save-progress and graph-availability as two independently-settable fields, so illegal combinations are constructible (e.g. `saveState = Saving` with `captureResult = NoActiveGraph`, or `Saved` with `GraphLocked`) — exactly the case sum types are meant to make unrepresentable.
  - **Recommendation**: collapse into one sealed hierarchy under `Shown`: `Editing(text)`, `Saving(text)`, `Saved(text)`, `Error(text, message)`, `Unavailable(reason: CaptureResult)` — so `CapturePopupWindow`'s rendering `when` is exhaustive over only reachable states.

- [ ] **Epic 3.1/3.2 vs. ADR-001 message-shape claim (`plan.md:450-451`; ADR-001 line 22)** — ADR-001 states both IPC paths "write into or read from the same `PendingCaptureFile` message shape via a single `CaptureSocketClient`," but Task 3.1.1b specifies the socket protocol as a bare newline-terminated UTF-8 string with no envelope, while the pending-captures file path uses the structured `{text, capturedAt}` JSON `PendingCaptureFile`. The two transports `CaptureSocketClient` is meant to unify do not actually share a message shape as scoped.
  - **Recommendation**: either encode the socket payload as the same JSON `PendingCaptureFile` shape (with `capturedAt` set to "now" at send time), or amend ADR-001 to state plainly that the shapes differ between the fast path and the cold path, and why that's acceptable.

- [ ] **ADR-002 deferred-scope vs. build-vs-buy.md §2 (`plan.md:504`)** — `research/build-vs-buy.md` §2 recommends a macOS custom URL scheme (`stelekit://capture?text=...`) as "Low effort... no Xcode target... works with the existing ad-hoc-signed jpackage `.dmg`," explicitly distinguishing it from (and cheaper than) both the Share Extension and a Services-menu entry. The plan's Deferred section and ADR-002 only mention "macOS Services-menu entry" for Phase 2 and never engage with the URL-scheme option that research flagged as v1-feasible with no new native tooling or IPC machinery (just `Desktop.setOpenURIHandler()`). Neither ADR-002's "Alternatives Considered" nor the plan explains why this specific, cheaper recommendation was dropped rather than folded into Phase 1 alongside the hotkey popup.
  - **Recommendation**: either add the URL-scheme handler to v1 scope (it has no IPC/cold-start dependency beyond what Phase 1 already builds) or add an explicit rejection rationale to ADR-002.

- [ ] **`CaptureController.graphManager` thread-safety (`plan.md:213`, Task 1.2.1c)** — `private var graphManager: GraphManager? = null` is written once via `attachGraphManager()` (from the composition/main-adjacent `onGraphManagerReady` callback) and read from at least three other execution contexts: the controller's own `Dispatchers.Default` scope, the poller's `Dispatchers.IO` scope, and the hotkey library's own callback thread. The plan doesn't mark it `@Volatile`, unlike this repo's established convention for the same shape of field (`GraphWriter.cryptoLayer`, `DatabaseWriteActor.onWriteSuccess`).
  - **Recommendation**: mark `graphManager` `@Volatile`, matching existing convention.

## Nitpicks

- The default hotkey key-combo is hardcoded inside `JKeymasterHotkeyListener` with no `HotkeyCombo`/similar value type threaded through `GlobalHotkeyListener.register(...)`. Fine for v1 (no settings UI planned), but a future "customize your hotkey" feature will require editing the Adapter's internals rather than passing in a value.
- `CaptureWriter.writeCapture` constructs a fresh `GraphWriter` (with its own unused `ownedScope`) on every single call (hotkey save, poller replay, socket message) rather than reusing a shared instance. Harmless today since `startAutoSave` is never invoked on it (no job is ever launched on that scope), but it's an unnecessary per-call allocation and obscures that `GraphWriter` is a per-graph singleton elsewhere in the app.
