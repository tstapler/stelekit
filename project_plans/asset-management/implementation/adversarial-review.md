# Adversarial Review: asset-management (Pass 3)

**Date**: 2026-06-13
**Verdict**: CONCERNS

---

## Blockers

(none)

All Pass 2 blockers confirmed resolved:

- [x] **`ml_tags_source` column now present in DDL** — Task 1.1.1a (line 124) column list explicitly includes `ml_tags_source TEXT NOT NULL DEFAULT 'NONE'` with enumerated values `'NONE'`, `'LOCAL'`, `'CLOUD_VISION'`, `'CLAUDE'`. RESOLVED.
- [x] **`updateAssetMlTagsSource(...)` stub now present** — Task 1.1.1b (line 135) `@DirectSqlWrite` stub list includes `updateAssetMlTagsSource(...)`. RESOLVED.

---

## Resolved Blockers (pass 1 + pass 2, all confirmed)

- [x] **B1 WAL replay idempotency** — 4-case decision tree present in Task 1.3.3b; write-actor serialization specified. RESOLVED.
- [x] **B2 Backfill drain infinite-loop** — `attemptedUuids` set + `ml_failed` column + `selectUnprocessedAssets` filter all present. RESOLVED.
- [x] **B3 Okio `listRecursively()` in commonMain** — Task 1.3.2a redirects to `platform.FileSystem`. RESOLVED.
- [x] **B4 `rewriteAssetReference` and file watcher** — Task 1.3.3c specifies `onPreWrite` / `onClearPendingWrite` hooks. RESOLVED.
- [x] **B5 `pending_asset_moves` `@DirectSqlWrite` stubs** — Task 1.1.2a lists stubs for `insertPendingMove` and `deletePendingMove`. RESOLVED.
- [x] **B6 `asset_index` `@DirectSqlWrite` stubs** — Task 1.1.1b lists all 9 mutating stubs. RESOLVED.

---

## Resolved Concerns (pass 1 + pass 2, confirmed fixed this pass)

- [x] **C2: `AssetBrowserViewModel` scope lifecycle** — Task 1.4.1b (line 376) now specifies implementing `RememberObserver` interface with `onForgotten`/`onAbandoned` → `scope.cancel()`, plus KDoc guidance on `remember { }`. RESOLVED.
- [x] **C4: MimeTypeDetectorTest truncated-file edge cases** — Task 1.5.1b (lines 474–479) now explicitly lists 0-byte, 4-byte, 7-byte, and "shorter than MP4 ftyp pattern" test cases, and mandates bounds-checking in `MimeTypeDetector.detect()`. RESOLVED.
- [x] **C6: Plugin registry wiring at platform entry points** — Task 2.3.1c (lines 682–689) now explicitly specifies Android `SteleKitApplication.onCreate()`, JVM `Main.kt`, and iOS `AppDelegate`/`@main` as construction sites. `RepositoryFactory` receives an already-constructed `PluginRegistry` as a constructor parameter. RESOLVED.
- [x] **C1: Cloud cost counter idempotency** — `ml_tags_source` column is now in schema; design statement is backed by the DDL. RESOLVED.
- [x] **C3: Backfill chunk size bounded for Android API < 30** — batch ≤50 specified. RESOLVED.
- [x] **C5: `selectUnprocessedAssets` drain termination** — dual-layer protection (`ml_failed` filter + `attemptedUuids`) present. RESOLVED.

---

## Concerns

- [ ] **WAL case 3 (both files exist) is log-only — stuck row surfaces no user signal** — Task 1.3.3b correctly skips with a log warning and retains the WAL row indefinitely. This row will re-appear on every graph open and trigger the same silent warning forever. Users never learn that a move is in an unresolvable state. The concern from Pass 2 (C7 partial) has been addressed by specifying the log, but not by providing a user-facing recovery path. Recommendation: either (a) emit a `DomainError` from `replayPendingMoves()` that surfaces in the UI as a non-fatal warning banner ("Asset move conflict detected — manual resolution required for: \<filename\>"), or (b) add reasoning in the plan explaining why case 3 is structurally impossible under the current move protocol (WAL insert always precedes the rename, so if both files exist the old path can only be a pre-move artifact and case 2 applies), allowing case 3 to be collapsed into case 2 safely. As written, the stuck-row scenario is plausible under external filesystem manipulation or a future code change that reorders the saga steps.

- [ ] **`AssetBrowserViewModel` constructor takes `DatabaseWriteActor?` (nullable) — silent no-ops on null** — Task 1.4.1b specifies `writeActor: DatabaseWriteActor?` as nullable. Every ViewModel method that writes (move, delete, tag edit, group creation) must null-check this parameter and will silently do nothing if the actor is unavailable (e.g., DB not yet open). This is invisible to the user and untestable without an explicit contract for the null case. Recommendation: make `writeActor` non-null and guard at the `App.kt` call site before constructing the ViewModel (only render `AssetBrowserScreen` when a valid write actor is available), or keep it nullable but document and test the "no write actor" degraded state explicitly (read-only mode with disabled action buttons).

---

## Minors

- **`AssetEntry` model is missing `mlTagsSource` field** — The DDL now has `ml_tags_source TEXT NOT NULL DEFAULT 'NONE'`, but Task 1.1.3a's `AssetEntry` field list (line 177) does not include this column. The `toModel()` extension in `SqlDelightAssetRepository` will fail to compile if the field is absent from the domain model. Add `mlTagsSource: String` (or `mlTagsSource: MlTagsSource` as a typed enum) to `AssetEntry`.

- **`AssetEntry` model is missing `mlFailed` field** — Similarly, Task 1.1.3a's field list omits `mlFailed: Boolean` even though `ml_failed INTEGER NOT NULL DEFAULT 0` is in the DDL and `selectUnprocessedAssets` filters on it. Add the field.

- **Task 2.5.1b's drain loop spec is inconsistent with Task 2.1.2a** — Task 2.5.1b says "call `getUnprocessedAssets(limit=10, offset=0)` in a loop" without mentioning `attemptedUuids`. An implementer reading only 2.5.1b will write the authoritative loop incorrectly. Cross-reference or consolidate: 2.5.1b should state "see Task 2.1.2a for the full drain spec including the `attemptedUuids` guard".

- **`remember` key missing in `App.kt` ViewModel construction** — Task 1.4.4a creates `AssetBrowserViewModel` as `remember { AssetBrowserViewModel(repoSet.assetRepository, ...) }` with no key argument. On graph switch, `repoSet` changes but `remember` without a key will not recreate the ViewModel. The old ViewModel will silently observe the wrong (possibly closed) repository. Fix: use `remember(graphId) { ... }` so the ViewModel is recreated on graph switch.

- **`CloudClaudePlugin` idempotency guard not specified in Task 2.4.2b** — Task 2.4.2b's implementation steps do not mention checking `asset.mlTagsSource` before making the API call. Task 2.4.2 acceptance criteria reference the guard, but the task body does not. Ensure the implementation checks `asset.mlTagsSource != "NONE"` (or specifically `asset.mlTagsSource == "CLAUDE"` to skip re-processing by the same provider) before calling the Anthropic API.

- **`PluginRegistry.all` concurrency** — `PluginRegistry` uses a `mutableListOf<AssetPipelinePlugin>()` with no thread-safety. Registration is single-threaded (startup), but `all` is read concurrently from `Dispatchers.Default` coroutines. A `@GuardedBy` comment or a read-only snapshot property (`val all get() = plugins.toList()`) is sufficient; the current spec already uses `plugins.toList()` (line 551), which provides a safe snapshot. Add a comment noting the threading contract so implementors do not refactor toward direct list iteration under the assumption of single-threaded access.

- **PDFBox-Android API 26 desugaring not in any task** — `coreLibraryDesugaring` must be added to `kmp/build.gradle.kts` for `com.tom-roush:pdfbox-android` on API < 26. No story covers this build change. Add it to Task 2.2.2b or the dependency task.

- **`AndroidPdfTextExtractor` has no processing timeout** — A large PDF with complex pages processed via OCR under `limitedParallelism(1)` can hang indefinitely. Add a `withTimeout(30_000)` guard in `AssetPipelineService.processAsset()` wrapping each plugin call, or specify it in Task 2.2.2b.

- **`tags` and `auto_labels` stored as JSON text** — `searchAssetsByTag` will use `LIKE '%"tag"%'` which is unindexed and O(N). Acceptable for this sprint assuming < 10 000 assets per graph. Track as a follow-up.

---

## Pass History

| Pass | Verdict | Blockers | Concerns |
|------|---------|----------|----------|
| 1 | BLOCKED | 6 | 7 |
| 2 | BLOCKED | 1 | 4 (1 partial) |
| 3 | CONCERNS | 0 | 2 |
