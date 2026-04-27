# Architecture Research: SteleKit Migration Map for Arrow 2.x

## Source Files Analyzed

| File | Lines | Key Patterns |
|---|---|---|
| `db/DatabaseWriteActor.kt` | 368 | Dual `Channel`, `CompletableDeferred<Result<Unit>>`, priority queue |
| `db/GraphWriter.kt` | 275 | `Mutex` + `MutableMap` debounce, `CoroutineScope` nullable, fire-and-forget |
| `db/GraphManager.kt` | 380 | Manual `CoroutineScope` + `cancel()`, `CompletableDeferred`, no resource guarantee |
| `db/GraphLoader.kt` | 1203 | Nested Mutex maps, `Result<T>` propagation, `WriteError` data class |
| `repository/GraphRepository.kt` | 554 | All repository interfaces return `Flow<Result<T>>` or `Result<Unit>` |
| `data/repositories/IBlockRepository.kt` | 524 | Legacy interface — same `Flow<Result<T>>` pattern |
| `error/ErrorTracker.kt` | 30 | No typed domain errors — only `Throwable` passed through |
| `model/Models.kt` | 151 | `Page`, `Block`, `Property` — plain data classes, no `@optics` |
| `repository/RepositoryFactory.kt` | 301 | Manual resource lifecycle in `createRepositorySet`, no structured release |

---

## Current Pattern → Arrow Replacement Table

| File | Current Pattern | Arrow Replacement | Complexity |
|---|---|---|---|
| `error/ErrorTracker.kt` | `Throwable`-only error interface; no hierarchy | Sealed `DomainError` hierarchy: `DatabaseError`, `FileSystemError`, `ParseError`, `ConflictError`, `ValidationError`, `NetworkError` | Low — new file |
| `repository/GraphRepository.kt` | `Flow<Result<Block?>>`, `Result<Unit>` on all interfaces | `Flow<Either<DomainError, Block?>>`, `suspend fun` returns `Either<DomainError, Unit>` | Medium — interface-only change cascades to all implementations |
| `data/repositories/IBlockRepository.kt` | Same `Flow<Result<T>>` contract | Same migration as `GraphRepository.kt` | Medium |
| `model/Models.kt` | Plain `data class Page`, `Block`, `Property` | Add `@optics` annotation + `companion object` to each | Low — annotation only, but triggers KSP rebuild |
| `db/DatabaseWriteActor.kt` | `Channel<WriteRequest>(UNLIMITED)` × 2, `CompletableDeferred<Result<Unit>>` per request, manual `select {}` dispatch | `TVar<WriteQueues>` STM priority queue; `Either<DomainError, Unit>` replaces `Result<Unit>` in deferred | High — core concurrency rewrite |
| `db/GraphWriter.kt` | `pendingByPage: MutableMap<String, Pair<Job, SaveRequest>>` + `pendingMutex: Mutex` | `TMap<String, SaveRequest>` STM for debounce state; `Resource<GraphWriterHandle>` for scope lifecycle; Saga for disk+DB write pipeline | High — concurrent state + resource + saga |
| `db/GraphManager.kt` | Manual `CoroutineScope` + `activeGraphJobs: MutableMap` + `currentFactory?.close()` in `shutdown()` | `Resource<GraphResources>` wrapping driver + scope + RepositorySet; finalizers guaranteed even on cancellation | High — lifecycle overhaul |
| `db/GraphLoader.kt` | `fileLocks: MutableMap<String, Mutex>`, `priorityFiles: MutableSet`, `writeErrors: MutableSharedFlow` | `TMap<String, Unit>` for priorityFiles; `Either<DomainError, Unit>` return types; `Schedule.recurs(3).jittered()` for write retries | High — many Mutex→STM conversions |
| `repository/RepositoryFactory.kt` | `createRepositorySet` wires everything with no structured release guarantee | `Resource<RepositorySet>` — each component (driver, scope, actor) is an `install()` step | Medium |
| `repository/SqlDelight*Repository.kt` | `Result<Unit>` returns, `runCatching {}` around SQL | `Either<DatabaseError, Unit>` returns, `either { }` block | Medium — mechanical substitution |
| UI ViewModels | `result.getOrNull()`, `result.isFailure` on collected flows | `result.fold(ifLeft = ..., ifRight = ...)` typed error mapping | Medium — all collectors need updating |
| Voice pipeline | Multi-step fire-and-forget with no rollback | Arrow `saga { }` with compensating actions | Medium — new structure |

---

## Migration Priority Order

### Tier 1 — Foundation (migrate first, no API breakage yet)

1. **`error/DomainError.kt` (new file)** — Define the sealed `DomainError` hierarchy. No callers yet. Zero breakage.

2. **`model/Models.kt`** — Add `@optics` + `companion object` to `Page`, `Block`, `Property`. Purely additive. No callers change.

3. **`build.gradle.kts`** — Add Arrow dependencies, KSP configuration. Can be done in a separate commit before any code changes.

### Tier 2 — Repository Layer (isolated, high impact)

4. **`repository/GraphRepository.kt`** — Change all interface signatures: `Flow<Result<T>>` → `Flow<Either<DomainError, T>>`, `Result<Unit>` → `Either<DomainError, Unit>`.

5. **`repository/InMemoryRepositories.kt`** — Update in-memory implementations (used in tests) to match new interfaces. Tests continue to compile.

6. **`repository/SqlDelight*Repository.kt`** (5 files) — Update SQLDelight implementations. Wrap SQL calls in `either { }` blocks, map SQL exceptions to `DatabaseError`.

7. **`data/repositories/IBlockRepository.kt`** — Align legacy interface (used by `IBlockOperations`). Same pattern as step 4.

### Tier 3 — DB Layer (core concurrency)

8. **`db/DatabaseWriteActor.kt`** — Replace dual `Channel` + `CompletableDeferred<Result<Unit>>` with STM `TVar<WriteQueues>`. Change return types to `Either<DomainError, Unit>`.

9. **`db/GraphWriter.kt`** — Replace `pendingByPage: MutableMap` + `pendingMutex` with `TMap<String, SaveRequest>`. Replace `saveMutex: Mutex` with STM transaction. Wrap disk+DB write pipeline as Arrow Saga.

10. **`db/GraphLoader.kt`** — Replace `fileLocksMutex + fileLocks: MutableMap<String, Mutex>` and `priorityFilesMutex + priorityFiles: MutableSet` with STM `TMap`/`TVar`. Add `Schedule.recurs(3)` retry around `writeActor.saveBlocks()`.

### Tier 4 — Graph Lifecycle

11. **`db/GraphManager.kt`** — Replace manual `CoroutineScope` + `activeGraphJobs: MutableMap` + `currentFactory?.close()` pattern with `Resource<GraphResources>`. GraphManager exposes `Resource<GraphResources>` instead of managing lifecycle manually.

12. **`repository/RepositoryFactory.kt`** — Change `createRepositorySet()` to return `Resource<RepositorySet>`. Each sub-resource (driver, scope, actor) becomes an `install()` step inside the resource builder.

### Tier 5 — UI Layer (must match repository changes)

13. **`ui/StelekitViewModel.kt`** — Collect `Flow<Either<DomainError, T>>` instead of `Flow<Result<T>>`. Map `DomainError` variants to typed UI states. No `Throwable` in `StateFlow`.

14. **All screen ViewModels** (`AllPagesViewModel`, `JournalsViewModel`, `SearchViewModel`, etc.) — Same pattern as StelekitViewModel.

15. **`voice/VoiceCaptureViewModel.kt`** — Replace multi-step pipeline with Arrow Saga.

---

## Call Graph: What Changes Propagate to What

```
DomainError (new)
    └── GraphRepository interfaces (Result → Either)
            ├── SqlDelightBlockRepository
            ├── SqlDelightPageRepository
            ├── SqlDelightPropertyRepository
            ├── SqlDelightReferenceRepository
            ├── SqlDelightSearchRepository
            ├── InMemoryBlockRepository (test)
            ├── InMemoryPageRepository (test)
            └── DatabaseWriteActor (uses BlockRepository, PageRepository)
                    ├── GraphLoader (uses DatabaseWriteActor)
                    │       └── StelekitViewModel (collects Flow<Either>)
                    │               └── All screen ViewModels
                    └── GraphWriter (uses DatabaseWriteActor)
                            └── StelekitViewModel (calls queueSave)

GraphManager (Resource<GraphResources>)
    └── RepositoryFactory.createRepositorySet() → Resource<RepositorySet>
            └── StelekitApp (top-level composable — uses .use {} or .allocated())

model/Models.kt (@optics)
    └── BlockStateManager (uses multi-level .copy() → optic lenses)
    └── StelekitViewModel (uses AppState copy chains → optic lenses)
    └── Editor reducers
```

---

## Current Error Handling Inventory

### `Result<T>` usage locations

| File | Pattern | Count (approx) |
|---|---|---|
| `GraphRepository.kt` | Interface return types `Result<Unit>`, `Flow<Result<T>>` | ~30 methods |
| `SqlDelightBlockRepository.kt` | `runCatching { }` around SQL | ~15 sites |
| `SqlDelightPageRepository.kt` | `runCatching { }` around SQL | ~10 sites |
| `DatabaseWriteActor.kt` | `CompletableDeferred<Result<Unit>>`, `Result.success/failure` | ~20 sites |
| `GraphLoader.kt` | `.getOrNull()`, `.isFailure`, `Result.failure(e)` | ~15 sites |
| `GraphWriter.kt` | `Result<Boolean>` return, `result.isSuccess` | ~5 sites |
| `GraphManager.kt` | Implicit — calls actor methods that return `Result<Unit>` | ~8 sites |

### `runCatching` / `try-catch` that produce `Result`

All SqlDelight repositories use the pattern:
```kotlin
override suspend fun savePage(page: Page): Result<Unit> = withContext(PlatformDispatcher.DB) {
    try {
        queries.insertPage(...)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

Post-migration:
```kotlin
override suspend fun savePage(page: Page): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
    either {
        try {
            queries.insertPage(...)
        } catch (e: Exception) {
            raise(DatabaseError.WriteFailed(e.message ?: "savePage failed"))
        }
    }
}
```

---

## Current Concurrency Inventory

| File | Primitive | Purpose | Arrow Replacement |
|---|---|---|---|
| `DatabaseWriteActor.kt` | `Channel<WriteRequest>(UNLIMITED)` × 2 | Priority queue for serialized writes | `TVar<WriteQueues>` STM |
| `DatabaseWriteActor.kt` | `CompletableDeferred<Result<Unit>>` per request | Caller await pattern | `TVar<Either<DomainError, Unit>>` or keep Deferred but change type |
| `DatabaseWriteActor.kt` | `select { highPriority.onReceive; lowPriority.onReceive }` | Priority drain | STM `retry()` + `atomically {}` |
| `DatabaseWriteActor.kt` | `CoroutineScope(SupervisorJob() + Default)` | Actor loop lifetime | Owned scope inside `Resource` |
| `GraphWriter.kt` | `pendingByPage: MutableMap<String, Pair<Job, SaveRequest>>` | Per-page debounce state | `TMap<String, SaveRequest>` |
| `GraphWriter.kt` | `pendingMutex: Mutex` | Guards debounce map | Eliminated by STM |
| `GraphWriter.kt` | `saveMutex: Mutex` | Single-writer guard for file writes | Eliminated — saga serializes |
| `GraphWriter.kt` | `scope: CoroutineScope?` | Job launch for debounce | `Resource<CoroutineScope>` |
| `GraphLoader.kt` | `fileLocksMutex: Mutex` + `fileLocks: MutableMap<String, Mutex>` | Per-file parse serialization | `TMap<String, Unit>` for in-flight set |
| `GraphLoader.kt` | `priorityFilesMutex: Mutex` + `priorityFiles: MutableSet<String>` | Priority file coalescing | `TVar<Set<String>>` STM |
| `GraphManager.kt` | `activeGraphJobs: MutableMap<String, CoroutineScope>` | Graph lifecycle tracking | `Resource<Map<String, GraphResources>>` |
| `GraphManager.kt` | `CompletableDeferred<Unit>` (`_pendingMigration`) | One-shot migration await | Keep as-is or use `Deferred<Unit>` |
| `GraphManager.kt` | `CoroutineScope(SupervisorJob() + Default)` | Manager-level scope | Top-level `Resource<CoroutineScope>` |

---

## Notes on `@DirectSqlWrite` Annotation

The existing `@DirectSqlWrite` opt-in annotation enforcement remains in place after migration. Arrow's `DatabaseWriteActor` replacement still gates writes — the annotation pattern guards against bypassing the actor, not the error type. No changes needed to `RestrictedDatabaseQueries.kt` or `DirectSqlWrite.kt`.

## Notes on `PlatformDispatcher`

Arrow STM uses `atomically {}` which is a suspend function — it runs on whatever coroutine context is active. The `PlatformDispatcher.DB` convention for database reads/writes is unaffected by the Arrow migration. Arrow does not replace dispatchers.
