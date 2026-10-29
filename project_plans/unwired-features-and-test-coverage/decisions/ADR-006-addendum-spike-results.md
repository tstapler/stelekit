# ADR-006 Addendum: Story 0.3 spike results (Spikes 0.3a-e)

**Date**: 2026-10-27 | **Unit**: U0b | **Status**: Recorded; this is the merge-gate evidence for U4, U5 and U6.
**Note**: `ADR-006-console-delivery-deltas.md` itself is not in `origin/main` at the time of writing (it exists only as an untracked file in the planning checkout), so this addendum is a standalone file next to it. Append or fold it into ADR-006 when that file is committed.

Every row below is VERIFIED by a committed test run on 2026-10-27 unless it says otherwise. The runnable spikes are:

| Spike | JVM class (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/console/spike/`) | Android class (`kmp/src/androidInstrumentedTest/kotlin/dev/stapler/stelekit/console/spike/`) |
|---|---|---|
| 0.3a read-only | `ReadOnlyEnforcementSpikeTest` | covered by `AuthorizerSpikeAndroidTest` (OPEN_READONLY rejection, handle independence) |
| 0.3b VACUUM INTO / swap | `DbSwapSpikeTest` | `VacuumIntoSpikeAndroidTest` |
| 0.3c write cascade | `WriteCascadeSpikeTest` | not applicable (actor and `GraphWriter` are common code; JVM run is the evidence) |
| 0.3d cancel | `QueryCancelSpikeTest` | `QueryCancelSpikeAndroidTest` |
| 0.3e authorizer | `AuthorizerSpikeTest` | `AuthorizerSpikeAndroidTest` |

Each spike prints `SPIKE-RESULT <spike> <key>=<value>` lines. JVM: read them from `kmp/build/test-results/jvmTest/TEST-*.xml`. Android: the result XML has no stdout, so the tests publish the lines to `Download/console-spike-<runId>.txt` through MediaStore (survives Gradle's uninstall); `adb pull /sdcard/Download/`.

## Decisions by spike

### 0.3a Read-only enforcement (JVM): YES

- `SQLiteConfig.setReadOnly(true)` on a dedicated non-pooled `sqlite-jdbc` 3.51.3 connection rejects `UPDATE pages SET name='x'` and `WITH c AS (SELECT 1) DELETE FROM pages` with `[SQLITE_READONLY] Attempt to write a readonly database`; page count unchanged (200 before and after).
- `PRAGMA query_only=0` on that handle does not lift it (the next UPDATE still fails `SQLITE_READONLY`), so the guard is the open flag, not a pragma.
- `PRAGMA query_only=1` on the console handle did not leak: 24 sequential app writes (more than the 8 pooled connections) succeeded and the app connection reports `query_only=0`.
- Concurrent reads on the read-only handle during 300 WAL writes: 63,938 reads, 0 errors.
- `DriverFactory().createReadDriver(...)` returns `null` on JVM, confirming the console must open its own handle.
- Android OPEN_READONLY (requery 3.49.0): `UPDATE` rejected with `attempt to write a readonly database (code 8)`, app pool writes unaffected (see 0.3e).

### 0.3b `VACUUM INTO` and swap: YES (JVM and Android)

- JVM, graph-sized DB: 12,600 pages / 151,200 blocks, main file 431,366,144 bytes (the run's target was 472,907,776 bytes of main file plus WAL; the main file is the number to cite). `VACUUM INTO` took 1,266 ms, copy 429,428,736 bytes, `PRAGMA integrity_check` = `ok`, copy page and block counts equal the source, peak sampled heap 18 MB (baseline 17 MB). Seeding the DB took 2,146,990 ms (about 36 min) on this machine; the backup itself is cheap.
- During that `VACUUM INTO`, 207 concurrent app writes through the 8-connection pool all succeeded (0 errors, max write latency 102 ms). This is a JVM measurement; the Task 3.3f bound for Android must come from an Android run (not measured here).
- `VACUUM INTO` also works from the read-only handle (copy 429,477,888 bytes), so backup does not need a read-write console handle.
- Swap order proven on a temp graph (JVM): validate snapshot (`integrity_check` ok) -> close console reader -> close app driver (drain) -> rename `graph.db` to `.pre-restore` -> copy snapshot in -> delete `-wal`/`-shm` -> reopen through `DriverFactory.createDriver` -> `integrity_check` ok and page count equals snapshot (200); a post-restore write succeeds; rollback by renaming `.pre-restore` back restores the pre-swap count (250).
- Why stale sidecars must be deleted: planting the old `-wal` beside the restored snapshot makes the open fail with `[SQLITE_CORRUPT] The database disk image is malformed`.
- Android (API 34 AVD, requery 3.49.0): `VACUUM INTO` works (300 pages, 36 ms, `integrity_check` ok, counts equal) from both a read-write and a read-only handle; platform SQLite 3.39.2 also accepts `VACUUM INTO`. Swap order and rollback pass on the Android driver (restored 50 pages equals snapshot, rollback 90 equals pre-swap).
- On both platforms the sidecars were already gone after all handles closed (`-wal`, `-shm` absent), so the delete step is a guard, not the usual path.

### 0.3c Raw-write cascade: NO cascade (no markdown written)

- Real SQLDelight repositories on a file DB, `DatabaseWriteActor`, a `GraphWriter` with auto-save running (500 ms debounce) over a real graph directory. A raw `UPDATE blocks SET content='zz'` through `actor.execute` changed the DB row, updated FTS (`blocks_fts MATCH 'zz'` = 1 hit, trigger-maintained), emitted 1 invalidation, and after a 2.5 s window the graph directory was byte- and mtime-identical (`pages/CascadeSpike.md` unchanged, does not contain `zz`).
- Limit: the only production subscriber of `blockInvalidations` is `BlockStateManager.observePage` (`GraphContentViewModelSetup.kt:100`, a read-only re-pull); it is not constructed in the test, and the raw write goes over a separate JDBC connection inside the actor lambda. Later user edits to the touched page can still serialize the changed row (ADR-002 A4 policy stands).

### 0.3d Cancel / interrupt: YES, with a per-driver mechanism

| Driver | `Statement.cancel()` / `CancellationSignal` | Abandon-and-close mid-query | `Thread.interrupt` only |
|---|---|---|---|
| JVM sqlite-jdbc | **YES**: `Statement.cancel()` returned the query in 0 ms after trigger with `[SQLITE_INTERRUPT]`; same connection stays usable | **NO**: `close()` did not return and the query did not stop within 4 s (close blocks behind the running step); app pool unaffected | NO: query still running after 4 s |
| Android requery 3.49.0 | **YES**: `androidx.core.os.CancellationSignal` returned in 0 ms (`OperationCanceledException: interrupted (code 9)`) | **NO**: `close()` returns immediately but the query keeps running (not returned within 5 s); it stops only when the signal is cancelled | NO |
| Android platform SQLite 3.39.2 | YES: `android.os.CancellationSignal` in 0 ms | NO: same as requery | n/a |

- Consequence: abandon-and-close is NOT a safe cancel on either platform; the deadline must call `Statement.cancel()` (JVM) or `CancellationSignal.cancel()` (Android, requery) on the running statement. The condition in the plan, "if neither interrupt nor abandon-and-close is safe on `requery`, Android ships without `sql!`", is NOT triggered because the interrupt path (`CancellationSignal`) is safe: the app pool and a fresh read-only handle were usable after every variant (app write plus fresh-handle count OK).
- Not tested: sqlite-jdbc `ProgressHandler` as a deadline hook (not on the compile classpath of the test source set).

### 0.3e Authorizer availability: NO on every driver; credential denial claim dropped

- JVM sqlite-jdbc 3.51.3: no `org.sqlite.Authorizer` class; `NativeDB`/`DB` declare no authorizer-like method; the bundled `libsqlitejdbc.so` (Linux x86_64) does not contain the string `sqlite3_set_authorizer`; `PRAGMA compile_options` lists nothing with `AUTH`. Five credential reads (`SELECT * FROM git_config`, CTE, alias, subquery, `JOIN`) each returned the row, so nothing is denied: **NO**.
- Android requery 3.49.0 (AVD API 34): the Java API has no authorizer method (`SQLiteDatabase`, `SQLiteConnection`, `SQLiteDatabaseConfiguration`); the native `libsqlite3x.so` does contain `sqlite3_set_authorizer` in all four ABIs, but nothing exposes it to Kotlin/Java. Reaching it would need a project-owned NDK/JNI shim, which is out of scope for the console (this is the reason the result is NO and not YES). The five credential reads returned rows: **NO** (via public API or reflection).
- Android platform SQLite 3.39.2: no authorizer method; `System.loadLibrary("sqlite")` fails (`libsqlite.so` not accessible for the app namespace): **NO**.
- Consequence for Story 3.2: use the **NO** criterion on every driver. Drop the "credential tables are unreadable" claim; `sql` output and CSV go through the single `Redactor`; `git_config` exposes alias strings, not secrets. No test may claim denial (UT-81/UT-121 apply; IT-66 is skipped with this recorded reason).
- Console handle independence: JVM `createReadDriver` = `null`. Android `createReadDriver` returns an `AndroidSqliteDriver` through which a write **succeeds** (read-write), confirming it must not be reused. A separate `OPEN_READONLY` handle rejected a write and, after it was closed, 16 further app writes on the pooled driver succeeded (pages 2 -> 18).

### Finding that changes a plan assumption: platform SQLite cannot be the Android console driver

On the API 34 AVD the platform SQLite is 3.39.2 and has **no FTS5 module**: a `UPDATE pages ...` through the platform read-only handle failed with `no such module: fts5` (code 1) while compiling, not with the read-only error. The app schema's FTS triggers/virtual tables need FTS5, so the Android console handle must be the requery fork (3.49.0, FTS5 present). Plan Task 3.2b wording "driver class chosen by Spike 0.3e" resolves to requery.

## Gaps (not covered by these runs)

- No Android `VACUUM INTO` / concurrent-write measurement at ~450 MB; that is Task 3.3f (IT-86). Android `VACUUM INTO` was run on a 7.6 MB DB.
- Cancel was measured on a recursive CTE that touches no tables; a cancel during a long scan of `blocks`/FTS was not measured.
- Only one AVD (`Pixel_Fold`, API 34, x86_64) and one JVM host (Linux x86_64) were exercised. macOS/Windows sqlite-jdbc native libraries were not scanned for the authorizer symbol.
- The raw-write cascade did not construct `BlockStateManager`.

## Addendum 5 (U5, Story 3.3): `sql!` write path decisions

- **FTS consistency (Task 3.3g): reject, do not repair.** `UPDATE` and `DELETE` on `blocks` and `pages` fire the existing triggers (`blocks_au`, `blocks_ad`, `pages_au`, `pages_ad`) and keep `blocks_fts` and `pages_fts` in sync. `REPLACE`, `INSERT OR REPLACE` and `UPDATE OR REPLACE` on those two tables remove the replaced row without firing the delete trigger (recursive triggers are off), which leaves a stale index row; `SqlWriteGuard` rejects them before they run with a reason. Evidence: `SqlWriteFtsConsistencyTest` (`engine_should_LeaveStaleIndexRow_When_ReplaceRunsWithoutTheGuard` shows the stale row without the guard).
- **Dry run and timeout.** The dry run runs inside `DatabaseWriteActor.execute(invalidate = false)` and ends by throwing a private sentinel, so nothing is committed and no invalidation is emitted. The 10 s deadline bounds the console's wait. SQLDelight's `SqlDriver` exposes no statement handle, so a statement the engine is already stepping cannot be interrupted from the console: on timeout the write is refused and the actor still finishes the statement and rolls it back.
- **FTS overhead is not a "cascade".** `total_changes()` counts the FTS trigger writes, so every `blocks`/`pages` write has `total_changes() > changes()`. The dry run also diffs `COUNT(*)` of every ordinary table (FTS shadow tables excluded); the escalate rule fires on a changed *other* table, or on extra changes on a table with no FTS index. A write whose only extra changes are on its own FTS index stays `Standard`. Limit: a trigger that rewrites rows of another table without changing its row count is not listed; the in-transaction re-check still compares the totals.
- **Backup.** `VACUUM INTO` over a console-owned read-only handle (Spike 0.3b), verified with `PRAGMA integrity_check`, written under a `.partial` name and renamed; the `wal_checkpoint(TRUNCATE)` + copy fallback of Task 3.3c was not built because `VACUUM INTO` works on JVM and Android (Spike 0.3b). Backups live in `console-backups/` beside the database file.
- **Allowlist.** The one new `@OptIn(DirectSqlWrite::class)` site is `SqlConsoleWriter.rawExec`, which owns the actor-side transaction and hands its body a session for the statement and the row-count snapshots; `DirectSqlWriteOptInAllowlistTest` pins the 40 existing lines plus it.
- **Release gate.** `ConsoleBuildFlags.WRITES_ENABLED` is `RELEASE_WRITES_ENABLED || internalBuild`; the constant is `false` and only the U18 flip PR changes it. Android sets `internalBuild` from `FLAG_DEBUGGABLE`; desktop never sets it, so `sql!` is listed `disabled: writes not enabled in this build` there too.
