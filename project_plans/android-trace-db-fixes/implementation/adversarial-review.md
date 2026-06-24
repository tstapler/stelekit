# Adversarial Review: android-trace-db-fixes

**Date**: 2026-06-02
**Verdict**: CONCERNS (patched — 4 concerns addressed in plan.md revision; remaining minors are low-impact)

---

## Blockers

*(none — plan is structurally sound)*

---

## Concerns

- [ ] **`saveBlocksUpdate` must be added to `BlockWriteRepository`, not `BlockRepository`** — The plan says to add `saveBlocksUpdate` to `BlockRepository.kt`, but `BlockRepository` is a composite interface delegating to `BlockWriteRepository` (and three other sub-interfaces). The correct file is `/home/tstapler/Programming/stelekit/kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockWriteRepository.kt`. Adding to the wrong interface will compile but will fail the structural intent and may cause a "method not found" compile error at the call site in `dispatchFullBlockWrites`. Recommendation: add `@DirectRepositoryWrite suspend fun saveBlocksUpdate(blocks: List<Block>): Either<DomainError, Unit>` to `BlockWriteRepository.kt`, not `BlockRepository.kt`.

- [ ] **`FakeBlockRepository` in `FakeRepositories.kt` must implement `saveBlocksUpdate`** — `FakeBlockRepository` at `/home/tstapler/Programming/stelekit/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt` line 139 implements `BlockRepository`. Adding `saveBlocksUpdate` to `BlockWriteRepository` without implementing it in `FakeBlockRepository` will cause a compile error breaking all jvmTest UI tests. Recommendation: add `override suspend fun saveBlocksUpdate(blocks: List<Block>) = saveBlocks(blocks)` to `FakeBlockRepository`. This is a one-liner delegation to `saveBlocks` (semantically correct for an in-memory fake).

- [ ] **Composite execute lambda captures `page` from outer scope — `dispatchFullBlockWrites` must accept `page: Page` as a parameter** — Task 2.1.1c calls out that `dispatchFullBlockWrites` needs `page` passed in, but the plan's fix description in Task 2.1.1b shows the lambda calling `pageRepository.savePage(page)` where `page` is not in scope (the current signature at line 1332 does not include `page`). The caller in `parseAndSavePage` (line 1510) currently passes `filePathStr, content, existingBlocks, blocksToSave, priority, traceId, rootSpan.spanId`. Adding `page` to `dispatchFullBlockWrites` is required. The plan mentions this in Task 2.1.1c but does not specify it clearly in the signature update for 2.1.1b — a developer following the plan may miss it. Recommendation: add a step explicitly stating the new `dispatchFullBlockWrites` signature: `fun dispatchFullBlockWrites(filePath, content, existingBlocks, blocksToSave, page, priority, traceId, parentSpanId)` and update the caller at line 1510.

- [ ] **METADATA_ONLY path still calls `writeActor.savePage` before `saveMetadataOnlyBlocks` — removing the standalone savePage breaks it** — The plan (Task 2.1.1c) removes the standalone `writeActor.savePage(page, priority)` call at line 1466. But the METADATA_ONLY path exits at line 1479 after calling `saveMetadataOnlyBlocks`, which means it exits BEFORE reaching `dispatchFullBlockWrites` where `savePage` is now located. If the plan removes line 1466's savePage call, METADATA_ONLY loads will never save the page. Recommendation: keep the standalone `writeActor.savePage(page, priority)` for the METADATA_ONLY branch only, OR move the composite savePage+saveBlocks approach into `saveMetadataOnlyBlocks` as well. The simplest fix: restructure the code so the METADATA_ONLY branch has its own savePage call. The FULL mode path uses the composite execute.

- [ ] **`diff.toDelete` FK constraint assumption needs verification** — The plan states "deletions must run before the composite write to avoid FK constraint violations when a block is deleted and re-inserted at a new position." However, the composite execute already calls `pageRepository.savePage` (which does INSERT OR REPLACE on the page) before block writes. FK constraints are `ON DELETE CASCADE` from pages to blocks, not the other way. The real concern is UNIQUE constraint on `blocks.uuid` — if a block is in `diff.toDelete` AND in `diff.toInsert` (same UUID, same page, different structural slot), the INSERT would fail if the DELETE hasn't completed. But `DiffMerge.diff()` should not produce a UUID in both `toDelete` and `toInsert`. Verify: ensure `diff.toDelete` and `diff.toInsert`/`diff.toUpdate` are disjoint sets. If confirmed disjoint, the ordering is still correct as written (deletes before inserts), but the reason cited in the plan is imprecise.

- [ ] **`evictHierarchyForPage` cast `(blockRepository as? SqlDelightBlockRepository)` will silently no-op in tests** — The plan calls `(blockRepository as? SqlDelightBlockRepository)?.evictHierarchyForPage(pageUuid.value)`. In test environments that inject `FakeBlockRepository`, this cast returns null and eviction is skipped. This is correct behavior for tests (fakes don't have hierarchy caches), but means the cache eviction is not tested. Recommendation: add `cacheEvictPage` to `BlockReadRepository` interface (it's already implemented in `SqlDelightBlockRepository` at line 1093–1095 and the interface at some level — verify) and call `blockRepository.cacheEvictPage(pageUuid)` instead of the unsafe cast. Check `BlockReadRepository.kt` for `cacheEvictPage`.

---

## Minors

- The plan's Task 3.1.1a creates a new test file `SqlDelightBlockRepositoryWarmPathTest.kt` in `jvmTest`. The test description says "Assert block row id (AUTOINCREMENT id) unchanged" — this is correct but note that `id` is not exposed in the `Block` domain model (`toBlockModel()` at line 1029 does not map `id`). The assertion will need to query the DB directly via `queries.selectBlockByUuid(uuid).executeAsOne().id` after calling `saveBlocksUpdate`. This requires the test to hold a reference to `SteleDatabaseQueries`, not just the repository.

- Task 2.1.1b shows the `else -> {}` branch capturing `blocksToInsert = diff.toInsert` and `blocksToUpdate = diff.toUpdate` from the `diff` object. Confirm `DiffMerge.Diff` has both `toInsert: List<Block>` and `toUpdate: List<Block>` fields (requirements.md implies this; the current `dispatchFullBlockWrites` at line 1380 uses `diff.toInsert + diff.toUpdate` as `blocksToWrite`, suggesting `toUpdate` exists). No action required if confirmed.

- The `db.savePage` span that currently wraps the standalone `writeActor.savePage()` call (line 1465) will be lost when that call is removed. The composite execute span is named `db.saveBlocks`. Consider renaming it to `db.savePageAndBlocks` or adding a nested span for `savePage` inside the composite execute lambda to preserve span-level observability in future traces.

---

## Resolution Status

All four concerns were addressed in the plan.md revision (2026-06-02):

1. ✅ Changed "Add `saveBlocksUpdate` to `BlockRepository.kt`" → `BlockWriteRepository.kt` (Story 1.1.2 added)
2. ✅ Added task to implement `saveBlocksUpdate` in `FakeBlockRepository` (Task 1.1.2b)
3. ✅ Added explicit `dispatchFullBlockWrites` signature update with `page: Page` parameter (Task 2.1.1b Step 2)
4. ✅ METADATA_ONLY path explicitly handled — keeps standalone `writeActor.savePage` for METADATA_ONLY branch; FULL mode moves savePage into composite execute (Task 2.1.1a restructuring)

Remaining minors (no action required):
- `id` field not in Block model — test will need to query DB directly via `queries.selectBlockByUuid().id`
- `db.savePage` span lost — acceptable tradeoff; composite span renamed `db.saveBlocks` covers both
