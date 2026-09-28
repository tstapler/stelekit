# ADR-017: WASM Git Write-Back Reachability via a `GitRepository` Actual (Amends ADR-015)

## Status
Accepted

## Context

ADR-015 (Accepted) specifies `WasmGitWriteService`'s write-back mechanics (GitHub five-step Git
Data API sequence, GitLab single-call fallback, conflict detection, dirty-file tracking) and
states the integration shape as:

> `WasmGitWriteService` is the `wasmJs` actual behind a `common` `GitWriteService` interface.
> JVM/Android/iOS actuals delegate to the existing `GitSyncService.commitLocalChanges()`.
> ... The `JsGitManager` methods (`commit`, `push`, `pull`, `status`, `isDirty`) are wired to
> `WasmGitWriteService` when credentials and remote URL are available.

Phase 2 research for `web-git-writeback` (`project_plans/web-git-writeback/research/features.md`
§0) found this integration shape targets dead code: grepping the entire `kmp/src` tree for
`GitManagerFactory.create()` or any UI/ViewModel/repository consumer of `GitManager` finds zero
call sites outside the four `actual` declaration files themselves. The UI stack that actually
exists — `SyncStatusBadge`, `GitSetupScreen`, `ConflictResolutionScreen`, all driven by
`GitSyncService.syncState: StateFlow<SyncState>` — depends on the `commonMain` `GitRepository`
`expect`/`actual` interface (`git/GitRepository.kt`), not `GitManager`. `App.kt` takes
`gitRepository: GitRepository? = null`; `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`
never passes one, so on web today `GitSyncService` is never instantiated at all and the entire
sync UI is structurally inert (renders "Set up sync" and nothing past it can fire), independent of
whatever `JsGitManager` does.

Shipping `WasmGitWriteService` only behind `JsGitManager` per ADR-015's literal text would produce
a working REST client that no screen calls — closing BUG-005's ticket text without closing its
actual user-visible symptom.

## Decision

Amend ADR-015's integration shape only. `WasmGitWriteService`'s write-back mechanics (the
five-step GitHub sequence, GitLab single-call path, conflict detection, dirty-file tracking
design) are unchanged and remain governed by ADR-015.

1. **No common `GitWriteService` interface is built.** `WasmGitWriteService` is called directly
   by two thin `wasmJs`-local callers — no new `commonMain` abstraction layer.
2. **A new `wasmJsMain` actual of the existing `GitRepository` interface (`WasmGitRepository`) is
   the primary integration point**, wired into `Main.kt`/`App.kt` exactly like
   `JvmGitRepository`/`AndroidGitRepository` are on their platforms. This makes the entire existing
   `GitSyncService` orchestration (`sync()`, `fetchOnly()`, `resolveConflictBySide()`, the
   `SyncState` state machine) and every UI surface that already consumes it work on web with zero
   changes to `GitSyncService`, `App.kt`'s existing null-gated wiring, or any screen.
3. **`JsGitManager` is retained and wired, but as a thin secondary delegate to the same
   `WasmGitWriteService` instance `WasmGitRepository` uses internally** — not a second, parallel
   implementation. This satisfies BUG-005's literal text (the interface is public; leaving it
   permanently stubbed after this project would be a silent gap) at near-zero marginal cost, since
   both callers share one underlying engine and therefore cannot disagree about push/conflict
   outcomes.
4. Git working-tree-shaped `GitRepository` methods that have no REST-API equivalent
   (`isGitRepo`, `init`, `hasDetachedHead`, `removeStaleLockFile`, `stageSubdir`) are mapped to
   conservative no-op/constant implementations (see `project_plans/web-git-writeback/implementation/plan.md`
   Story 4.1.1) rather than `NotSupported` errors, since none of them represent a real error
   condition in a checkout-less REST model — only `clone()` returns `NotSupported`, since a full
   working-tree clone genuinely has no scoped implementation in this project (out of Scope; see
   that plan's Unresolved Questions).

## Rationale

1. **Reaches the real UI for free.** `GitSyncService`'s conflict flow, `SyncState` machine, and
   every consuming screen are already correct and already shared cross-platform Compose code —
   ADR-015's own stated goal ("no WASM-specific UI branches are needed") is achieved more directly
   by giving `GitSyncService` a working `GitRepository`, its actual dependency, than by building a
   parallel `GitManager`-based path nothing calls.
2. **Avoids a redundant object graph.** Retrofitting `App.kt`/`Main.kt` to also instantiate a
   second, `GitManager`-based sync orchestrator alongside `GitSyncService` (an alternative
   considered and rejected during planning — see that plan's Pattern Decisions table) would double
   the surface area maintaining dirty-set reads, host dispatch, and conflict detection for no
   benefit, and risks the two paths silently disagreeing about state over time.
3. **`JsGitManager` stays real, not deleted.** Deleting `JsGitManager`/`GitManager` outright was
   considered and rejected: the interface is public and referenced by BUG-005's own text; wiring it
   as a thin delegate (rather than leaving it a stub or removing it) closes that ticket honestly
   without incurring a second implementation's maintenance cost.

## Alternatives Rejected

### Implement only `WasmGitWriteService` behind `JsGitManager`/`GitManager` (ADR-015's literal integration shape)
Ships a working REST client with zero UI call sites, per the reachability gap this ADR documents.
Rejected.

### Build both a `GitRepository` actual and the `GitManager`-based path independently ("dual-track")
Satisfies ADR-015's literal text and fixes real UI reachability, but produces two independent
implementations of dirty-set reading, host dispatch, and conflict detection for a single
underlying capability — doubles maintenance surface and invites drift. Rejected in favor of one
engine (`WasmGitWriteService`) with two thin call-in surfaces.

## Consequences

- `GitHostAdapter` and all new `@Serializable` Git Data API / GitLab commits API models live in
  `commonMain` (`git/GitHostAdapter.kt`, `git/model/GitDataApiModels.kt`,
  `git/model/GitLabCommitModels.kt`) rather than `wasmJsMain`, since they have no `js()`/`JsAny`
  dependency — this also makes them directly unit-testable from `commonTest` without the
  pure-Kotlin "test double" pattern `WasmSectionSyncServiceTest.kt` needs for genuinely
  `wasmJsMain`-only code.
- `GitSyncService.sync()`'s existing step ordering (commit local → fetch → merge → reload → push)
  requires `WasmGitWriteService`'s `commit()` to create the GitHub commit object (Git Data API
  steps 1–4) *before* the conflict check, and `push()` to perform the ref update (step 6)
  separately — see `plan.md`'s "Commit/push split" Pattern Decision. This is a direct structural
  consequence of reusing `GitSyncService` unmodified rather than writing a wasmJs-specific
  orchestrator that could fuse commit+push into one call.
- The same "`GitSyncService` calls `commit()`/`fetch()`/`merge()`/`push()` as 4 independent
  top-level suspend calls" structure means the per-remote `GitWriteLock`
  (`navigator.locks.request()`) cannot span the full commit→push attempt the way `plan.md`'s Web
  Lock Pattern Decision originally specced — a Web Lock's lifetime is scoped to the single
  callback passed to it, and there is no safe way to hold that callback open across all 4
  separately-invoked calls without leaking the lock on the call paths (`commitLocalChanges()`,
  the pre-resolution `commit()` inside `resolveConflictBySide()`) that can call `commit()` without
  a following `push()` in the same attempt. `GitWriteLock` is therefore scoped narrower than
  originally planned: it wraps only `WasmGitWriteService.push()`'s write-critical-section (the
  final ref-update PATCH or GitLab commits POST, plus the immediately-following
  `clearDirtySet` checkpoint write) — see `plan.md`'s corrected "Web Lock scope" Pattern Decision
  and the "Web Lock — guarantees and known gaps" note under Epic 2.3 for exactly which races this
  does and doesn't close. This is the same trade-off as the commit/push split above: reusing
  `GitSyncService` unmodified buys reachability for free but caps how much of the read-modify-write
  window a client-only lock can practically cover.
- Future `ADR-016` (`GitManager` → `SyncTransport` rename) is unaffected in scope: `WasmGitRepository`
  becomes the thing `ADR-016`'s eventual `GitHubRestTransport` wraps/renames, and `JsGitManager`'s
  thin-delegate role is exactly the kind of "mechanical follow-up" `ADR-016` anticipates, not a
  rewrite.
