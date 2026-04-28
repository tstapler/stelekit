# Journal Service Extraction

**Status**: Planning  
**Created**: 2026-04-11  
**Triggered by**: Bug fix for duplicate journal creation (underscore vs. hyphen filename formats)

---

## Epic Overview

### Problem Statement

The `PageRepository` interface contains journal-domain operations (`getJournalPages`, `getJournalPageByDate`) that violate the Single Responsibility Principle. A generic page-storage abstraction should not encode knowledge about what a journal is or how journals are located. This domain leak has two concrete consequences:

1. Every `PageRepository` implementation (SQLDelight, InMemory, Datascript) must implement journal-specific logic even when used in non-journal contexts.
2. Journal creation logic (`generateTodayJournal`) lives in a UI ViewModel, making it impossible to reuse from other entry points (e.g., a future CLI, a background sync agent, or a test harness) without instantiating a ViewModel.

The bug that triggered this — a journal page being created twice because `2026_04_11` (disk) and `2026-04-11` (in-app) were treated as different pages — was fixed by adding `getJournalPageByDate`. That fix is correct but was placed in the wrong layer.

### Success Metrics

- `PageRepository` interface no longer contains any method with the word "journal" in its name.
- `generateTodayJournal` logic no longer lives in `JournalsViewModel`; the ViewModel delegates to `JournalService`.
- `GraphLoader` resolves journal pages by date using a single, centrally-owned helper rather than duplicating the lookup pattern in two private functions.
- All three `PageRepository` implementations (`SqlDelightPageRepository`, `InMemoryPageRepository`, `DatascriptPageRepository`) are slimmer by the removed methods.
- No regression: today's journal is still created on app launch, and loading a disk file whose name uses underscores does not create a duplicate page.

---

## Architecture Decisions

### ADR-1: Where does `getJournalPages` live?

**Question**: Should `getJournalPages(limit, offset)` be removed from `PageRepository` and provided exclusively through `JournalService`, or should it stay on `PageRepository` as a query convenience?

**Context**: `getJournalPages` is a filtered, ordered query — conceptually a view over the page table. Its SQL implementation (`selectJournalPages`) is a simple `WHERE is_journal = 1 ORDER BY journal_date DESC LIMIT ? OFFSET ?`. The InMemory and Datascript implementations also contain this filter directly. The query is only ever called from `JournalsViewModel.startPaginationObserver()`.

**Decision**: Move `getJournalPages` to `JournalService` and remove it from `PageRepository`.

**Rationale**: The method is only consumed by journal-domain code, and leaving it on `PageRepository` would create an asymmetry — `getJournalPageByDate` is gone but `getJournalPages` remains — that future contributors would rightly question. Centralizing all journal queries in `JournalService` gives one obvious place to add future journal-specific queries (e.g., `getJournalPageRange`, `getJournalStreak`). `JournalService` will delegate to a package-private `PageRepository` extension that the SQLDelight-backed implementation provides through the existing query.

**Rejected alternative**: Keep `getJournalPages` on `PageRepository` as a performance convenience. Rejected because the performance argument is weak — a `Flow<Result<List<Page>>>` filtered in `JournalService` using `getAllPages` would be reactive and correct, but less efficient than a native SQL query. The better solution is for `JournalService` to call a new internal repository method through a narrower helper interface (see ADR-2).

### ADR-2: How does `GraphLoader` get journal lookup without depending on `JournalService`?

**Question**: Should `GraphLoader` import and depend on `JournalService`, or should it receive a narrower lookup helper?

**Context**: `GraphLoader` calls `pageRepository.getJournalPageByDate(journalDate)` in two places: `parseAndSavePage` (line 715) and `parsePageWithoutSaving` (line 621). If `getJournalPageByDate` is removed from `PageRepository`, `GraphLoader` needs another mechanism.

**Decision**: Extract a `JournalDateResolver` functional interface (a single-method SAM interface) injected into `GraphLoader`. `JournalService` itself provides a concrete implementation.

```kotlin
// In repository package (or a shared util package)
fun interface JournalDateResolver {
    suspend fun getPageByJournalDate(date: LocalDate): Page?
}
```

`GraphLoader` constructor becomes:

```kotlin
class GraphLoader(
    private val fileSystem: FileSystem,
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val journalDateResolver: JournalDateResolver
)
```

**Rationale**: `GraphLoader` lives in the `db` package; `JournalService` will live in the `repository` or `service` package. A direct import in the wrong direction (`db` importing from `service`) would introduce a cycle risk. A SAM interface that is defined at the boundary keeps the dependency pointing inward. It also makes `GraphLoader` independently testable: tests can pass a simple lambda rather than constructing a full `JournalService`.

**Rejected alternative**: Pass `JournalService` directly into `GraphLoader`. Rejected because it couples the loader to the full service surface and creates a circular dependency risk if `JournalService` ever needs `GraphLoader` for on-demand page loading.

### ADR-3: Package placement for `JournalService`

**Decision**: Place `JournalService` in `dev.stapler.stelekit.repository` alongside the other repository types.

**Rationale**: `JournalService` wraps repository access and has no UI concerns. The `repository` package already contains domain-adjacent service logic (the `RepositoryFactory` wires everything together). This avoids creating a half-empty `service` package for a single class. If the project grows additional domain services (e.g., `SearchService`, `ExportService`), they can be co-located or migrated to a `service` package at that point.

### ADR-4: `RepositorySet` and wiring

**Decision**: Add `JournalService` as a first-class member of `RepositorySet` and add a `createJournalService` method to `RepositoryFactoryImpl`.

**Rationale**: `RepositorySet` is already the canonical bundle passed around the app. Adding `journalService: JournalService` there makes it naturally available wherever a `RepositorySet` is constructed, without requiring callers to wire it manually. The `Repositories` singleton object gets a `journal()` accessor analogous to `block()` and `page()`.

---

## Proposed `JournalService` API

```kotlin
package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

class JournalService(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository
) : JournalDateResolver {

    /**
     * Format-agnostic lookup by calendar date.
     * This is the single canonical way to find a journal page — never look up by name.
     */
    fun getJournalPageByDate(date: LocalDate): Flow<Result<Page?>> =
        pageRepository.getJournalPageByDate(date)

    /**
     * Implements JournalDateResolver SAM for injection into GraphLoader.
     */
    override suspend fun getPageByJournalDate(date: LocalDate): Page? =
        pageRepository.getJournalPageByDate(date).first().getOrNull()

    /**
     * Paginated journal listing, sorted descending by date.
     */
    fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> =
        pageRepository.getJournalPages(limit, offset)

    /**
     * Ensures today's journal page exists, creating it (with an empty seed block)
     * if absent. Returns the existing or newly created page.
     *
     * This absorbs the logic from JournalsViewModel.generateTodayJournal().
     * Idempotent — safe to call multiple times.
     */
    suspend fun ensureTodayJournal(): Page {
        val today = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        val existing = pageRepository.getJournalPageByDate(today).first().getOrNull()
        if (existing != null) return existing

        val pageName = today.toString() // YYYY-MM-DD canonical form
        val pageUuid = UuidGenerator.generateV7()
        val now = Clock.System.now()
        val newPage = Page(
            uuid = pageUuid,
            name = pageName,
            createdAt = today.atStartOfDayIn(TimeZone.currentSystemDefault()),
            updatedAt = now,
            isJournal = true,
            journalDate = today
        )
        pageRepository.savePage(newPage)

        val seedBlock = Block(
            uuid = UuidGenerator.generateV7(),
            pageUuid = pageUuid,
            content = "",
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        blockRepository.saveBlock(seedBlock)
        return newPage
    }
}
```

Note: `pageRepository.getJournalPageByDate` and `pageRepository.getJournalPages` remain on `PageRepository` **during the migration** as `internal` or package-scoped methods. They are removed from the public interface once all callers are migrated to `JournalService`.

---

## Story Breakdown

Stories are ordered by dependency. Each is sized for a single focused LLM session (small file count, clear input/output).

### Story 1 — Create `JournalDateResolver` interface and `JournalService` skeleton

**Files to create/edit**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/JournalService.kt` (create)

**Tasks**:
1. Define `JournalDateResolver` fun interface in the new file.
2. Implement `JournalService` class with constructor taking `PageRepository` and `BlockRepository`.
3. Implement `getJournalPageByDate`, `getJournalPages`, and `ensureTodayJournal` delegating to existing `pageRepository` methods.
4. Implement `JournalDateResolver` on `JournalService` (single `override suspend fun getPageByJournalDate`).
5. Add `@Suppress("NOTHING_TO_INLINE")` / KDoc for all public members.

**Acceptance criteria**: File compiles. No callers changed yet. Existing tests still pass.

**Dependencies**: None — purely additive.

---

### Story 2 — Wire `JournalService` into `RepositoryFactoryImpl` and `RepositorySet`

**Files to edit**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/RepositoryFactory.kt`

**Tasks**:
1. Add `journalService: JournalService` field to `RepositorySet` data class.
2. Add `createJournalService(backend: GraphBackend): JournalService` to `RepositoryFactory` interface.
3. Implement `createJournalService` in `RepositoryFactoryImpl` (constructs a `JournalService` with the same backend's page and block repositories).
4. Update `createRepositorySet` in `RepositoryFactoryImpl` to include `journalService`.
5. Add `fun journal(...)` accessor to the `Repositories` singleton.

**Acceptance criteria**: `RepositorySet` includes `journalService`. `Repositories.journal()` returns a `JournalService`. Compiles and existing tests pass.

**Dependencies**: Story 1 complete.

---

### Story 3 — Migrate `GraphLoader` to use `JournalDateResolver`

**Files to edit**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`

**Tasks**:
1. Add `journalDateResolver: JournalDateResolver` as a constructor parameter to `GraphLoader`.
2. Replace the two call sites of `pageRepository.getJournalPageByDate(journalDate)` in `parseAndSavePage` (line 715) and `parsePageWithoutSaving` (line 621) with `journalDateResolver.getPageByJournalDate(journalDate)`.
3. Update all construction sites of `GraphLoader` in the codebase to pass in the resolver. Search for `GraphLoader(` to find them.
4. In test code, use a lambda: `JournalDateResolver { date -> null }` or a simple stub.

**Acceptance criteria**: `GraphLoader` no longer calls any method on `PageRepository` with "journal" in its name. Compiles and existing tests pass.

**Dependencies**: Stories 1 and 2 complete (resolver type must exist; concrete impl provided by `JournalService`).

---

### Story 4 — Migrate `JournalsViewModel` to use `JournalService`

**Files to edit**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/JournalsViewModel.kt`

**Tasks**:
1. Replace `private val pageRepository: PageRepository` constructor parameter with `private val journalService: JournalService` (keep `blockRepository: BlockRepository` and `graphLoader: GraphLoader`).
2. Replace `generateTodayJournal()` body with a delegation call: `journalService.ensureTodayJournal()`.
3. Replace `pageRepository.getJournalPages(...)` in `startPaginationObserver` with `journalService.getJournalPages(...)`.
4. Remove the now-unused `pageRepository` import.
5. Update all construction sites of `JournalsViewModel` to pass `journalService` instead of `pageRepository`.
6. Remove `generateTodayJournal` implementation body — keep the public function signature as a one-liner delegating to `scope.launch { journalService.ensureTodayJournal() }`.

**Acceptance criteria**: `JournalsViewModel` has no direct reference to `PageRepository`. Today's journal is still created on startup. Existing tests pass.

**Dependencies**: Story 2 complete (JournalService wired and accessible).

---

### Story 5 — Remove journal methods from `PageRepository` interface and all implementations

**Files to edit**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightPageRepository.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/InMemoryRepositories.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/DatascriptPageRepository.kt`

**Tasks**:
1. Remove `getJournalPages(limit: Int, offset: Int)` from `PageRepository` interface.
2. Remove `getJournalPageByDate(date: LocalDate)` from `PageRepository` interface.
3. In `SqlDelightPageRepository`: keep the implementations but make them `internal` (they are still needed by `JournalService` if `JournalService` is in the same package). Alternatively, inline the SQL call directly inside `JournalService` if it lives alongside the repository.
4. In `InMemoryPageRepository` and `DatascriptPageRepository`: delete both overrides entirely (they are no longer required by an interface, and `JournalService` for these backends can implement the filtering in-service using `getAllPages`).
5. Update `JournalService` to handle InMemory/Datascript backends: for those backends, `getJournalPages` filters `pageRepository.getAllPages()` in memory.
6. Run full compile and test suite.

**Acceptance criteria**: `PageRepository` interface has no journal-specific methods. Grep for `getJournalPage` in `GraphRepository.kt` returns zero matches. All implementations compile without the removed overrides.

**Dependencies**: Stories 3 and 4 complete (no remaining callers on the old interface).

---

### Story 6 — Update `RepositorySet` construction sites and integration smoke test

**Files to edit**:
- Any file that constructs `GraphLoader`, `JournalsViewModel`, or `RepositorySet` directly.
- Existing test files under `kmp/src/commonTest/`.

**Tasks**:
1. Grep for `GraphLoader(` and `JournalsViewModel(` to find all construction sites not already updated in Stories 3–4.
2. Verify `AppState.kt` or equivalent platform-specific wiring passes `JournalService` correctly.
3. Add or update one integration-level test that:
   - Creates an `InMemoryPageRepository` with no pages.
   - Calls `JournalService.ensureTodayJournal()`.
   - Asserts exactly one page and one block exist with the correct `journalDate`.
   - Calls `ensureTodayJournal()` a second time.
   - Asserts still exactly one page (idempotency).
4. Add a unit test for the underscore-vs-hyphen deduplication scenario:
   - Save a page with `journalDate = LocalDate(2026, 4, 11)` and `name = "2026_04_11"`.
   - Call `JournalService.ensureTodayJournal()` (mocked to treat today as 2026-04-11).
   - Assert no new page was created.

**Acceptance criteria**: All new and existing tests pass. No compile errors anywhere in the project.

**Dependencies**: Story 5 complete.

---

## Dependency Visualization

```
Story 1 (JournalService skeleton)
    |
    v
Story 2 (RepositoryFactory wiring)
    |
    +-----> Story 3 (GraphLoader migration)
    |
    +-----> Story 4 (JournalsViewModel migration)
                    |
                    v (both 3 and 4 must complete)
              Story 5 (Remove from PageRepository interface)
                    |
                    v
              Story 6 (Smoke tests and construction-site cleanup)
```

Stories 3 and 4 can be executed in parallel once Story 2 is done. Story 5 cannot start until both Stories 3 and 4 are complete, because removing the interface methods before migrating the callers will cause compile errors.

---

## Known Risks and Potential Bugs

### Risk 1 — Race condition during `ensureTodayJournal` (Severity: Medium)

**Description**: If `ensureTodayJournal` is called concurrently — e.g., from a background sync agent and from `JournalsViewModel.init` — the check-then-create sequence (`getJournalPageByDate` then `savePage`) is not atomic. Two concurrent callers could both see `existing == null` and both attempt to create the page.

**Why this matters for the migration**: The original `generateTodayJournal` had the same bug, but it was hidden by being called from a single ViewModel. As `ensureTodayJournal` becomes a shared service method, the risk surface widens.

**Mitigation**:
- Add a `Mutex` inside `JournalService` guarding `ensureTodayJournal`.
- `savePage` in `SqlDelightPageRepository` uses `INSERT OR IGNORE` + `UPDATE`, so the second writer will upsert to the same UUID if both created the same page — but they will generate different UUIDs (`UuidGenerator.generateV7()`), causing two rows.
- The database does not have a unique constraint on `(is_journal = 1, journal_date)`. Consider adding one as a follow-up, which would make the race fail safely rather than silently inserting a duplicate.

**Test coverage**: The idempotency test in Story 6 should use two coroutines launched concurrently.

---

### Risk 2 — `InMemoryPageRepository` and `DatascriptPageRepository` lose efficient `getJournalPages` (Severity: Low)

**Description**: After Story 5, these backends no longer have a native `getJournalPages` implementation. `JournalService` for these backends must fall back to `getAllPages().map { pages -> pages.filter { it.isJournal } }`. For large graphs (thousands of pages), this emits the full list on every update.

**Mitigation**: This is acceptable for testing backends (InMemory and Datascript are not used in production for large graphs). Document the limitation in `JournalService` with a comment. If the Datascript backend ever becomes production-grade, add a dedicated journal index at that point.

---

### Risk 3 — `GraphLoader` construction sites not fully updated (Severity: High)

**Description**: `GraphLoader` is constructed in platform-specific wiring code (e.g., `AppState.kt`, Android `MainActivity`, or dependency injection setup). If any construction site is missed and not given a `journalDateResolver`, the code will not compile, which will be caught at build time. However, if a stub resolver (`JournalDateResolver { null }`) is accidentally used in production wiring, journals loaded from disk would always create duplicates.

**Mitigation**: Grep for all `GraphLoader(` occurrences before closing Story 3. The correct resolver in all production paths is `journalService` (from `RepositorySet.journalService`).

---

### Risk 4 — `JournalService.ensureTodayJournal` creates a page without a `filePath` (Severity: Low)

**Description**: The in-app created journal page has `filePath = null`. When `GraphLoader` subsequently scans the journals directory and encounters `2026_04_11.md`, it calls `getPageByJournalDate` (now via `journalDateResolver`), finds the existing page, and updates it — setting `filePath` to the real path. This is the intended behavior and was working before the extraction. The migration must not break this update path.

**Mitigation**: Verify in Story 6's smoke test that after `ensureTodayJournal()` + a simulated file load, the page's `filePath` is set correctly. The fix relies on `parseAndSavePage` calling `pageRepository.savePage(page)` which does `INSERT OR IGNORE` + `UPDATE` — the `file_path` field is included in the `UPDATE` statement (confirmed in `SqlDelightPageRepository.savePage` lines 104–116).

---

### Risk 5 — `getJournalPages` Flow semantics change for InMemory backend (Severity: Low)

**Description**: Currently `InMemoryPageRepository.getJournalPages` returns a `MutableStateFlow`-derived `Flow` that re-emits whenever any page is saved (reactive). After Story 5, `JournalService` would use `getAllPages().map { filter }` which preserves the same reactive property. However, the filter runs on every emission even for non-journal page saves. For the InMemory backend (used only in tests), this is a non-issue in practice.

---

## Files Changed Summary

| File | Change |
|------|--------|
| `repository/GraphRepository.kt` | Remove `getJournalPages`, `getJournalPageByDate` from `PageRepository` interface; add `JournalDateResolver` fun interface; add `journalService` to `RepositorySet`; add `createJournalService` to `RepositoryFactory` |
| `repository/JournalService.kt` | Create new file |
| `repository/RepositoryFactory.kt` | Implement `createJournalService`; add `journal()` to `Repositories` singleton |
| `repository/SqlDelightPageRepository.kt` | Make `getJournalPages` and `getJournalPageByDate` `internal` (or move body into `JournalService`) |
| `repository/InMemoryRepositories.kt` | Remove `getJournalPages` and `getJournalPageByDate` overrides from `InMemoryPageRepository` |
| `repository/DatascriptPageRepository.kt` | Remove `getJournalPages` and `getJournalPageByDate` overrides |
| `db/GraphLoader.kt` | Add `journalDateResolver: JournalDateResolver` constructor param; replace two `pageRepository.getJournalPageByDate` call sites |
| `ui/screens/JournalsViewModel.kt` | Replace `pageRepository` param with `journalService`; delegate `generateTodayJournal` and pagination to service |
| Test files | Add `ensureTodayJournal` idempotency and concurrency tests; add underscore/hyphen dedup test |
