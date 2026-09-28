# Adversarial Review: graph-namespaces

**Date**: 2026-06-24
**Reviewer**: Adversarial Architecture Review
**Verdict**: BLOCKED

---

## Blockers

- [ ] **`ALTER TABLE ... ADD COLUMN` without `IF NOT EXISTS` is not idempotent on SQLite — plan claims otherwise.** Task 3.1.2 states "`ALTER TABLE ... ADD COLUMN` without `IF NOT EXISTS` (SQLite does not support it); `MigrationRunner.applyAll` swallows 'duplicate column name' errors." This is the only documented idempotency guard. The CLAUDE.md `MigrationRunner` rule says _nothing_ about swallowing duplicate-column errors — it says migrations are applied, not retried idempotently. If `MigrationRunner` does NOT in fact swallow that error, every existing user database will crash on the second migration attempt. The plan must (a) verify `MigrationRunner.applyAll` actually catches `SQLiteException("duplicate column name")` and (b) add a test asserting that running the migration twice does not throw. Currently the written test (`SectionNameMigrationTest`) tests the single-apply path only.

- [ ] **`SectionResolver` name-to-path heuristic is fundamentally broken for non-canonical page names.** Task 4.1.1 describes `resolveInactiveSection("My Diary")` → maps to `personal/My-Diary.md` by slugifying. But SteleKit (like Logseq) allows arbitrary page names that do not map to predictable file paths. Pages can live at `personal/my diary.md`, `personal/My Diary.md`, `personal/My-Diary.md`, or `Personal/My Diary.md` depending on OS, editor, and creation method. The resolver can produce false positives (showing a tombstone when the page genuinely does not exist) and false negatives (showing a broken link when the page exists but the path slug didn't match). There is no story for consulting `PageNameIndex` or the DB's `section_name` column to resolve this. **Recommendation:** For pages absent from the DB, the resolver cannot reliably determine which section they would live in from a name alone. The only reliable implementation is: store `section_name` in DB rows, and for absent pages, query the `SectionManifest` path prefixes against the inferred file path using _all known casing variants_. Or restrict tombstones to the set of names that _were_ seen in a previous sync and are now absent (a "known absent" set). The current heuristic is not implementable correctly.

- [ ] **git sparse-checkout on desktop does not handle the case where git is present but the repo has no remote.** FR-4.6 says "if git is not installed or the repo is not a git repo, silently skip." But a local git repo without a remote (common for personal graphs that aren't synced) _is_ a git repo with git installed — the plan will invoke sparse-checkout on it. Sparse-checkout on a local-only repo with cone mode + `git pull` from another client is undefined because there is no remote. The sparse-checkout will succeed, but the user's pages/journals directories will be excluded from their working tree after any `git checkout` or `git reset`. There is no guard for `git remote -v` returning empty. **Recommendation:** Gate sparse-checkout on `gitInfo.hasRemote` not just `gitInfo != null`.

- [ ] **WASM REST API flow has no credential/authentication story for private repos.** FR-5 describes fetching from GitHub/GitLab/Gitea REST APIs. Public repos work unauthenticated. Private repos require a PAT or OAuth token. Neither the requirements nor the plan has _any_ story for: how the user provides a PAT, where it is stored (OPFS? sessionStorage? localStorage?), how it is transmitted (header? query param?), or how it is rotated. This is not a "phase 2" deferral — it is a prerequisite for the feature being useful for the stated privacy use case (keeping personal notes off a work machine implies the repo is private). FR-5 mentions nothing about authentication. **Recommendation:** Add a credential-management story to Epic 6, or explicitly scope Epic 6 to public repos only and document this limitation.

---

## Concerns

- [ ] **Section rename breaks the `section_name` DB column — no migration story exists.** The Pattern Decisions table correctly notes "stable `id` separates human label from identity." However, the DB column is named `section_name TEXT` and the plan stores the section's _name_ (mutable human label), not its _id_ (stable UUID slug). If a user renames "tech" to "technology" in the settings UI, every existing DB row with `section_name = 'tech'` becomes orphaned — `getPagesBySection("technology")` returns zero rows, filter dots disappear, and section-filter queries silently return empty. There is no story for an `UPDATE pages SET section_name = ? WHERE section_name = ?` on rename. **Recommendation:** Either store `section_id` (the stable field) in the DB column instead of `section_name`, or add an explicit rename-reconciliation step to `GraphManager.renameSection`.

- [ ] **`ktoml-core 0.7.1` — maintenance trajectory and WASM breakage risk.** The plan selects `ktoml-core` as "the only KMP lib with confirmed wasmJs support." The pitfalls research flagged this. The library has a small contributor base. Kotlin WASM is still in Beta/RC; `ktoml-core:0.7.1`'s WASM target depends on `kotlinx-serialization` which itself moves fast. There is no lock on the Kotlin/Wasm ABI version in the plan. If `ktoml-core` breaks on a future `kotlinx-serialization` upgrade, the TOML manifest cannot be parsed on WASM — the entire feature fails silently or crashes at boot. **Recommendation:** Add a task to pin `ktoml-core` to a version range, write a WASM-side integration test that parses a real `.stele-sections` string (not just JVM), and document the fallback (hand-rolled minimal TOML parser for the manifest's simple structure, since the TOML is well-bounded).

- [ ] **`SparseCheckoutService` fails open on git errors — pages are not removed from disk.** Task 5.1.2 says if `git` is absent, return `Unit.right()` and proceed. But if `git sparse-checkout set` _fails_ (non-zero exit, e.g., git version < 2.26 which introduced cone mode, or a repo in a broken state), the plan has no error surface. The DB will have deleted the pages (Task 2.3.1 runs first per Task 2.3.2's ordering), but the files remain on disk. The next `GraphLoader` run will re-index them. Result: the user deactivated a section, the DB is cleaned, but the pages come back on next reload. **Recommendation:** Either reverse the operation order (sparse-checkout first, DB delete only on success) or do not delete from DB until sparse-checkout is confirmed — return `Either.Left` and surface the git error before touching the DB.

- [ ] **OPFS sync interrupted mid-write leaves partial data; delta-sync may miss files.** FR-5.3 says the `.stele-sections-sync-complete` marker is written _last_ as an atomic indicator. A crash mid-write leaves the marker absent, so the next load re-fetches. But Task 6.2.1's implementation writes files to their final OPFS paths _before_ the marker. If a partial write leaves stale or corrupt file content (e.g., a 200 KB file partially written), re-fetching from scratch (full re-sync) is correct. However, the delta-sync path (Task 6.2.2) does NOT re-check the marker — it checks `commitSha`. If a crash occurred after some files were written and before the marker, but the SHA comparison happens to match (impossible in practice since the marker is absent, but possible if the marker-write crashed and a partial JSON was left), the delta sync could skip files that are corrupt. **The real concern** is simpler: partial-write corruption is not detected. OPFS writes are not transactional. A file could be half-written. There is no checksum/hash per file. **Recommendation:** Store a per-file hash in `SectionOpfsSyncState` (or use a tree-hash from the GitHub API `sha` field already present in the tree response) to detect partial writes on re-check.

- [ ] **FR-7.1 pages loaded before the manifest exists have no defined section assignment.** The requirements say `Page.section` is populated at load time. The plan (Task 2.2.2) calls `SectionFilter.resolveSection(absolutePath)` during `GraphLoader.parsePageWithoutSaving`. But what happens when: (a) `GraphLoader` starts before `readSectionManifest` completes (race in the boot sequence); (b) a user adds a `.stele-sections` file to a graph that has already been indexed (existing DB rows have `section_name = 'default'` and are never re-assigned)? Case (b) is a real user workflow — they start with no sections, create the file via the Section Settings UI, and existing pages must be re-tagged. There is no re-indexing / backfill story for case (b). **Recommendation:** Add a story for `GraphManager.onManifestChanged()` that triggers a section-name backfill pass over existing DB rows using `UPDATE pages SET section_name = ? WHERE file_path LIKE ?` — one pass per section definition, cheapest-to-most-specific order.

- [ ] **`SyncSectionDialog` (FR-3 tombstone click → "Sync section?" dialog) has no WASM vs. desktop behavioral split.** On desktop, "add section to active set" means updating sparse-checkout (involves git, takes seconds, may require clean working tree). On WASM, it means triggering `SectionSyncService.syncSection` (involves a REST API fetch, takes 10–60 seconds, requires network). Task 4.2.2 calls `GraphManager.toggleSection(graphId, section.name, active = true)` on confirm, but `toggleSection` on WASM will call the WASM `SparseCheckoutService` no-op and presumably also call `SectionSyncService`. The sequencing of these two calls is not specified. More critically: the WASM sync is a long operation — there is no progress reporting plumbed through `SyncSectionDialog`. The user clicks "Confirm" and sees... nothing? The dialog should be async-aware. **Recommendation:** Add a loading/progress state to `SyncSectionDialog` and specify that WASM shows a progress bar (file count from `onProgress` callback in `SectionSyncService.syncSection`), while desktop shows a spinner for the git operation.

- [ ] **`deletePagesInPaths` uses `LIKE` for prefix matching — performance and correctness hazard.** Task 2.3.1 proposes `DELETE FROM pages WHERE file_path LIKE ?` per path prefix. The `LIKE` operator in SQLite is case-insensitive for ASCII by default and does not use the `idx_pages_section_name` index (it uses a full table scan unless the pattern has no leading wildcard). For an 8 000-page graph, this is 8 000 row scans per path prefix. More critically, a prefix match via `LIKE 'tech/%'` is also prone to false-positive matches if path separators differ across platforms (Windows `\` vs. `/`). **Recommendation:** Use the `section_name` column already being added: `DELETE FROM pages WHERE section_name = ?` — one indexed equality query, no LIKE needed. This is both faster and correct.

---

## Minors

- **Open Question 2 (page name collisions) is acknowledged but the v1 restriction is not enforced.** The plan documents "page names must be unique graph-wide" but adds no validation or warning when `SectionManifestParser.writeSectionManifest` is called or when `GraphLoader` detects a collision. Silent duplicates will produce unpredictable `PageRepository.getPageByName` results. Add at minimum a logged warning when two pages with the same name are indexed in different sections.

- **FR-4.3 (`.stele-sections` always in sparse-checkout) has no explicit task.** FR-4.3 says the manifest file itself must always be included. Task 5.1.2 mentions "Always include the repo root itself (so `.stele-sections` is never excluded — cone mode always includes root files)." This is correct for cone mode (which includes all files at repo root), but it is buried in a comment rather than being a tested assertion. If a user later switches to non-cone sparse-checkout externally, the manifest would be excluded. Add an explicit assertion in `JvmSparseCheckoutServiceTest` that the repo root is always in the path set.

- **Estimated page count on `SectionSelectorScreen` (FR-5.6) has no data source.** Task 6.1.3 says "estimated page count (from manifest metadata if present)." The manifest format in FR-1.1 has no `page_count` or `size` field. The GitHub tree API (`?recursive=1`) returns the full file list but this is fetched _after_ section selection, not before. There is a chicken-and-egg problem: the count is shown to help the user decide, but computing it requires the API call. **Recommendation:** Either (a) add optional `estimated_page_count: Int` and `estimated_size_bytes: Long` metadata fields to the manifest that the graph owner can populate, or (b) show "~N pages" estimated from the git tree's `truncated` field and tree-entry count (available from a lightweight, non-recursive tree fetch of the section root directories).

- **`GraphSection.id` generation from `name.slugify()` on first read is not deterministic across callers.** Open Question 1 notes that when an existing manifest without `id` fields is read, ids are generated as `name.slugify()`. If two callers read the manifest simultaneously (e.g., two tabs or two coroutines), both generate the same slug. This is actually fine (deterministic), but if `slugify` produces the same output for two different names (e.g., "Tech Notes" and "tech-notes" both → `"tech-notes"`), the stable id guarantee is broken. Specify the slugify algorithm (kebab-case, lowercase, strip non-alphanumeric except hyphen) and add a collision-check in `SectionManifestParser.parseString`.

- **FR-8.2 "Section view toggle in toolbar" has no implementation task.** FR-8.2 requires a toolbar toggle for merged vs. section-filtered view. No epic, story, or task covers this. Either add it to Epic 7 or explicitly mark it as deferred.

- **Rate limiting on GitHub REST API is unaddressed.** Epic 6 fetches individual file contents in parallel (10 concurrent). GitHub's unauthenticated rate limit is 60 req/hour; authenticated is 5000 req/hour. A 500-page section requires 500+ raw file fetches plus 1 tree fetch + 1 HEAD SHA fetch. At 60 req/hour unauthenticated, this would take 8+ hours. Even authenticated (5000/hour), 500 files take ~6 minutes at max rate. There is no backoff, retry, or rate-limit-aware batching strategy. **Recommendation:** Use the GitHub raw tarball download (`GET /repos/{owner}/{repo}/tarball/{ref}`) filtered to section paths as a fallback when file-count exceeds a threshold (e.g., 50 files), to reduce API calls to O(1) per section.

- **`SectionOpfsSyncState` commit SHA comparison assumes the remote HEAD is a branch, not a tag or detached SHA.** Task 6.2.1 checks "commitSha matches remote HEAD." The GitHub REST endpoint for HEAD SHA is `GET /repos/{owner}/{repo}/git/refs/heads/{branch}`. If the WASM session was opened with a tag or a specific SHA (possible if `remoteUrl` includes a `#ref` fragment), the HEAD comparison would always differ and trigger a full re-sync on every load. The plan should specify how `branch` is determined and what happens when the user's remote URL does not specify a branch.

---

## Re-Review — 2026-06-24 (Patch Verification)

**Reviewer**: Adversarial Architecture Review (second pass)

### Blocker Resolution Status

#### Blocker 1 — Migration idempotency (RESOLVED)

The false "swallows duplicate column errors" claim is removed in both the Migration Plan section and Task 3.1.2. The correct version-number guard is now the documented idempotency mechanism. Task 3.1.4 now has two scenarios: single-apply and double-apply (the latter asserts no exception on second `MigrationRunner.applyAll` call with the same DB). `MigrationRunnerSchemaSyncTest` interaction: that test checks for `CREATE TABLE IF NOT EXISTS` table names in `MigrationRunner.all`; the new migration adds a column, not a table — the test will not catch a missing column migration. However, the plan correctly documents the manual migration entry requirement, so this is acceptable. **Blocker 1 is resolved.**

#### Blocker 2 — SectionResolver name-to-path heuristic (RESOLVED)

`SectionIndex` is defined in the glossary and implemented across Tasks 4.1.1–4.1.3. The approach is correct: the index is built from DB rows (via `getPagesBySectionSnapshot`) **before** deletion, keyed by exact page name, with no slugification or path reconstruction. `SectionResolver.resolveTombstone` performs an exact map lookup. **Blocker 2 is resolved.**

#### Blocker 3 — Sparse-checkout on local-only repos (RESOLVED)

Task 5.2.1 now gates sparse-checkout on `gitInfo != null AND gitInfo.hasRemote == true`. The fallback (in-memory `SectionFilter` only, plus a non-blocking info banner) is specified. The `hasRemote` check runs `git remote get-url origin` and stores the result in `GraphInfo` at graph-open time. **Blocker 3 is resolved.**

#### Blocker 4 — WASM authentication story (RESOLVED)

Story 6.4 adds full credential management: `RepoCredential` sealed class (Task 6.4.1), PAT header injection + `CredentialRequired` error on 401/403 (Task 6.4.2), PAT dialog + `sessionStorage` storage + retry (Task 6.4.3), PAT prompt inline in `SectionSelectorScreen` (Task 6.4.4), and a test covering the 401→dialog→retry flow (Task 6.4.5). Security model is documented: PAT in `sessionStorage` only, never OPFS, never `localStorage`, cleared on tab close. **Blocker 4 is resolved.**

---

### New Issues Introduced by Patches

#### NEW BLOCKER: `hasRemote` subprocess at graph-open time may block on the wrong dispatcher

Task 5.2.1 specifies that `fun hasRemote(repoRoot: String): Boolean` runs `git remote get-url origin` via `ProcessBuilder` at graph-open time and stores the result in `GraphInfo`. Task 5.1.2 specifies that all `ProcessBuilder` calls must be wrapped in `withContext(PlatformDispatcher.IO)`. However, `hasRemote` is described as a method on `JvmSparseCheckoutService` called during graph-open (in `GraphManager`), and `GraphManager` methods are typically called from the UI scope or `StelekitViewModel`. There is no explicit instruction in Task 5.2.1 to wrap the `hasRemote` call in `withContext(IO)` at the call site in `GraphManager`. If an implementer calls `hasRemote(repoRoot)` without the dispatcher switch, the subprocess blocks the calling coroutine on whatever dispatcher `GraphManager` is running on — typically `Dispatchers.Default` or even the main thread if called from a composable launch. This will cause ANR-class hangs on Android and UI freezes on desktop.

**Required fix:** Either (a) declare `hasRemote` as `suspend fun hasRemote(repoRoot: String): Boolean` and call `withContext(PlatformDispatcher.IO)` inside the implementation (not at the call site), or (b) add an explicit note to Task 5.2.1 that `GraphManager` must call `hasRemote` inside a `withContext(PlatformDispatcher.IO)` block. Given CLAUDE.md's dispatcher matrix (non-database IO → `PlatformDispatcher.IO`), option (a) is strongly preferred.

#### NEW CONCERN: `SectionIndex` is bounded per operation but persisted in `GraphManager` — may grow O(graph) across multiple deactivations

The Domain Glossary defines `SectionIndex` as `data class SectionIndex(val entries: Map<PageName, GraphSection>)` stored as `GraphManager.sectionIndex: SectionIndex`. Task 4.1.2 says "store this index in `GraphManager.sectionIndex`… replaces any prior index." Task 4.1.3 says "clear `sectionIndex` when `activeSectionSet == null`."

Single-deactivation case is fine: the index covers only the pages being deactivated. But the plan does not address sequential deactivation: if a user deactivates "personal" (500 pages → index built, pages deleted, index persists as `sectionIndex`), then deactivates "finance" (200 pages → new index built, replaces `sectionIndex`), the previous "personal" entries are discarded. This means after the second deactivation, `SectionResolver` cannot resolve tombstones for "personal" pages — it will only have the "finance" index. A `[[My Diary]]` link on a tech page would return `null` (broken link) instead of a "personal" tombstone.

**The root design tension:** `SectionIndex` is needed to resolve tombstones for ALL inactive sections, not just the most-recently-deactivated one. If stored as a single `SectionIndex` (replacing on each deactivation), it only covers the last deactivated section. If stored as an accumulated `Map<SectionName, SectionIndex>` (one per inactive section), it could grow O(graph) if many sections are deactivated.

**Required fix:** Either (a) make `GraphManager.sectionIndex` an `Map<String, SectionIndex>` (keyed by section name) with each deactivation adding an entry and re-activation removing it — document the memory bound as O(inactive-section-pages, not O(graph)), or (b) on `SectionResolver.resolveTombstone`, fall back to a DB query if the page name is not in `sectionIndex` (query `section_name` from a soft-deleted or audit table). Option (a) is simpler and the memory is bounded by the number of inactive pages, which by definition have been removed from the working set.

This is a BLOCKER: tombstone resolution is non-functional for all but the most-recently-deactivated section under the current single-`SectionIndex` design.

#### NEW CONCERN: PAT stored in `sessionStorage` with a predictable key is vulnerable to XSS

Task 6.4.3 stores the PAT under key `stele-pat-<urlSafeRemote>`. `sessionStorage` is origin-scoped and tab-scoped — the right choice. However, the `urlSafeRemote` key suffix is derived from the remote URL, which is user-controlled input. If the URL sanitization is insufficient, the key could contain characters that cause issues in `sessionStorage.getItem()` / `sessionStorage.setItem()` calls, or could be guessed by a script injected into the page. More critically: if the WASM app ever logs `sessionStorage` keys or values (e.g., for debugging), the PAT would appear in logs. The plan has no explicit prohibition on logging credential values.

**Required fix:** Add to Task 6.4.3 an explicit constraint: "The PAT value MUST NOT appear in any log, error message, or `DomainError` message. Any exception caught during the `sessionStorage` read/write must use a sentinel message ('credential storage error') without echoing the token value." This is a low-effort addition that prevents accidental token leakage in debug logs. Not a blocker, but a security hygiene requirement.

#### NEW CONCERN: `getPagesBySectionSnapshot` introduces a one-shot unbounded read

Task 4.1.2 introduces `pageRepository.getPagesBySectionSnapshot(sectionName): List<Page>` — described as "not a `Flow`, since we need a one-shot read before deletion." This is a `suspend` method returning `List<Page>`. For a section with 500 pages, this materializes 500 `Page` objects (including all their fields: content, properties, etc.) into memory in one call. CLAUDE.md prohibits "unbounded in-memory collections" and explicitly states `getAllPagesSnapshot()` must page through `getPages(limit, offset)` in bounded batches.

If the "personal" section has 2000 pages on a large graph, `getPagesBySectionSnapshot` would allocate all 2000 `Page` objects at once — potentially 5–20 MB of heap on mobile. On Android under heap pressure, this is an OOM risk.

**Required fix:** Either (a) implement `getPagesBySectionSnapshot(sectionName, limit, offset)` and call it in a drain loop (same pattern as `getAllPagesSnapshot()`), or (b) use a projection-only query that returns `Map<PageName, SectionName>` (name + section only, no block content) rather than full `Page` objects. Option (b) is far more efficient and still provides all data needed to build `SectionIndex`.

#### Concern from original review — SparseCheckoutService fails open on git errors (STILL UNRESOLVED)

The original Concerns section raised: if `git sparse-checkout set` fails (non-zero exit), the DB has already deleted the pages (Task 2.3.1 runs before Task 2.3.2's sparse-checkout call), and `GraphLoader` will re-index them on next load, silently restoring the deactivated section. The patch for Blocker 3 adds a `hasRemote` guard and a dirty-worktree guard (Task 5.2.2), but neither addresses the operation-ordering hazard when sparse-checkout itself fails despite having a remote and a clean worktree (e.g., git version < 2.26 lacking cone mode, or a git repo in a broken state). The concern's recommended fix (reverse operation order: sparse-checkout first, DB delete only on success) is not implemented. This concern remains open.

---

### Revised Verdict

**BLOCKED** — two new blockers introduced by patches:

1. **`hasRemote` subprocess dispatcher not specified at `GraphManager` call site** — risk of blocking the default dispatcher or main thread at graph-open time. Fix: declare `hasRemote` as `suspend` with internal `withContext(IO)`.

2. **`SectionIndex` single-instance design breaks tombstone resolution for all but the most-recently-deactivated section** — after two sequential deactivations, the first section's tombstones resolve as broken links. Fix: accumulate `Map<SectionName, SectionIndex>` across deactivations, clear entry on re-activation.

**Prior concern still open (not a new issue, promoted to tracked):**

- **SparseCheckoutService fails open on git errors** — DB pages deleted before sparse-checkout confirmed; re-index silently restores them. Reverse operation order or add compensating re-insert on sparse-checkout failure.

**Security hygiene (not blocking):**

- PAT must not appear in logs or error messages — add explicit prohibition to Task 6.4.3.
- `getPagesBySectionSnapshot` must use a bounded projection query, not a full `List<Page>` materialization — prevents OOM on large sections on Android.

---

## Final Re-Review — 2026-06-24 (Round 2 Patch Verification)

**Reviewer**: Adversarial Architecture Review (third pass)

### Round 2 Blocker Verification

#### Round 2 Blocker A — `hasRemote` subprocess dispatcher (RESOLVED)

`SparseCheckoutService` interface (Task 5.1.1) now declares `suspend fun hasRemote(repoRoot: Path): Boolean`. Task 5.1.2 mandates `withContext(PlatformDispatcher.IO)` inside the method body and provides the exact implementation sketch: `override suspend fun hasRemote(repoRoot: Path): Boolean = withContext(PlatformDispatcher.IO) { ProcessBuilder(...).start().waitFor() == 0 }`. Non-JVM actuals return `false` unconditionally via `override suspend fun hasRemote(repoRoot: Path) = false`. Task 5.2.1 repeats the internal-dispatcher requirement explicitly and states the ANR rationale. The fix is structurally enforced: the `suspend` signature forces the caller to await rather than block. **Round 2 Blocker A is resolved.**

#### Round 2 Blocker B — `SectionIndex` accumulation across deactivations (RESOLVED)

The Domain Glossary now declares `GraphManager.sectionIndices: MutableMap<SectionName, SectionIndex>` (not a single `SectionIndex`). Task 4.1.1 initializes it as `private val sectionIndices: MutableMap<SectionName, SectionIndex> = mutableMapOf()`. Task 4.1.2 specifies: deactivation adds `sectionIndices[sectionName] = index`; re-activation calls `sectionIndices.remove(sectionName)`; full-graph load calls `sectionIndices.clear()`. Task 4.1.3 iterates `sectionIndices.values` across all inactive sections, not just the most-recently-deactivated one. A sequential-deactivation test scenario is present (deactivate "personal" then "finance"; assert `resolveTombstone("My Diary")` returns the "personal" tombstone — not `null`). Additionally, `getPagesBySectionSnapshot` is now a projection-only query (`SELECT name, section_name FROM pages WHERE section_name = ?`) returning `List<PageNameEntry>(val name: String, val sectionName: String)` — no full `Page` object materialization, eliminating the OOM risk on Android flagged as a concern in the second pass. **Round 2 Blocker B is resolved.**

### New Issue Scan

**Map growth unbounded?** No. The map has one entry per currently-inactive section. On re-activation the entry is removed; on full-graph load the map is cleared. Total size is bounded by the number of sections in the manifest — a user-defined, small number (O(10)). No unbounded growth.

**`PageNameEntry` projection sufficient for `SectionResolver`?** Yes. `SectionResolver` needs: (1) the page name to key into `SectionIndex.entries`; (2) a `GraphSection` as the value. `PageNameEntry` provides `name` and `sectionName`; at index-build time the `GraphSection` is resolved from the manifest by `sectionName` and stored as the map value in `SectionIndex.entries: Map<PageName, GraphSection>`. The projection carries exactly the data needed — no over-fetching, no under-fetching.

**`LruCache` invalidation correctness?** Task 4.1.3 specifies the cache is invalidated on any `sectionIndices` mutation (add or remove). This is correct and prevents stale tombstone results after a re-activation.

**No new blockers identified.**

### Prior Concern Status

- **SparseCheckoutService fails open on git errors (operation ordering hazard)** — still unresolved. The dirty-worktree guard (Task 5.2.2) and `hasRemote` guard (Task 5.2.1) do not address the case where sparse-checkout fails despite a clean worktree and a configured remote (e.g., git < 2.26 lacking cone mode). DB pages are deleted before sparse-checkout is confirmed; a failed sparse-checkout leaves the DB cleaned but files on disk, causing re-indexing to silently restore the deactivated section. This is a pre-existing concern from the original review, not introduced by these patches. It remains tracked but is not a blocker for plan approval.

### Final Verdict

**CONCERNS** — 0 blockers remaining. All 6 total blockers (4 original + 2 from Round 2) are resolved. One pre-existing concern (SparseCheckoutService fails-open on git errors) remains tracked and unresolved but does not block implementation. The plan is cleared for implementation with the operation-ordering concern flagged for the implementer.
