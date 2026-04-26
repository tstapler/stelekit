# Pitfalls Research: Arrow 2.x in Kotlin Multiplatform

## Risk Register

| Pitfall | Severity | Probability | Impact | Mitigation Exists? |
|---|---|---|---|---|
| STM on WASM/JS single-threaded | Low | High (WASM is single-threaded) | Low (degrades gracefully) | Yes — by design |
| KSP + multi-target optics config | Medium | High (common KMP mistake) | Medium (build fails) | Yes — documented fix |
| Either boxing in hot Flow | Low | Medium | Low (negligible in practice) | Yes — use Raise internally |
| Resource + rememberCoroutineScope | High | Medium | High (silent data loss) | Yes — own the scope |
| Result→Either viral propagation | High | Certain | High (entire codebase) | Yes — migration order |
| Arrow + Kotlin version mismatch | Medium | Low (stay on latest) | High (won't compile) | Yes — pin versions |

---

## 1. STM on Single-Threaded Targets (WASM/JS, iOS)

### WASM/JS

WASM/JS is a single-threaded runtime. Arrow STM still compiles and runs correctly on WASM/JS. Because there is only one thread, transactions never actually conflict — every `atomically {}` block succeeds on the first attempt without retry. This is correct behavior: STM degrades gracefully to sequential execution.

**Implication for SteleKit:** The `DatabaseWriteActor` STM rewrite will work on WASM/JS. Performance may actually be slightly better than on JVM because there is no contention overhead.

**Mitigation:** None needed. STM on WASM/JS is a deliberate design in Arrow 2.x — the K2 compiler generates appropriate code per target.

### iOS (Kotlin Native)

The new Kotlin Native memory model (Kotlin 1.7.20+) allows shared mutable state across threads. Arrow STM on iOS uses atomic operations from the Kotlin Native atomics library. All Arrow 2.x releases are tested against iOS targets.

**Known issue (historical):** Early Arrow STM had issues with Kotlin Native's old memory model. This is resolved in Arrow 2.x + Kotlin Native with the new memory model.

**Mitigation:** Use Arrow 2.2.x with Kotlin 2.x — the new memory model is the default and required.

### `retry()` on single-threaded targets

`retry()` inside an STM block suspends the coroutine until a watched `TVar` changes. On single-threaded platforms, this is still safe because `atomically {}` is a `suspend fun` — the coroutine dispatcher handles the suspension. The key point: `retry()` does NOT spin-wait; it suspends and is resumed by the STM runtime when a relevant TVar is written.

---

## 2. KSP + Optics Code Generation on Multiple KMP Targets

### The Core Problem

The generic `ksp(...)` configuration is **deprecated** in KMP projects. If you use it, KSP may silently skip targets or generate code only for one target, producing "Unresolved reference: Companion" errors when optics DSL is accessed from other targets.

### Documented Fix (from Kotlin Slack + Arrow GitHub issues)

```kotlin
// build.gradle.kts — CORRECT multi-target KSP setup
dependencies {
    // Run on commonMain metadata first — generates shared .kt files
    add("kspCommonMainMetadata", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    // Each target also needs the plugin to avoid "unresolved" on its compilation
    add("kspJvm", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspAndroid", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspIosX64", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspIosArm64", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspIosSimulatorArm64", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    // wasmJs only if enableJs=true
    // add("kspWasmJs", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
}

// CRITICAL: make all compilations depend on commonMain KSP running first
tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

// Add generated sources to commonMain source set
kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}
```

### KSP2 vs KSP1

Arrow 2.2.x supports KSP2 (the new incremental KSP processor). KSP version must match the Kotlin version:
- Kotlin 2.2.x → KSP `2.2.0-1.0.29` (check https://github.com/google/ksp/releases for latest)

**Recommendation:** Use KSP2 with Arrow 2.2.x. Add `ksp { useKSP2 = true }` in `build.gradle.kts` to opt in explicitly.

### Arrow Optics Gradle Plugin (Beta alternative)

Arrow introduced a Gradle plugin in November 2025 (`io.arrow-kt.arrow-optics-gradle-plugin`) that handles all KSP configuration automatically. This eliminates the multi-target KSP setup complexity. Currently in beta — evaluate for stability before adopting.

### The `companion object` requirement

Classes annotated `@optics` must have an explicit `companion object` declaration:
```kotlin
@optics
data class Page(...) { companion object }  // REQUIRED — KSP won't generate without it
```

---

## 3. Either in Flow — Performance Implications

### Is Either an inline class?

No. `Either<L, R>` is a sealed class in Arrow 2.x. It allocates a `Right` or `Left` wrapper object per emission. `Result<T>` from Kotlin stdlib is also a value class (inline on JVM), making `Result<T>` slightly cheaper per allocation.

**Practical impact:** In a `Flow<Either<DomainError, Page>>`, each `emit()` creates one `Either.Right<Page>` object. For a flow emitting one item per user navigation (cold flow), this is negligible. For a flow emitting hundreds of items per second (e.g., bulk import events), the allocation rate may be noticeable.

### Cold vs Hot Flows

**Cold flows (repository queries):** Each `collect {}` call re-executes the flow body. Either wrapping is on the critical path per emission — acceptable.

**Hot flows (StateFlow, SharedFlow):** `StateFlow<Either<DomainError, Page>>` holds one Either reference at a time. Recomposition reads it. The extra wrapper object in a StateFlow is trivially small.

**Compose recomposition:** `collectAsState()` on a `StateFlow<Either<DomainError, PageUiState>>` triggers recomposition when the Either reference changes. Either does not implement structural equality checks differently from data classes — `Right("a") == Right("a")` is true (value equality from the data class). No extra recompositions.

### Mitigation: Use Raise internally, Either at boundaries

```kotlin
// INTERNAL: use Raise<DomainError> context — zero allocation overhead
context(Raise<DomainError>)
private suspend fun parseAndSave(content: String): Page { ... }

// BOUNDARY: convert to Either only at public API surface
suspend fun publicMethod(): Either<DomainError, Page> = either {
    parseAndSave(content)  // Raise context provided by either { }
}
```

This means the boxing overhead only occurs once per public API call, not inside hot inner loops.

---

## 4. Resource and Compose Lifecycle

### The Core Risk

`Resource<A>` must be installed in a coroutine scope that outlives the composable. If you use `rememberCoroutineScope()` — which is cancelled when the composable leaves composition — and pass it into a `Resource`, the finalizers may never run on navigation-away.

### Forbidden Pattern

```kotlin
@Composable
fun GraphScreen() {
    val scope = rememberCoroutineScope()
    val graphRes = remember { graphResources(graphId) }  // Resource not installed yet
    // BAD: scope is cancelled on navigation
    LaunchedEffect(graphId) {
        graphRes.use(scope) { ... }  // scope may cancel before release runs
    }
}
```

### Correct Pattern: ViewModel-owned scope

```kotlin
class StelekitViewModel : ViewModel() {
    // viewModelScope lives as long as the ViewModel — survives recomposition and
    // navigation, cancelled only when ViewModel is cleared
    private val graphResources: MutableStateFlow<GraphResources?> = MutableStateFlow(null)
    private var releaseGraphResources: (suspend () -> Unit)? = null

    fun openGraph(graphId: String) {
        viewModelScope.launch {
            val (res, release) = graphResourcesFor(graphId).allocated()
            releaseGraphResources?.invoke()  // close previous
            graphResources.value = res
            releaseGraphResources = release
        }
    }

    override fun onCleared() {
        viewModelScope.launch { releaseGraphResources?.invoke() }
    }
}
```

### Alternatively: Resource.use {} in a stable scope

For GraphManager, the lifecycle matches the process lifetime:

```kotlin
// In the entry point (Main.kt), not in a Composable:
fun main() = runBlocking {
    graphResources.use { resources ->
        StelekitApp(resources).run()
    }
    // finalizers run here when app exits
}
```

### Resource + CoroutineScope (from features.md)

Arrow's `Resource` finalizer always runs in the scope where `.use {}` is called, even if the resource's inner computation throws a `CancellationException`. This is the key guarantee that manual `try-finally` cannot provide when `scope.cancel()` is called from outside.

---

## 5. Migration Risks: Result<T> → Either at API Boundaries

### The Viral Nature

Changing `BlockRepository.saveBlock(block: Block): Result<Unit>` to `Either<DomainError, Unit>` requires changing:
1. All callers of `saveBlock` (currently ~15 in DatabaseWriteActor, GraphLoader, GraphWriter)
2. Their callers (StelekitViewModel, all screen ViewModels)
3. Test code that asserts on `Result`

This is a **compile-error-driven migration** — the compiler flags every site. This is actually an advantage (nothing is silently broken), but it means you cannot do a partial migration of one file without breaking others.

### Recommended Migration Strategy

**Strategy: Migrate leaf-first, then propagate upward (same PR)**

Per the requirements (NFR-2), there are no dual `Result`/`Either` overloads. Migrate complete vertical slices:

```
PR 1: DomainError hierarchy + @optics models + build.gradle (additive, no breakage)
PR 2: All repository interfaces + all implementations + all tests (atomically)
PR 3: DatabaseWriteActor STM rewrite (depends on PR 2 interfaces)
PR 4: GraphWriter STM + Saga (depends on PR 3 actor)
PR 5: GraphManager Resource lifecycle (depends on PR 4)
PR 6: GraphLoader STM + retry (depends on PR 3 actor)
PR 7: All ViewModels + UI error mapping (depends on PR 2-6)
```

Each PR must compile and pass tests independently.

### typealias to ease transition (optional)

```kotlin
// Temporary — can be deleted after migration is complete
typealias DomainResult<T> = Either<DomainError, T>
```

This allows gradual callsite updates: `fun save(): DomainResult<Unit>` reads identically during migration and can be inlined later.

### "Either hell" — avoid deeply nested flatMap

**Problematic:**
```kotlin
getPage(uuid).flatMap { page ->
    getBlocks(page.uuid).flatMap { blocks ->
        saveAll(page, blocks).flatMap { ... }
    }
}
```

**Use Raise/either {} instead:**
```kotlin
either {
    val page = getPage(uuid).bind()
    val blocks = getBlocks(page.uuid).bind()
    saveAll(page, blocks).bind()
}
```

The `either { }` builder with `.bind()` eliminates all nesting and reads like sequential code.

---

## 6. Arrow Version Compatibility Matrix

### Arrow ↔ Kotlin Version Requirements

| Arrow | Kotlin | KSP | Notes |
|---|---|---|---|
| 2.0.x | 2.0.x | 2.0.x-1.0.x | First K2 release |
| 2.1.x | 2.1.x | 2.1.x-1.0.x | Kotlin 2.1 features |
| 2.2.0 | 2.2.x | 2.2.0-1.0.x | Kotlin 2.2 features |
| 2.2.1.1 | 2.2.x / 2.3.x | 2.2.0-1.0.x | Explicit Kotlin 2.3 compat |

**Rule:** Arrow major.minor must match Kotlin major.minor. Arrow patch versions can be updated freely.

### Arrow 1.x → 2.x Breaking Changes (migration risk if any 1.x code exists)

Arrow 2.x has breaking changes vs 1.x:
- `Optional<S, A>` for nullable fields is now `Lens<S, A?>` (optics hierarchy simplified)
- `arrow.core.computations.either` is now `arrow.core.raise.either`
- `EitherEffect` removed — use `Raise<E>` directly
- `ValidatedNel` replaced by `NonEmptyList` + `Raise` accumulation

SteleKit currently has **zero Arrow dependency** — migration starts fresh from 2.x. No 1.x migration risk.

### Known Issues

1. **Kotlin 2.3 + Arrow optics KSP (GH #3842):** Arrow 2.2.1.1 was released specifically to address Kotlin 2.3 compatibility. If SteleKit upgrades Kotlin to 2.3 before Arrow releases a compatible version, KSP optics generation may fail. Mitigation: stay on Kotlin 2.2.x until Arrow explicitly supports 2.3.

2. **WASM/JS + Arrow:** Arrow 2.0 added WebAssembly support explicitly. SteleKit's WASM target (`enableJs=true`) is opt-in — Arrow will compile to WASM/JS correctly when enabled.

3. **Compose Multiplatform 1.7.x:** No known incompatibilities with Arrow 2.x. Arrow does not depend on Compose. The optic lenses generated for `AppState` and related Compose state classes are pure Kotlin.

---

## Sources

- [Arrow 2.0 Release Blog](https://arrow-kt.io/community/blog/2024/12/05/arrow-2-0/)
- [Arrow 2.2.1.1 Blog](https://arrow-kt.io/community/blog/2025/12/17/arrow-2-2-1/)
- [Arrow Migration Guide](https://arrow-kt.io/learn/quickstart/migration/)
- [Arrow GitHub Issue #2646: Optics KSP unresolved reference](https://github.com/arrow-kt/arrow/issues/2646)
- [Arrow GitHub Issue #2654: Fix Optics KSP Iso generation](https://github.com/arrow-kt/arrow/pull/2654)
- [Arrow GitHub Issue #3842: Kotlin 2.3 metadata-jvm](https://github.com/arrow-kt/arrow/issues/3842)
- [Kotlin Slack #arrow: KSP multiplatform config](https://slack-chats.kotlinlang.org/t/12093809/i-configured-arrow-with-ksp-for-my-multiplatform-project-i-j)
- [Kotlin Slack #arrow: Either vs Result](https://slack-chats.kotlinlang.org/t/10013951/what-is-the-current-take-on-kotlin-s-native-result-vs-eg-arr)
- [KSP Multiplatform Docs](https://kotlinlang.org/docs/ksp-multiplatform.html)
- [Arrow STM Docs](https://arrow-kt.io/learn/coroutines/stm/)
