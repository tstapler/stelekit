# Graph Sections — Pre-Mortem

**Feature:** Graph Sections (graph-namespaces)
**Date:** 2026-06-24
**Status:** Pre-implementation adversarial review

---

## Summary

7 failure modes analyzed. Counts: **P1: 2 | P2: 3 | P3: 2**

Single most critical mitigating action: **Add a dirty-worktree guard that runs BEFORE building `SectionIndex` and deleting DB rows on deactivation — making the entire deactivation operation atomic so data is never removed from disk or DB unless the user has no unsaved changes (P1, Failure Mode 1).**

---

## Failure Mode 1 — User Loses Pages After Section Deactivation

**Priority: P1**

**Incident Report:**
Six months after ship, a user filed a bug titled "All my journals are gone." They had been editing a journal entry in the "personal" section when they opened Section Settings to deactivate "personal" so their work laptop would stop syncing it. The `toggleSection(false)` flow ran in the following sequence: (1) `SectionIndex` was built from DB, (2) `deletePagesInPaths` removed all personal-section rows from the DB, (3) `git sparse-checkout set` was called with the remaining active paths, (4) on the user's next `git pull`, the personal directory was removed from the working tree. The user's in-progress edit was still in `BlockStateManager`'s debounce window — the 500 ms write to disk had not fired yet. After sparse-checkout removed `personal/` from disk, `GraphWriter.saveBlock()` attempted to write to a path that no longer existed, silently failed, and the edit was lost. On re-activation, git materialized the old committed version of the file without the in-progress edit. The user lost approximately 45 minutes of journaling.

The plan (Task 5.2.2) has a dirty-worktree guard for the `git sparse-checkout` step but it only checks `git status --porcelain` — which reports uncommitted file changes already on disk, not changes held in memory by `BlockStateManager` that have not yet been flushed. The in-memory-only edit never appeared in git's status output.

**Mitigation:**
1. Before any deactivation flow begins, drain `BlockStateManager` for all open blocks in the target section (call `GraphWriter.saveBlock()` immediately, bypassing the debounce). This must complete successfully before `deletePagesInPaths` is called.
2. Add a UI confirmation step that names any currently-open pages in the section being deactivated: "You have unsaved changes in 'My Journal' (personal section). Save and deactivate?"
3. Make deactivation transactional: if `git sparse-checkout set` fails after `deletePagesInPaths` has already run, re-index the section immediately to restore DB state rather than leaving the user with empty DB rows but files still on disk.

---

## Failure Mode 2 — WASM Graph Never Loads (Spinner Forever)

**Priority: P2**

**Incident Report:**
A user on a corporate laptop opened the WASM build pointing at a private GitHub repo. The `SectionSyncService.fetchManifest()` call was routed through the company's web proxy, which injected a self-signed TLS certificate. The browser's `fetch()` rejected the request with a CORS/TLS error. The boot sequence (Task 6.3.1) awaited `fetchManifest`, received an exception, checked the `SectionOpfsSyncState` marker (absent — first load), and transitioned to a `NoNetworkAndNoCache` error state. However, the error state composable was wired but never actually shown because the WASM entry-point composable still had `SectionSelectorScreen` as its active screen with `manifest = null` — the state machine did not handle the pre-manifest error path correctly, so the spinner remained.

A second variant: `syncSection()` for a 2,000-page section timed out at 30 s (the default `fetch()` timeout) midway through the parallel file fetches. The `navigator.locks.request` lock was not released because the coroutine was cancelled via structured cancellation but the lock scope was a JS Promise that outlived the Kotlin coroutine. The next reload found the lock held and hung at the mutex acquisition.

**Mitigation:**
1. Add an explicit timeout to `fetchManifest` (recommended: 15 s) and `syncSection` (per-file: 10 s, total: 120 s) with user-visible countdown UI.
2. Add a "Retry" and "Work Offline (from cache)" button that is always visible after 5 s, regardless of the current state.
3. The `navigator.locks.request` callback must release the lock in a `finally` block that survives coroutine cancellation. Add a test that cancels the coroutine mid-sync and verifies the lock is released.
4. Test the full error → "No manifest, no cache" UI path explicitly in a WASM integration test.

---

## Failure Mode 3 — `SectionIndex` Memory Explosion

**Priority: P2**

**Incident Report:**
A power user had defined 50 sections over two years of graph growth, mapping directories like "2021/", "2022/", "2023-q1/" etc. They opened their work laptop, which had `activeSectionSet` configured for only 5 sections. They decided to deactivate all remaining 45 sections one by one via the settings screen. Each deactivation call built a `SectionIndex` via `getPagesBySectionSnapshot` and accumulated it into `GraphManager.sectionIndices: MutableMap<SectionName, SectionIndex>`. With 10,000 pages distributed across 50 sections, each `SectionIndex` held roughly 200 `PageNameEntry(name, sectionName)` pairs — but the accumulation of 45 indices meant 9,000 entries in memory simultaneously. On a 32 GB desktop this was fine. On Android (the feature non-goal says Android is excluded from sparse checkout, but section filtering is not excluded), an OOM was triggered in `GraphManager`'s `CoroutineScope` and was caught by the `CoroutineExceptionHandler` per CLAUDE.md rules — but the UI showed a generic fatal error and the graph closed.

The plan specifies the `PageNameEntry` projection to avoid full `Page` object materialization, but does not cap the total number of accumulated `SectionIndex` entries or bound the map size.

**Mitigation:**
1. Cap `sectionIndices` at a maximum of 20 entries (or a configurable limit). When the cap is exceeded on a new deactivation, evict the oldest index (LRU). Log a warning: tombstone resolution for evicted sections will fall back to "genuinely absent page" behavior.
2. Add a unit test for 50-section accumulation that asserts heap usage stays below a threshold (use `Runtime.getRuntime().totalMemory()` differential in `jvmTest`).
3. In `SectionResolver.resolveTombstone`, add an O(1) early exit: if `sectionIndices.size == 0`, return `null` immediately (fast path for primary-device mode).

---

## Failure Mode 4 — Section Rename Causes Invisible Pages

**Priority: P1**

**Incident Report:**
A user renamed the "tech" section to "technology" via the `SectionFormDialog`. `writeSectionManifest` updated `.stele-sections` on disk with the new name. However, the rename flow in `SectionSettingsViewModel.renameSection()` (not yet written at planning time) had no story for updating `section_name` values in the `pages` DB table. On the next app launch, `GraphManager.addGraph` read the new manifest (sections: `["technology", "personal", "default"]`), but all DB rows still had `section_name = 'tech'`. The `getPagesBySection("technology")` query returned 0 rows. The `SectionFilter` was constructed with the new manifest and correctly included `tech/` paths under `"technology"`, so `GraphLoader` re-indexed tech pages and wrote them with `section_name = 'technology'` — but only for pages that had changed since the last load. Unchanged pages (the vast majority) were skipped by the "already loaded" check in `GraphLoader.reconcileDirectory` and kept their stale `section_name = 'tech'` column value. From the UI, 2,300 of 2,400 tech pages were invisible — they existed in the DB but matched no active section name.

**Mitigation:**
1. Add an explicit rename migration to `GraphManager.renameSection(oldName, newName)`: run `UPDATE pages SET section_name = :newName WHERE section_name = :oldName` via `DatabaseWriteActor` before writing the manifest.
2. The plan's Pattern Decisions table lists `id: String` (stable UUID slug) as the chosen section identity, but the DB column is `section_name TEXT` keyed on the human name. Either (a) add a `section_id TEXT` column to `pages` and key on `GraphSection.id` instead of `.name`, or (b) implement the `renameSection` DB migration and add a regression test `SectionRenameTest` that renames a section and asserts all pages are immediately visible under the new name without re-indexing.
3. `MigrationRunnerSchemaSyncTest` will not catch this because it is a data mutation, not a schema change. A dedicated `SectionRenameTest` in `businessTest` is required.

---

## Failure Mode 5 — Sparse-Checkout Corrupts Git Index (Empty Path List)

**Priority: P3**

**Incident Report:**
A user had a single-section graph where the one non-default section ("tech") contained all their pages, and `paths = ["pages/"]`. They deactivated "tech" — the only non-default active section. The `resolveActivePaths` helper returned only the "default" section, which has `paths = []` (the catch-all). The JVM implementation of `setSectionPaths` translated the empty `paths` list to an empty argument list for `git sparse-checkout set`. Running `git -C $repoRoot sparse-checkout set` with zero path arguments caused git to remove all tracked directories from the working tree, leaving only untracked files and the `.stele-sections` manifest (which is included per FR-4.3 as a special case). The user's next git operation showed a completely empty working tree and they believed their data was deleted. Recovery required `git sparse-checkout disable`.

This scenario is partially mitigated by the "default" section always being active (catch-all), but the catch-all has `paths = []` which does not translate to a valid `git sparse-checkout set` argument.

**Mitigation:**
1. In `JvmSparseCheckoutService.setSectionPaths`, guard: if the resolved path list is empty after translation, do NOT invoke `git sparse-checkout set`. Instead log a warning and return `Either.Left(DomainError.GitError.EmptyPathList(...))`. Surface a UI error: "Cannot deactivate all sections — at least one section must remain active."
2. Add a `SparseCheckoutServiceTest` that asserts zero git invocations when `absolutePaths` is empty after translation.
3. The "default" section catch-all (`paths = []`) needs special handling: when "default" is the only active section and `paths = []`, either (a) expand it to all manifest-declared paths' complement, or (b) disable sparse-checkout entirely (`git sparse-checkout disable`) so all files are materialized.

---

## Failure Mode 6 — `ktoml-core` Drops WASM Support

**Priority: P3**

**Incident Report:**
Eight months after ship, `ktoml-core` released version 0.8.0 with breaking API changes and silently dropped the `wasmJs` artifact from their release. The WASM build's `SectionManifestParser.parseString()` failed at the `Toml.decodeFromString` call with `ClassNotFoundException` at runtime (not at compile time, since the `ktoml-core` common API still compiled). Users on the WASM build saw a crash on every graph load — the manifest parse failure was unhandled and bubbled up through the WASM boot sequence without a fallback. Desktop was unaffected (JVM artifact still present). The team discovered the regression only when a user filed a bug two weeks after deployment.

**Mitigation:**
1. Pin `ktoml-core` to `0.7.1` with an explicit version constraint in `build.gradle.kts` and document in a comment why the pin exists.
2. Add a WASM-specific smoke test (even a stub in `wasmJsTest`) that calls `SectionManifestParser.parseString` with a minimal TOML string and asserts a non-empty manifest. This would catch the missing WASM artifact at CI time.
3. Wrap `SectionManifestParser.parseString` in a try/catch that returns `SectionManifest.DEFAULT` on parse failure, and emits a warning banner: "Could not read section configuration — all sections will be shown." This prevents a hard crash and degrades gracefully.
4. Track `ktoml-core` releases in a dependency audit checklist (quarterly) to catch WASM target drops before they reach production.

---

## Failure Mode 7 — Section Settings Overwritten by Git Pull

**Priority: P2**

**Incident Report:**
A user had two desktops: Home and Office. On Home, they created a new "research" section via `SectionSettingsScreen`, which wrote a new `[[section]]` entry to `.stele-sections` and committed it. Meanwhile on Office, they had also been editing `.stele-sections` directly (FR-1.4 permits this), adding a "fiction" section entry, and committed that change. When Home pulled the remote, git detected a conflict in `.stele-sections`. The user had `pull.rebase = true` in their git config, so git applied the remote change on top of theirs. The auto-merge was not possible (TOML is text, no semantic merge driver), and git generated a conflict-marker file like:

```
<<<<<<< HEAD
[[section]]
name = "research"
...
=======
[[section]]
name = "fiction"
...
>>>>>>> origin/main
```

`SectionManifestParser.parseString` received this conflict-marked content, which is not valid TOML. The parse failed. Because the failure path returned `SectionManifest.DEFAULT` (the mitigation from Failure Mode 6), all section configuration was silently discarded. On the next app launch, all sections appeared as "default," pages lost their section assignments in the UI (the `section_name` DB column still held correct data, but the manifest was gone), and the settings screen showed only the default section. The user did not realize this had happened until they noticed their "research" and "fiction" sections were missing from the sidebar.

**Mitigation:**
1. `SectionManifestParser` should detect git conflict markers (`<<<<<<`, `=======`, `>>>>>>>`) before attempting TOML parse. If detected, return `Either.Left(DomainError.ManifestConflict(...))` and show a UI error: "`.stele-sections` has a git merge conflict — please resolve it in a text editor."
2. Do not fall back to `SectionManifest.DEFAULT` on conflict — that silently destroys configuration. Surface the error explicitly and block section operations until the file is repaired.
3. The plan should document that `.stele-sections` is a user-facing file that will have git conflicts and that SteleKit does not auto-resolve them (consistent with Logseq's behavior for config files).
4. Long-term: consider a TOML merge driver (`gitattributes` + a custom merge tool) as a v2 item.
