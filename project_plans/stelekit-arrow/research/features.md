# Features Research: Arrow 2.x API Reference for SteleKit

## 1. Either and the Raise / Effect DSL

### Either<L, R>

`Either` is Arrow's typed error container. Left = error, Right = success.

```kotlin
import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.left
import arrow.core.right

// Construction
val ok: Either<DomainError, Page> = page.right()
val err: Either<DomainError, Page> = DatabaseError("connection lost").left()

// Transformation
val name: Either<DomainError, String> = ok.map { it.name }
val chained: Either<DomainError, Block> = ok.flatMap { page -> loadBlocks(page).right() }
val value: Page = ok.getOrElse { Page.empty() }
val folded: String = ok.fold(
    ifLeft = { error -> "Error: ${error.message}" },
    ifRight = { page -> page.name }
)
```

### either { } block — Raise DSL

The `either { }` builder uses `Raise<E>` context to write imperative-style code that short-circuits on error:

```kotlin
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull

suspend fun loadAndSavePage(path: String): Either<DomainError, Unit> = either {
    val content = fileSystem.readFile(path)
        ?: raise(FileSystemError.NotFound(path))       // short-circuit, returns Left
    
    ensure(content.isNotBlank()) { ParseError.EmptyFile(path) }  // ensure condition or raise
    
    val page = parseMarkdown(content)
        .mapLeft { ParseError.InvalidSyntax(it.message) }
        .bind()    // bind() = .getOrElse { raise(it) }
    
    actor.savePage(page).bind()                         // propagate Either from sub-call
}
```

### Raise<E> context — internal pipelines

For internal computation pipelines (not public API), use `Raise<E>` directly to avoid boxing:

```kotlin
import arrow.core.raise.Raise
import arrow.core.raise.recover

context(Raise<DomainError>)
suspend fun validateAndParse(content: String): Page {
    ensure(content.isNotBlank()) { ParseError.EmptyFile("unknown") }
    return parser.parse(content)
        .getOrElse { raise(ParseError.InvalidSyntax(it.message)) }
}

// recover allows catching specific error variants without leaving Raise context:
context(Raise<DomainError>)
fun safeLoad(uuid: String): Page? = recover(
    block = { loadPage(uuid) },        // may raise DomainError
    recover = { _: DatabaseError -> null }  // handle specific subtype, re-raise others
)
```

### Effect<R, A> — deferred Either computation

`Effect<R, A>` is a lazy version of `Raise<R> -> A`. Arrow 2.x makes this transparent — the `either { }` builder IS the Effect DSL.

### Converting Result<T> → Either<Throwable, T>

```kotlin
import arrow.core.toEither

val result: Result<Page> = runCatching { loadPage(uuid) }
val either: Either<Throwable, Page> = result.toEither()

// To map to DomainError:
val domainEither: Either<DomainError, Page> = result
    .toEither()
    .mapLeft { e -> DatabaseError(e.message ?: "unknown") }
```

### Flow<Either<E, T>> patterns

```kotlin
import arrow.core.raise.either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Repository returns Flow<Either<DomainError, Page>>
fun getPage(uuid: String): Flow<Either<DomainError, Page>> =
    queries.selectPage(uuid)
        .asFlow()
        .mapToOneOrNull(PlatformDispatcher.DB)
        .map { row ->
            either {
                val r = row ?: raise(DatabaseError.NotFound("page", uuid))
                r.toModel()
            }
        }

// Collecting in ViewModel:
viewModelScope.launch {
    repo.getPage(uuid).collect { result ->
        result.fold(
            ifLeft = { _state.update { s -> s.copy(error = it.toUiError()) } },
            ifRight = { _state.update { s -> s.copy(page = it) } }
        )
    }
}
```

---

## 2. Arrow Optics

### @optics annotation and generated DSL

Apply `@optics` to data classes in `commonMain`. KSP generates a companion object with optic instances:

```kotlin
import arrow.optics.optics

@optics
data class Page(
    val uuid: String,
    val name: String,
    val properties: Map<String, String>,
    val blocks: List<Block>,
) {
    companion object  // required — KSP fills this in
}

@optics
data class Block(
    val uuid: String,
    val content: String,
    val properties: Map<String, String>,
    val children: List<Block> = emptyList(),
) {
    companion object
}
```

### Lens<S, A> — get and modify

```kotlin
// Generated: Page.name is a Lens<Page, String>
val page = Page(uuid = "1", name = "Old Name", ...)

// Get
val name: String = Page.name.get(page)

// Set (returns new Page, immutable)
val renamed: Page = Page.name.set(page, "New Name")

// Modify
val upper: Page = Page.name.modify(page) { it.uppercase() }
```

### Traversal — bulk mutations on nested collections

```kotlin
import arrow.optics.Every
import arrow.optics.Traversal

// Traversal<Page, Block> for all blocks in a page
val allBlocks: Traversal<Page, Block> = Page.blocks compose Every.list()

// Modify all blocks' content in one call
val updated: Page = allBlocks.modify(page) { block ->
    block.copy(content = block.content.trim())
}

// Get all values
val contents: List<String> = (allBlocks compose Block.content).getAll(page)

// Set all at once
val cleared: Page = (allBlocks compose Block.content).set(page, "")
```

### Prism — sealed class hierarchies

```kotlin
import arrow.optics.Prism

sealed interface DomainError {
    data class DatabaseError(val message: String) : DomainError
    data class FileSystemError(val path: String) : DomainError
}

// Arrow generates Prisms automatically for sealed classes annotated @optics
@optics
sealed interface DomainError { companion object }
// Usage:
val dbError: Option<DatabaseError> = DomainError.databaseError.getOrModify(someError).toOption()
```

### KSP Gradle setup (see stack.md for full config)

```kotlin
// build.gradle.kts
dependencies {
    add("kspCommonMainMetadata", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspJvm", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    // ... other targets
}
```

---

## 3. arrow.fx.coroutines.Resource

### Resource<A> — guaranteed acquisition and release

`Resource<A>` models resource lifecycle: acquire → use → release (even on cancellation or error).

```kotlin
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource

// Define a resource
fun databaseDriver(url: String): Resource<SqlDriver> = resource {
    val driver = driverFactory.createDriver(url)   // acquire
    onRelease { driver.close() }                    // release (always runs)
    driver
}

// Nest resources — release order is reverse of acquisition
fun graphResources(graphId: String): Resource<GraphResources> = resource {
    val driver = install(databaseDriver(url))       // acquire inner resource
    val db = SteleDatabase(driver)
    val scope = install(coroutineScopeResource())   // second resource
    GraphResources(db, scope)
}

// Use the resource — finalizers guaranteed even on cancellation
suspend fun main() {
    graphResources(graphId).use { resources ->
        // resources available here
        loadGraph(resources)
    }
    // driver.close() and scope.cancel() guaranteed after this point
}
```

### Resource.allocated() — manual control

When you need to manage lifecycle manually (e.g., in a ViewModel):

```kotlin
val (resources, release) = graphResources(graphId).allocated()
// ... use resources ...
release()  // must call manually — prefer .use {} instead
```

### Integration with CoroutineScope

```kotlin
import arrow.fx.coroutines.resource

fun coroutineScopeResource(): Resource<CoroutineScope> = resource {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    onRelease { scope.cancel() }
    scope
}
```

---

## 4. arrow.fx.coroutines.STM, TVar, TMap

### What is STM?

Software Transactional Memory (STM) provides lock-free concurrent state modification. Multiple coroutines can read/modify TVars in a transaction; if two transactions conflict, one retries automatically. No deadlocks, no race conditions.

### TVar<A> — atomic transactional variable

```kotlin
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import arrow.fx.stm.STM

// Create (outside transaction)
val counter: TVar<Int> = TVar.new(0)
val pendingQueue: TVar<List<WriteRequest>> = TVar.new(emptyList())

// Read/write inside STM block
atomically {
    val current = counter.read()
    counter.write(current + 1)
    // or: counter.modify { it + 1 }
}

// Conditional — retry blocks until condition is met
atomically {
    val q = pendingQueue.read()
    if (q.isEmpty()) retry()   // suspends and retries when any read TVar changes
    val item = q.first()
    pendingQueue.write(q.drop(1))
    item
}
```

### TMap<K, V> — transactional hash map

```kotlin
import arrow.fx.stm.TMap

val pendingByPage: TMap<String, SaveRequest> = TMap.new()

// Insert
atomically { pendingByPage.insert("page-uuid", saveRequest) }

// Lookup
val req: SaveRequest? = atomically { pendingByPage.lookup("page-uuid") }

// Delete
atomically { pendingByPage.remove("page-uuid") }
```

### Replacing Mutex + MutableMap (DatabaseWriteActor pattern)

Current pattern in `DatabaseWriteActor`:
```kotlin
// BEFORE: dual Channel + CompletableDeferred
private val highPriority = Channel<WriteRequest>(Channel.UNLIMITED)
private val lowPriority = Channel<WriteRequest>(Channel.UNLIMITED)
```

Arrow STM equivalent:
```kotlin
// AFTER: TVar-based priority queue
data class WriteQueues(
    val high: List<WriteRequest>,
    val low: List<WriteRequest>,
)
val queues: TVar<WriteQueues> = TVar.new(WriteQueues(emptyList(), emptyList()))

// Enqueue (from any coroutine, no lock needed)
suspend fun enqueueHigh(req: WriteRequest) = atomically {
    queues.modify { it.copy(high = it.high + req) }
}

// Dequeue with priority — atomic, no lock
suspend fun dequeue(): WriteRequest = atomically {
    val q = queues.read()
    if (q.high.isNotEmpty()) {
        queues.write(q.copy(high = q.high.drop(1)))
        q.high.first()
    } else if (q.low.isNotEmpty()) {
        queues.write(q.copy(low = q.low.drop(1)))
        q.low.first()
    } else {
        retry()  // suspends until queue has items
    }
}
```

### Replacing GraphWriter's pendingByPage Mutex + MutableMap

```kotlin
// BEFORE:
private val pendingByPage = mutableMapOf<String, Pair<Job, SaveRequest>>()
private val pendingMutex = Mutex()

// AFTER:
val pendingByPage: TMap<String, SaveRequest> = TMap.new()

// Reads and writes are lock-free; composable with other STM operations
suspend fun queueSave(request: SaveRequest) = atomically {
    pendingByPage.insert(request.page.uuid, request)
}
```

---

## 5. Arrow Resilience: Schedule, retry, CircuitBreaker

### Schedule<Input, Output> — retry policies

```kotlin
import arrow.resilience.Schedule
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Retry 3 times with no delay
val simpleRetry = Schedule.recurs<Throwable>(3)

// Exponential backoff starting at 100ms
val exponential = Schedule.exponential<Throwable>(100.milliseconds)

// Exponential with jitter (randomized delay to avoid thundering herd)
val jittered = Schedule.exponential<Throwable>(100.milliseconds).jittered()

// Named constants (testability — override in tests with zero delay)
object RetryPolicies {
    val sqliteBusy = Schedule.recurs<Throwable>(3).jittered()
    val fileWatch = Schedule.exponential<Throwable>(100.milliseconds)
        .untilOutput { it > 5.seconds }
    val zeroDelay = Schedule.recurs<Throwable>(3)  // for tests
}
```

### retry { } — wrapping suspend functions

```kotlin
import arrow.resilience.retry

// Wrap any suspend fun
suspend fun saveWithRetry(block: Block): Result<Unit> =
    RetryPolicies.sqliteBusy.retry { blockRepository.saveBlock(block) }
```

### CircuitBreaker

```kotlin
import arrow.resilience.CircuitBreaker
import kotlin.time.Duration.Companion.seconds

val llmCircuitBreaker = CircuitBreaker(
    openingStrategy = CircuitBreaker.OpeningStrategy.Count(
        maxFailures = 5,
        resetTimeout = 30.seconds,
        exponentialBackoffFactor = 2.0,
        maxResetTimeout = 5.minutes,
    )
)

// Wrap HTTP calls
suspend fun formatWithLlm(text: String): Either<DomainError, String> = either {
    llmCircuitBreaker.protectOrThrow {
        llmProvider.format(text)
    }.mapLeft { NetworkError.CircuitOpen }.bind()
}
```

---

## 6. Arrow Saga Pattern

### Saga IS in Arrow 2.x

Arrow Resilience (`arrow-resilience`) includes a `saga { }` builder. The Saga pattern is part of the stable Arrow 2.x API.

```kotlin
import arrow.resilience.saga

// Each step declares its compensating action inline
suspend fun saveDiskPipeline(page: Page, blocks: List<Block>): Either<DomainError, Unit> =
    saga {
        // Step 1: Write markdown file
        val oldContent = fileSystem.readFile(page.filePath) // read for rollback
        sagas(
            action = { fileSystem.writeFile(page.filePath, renderMarkdown(blocks)) },
            compensation = {
                // Rollback: restore old content if step 2 or 3 fails
                if (oldContent != null) fileSystem.writeFile(page.filePath, oldContent)
            }
        )

        // Step 2: Upsert page in DB
        sagas(
            action = { actor.savePage(page).bind() },
            compensation = {
                // Rollback: delete the page we just wrote (or restore previous version)
                actor.deletePage(page.uuid)
            }
        )

        // Step 3: Upsert blocks in DB
        sagas(
            action = { actor.saveBlocks(blocks).bind() },
            compensation = {
                actor.deleteBlocksForPage(page.uuid)
            }
        )

        // Step 4: Update sidecar
        sagas(
            action = { sidecarManager.write(page.name, blocks) },
            compensation = { /* sidecar failure is non-critical; log only */ }
        )
    }.toEither { DomainError.DatabaseError(it.message ?: "saga failed") }
```

### Saga + Raise integration

The saga DSL composes with Raise — raise() inside a saga step triggers compensations:

```kotlin
saga {
    sagas(
        action = {
            val result = writeFile(path)
            ensure(result) { FileSystemError.WriteFailed(path) }  // raises → compensations run
        },
        compensation = { restoreBackup(path) }
    )
}
```

### Voice Pipeline Saga

```kotlin
suspend fun recordTranscribeFormatSave(audioData: ByteArray): Either<DomainError, Block> = saga {
    val recordingPath = sagas(
        action = { audioRecorder.save(audioData) },
        compensation = { audioRecorder.delete(recordingPath) }
    )
    val transcript = sagas(
        action = { transcriber.transcribe(recordingPath) },
        compensation = { /* no compensation needed for transcription */ }
    )
    val formatted = sagas(
        action = { llmFormatter.format(transcript) },
        compensation = { /* no rollback for LLM call */ }
    )
    sagas(
        action = { blockRepository.saveBlock(Block(content = formatted)) },
        compensation = { blockRepository.deleteBlock(savedBlock.uuid) }
    )
}.toEither { ... }
```

---

## Sources

- [Arrow STM Docs](https://arrow-kt.io/learn/coroutines/stm/)
- [Arrow Saga Docs](https://arrow-kt.io/learn/resilience/saga/)
- [Arrow From Either to Raise](https://arrow-kt.io/learn/typed-errors/from-either-to-raise/)
- [Arrow Effect API Docs](https://apidocs.arrow-kt.io/arrow-core/arrow.core.raise/-effect/index.html)
- [Arrow 2.0 Release](https://arrow-kt.io/community/blog/2024/12/05/arrow-2-0/)
- [Arrow 2.2.0 Release](https://arrow-kt.io/community/blog/2025/11/01/arrow-2-2/)
- [Arrow Parallelism Docs](https://arrow-kt.io/learn/coroutines/parallel/)
