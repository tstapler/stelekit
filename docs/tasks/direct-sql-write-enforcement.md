# Direct SQL Write Enforcement

## Epic Overview

### Problem

`@DirectRepositoryWrite` (in `repository/DirectRepositoryWrite.kt`) is applied to repository *interface* methods. It guards the `BlockRepository` and `PageRepository` call sites from being invoked outside `DatabaseWriteActor`, but it does nothing to guard direct access to the SQLDelight-generated `SteleDatabaseQueries` object.

`OperationLogger` holds a raw `SteleDatabase` reference and calls `db.steleDatabaseQueries.upsertLogicalClock()` and `db.steleDatabaseQueries.insertOperation()` directly — these are two separate auto-commit write-lock acquisitions on the SQLite connection. This is what caused the production `SQLITE_BUSY` lockout: the actor serializes writes from repository layer, but `OperationLogger` was acquiring SQLite write locks outside the actor's serialization boundary.

Additional direct writers discovered during audit:

| File | Methods called directly | Notes |
|---|---|---|
| `OperationLogger.kt` | `upsertLogicalClock`, `insertOperation` | Primary offender; two locks per log call (now one transaction, but still outside actor) |
| `HistogramWriter.kt` | `insertHistogramBucketIfAbsent`, `incrementHistogramBucketCount` | Already routes through `writeActor.execute()` when actor is non-null; the `writeOp` lambda still calls `database.steleDatabaseQueries.*` inside the lambda, which is acceptable since it runs inside the actor — but the call site is currently not enforced by the type system |
| `DebugFlagRepository.kt` | `upsertDebugFlag` | Direct write, no actor involvement |
| `HistogramRetentionJob.kt` | `deleteOldHistogramRows` | Direct write, no actor involvement |
| `ChangelogRepository.kt` | `insertMigrationRecord`, `updateMigrationStatus`, `deleteMigrationRecord`, `updateMigrationChecksum` | Migration-time writes; deliberately run before actor exists — needs explicit exemption |
| `UuidMigration.kt` | Multiple `update*ForMigration`, `upsertMetadata` | Migration-time writes; same exemption rationale |

### Solution

Introduce a `@DirectSqlWrite` opt-in annotation and a `RestrictedDatabaseQueries` wrapper class. The generated `SteleDatabaseQueries` cannot be annotated (it is generated code), so the wrapper pattern is mandatory: wrap the generated class, annotate every mutating method on the wrapper, and replace all `SteleDatabase` injections that need write access with `RestrictedDatabaseQueries`.

### Success Criteria

- `./gradlew jvmTest` passes with zero new failures.
- Any code that calls a mutating query method on `SteleDatabaseQueries` directly (not through `RestrictedDatabaseQueries`) produces a compile-time error unless it carries `@OptIn(DirectSqlWrite::class)` or is itself annotated `@DirectSqlWrite`.
- `DatabaseWriteActor` is the only class with an unconditional `@OptIn(DirectSqlWrite::class)` at class level (or on its actor-internal execute methods).
- `OperationLogger.log()` carries `@OptIn(DirectSqlWrite::class)` at method level; its public API (`logInsert`, `logUpdate`, etc.) does not.
- `ChangelogRepository` and `UuidMigration` carry `@DirectSqlWrite` at class level with a doc comment explaining they are migration-only writers exempt from actor routing.
- `@DirectRepositoryWrite` is deleted; all enforcement is provided by `@DirectSqlWrite`.

---

## Architecture Decision: `@RequiresOptIn` vs. Alternatives

### Options Considered

**Option A — `@RequiresOptIn(level = ERROR)` wrapper (chosen)**

Wrap the generated `SteleDatabaseQueries` in a hand-written `RestrictedDatabaseQueries` class. Every mutating method on the wrapper is annotated `@DirectSqlWrite`. Read-only queries are forwarded without annotation.

- Pros: Compile-time enforcement. Zero runtime overhead. Works identically on JVM, Android, and native/JS targets. No tooling setup required beyond the annotation and wrapper class. Compatible with KMP binary annotation retention.
- Cons: Wrapper must be kept in sync with `.sq` schema additions. Every new mutating query needs a forwarding stub.

**Option B — Android/JVM Lint rule**

Write a custom Lint check that flags `steleDatabaseQueries.*` calls in non-actor classes.

- Pros: No code changes to existing callers needed immediately.
- Cons: Lint runs only on Android module; JVM desktop target is not covered. Requires Lint API knowledge, separate module, and CI configuration. Violations surface as warnings by default; promoting to error requires additional configuration. Does not protect KMP common source set.

**Option C — Architecture tests (ArchUnit)**

Write ArchUnit rules asserting that only designated classes may call `SteleDatabaseQueries` mutation methods.

- Pros: Clear failure messages; can be run as a JVM test.
- Cons: JVM only; does not cover Android or native. Fails at test time, not compile time. Requires ArchUnit dependency. Reflection-based so is not robust against obfuscation or name changes.

**Option D — Code review convention**

Document the rule; enforce via PR review.

- Pros: Zero implementation work.
- Cons: Relies on reviewers catching every future call site. Did not catch the original `OperationLogger` bug.

### Decision

Option A. Compile-time enforcement via `@RequiresOptIn` is the only approach that provides universal KMP coverage (JVM + Android + native) and produces errors at build time rather than test or review time. The wrapper maintenance burden is bounded: the schema is stable, and new mutations are infrequent relative to reads.

### KMP Gotchas for `@RequiresOptIn`

- `@Retention(AnnotationRetention.BINARY)` is required for the annotation to survive compilation and be visible across module boundaries (commonMain → jvmMain, androidMain, etc.).
- `@RequiresOptIn` on `commonMain` annotations works on all KMP targets since Kotlin 1.7. No per-target annotation redeclaration is needed.
- The generated `SteleDatabaseQueries` class cannot be touched — do not attempt to annotate it post-generation or via a Kotlin compiler plugin. The wrapper is the only safe approach.
- `@OptIn` at a `class` level applies to all methods in that class. Use this only for `DatabaseWriteActor` and migration-time classes. All other opt-ins must be at the specific `private fun` level.

---

## Dependency Visualization

```
Story 1 (annotation + wrapper scaffold)
    │
    ▼
Story 2 (migrate OperationLogger + DatabaseWriteActor)
    │
    ▼
Story 3 (migrate remaining callsites + delete @DirectRepositoryWrite)
```

Story 2 depends on Story 1 because `RestrictedDatabaseQueries` must exist before `OperationLogger` can be migrated to it.
Story 3 depends on Story 2 because we need to validate the actor migration is clean before deleting the old annotation.

---

## Story 1: Annotation + Wrapper Scaffold

**Goal**: Create `@DirectSqlWrite` and `RestrictedDatabaseQueries`. No existing code is migrated yet; the project must still compile.

### Task 1.1 — Create `@DirectSqlWrite` annotation

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DirectSqlWrite.kt` (new file)

**Implementation**:
```kotlin
package dev.stapler.stelekit.db

@RequiresOptIn(
    message = "Direct SQL writes must go through DatabaseWriteActor to prevent SQLITE_BUSY. " +
              "Opt in only if you own the transaction (actor internals, migration-time writers).",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
annotation class DirectSqlWrite
```

`@Target` includes `CLASS` so migration classes can annotate at class level, and `FUNCTION` so actor internals and `OperationLogger.log()` can annotate at method level.

**Validation**: `./gradlew jvmTest` — no compile errors; annotation is in commonMain so all targets see it.

---

### Task 1.2 — Create `RestrictedDatabaseQueries` wrapper

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt` (new file)

**Implementation approach**:

The wrapper holds an internal `SteleDatabaseQueries` reference. It exposes:
- All read-only SELECT queries as plain delegating methods (no annotation).
- All mutating queries (INSERT, UPDATE, DELETE, UPSERT) annotated `@DirectSqlWrite`.
- The `transaction {}` block annotated `@DirectSqlWrite` — because callers that want a transaction around mutations must themselves be opted in.

**Mutating queries to wrap** (derived from schema audit):

| Query name | Type |
|---|---|
| `insertBlock` | INSERT |
| `updateBlockParent` | UPDATE |
| `updateBlockParentPositionAndLevel` | UPDATE |
| `updateBlockHierarchy` | UPDATE |
| `updateBlockPositionOnly` | UPDATE |
| `updateBlockContent` | UPDATE |
| `updateBlockLevelOnly` | UPDATE |
| `updateBlockLeftUuid` | UPDATE |
| `updateBlockProperties` | UPDATE |
| `deleteBlockByUuid` | DELETE |
| `deleteBlockChildren` | DELETE |
| `deleteAllBlocks` | DELETE |
| `deleteBlocksByPageUuid` | DELETE |
| `insertPage` | INSERT |
| `updatePage` | UPDATE |
| `updatePageProperties` | UPDATE |
| `updatePageName` | UPDATE |
| `updatePageFavorite` | UPDATE |
| `deletePageByUuid` | DELETE |
| `deleteAllPages` | DELETE |
| `insertBlockReference` | INSERT |
| `deleteBlockReference` | DELETE |
| `insertPluginData` | INSERT |
| `updatePluginData` | UPDATE |
| `upsertPluginData` | UPSERT |
| `deletePluginData` | DELETE |
| `deletePluginDataByPlugin` | DELETE |
| `deletePluginDataByEntity` | DELETE |
| `insertOperation` | INSERT |
| `upsertLogicalClock` | UPSERT |
| `insertHistogramBucketIfAbsent` | INSERT (OR IGNORE) |
| `incrementHistogramBucketCount` | UPDATE |
| `deleteOldHistogramRows` | DELETE |
| `upsertDebugFlag` | UPSERT |
| `upsertMetadata` | UPSERT |
| `updateBlockUuidForMigration` | UPDATE |
| `updateParentUuidForMigration` | UPDATE |
| `updateLeftUuidForMigration` | UPDATE |
| `updateBlockReferencesFromForMigration` | UPDATE |
| `updateBlockReferencesToForMigration` | UPDATE |
| `updatePropertiesBlockUuidForMigration` | UPDATE |
| `insertMigrationRecord` | INSERT |
| `updateMigrationStatus` | UPDATE |
| `deleteMigrationRecord` | DELETE |
| `updateMigrationChecksum` | UPDATE |

Skeleton:
```kotlin
package dev.stapler.stelekit.db

/**
 * Wraps [SteleDatabaseQueries] and annotates every mutating query with [DirectSqlWrite].
 *
 * Read-only SELECT queries are forwarded without restriction.
 * The [transaction] helper is also restricted because any block passed to it may contain mutations.
 *
 * This wrapper exists because SQLDelight generates [SteleDatabaseQueries] — we cannot annotate
 * the generated class directly. All code that needs to perform writes must receive this wrapper
 * rather than the raw [SteleDatabase] or [SteleDatabaseQueries].
 */
class RestrictedDatabaseQueries(private val q: SteleDatabaseQueries) {

    // --- Restricted: transaction scope ---

    @DirectSqlWrite
    fun <T> transaction(noEnclosing: Boolean = false, body: TransactionWithReturn<T>.() -> T): T =
        q.transaction(noEnclosing, body)

    @DirectSqlWrite
    fun transaction(noEnclosing: Boolean = false, body: Transaction.() -> Unit) =
        q.transaction(noEnclosing, body)

    // --- Restricted: blocks mutations ---

    @DirectSqlWrite
    fun insertBlock(...) = q.insertBlock(...)

    // ... (one stub per mutating query in the table above)

    // --- Unrestricted: read queries (sample) ---

    fun selectBlockByUuid(uuid: String) = q.selectBlockByUuid(uuid)

    fun selectAllPages() = q.selectAllPages()

    // ... (one stub per SELECT query)
}
```

**Note on `transaction`**: The SQLDelight-generated `SteleDatabaseQueries` exposes `transaction {}` from the `Transacter` interface. `RestrictedDatabaseQueries` must wrap it and annotate it `@DirectSqlWrite` so callers cannot silently wrap multiple writes in a transaction without opting in. The `TransactionWithReturn` and `Transaction` types come from `app.cash.sqldelight.Transacter`.

**Validation**: `./gradlew jvmTest` — compiles without errors. No existing code is broken because nothing imports `RestrictedDatabaseQueries` yet.

---

## Story 2: Migrate OperationLogger + DatabaseWriteActor

**Goal**: Wire `RestrictedDatabaseQueries` into the two primary write paths and confirm the enforcement triggers correctly.

### Task 2.1 — Migrate `OperationLogger` to `RestrictedDatabaseQueries`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/OperationLogger.kt`

**Changes**:
1. Replace `private val db: SteleDatabase` constructor parameter with `private val queries: RestrictedDatabaseQueries`.
2. In `nextSeq()`: change `db.steleDatabaseQueries.selectLogicalClock(sessionId)` (read — no annotation needed) to `queries.selectLogicalClock(sessionId)`. Change `db.steleDatabaseQueries.upsertLogicalClock(sessionId, seq)` to `queries.upsertLogicalClock(sessionId, seq)`.
3. In `log()`: change `db.steleDatabaseQueries.transaction { ... }` to `queries.transaction { ... }`. Change `db.steleDatabaseQueries.insertOperation(...)` to `queries.insertOperation(...)`.
4. Annotate the `private fun log(...)` method with `@OptIn(DirectSqlWrite::class)`. Do NOT annotate the public `logInsert`, `logUpdate`, `logDelete`, etc. methods — callers of those should see no restriction.
5. Annotate `private fun nextSeq()` with `@OptIn(DirectSqlWrite::class)` (it calls `upsertLogicalClock`).

**Update construction site** in `RepositoryFactory.kt` (line 127):
```kotlin
// Before:
val opLogger = if (backend == GraphBackend.SQLDELIGHT) OperationLogger(database, sessionId) else null

// After:
val opLogger = if (backend == GraphBackend.SQLDELIGHT)
    OperationLogger(RestrictedDatabaseQueries(database.steleDatabaseQueries), sessionId)
else null
```

**Update test construction sites**:
- `MigrationTestHarness.kt` line 47
- `ChangeApplierTest.kt` line 121

Both wrap the existing `db.steleDatabaseQueries` in `RestrictedDatabaseQueries(db.steleDatabaseQueries)`.

**Validation**: `./gradlew jvmTest` — tests pass. Deliberately introduce a direct `db.steleDatabaseQueries.insertOperation()` call in `log()` without `@OptIn` and verify it fails to compile, then revert.

---

### Task 2.2 — Confirm `DatabaseWriteActor` requires no change to opt-in scope

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`

The actor does not call `steleDatabaseQueries.*` directly. It delegates to `BlockRepository` and `PageRepository` (guarded by the existing `@OptIn(DirectRepositoryWrite::class)`) and to `OperationLogger` (public methods, no restriction). Therefore no `@OptIn(DirectSqlWrite::class)` is required on the actor itself.

The `WriteRequest.Execute` path (`writeActor.execute { ... }`) accepts a lambda. If that lambda calls `RestrictedDatabaseQueries` mutations, the lambda's call site must carry `@OptIn`. This is correct: the actor itself does not opt in; the lambda author opts in at the call site.

**Validation**: Review `HistogramWriter.kt`. Its `writeOp` lambda calls `database.steleDatabaseQueries.transaction { ... }` which, after Story 3, must become `restrictedQueries.transaction { ... }` with `@OptIn(DirectSqlWrite::class)` on the lambda or the enclosing method.

---

## Story 3: Migrate Remaining Callsites + Remove `@DirectRepositoryWrite`

**Goal**: All remaining direct writers are migrated or explicitly exempted. The old `@DirectRepositoryWrite` annotation is deleted.

### Task 3.1 — Migrate `HistogramWriter` and `HistogramRetentionJob`

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramWriter.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramRetentionJob.kt`

**HistogramWriter changes**:
1. Replace `private val database: SteleDatabase` with `private val queries: RestrictedDatabaseQueries`.
2. Annotate the lambda `val writeOp: suspend () -> Result<Unit>` enclosing function or the lambda body with `@OptIn(DirectSqlWrite::class)`. The `init` block's `scope.launch` lambda is the right target — add `@OptIn(DirectSqlWrite::class)` on the inner lambda or extract the body to a private method annotated `@OptIn`.
3. Update construction in whatever wires `HistogramWriter` (search for `HistogramWriter(` in `RepositoryFactory.kt` or graph setup).

**HistogramRetentionJob changes**:
1. Replace `private val database: SteleDatabase` with `private val queries: RestrictedDatabaseQueries`.
2. The `deleteOldHistogramRows` call must be inside a method annotated `@OptIn(DirectSqlWrite::class)`.

**DebugFlagRepository changes** (`performance/DebugFlagRepository.kt`):
1. Replace raw database field with `RestrictedDatabaseQueries`.
2. The `upsertDebugFlag` call site needs `@OptIn(DirectSqlWrite::class)` on the enclosing method.

**Validation**: `./gradlew jvmTest` — no regressions.

---

### Task 3.2 — Exempt migration-time writers

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/ChangelogRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/UuidMigration.kt`

These classes write directly to the database during migration execution, before the `DatabaseWriteActor` is constructed. They are legitimately exempt because:
- Migrations run once at startup, sequentially, on a single coroutine.
- The actor does not exist yet when migrations run.

**Changes**:
1. Annotate `ChangelogRepository` at class level with `@DirectSqlWrite`. Add a KDoc comment: `/** Migration-time writer. Runs before [DatabaseWriteActor] exists; direct SQL writes are intentional and safe here. */`
2. Annotate `UuidMigration` at class level (or its internal migration functions) with `@DirectSqlWrite` for the same reason.
3. Replace `db.steleDatabaseQueries.*` calls in both classes with `RestrictedDatabaseQueries` calls. Class-level `@DirectSqlWrite` satisfies the opt-in requirement for all methods in the class.

**Note**: `@DirectSqlWrite` on the class means callers of `ChangelogRepository.markRunning()` do NOT need to opt in — the annotation is on the implementation, not the interface method signatures. This is intentional: callers of migration infrastructure are already inside the migration framework and understand the contract.

**Validation**: `./gradlew jvmTest` — migration tests in `MigrationTestHarness.kt` and `ChangeApplierTest.kt` pass.

---

### Task 3.3 — Remove `@DirectRepositoryWrite`

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DirectRepositoryWrite.kt` — delete
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt` — remove `@OptIn(DirectRepositoryWrite::class)` at class level and any method-level usages
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt` — remove `@OptIn(DirectRepositoryWrite::class)` at class level; remove import
- Any repository interface method that carries `@DirectRepositoryWrite` — remove the annotation from the interface methods

**Rationale**: `@DirectRepositoryWrite` guards repository interface methods but cannot guard the SQL layer. Now that `@DirectSqlWrite` guards the SQL layer (which is the actual enforcement boundary), the repository-level annotation is redundant and should be removed to avoid dual annotation systems with different scopes.

**Before deleting**, verify no other files reference `DirectRepositoryWrite`:
```
Grep: "DirectRepositoryWrite" in kmp/src/**/*.kt
```
All references should be in the four files above only.

**Validation**: `./gradlew jvmTest && ./gradlew testDebugUnitTest` — full test suite passes on both JVM and Android.

---

## Known Issues / Gotchas

### 1. `RestrictedDatabaseQueries.transaction {}` signature mismatch on KMP targets

The SQLDelight `Transacter.transaction {}` has two overloads: one returning `Unit` and one `TransactionWithReturn<T>`. Both must be wrapped. The `TransactionWithReturn` type is from `app.cash.sqldelight.Transacter` — import it explicitly; do not rely on star imports from the generated package.

On native (iOS/macOS) targets, SQLDelight uses a different driver that may enforce its own transaction tracking. The wrapper must faithfully delegate without introducing an extra transaction layer.

### 2. `@DirectSqlWrite` does not propagate into lambdas automatically

A `suspend () -> Result<Unit>` lambda passed to `writeActor.execute {}` is not automatically opted in even if the caller's function is `@OptIn`. Each lambda that calls a `@DirectSqlWrite` method must independently carry `@OptIn(DirectSqlWrite::class)` — either via an annotation on the lambda's enclosing function, or via a local `@OptIn` on the lambda itself (Kotlin 1.9+ supports `@OptIn` on function expressions). Verify this in `HistogramWriter.init`.

### 3. `RestrictedDatabaseQueries` is not a drop-in replacement for `SteleDatabaseQueries`

Code that uses `asFlow()` or `mapToList()` extension functions from `app.cash.sqldelight.coroutines` calls those functions on the `Query<T>` object returned by a query method. The wrapper returns the same `Query<T>` type, so the extension functions continue to work. No change to read-side flow collection is required.

### 4. Wrapper maintenance: new `.sq` queries

Every time a new mutating query is added to `SteleDatabase.sq`, a corresponding annotated stub must be added to `RestrictedDatabaseQueries`. This is a process risk. Mitigate by:
- Adding a `// TODO: add to RestrictedDatabaseQueries` comment convention in `.sq` for new mutations.
- Adding a unit test (or a `@Suppress`-less compilation check) that verifies `RestrictedDatabaseQueries` covers all mutation queries by name. This can be done via reflection in a JVM test, or documented in `CLAUDE.md`.

### 5. `OperationLogger` test construction

After Story 2, `OperationLogger(db, sessionId)` in tests becomes `OperationLogger(RestrictedDatabaseQueries(db.steleDatabaseQueries), sessionId)`. Both `MigrationTestHarness.kt` and `ChangeApplierTest.kt` must be updated. Missing this will produce a compilation failure in `jvmTest` that is easy to diagnose but easy to overlook during a mechanical migration.

### 6. `HistogramWriter` has dual write paths

`HistogramWriter` has `if (writeActor != null) writeActor.execute(writeOp) else writeOp()`. The fallback `writeOp()` path (no actor) is used in some test or lightweight contexts. Both paths call `RestrictedDatabaseQueries` mutations inside the `writeOp` lambda. The `@OptIn` must be on the lambda or the enclosing scope — not on the `HistogramWriter` class — because the class-level opt-in would allow arbitrary future methods to call mutations without an actor, defeating the purpose.

### 7. `SqlDelightBlockRepository` already opts in at class level

`SqlDelightBlockRepository` carries `@OptIn(DirectRepositoryWrite::class)` at class level. After removing `@DirectRepositoryWrite`, this annotation is deleted. The class still calls `database.steleDatabaseQueries.*` for reads. These read calls do not require opt-in on `RestrictedDatabaseQueries`, so no additional annotation is needed. The write calls from `SqlDelightBlockRepository` (e.g., `insertBlock`, `deleteBlocksByPageUuid`) will now produce errors unless the class is given a `RestrictedDatabaseQueries` and opts in at method level on the methods that perform writes. Audit each write-calling method in `SqlDelightBlockRepository` as part of Story 3 cleanup.
