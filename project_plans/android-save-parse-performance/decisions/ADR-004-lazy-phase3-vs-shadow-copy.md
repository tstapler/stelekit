# ADR-004: Lazy Phase 3 Before Shadow Copy

**Status**: Accepted  
**Date**: 2026-04-30  
**Context**: Android SAF Performance — Story 3 and Story 4 sequencing

---

## Context

Phase 3 background indexing (`indexRemainingPages`) reads every markdown file from SAF on graph open. With a 1000-page graph, this serializes to minutes of Binder IPC. Two complementary fixes exist:

**Option B — Lazy Phase 3**: Index a page only when the user first navigates to it. Remaining pages are indexed in the background at lowest priority.

**Option A — SAF Shadow Copy**: On graph open, bulk-copy all markdown files from SAF to `context.filesDir/graphs/<id>/shadow/`. Phase 3 reads from the shadow (direct file I/O, ~0.1ms). Writes go to both SAF (source of truth) and shadow.

Both options eliminate the SAF bottleneck from Phase 3. The question is order of implementation.

---

## Decision

Implement **Lazy Phase 3 (Option B) first**. Implement **SAF Shadow Copy (Option A) second**, only after Lazy Phase 3 ships and we can measure whether writes (not reads) are still the dominant bottleneck.

---

## Rationale

### Why Lazy Phase 3 first

1. **Eliminates the problem entirely from the startup critical path.** With lazy indexing, Phase 3 reads zero SAF files at startup. Pages are indexed on-demand when navigated to. The 48-second `parseAndSavePage` never blocks startup.

2. **Lower implementation risk.** Lazy Phase 3 adds a check in `indexRemainingPages` and a load trigger in `StelekitViewModel.navigateTo`. It does not add a new persistent artifact (shadow directory) that must be kept in sync across crashes, upgrades, and external edits.

3. **Preserves SAF as the only source of truth.** Shadow copy introduces a second on-disk representation. If the app crashes between writing SAF and writing shadow, or between writing shadow and writing SAF, the two can diverge. Lazy Phase 3 has no such dual-state risk.

4. **Required data for the shadow copy decision.** Once Lazy Phase 3 is in place, the remaining SAF overhead is confined to write paths (`savePageInternal`) and on-demand reads. We need session exports from this state to know whether shadow copy would provide additional benefit. Shadow copy without this measurement is premature optimization.

5. **Incremental delivery.** Lazy Phase 3 ships independently and provides user-visible relief within days. Shadow copy is a separate, larger change that can ship in a subsequent sprint.

### Why Shadow Copy eventually

Shadow copy eliminates SAF overhead for **all** reads — including `savePageInternal`'s read-before-write check and `loadFullPage` on cold navigation. Once Lazy Phase 3 is in place and per-write overhead is measured, shadow copy becomes the right next step if:
- Session exports show `file.readCheck` spans contribute > 50ms to interactive save latency
- The `listFilesWithModTimes` bulk cursor confirms the startup sync is fast enough (< 2s for 1000 files)

### Alternatives Rejected

**Shadow Copy first**: Higher complexity, introduces dual-state risk before we need it, and does not address the race condition (Story 1) or the `DocumentFile` overhead (Story 2).

**Parallel SAF reads (Option D)**: Explicitly rejected — parallelizing reads before eliminating the bulk volume of reads saturates the `DatabaseWriteActor` write queue, making interactive write latency worse, not better.

---

## Consequences

- Story 3 (Lazy Phase 3) ships before Story 4 (Shadow Copy). Story 4 is gated on measurement from Story 3.
- Search results may be incomplete until background lazy indexing completes. A "still indexing" indicator must be added to the search UI.
- Shadow copy implementation (if validated) must handle the startup mtime sync, write-through pattern, and `externalFileChanges` re-sync. These are documented in `findings-saf-optimization.md` Option A.
