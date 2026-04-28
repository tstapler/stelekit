# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SteleKit is a Kotlin Multiplatform (KMP) migration of Logseq — a Markdown-based outliner/note-taking app. It targets Desktop (JVM), Android, iOS, and Web from a single shared codebase in the `kmp/` module.

## Build & Run Commands

```bash
# Run desktop app
./gradlew run

# Run all tests
./gradlew allTests

# Run JVM (desktop/shared) tests only
./gradlew jvmTest

# Run a single test class
./gradlew jvmTest --tests "dev.stapler.stelekit.SomeTest"

# Run Android tests
./gradlew testDebugUnitTest

# Build and install on connected Android device
./gradlew installAndroid

# Package desktop distributable
./gradlew packageDistributionForCurrentOS

# Run all CI checks locally (detekt + jvmTest + Android unit tests + assembleDebug)
./gradlew ciCheck
# If xvfb-run is installed (headless Linux / SSH), it is used automatically:
# command -v xvfb-run >/dev/null && xvfb-run --auto-servernum ./gradlew ciCheck || ./gradlew ciCheck
# README sync is not covered by ciCheck — run separately:
# bash scripts/generate-readme.sh && git diff --exit-code README.md

# Run benchmark locally — mirrors CI, generates flamegraph PNGs (requires async-profiler + librsvg)
./scripts/benchmark-local.sh                        # synthetic graph only
./scripts/benchmark-local.sh /path/to/your/graph   # include real-graph test

# Or run the Gradle task directly (flamegraph PNGs require flamegraph.pl + rsvg-convert separately)
./gradlew :kmp:jvmTestProfile -PgraphPath=/path/to/your/graph
# Outputs to kmp/build/reports/:
#   graph-load.jfr              — raw JFR recording (alloc events)
#   graph-load-wall.jfr         — async-profiler wall-clock recording (all thread states)
#   graph-load-alloc.collapsed  — allocation stacks (collapsed, flamegraph-ready)
#   graph-load-cpu.collapsed    — wall-clock stacks filtered to DefaultDispatcher-worker-*
#                                 (Kotlin coroutine pool, Gradle/Kryo noise excluded)
#                                 Falls back to JFR CPU samples if async-profiler not found.
#   flamegraph.html             — interactive allocation flamegraph
#
# Wall-clock mode (macOS): brew install async-profiler
# Wall-clock mode (Linux): place async-profiler-4.4-linux-x64/ in repo root, or set AP_LIB=
# CI uploads flamegraph-alloc.png and flamegraph-cpu.png as individual artifacts
# viewable directly in the browser (no download required).
```

## Module Structure

All shared code lives in `kmp/src/`:

| Source Set | Purpose |
|------------|---------|
| `commonMain` | Platform-agnostic UI, domain, repository, DB, parser |
| `jvmMain` | Desktop entry point, file watching, JVM logging |
| `androidMain` | Android entry point, driver factory |
| `iosMain` | iOS driver |
| `jsMain` | Web (enabled via `gradle.properties` `enableJs=true`) |
| `jvmTest` / `commonTest` / `businessTest` | Tests |

## Architecture

SteleKit follows a layered architecture inside `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`:

```
UI (Compose)       → ui/ (App.kt, screens/, components/)
ViewModel          → ui/StelekitViewModel.kt, ui/LogseqViewModel.kt
Repository         → repository/ (Page, Block, Search, Journal)
Database/Files     → db/ (GraphManager, GraphLoader, GraphWriter)
Domain Models      → model/
Parser             → parser/ + outliner/
Platform abstracts → platform/
```

### Key Data Flow

1. **Startup**: `StelekitApp` → `GraphManager.addGraph(path)` creates per-graph `RepositorySet` (PageRepository, BlockRepository, SearchRepository)
2. **Page load**: `StelekitViewModel.navigateTo()` → `GraphLoader` reads markdown → `OutlinerPipeline` builds block tree → saved to repositories
3. **Editing**: `BlockEditor` → `BlockStateManager` (local state) → debounced 500ms → `GraphWriter.saveBlock()` writes to disk
4. **External changes**: `GraphLoader.externalFileChanges` (SharedFlow) detects disk writes → emits `DiskConflict` → user resolves in UI

### Multi-Graph Support

`GraphManager` maintains multiple isolated graphs simultaneously. Each graph has its own `RepositorySet` and `CoroutineScope`. Repository backends: `IN_MEMORY` (tests), `SQLDELIGHT` (production).

### State Management

- `StelekitViewModel`: central `StateFlow`-based state (navigation, open page, search)
- `AppState`: global UI flags (sidebar, search dialog, command palette)
- `BlockStateManager`: isolated block editing state per block

### Error handling — Arrow `Either`

All repository and service methods use [Arrow](https://arrow-kt.io)'s `Either<DomainError, T>` for error-returning operations. **Do not use `Result<T>`, nullable returns, or thrown exceptions for domain errors at repository boundaries.**

```kotlin
// Return success
return Unit.right()
return page.right()

// Return failure
return DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()

// Consume at call site
result.onLeft { e -> logger.error("failed: ${e.message}") }
result.getOrNull()           // null on failure
result.fold({ err -> … }, { value -> … })
```

Rules:
- **Repository interfaces** return `Either<DomainError, T>` for `suspend fun` and `Flow<Either<DomainError, T>>` for reactive queries.
- **Never** let SQLite exceptions propagate raw — wrap in `DomainError.DatabaseError.WriteFailed`.
- **`getOrNull()`** is fine for internal callers that treat failure as absence. Use `.fold` or `.onLeft` when the error needs to be surfaced.
- Arrow is already on the classpath via `commonMain`; import `arrow.core.Either`, `arrow.core.left`, `arrow.core.right`.

### Database

SQLDelight 2.3.2 generates type-safe Kotlin from `.sq` files in `kmp/src/commonMain/sqldelight/`. Schema in `SteleDatabase.sq`.

#### Write enforcement — `@DirectSqlWrite`

**Never call mutating methods (`insert*`, `update*`, `delete*`, `upsert*`, `transaction`) directly on `SteleDatabaseQueries`.** All writes are gated behind `@DirectSqlWrite` on `RestrictedDatabaseQueries` (`db/RestrictedDatabaseQueries.kt`).

Rules:
- Route writes through `DatabaseWriteActor` (preferred) — use `actor.execute { ... }` or the typed methods (`saveBlock`, `savePage`, etc.).
- If a helper class needs to write inside an actor lambda, inject `RestrictedDatabaseQueries` and annotate the private write function `@OptIn(DirectSqlWrite::class)`.
- Migration-time writers (`MigrationRunner`, `UuidMigration`) run before the actor exists and may carry `@OptIn(DirectSqlWrite::class)` at class level — this is the only approved class-level opt-in outside the actor.
- When you add a new query to `SteleDatabase.sq` that performs INSERT/UPDATE/DELETE/UPSERT, add a corresponding forwarding stub to `RestrictedDatabaseQueries` annotated `@DirectSqlWrite`.

### Coroutine dispatcher and database connection pool

The database layer uses `PlatformDispatcher.DB` for all SQL work and `DatabaseWriteActor` to serialize writes. Follow these rules when adding or modifying repository code.

#### Dispatcher matrix

| Context | Dispatcher | Mechanism |
|---|---|---|
| Read `Flow` (SQLDelight generated) | `PlatformDispatcher.DB` | `mapToList(DB)` / `mapToOneOrNull(DB)` |
| Read `flow { }` block with raw SQL | `PlatformDispatcher.DB` | `.flowOn(DB)` at end of chain |
| Write `suspend fun` | `PlatformDispatcher.DB` | `withContext(DB) { ... }` |
| `DatabaseWriteActor` internal scope | `PlatformDispatcher.Default` | Owns its own `CoroutineScope` |
| Non-database IO (files, network) | `PlatformDispatcher.IO` | Direct or `withContext(IO)` |

#### Read pattern — use in every `SqlDelight*Repository`

```kotlin
// Generated query → reactive Flow
override fun getPageByUuid(uuid: String): Flow<Result<Page?>> =
    queries.selectPageByUuid(uuid)
        .asFlow()
        .mapToOneOrNull(PlatformDispatcher.DB)      // ← always DB, never IO
        .map { success(it?.toModel()) }

// Custom flow with raw SQL calls
override fun getBlockHierarchy(rootUuid: String): Flow<Result<List<BlockWithDepth>>> = flow {
    // Synchronous SQL here — flowOn switches the whole upstream to DB dispatcher
    emit(success(queries.selectBlockByUuid(rootUuid)?.let { ... } ?: emptyList()))
}.flowOn(PlatformDispatcher.DB)                     // ← always at the end of the chain
```

#### Write pattern — use in every `SqlDelight*Repository`

```kotlin
override suspend fun savePage(page: Page): Result<Unit> = withContext(PlatformDispatcher.DB) {
    try {
        queries.transaction { queries.insertPage(...); queries.updatePage(...) }
        success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

All write calls reach the repository through `DatabaseWriteActor`, which serializes them to a single coroutine. The `withContext(PlatformDispatcher.DB)` inside the repository ensures the write always executes on a pooled DB connection thread, regardless of which thread the actor is currently running on.

#### Platform mapping for `PlatformDispatcher.DB`

| Platform | Value | Rationale |
|---|---|---|
| JVM | `Dispatchers.IO` | `PooledJdbcSqliteDriver` pre-creates 8 connections at startup — unlimited read concurrency; pool bounds total connection count |
| Android | `Dispatchers.IO` | Android SQLite driver manages its own native connection pool |
| iOS | `Dispatchers.Default` | Native driver, GCD handles threading |
| WASM/JS | `Dispatchers.Default` | Single-threaded runtime |

#### Never do

```kotlin
// ✗ Hardcode Dispatchers.IO — wrong on iOS/WASM and bypasses pool abstraction
.mapToList(Dispatchers.IO)

// ✗ Use PlatformDispatcher.DB for non-database IO
withContext(PlatformDispatcher.DB) { File("...").readText() }

// ✗ Use PlatformDispatcher.IO for database reads — bypasses pool abstraction on JVM
.mapToList(PlatformDispatcher.IO)

// ✗ Write SQL without withContext(DB) — inconsistent thread safety across platforms
override suspend fun addReference(...): Result<Unit> {
    queries.insertBlockReference(...)  // missing withContext
}

// ✗ Pass rememberCoroutineScope() into any class that touches the DB
val scope = rememberCoroutineScope()
val actor = remember { DatabaseWriteActor(repo, scope) }  // scope cancelled on recomposition
```

### Coroutine scope ownership — `rememberCoroutineScope` must not escape composition

**Never pass a `rememberCoroutineScope()` result to a class that outlives the composable.** Compose cancels `rememberCoroutineScope` scopes when the composable leaves the composition; any object still holding that scope will throw `ForgottenCoroutineScopeException` on its next `launch`.

Rules:
- Any class instantiated inside `remember { }` must own its `CoroutineScope` internally: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- `rememberCoroutineScope()` is for transient UI work only — event handlers, one-shot animations, button callbacks — never for objects stored in `remember`.
- Long-lived classes expose results as `StateFlow`/`Flow`; composables collect them with `collectAsState()`. They do not accept a caller-supplied scope.
- To audit: grep for `remember.*scope` or `scope.*remember` and confirm no `rememberCoroutineScope()` value is flowing into a constructor stored by `remember { }`.

Violation pattern (forbidden):
```kotlin
val scope = rememberCoroutineScope()
val manager = remember { SomeManager(scope) }  // scope will be cancelled on recomposition
```

Correct pattern:
```kotlin
// SomeManager.kt
class SomeManager(...) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
// Composable
val manager = remember { SomeManager() }
```

## Testing Infrastructure

See `kmp/TESTING_README.md` for the full testing guide. Test source sets:
- `commonTest` — shared utilities
- `businessTest` — business logic without UI
- `jvmTest` — JVM UI + integration tests (uses Roborazzi for screenshot tests)
- `androidUnitTest` — Android local unit tests

## Key Files

| File | Role |
|------|------|
| `kmp/build.gradle.kts` | All dependencies, targets, SQLDelight config |
| `kmp/src/commonMain/.../ui/App.kt` | Root Compose composable, screen routing |
| `kmp/src/commonMain/.../ui/AppState.kt` | Global app state model |
| `kmp/src/commonMain/.../db/GraphManager.kt` | Multi-graph lifecycle |
| `kmp/src/commonMain/.../db/GraphLoader.kt` | File import and markdown parsing |
| `kmp/src/commonMain/.../db/GraphWriter.kt` | File export and conflict detection |
| `kmp/src/commonMain/.../model/Models.kt` | Page, Block, Property data classes with built-in validation |
| `kmp/src/commonMain/.../repository/RepositoryFactory.kt` | Backend abstraction |
| `kmp/src/jvmMain/.../desktop/Main.kt` | Desktop entry point |
| `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq` | SQLDelight schema |
