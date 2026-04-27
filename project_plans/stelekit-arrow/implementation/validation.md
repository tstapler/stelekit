# Validation Plan: Arrow Integration for SteleKit

> Phase 4 of MDD — maps every requirement to a test case before any code is written.
> Input: `requirements.md` + `implementation/plan.md`. Output: authoritative test design.

---

## 1. Requirement-to-Test Traceability Matrix

| Requirement | Description | Test Case(s) | Minimum Pass Condition |
|---|---|---|---|
| FR-1 | Typed error handling — Either at boundaries | TC-001 – TC-010 | All 10 pass; zero `Result<T>` at public API |
| FR-2 | Optics code generation and lens usage | TC-011 – TC-016 | KSP generates lenses; lens set == copy() |
| FR-3 | Resource lifecycle — guaranteed finalizers | TC-017 – TC-022 | Finalizer runs on cancel AND on throw |
| FR-4 | STM priority queue and debounce map | TC-023 – TC-030 | Priority ordering correct; no data loss |
| FR-5 | Saga pattern — compensation on partial failure | TC-031 – TC-036 | Reverse-order compensation verified |
| FR-6 | Resilience — retry, schedule, circuit breaker | TC-037 – TC-042 | Exact retry count; CB opens and half-opens |
| NFR-1 | KMP compilation on all 4 targets | TC-043 – TC-046 | `compileKotlin*` tasks succeed |
| NFR-3 | Performance — no regression vs baseline | TC-047 – TC-048 | Within 5% of baseline throughput |
| NFR-4 | Testability — DomainError exhaustive + RetryPolicies injectable | TC-001, TC-037, TC-038 | `when` exhaustive compile; zero-delay schedule works |

All 10 requirements (6 FR + 4 NFR) have at least one test case.

---

## 2. Test Cases by Category

### FR-1 — Typed Error Handling (TC-001 through TC-010)

---

#### TC-001
- **Requirement:** FR-1, NFR-4
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies the `DomainError` sealed hierarchy is exhaustive — every branch must be handled or the Kotlin compiler rejects the file.
- **Given:** A `DomainError` instance created for each concrete subtype (WriteFailed, ReadFailed, NotFound, TransactionFailed, FileSystemError.NotFound, WriteFailed, ReadFailed, DeleteFailed, ParseError.EmptyFile, InvalidSyntax, MalformedMarkdown, ConflictError.DiskConflict, ConcurrentWrite, ValidationError.InvalidUuid, EmptyName, ConstraintViolation, NetworkError.HttpError, CircuitOpen, Timeout)
- **When:** A `when(err)` expression without an `else` branch compiles and covers every variant
- **Then:** The test file compiles; each branch asserts its `message` property is non-blank

**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorTest.kt`

---

#### TC-002
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies `DatabaseError.NotFound` produces the expected message string from its auto-generated `message` property.
- **Given:** `DomainError.DatabaseError.NotFound("page", "abc-123")`
- **When:** `.message` is read
- **Then:** Returns `"page not found: abc-123"`

**File:** Same as TC-001

---

#### TC-003
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `businessTest`
- **Description:** Verifies `Either.Left(DatabaseError)` propagates from a fake repository through to the ViewModel's state without losing the error variant.
- **Given:** A `FakeDomainErrorRepository` that always returns `DomainError.DatabaseError.ReadFailed("simulated").left()`
- **When:** The ViewModel collects the Flow and updates its state
- **Then:** `state.error` is `DomainError.DatabaseError.ReadFailed` — not a generic `Throwable` or string

**File:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/error/EitherPropagationTest.kt`

---

#### TC-004
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies that `raise()` inside an `either { }` block short-circuits without executing subsequent lines.
- **Given:** An `either { }` block that calls `raise(DomainError.DatabaseError.WriteFailed("boom"))` before a side-effectful counter increment
- **When:** The block executes
- **Then:** Result is `Left(WriteFailed("boom"))`; the counter was not incremented

**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/RaiseShortCircuitTest.kt`

---

#### TC-005
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies `Flow<Either<DomainError, T>>` emits `Left` when the underlying query throws.
- **Given:** A `flow { throw RuntimeException("db error") }.map { it.right() }.catch { emit(DomainError.DatabaseError.ReadFailed(it.message ?: "").left()) }`
- **When:** Collected with `first()`
- **Then:** Emission is `Left(ReadFailed("db error"))`

**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/FlowEitherTest.kt`

---

#### TC-006
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies `Flow<Either<DomainError, T>>` emits `Right` when the query succeeds.
- **Given:** A flow that emits a valid `Page` wrapped in `.right()`
- **When:** Collected with `first()`
- **Then:** Emission is `Right(page)` where `page.name` matches what was inserted

**File:** Same as TC-005

---

#### TC-007
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies that the specific `DomainError` variant is preserved through a `flatMap { it.flatMap { ... } }` chain.
- **Given:** `Either.Left(DomainError.DatabaseError.NotFound("block", "uuid-x"))`
- **When:** `.flatMap { /* never reached */ Right(Unit) }` is called
- **Then:** Result is still `Left(NotFound("block", "uuid-x"))` — not wrapped or transformed

**File:** Same as TC-005

---

#### TC-008
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies that `bind()` inside an outer `either { }` block propagates inner Left without reaching subsequent lines.
- **Given:** An inner `Either.Left(DomainError.ValidationError.InvalidUuid("bad-id"))` bound inside an outer `either { inner.bind(); /* unreachable */ }`
- **When:** The outer block executes
- **Then:** Result is `Left(InvalidUuid("bad-id"))`; the unreachable code was not executed (verified by counter)

**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/BindPropagationTest.kt`

---

#### TC-009
- **Requirement:** FR-1
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** End-to-end: a SQLDelight write method that catches a simulated exception maps it to `Left(DatabaseError.WriteFailed)`, not a raw exception.
- **Given:** A `SqlDelightBlockRepository` backed by an in-memory SQLite; a `SaveBlocks` call where the underlying query is stubbed to throw `SQLiteException`
- **When:** `saveBlocks(blocks)` is called
- **Then:** Result is `Left(DomainError.DatabaseError.WriteFailed(...))` — `isLeft()` is true; no exception escapes to the caller

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/error/SqlDelightErrorMappingTest.kt`

---

#### TC-010
- **Requirement:** FR-1
- **Type:** Unit
- **Source set:** `businessTest`
- **Description:** Verifies the ViewModel's `StateFlow` contains a typed `DomainError` (not raw `Throwable`) when the repository returns Left.
- **Given:** A `StelekitViewModel` wired to a `FakeDomainErrorRepository` that returns `Left(FileSystemError.NotFound("/missing.md"))`
- **When:** `navigateTo(pageUuid)` is invoked and state is collected
- **Then:** `state.value.error` is `DomainError.FileSystemError.NotFound` with the correct path; `state.value.error` is NOT `Throwable`

**File:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/error/ViewModelErrorStateTest.kt`

---

### FR-2 — Optics (TC-011 through TC-016)

---

#### TC-011
- **Requirement:** FR-2
- **Type:** Smoke
- **Source set:** `jvmTest`
- **Description:** Verifies KSP generated a lens for every field of `Block` — compile-time smoke test that KSP ran correctly.
- **Given:** The project has been compiled with KSP enabled
- **When:** `Block.content`, `Block.uuid`, `Block.parentUuid`, `Block.position`, `Block.level` lenses are referenced in code
- **Then:** The file compiles without "unresolved reference" errors; `Block.content.get(block)` returns the block's content

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/optics/OpticsSmokeTest.kt`

---

#### TC-012
- **Requirement:** FR-2
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies the `Page.blocks` traversal via `Page.blocks compose Every.list()` retrieves all blocks.
- **Given:** A `Page` holding a list of 5 `Block`s
- **When:** `(Page.blocks compose Every.list()).getAll(page)` is called
- **Then:** Returns a list of exactly 5 blocks matching the originals

**File:** Same as TC-011

---

#### TC-013
- **Requirement:** FR-2
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies that a lens `set` produces the same result as the equivalent `.copy()` call.
- **Given:** A `Page` with `name = "old"`
- **When:** `Page.name.set(page, "new")` is compared with `page.copy(name = "new")`
- **Then:** Both results are equal (`==`) — optic set is semantically equivalent to copy

**File:** Same as TC-011

---

#### TC-014
- **Requirement:** FR-2
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies a lens `modify` call transforms the field value.
- **Given:** A `Page` with `name = "hello"`
- **When:** `Page.name.modify(page) { it.uppercase() }`
- **Then:** Result page has `name = "HELLO"`; all other fields are unchanged

**File:** Same as TC-011

---

#### TC-015
- **Requirement:** FR-2
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies a deep update via composed optics matches the hand-written `.copy()` chain for a nested Block content update inside a Page.
- **Given:** A `Page` with blocks where one `Block` has `content = "original"`
- **When:** `(Page.blocks compose Every.list() compose Block.content).modify(page) { if (it == "original") "updated" else it }` is called
- **Then:** The result equals `page.copy(blocks = page.blocks.map { if (it.content == "original") it.copy(content = "updated") else it })`

**File:** Same as TC-011

---

#### TC-016
- **Requirement:** FR-2
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies `AppState` lenses are generated and usable — a lens set on a nested field round-trips correctly.
- **Given:** An `AppState` with `isSidebarOpen = false`
- **When:** `AppState.isSidebarOpen.set(state, true)`
- **Then:** Result has `isSidebarOpen = true`; all other AppState fields are unchanged

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/optics/AppStateOpticsTest.kt`

---

### FR-3 — Resource Lifecycle (TC-017 through TC-022)

---

#### TC-017
- **Requirement:** FR-3
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies a `Resource<GraphResources>` releases the DB driver on normal `use` block exit.
- **Given:** A fake driver that records `close()` calls; a `Resource<GraphResources>` wrapping it
- **When:** `resource.use { /* do nothing */ }` completes normally
- **Then:** The fake driver's `close()` was called exactly once

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphResourcesLifecycleTest.kt`

---

#### TC-018
- **Requirement:** FR-3
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies the Resource finalizer runs when the parent coroutine scope is cancelled.
- **Given:** A `Resource<GraphResources>` with a `finalizerInvoked` flag; the resource is allocated inside a `CoroutineScope`
- **When:** `scope.cancel()` is called while the resource is held
- **Then:** `finalizerInvoked` is `true` after the scope completes — cancellation triggers release

**File:** Same as TC-017

---

#### TC-019
- **Requirement:** FR-3
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies the Resource finalizer runs when the `use` block throws an exception.
- **Given:** A `Resource<GraphResources>` with a `finalizerInvoked` counter
- **When:** `resource.use { throw RuntimeException("simulated") }` is called and the exception is caught
- **Then:** `finalizerInvoked` is `true`; the original exception propagates to the caller

**File:** Same as TC-017

---

#### TC-020
- **Requirement:** FR-3
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies `GraphWriter.resource(...)` finalizer flushes pending saves and cancels the debounce scope before returning.
- **Given:** A `GraphWriter` with one pending debounced save; a fake `FileSystem` that records writes
- **When:** The `Resource<GraphWriter>` is released (via `use` block exit)
- **Then:** The fake FileSystem recorded the save that was still pending; no orphaned coroutine jobs remain

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphWriterResourceTest.kt`

---

#### TC-021
- **Requirement:** FR-3
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies a file handle opened inside `GraphLoader` import is closed even when a parse exception occurs mid-import.
- **Given:** A fake `FileSystem` where reading returns valid content then a malformed line that triggers `ParseError`; the handle records whether `close()` was called
- **When:** `GraphLoader` attempts to import the file and the parse fails
- **Then:** The fake handle's `close()` was invoked; result is `Left(ParseError.MalformedMarkdown(...))`

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderResourceTest.kt`

---

#### TC-022
- **Requirement:** FR-3
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies that nested `Resource` allocations (driver → database → actor) all release in reverse order when the outermost resource exits.
- **Given:** Three resources installed via `install()` in a `resource { }` block; each records its release order in a shared list
- **When:** The outermost resource exits
- **Then:** Release order list is `[actor, database, driver]` — last-in first-out

**File:** Same as TC-017

---

### FR-4 — STM (TC-023 through TC-030)

---

#### TC-023
- **Requirement:** FR-4
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies a `TVar` read inside `atomically` sees the latest committed value after a prior `atomically` write.
- **Given:** A `TVar<Int>` initialized to 0
- **When:** `atomically { tvar.write(42) }` runs, then `atomically { tvar.read() }` runs
- **Then:** Read returns 42

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/StmBasicTest.kt`

---

#### TC-024
- **Requirement:** FR-4
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies two concurrent `atomically` blocks on the same `TVar` serialize correctly — the final value reflects both writes without data corruption.
- **Given:** A `TVar<Int>` initialized to 0; two coroutines each atomically incrementing by 1000 iterations
- **When:** Both coroutines complete
- **Then:** Final value is exactly 2000 — no lost updates

**File:** Same as TC-023

---

#### TC-025
- **Requirement:** FR-4
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies the STM-based `DatabaseWriteActor` services a HIGH-priority write before a batch of LOW-priority writes that arrived first.
- **Given:** `DatabaseWriteActor` with STM queue; 10 `SaveBlocks` at `Priority.LOW` enqueued; then 1 `SaveBlocks` at `Priority.HIGH` enqueued mid-batch
- **When:** The actor processes all requests
- **Then:** The HIGH-priority request completes before any of the 10 LOW-priority requests; verified by ordering deferred completion timestamps

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorStmTest.kt`

---

#### TC-026
- **Requirement:** FR-4
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies write coalescing: 3 consecutive `SaveBlocks` for the same page at LOW priority result in a single DB transaction.
- **Given:** A `FakeBlockRepository` that counts `saveBlocks` calls; 3 `SaveBlocks` requests enqueued for the same page blocks
- **When:** The actor processes all requests
- **Then:** `FakeBlockRepository.saveBlocksCallCount == 1` — the 3 requests were coalesced into one transaction

**File:** Same as TC-025

---

#### TC-027
- **Requirement:** FR-4
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies retry-individual-on-batch-failure: if a combined batch of 3 requests fails, each is retried individually and partial successes are preserved.
- **Given:** A `FakeBlockRepository` configured to fail combined transactions but succeed individual ones; 3 `SaveBlocks` requests
- **When:** The actor processes the batch
- **Then:** All 3 individual `deferred.await()` return `Right(Unit)`; `saveBlocks` was called 4 times total (1 batch + 3 individual)

**File:** Same as TC-025

---

#### TC-028
- **Requirement:** FR-4
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies STM `retry()` blocks the transaction until the `TVar` changes.
- **Given:** A `TVar<Boolean>` initialized to `false`; a coroutine running `atomically { if (!tvar.read()) retry(); "done" }`
- **When:** Another coroutine sets `tvar` to `true` after 100ms
- **Then:** The blocked transaction completes with "done" after the `TVar` changes; total wait < 500ms

**File:** Same as TC-023

---

#### TC-029
- **Requirement:** FR-4
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies the STM `TMap` used in `GraphWriter` for debounce state: inserting a key, looking it up, and removing it atomically.
- **Given:** A `TMap<String, SaveRequest>` initialized via `TMap.new()`
- **When:** `atomically { tmap.insert("page-uuid", request) }`, then `atomically { tmap.lookup("page-uuid") }`, then `atomically { tmap.remove("page-uuid") }`
- **Then:** Lookup returns `Some(request)` before removal, `None` after removal

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/StmTMapTest.kt`

---

#### TC-030
- **Requirement:** FR-4
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies that cancelling a pending debounce job in `GraphWriter` via the STM map is race-free: the replaced request's job is cancelled before the new job is launched.
- **Given:** A `GraphWriter` with STM debounce state; `queueSave` called twice rapidly for the same page UUID
- **When:** The debounce delay elapses once
- **Then:** Only one save fires (the second request); the first job was cancelled; `FileSystem.writeFile` called exactly once

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphWriterStmDebounceTest.kt`

---

### FR-5 — Saga (TC-031 through TC-036)

---

#### TC-031
- **Requirement:** FR-5
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Happy path: all 4 saga steps complete successfully and no compensation is called.
- **Given:** A `GraphWriter` saga with all `FileSystem` and DB stubs returning success; a compensation-invocation counter for each step
- **When:** `savePageInternal(page, blocks, graphPath)` completes
- **Then:** Result is `Right(Unit)`; all 4 step actions were invoked; compensation counter for each step is 0

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphWriterSagaTest.kt`

---

#### TC-032
- **Requirement:** FR-5
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Step 2 (upsertPage) fails: compensation for step 1 (writeMarkdown) runs and restores old file content.
- **Given:** A fake `FileSystem` where `writeFile` succeeds on first call; a fake DB actor where `savePage` returns `Left(DatabaseError.WriteFailed(...))`; the old file content is "original content"
- **When:** `savePageInternal` runs
- **Then:** Result is `Left`; `FileSystem.writeFile` was called twice — once with new content, once with "original content" (rollback); final on-disk content is "original content"

**File:** Same as TC-031

---

#### TC-033
- **Requirement:** FR-5
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Step 3 (upsertBlocks) fails: compensation for steps 1 and 2 runs in reverse order.
- **Given:** Stubs where file write and page upsert succeed; block upsert returns failure; separate compensation invocation lists for each step
- **When:** `savePageInternal` runs
- **Then:** Result is `Left`; compensation list shows step-2 compensation ran before step-1 compensation (reverse order)

**File:** Same as TC-031

---

#### TC-034
- **Requirement:** FR-5
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies that a compensation action that itself throws does not mask the original saga error.
- **Given:** Step 2 fails with `DomainError.DatabaseError.WriteFailed("original")`; step-1 compensation throws `RuntimeException("compensation error")`
- **When:** `savePageInternal` runs
- **Then:** The returned error is `Left` containing a message referencing "original" — the compensation exception does not replace or swallow the original failure

**File:** Same as TC-031

---

#### TC-035
- **Requirement:** FR-5
- **Type:** Unit
- **Source set:** `businessTest`
- **Description:** Voice pipeline saga: transcription step fails → recording cleanup (compensation for step 1) runs.
- **Given:** A `VoiceSaga` with a stub `AudioRecorder` that records `delete(path)` calls; a stub `Transcriber` that throws; `AudioRecorder.save(audioData)` returns a path
- **When:** `recordTranscribeFormatSave(audioData)` runs
- **Then:** Result is `Left(ParseError.InvalidSyntax(...))`; `AudioRecorder.delete(path)` was called exactly once — the recording was cleaned up

**File:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceSagaTest.kt`

---

#### TC-036
- **Requirement:** FR-5
- **Type:** Unit
- **Source set:** `businessTest`
- **Description:** Voice pipeline happy path: all 4 steps succeed, saved block is returned, no compensation actions fire.
- **Given:** All voice pipeline stubs return success; a `delete` call counter on `AudioRecorder`
- **When:** `recordTranscribeFormatSave(audioData)` runs
- **Then:** Result is `Right(block)` where `block.content` contains the LLM-formatted text; `AudioRecorder.delete` was never called

**File:** Same as TC-035

---

### FR-6 — Resilience (TC-037 through TC-042)

---

#### TC-037
- **Requirement:** FR-6, NFR-4
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies `Schedule.recurs(3)` retries exactly 3 times before propagating the error.
- **Given:** A fake operation that always throws `RuntimeException("busy")`; `RetryPolicies.testImmediate` (zero-delay, recurs 3)
- **When:** `RetryPolicies.testImmediate.retry { fakeOp() }` is called
- **Then:** The fake op's call counter is exactly 4 (1 initial + 3 retries); a `RuntimeException` is thrown after exhaustion

**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/resilience/RetryPoliciesTest.kt`

---

#### TC-038
- **Requirement:** FR-6, NFR-4
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies that `RetryPolicies.testImmediate` succeeds when the fake operation fails twice then succeeds, resulting in exactly 3 total attempts.
- **Given:** A fake operation that throws on attempts 1 and 2, succeeds on attempt 3
- **When:** `RetryPolicies.testImmediate.retry { fakeOp() }` completes
- **Then:** Returns the success value; fake op call counter is exactly 3; no exception propagates

**File:** Same as TC-037

---

#### TC-039
- **Requirement:** FR-6
- **Type:** Unit
- **Source set:** `commonTest`
- **Description:** Verifies the exponential backoff schedule produces strictly increasing delay durations across retries (using a mock clock / schedule inspection, not wall time).
- **Given:** `RetryPolicies.fileWatchReregistration` schedule; a `Schedule` introspection that captures the output (delay) at each step
- **When:** The schedule is stepped through 4 iterations with a simulated-failure input
- **Then:** Delays are `100ms, 200ms, 400ms, 800ms` (or the first value exceeding 5s halts the schedule)

**File:** Same as TC-037

---

#### TC-040
- **Requirement:** FR-6
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies the circuit breaker opens after the configured threshold of failures and rejects subsequent calls without invoking the underlying function.
- **Given:** A `CircuitBreaker` with `maxFailures = 5`; a stub that always returns failure; call counter tracking
- **When:** 5 failing calls are made, then a 6th call is made
- **Then:** The 6th call returns `Left(NetworkError.CircuitOpen(...))` immediately; the stub was called exactly 5 times (not 6)

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/resilience/CircuitBreakerTest.kt`

---

#### TC-041
- **Requirement:** FR-6
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies the circuit breaker half-opens after the reset timeout and allows one probe call through.
- **Given:** A `CircuitBreaker` already in open state with a short `resetTimeout = 50ms`; a stub that succeeds on the probe call
- **When:** 50ms elapses and a new call is made
- **Then:** The probe call reaches the stub (stub call counter increments to 1); circuit transitions to closed; subsequent calls also succeed

**File:** Same as TC-040

---

#### TC-042
- **Requirement:** FR-6
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies that `RetryPolicies.sqliteBusy` applied to a real SQLDelight write retries on a simulated `SQLiteBusyException` and succeeds on the final attempt.
- **Given:** A `SqlDelightBlockRepository` backed by a proxy driver that throws `SQLiteBusyException` twice then succeeds; `RetryPolicies.sqliteBusy` wrapping the write
- **When:** `saveBlock(block)` is called
- **Then:** Result is `Right(Unit)`; the driver's execute method was called exactly 3 times

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/resilience/SqliteBusyRetryTest.kt`

---

### NFR-1 — KMP Compatibility (TC-043 through TC-046)

---

#### TC-043
- **Requirement:** NFR-1
- **Type:** Smoke
- **Source set:** N/A (Gradle task)
- **Description:** Verifies Arrow Either used in `commonMain` compiles for JVM.
- **Given:** Arrow `arrow-core` and `arrow-fx-coroutines` on the `commonMain` classpath
- **When:** `./gradlew compileKotlinJvm` runs in CI
- **Then:** Exit code 0; no "unresolved reference: Either" or "unresolved reference: atomically" errors

**CI Command:** `./gradlew compileKotlinJvm`

---

#### TC-044
- **Requirement:** NFR-1
- **Type:** Smoke
- **Source set:** N/A (Gradle task)
- **Description:** Verifies Arrow compiles for Android target.
- **Given:** Same as TC-043
- **When:** `./gradlew compileDebugKotlinAndroid`
- **Then:** Exit code 0

**CI Command:** `./gradlew compileDebugKotlinAndroid`

---

#### TC-045
- **Requirement:** NFR-1
- **Type:** Smoke
- **Source set:** N/A (Gradle task)
- **Description:** Verifies Arrow compiles for iOS (x64 simulator) target; also verifies `@optics` KSP generated code compiles on iOS.
- **Given:** KSP configured for `kspIosX64`
- **When:** `./gradlew compileKotlinIosX64`
- **Then:** Exit code 0; no KSP-generated "unresolved Companion" errors

**CI Command:** `./gradlew compileKotlinIosX64`

---

#### TC-046
- **Requirement:** NFR-1
- **Type:** Smoke
- **Source set:** N/A (Gradle task)
- **Description:** Verifies Arrow compiles for WASM/JS target (when `enableJs=true`); confirms STM degrades gracefully on single-threaded runtime.
- **Given:** `gradle.properties` contains `enableJs=true`; `kspWasmJs` configured
- **When:** `./gradlew compileKotlinWasmJs`
- **Then:** Exit code 0; no STM-related linkage errors

**CI Command:** `./gradlew compileKotlinWasmJs` (skipped in CI if `enableJs=false`)

---

### NFR-3 — Performance (TC-047 through TC-048)

---

#### TC-047
- **Requirement:** NFR-3
- **Type:** Integration
- **Source set:** `jvmTest`
- **Description:** Verifies `DatabaseWriteActor` STM throughput is within 5% of the pre-STM channel-based baseline, using the existing `RepositoryBenchmark` harness.
- **Given:** The existing `RepositoryBenchmark` in `kmp/src/jvmTest/kotlin/.../benchmark/RepositoryBenchmark.kt`; a baseline result captured from the main branch
- **When:** `./gradlew jvmTest --tests "*.RepositoryBenchmarkRunnerTest"` runs on the STM branch
- **Then:** Throughput (ops/sec) measured by the benchmark is >= 95% of the baseline value stored in `benchmark_baseline.json`

**File:** Extend existing `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/RepositoryBenchmarkRunnerTest.kt`

---

#### TC-048
- **Requirement:** NFR-3
- **Type:** Unit
- **Source set:** `jvmTest`
- **Description:** Verifies optics `set` does not allocate more objects than an equivalent `.copy()` call (micro-benchmark using allocation counting via JVM instrumentation).
- **Given:** A `Page` with 5 fields; 10,000 iterations of both `Page.name.set(page, "new")` and `page.copy(name = "new")`
- **When:** Both paths are timed after JVM warmup (first 1,000 iterations discarded)
- **Then:** Optics path p50 latency is <= 110% of copy() path p50 latency (10% tolerance for overhead)

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/OpticsVsCopyBenchmarkTest.kt`

---

## 3. Test Infrastructure Requirements

The following new test utilities are needed. None exist today in `jvmTest/`, `commonTest/`, or `businessTest/`.

### 3.1 `FakeDomainErrorRepository`
**Purpose:** An in-memory `BlockRepository` (and `PageRepository`) whose return values are configurable at construction time — either `Right(value)` or `Left(specificDomainError)`.
**Used by:** TC-003, TC-010
**Location:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeDomainErrorRepository.kt`

```kotlin
// Sketch — not authoritative, implementer may adjust
class FakeDomainErrorRepository(
    private val blockError: DomainError? = null,
    private val pageError: DomainError? = null,
) : BlockRepository, PageRepository {
    override suspend fun saveBlock(block: Block) =
        if (blockError != null) blockError.left() else Unit.right()
    // ... all methods follow the same pattern
}
```

### 3.2 `TestSchedule` — Zero-Delay Retry Policy
**Purpose:** An alias for `Schedule.recurs<Throwable>(3)` with zero jitter and zero base delay, so unit tests don't introduce real time delays.
**Used by:** TC-037, TC-038
**Location:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/resilience/TestSchedules.kt`

Note: `RetryPolicies.testImmediate` defined in `RetryPolicies.kt` (production code, available to all) serves this purpose per Story 5-A-1 in the plan. Tests import `RetryPolicies.testImmediate` directly; no additional test-only object is needed unless isolation is preferred.

### 3.3 `FakeFileSystem`
**Purpose:** An in-memory implementation of `FileSystem` that records reads, writes, and deletes; supports injecting failures at specific call ordinals.
**Used by:** TC-020, TC-021, TC-031 – TC-034
**Location:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/platform/FakeFileSystem.kt`

Note: Check `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt` — an in-memory file system may already exist. If not, create a new one.

```kotlin
class FakeFileSystem : FileSystem {
    private val files = mutableMapOf<String, String>()
    val writeCalls = mutableListOf<Pair<String, String>>()
    val deleteCalls = mutableListOf<String>()
    var failOnWriteAttempt: Int? = null  // null = never fail
    private var writeAttempts = 0

    override suspend fun writeFile(path: String, content: String): Boolean {
        writeAttempts++
        if (writeAttempts == failOnWriteAttempt) return false
        files[path] = content
        writeCalls.add(path to content)
        return true
    }
    // readFile, deleteFile, fileExists similarly
}
```

### 3.4 STM Concurrency Test Harness
**Purpose:** Utilities for running N coroutines concurrently in `TestScope` and collecting ordered completion timestamps.
**Used by:** TC-024, TC-025, TC-026, TC-027
**Location:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/StmTestHarness.kt`

```kotlin
// Runs [n] coroutines concurrently, returns list of completion timestamps in order
suspend fun runConcurrentRequests(n: Int, block: suspend (Int) -> Unit): List<Long>
```

Uses `kotlinx-coroutines-test`'s `TestScope` + `UnconfinedTestDispatcher` for deterministic ordering where needed; switches to real dispatchers for genuine concurrency tests (TC-024).

### 3.5 `FakeAudioRecorder` and `FakeTranscriber`
**Purpose:** Controllable stubs for the voice pipeline saga tests.
**Used by:** TC-035, TC-036
**Location:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceSagaTestDoubles.kt`

```kotlin
class FakeAudioRecorder(private val savePath: String = "/tmp/audio.m4a") : AudioRecorder {
    val deletedPaths = mutableListOf<String>()
    override suspend fun save(data: ByteArray): String? = savePath
    override suspend fun delete(path: String) { deletedPaths.add(path) }
}

class FailingTranscriber(private val error: DomainError) : SpeechToTextProvider {
    override suspend fun transcribe(path: String): Either<DomainError, String> = error.left()
}
```

### 3.6 `FakeBlockRepository` with call counters
**Purpose:** Extends the existing `FakeRepositories.kt` with call-count tracking and configurable per-call failure modes.
**Used by:** TC-026, TC-027, TC-042
**Location:** Extend `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt`

---

## 4. Definition of Done Checklist

Each checkbox maps to one or more test case IDs. All boxes must be checked before the Arrow migration PR is merged.

- [ ] **Zero `Result<T>` at repository/service API boundaries** — all methods return `Either<DomainError, T>` or `Flow<Either<DomainError, T>>`
  _Verified by: TC-009 (SQLDelight mapping), TC-010 (ViewModel state), and ArchTest compile-time check (Story 5-C-2)_

- [ ] **`DomainError` sealed hierarchy covers all current exception types** thrown across `db/`, `repository/`, `voice/` — verified by exhaustive `when` expression
  _Verified by: TC-001, TC-002 (compile-time exhaustive match)_

- [ ] **`GraphManager` and `GraphWriter` resource cleanup is guaranteed** — cancel parent scope, assert finalizers ran (DB driver closed, scope jobs cancelled)
  _Verified by: TC-017, TC-018, TC-019 (GraphResources), TC-020 (GraphWriter)_

- [ ] **At least `DatabaseWriteActor` uses STM `TVar<WriteQueues>`** for its priority queue; `GraphWriter` uses `TMap<String, SaveRequest>` for debounce state
  _Verified by: TC-025 (priority ordering), TC-030 (debounce race safety), TC-029 (TMap operations)_

- [ ] **Disk-write saga implemented with at least 2 compensation steps tested**: file rollback on DB failure, and block deletion on voice pipeline error
  _Verified by: TC-032 (file rollback on step-2 failure), TC-033 (reverse-order compensation), TC-035 (voice recording cleanup)_

- [ ] **All CI checks pass on all platforms**: `./gradlew ciCheck` (detekt + jvmTest + testDebugUnitTest + assembleDebug)
  _Verified by: TC-043 – TC-046 (platform compilation smokes) + full `ciCheck` in CI pipeline_

- [ ] **Existing benchmark results do not regress by more than 5%**: `./gradlew jvmBenchmark` before vs after each tier
  _Verified by: TC-047 (write actor throughput), TC-048 (optics allocation)_

- [ ] **`@optics` lenses compile and are exercised**
  _Verified by: TC-011 (Block lenses), TC-012 (Page traversal), TC-016 (AppState lenses)_

- [ ] **`RetryPolicies` object exists** with at least `sqliteBusy` and `fileWatchReregistration` constants; used in at least one repository
  _Verified by: TC-037, TC-038 (testImmediate policy), TC-039 (exponential backoff), TC-042 (integration with SQLDelight)_

- [ ] **Circuit breaker wraps `ClaudeLlmFormatterProvider`**; `CircuitBreakerTest` asserts open-circuit behavior
  _Verified by: TC-040 (opens after threshold), TC-041 (half-opens after timeout)_

- [ ] **`Either.Left` propagates from repository through ViewModel** without losing type variant
  _Verified by: TC-003, TC-007, TC-008 (flatMap / bind chain preservation)_

- [ ] **Saga compensation does not mask original error**
  _Verified by: TC-034 (throwing compensation)_

---

## Appendix: Test Count Summary

| Category | Count | Source Sets Used |
|---|---|---|
| FR-1 Typed Error Handling | 10 | commonTest (TC-001 – TC-008), jvmTest (TC-009), businessTest (TC-010) |
| FR-2 Optics | 6 | jvmTest (TC-011 – TC-016) |
| FR-3 Resource Lifecycle | 6 | jvmTest (TC-017 – TC-022) |
| FR-4 STM | 8 | jvmTest (TC-023 – TC-030) |
| FR-5 Saga | 6 | jvmTest (TC-031 – TC-034), businessTest (TC-035 – TC-036) |
| FR-6 Resilience | 6 | commonTest (TC-037 – TC-039), jvmTest (TC-040 – TC-042) |
| NFR-1 KMP Compatibility | 4 | Gradle tasks (TC-043 – TC-046) |
| NFR-3 Performance | 2 | jvmTest (TC-047 – TC-048) |
| **Total** | **48** | |

| Type | Count |
|---|---|
| Unit | 26 |
| Integration | 14 |
| Property-based | 0 (existing `TreeOperationsPropertyTest` and `ParsingPropertyTest` cover structural properties; Arrow migration does not introduce new property domains) |
| Smoke | 8 (TC-011, TC-043 – TC-046 plus KSP smoke in TC-011, TC-016) |
