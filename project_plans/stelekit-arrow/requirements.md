# Requirements: Arrow Integration for SteleKit

## Project Context

SteleKit is a Kotlin Multiplatform (KMP) outliner/note-taking app targeting JVM Desktop, Android, iOS, and WASM/Web. The codebase currently uses Kotlin's built-in `Result<T>`, `Flow<Result<T>>`, `Mutex`, coroutine `Channel`s, and `CompletableDeferred` for error handling and concurrency. No Arrow library is currently present.

This project integrates Arrow (`arrow-kt`) across the full codebase — a complete architectural migration, not just targeted additions.

## Scope: Affected Areas

The following modules/packages are in scope for migration:

| Area | Package | Current Pain |
|---|---|---|
| Repository interfaces | `data/repositories/`, `repository/` | `Flow<Result<T>>` loses error type; errors are `Throwable` with no domain semantics |
| DB write coordination | `db/DatabaseWriteActor.kt` | Manual dual-channel priority queue with `CompletableDeferred<Result<Unit>>` — complex, hard to test |
| File I/O (save/load) | `db/GraphWriter.kt`, `db/GraphLoader.kt` | Multi-step disk+DB pipeline has no rollback on partial failure; `Mutex`+mutable maps for debounce |
| Graph lifecycle | `db/GraphManager.kt` | Resource acquisition (DB driver, CoroutineScope, RepositorySet) has no structured release guarantee |
| Error domain | `error/` | No typed domain error hierarchy — callers receive `Throwable` wrapped in `Result.failure` |
| Nested model updates | `model/` | Page → Block → children hierarchy requires multi-level copy() chains |
| File watching / retries | `db/GraphLoader.kt`, `db/GraphWriter.kt` | No structured retry or backoff for transient SQLITE_BUSY or file-lock errors |
| Voice pipeline | `voice/` | Multi-step pipeline (record → transcribe → format → save) has no compensating action on partial failure |

## Functional Requirements

### FR-1: Typed Error Handling (Arrow Core — Either / Raise / Effect)

- Replace all `Result<T>` return types with Arrow's `Either<DomainError, T>` at repository and service boundaries.
- Define a sealed `DomainError` hierarchy covering: `DatabaseError`, `FileSystemError`, `ParseError`, `ConflictError`, `ValidationError`, `NetworkError`.
- Use `arrow.core.raise.Raise` / `Effect` for internal computation pipelines; convert to `Either` at public API boundaries.
- `Flow<Result<T>>` becomes `Flow<Either<DomainError, T>>`.
- The UI layer receives `Either` and maps errors to typed UI states — no raw `Throwable` in `StateFlow`.

### FR-2: Optics for Nested Data (Arrow Optics)

- Apply `@optics` code generation to `Page`, `Block`, `Property`, and `AppState` model classes.
- Replace multi-level `.copy()` chains in `BlockStateManager`, `StelekitViewModel`, and editor state reducers with optic lenses and traversals.
- Provide a `Traversal<Page, Block>` for bulk block mutations (e.g., re-indexing, bulk property writes).

### FR-3: Structured Resource Lifecycle (Arrow Fx Coroutines — Resource)

- Wrap `GraphManager`'s per-graph resource acquisition (DB driver, RepositorySet, CoroutineScope) in `Resource<GraphResources>`.
- Wrap `GraphWriter`'s scope and debounce state in `Resource<GraphWriterHandle>`.
- File handles in `GraphLoader` opened during import use `Resource<File>` to guarantee close on cancellation.
- Resource finalizers must run even on coroutine cancellation and `CancellationException`.

### FR-4: STM for Concurrent State (Arrow Fx Coroutines — STM)

- Replace the `DatabaseWriteActor`'s manual dual-channel priority queue with Arrow STM `TVar`s and atomic transactions.
- Replace `GraphWriter`'s `pendingByPage: MutableMap` + `Mutex` debounce state with STM `TMap` / `TVar` for lock-free reads.
- STM transactions compose without locks; concurrent reads and writes are safe across all platforms where STM is supported.

### FR-5: Saga Pattern for Disk Writes (Arrow Fx Coroutines — Saga)

- The disk-write pipeline (`GraphWriter` → `DatabaseWriteActor` → SQLite → filesystem) is a multi-step operation requiring rollback on partial failure.
- Model this as an Arrow `Saga`: each step (`writeMarkdownFile`, `upsertPageInDB`, `upsertBlocksInDB`, `updateSidecar`) has a defined compensating action.
- On partial failure, compensation runs in reverse order: reverts DB changes if file write failed, or re-reads file if DB commit failed.
- The voice pipeline (`record → transcribe → LLM format → save block`) also uses a `Saga` to revert intermediate steps on failure.

### FR-6: Resilience — Retry, Schedule, Circuit Breaker (Arrow Resilience)

- Wrap SQLite operations in `Schedule.recurs(3).jittered()` to handle transient `SQLITE_BUSY` / lock errors.
- Wrap file-watching re-registration in `Schedule.exponential(100.milliseconds).untilOutput { it < 5.seconds }`.
- Add a circuit breaker around `ClaudeLlmFormatterProvider` (external HTTP calls) to prevent cascading failures during network partition.
- All `Schedule` policies are defined as named constants in a `RetryPolicies` object for testability.

## Non-Functional Requirements

### NFR-1: KMP Compatibility
- All Arrow modules used must support all 4 targets: JVM, Android, iOS (native), WASM/JS.
- Arrow Core (Either, Option, NonEmptyList, Raise) — ✅ all platforms.
- Arrow Optics + compiler plugin — ✅ all platforms.
- Arrow Fx Coroutines (Resource, STM) — ✅ all platforms.
- Arrow Resilience (Schedule, retry, CircuitBreaker) — ✅ all platforms (uses coroutines).
- **Platform limitation**: STM uses atomic compare-and-swap — on WASM/JS single-threaded runtime, STM degrades gracefully to sequential execution (no contention anyway).

### NFR-2: Backwards Compatibility During Migration
- Repository interfaces are migrated in a single pass — no dual `Result`/`Either` overloads.
- UI-layer callers are migrated in the same PR as their repository dependencies.
- No `@Deprecated` shims left in place after migration.

### NFR-3: Performance
- STM replaces Mutex/Channel — must not regress `DatabaseWriteActor` throughput benchmarks.
- Optics must not allocate more than equivalent `.copy()` chains (verify with existing bench module).
- Resource wrappers add negligible overhead — resource acquisition is not on the hot path.

### NFR-4: Testability
- `DomainError` hierarchy is exhaustively testable — tests assert specific error variants, not `Throwable` messages.
- Saga compensations are unit-testable in isolation.
- `RetryPolicies` constants can be overridden in tests (zero-delay schedule for unit tests).

## Out of Scope

- Arrow Analysis (proof plugin) — too experimental for production.
- Arrow optics for `StateFlow` composition — handled by existing `collectAsState()` pattern.
- Removing `PlatformDispatcher` — dispatcher abstraction remains; Arrow does not replace it.

## Target Arrow Version

Arrow 2.x (latest stable as of 2026-04-25). Modules:
- `io.arrow-kt:arrow-core`
- `io.arrow-kt:arrow-optics`
- `io.arrow-kt:arrow-optics-ksp-plugin` (KSP annotation processor)
- `io.arrow-kt:arrow-fx-coroutines`
- `io.arrow-kt:arrow-resilience`

## Success Criteria

1. Zero `Result<T>` at repository/service API boundaries — replaced by `Either<DomainError, T>`.
2. `DomainError` sealed hierarchy covers all current exception types thrown across `db/`, `repository/`, `domain/`.
3. `GraphManager` and `GraphWriter` resource cleanup is guaranteed (verified by test: cancel parent scope, assert finalizers ran).
4. At least `DatabaseWriteActor` and `GraphWriter` debounce state use STM.
5. Disk-write saga implemented with at least 2 compensation steps tested.
6. All CI checks pass on all platforms (`./gradlew ciCheck`).
7. Existing benchmark results do not regress by more than 5%.
