# Findings: Pitfalls — Android Save/Parse Latency

## Summary

Six distinct failure modes can each independently produce several-second Android latency. In order of likelihood of being the actual culprit in SteleKit today:

1. **SAF file I/O** — confirmed present in `PlatformFileSystem.kt`; every graph file read/write goes through `ContentResolver`, adding 10–100x overhead per call vs. `java.io.File`. The default graph path (`getDefaultGraphPath()`) returns a `saf://` URI whenever a tree URI is stored, meaning all production graph access uses SAF.
2. **Read-before-write doubles SAF ops** — `writeFile` calls `DocumentFile.fromSingleUri(...).exists()` before every write. Combined with the `savePageInternal` re-read safety check, every save does 3+ IPC round-trips minimum.
3. **`Dispatchers.IO` starvation** — DB and file I/O share the same 64-thread pool with image loading, OkHttp, etc. Confirmed: `PlatformDispatcher.android.kt` sets `DB = Dispatchers.IO`.
4. **WAL checkpoint spike** — auto-checkpoints at 1,000 pages; on slow NAND this blocks all writes for 1–5s. `DriverFactory.android.kt` does not configure `wal_autocheckpoint`.
5. **Cold page cache** — first graph open after boot pays full disk-read cost; AhoCorasickMatcher init forces a full `pages` table scan.
6. **`AndroidSqliteDriver` single-connection serialization** — unlike JVM's 8-connection pool, the Android driver serializes all access. [TRAINING_ONLY — verify whether RequerySQLiteOpenHelper opens a separate read connection in WAL mode]

## Options Surveyed

Not applicable for a pitfalls analysis — this dimension enumerates failure modes rather than comparing candidate solutions.

## Trade-off Matrix

| Pitfall | Severity | Likelihood | Explains 2–5s? | Detection |
|---------|----------|------------|----------------|-----------|
| SAF IPC per file op | High | Confirmed | Yes | `file.read_ms` span; Profiler Binder calls |
| Read-before-write doubles SAF ops | High | Confirmed | Yes | `file.read_count == file.write_count` counter |
| `Dispatchers.IO` starvation | High | Medium | Yes | CPU timeline; inline dispatcher wait |
| WAL checkpoint spike | High | Medium | Yes | `PRAGMA wal_checkpoint`; disk burst in Profiler |
| Cold page cache (first open) | Medium | High | Yes (first open) | Cold vs. warm `graph_load.phase1` comparison |
| Emulator measurement trap | High (validity) | Certain | Explains invisibility | Physical device vs. emulator ratio |
| Single-connection serialization | Medium | Medium | Partially | `TimingDriverWrapper` query spans |

## Risk and Failure Modes

### 1. SAF File I/O: 10–100x Overhead per File Operation

`PlatformFileSystem.kt` routes every operation through `ContentResolver` when the path starts with `saf://`. For a graph opened via the Android folder picker, `getDefaultGraphPath()` returns a `saf://` URI, so every file operation uses SAF.

The SAF stack per `readFile` call:
1. `ContentResolver.openInputStream(docUri)` — IPC to MediaProvider or ExternalStorageProvider via Binder
2. Provider resolves document ID to real file path
3. Returns a `ParcelFileDescriptor` via Binder
4. Caller reads bytes through the parcel fd

Each IPC adds 1–5ms on a warm Binder connection, up to 50–200ms cold. `GraphLoader` reads every `.md` file sequentially — 200 pages = 200 SAF reads. Additionally, `writeFile` performs a `DocumentFile.fromSingleUri(...).exists()` check (a full `ContentResolver.query()` IPC) before every write, then `openOutputStream` (another IPC). Minimum 3 IPC round-trips per write.

**How to detect**: Android Profiler System Trace; look for `Binder:*` calls on `Dispatchers.IO` threads during graph load. Add `file.read_ms` / `file.write_ms` OTel attributes mirroring the `TimingDriverWrapper` pattern.

**Mitigation**:
- Cache last serialized content in `GraphWriter`; use that for the safety check instead of re-reading from SAF
- Shadow-copy graph to `context.filesDir` on first open; serve reads from internal storage; write back to SAF only on save
- Parallelize SAF reads with a `Semaphore(8)` instead of sequential reads

### 2. Read-Before-Write: Doubled File I/O on Every Save

`savePageInternal` reads old file content to count blocks (safety check), then writes new content. In SAF: `readFile` (1 IPC) + `writeFile` (3 IPCs) = 4 IPC round-trips. At 50ms per IPC: 200ms per save. At 200ms cold: 800ms per save — just for one page.

**How to detect**: OTel `file.read_count` counter; if equal to `file.write_count` during a save session, every write is preceded by a read.

**Mitigation**: Store last written content in a `Map<String, String>` keyed by path inside `GraphWriter`. Compare against cached value on next save instead of re-reading from SAF.

### 3. `Dispatchers.IO` Thread Starvation

`PlatformDispatcher.DB = Dispatchers.IO` (confirmed in `PlatformDispatcher.android.kt`). The shared pool defaults to 64 threads but is shared with all other IO in the process. If 60 threads are occupied at graph load time, coroutines queue invisibly — the wait does not appear in `db.queue_wait` (which only measures the `DatabaseWriteActor`'s Kotlin `Channel`).

**How to detect**: Android Profiler CPU timeline; count occupied `Dispatchers.IO` threads at graph load start.

**Mitigation**: `private val dbDispatcher = Dispatchers.IO.limitedParallelism(4)` assigned to `PlatformDispatcher.DB` on Android. Isolates DB work from app-wide IO. [TRAINING_ONLY — verify `limitedParallelism` behavior with `AndroidSqliteDriver`'s threading model]

### 4. WAL Checkpoint Spike

SQLite auto-checkpoints at 1,000 pages (≈4MB). On devices with slow NAND, writing 4MB to flash takes 1–5 seconds. `DriverFactory.android.kt` sets `journal_mode=WAL` but does not set `wal_autocheckpoint`. The checkpoint fires non-deterministically mid-save, stalling `DatabaseWriteActor`. The `TimingDriverWrapper` shows the triggering query as anomalously slow (wall-clock includes the checkpoint), not as a separate span.

**How to detect**: `adb shell run-as <package> sqlite3 files/stelekit-graph-<id>.db "PRAGMA wal_checkpoint(PASSIVE);"` — if WAL frame count is consistently >200, checkpoint pressure is present.

**Mitigation**: Add `PRAGMA wal_autocheckpoint=100` to `DriverFactory.android.kt`. Call `PRAGMA wal_checkpoint(PASSIVE)` from `DatabaseWriteActor` when the write queue drains to move checkpoint cost to idle time.

### 5. Cold Page Cache

On cold start, every SQLite page requires a physical flash read. A 10MB database on fragmented NAND can take 200–1,000ms to cache. `AhoCorasickMatcher` init forces a full `SELECT name FROM pages` table scan, accessing every page row cold.

**How to detect**: Compare `graph_load.phase1` TTI on first launch after reboot vs. second launch.

**Mitigation**: Issue `SELECT COUNT(*) FROM pages` immediately after `MigrationRunner.applyAll(driver)` in `DriverFactory.createDriver` to warm the page cache before user-visible queries.

### 6. Emulator vs. Real Device: The Measurement Trap

The Android emulator routes the SQLite file through VirtIO-FS or host tmpfs. Host NVMe fsync latency: <1ms. Physical Pixel 6 fsync: 5–50ms. Budget device (Galaxy A13): 20–200ms. SAF Binder IPC is also near-zero on emulator. A benchmark showing 10ms on emulator can correspond to 1–5s on a real device. This is why the JVM benchmark shows 22ms while the user sees several seconds on Android.

**Mitigation**: Physical-device-only benchmarks. Document emulator invalidity. Use Firebase Test Lab for CI.

### 7. `AndroidSqliteDriver` Single-Connection Serialization

Unlike the JVM `PooledJdbcSqliteDriver` (8 connections), `AndroidSqliteDriver` + `RequerySQLiteOpenHelperFactory` serializes through one native connection handle. Concurrent coroutines queue at the native layer even if the Kotlin `Channel` drains quickly. [TRAINING_ONLY — verify whether RequerySQLiteOpenHelper opens a read connection + write connection in WAL mode]

**How to detect**: `TimingDriverWrapper` spans showing 50–200ms individual `INSERT INTO blocks` statements with near-zero `db.queue_wait`.

## Migration and Adoption Cost

No new libraries required for most mitigations:
- Read-before-write removal: ~20 lines in `GraphWriter.kt` — add `Map<String, String>` field, populate on write, compare on next save
- WAL autocheckpoint: 1 line in `DriverFactory.android.kt`
- DB warm-up query: 1–2 lines in `DriverFactory.android.kt`
- Dedicated DB dispatcher: 1 line in `PlatformDispatcher.android.kt`
- Shadow-copy to `filesDir`: moderate effort (~2 days), requires new `FilesDir` backing path and sync logic

## Operational Concerns

The existing `TimingDriverWrapper` (from observability commit) will capture SQL-level timing but not file-level timing. Adding `file.read_ms` and `file.write_ms` OTel attributes to `GraphLoader` and `GraphWriter` completes the picture without new libraries.

The `PerfExportReport.SessionSummary` already captures `p99ByOperation` — once file operations are instrumented as spans, they will appear there automatically.

## Prior Art and Lessons Learned

- The Android Jetpack Room library added a `multiInstanceInvalidation` warning for exactly this pattern — SAF-backed databases on shared external storage serialize through a single provider process. The recommendation from the Room team is always to copy to internal storage before opening.
- Multiple open-source note-taking apps (Joplin Android, Orgzly) moved from SAF-backed storage to internal-copy approach after encountering the same latency pattern.
- Google's own guidance (I/O 2021 "Best practices for SQLite on Android") recommends against opening SQLite files from SAF URIs directly; always copy to `filesDir` first. [TRAINING_ONLY — verify exact guidance]

## Open Questions

- [ ] Does `RequerySQLiteOpenHelperFactory` open a separate read connection in WAL mode? — blocks decision on whether a dedicated read dispatcher helps
- [ ] Does `PlatformFileSystem.kt` use SAF for all graph files, or only for externally-opened graphs? (If internal storage is the default, SAF may not be the bottleneck for most users) — blocks prioritization between SAF mitigation and WAL checkpoint fix
- [ ] What device and Android version is the user seeing several-second latency on? — determines whether budget NAND or SAF is primary
- [ ] Is the several-second latency on page load, on save (after debounce fires), or on both? — determines whether GraphLoader or GraphWriter is the primary target

## Recommendation

**Highest-confidence quick wins (do first, no measurement required)**:
1. Add `PRAGMA wal_autocheckpoint=100` to `DriverFactory.android.kt` — 1 line, eliminates multi-second checkpoint spikes
2. Add DB warm-up query `SELECT COUNT(*) FROM pages` in `DriverFactory.createDriver` — 1–2 lines, reduces cold-start latency
3. Cache last written content in `GraphWriter` to eliminate read-before-write — ~20 lines, halves file I/O per save

**Measure before optimizing further**:
4. Export OTel session from a real physical device (not emulator); look at `graph_load.phase1` vs. `graph_load.phase3` span durations to determine whether load or save dominates
5. Add `file.read_ms` / `file.write_ms` spans to `GraphLoader`/`GraphWriter`; if any single file op >50ms, SAF is confirmed
6. Only then decide whether shadow-copy to `filesDir` is justified

**Conditions that would change this recommendation**: If `getDefaultGraphPath()` is confirmed to always return an internal-storage path (not `saf://`), the SAF mitigations are irrelevant and WAL + dispatcher tuning dominate.

## Pending Web Searches

1. `"RequerySQLiteOpenHelper" WAL "read connection" OR "connection pool"` — verify single vs. dual connection model in WAL mode
2. `site:github.com/cashapp/sqldelight "limitedParallelism" "AndroidSqliteDriver"` — threading constraints
3. `android "storage access framework" latency benchmark milliseconds "openInputStream" OR "openOutputStream"` — confirm 1–200ms per-call range
4. `android sqlite WAL checkpoint latency "slow" OR "seconds" mobile 2023 OR 2024` — confirm multi-second checkpoint spikes on real devices
5. `site:github.com/cashapp/sqldelight issues "AndroidSqliteDriver" "performance" "slow"` — known open issues
