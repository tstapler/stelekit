# ADR-001: @HelpPage Annotation Design and Diataxis Interface Contract

**Status**: Accepted  
**Date**: 2026-04-13  
**Deciders**: Tyler Stapler  
**Feature**: Robust Demo Graph

---

## Context

Every user-facing SteleKit feature must declare its help documentation intrinsically in code — not in an external registry. The goal is documentation that cannot be omitted without a compile or test error (analogous to Python docstrings: intrinsic to the definition).

Three candidate approaches were evaluated:

**Option A — Annotation only (`@HelpPage(page: "Block Editing")`)**
The annotation holds a raw string referencing a page filename. JVM reflection at test time scans for annotated classes and asserts `.md` files exist.

**Option B — Interface only (`interface HelpDocumented { val helpPageName: String }`)**
All feature objects implement an interface. Compile enforces presence; test-time verifies corresponding `.md` files exist.

**Option C — Annotation references a Diataxis class (`@HelpPage(docs = BlockEditorDocs::class)`)**
The annotation does not hold a string; instead it references a documentation class that must implement required Diataxis interfaces (`HowToDoc`, `ReferenceDoc`). The compiler enforces which interfaces the docs class implements. Tests verify corresponding `.md` files.

The natural attachment point for `@HelpPage` is the `sealed class Screen` in `AppState.kt`: screens are the primary user-facing surfaces in SteleKit, and `Screen` is an existing sealed class (not a composable function), making it an ideal first-class annotation target.

---

## Decision

**Adopt Option C** — annotation pointing to a Diataxis documentation class — with test-time enforcement via JVM reflection in `DemoGraphCoverageTest`.

### Design Details

```kotlin
// commonMain: docs/HelpPage.kt
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpPage(val docs: KClass<out DiataxisDoc>)

// commonMain: docs/DiataxisDoc.kt
interface DiataxisDoc

interface HowToDoc : DiataxisDoc {
    val howTo: HowToContent
}

interface ReferenceDoc : DiataxisDoc {
    val reference: ReferenceContent
}

interface TutorialDoc : DiataxisDoc {
    val tutorial: TutorialContent
}

interface ExplanationDoc : DiataxisDoc {
    val explanation: ExplanationContent
}
```

`HowToDoc` and `ReferenceDoc` are required for all user-facing features. Missing them is a compile error because the docs class simply will not compile without implementing all its declared interfaces.

```kotlin
// Example: Screen.Journals gets @HelpPage in AppState.kt
@HelpPage(docs = JournalsDocs::class)
data object Journals : Screen()

// docs class — compile error if HowToDoc or ReferenceDoc are missing
class JournalsDocs : HowToDoc, ReferenceDoc {
    override val howTo = HowToContent(
        title = "How to use Daily Notes",
        steps = listOf("...")
    )
    override val reference = ReferenceContent(
        title = "Daily Notes",
        description = "..."
    )
}
```

### Runtime Access — Factory Registry (no reflection)

`@HelpPage` captures a `KClass<out DiataxisDoc>`. Instantiation at runtime via `KClass.createInstance()` uses reflection, which is unavailable on Kotlin/Native (iOS). Therefore, docs classes are registered via a factory:

```kotlin
// commonMain: docs/FeatureDocRegistry.kt
object FeatureDocRegistry {
    private val registry = mutableMapOf<KClass<out DiataxisDoc>, () -> DiataxisDoc>()

    fun <T : DiataxisDoc> register(klass: KClass<T>, factory: () -> T) {
        registry[klass] = factory as () -> DiataxisDoc
    }

    fun get(klass: KClass<out DiataxisDoc>): DiataxisDoc? = registry[klass]?.invoke()
}
```

Registrations happen at app initialization (commonMain, no platform code needed).

### Test-Time Enforcement

`DemoGraphCoverageTest` (jvmTest) uses JVM reflection — acceptable in tests, never shipped:

1. Scan all classes annotated with `@HelpPage`.
2. For each, resolve the `docs` KClass.
3. Check which Diataxis interfaces it implements.
4. Assert corresponding `.md` files exist and are non-empty in the bundled demo graph.

CI fails with a specific message naming the missing file:
```
FAIL: JournalsDocs implements HowToDoc but demo-graph/pages/How to use Daily Notes.md is missing.
```

---

## Consequences

### Positive
- Compile enforces that docs classes implement all required Diataxis interfaces.
- Annotation on `Screen` sealed class variants is natural and clean.
- Factory registry works identically on JVM, Android, and iOS (no reflection in production).
- Test-time enforcement catches missing `.md` files before merge.
- Documentation is co-located with the feature in a reviewable diff.

### Negative
- Each new user-facing feature requires authoring a docs class AND a `.md` file — more work than a simple string annotation.
- JVM reflection in `DemoGraphCoverageTest` does not cover iOS/Android at test time; must rely on manual review for those targets.
- `FeatureDocRegistry` registrations must be kept in sync with `@HelpPage` declarations; a missed registration silently skips runtime access (though test-time still catches missing `.md` files).

### Neutral
- The `sealed class Screen` in `AppState.kt` is the initial attachment point. Other classes may be annotated as the system matures.

---

## Alternatives Rejected

**Option A (annotation with raw string)**: Rejected because a string has no compile-time enforcement — the docs class existence and Diataxis completeness cannot be checked without runtime reflection.

**Option B (interface only)**: Rejected because interfaces enforce presence at compile time but do not provide Diataxis structure. A class can implement `HelpDocumented` with an empty string `helpPageName = ""` and pass the compiler check.

**KSP code generation**: Rejected. KSP requires per-target configuration in KMP (separate `kspJvm`, `kspAndroid`, `kspIos*` declarations), and Kotlin/Native KSP support is limited. The complexity is not justified for a single use case. See ADR-003.
