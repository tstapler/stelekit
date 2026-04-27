# Implementation Plan: Arrow Integration for SteleKit

## Overview

This plan migrates SteleKit from Kotlin stdlib `Result<T>`, `Mutex`, `Channel`, and manual resource
management to Arrow 2.2.1.1 across all KMP targets (JVM, Android, iOS, WASM/JS). The migration is
ordered so every tier compiles and passes `./gradlew ciCheck` independently before the next begins.

---

## Tier 0: Build Wiring (Precondition)

**Rationale:** No Arrow code can compile until the Gradle build pulls the artifacts and KSP is
correctly wired to all compilation targets. This must land as a standalone commit that produces zero
compile errors but introduces no logic changes.

### Epic 0-A: Add Arrow to `kmp/build.gradle.kts`

**Goal:** Arrow BOM + 5 modules available on all 4 platforms; KSP generates optics metadata before
any compilation task runs.

#### Story 0-A-1: Apply KSP plugin and Arrow BOM

As a developer, I want Arrow's full dependency set resolved consistently so I never debug version
skew between Arrow modules.

Tasks:
1. In `kmp/build.gradle.kts` `plugins {}` block, add `id("com.google.devtools.ksp") version "2.2.0-1.0.29"` (KSP version must match Kotlin 2.2.x used in the project).
2. In `commonMain` dependencies, add:
   ```kotlin
   implementation(platform("io.arrow-kt:arrow-stack:2.2.1.1"))
   implementation("io.arrow-kt:arrow-core")
   implementation("io.arrow-kt:arrow-optics")
   implementation("io.arrow-kt:arrow-fx-coroutines")
   implementation("io.arrow-kt:arrow-resilience")
   ```
3. Verify `./gradlew dependencies --configuration commonMainImplementationDependenciesMetadata` resolves all 5 Arrow modules at 2.2.1.1.

#### Story 0-A-2: Configure multi-target KSP for Arrow Optics

As a developer, I want the `@optics` KSP processor to run on all 4 platforms so generated lenses
are available from every source set.

Tasks:
1. In the top-level `dependencies {}` block of `kmp/build.gradle.kts`, add:
   ```kotlin
   add("kspCommonMainMetadata", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
   add("kspJvm",                "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
   add("kspAndroid",            "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
   add("kspIosX64",             "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
   add("kspIosArm64",           "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
   add("kspIosSimulatorArm64",  "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
   ```
   If `enableJs=true`, also add `kspWasmJs`.
2. Add generated-source wiring to `commonMain`:
   ```kotlin
   kotlin.sourceSets.named("commonMain") {
       kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
   }
   ```
3. Add task dependency so all compilations wait for KSP metadata:
   ```kotlin
   tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>>().configureEach {
       if (name != "kspCommonMainKotlinMetadata") {
           dependsOn("kspCommonMainKotlinMetadata")
       }
   }
   ```
4. Optionally add `ksp { useKSP2 = true }` block to opt into incremental KSP2.
5. Run `./gradlew kspCommonMainKotlinMetadata` and confirm it succeeds with no sources yet.

---

## Tier 1: Foundation — Error Hierarchy + Optics Annotations

**Rationale:** `DomainError` is the error type used everywhere in Tiers 2–5. The `@optics`
annotations on model classes are purely additive — no callers change. Both changes produce zero
compile breakage and establish the primitives all later tiers consume.

### Epic 1-A: Define Sealed `DomainError` Hierarchy

**Goal:** Replace raw `Throwable` propagation with typed domain errors covering every failure mode
in `db/`, `repository/`, `voice/`, and `domain/`.

#### Story 1-A-1: Create `error/DomainError.kt`

As a developer, I want a typed error hierarchy so I can exhaustively match error variants in tests
and in the UI layer without inspecting exception messages.

Tasks:
1. Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`:
   ```kotlin
   package dev.stapler.stelekit.error

   sealed interface DomainError {
       val message: String

       sealed interface DatabaseError : DomainError {
           data class WriteFailed(override val message: String) : DatabaseError
           data class ReadFailed(override val message: String) : DatabaseError
           data class NotFound(val entity: String, val id: String) : DatabaseError {
               override val message: String = "$entity not found: $id"
           }
           data class TransactionFailed(override val message: String) : DatabaseError
       }

       sealed interface FileSystemError : DomainError {
           data class NotFound(val path: String) : FileSystemError {
               override val message: String = "File not found: $path"
           }
           data class WriteFailed(val path: String, override val message: String) : FileSystemError
           data class ReadFailed(val path: String, override val message: String) : FileSystemError
           data class DeleteFailed(val path: String, override val message: String) : FileSystemError
       }

       sealed interface ParseError : DomainError {
           data class EmptyFile(val path: String) : ParseError {
               override val message: String = "Empty file: $path"
           }
           data class InvalidSyntax(override val message: String) : ParseError
           data class MalformedMarkdown(override val message: String) : ParseError
       }

       sealed interface ConflictError : DomainError {
           data class DiskConflict(val pageUuid: String, override val message: String) : ConflictError
           data class ConcurrentWrite(val pageUuid: String, override val message: String) : ConflictError
       }

       sealed interface ValidationError : DomainError {
           data class InvalidUuid(val uuid: String) : ValidationError {
               override val message: String = "Invalid UUID: $uuid"
           }
           data class EmptyName(override val message: String) : ValidationError
           data class ConstraintViolation(override val message: String) : ValidationError
       }

       sealed interface NetworkError : DomainError {
           data class HttpError(val statusCode: Int, override val message: String) : NetworkError
           data class CircuitOpen(override val message: String = "Circuit breaker is open") : NetworkError
           data class Timeout(override val message: String) : NetworkError
       }
   }
   ```
2. Add a convenience extension `fun Throwable.toDatabaseError(): DomainError.DatabaseError.WriteFailed = DomainError.DatabaseError.WriteFailed(message ?: "unknown")`.
3. Add a `fun DomainError.toUiMessage(): String` extension that returns a human-readable string for each variant (used by ViewModels).

#### Story 1-A-2: Smoke-test `DomainError` hierarchy

As a developer, I want a compile-time check that the hierarchy is exhaustive.

Tasks:
1. In `kmp/src/commonTest/`, add `DomainErrorTest.kt` with a `when(err)` expression that matches every sealed branch — compiler error if any branch is missing.
2. Assert `DomainError.DatabaseError.NotFound("page", "abc").message == "page not found: abc"`.
3. Run `./gradlew jvmTest --tests "*.DomainErrorTest"`.

### Epic 1-B: Apply `@optics` to Model Classes

**Goal:** KSP generates lenses for `Page`, `Block`, `Property`, and `AppState` so later tiers can
replace multi-level `.copy()` chains.

#### Story 1-B-1: Annotate `model/Models.kt`

As a developer, I want optic lenses generated for all core models so I can write `Page.blocks compose Every.list()` without manual copy chains.

Tasks:
1. Read `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`.
2. Add `@optics` annotation and `companion object` to `Page`, `Block`, and `Property` data classes. Example:
   ```kotlin
   @optics
   data class Page(
       val uuid: String,
       val name: String,
       ...
   ) { companion object }
   ```
3. Import `arrow.optics.optics` at the top of `Models.kt`.
4. Run `./gradlew kspCommonMainKotlinMetadata` and confirm generated files appear in `build/generated/ksp/metadata/commonMain/kotlin/dev/stapler/stelekit/model/`.

#### Story 1-B-2: Annotate `AppState` for UI optics

As a developer, I want optic lenses for `AppState` so `StelekitViewModel` can reduce state with
lenses rather than nested `.copy()`.

Tasks:
1. Read `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`.
2. Add `@optics` + `companion object` to `AppState` and any nested data classes it contains.
3. Verify compilation passes with `./gradlew compileKotlinJvm`.

#### Story 1-B-3: Optics smoke test

As a developer, I want a unit test that exercises the generated lenses so a KSP misconfiguration
fails loudly in CI.

Tasks:
1. In `kmp/src/jvmTest/`, add `OpticsSmokteTest.kt`:
   - Create a `Page` with a known `name`, use `Page.name.modify(page) { it.uppercase() }`, assert result.
   - Create a `Traversal<Page, Block>` via `Page.blocks compose Every.list()`, use `getAll`, assert count.
2. Run `./gradlew jvmTest --tests "*.OpticsSmokeTest"`.

---

## Tier 2: Repository Layer — `Result<T>` → `Either<DomainError, T>`

**Rationale:** Repository interfaces are the single API boundary between the DB layer and the
application. Migrating interfaces and all implementations atomically (one PR) ensures the compiler
enforces completeness. UI callers are updated in the same pass. No dual overloads survive.

### Epic 2-A: Migrate Repository Interfaces

**Goal:** Every method in `BlockRepository`, `PageRepository`, `PropertyRepository`,
`ReferenceRepository`, `SearchRepository`, and `SpanRepository` returns `Either<DomainError, T>`
or `Flow<Either<DomainError, T>>`.

#### Story 2-A-1: Migrate `BlockRepository` interface

As a developer, I want `BlockRepository` to return typed errors so callers can match
`DatabaseError.NotFound` rather than checking exception messages.

Tasks:
1. In `GraphRepository.kt`, change all `Result<Unit>` return types to `Either<DomainError, Unit>`.
2. Change all `Flow<Result<T?>>` to `Flow<Either<DomainError, T?>>`.
3. Change `suspend fun splitBlock(...)` from `Result<Block>` to `Either<DomainError, Block>`.
4. Add import `arrow.core.Either` and `dev.stapler.stelekit.error.DomainError`.

#### Story 2-A-2: Migrate `PageRepository`, `PropertyRepository`, `ReferenceRepository`, `SearchRepository`, `SpanRepository` interfaces

As a developer, I want all repository interfaces uniformly typed so I can trust compile errors to
guide the migration.

Tasks:
1. In `GraphRepository.kt` (or separate interface files if split), change all remaining interface methods: `Result<Unit>` → `Either<DomainError, Unit>`, `Flow<Result<T>>` → `Flow<Either<DomainError, T>>`.
2. Verify the file compiles in isolation: `./gradlew compileCommonMainKotlinMetadata`.

### Epic 2-B: Migrate All Repository Implementations

**Goal:** All five SQLDelight repositories and the in-memory repositories implement the new interfaces.

#### Story 2-B-1: Migrate `SqlDelightBlockRepository`

As a developer, I want SQLite errors mapped to `DatabaseError` variants so I never see raw
`SQLiteException` in UI error states.

Tasks:
1. In `SqlDelightBlockRepository.kt`, replace all `runCatching { ... }.fold(...)` and `try/catch` blocks with `either { try { ... } catch (e: Exception) { raise(DomainError.DatabaseError.WriteFailed(e.message ?: "...")) } }`.
2. Change all `Result.success(Unit)` → `.right()`, `Result.failure(e)` → `DomainError.DatabaseError.WriteFailed(e.message).left()`.
3. For `Flow` queries, wrap the `.map { }` callback in `either { }`:
   ```kotlin
   .map { row -> either { row ?: raise(DomainError.DatabaseError.NotFound("block", uuid)) ; row.toModel() } }
   ```
4. Run `./gradlew jvmTest` and fix any test failures.

#### Story 2-B-2: Migrate remaining SQLDelight repositories

As a developer, I want `SqlDelightPageRepository`, `SqlDelightPropertyRepository`,
`SqlDelightReferenceRepository`, `SqlDelightSearchRepository`, and `SqlDelightSpanRepository`
following the same pattern as Story 2-B-1.

Tasks:
1. Apply the same `either { }` + `raise()` pattern to each of the 5 remaining SQLDelight repositories.
2. Map not-found rows to `DomainError.DatabaseError.NotFound`.
3. Map write failures to `DomainError.DatabaseError.WriteFailed`.
4. Run `./gradlew jvmTest` after each file; fix failures before moving to the next.

#### Story 2-B-3: Migrate `InMemoryRepositories`

As a developer, I want in-memory repos (used in tests) to implement the new interfaces so all
existing test infrastructure keeps compiling.

Tasks:
1. In `InMemoryRepositories.kt`, change each method's return to `Either<DomainError, T>`.
2. Replace `Result.success(x)` → `x.right()`, `Result.failure(e)` → `DomainError.DatabaseError.WriteFailed(e.message).left()`.
3. For not-found cases, return `DomainError.DatabaseError.NotFound("entity", id).left()`.
4. Run `./gradlew jvmTest` to confirm all in-memory-backed tests pass.

#### Story 2-B-4: Migrate `DatabaseWriteActor` return types (interface surface only)

As a developer, I want `DatabaseWriteActor`'s public methods to return `Either<DomainError, Unit>`
so callers compile against the new interface before the STM rewrite in Tier 3.

Tasks:
1. Change `CompletableDeferred<Result<Unit>>` → `CompletableDeferred<Either<DomainError, Unit>>` on all `WriteRequest` subtypes.
2. Change all `deferred.complete(Result.success(Unit))` → `deferred.complete(Unit.right())`.
3. Change all `deferred.complete(result)` calls where `result: Result<Unit>` to map through `result.fold({ it.right() }, { DomainError.DatabaseError.WriteFailed(it.message ?: "").left() })`.
4. Change public method signatures: `savePage`, `savePages`, `saveBlocks`, etc. — return `Either<DomainError, Unit>`.
5. In `flushBatch`, change `batchResult.isSuccess` → `batchResult.isRight()`, `Result.success(Unit)` → `Unit.right()`.
6. Run `./gradlew jvmTest`.

### Epic 2-C: Migrate ViewModel Collectors

**Goal:** All `StateFlow` in ViewModels carry typed error states, not raw `Throwable`.

#### Story 2-C-1: Migrate `StelekitViewModel`

As a developer, I want `StelekitViewModel` to collect `Flow<Either<DomainError, T>>` so the UI
receives typed errors it can display meaningfully.

Tasks:
1. Read `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`.
2. For each `repo.getSomething(uuid).collect { result -> ... }` block, change `result.getOrNull()` → `result.getOrNull()` (on `Either`, use `.getOrNull()` from Arrow) and `result.isFailure` → `result.isLeft()`.
3. Update `_state.update { s -> s.copy(error = result.exceptionOrNull()) }` → `_state.update { s -> s.copy(error = result.leftOrNull()?.toUiMessage()) }`.
4. Run `./gradlew jvmTest`.

#### Story 2-C-2: Migrate all screen ViewModels

As a developer, I want `AllPagesViewModel`, `JournalsViewModel`, `SearchViewModel`, and any other
ViewModels to follow the same Either-collection pattern.

Tasks:
1. Find all ViewModel files: `find kmp/src -name "*ViewModel.kt"`.
2. For each, apply the same collection pattern as Story 2-C-1.
3. Update `AppState` error fields to use `DomainError?` instead of `Throwable?` or `String?` (whichever applies).
4. Run `./gradlew ciCheck`.

---

## Tier 3: `DatabaseWriteActor` STM Rewrite

**Rationale:** The dual-`Channel` priority queue and `CompletableDeferred<Result<Unit>>` per request
is the hardest concurrency primitive in the codebase. Migrating it to Arrow STM `TVar<WriteQueues>`
eliminates the manual `select {}` dispatch, makes the queue observable, and removes the deadlock
surface from manual channel management. This tier depends on Tier 2's `Either` types being in place.

### Epic 3-A: Replace Dual Channel with STM Priority Queue

**Goal:** `DatabaseWriteActor` uses `TVar<WriteQueues>` + `atomically {}` for enqueue/dequeue;
`CompletableDeferred<Either<DomainError, Unit>>` replaces the Result-based deferred.

#### Story 3-A-1: Define `WriteQueues` STM data structure

As a developer, I want an immutable `WriteQueues` value type held in a `TVar` so enqueue and
dequeue are atomic and composable without locks.

Tasks:
1. In `DatabaseWriteActor.kt`, add:
   ```kotlin
   import arrow.fx.stm.TVar
   import arrow.fx.stm.atomically
   ```
2. Define `data class WriteQueues(val high: List<WriteRequest>, val low: List<WriteRequest>)`.
3. Add `private val queues: TVar<WriteQueues>` initialized in a `companion object` or via `TVar.new(WriteQueues(emptyList(), emptyList()))` inside an `init` block inside a `runBlocking` (or via a factory `suspend fun`). Since `TVar.new()` is a suspend function, declare the actor as having a `suspend` factory or use `TVar.new` in a `runBlocking` at construction time for backward compatibility.
   - Preferred: Change the constructor to a companion `suspend fun create(...)` that calls `TVar.new()` and remove the `init` block launch.
4. Add `private suspend fun enqueue(req: WriteRequest) = atomically { queues.modify { q -> if (req.priority == Priority.HIGH) q.copy(high = q.high + req) else q.copy(low = q.low + req) } }`.
5. Add `private suspend fun dequeue(): WriteRequest = atomically { val q = queues.read(); if (q.high.isNotEmpty()) { queues.write(q.copy(high = q.high.drop(1))); q.high.first() } else if (q.low.isNotEmpty()) { queues.write(q.copy(low = q.low.drop(1))); q.low.first() } else retry() }`.

#### Story 3-A-2: Rewrite actor loop to use STM dequeue

As a developer, I want the actor loop to call `dequeue()` instead of `select { highPriority.onReceive; lowPriority.onReceive }` so the priority logic is purely in the STM transaction.

Tasks:
1. Remove `private val highPriority = Channel<WriteRequest>(capacity = Channel.UNLIMITED)` and `private val lowPriority`.
2. Remove `private fun channelFor(priority: Priority)`.
3. Replace the `while (isActive)` body's `select {}` dispatch with a single `val request = dequeue()` call.
4. Update all `channelFor(priority).send(req)` call sites to `enqueue(req)`.
5. Update `close()`: remove `highPriority.close()` / `lowPriority.close()`. The STM queue drains naturally when `actorScope.cancel()` is called.
6. Update coalescing in `processSaveBlocks` — instead of `sourceChannel.tryReceive()`, use `atomically { queues.read().let { q -> if (first.priority == Priority.LOW && q.high.isNotEmpty()) { /* dequeue high */ } else { /* tryDequeue same priority */ } } }`.

#### Story 3-A-3: Preserve coalescing + retry-individual-on-batch-failure behavior

As a developer, I want batch coalescing and individual retry semantics preserved so the actor
degrades gracefully on partial SQLite failures.

Tasks:
1. Port `processSaveBlocks` to use STM non-blocking peek: `atomically { val q = queues.read(); if (q.high.isNotEmpty() && first.priority == Priority.LOW) { /* flush immediately */ } else { /* peek same-priority head */ } }`.
2. Preserve `flushBatch` logic: single-request fast path, multi-request combined transaction, individual retry on combined failure.
3. Add a unit test `DatabaseWriteActorCoalesceTest` that enqueues 10 `SaveBlocks` at LOW priority and 1 `SaveBlocks` at HIGH priority mid-batch, asserting the HIGH request is serviced before the LOW batch completes.
4. Run `./gradlew jvmTest --tests "*.DatabaseWriteActor*"`.

#### Story 3-A-4: Wrap actor scope in `Resource`

As a developer, I want `DatabaseWriteActor`'s coroutine scope guaranteed to cancel even under
`CancellationException` so no background coroutine outlives the graph.

Tasks:
1. Import `arrow.fx.coroutines.Resource` and `arrow.fx.coroutines.resource`.
2. Add a companion `fun resource(...): Resource<DatabaseWriteActor>`:
   ```kotlin
   companion object {
       fun resource(blockRepo: BlockRepository, pageRepo: PageRepository, opLogger: OperationLogger? = null): Resource<DatabaseWriteActor> = resource {
           val actor = DatabaseWriteActor.create(blockRepo, pageRepo, opLogger)
           onRelease { actor.close() }
           actor
       }
   }
   ```
3. Keep the existing `close()` for compatibility with callers that don't use `Resource` yet; it will be removed in Tier 4.
4. Run `./gradlew jvmTest`.

---

## Tier 4: `GraphWriter` STM + Saga + `GraphManager` Resource

**Rationale:** `GraphWriter` has two concurrency problems (Mutex+MutableMap debounce) and one
correctness problem (no rollback on partial disk+DB failure). `GraphManager` has no structured
resource release guarantee. Both require Tier 3's actor and Tier 2's Either types.

### Epic 4-A: `GraphWriter` STM Debounce State

**Goal:** Replace `pendingByPage: MutableMap<String, Pair<Job, SaveRequest>>` + `pendingMutex` with
`TMap<String, SaveRequest>` for lock-free debounce state management.

#### Story 4-A-1: Replace `MutableMap + Mutex` with `TMap`

As a developer, I want the debounce map to be STM-managed so reads and writes are composable
without locks and the state is always consistent.

Tasks:
1. In `GraphWriter.kt`, import `arrow.fx.stm.TMap` and `arrow.fx.stm.atomically`.
2. Replace `private val pendingByPage = mutableMapOf<String, Pair<Job, SaveRequest>>()` and `private val pendingMutex = Mutex()` with `private lateinit var pendingByPage: TMap<String, SaveRequest>` (initialized in a `suspend` factory or `startAutoSave`).
   - Simplest: initialize `TMap.new()` in `startAutoSave` which is already a regular function called once. However `TMap.new()` is `suspend`, so change `startAutoSave` signature to `suspend fun startAutoSave(...)` or initialize lazily inside a `runBlocking` at construction.
   - Preferred: Initialize in a companion `suspend fun create(...)` factory.
3. In `queueSave`, replace `pendingMutex.withLock { pendingByPage[page.uuid]?.first?.cancel(); ... }` with:
   ```kotlin
   atomically {
       val existing = pendingByPage.lookup(page.uuid)
       // STM lookup is composable; job cancellation happens outside STM
       pendingByPage.insert(page.uuid, request)
   }
   existing?.first?.cancel()  // cancel outside STM — Job is not a TVar
   ```
4. In `flush`, replace `pendingMutex.withLock { ... }` with `atomically { val snapshot = pendingByPage.toList(); pendingByPage.keys().forEach { pendingByPage.remove(it) }; snapshot }` — then iterate outside STM.
5. Remove `private val pendingMutex = Mutex()`.

#### Story 4-A-2: Replace `saveMutex` with saga-level serialization

As a developer, I want file writes serialized through the saga rather than a Mutex so rollback is
possible when the disk write succeeds but the DB commit fails.

Tasks:
1. Remove `private val saveMutex = Mutex()`.
2. The saga in Story 4-B-1 provides the serialization guarantee — each `savePageInternal` call becomes one saga execution. If two calls race, they both run as separate sagas; since `GraphWriter.queueSave` debounces per-page, races are already prevented.
3. Update `savePageInternal` signature from `private suspend fun savePageInternal(...) = saveMutex.withLock { ... }` to `private suspend fun savePageInternal(...)` (no lock).

### Epic 4-B: Disk-Write Saga

**Goal:** The 4-step disk+DB write pipeline is an Arrow `Saga` with explicit compensating actions
so partial failures roll back consistently.

#### Story 4-B-1: Model `savePageInternal` as a Saga

As a developer, I want the disk write pipeline to roll back DB changes if the file write fails (and
vice versa) so no page is half-saved in an inconsistent state.

Tasks:
1. In `GraphWriter.kt`, import `arrow.resilience.saga` and `arrow.core.raise.either`.
2. Refactor `savePageInternal` to return `Either<DomainError, Unit>` and internally use a saga:
   ```kotlin
   private suspend fun savePageInternal(page: Page, blocks: List<Block>, graphPath: String): Either<DomainError, Unit> = saga {
       // Step 1: Read old content for rollback
       val oldContent: String? = fileSystem.readFile(filePath)

       // Step 2: Write markdown file
       sagas(
           action = {
               val ok = fileSystem.writeFile(filePath, content)
               if (!ok) raise(DomainError.FileSystemError.WriteFailed(filePath, "writeFile returned false"))
           },
           compensation = {
               if (oldContent != null) fileSystem.writeFile(filePath, oldContent)
               else fileSystem.deleteFile(filePath)
           }
       )

       // Step 3: Upsert page in DB (if new page needs filePath set)
       if (page.filePath.isNullOrBlank()) {
           val updatedPage = page.copy(filePath = filePath)
           sagas(
               action = { writeActor?.savePage(updatedPage)?.bind() ?: Unit },
               compensation = { /* page was not yet persisted — nothing to undo */ }
           )
       }

       // Step 4: Write sidecar (non-critical; compensation is no-op)
       sagas(
           action = {
               if (sidecarManager != null) {
                   val pageSlug = FileUtils.sanitizeFileName(page.name)
                   try { sidecarManager.write(pageSlug, blocks) }
                   catch (e: Exception) { /* non-fatal — log only */ }
               }
           },
           compensation = { /* sidecar failure is non-critical */ }
       )

       onFileWritten?.invoke(filePath)
   }.toEither { DomainError.FileSystemError.WriteFailed(filePath, it.message ?: "saga failed") }
   ```
3. Update `saveImmediately` and `savePage` to propagate the `Either<DomainError, Unit>` return.
4. Add a `GraphWriterSagaTest` that:
   - Injects a `FileSystem` stub where `writeFile` fails on the second call.
   - Asserts that the first step (file) is rolled back (old content restored).
   - Asserts the method returns `Left(FileSystemError.WriteFailed(...))`.

#### Story 4-B-2: Wrap `GraphWriter` scope in `Resource`

As a developer, I want `GraphWriter`'s debounce coroutine scope guaranteed to cancel when the graph
is closed so no orphaned debounce jobs fire after shutdown.

Tasks:
1. Add a companion `fun resource(...): Resource<GraphWriter>`:
   ```kotlin
   companion object {
       fun resource(...): Resource<GraphWriter> = resource {
           val writer = GraphWriter.create(...)  // suspend factory
           onRelease {
               writer.flush()        // flush pending saves
               writer.stopAutoSave() // cancel scope
           }
           writer
       }
   }
   ```
2. Update callers in `RepositoryFactory.createRepositorySet` to use `GraphWriter.resource(...)`.

### Epic 4-C: `GraphManager` Resource Lifecycle

**Goal:** `GraphManager`'s per-graph resource acquisition is wrapped in `Resource<GraphResources>`
so finalizers (DB close, scope cancel) run even on `CancellationException`.

#### Story 4-C-1: Define `GraphResources` and wrap acquisition

As a developer, I want `GraphManager` to guarantee DB driver closure and scope cancellation when
`switchGraph` or `shutdown` is called, regardless of exceptions.

Tasks:
1. Define `data class GraphResources(val repositorySet: RepositorySet, val graphScope: CoroutineScope, val factory: RepositoryFactory)` in `GraphManager.kt` or a new `GraphResources.kt`.
2. Add a private `fun graphResourcesFor(graphId: String, graphInfo: GraphInfo): Resource<GraphResources>` inside `GraphManager`:
   ```kotlin
   private fun graphResourcesFor(graphId: String, graphInfo: GraphInfo): Resource<GraphResources> = resource {
       val graphScope = install(coroutineScopeResource())
       val dbUrl = driverFactory.getDatabaseUrl(graphId)
       val factory = RepositoryFactoryImpl(driverFactory, dbUrl)
       val repoSet = factory.createRepositorySet(...)
       onRelease {
           factory.close()
           // graphScope is cancelled by coroutineScopeResource's onRelease
       }
       GraphResources(repoSet, graphScope, factory)
   }
   ```
   Where `coroutineScopeResource()` is:
   ```kotlin
   private fun coroutineScopeResource(): Resource<CoroutineScope> = resource {
       val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
       onRelease { scope.cancel() }
       scope
   }
   ```
3. Replace `activeGraphJobs: MutableMap<String, CoroutineScope>` + `currentFactory?.close()` in `switchGraph` and `shutdown` with Resource lifecycle management.
4. Hold the active resource handle as `private var activeGraphResource: Pair<GraphResources, suspend () -> Unit>? = null` (allocated pair from `.allocated()`).
5. In `switchGraph`, call `activeGraphResource?.second?.invoke()` to release the previous resource before allocating the new one.
6. In `shutdown`, call `activeGraphResource?.second?.invoke()`.

#### Story 4-C-2: Update `RepositoryFactory` to return `Resource<RepositorySet>`

As a developer, I want `RepositoryFactory.createRepositorySet` to be resource-managed so every
sub-component (driver, actor, scope) has a guaranteed release path.

Tasks:
1. In `RepositoryFactory.kt`, add a `fun repositorySetResource(...): Resource<RepositorySet>` method.
2. Inside, use `install()` for the driver, actor, and scope:
   ```kotlin
   fun repositorySetResource(...): Resource<RepositorySet> = resource {
       val driver = install(resource { driverFactory.createDriver(jdbcUrl); onRelease { driver.close() }; driver })
       val db = SteleDatabase(driver)
       val actor = install(DatabaseWriteActor.resource(blockRepo, pageRepo))
       ...
       RepositorySet(blockRepo, pageRepo, ..., actor)
   }
   ```
3. Keep the existing `createRepositorySet(...)` for backward compatibility (wraps `repositorySetResource(...).use { it }`); mark `@Deprecated`.

---

## Tier 5: Resilience, Voice Saga, ViewModel Error Propagation

**Rationale:** With foundation, repositories, actor, and graph lifecycle all migrated, the final tier
adds resilience policies and migrates the voice pipeline. This tier depends on all previous tiers.

### Epic 5-A: Retry Policies and Schedule Constants

**Goal:** All `SQLITE_BUSY` and file-watch registration errors are retried with named, testable
schedule policies.

#### Story 5-A-1: Define `RetryPolicies` object

As a developer, I want named schedule constants so tests can inject zero-delay schedules and
production uses jittered backoff.

Tasks:
1. Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/resilience/RetryPolicies.kt`:
   ```kotlin
   package dev.stapler.stelekit.resilience

   import arrow.resilience.Schedule
   import kotlin.time.Duration.Companion.milliseconds
   import kotlin.time.Duration.Companion.seconds

   object RetryPolicies {
       val sqliteBusy: Schedule<Throwable, *> =
           Schedule.recurs<Throwable>(3).jittered()

       val fileWatchReregistration: Schedule<Throwable, *> =
           Schedule.exponential<Throwable>(100.milliseconds)
               .untilOutput { it > 5.seconds }

       // For tests: zero delay, same retry count
       val testImmediate: Schedule<Throwable, *> =
           Schedule.recurs<Throwable>(3)
   }
   ```
2. Verify `RetryPolicies` compiles on all targets: `./gradlew compileCommonMainKotlinMetadata`.

#### Story 5-A-2: Wrap SQLite writes in retry in `SqlDelightBlockRepository`

As a developer, I want transient `SQLITE_BUSY` errors automatically retried so users don't see
flaky save failures during concurrent graph loads.

Tasks:
1. In `SqlDelightBlockRepository.kt`, import `arrow.resilience.retry` and `dev.stapler.stelekit.resilience.RetryPolicies`.
2. For each write method (`saveBlock`, `saveBlocks`, `deleteBlocksForPage`), wrap the inner SQL call:
   ```kotlin
   RetryPolicies.sqliteBusy.retry { queries.insertBlock(...) }
   ```
   Keep the outer `either { }` / `withContext(PlatformDispatcher.DB)` pattern unchanged — retry wraps only the innermost SQL call.
3. Add a unit test `RetryPolicyTest` that uses `RetryPolicies.testImmediate` against a fake that fails twice then succeeds, asserting 3 total attempts.

#### Story 5-A-3: Add circuit breaker around `ClaudeLlmFormatterProvider`

As a developer, I want a circuit breaker around external LLM API calls so a network partition
during voice transcription doesn't cascade into indefinite hanging.

Tasks:
1. Locate `ClaudeLlmFormatterProvider` (likely in `voice/` or `llm/` package).
2. Add a `val llmCircuitBreaker = CircuitBreaker(openingStrategy = CircuitBreaker.OpeningStrategy.Count(maxFailures = 5, resetTimeout = 30.seconds, exponentialBackoffFactor = 2.0, maxResetTimeout = 5.minutes))` as a companion object or injected dependency.
3. Wrap the HTTP call: `llmCircuitBreaker.protectEither { httpClient.post(...) }.mapLeft { DomainError.NetworkError.CircuitOpen() }`.
4. Add a `CircuitBreakerTest` that trips the breaker after 5 failures, asserts the 6th call returns `Left(NetworkError.CircuitOpen(...))` without hitting the HTTP stub.

### Epic 5-B: Voice Pipeline Saga

**Goal:** The `record → transcribe → LLM format → save block` pipeline rolls back completed steps
on partial failure.

#### Story 5-B-1: Model voice pipeline as Arrow Saga

As a developer, I want the voice pipeline to delete a saved recording if transcription fails and
delete a saved block if the LLM format step throws, so the DB never contains orphaned partial results.

Tasks:
1. Locate the voice pipeline entry point (likely `VoiceCaptureViewModel` or `VoicePipeline`).
2. Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSaga.kt`:
   ```kotlin
   suspend fun recordTranscribeFormatSave(audioData: ByteArray, ...): Either<DomainError, Block> = saga {
       // Step 1: Save audio recording
       val recordingPath = sagas(
           action = { audioRecorder.save(audioData) ?: raise(DomainError.FileSystemError.WriteFailed("audio", "save returned null")) },
           compensation = { path -> audioRecorder.delete(path) }
       )

       // Step 2: Transcribe (no compensation — idempotent service call)
       val transcript = sagas(
           action = { transcriber.transcribe(recordingPath) ?: raise(DomainError.ParseError.InvalidSyntax("empty transcript")) },
           compensation = { }
       )

       // Step 3: LLM format (circuit-broken; no compensation)
       val formatted = sagas(
           action = { llmFormatter.format(transcript).bind() },
           compensation = { }
       )

       // Step 4: Save block
       val block = Block(content = formatted, ...)
       sagas(
           action = { writeActor.saveBlock(block).bind() },
           compensation = { writeActor.deleteBlock(block.uuid) }
       )

       block
   }.toEither { DomainError.DatabaseError.WriteFailed(it.message ?: "voice saga failed") }
   ```
3. Wire `VoiceSaga` into `VoiceCaptureViewModel`, replacing the existing fire-and-forget chain.
4. Add `VoiceSagaTest` asserting that if `transcriber.transcribe()` throws, the audio recording is deleted (compensation ran) and the result is `Left`.

### Epic 5-C: ViewModel Error Propagation Cleanup

**Goal:** No `StateFlow` carries raw `Throwable` or untyped `String` errors; all error states use
`DomainError?`.

#### Story 5-C-1: Replace raw exception fields in `AppState` and UI states

As a developer, I want `AppState` error fields typed as `DomainError?` so the UI can switch on
error variant and show actionable messages.

Tasks:
1. Read all `data class` UI state definitions in `ui/` that contain `error: Throwable?` or `error: String?`.
2. Replace with `error: DomainError?` and update all assignment sites.
3. In Compose screens, replace `if (state.error != null) Text(state.error!!.message)` with `state.error?.toUiMessage()?.let { Text(it) }`.
4. Run `./gradlew ciCheck`.

#### Story 5-C-2: Final audit — zero `Result<T>` at API boundaries

As a developer, I want a compile-time guarantee that no repository interface method returns `Result<T>` so the migration is complete.

Tasks:
1. Add a Detekt custom rule (or use `grep` in CI) that flags any `Result<` return type on methods in `*Repository.kt` files.
2. Alternatively, add a compile-time check: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ArchTest.kt` using reflection to assert zero `Result`-returning methods on all classes implementing `BlockRepository`, `PageRepository`, etc.
3. Run `./gradlew ciCheck` and confirm the check passes.

---

## Technology Validation

| Arrow Module | Version | KMP Targets | Caveats |
|---|---|---|---|
| `arrow-core` | 2.2.1.1 | JVM, Android, iOS (x64/arm64/simArm64), wasmJs | None. Stable. |
| `arrow-optics` | 2.2.1.1 | All | `Optional<S, A>` removed — use `Lens<S, A?>` for nullable fields. Arrow 2.x only. |
| `arrow-optics-ksp-plugin` | 2.2.1.1 | All (runs on JVM, generates code compiled to all targets) | **Must use per-target `kspJvm/kspAndroid/...` configs** — generic `ksp(...)` is deprecated and silently skips targets. `companion object` is required on every `@optics` class. |
| `arrow-fx-coroutines` | 2.2.1.1 | All | STM on WASM/JS: single-threaded runtime, transactions never conflict — degrades gracefully to sequential. iOS: new Kotlin Native memory model required (Kotlin 2.x default). |
| `arrow-resilience` | 2.2.1.1 | All | `Saga` is inside `arrow-resilience`, not a separate module. `CircuitBreaker` uses `OpeningStrategy` — check API for Count vs Rate options. |

### Flagged Technology Decision: KSP Plugin vs Arrow Optics Gradle Plugin

The Arrow Optics Gradle Plugin (`io.arrow-kt.arrow-optics-gradle-plugin`) entered beta in November
2025 and would eliminate the multi-target KSP setup complexity. **Decision required:** Use manual
KSP configuration (stable, documented) or adopt the beta Gradle plugin. The plan uses manual KSP.
If the plugin reaches stable before Tier 0 is implemented, prefer it.

### Flagged Technology Decision: `DatabaseWriteActor` Construction Pattern

`TVar.new()` and `TMap.new()` are `suspend` functions, which prevents simple constructor
initialization. **Decision required:** Use a companion `suspend fun create(...)` factory (cleaner
API, requires callers to use coroutines) or wrap `TVar.new()` in `runBlocking` inside the
constructor (simpler callers, blocks on construction). The plan recommends the `suspend` factory.

---

## Migration Risk Register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | **Result → Either viral propagation** causes cascading compile errors across ~100 call sites simultaneously | High | Follow tier order strictly; each tier compiles independently. Use `typealias DomainResult<T> = Either<DomainError, T>` temporarily to isolate blast radius if needed. |
| 2 | **KSP multi-target misconfiguration** causes silent "unresolved reference: Companion" errors only on specific targets (Android, iOS) | High | Tier 0 includes a smoke test (`OpticsSmokteTest`) that runs on JVM. Add a dedicated iOS/Android compilation check in CI (`./gradlew compileKotlinIosX64`) after Tier 1. |
| 3 | **`DatabaseWriteActor` STM rewrite** breaks coalescing or retry-individual semantics, silently losing block writes | High | Story 3-A-3 adds a dedicated coalesce unit test before the PR merges. Run the existing benchmark (`./gradlew jvmBenchmark`) before and after Tier 3. |
| 4 | **`Resource` installed in wrong scope** (Compose vs ViewModel) causes finalizers to never run during graph switch | Medium | Architecture note in CLAUDE.md explicitly forbids `rememberCoroutineScope()` in `remember { }`. Code review checklist item: verify all `Resource.allocated()` calls are inside ViewModel or entry-point scope. |
| 5 | **Kotlin version mismatch with Arrow 2.2.1.1** (if project upgrades to Kotlin 2.3 before Arrow supports it) | Medium | Arrow 2.2.1.1 targets Kotlin 2.3 explicitly. Pin Kotlin to 2.2.x in `gradle.properties` until Arrow 2.3.x is released. |

---

## Definition of Done

- [ ] Zero `Result<T>` at repository/service API boundaries — all methods return `Either<DomainError, T>` or `Flow<Either<DomainError, T>>`.
- [ ] `DomainError` sealed hierarchy covers all current exception types thrown across `db/`, `repository/`, `voice/` — verified by exhaustive `when` in `DomainErrorTest`.
- [ ] `GraphManager` and `GraphWriter` resource cleanup is guaranteed — verified by test: cancel parent scope, assert finalizers ran (DB driver closed, scope jobs cancelled).
- [ ] At least `DatabaseWriteActor` uses STM `TVar<WriteQueues>` for its priority queue; `GraphWriter` uses `TMap<String, SaveRequest>` for debounce state.
- [ ] Disk-write saga implemented with at least 2 compensation steps tested: file rollback on DB failure, and block deletion on voice pipeline error.
- [ ] All CI checks pass on all platforms: `./gradlew ciCheck` (detekt + jvmTest + testDebugUnitTest + assembleDebug).
- [ ] Existing benchmark results do not regress by more than 5% (`./gradlew jvmBenchmark` before vs after each tier).
- [ ] `@optics` lenses compile and are exercised in `OpticsSmokteTest`.
- [ ] `RetryPolicies` object exists with at least `sqliteBusy` and `fileWatchReregistration` constants; used in at least one repository.
- [ ] Circuit breaker wraps `ClaudeLlmFormatterProvider`; `CircuitBreakerTest` asserts open-circuit behavior.
