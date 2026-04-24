# Findings: Architecture — Android Save & Parse Bottleneck Identification

## Summary

Based on a full read of `GraphWriter.kt`, `GraphLoader.kt`, `DatabaseWriteActor.kt`, `MarkdownParser.kt`, and the JVM benchmark harness, **the dominant bottleneck is most likely (A) the read-before-write safety check in `savePageInternal`, with (B) DatabaseWriteActor queue contention as a strong secondary suspect during Phase 3 indexing**. Parsing (C) is unlikely to cause several-second latency for a single page. A fourth factor — Android-specific SQLite fsync latency per transaction — may explain why even idle writes are slower than JVM baseline.

**Recommended first fix**: eliminate the read-before-write safety check in `savePageInternal` for the autosave path. ~10 lines, very low correctness risk, always on the critical path.

**Recommended second step**: add `db.queue_wait` span recording to HIGH priority `SavePage`/`SaveBlocks` requests (currently only `Execute` records this), get a session export from a real Android device, and confirm whether actor contention is also contributing.

## Options Surveyed

Four suspects analyzed from code inspection:
- A: Read-before-write safety check in `savePageInternal`
- B: `DatabaseWriteActor` queue contention (HIGH priority saves blocked behind LOW `Execute` chunks)
- C: Markdown parsing (`OutlinerPipeline` + `InlineParser` + `AhoCorasickMatcher`)
- D: Android SQLite fsync latency per transaction

## Trade-off Matrix

| Suspect | Estimated contribution | Safe to fix | Visible in current OTel | Fix first? |
|---------|----------------------|-------------|------------------------|-----------|
| A: Read-before-write | 50–300ms/save | Yes, ~10 lines | No (span missing) | Yes |
| B: Actor contention (HIGH behind LOW Execute) | 300ms–2s/save during load | Yes but ~30 lines, medium risk | Partial (span missing for HIGH) | Second — instrument first |
| C: Markdown parsing | 5–50ms/page | Not needed | Yes (`parse.markdown`) | No |
| D: Android fsync | 10–80ms/transaction | Yes, ~3 lines | Yes (`sql.insert`) | Third — diagnose first |

## Risk and Failure Modes

### Codebase Baseline — Save Path

```
BlockEditor keystroke
  → BlockStateManager (local state, immediate)
  → 500ms debounce (queueSave, pendingByPage map)
  → savePageInternal (saveMutex.withLock)
      A. fileSystem.fileExists(filePath)              ← file I/O #1
      B. fileSystem.readFile(filePath)                ← file I/O #2 (full file read, SYNCHRONOUS)
      C. content.lines().count { … }                 ← O(lines) CPU scan
      D. buildString { … }                           ← O(blocks) serialization
      E. fileSystem.writeFile(filePath, content)      ← file I/O #3
      F. writeActor.savePage(updatedPage)             ← enqueue to actor, await deferred
```

The safety check at step B reads the entire existing file synchronously before every write. The `saveMutex` is held for the entire sequence A–F, including the `writeActor.savePage` await.

### Suspect A: Read-Before-Write Safety Check

**Code location**: `GraphWriter.kt` lines 181–192

```kotlin
if (fileSystem.fileExists(filePath)) {
    val oldContent = fileSystem.readFile(filePath) ?: ""   // full file read on every save
    val oldBlockCount = oldContent.lines().count { it.trim().startsWith("- ") }
    val newBlockCount = blocks.size
    if (oldBlockCount > largeDeletionThreshold && newBlockCount < oldBlockCount / 2) {
        // only fires for pages with >50 blocks where >50% deleted
        return@withLock
    }
}
```

The 50-block threshold means pages with fewer than 50 blocks (most daily journals) always pay the full file-read cost without ever triggering the guard. Reads full file synchronously before every save.

**Estimated Android latency**: 50–300ms per save on a mid-range device [TRAINING_ONLY — verify]. Doubles the file I/O cost of every autosave.

**Fixable without behavior change**: Yes. The autosave path receives `blocks` directly from `BlockStateManager` — the in-memory editor state — which cannot silently discard blocks. The safety check only makes sense when blocks come from an external source.

**Fix**: Add `skipSafetyCheck: Boolean = false` to `savePageInternal`. Pass `true` from `saveImmediately` (the debounced autosave entry point) and `flush()`. Leave check active for `savePage()` (import/direct call) and future callers.

**Correctness risk**: Very low. The blank-file guard and zero-block parser guard in `parseAndSavePage` already handle external-file corruption.

### Suspect B: DatabaseWriteActor Queue Contention

**Architecture**: a single coroutine drains two `Channel.UNLIMITED` channels. HIGH is polled first via `highPriority.tryReceive()` before a `select`. LOW is used by Phase 3 indexing.

**Contention scenario**: `indexRemainingPages` dispatches `writeActor.execute(Priority.LOW) { flushChunkWrites(...) }` per 10-page chunk. Inside `flushChunkWrites`, one `execute` lambda handles `savePages`, `deleteBlocksForPages`, and `saveBlocks` for all 10 pages — potentially 1,000+ block writes — in a single actor `execute`. The actor's preemption mechanism (checking `highPriority.tryReceive()` between `SaveBlocks` coalescing) does **not** fire during an `Execute` lambda. If a LOW `Execute` is currently running, HIGH priority saves queue behind it for the full chunk-transaction duration.

**Estimated contribution**: up to one full chunk-transaction duration per user save if Phase 3 is active. A chunk of 10 pages × 100 blocks on Android SQLite may take 300ms–2s [TRAINING_ONLY]. Zero contribution if Phase 3 has already finished.

**Key OTel gap**: `db.queue_wait` is recorded for `Execute` requests but **not** for `SavePage` or `SaveBlocks` typed requests. There is no way to see in a current session export how long a HIGH priority save waited. This must be added before Suspect B can be confirmed.

**Fix**: Replace `writeActor.execute(LOW) { flushChunkWrites(...) }` with individual typed `writeActor.savePages(..., LOW)`, `writeActor.deleteBlocksForPages(..., LOW)`, `writeActor.saveBlocks(..., LOW)` calls. The existing `processSaveBlocks` coalescing already preempts for HIGH between consecutive `SaveBlocks` requests.

**Correctness risk**: Medium. The `Execute`-wrapped chunk gives atomic visibility (all-or-nothing per chunk). Splitting into typed calls means partial-chunk results are visible during Phase 3. Acceptable for background indexing (idempotent on re-index), but requires testing `isContentLoaded` flag and FK constraint ordering.

### Suspect C: Markdown Parsing

On JVM the SMALL graph parses in ~22ms total. Per-page: ~0.1ms. Even at 10× Android slowdown, a single page with 100 blocks parses in ~1–10ms. `AhoCorasickMatcher` is **not** on the save/parse hot path — not called from `savePageInternal` or `parseAndSavePage`. The `parse.markdown` OTel span already captures this; if parsing is dominant it will appear in the span waterfall.

**Verdict**: Not a significant contributor for single-page operations.

### Suspect D: Android SQLite Fsync Latency

`AndroidSqliteDriver` operates on a single native connection. On a mid-range device under I/O load, even a small `INSERT` transaction can take 20–80ms [TRAINING_ONLY]. The `TimingDriverWrapper` `sql.insert` spans will show this directly in a session export.

## Migration and Adoption Cost

| Fix | Lines | Files | Risk |
|-----|-------|-------|------|
| Remove read-before-write (autosave path) | ~10 | `GraphWriter.kt` | Very low |
| Add `db.queue_wait` to HIGH typed requests | ~15 | `DatabaseWriteActor.kt` | None (instrumentation only) |
| Replace `Execute` chunk with typed actor calls | ~30 | `GraphLoader.kt` | Medium |

## Operational Concerns

Two OTel spans are currently missing that are required to diagnose Suspects A and B:
- `"file.readCheck"` span around the read-before-write in `savePageInternal` — measures Suspect A
- `db.queue_wait` for HIGH `SavePage`/`SaveBlocks` typed requests — measures Suspect B (HIGH path)

Without these, a session export from a real Android device cannot attribute latency to either suspect. Adding them is the correct first step before implementing fixes.

## Prior Art and Lessons Learned

The JVM benchmark shows 22ms total for a 200-page graph — consistent with fast JVM file I/O and the pool-backed JDBC driver. On Android, the same operations run through ContentResolver (SAF) or Android's single-connection SQLiteDatabase, both of which add significant overhead. The architectural gap is not algorithmic — it is infrastructure latency multiplied by operation count.

## Open Questions

- [ ] Actual Android device numbers — all estimates are extrapolated from JVM baseline; a session export from a Pixel 6-class device is required before committing to any fix order
- [ ] Phase 3 timing relative to typical first edit — if users consistently edit before Phase 3 finishes, Suspect B is significant; if Phase 3 typically completes in <5s on Android, it may not matter
- [ ] `saveMutex` deadlock safety — `savePageInternal` holds `saveMutex` for the full sequence including `writeActor.savePage` await; confirmed safe since Phase 3 never calls `savePageInternal`

## Recommendation

**Step 1** (this week): Add `"file.readCheck"` span to `savePageInternal` and `db.queue_wait` recording to HIGH `SavePage`/`SaveBlocks` in `DatabaseWriteActor`. Get a session export from a real Android device.

**Step 2** (after session export, regardless): Implement read-before-write removal on autosave path (~10 lines). Safe regardless of what the session export shows.

**Step 3**: If session export shows HIGH `db.queue_wait` > 200ms during load → implement typed actor calls for chunk writes. If `sql.insert` spans > 30ms → investigate PRAGMA tuning.

## Pending Web Searches

1. `Android internal storage write latency f2fs ext4 fsync benchmark 2023 2024` — verify 50–200ms file write estimate
2. `SQLDelight AndroidSqliteDriver RequerySQLiteOpenHelperFactory WAL synchronous pragma defaults` — confirm current PRAGMA values
3. `Kotlin coroutines Channel UNLIMITED actor single coroutine throughput Android` — verify actor throughput assumptions
4. `Android SQLite INSERT transaction latency Pixel 6 mid-range WAL fsync` — verify per-transaction latency estimate
5. `AndroidSqliteDriver single connection WAL concurrent read write serialization` — confirm whether the Android driver serializes reads and writes
