# Findings: Features — SQLite Write Latency Tuning on Android

## Summary

SteleKit Android already has the most impactful SQLite optimization in place: WAL mode with `synchronous=NORMAL`. The remaining candidates fall into three tiers by expected impact:

**High impact, low risk**: Transaction batching (partially implemented via `DatabaseWriteActor.flushBatch()`) is the biggest remaining gap — the DELETE-blocks + INSERT-blocks for a full page save currently execute as two separate transactions.

**Medium impact, zero risk**: `PRAGMA temp_store=MEMORY` and a modest `cache_size` increase are safe standard additions. `PRAGMA wal_autocheckpoint=4000` smooths checkpoint-triggered latency spikes during continuous editing.

**Low impact or uncertain**: `synchronous=OFF` is not meaningfully better than `NORMAL` in WAL mode and risks OS-crash corruption. `mmap_size` has uncertain SELinux/Bionic compatibility on Android. Driver migration to `BundledSQLiteDriver` offers 20–26% improvement but is a non-trivial migration.

## Options Surveyed

1. `PRAGMA synchronous=OFF`
2. `PRAGMA cache_size=<N>`
3. `PRAGMA temp_store=MEMORY`
4. `PRAGMA mmap_size=<N>`
5. `PRAGMA wal_autocheckpoint=<N>`
6. Connection pool tuning
7. Transaction batching (DELETE + INSERT in one transaction)
8. `BundledSQLiteDriver` migration

## Trade-off Matrix

| Candidate | Expected Impact | Durability Risk | Complexity | Verdict |
|-----------|----------------|-----------------|------------|---------|
| `synchronous=OFF` | Low (+5–15% over NORMAL in WAL) | OS-crash corruption | Trivial | Skip |
| `cache_size=-8000` | Low (graph load only) | None | Trivial | Adopt |
| `temp_store=MEMORY` | Low–medium (eliminates temp I/O) | None | Trivial | Adopt |
| `mmap_size=N` | Unknown on Android | None | Trivial + validation | Pending research |
| `wal_autocheckpoint=4000` | Medium (smooths latency spikes) | None | Trivial | Adopt |
| Connection pool tuning | None (write-path is serialized) | None | N/A | Skip |
| DELETE+INSERT single transaction | High (~2x save overhead reduction) | None | Moderate | High priority |
| Migrate to `BundledSQLiteDriver` | Medium (20–26% system-wide) | None | Moderate | Phase 2 |

## Risk and Failure Modes

### `synchronous=OFF`
In WAL mode, `synchronous=NORMAL` already skips the per-commit fsync on the WAL file; it only fsyncs at WAL checkpoint boundaries. `synchronous=OFF` additionally skips the checkpoint fsync. The speedup over `NORMAL` in WAL mode is ~5–15% [TRAINING_ONLY — verify with WAL-specific benchmark]. With `OFF`, an OS crash (not app crash) during checkpoint can corrupt the database. Given the marginal gain, the risk is not justified.

### `mmap_size`
SELinux `execmem` denials have been reported on some Android versions when SQLite attempts to create mmap regions. Google's own Android SQLite best practices page does not mention `mmap_size` — a conspicuous omission. Compatibility with `RequerySQLiteOpenHelperFactory` through Bionic's `mmap(2)` is not documented. [TRAINING_ONLY — verify]

### WAL checkpoint spike (without `wal_autocheckpoint`)
At 500ms save interval with ~60KB WAL per save, the default 1,000-page autocheckpoint fires roughly every 67 saves (~33 seconds of continuous editing). Checkpoint on slow NAND can stall writers for 1–5 seconds. Setting `wal_autocheckpoint=4000` defers this to ~133 seconds of continuous editing; pair with an explicit `PRAGMA wal_checkpoint(PASSIVE)` during editor idle time.

### Transaction batching gap
A full page save sends two separate actor requests: `actor.deleteBlocksForPage()` then `actor.saveBlocks()`. Each incurs a full WAL commit cycle. Combining both into a single `actor.execute { queries.transaction { delete; upsert } }` cuts the per-page-save WAL overhead in half. `DatabaseWriteActor` already has the `execute {}` escape hatch for this pattern.

## Migration and Adoption Cost

**Immediate `DriverFactory.android.kt` additions** (3 lines, zero risk):
```kotlin
try { driver.execute(null, "PRAGMA temp_store=MEMORY;", 0) } catch (_: Exception) { }
try { driver.execute(null, "PRAGMA cache_size=-8000;", 0) } catch (_: Exception) { }
try { driver.execute(null, "PRAGMA wal_autocheckpoint=4000;", 0) } catch (_: Exception) { }
```

**Transaction batching** (~30 lines): restructure `GraphWriter.savePageInternal` or add a `DatabaseWriteActor.savePageBlocks()` typed method. Requires combining the delete and insert into one `queries.transaction {}` block.

**`BundledSQLiteDriver` migration**: requires `DriverFactory.android.kt` changes and `build.gradle.kts` dependency update. SteleKit already bundles SQLite 3.49.0 via Requery, so the version advantage is minimal; the JNI `@FastNative` overhead reduction is the remaining gain.

## Operational Concerns

`wal_autocheckpoint=4000` means the WAL can grow to ~16MB between checkpoints. Monitor WAL file size in the OTel session export or via `PRAGMA wal_checkpoint(PASSIVE)` return values. Add a background idle checkpoint to prevent unbounded growth.

## Prior Art and Lessons Learned

- Android developer docs and PowerSync both list `temp_store=MEMORY` as standard baseline SQLite configuration alongside WAL mode — not an optional optimization.
- phiresky's SQLite performance tuning guide: `mmap_size` significantly reduces read latency when working set fits the mapped window, but is OS-specific and may be a no-op on Android.
- Jason Feinstein benchmark: batched inserts in one transaction are ~130x faster for bulk and ~2x overhead reduction for paired operations (the delete+insert case).
- loke.dev WAL checkpoint starvation post-mortem: unbounded WAL growth can cause the checkpoint to block for minutes on spinning rust or slow NAND. `wal_autocheckpoint` is the primary mitigation.

## Open Questions

- [ ] Does `PRAGMA mmap_size` work on Android 12+ with default SELinux policy? — blocks decision on whether to adopt it
- [ ] Is SteleKit's Requery sqlite-android 3.49.0 JNI path equivalent to `BundledSQLiteDriver`'s `@FastNative` annotations? — determines whether driver migration is worth the effort
- [ ] What is the current WAL file size after a typical 30-minute editing session on Android? — determines urgency of `wal_autocheckpoint` change

## Recommendation

**Adopt immediately** (3 lines in `DriverFactory.android.kt`):
- `PRAGMA temp_store=MEMORY`
- `PRAGMA cache_size=-8000`
- `PRAGMA wal_autocheckpoint=4000`

**High priority architectural change**: combine DELETE-blocks + INSERT-blocks for page save into a single `queries.transaction {}` block via `actor.execute {}`. This is the most impactful SQLite-level change available and targets the actual per-save overhead.

**Defer**: `synchronous=OFF` (marginal gain, non-zero risk), `mmap_size` (unverified Android compatibility), `BundledSQLiteDriver` (pending research on JNI advantage with Requery).

**Conditions that would change this recommendation**: If `mmap_size` is confirmed to work on Android (Pending Web Search #1), adopt it. If Requery is confirmed to lack `@FastNative` equivalent, evaluate `BundledSQLiteDriver` migration.

## Pending Web Searches

1. `PRAGMA mmap_size Android SELinux mmap permission denied SQLite` — confirm compatibility on Android 12+
2. `BundledSQLiteDriver SQLDelight 2.3.2 compatibility AndroidSqliteDriver migration` — confirm migration steps
3. `SQLite WAL mode synchronous=NORMAL vs OFF checkpoint fsync Android benchmark` — find WAL-specific benchmark comparing NORMAL and OFF
4. `sqlite-android 3.49.0 requery BundledSQLiteDriver JNI FastNative overhead comparison` — determine whether Requery already matches BundledSQLiteDriver JNI performance

## Sources

- [Best practices for SQLite performance | Android Developers](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- [SQLite Optimizations For Ultra High-Performance — PowerSync](https://www.powersync.com/blog/sqlite-optimizations-for-ultra-high-performance)
- [SQLite performance tuning — phiresky's blog](https://phiresky.github.io/blog/2020/sqlite-performance-tuning/)
- [Write-Ahead Logging — sqlite.org](https://sqlite.org/wal.html)
- [The 20GB WAL File That Shouldn't Exist: Checkpoint Starvation — loke.dev](https://loke.dev/blog/sqlite-checkpoint-starvation-wal-growth)
- [Squeezing Performance from SQLite: Insertions — Jason Feinstein, Medium](https://medium.com/@JasonWyatt/squeezing-performance-from-sqlite-insertions-971aff98eef2)
- [BundledSQLiteDriver, A New Look at SQLite in Android and KMP — wsoh.released.at](https://wsoh.released.at/blog/bundledsqlitedriver/)
- [AndroidX SQLite releases](https://developer.android.com/jetpack/androidx/releases/sqlite)
