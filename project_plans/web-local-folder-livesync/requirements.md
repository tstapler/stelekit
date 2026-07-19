# Requirements: web-local-folder-livesync

**Date**: 2026-07-16
**Type**: feature addition
**Complexity**: 4 — high-stakes / cross-cutting

## Problem Statement
On the web/WASM build, users can pick a local host directory via `pickDirectoryAsync()` (`PlatformFileSystem.kt:324`), but this is a one-time import: `importUserDirToCache()` (`PlatformFileSystem.kt:341`) copies the directory's file contents into the OPFS-backed in-memory cache once, and every subsequent read/write goes only to OPFS. There is no retained `FileSystemDirectoryHandle`, so edits made in the browser never reach the picked folder's files, and edits made to those files outside the browser (in a text editor, by git, by another app) are never picked up after the initial import. Desktop and Android users get true bidirectional sync with their local files (JVM file I/O, Android SAF); web users currently only get a one-shot snapshot into an origin-private cache they can't get back out of, except via the separate, unrelated git-remote write-back path (`project_plans/web-git-writeback`).

## Baseline
Today, a web user who picks a directory sees their files load into the app once. Any edit they make in the browser stays in OPFS only — it is never written back to the original folder on disk. If they edit a `.md` file directly (in their editor, via git pull, etc.) while the SteleKit tab is open, the app has no way to notice; the only way to see the update is to re-pick the same directory, which re-imports everything (of unclear — and likely conflicting-with-local-edits — behavior, since the import logic was written for a first-load case, not a merge case). This gap is explicitly called out and deferred in two prior projects: `project_plans/web-git-writeback/requirements.md:10,58-59` and `project_plans/stelekit-web-opfs/requirements.md:15`.

## Users / Consumers
Web-target SteleKit users on a browser that supports the File System Access API (`showDirectoryPickerSupported()`, `PlatformFileSystem.kt:323` — Chromium-based browsers today) who want to use their graph directly from a local folder on the machine they're browsing from, without configuring a git remote. This is a separate audience/use-case from `web-git-writeback`'s git-sync users, though the two features may eventually be used together by the same user on the same graph.

## Success Metrics
- A user picks a directory once; edits made to a page in the browser are written to the corresponding `.md` file in that host directory without any further user action (write-through), within roughly the same latency budget as SteleKit's existing 500ms debounced editor autosave.
- A `.md` file changed outside the browser (in an external editor, via git, etc.) while the picked directory is open in SteleKit is detected and reflected in the app without requiring the user to re-pick the directory.
- A conflicting change — the app has an unsaved/uncommitted-to-disk edit to a block/page at the same time the underlying file changed externally — surfaces through the same `DiskConflict`/`ExternalFileChange` mechanism desktop already uses (`GraphLoader.externalFileChanges`, `db/GraphLoader.kt:433`, rendered via `DiskConflictDialog`/`DiskConflictFullScreen`), not a silent overwrite in either direction.
- Reopening the app in a new tab/session for a previously-picked directory requires at most one click to resume access (per the accepted permission-UX decision below) — not a full re-pick-and-reimport.
- No regression to the existing one-time-import behavior on browsers without File System Access API support (`supportsNativeDirectoryPicker == false`) — those users keep today's fallback experience unchanged.
- No regression to `web-git-writeback`'s OPFS-to-git-remote sync path or its dirty-file tracking.

## Appetite
Large (3–6 weeks)
*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline.)*

## Constraints
- File System Access API is available today only in Chromium-based browsers (per `PlatformFileSystem.kt:323`'s existing capability check) — this project must degrade gracefully on unsupported browsers to the current one-time-import behavior, not require the new API.
- Directory permission grants are session-scoped from the browser's perspective. Per this session's explicit decision, a one-click "resume access" re-grant per new session is acceptable UX (matching the precedent already accepted for git sync's session-scoped PAT in `web-git-writeback`) — do not attempt to build a fully silent/no-prompt persistence mechanism, and do not treat the need for a re-grant click as a bug.
- Must not regress `web-git-writeback`'s dirty-file tracking (`PlatformFileSystem.kt:29` `dirtySet`, `.stele-dirty-set.json` checkpoint) or its OPFS-to-git-remote push path — if this project's write-through reuses or extends that tracking, it must remain correct for both consumers (local-folder write-back and git write-back) independently and simultaneously.
- Conflict UX should reuse the existing `DiskConflict`/`DiskConflictDialog`/`DiskConflictFullScreen` machinery already built for desktop's disk-conflict flow rather than inventing a new conflict UI. **Correction from Phase 2 research**: the original draft of this requirement named `ConflictResolutionScreen`/`SyncState.ConflictPending` — that is the *git-merge* line-hunk conflict UI (`web-git-writeback`'s concern), a different mechanism from the external-file-vs-editor `DiskConflict` flow this feature actually needs (`research/architecture.md`). Any deviation from the `DiskConflict` precedent is a planning-phase decision, not assumed here.
- Paranoid mode (encrypted `.md.stek` blobs, `writeFileBytes`/`readFileBytes`) must work over this path too, per the explicit scope note in `project_plans/web-git-writeback/requirements.md:59`.

## Non-functional Requirements
- **Performance SLO**: Write-through latency should not noticeably lag behind SteleKit's existing ~500ms debounced autosave; detecting an external file change should not require a full directory re-scan on every check if that would be perceptibly slow on a large graph (thousands of pages) — exact detection cadence/mechanism is a research/planning decision, not fixed here.
- **Scalability**: Must not become an O(graph) scan per this codebase's standing constraint (`CLAUDE.md`) — a large graph (thousands of pages, matching the existing 8,000+-page warm-start regression tests elsewhere in this repo) must not make either write-through or external-change detection linearly scan the whole directory on every tick.
- **Security classification**: Internal/local — File System Access API access is scoped to a directory the user explicitly picked and explicitly re-grants; no new network surface is introduced by this project (distinct from `web-git-writeback`, which does add one).
- **Data residency**: Not applicable — user's own local machine.

## Scope
### In Scope
- Retaining a `FileSystemDirectoryHandle` across the session (and across reloads/reopens via IndexedDB handle persistence + the one-click permission re-grant) rather than the current import-and-discard behavior.
- Write-through: edits made in the app are written to the corresponding file(s) in the picked host directory, not just to OPFS.
- Detecting changes made to files in the picked directory from outside the browser while the app has it open, and reflecting them in the app.
- Conflict handling when a local (in-app, not-yet-written) edit and an external file change collide, reusing the existing `DiskConflict`/`DiskConflictDialog` pattern (see Constraints correction above — not `ConflictResolutionScreen`, which is `web-git-writeback`'s unrelated git-merge UI).
- Paranoid-mode (encrypted) byte-level I/O over this path.
- Cross-tab coordination — if the same directory could be open in more than one browser tab at once, define and implement the coordination story rather than leaving it undefined (exact mechanism is a planning-phase decision).
- Graceful fallback to today's one-time-import behavior on browsers without File System Access API support.

### Out of Scope
- Firefox/Safari support for the File System Access API itself — out of this project's control; those browsers keep the existing fallback path.
- Any change to `web-git-writeback`'s git-remote sync path, `WasmGitWriteService`, or `WasmSectionSyncService` — this project may need to coordinate with (not replace) that path's dirty-tracking, but does not modify its behavior.
- The `sync-transport-rename` follow-up referenced in `web-git-writeback`'s requirements.md — unrelated.
- Syncing more than one picked directory at a time per graph.
- Any new server-side component — this stays a purely client-side (browser) feature, matching the rest of the wasmJs platform surface.

## Rabbit Holes
- **No native filesystem-watch API in browsers**: unlike desktop/Android's `fileWatcher`/`externalFileChanges`, there is no OS-level notification for "a file in this directory changed" available to a web page — detection will need some form of polling or re-check-on-focus, and the exact mechanism/cadence has real perf and battery/CPU tradeoffs on a large graph. This needs an explicit decision in planning, not a default assumption.
- **Directory structure changes** (renaming a page, moving it between namespaces, deleting it) need to propagate to actual file renames/moves/deletes in the host directory — more than just "write new content to an existing path." Scope and mechanism for this needs explicit planning, not just editing existing files in place.
- **Concurrent write races**: the app writing a file via `FileSystemFileHandle.createWritable()` at the same moment an external process (git, another editor) writes the same file is a real race with no cross-process locking primitive available in browser JS — needs a defined behavior (last-write-wins? detect-and-conflict?) rather than an assumed one.
- **Interaction with `web-git-writeback`'s dirty-tracking**: if a user has both git sync configured AND a local folder picked for the same graph, whether an edit is "dirty" for local-folder purposes and "dirty" for git-push purposes could diverge (e.g., a change written to disk via this feature but not yet committed to git) — needs explicit planning rather than assuming the existing single dirty-set generalizes cleanly to two independent consumers.

## Alternatives Considered
- Continuing the current one-time-import-only behavior and just documenting the limitation — rejected per the explicit prior-session decision (recorded in both sibling projects' requirements.md) to treat this as a real deferred follow-up, not a permanent limitation.
- Polling-only sync without a retained handle (re-run `pickDirectoryAsync()`'s import logic on a timer) — not evaluated in depth here; whether a retained handle vs. re-import-on-timer is the right approach is a research/planning-phase question, not decided in this document.

## Feasibility Risks
- File System Access API browser support and exact permission-persistence behavior (what survives a reload without a re-grant, what doesn't) may have shifted since this was last investigated for the sibling OPFS project — research phase should verify current behavior against real browsers, not assume.
- No native file-watch API means external-change detection is inherently best-effort in a browser; research should establish what detection latency is actually achievable before success metrics are treated as committed.
- Reusing `web-git-writeback`'s conflict UI and/or dirty-tracking machinery for a second, independent consumer (local-folder write-back) could have hidden coupling — flagged as a rabbit hole above, not assumed solvable by simple reuse.
- **[RESOLVED during planning — scope expanded, see "Planning Amendments" below] Latent, pre-existing `writeFile`/`writeFileBytes` OPFS-write durability gap.** *Originally accepted as out of scope; the user subsequently directed this project's scope be expanded to fix it at the root instead — see `implementation/plan.md` Epic 1.7. The paragraph below is retained verbatim as the original risk assessment, for audit-trail purposes; it no longer describes this project's shipped behavior.* `PlatformFileSystem.writeFile`/`writeFileBytes` (`PlatformFileSystem.kt:267-305`, predates this project) set the in-memory `cache`/`bytesCache` entry synchronously but persist it to OPFS via an unawaited `scope.launch { opfsWriteFile(...) }` — fire-and-forget, with no `beforeunload`/`pagehide` flush covering content writes (only the separate `.stele-dirty-set.json` git marker has that backstop). A hard crash (not a graceful tab close) landing between the synchronous cache write and that async write's completion leaves the edit in neither OPFS nor, once this project ships, the host directory: on reconnect, `cache` reloads from OPFS without the edit, and this project's own reconciliation pass (Epic 3.2) correctly reports `Identical` for that path — a silent, permanent, zero-record loss of one edit. This is a real gap in the *existing* codebase's write path, not something this project's design introduces or worsens; closing it requires changing `writeFile`/`writeFileBytes`'s platform-wide synchronous/non-blocking contract (used by every wasmJs save, not just host-sync users) or adding new flush infrastructure to `PlatformFileSystem` itself — both out of scope for this project's Large (3-6 week) appetite, which is already fully committed to the host-directory-sync feature surface. *(Historical: this paragraph originally continued "...but does not close it," with a pointer to plan.md's now-removed "Known Pre-Existing Limitations Not Fixed By This Project" section. That section was removed when the gap was closed — see `implementation/plan.md` Epic 1.7 and the "OPFS-write durability" Pattern Decisions row for the fix that superseded this risk assessment.)*

## Planning Amendments
*(Notes added during/after Phase 3 planning, for a future auditor — not part of the original
ideation interview.)*
- **OPFS-eviction mitigation (`storage.persist()`, `implementation/plan.md` Epic 2.4)**: added during
  planning, not part of this document's original Scope/Constraints. `research/pitfalls.md` §1.3
  surfaced that OPFS can be LRU-evicted under storage pressure with no app-visible error once a host
  directory is attached — a risk this document's original ideation interview did not anticipate.
  `implementation/plan.md`'s Epic 2.4 (`navigator.storage.persist()`, best-effort, fire-and-forget on
  every successful connect/reconnect) was added in response, alongside the unconditional
  reconciliation-on-reconnect fix (Epic 3.2/3.4) that serves as the actual recovery path if eviction
  has already happened. No change was made to this document's Scope/Constraints/Success Metrics —
  this note exists so a future auditor sees where Epic 2.4 came from rather than assuming it was
  always in scope.
- **OPFS-write-durability fix (`implementation/plan.md` Epic 1.7) — scope explicitly expanded by the
  user.** The Feasibility Risks bullet below originally documented `writeFile`/`writeFileBytes`'s
  unawaited OPFS write as an *accepted, pre-existing latent bug* this project would narrow but not
  close. The user subsequently directed that this project's scope be expanded to fix it at the root
  instead. `implementation/plan.md`'s Epic 1.7 now closes it (awaited-write-before-host-enqueue plus
  a `beforeunload`/`pagehide` best-effort flush); the Feasibility Risks bullet below is retained,
  marked resolved, rather than deleted, so the original risk assessment remains visible alongside its
  resolution.

## Observability Requirements
Log write-through attempts/outcomes and detected-external-change events client-side (console, matching this codebase's existing `println("[SteleKit] ...")` convention in `PlatformFileSystem.kt`) — no PII/file-content in logs, just paths and outcomes. No new server-side alerting/oncall surface (purely client-side feature, same as `web-git-writeback`).

## Risk Control
No feature-flag infrastructure exists for gradual client-side rollout (consistent with `web-git-writeback`'s precedent); this ships scoped to `wasmJs` only, behind the existing explicit opt-in of picking a directory — a user who hasn't picked a directory sees no behavior change, and a user on a browser without File System Access API support falls back to today's behavior automatically. Rollback = revert the retained-handle/write-through wiring back to the current import-only `pickDirectoryAsync`/`importUserDirToCache` behavior; no data migration concern since OPFS remains the source of truth either way.

## Open Questions
*(all four original questions below were resolved by Phase 2 research — see `research/*.md`)*
- ~~Exact mechanism and cadence for detecting external file changes~~ — resolved: `FileSystemObserver` is shipped stable on Chrome/Edge 133+ (Jan 2025, no origin trial needed) — the only browsers this feature targets anyway — so native change events are the primary mechanism, not polling. Caveats: re-request permission on first observe after reload, a Windows cross-directory-move quirk (reports as separate disappear/appear rather than "moved"), and an "unknown" event type on some platforms needing a re-enumeration fallback. The existing `FileRegistry`/`GraphFileWatcher` polling contract (commonMain, already wired to `DiskConflictDialog`) should still be the *destination* — `getLastModifiedTime()` just needs to stop being hardcoded `null` on wasmJs (`PlatformFileSystem.kt:365`) — but the *source* feeding it can be observer-driven rather than a new polling loop.
- ~~Whether to extend `web-git-writeback`'s dirty-tracking~~ — resolved: no. `research/architecture.md` recommends this feature's write-through be a structurally independent third side-effect on `writeFile`/`writeFileBytes`/`deleteFile` (`PlatformFileSystem.kt:267-321`), never routed through the existing `dirtySet`/`recordDirty` git-write-back machinery, which has different consumers and persistence semantics.
- ~~Cross-tab coordination mechanism~~ — resolved: reuse `GitWriteLock.kt`'s existing `navigator.locks.request()` acquire-now/release-later idiom rather than building a new one.
- ~~How directory-structure changes propagate~~ — resolved (at the design-principle level; exact algorithm is still a planning task): no browser supports directory `move()`/rename, so in-app renames must become write-new + delete-old with an idempotent reconciliation path for interrupted renames (`research/features.md`).

## Critical Finding From Research — must be addressed in planning, not deferred
`research/architecture.md` identified a real data-loss bug at the feature's own upgrade boundary: `pickDirectoryAsync()`'s `importUserDirToCache()` (`PlatformFileSystem.kt:341-363`) is unconditional and overwrite-only. A returning user who already has browser-only edits sitting in OPFS from the *current* one-time-import behavior, and who then opts into this new live-sync feature by picking the same directory again, would have those edits silently destroyed by the existing overwrite-only import path. This needs an explicit reconciliation pass (3-way classification: identical / conflict / host-only-new / browser-only-needs-push) before the feature can ship — required work, not a stretch goal, and needs dedicated test coverage. `/sdd:3-plan` must scope this explicitly rather than assuming "enable sync" is just "call the existing picker again."
