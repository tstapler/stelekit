# Pitfalls Research — SteleKit Assets Viewer

## 1. SAF Delete — Already Correct

`PlatformFileSystem.deleteFile()` (Android, line 538) has a complete three-way branch:
- **SAF + MANAGE_EXTERNAL_STORAGE**: resolves to a real path and calls `legacyDeleteFile()` (java.io.File)
- **SAF without direct access**: calls `DocumentsContract.deleteDocument(contentResolver, docUri)` and evicts the entry from `knownExistingFiles`
- **Legacy paths**: calls `legacyDeleteFile()` as before

`AssetEntry.filePath` stores the full saf:// or absolute path — it is the correct argument to `fileSystem.deleteFile()`. Do **not** use `relativePath` for disk operations; that field is for markdown link generation only (`![](<relativePath>)` / `[<filename>](<relativePath>)`).

The shadow cache is also invalidated on SAF delete via `invalidateShadow()`, so no stale-cache pitfall there.

**Action**: call `fileSystem.deleteFile(asset.filePath)` — no special casing needed for SAF.

---

## 2. FileProvider Gap for Non-SAF Assets (Intent.ACTION_VIEW)

`androidApp/src/main/res/xml/file_provider_paths.xml` currently contains:

```xml
<paths>
    <cache-path name="share_export" path="share_export/" />
</paths>
```

This only covers `context.cacheDir/share_export/`. Assets stored on non-SAF graphs live under the Documents directory (`/storage/emulated/0/Documents/...`), which is **not** in the FileProvider path config. Passing a `file://` URI from that location via `Intent.ACTION_VIEW` on API 24+ will throw `FileUriExposedException` and crash.

**SAF-backed graphs are safe**: `PlatformFileSystem.resolveAssetUri()` returns a `content://` DocumentsContract URI for saf:// paths (or a `file://` URI when MANAGE_EXTERNAL_STORAGE is granted and the path resolves to a real path). The DocumentsContract `content://` URI can be passed to `Intent.ACTION_VIEW` directly without FileProvider — Android's ContentResolver handles it.

**Non-SAF legacy graphs** (Documents-dir paths, no SAF): need one of:
- Add `<external-path name="assets" path="." />` to `file_provider_paths.xml` to expose the entire external storage tree via FileProvider
- **OR** copy the file to `cacheDir/share_export/` first, then serve via the existing FileProvider path (mirrors the existing note-export flow, avoids widening the FileProvider scope)

The copy-to-cache approach is safer because it avoids exposing arbitrary paths to other apps. The `PlatformFileOpener` actual (Android) should detect whether `filePath` is a saf:// path and act accordingly:
1. `saf://` → use `resolveAssetUri()` to get a `content://` URI; grant `FLAG_GRANT_READ_URI_PERMISSION`
2. direct path with MANAGE_EXTERNAL_STORAGE → resolve to real path, create FileProvider URI via the cache-copy path, or add an `<external-path>` entry
3. legacy Documents path without MANAGE_EXTERNAL_STORAGE → copy to cache before sharing

---

## 3. OFFSET Pagination Drift

All three paginated SELECT queries in `SteleDatabase.sq` use `ORDER BY imported_at_ms DESC LIMIT :limit OFFSET :offset`:
- `selectAssets`
- `selectAssetsByMediaType`
- `searchAssets`

Two drift scenarios:

**Insert drift**: `AssetIndexService.registerAsset()` runs in the background during graph load. A new asset inserted at `imported_at_ms = now` lands at the front of the `DESC` ordered result set. Page 2 will now return one item that was previously at the end of page 1 (skipped by offset shift) — or miss the last item of each page entirely on deletion.

**Invalidation churn**: SQLDelight invalidates all collectors of `asset_index` on every write to that table. This includes `markAssetMlProcessed`, `updateAssetAutoLabels`, `updatePageUuids`, etc. — all called by the background ML pipeline. A standing `getAssets()` Flow collector in `AssetBrowserViewModel` will re-emit on every ML pipeline write, causing visible list reloads or scroll position jumps during infinite scroll if the offset changes between re-emissions.

**Mitigation options** (pick one):
- **Keyset pagination** (preferred): replace `OFFSET :offset` with `WHERE imported_at_ms < :lastSeenMs ORDER BY imported_at_ms DESC LIMIT :limit` — stable under inserts/deletes at the front
- **Snapshot + tombstone**: load a snapshot on entry, track deletions as tombstones, merge them client-side — avoids re-querying
- **Minimum viable**: accept the drift for this PR (offline background ML is rare during active browsing); document as a known limitation; address in a follow-up

The `countAssets()` query (used for `hasMore` in REQ-5) is a separate SELECT that is also invalidated on every write, so `hasMore` will recalculate correctly after each invalidation.

---

## 4. Coil Loading for Non-SAF `filePath` Values

`SteleKitAssetMapper` only maps strings with `../assets/` prefix.
`SteleKitSafPathMapper` only maps strings with `saf://` prefix.

`AssetEntry.filePath` for non-SAF graphs is an absolute POSIX path like `/storage/emulated/0/Documents/mywiki/assets/photo.jpg`. Coil will not load this as a file URI — it expects either `file:///absolute/path` or a `content://` URI.

When rendering thumbnails in `AssetItemCard`, the `filePath` must be adapted before passing to `AsyncImage`:

```kotlin
val coilModel = when {
    filePath.startsWith("saf://") -> filePath    // SteleKitSafPathMapper handles this
    filePath.startsWith("file://") || filePath.startsWith("content://") -> filePath
    else -> "file://$filePath"                    // wrap bare POSIX path
}
```

Alternatively, call `fileSystem?.resolveLoadableUri(asset.filePath)` (already exists on `FileSystem` interface, returns `content://` or `file://` string) and pass that to `AsyncImage`.

---

## 5. DatabaseWriteActor Required for deleteAsset

`AssetRepository.deleteAsset()` is annotated `@DirectRepositoryWrite` (`@RequiresOptIn(level = ERROR)`). Callers that don't opt in get a compile error. The precedent from `AssetIndexService.registerAsset()` shows the pattern:

```kotlin
val result = if (writeActor != null) {
    writeActor.execute { @OptIn(DirectRepositoryWrite::class) assetRepository.deleteAsset(uuid) }
} else {
    @OptIn(DirectRepositoryWrite::class) assetRepository.deleteAsset(uuid)
}
```

`AssetBrowserViewModel` already imports `DatabaseWriteActor` with `private val writeActor: DatabaseWriteActor? = null` — it just needs this delete routing wired up. The actor is nullable because tests use `InMemoryAssetRepository` without an actor.

---

## 6. No MigrationRunner Entry Needed for New Queries

The new queries required by REQ-4 (`sortedAssets`), REQ-6 (`getDistinctTags`), and REQ-7 (`getOrphanedAssets`) are all SELECT-only — no DDL. The `asset_index` table is already created by the `"asset_index_table"` migration in `MigrationRunner.all`, and all columns needed for these queries (`media_type`, `imported_at_ms`, `file_path`, `size_bytes`, `tags`, `is_orphan`, `page_uuids`) already exist.

CLAUDE.md rule: "Every `CREATE TABLE IF NOT EXISTS <name>` added to `SteleDatabase.sq` must also appear in `MigrationRunner.all`." Since these queries add no new tables, no MigrationRunner entry is needed.

The `sortedAssets` query can be a SQLDelight named query with a runtime sort parameter. SQLDelight does not support dynamic `ORDER BY` natively; the standard approach is to add **separate named queries** per sort order (e.g., `selectAssetsSortedByName`, `selectAssetsSortedBySize`, `selectAssets` for date-default) and dispatch to the right one in `SqlDelightAssetRepository` based on a `SortOrder` enum argument. This keeps the SQL type-safe and avoids string injection.

---

## Summary

| Area | Finding | Severity |
|---|---|---|
| SAF delete | `PlatformFileSystem.deleteFile()` already handles saf:// via `DocumentsContract.deleteDocument()` | No action needed |
| FileProvider for Open In | `file_provider_paths.xml` only covers `cache-path/share_export/` — legacy graph assets need copy-to-cache or path extension | Must fix for non-SAF graphs |
| OFFSET pagination drift | ML pipeline writes invalidate the asset Flow; inserts at head cause page skips | Mitigate with keyset pagination or accept for v1 |
| Coil for bare paths | `filePath` values without `saf://` or `../assets/` prefix need `file://` wrapping before `AsyncImage` | Must fix for non-SAF image thumbnails |
| DatabaseWriteActor | `deleteAsset` is `@DirectRepositoryWrite` — must go through `writeActor.execute { }` | Must fix |
| MigrationRunner | No new DDL — no migration entry needed for any new queries in this PR | No action needed |
