# Agent 2 — Cache Invalidation Techniques Research

## Subject
State-of-the-art approaches for cache invalidation without mtime: content hashing algorithms,
write-epoch/generation counters, event-driven dirty sets, ETag-style tokens. SQLite WAL and
built-in change detection. Overhead on 1–50KB Logseq pages at navigation time.

---

## 1. Content Hashing

### Algorithm comparison for 1–50KB files

| Algorithm | Speed (JVM, ~50KB) | Collision prob | Cryptographic? | Library needed |
|---|---|---|---|---|
| `String.hashCode()` | ~0.05ms (built-in) | 1 in 2^32 (~1/4B) | No | None |
| CRC32 | ~0.1ms | 1 in 2^32 | No | `java.util.zip.CRC32` |
| xxHash32/64 | ~0.05ms | 1 in 2^32 / 2^64 | No | External (lz4-java or xxhash4j) |
| MD5 | ~0.5ms | 1 in 2^128 | Broken, not needed | `java.security.MessageDigest` |
| SHA-256 | ~1–5ms | 1 in 2^256 | Yes | `java.security.MessageDigest` |

**Key benchmark finding**: xxHash64 is ~4-6x faster than SHA-256 and ~5-10x faster than MD5 on the
JVM. For non-cryptographic use (change detection), xxHash32 or CRC32 provides adequate collision
resistance (1/4B false-negative probability per comparison).

**For SteleKit's use case** (1–50KB Logseq markdown pages, navigation-time check):
- `String.hashCode()` is already in use in `FileRegistry` and is essentially free — it runs on the
  already-in-memory string, no extra I/O.
- For a hash of on-disk content before loading into memory, CRC32 from `java.util.zip` requires no
  new dependency and completes in <0.1ms on a 50KB file.
- The cost of *reading* the file from SAF (Binder IPC, ~10–30ms per file) dominates any hashing
  overhead. Hashing itself is negligible.

### False-negative (missed change) probability
Using `String.hashCode()` (32-bit): 1/4,294,967,296 ≈ 2.3×10⁻¹⁰ per comparison. For a graph with
1,000 pages checked daily over a year (~365,000 checks), the expected false-negative count is
~0.000085 — effectively zero. This is the accepted trade-off already documented in `FileRegistry`
comments.

### Current SteleKit implementation
`FileRegistry` already stores 32-bit `contentHashes` (using `String.hashCode()`) and uses them as
a content-based guard in `detectChanges()` to suppress false positives from own-writes. The hash
is computed on the already-read plaintext string — zero extra I/O cost.

---

## 2. Write-Epoch / Generation Counters

### The pattern
Instead of comparing timestamps, maintain a monotonically-increasing integer ("epoch" or "generation")
that is incremented on every write. The cache entry is tagged with the epoch at write time. A cache
miss is a mismatch between the stored epoch and the current epoch.

### SQLite `PRAGMA user_version`
SQLite's `user_version` is a 32-bit integer in the database header (offset 60), readable and
writable via `PRAGMA user_version = N`. It can serve as an application-managed generation counter:
increment it on every page save, read it at navigation time to detect whether any DB write happened
since the last navigation.

**Limitations**:
- `user_version` is a single global counter — it cannot track per-file staleness; any write to any
  page increments it.
- Incrementing it requires a write transaction (even if only the header changes), adding
  contention with the DatabaseWriteActor.
- Does not help with *external* file changes that bypass the DB entirely.

### SQLite `PRAGMA data_version`
`PRAGMA data_version` returns a per-connection integer that changes whenever another connection
commits a write. It is useful for detecting that *another process or connection* modified the DB.
In SteleKit's single-process model this is less useful, but it could detect changes from a future
background sync process.

**Key limitation**: `data_version` reflects DB changes, not file changes. It cannot detect that an
external app (Logseq desktop, Syncthing) changed a `.md` file on disk without going through
SteleKit's DB.

### App-managed generation counter in SQLite
An alternative is a `file_generations` table:
```sql
CREATE TABLE file_generations (file_path TEXT PRIMARY KEY, generation INTEGER NOT NULL);
```
On each save, `UPDATE file_generations SET generation = generation + 1 WHERE file_path = ?`.
At navigation time, compare `SELECT generation FROM file_generations WHERE file_path = ?` against
the generation stored in the page's in-memory record.

**Pros**: Per-file granularity, no mtime dependency, survives process kill (persisted in DB).
**Cons**: Requires two DB round-trips per navigation (read generation + compare), adds schema
complexity, and still does not detect *external* writes that skip the DB.

---

## 3. Event-Driven Dirty Sets

### The pattern
Maintain an in-memory `Set<String>` of file paths that are "dirty" (known to have changed since
last loaded). Pages in the dirty set are unconditionally re-read on next navigation; pages not in
the set use the cached content.

**Dirty set is populated by**:
- The file watcher (on external change detection)
- The `activePageUuids` guard (pages being edited are never put in the dirty set while editing)

**Dirty set is cleared by**:
- Successful `parseAndSavePage` (page is now fresh)

### Failure mode: process kill
On Android, the OS can kill the process at any time, discarding the in-memory dirty set. After
process restart, no pages are in the dirty set, so the stale-read problem recurs unless there is a
persistent fallback.

**Mitigation**: Combine dirty set with the `invalidateStaleShadow` cold-start purge. On cold start,
the shadow is purged, so all pages will be read from SAF on first access — effectively a full
dirty-set reset. The dirty set is only needed as a warm-path optimization.

### Current SteleKit state
SteleKit does not currently maintain an explicit dirty set. The `FileRegistry` serves a similar
role for the watcher: it tracks which files have changed via mtime + content-hash comparison. The
missing piece is using that information proactively at navigation time (in `loadFullPage`), rather
than only reactively in the watcher's polling loop.

---

## 4. ETag-Style Content Tokens

ETag (HTTP) is a server-assigned opaque token that changes when content changes. The equivalent in
a local file system is a content-addressable token stored alongside the file or in a sidecar.

### Options
1. **Store last-loaded content hash in the DB** (`Page.contentHash` column): At navigation, compare
   stored hash against current on-disk content hash. Requires reading the file before deciding
   whether to parse it — net cost is the SAF read (~10–30ms) plus hashing (~0.1ms).
2. **Sidecar file with content hash**: SteleKit already has a `SidecarManager`. Adding a hash to
   the sidecar avoids a DB schema change but introduces an extra file read per navigation.
3. **Hash in SQLite as a separate query**: A lightweight table `(page_uuid, content_hash)` avoids
   polluting the `Page` model.

### Practical constraint
For any of these to avoid the SAF file read, the hash must be computable without reading the file.
That is only possible if:
- The hash was stored at last-write time (we hashed it when we wrote it or last read it), AND
- External writes are detected before navigation arrives (via the watcher)

If external writes are detected lazily (at navigation time), a file read is unavoidable regardless
of technique. The question is whether the read triggers a full re-parse (expensive) or just a hash
comparison (cheap but still requires the SAF IPC).

---

## 5. SQLite WAL Mode and Change Detection

SteleKit already uses SQLite WAL mode (standard in SQLDelight 2.x on Android via the JDBC driver).
WAL mode does not provide a per-file-on-disk change detection mechanism for markdown files — it is
a DB-internal mechanism for concurrent read/write throughput. It does not help with the external
file change detection problem.

**WAL checkpoint** is called via `onBulkImportComplete` in GraphLoader after bulk imports. This
flushes WAL pages to the main DB file, which is relevant for the `PRAGMA data_version` counter
(WAL writes are visible to other connections without waiting for checkpoint), but does not address
the markdown file staleness problem.

---

## 6. Overhead Analysis at Navigation Time

**Current navigation path** (when page is cached and not force-reloaded):
1. `pageRepository.getPageByUuid()` → 1 DB query (~1ms)
2. `blockRepository.getBlocksForPage()` → 1 DB query (~1ms)
3. `fileSystem.getLastModifiedTime()` → 1 SAF Binder IPC (~5–15ms on non-privileged SAF path)
4. Guard check → 0ms

Total: ~7–17ms for the mtime guard alone (the IPC dominates).

**Proposed content-hash guard at navigation time**:
- If hash is already stored in `FileRegistry` and file watcher has not flagged the file as changed:
  skip re-read entirely (0ms additional cost).
- If watcher flagged file as dirty: must read file (~10–30ms SAF IPC) + hash (~0.1ms) + re-parse
  (~5–20ms depending on page size). Total: ~15–50ms.
- If no watcher (iOS/WASM): must read file at navigation time to hash it — adds 10–30ms per navigation.

**Target**: < 100ms added latency per navigation (requirement). Content-hash approach comfortably
meets this on the happy path (watcher is running). On iOS/WASM without a watcher, the unavoidable
file read adds ~10–30ms, still within budget.

---

## 7. Recommended Approach for SteleKit

Based on the analysis, a **hybrid layered approach** is optimal:

### Layer 1: Watcher-driven dirty set (warm path, fast)
- `GraphFileWatcher` already detects external changes and calls `onReloadFile`.
- Add an explicit `dirtyPaths: Set<String>` (or reuse `FileRegistry` change detection) to track
  externally-changed files.
- At navigation time in `loadFullPage`: if the page's `filePath` is in `dirtyPaths`, skip the
  mtime guard and force a re-read.

### Layer 2: Content hash guard (replaces mtime guard for watcher-unavailable platforms)
- At navigation time, if watcher is absent (iOS/WASM) or if the dirty set is cold (post-kill):
  read the file, compute `content.hashCode()` (same 32-bit hash `FileRegistry` already stores),
  compare against stored hash. If different → re-parse. If same → serve from cache.
- Net cost: 1 SAF read + 0.05ms hash, but avoids full re-parse on no-change case.

### Layer 3: Cold-start shadow purge (already implemented)
- `invalidateStaleShadow` on first cold start ensures that cloud-stale shadow files are discarded.
- After purge, the next read of each file goes to SAF, providing guaranteed freshness at startup.

### What NOT to do
- **Do not add a `file_generations` table** unless per-file external-change detection at DB level
  becomes necessary — the complexity is not justified given the watcher + dirty-set approach.
- **Do not use SHA-256** at navigation time — CRC32 or `String.hashCode()` is sufficient and
  avoids any new dependency.
- **Do not use `PRAGMA data_version`** as the primary mechanism — it detects DB changes, not
  on-disk file changes.

---

## Sources

- [xxHash benchmark — lz4.github.io](https://lz4.github.io/lz4-java/1.3.0/xxhash-benchmark/)
- [Java Hashing Benchmark — bp-alex/hash-bench](https://github.com/bp-alex/hash-bench)
- [Use Fast Data Algorithms — jolynch.github.io](https://jolynch.github.io/posts/use_fast_data_algorithms/)
- [Comparison of hash functions — greenrobot.org](https://greenrobot.org/essentials/features/performant-hash-functions-for-java/comparison-of-hash-functions/)
- [SHA256 for cache invalidation — clash-lang/clash-compiler PR #1985](https://github.com/clash-lang/clash-compiler/pull/1985)
- [SQLite PRAGMA data_version — sqlite.org](https://sqlite.org/pragma.html)
- [SQLite cache invalidation via data_version — sqlite.org forum](https://sqlite.org/forum/info/e76cac71ac2db298)
- [Building a basic cache with SQLite — alexwlchan.net](https://alexwlchan.net/2026/sqlite-cache/)
- [CRC32 vs SHA256 — foldermanifest.com](https://www.foldermanifest.com/blog/crc32-vs-sha256-checksums)
- SteleKit source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`
- SteleKit source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`
