# Implementation Plan: Robust Demo Graph

**Feature**: Robust Demo Graph  
**Branch**: `stelekit-robust-demo-graph`  
**Status**: Ready for implementation  
**Created**: 2026-04-13  
**Requirements**: `project_plans/robust-demo-graph/requirements.md`

---

## Epic Overview

SteleKit has no canonical example graph. This feature ships a bundled demo/help graph that simultaneously serves as:

1. A first-run user experience ("Hello World" for new users).
2. A human-readable, maintainable test fixture for the parser and block rendering pipeline.
3. Screenshot regression baselines for key rendering surfaces.
4. An in-app help reference, enforced by code annotations.

The demo graph lives in `kmp/src/commonMain/resources/demo-graph/` (Logseq-format: `pages/` + `journals/` subdirectories). It is loaded by `GraphLoader` through a new `ResourceLoader` abstraction on JVM/Desktop (and later Android/iOS). Every user-facing feature must annotate its `Screen` entry with `@HelpPage(docs = SomeDocsClass::class)`, backed by a Diataxis documentation class. CI fails if the corresponding `.md` files are missing or empty.

---

## Architecture Decision Records

- **ADR-001** (`decisions/ADR-001-help-page-annotation-design.md`): `@HelpPage` annotation points to a Diataxis docs class; `FeatureDocRegistry` provides cross-platform factory access; JVM-only reflection enforces coverage in tests.
- **ADR-002** (`decisions/ADR-002-resource-loader-abstraction.md`): `ResourceLoader` interface is separate from `PlatformFileSystem`; JVM uses `ClassLoader.getResource` + real path for integration tests; Android uses `AssetManager`.
- **ADR-003** (`decisions/ADR-003-demo-graph-validation-vs-generation.md`): Checked-in `.md` files + test-time validation; KSP code generation explicitly rejected.

---

## Story Breakdown

### Story 1: Core Infrastructure

**Goal**: Define the `ResourceLoader` abstraction, the `@HelpPage` annotation, Diataxis interfaces, and `FeatureDocRegistry`. No demo content yet; no `Screen` wiring yet. This story establishes the framework everything else depends on.

**Acceptance Criteria**:
- `ResourceLoader` interface compiles in commonMain with a JVM `expect`/`actual` implementation.
- `@HelpPage(docs = SomeClass::class)` annotation compiles on any `class` target in commonMain.
- All four Diataxis interfaces (`HowToDoc`, `ReferenceDoc`, `TutorialDoc`, `ExplanationDoc`) and their content data classes exist in commonMain.
- `FeatureDocRegistry` compiles and a unit test registers + retrieves a docs instance without reflection.
- Demo graph resource directory skeleton exists (`pages/.gitkeep`, `journals/.gitkeep`).

#### Task 1.1 — `ResourceLoader` interface + JVM implementation

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ResourceLoader.kt` (new)
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/JvmResourceLoader.kt` (new)

**What to build**:

`ResourceLoader` has two methods:
```kotlin
interface ResourceLoader {
    fun readResource(path: String): String?
    fun listResourceDirectory(path: String): List<String>
}
```

`JvmResourceLoader` uses `javaClass.classLoader.getResourceAsStream(path)` for `readResource` and walks the `File(url.toURI())` directory for `listResourceDirectory`. Both methods return `null`/empty on any exception rather than throwing.

The `expect fun platformResourceLoader(): ResourceLoader` declaration goes in commonMain; `actual` implementations go in each source set. For the initial story, only jvmMain is required. Android and iOS actuals can be stubs returning `null`/empty with a `TODO` comment.

**Estimated effort**: 2h

---

#### Task 1.2 — Diataxis interface hierarchy and content data classes

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/DiataxisDoc.kt` (new)

**What to build**:

Four interfaces inheriting `DiataxisDoc`:

```kotlin
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

Content data classes (no `@Serializable` yet — add when needed):

```kotlin
data class HowToContent(
    val title: String,
    val description: String = "",
    val steps: List<String>,
    val tips: List<String> = emptyList()
)

data class ReferenceContent(
    val title: String,
    val description: String,
    val sections: List<ReferenceSection> = emptyList()
)

data class ReferenceSection(val heading: String, val body: String)

data class TutorialContent(
    val title: String,
    val description: String,
    val steps: List<String>
)

data class ExplanationContent(
    val title: String,
    val body: String
)
```

Keep all fields minimal. The data classes are content holders, not renderers.

**Estimated effort**: 1h

---

#### Task 1.3 — `@HelpPage` annotation

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/HelpPage.kt` (new)

**What to build**:

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpPage(val docs: KClass<out DiataxisDoc>)
```

A companion object on `HelpPage` is not needed at this stage. The annotation is intentionally minimal: one field, one type. No defaults.

Also define a marker interface for the minimum Diataxis requirement:

```kotlin
/**
 * Marker that a docs class has met the minimum required Diataxis coverage.
 * All user-facing feature docs classes must implement both HowToDoc and ReferenceDoc.
 * The compiler enforces this structurally — this interface is not technically required
 * but serves as documentation of the contract.
 */
interface MinimalFeatureDoc : HowToDoc, ReferenceDoc
```

**Estimated effort**: 30min

---

#### Task 1.4 — `FeatureDocRegistry`

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/FeatureDocRegistry.kt` (new)

**What to build**:

```kotlin
object FeatureDocRegistry {
    private val registry = mutableMapOf<KClass<out DiataxisDoc>, () -> DiataxisDoc>()

    fun <T : DiataxisDoc> register(klass: KClass<T>, factory: () -> T) {
        registry[klass] = factory as () -> DiataxisDoc
    }

    fun get(klass: KClass<out DiataxisDoc>): DiataxisDoc? = registry[klass]?.invoke()

    fun registeredClasses(): Set<KClass<out DiataxisDoc>> = registry.keys.toSet()
}
```

No reflection. No global init call. Registrations are performed at app startup by calling `FeatureDocRegistry.register(...)` from a dedicated initializer in commonMain (to be wired in Story 5).

Unit test in `businessTest` or `jvmTest`: register a test docs instance, retrieve it, assert correctness.

**Estimated effort**: 1h (including test)

---

#### Task 1.5 — Demo graph directory skeleton

**Files**:
- `kmp/src/commonMain/resources/demo-graph/pages/.gitkeep` (new)
- `kmp/src/commonMain/resources/demo-graph/journals/.gitkeep` (new)
- `kmp/src/commonMain/resources/demo-graph/assets/.gitkeep` (new)

**What to build**:

Create the three subdirectories with `.gitkeep` files so git tracks the structure before content is added in Story 2. Verify that `./gradlew jvmTest` still passes (resources directory should not break any existing test).

**Estimated effort**: 15min

---

### Story 2: Demo Graph Content

**Goal**: Author all 16 pages and 5 journal entries for the demo graph. Content is human-written, Logseq-compatible Markdown. This story has no Kotlin code changes.

**Acceptance Criteria**:
- 16 `.md` files exist under `demo-graph/pages/` (see page list below).
- 5 `.md` files exist under `demo-graph/journals/` with filenames in `YYYY_MM_DD.md` format.
- All files use bullet-outliner syntax (each content line starts with `- `).
- All `[[wiki links]]` reference pages that exist within the demo graph.
- No file is empty; each page has at least 5 meaningful blocks.
- One page includes a Markdown table (even if currently rendered as plain text by the parser).
- One page includes a task marker (`- [ ] ` or `- [x] `).
- One page embeds an image reference (`![alt](../assets/stelekit-diagram.png)`).

#### Page List

| Filename | Purpose | Links to |
|----------|---------|----------|
| `Start Here.md` | Index / welcome. Lists all feature pages. | Every other page |
| `Block Editing.md` | HowTo: create, indent, reorder blocks | `Block Editor Reference.md` |
| `Block Editor Reference.md` | Reference: keyboard shortcuts, block states | `Block Editing.md` |
| `Page Linking.md` | HowTo: create wiki links, navigate by link | `Backlinks.md` |
| `Backlinks.md` | Reference: how backlinks work, example backlinks from Block Editing | `Block Editing.md`, `Page Linking.md` |
| `Properties.md` | HowTo + Reference: `key:: value` syntax, frontmatter | (standalone) |
| `Daily Notes.md` | HowTo: open today's journal, navigate dates | (standalone) |
| `Search.md` | HowTo: full-text search, keyboard shortcut | (standalone) |
| `All Pages.md` | Reference: what the All Pages view shows | (standalone) |
| `Flashcards.md` | HowTo: create a flashcard, review queue | (standalone) |
| `Markdown Formatting.md` | Reference: bold, italic, code, headings supported | (standalone) |
| `Tables.md` | Reference: GFM table syntax example (parser partial support noted) | `Markdown Formatting.md` |
| `Tasks.md` | Reference: `- [ ]` and `- [x]` syntax (parser partial support noted) | (standalone) |
| `Images.md` | HowTo: embed an image; includes `![demo](../assets/stelekit-diagram.png)` | (standalone) |
| `Keyboard Shortcuts.md` | Reference: full shortcut table | (standalone) |
| `About SteleKit.md` | Explanation: what SteleKit is, how it differs from Logseq | `Start Here.md` |

#### Journal Entries (5 files)

Use dates in the past relative to the feature branch date (2026-04-13). Use static dates — no `today`, no `SCHEDULED:` timestamps. Filename format: `YYYY_MM_DD.md`.

| Filename | Theme |
|----------|-------|
| `2026_04_08.md` | Introductory note: "First time using SteleKit" |
| `2026_04_09.md` | Using page links and backlinks |
| `2026_04_10.md` | Experimenting with properties |
| `2026_04_11.md` | Creating flashcards |
| `2026_04_12.md` | Daily review: links to multiple feature pages |

#### Image Asset

- `demo-graph/assets/stelekit-diagram.png` — a small PNG (under 50 KB) depicting a simple block hierarchy diagram. Can be generated from ASCII art or a minimal SVG-to-PNG conversion. Must be PNG (not SVG, not GIF).

#### Task 2.1 — Core pages (Start Here, Block Editing, Page Linking, Properties, Daily Notes)

**Files**: 5 `.md` files in `demo-graph/pages/`  
**Estimated effort**: 3h

---

#### Task 2.2 — Reference pages (Markdown, Tables, Tasks, Images, Shortcuts, About)

**Files**: 6 `.md` files in `demo-graph/pages/`  
**Estimated effort**: 2h

Note: `Tables.md` and `Tasks.md` must include a prose note acknowledging parser limitations:
```
- Note: Table rendering is not yet fully supported. The raw Markdown is shown below as a reference.
```

---

#### Task 2.3 — Search, All Pages, Flashcards, Backlinks, Block Editor Reference

**Files**: 5 `.md` files in `demo-graph/pages/`  
**Estimated effort**: 2h

---

#### Task 2.4 — Journal entries and image asset

**Files**: 5 `.md` files in `demo-graph/journals/`, 1 `.png` in `demo-graph/assets/`  
**Estimated effort**: 2h (1h writing, 1h generating/sourcing the PNG)

---

### Story 3: Integration Test Suite

**Goal**: `DemoGraphIntegrationTest` loads the demo graph end-to-end through `GraphLoader`. `DemoGraphCoverageTest` uses JVM reflection to assert `@HelpPage` annotations have corresponding `.md` files. Both tests must pass before any PR can merge.

**Acceptance Criteria**:
- `DemoGraphIntegrationTest` loads all pages without errors.
- No block in any page has empty content after parsing.
- All `[[wiki links]]` in the demo graph resolve to pages that exist in the loaded graph.
- `DemoGraphCoverageTest` scans for `@HelpPage` annotations on the `Screen` sealed class (and any other annotated classes added in Story 5) and asserts corresponding `.md` files exist and are non-empty.
- Both tests pass in `./gradlew jvmTest`.

#### Task 3.1 — `DemoGraphIntegrationTest`

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DemoGraphIntegrationTest.kt` (new)

**What to build**:

```kotlin
class DemoGraphIntegrationTest {

    private fun resolveDemoGraphPath(): String {
        val url = javaClass.classLoader.getResource("demo-graph")
            ?: fail("demo-graph not found in test classpath")
        return File(url.toURI()).absolutePath
    }

    @Test
    fun `all demo graph pages parse without errors`() = runBlocking {
        val path = resolveDemoGraphPath()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val searchRepo = InMemorySearchRepository()
        val fileSystem = PlatformFileSystem()
        // Register the demo graph path so whitelist allows it
        fileSystem.registerGraphRoot(path)

        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo, searchRepo)
        graphLoader.loadGraph(path) {}

        val pages = pageRepo.getAllPages().first().getOrThrow()
        assertNotEquals(0, pages.size, "Demo graph must have at least one page")
    }

    @Test
    fun `no demo graph block has empty content`() = runBlocking {
        val path = resolveDemoGraphPath()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val fileSystem = PlatformFileSystem()
        fileSystem.registerGraphRoot(path)
        GraphLoader(fileSystem, pageRepo, blockRepo, InMemorySearchRepository())
            .loadGraph(path) {}

        val pages = pageRepo.getAllPages().first().getOrThrow()
        for (page in pages) {
            val blocks = blockRepo.getBlocksForPage(page.uuid).first().getOrThrow()
            for (block in blocks) {
                assertNotEquals(
                    "",
                    block.content.trim(),
                    "Empty block on page '${page.name}' (uuid: ${block.uuid})"
                )
            }
        }
    }

    @Test
    fun `all wiki links in demo graph resolve`() = runBlocking {
        val path = resolveDemoGraphPath()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val fileSystem = PlatformFileSystem()
        fileSystem.registerGraphRoot(path)
        GraphLoader(fileSystem, pageRepo, blockRepo, InMemorySearchRepository())
            .loadGraph(path) {}

        val pages = pageRepo.getAllPages().first().getOrThrow()
        val pageNames = pages.map { it.name.lowercase() }.toSet()

        val wikiLinkPattern = Regex("\\[\\[([^\\]]+)]]")
        for (page in pages) {
            val blocks = blockRepo.getBlocksForPage(page.uuid).first().getOrThrow()
            for (block in blocks) {
                wikiLinkPattern.findAll(block.content).forEach { match ->
                    val linked = match.groupValues[1].lowercase()
                    assertContains(
                        pageNames,
                        linked,
                        "Unresolved wiki link '${match.value}' in page '${page.name}'"
                    )
                }
            }
        }
    }

    @Test
    fun `demo graph page count matches files on disk`() = runBlocking {
        val path = resolveDemoGraphPath()
        val pagesOnDisk = File("$path/pages").listFiles()
            ?.filter { it.extension == "md" }?.size ?: 0
        val journalsOnDisk = File("$path/journals").listFiles()
            ?.filter { it.extension == "md" }?.size ?: 0

        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val fileSystem = PlatformFileSystem()
        fileSystem.registerGraphRoot(path)
        GraphLoader(fileSystem, pageRepo, blockRepo, InMemorySearchRepository())
            .loadGraph(path) {}

        val loaded = pageRepo.getAllPages().first().getOrThrow().size
        assertEquals(pagesOnDisk + journalsOnDisk, loaded,
            "Loaded page count does not match files on disk")
    }
}
```

**Note**: Check whether `PlatformFileSystem` exposes `registerGraphRoot` publicly or whether the whitelist is bypassed differently in existing tests. If tests use a different pattern, match it.

**Estimated effort**: 2h

---

#### Task 3.2 — `DemoGraphCoverageTest`

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt` (new)

**What to build**:

Uses JVM reflection to find all classes in `dev.stapler.stelekit` annotated with `@HelpPage`, resolves the docs class, checks Diataxis interface implementations, and asserts corresponding `.md` files in the demo graph.

```kotlin
class DemoGraphCoverageTest {

    private val demoGraphPagesPath: String by lazy {
        val url = javaClass.classLoader.getResource("demo-graph/pages")
            ?: fail("demo-graph/pages not found in classpath")
        File(url.toURI()).absolutePath
    }

    private fun pageFileExists(title: String): Boolean {
        val fileName = "$title.md"
        return File(demoGraphPagesPath, fileName).exists()
    }

    private fun pageFileNonEmpty(title: String): Boolean {
        val file = File(demoGraphPagesPath, "$title.md")
        return file.exists() && file.readText().isNotBlank()
    }

    private fun findAnnotatedClasses(): List<Pair<String, HelpPage>> {
        // Scan the Screen sealed class and its nested objects, plus any other
        // registered classes as the annotation spreads. JVM reflection only.
        val rootClass = Screen::class.java
        return (rootClass.declaredClasses.toList() + listOf(rootClass))
            .mapNotNull { clazz ->
                val annotation = clazz.getAnnotation(HelpPage::class.java)
                if (annotation != null) clazz.simpleName to annotation else null
            }
    }

    @Test
    fun `all HelpPage annotated classes have required markdown files`() {
        val annotated = findAnnotatedClasses()
        assertTrue(annotated.isNotEmpty(),
            "No @HelpPage annotations found — did Story 5 wire them onto Screen?")

        val failures = mutableListOf<String>()

        for ((className, annotation) in annotated) {
            val docsClass = annotation.docs
            val docsInstance = docsClass.java.getDeclaredConstructor().newInstance()

            if (docsInstance is HowToDoc) {
                val title = docsInstance.howTo.title
                if (!pageFileExists(title)) {
                    failures += "[$className] HowToDoc: missing demo-graph/pages/$title.md"
                } else if (!pageFileNonEmpty(title)) {
                    failures += "[$className] HowToDoc: demo-graph/pages/$title.md is empty"
                }
            }

            if (docsInstance is ReferenceDoc) {
                val title = docsInstance.reference.title
                if (!pageFileExists(title)) {
                    failures += "[$className] ReferenceDoc: missing demo-graph/pages/$title.md"
                } else if (!pageFileNonEmpty(title)) {
                    failures += "[$className] ReferenceDoc: demo-graph/pages/$title.md is empty"
                }
            }
        }

        assertTrue(failures.isEmpty(),
            "Demo graph coverage failures:\n${failures.joinToString("\n")}")
    }
}
```

**Estimated effort**: 2h

---

### Story 4: Screenshot Baselines

**Goal**: A `DemoGraphScreenshotTest` (Roborazzi) captures pixel-stable baselines for 4 key rendering surfaces. Uses a separate static test graph (not the production demo graph) to avoid date-flakiness in journal rendering. Baselines are committed to the repo.

**Acceptance Criteria**:
- Static test graph exists under `kmp/src/jvmTest/resources/screenshot-test-graph/` with fixed dates and no dynamic content.
- Roborazzi baselines committed for: Welcome page (light), Welcome page (dark), a journal list (light), a properties page (light).
- `./gradlew jvmTest` passes with baseline comparison enabled.
- `./gradlew recordRoborazziJvm` regenerates baselines when content changes.

**Why a separate static test graph**: The production demo graph may evolve (pages added, content edited). Screenshot test baselines break whenever content changes. The static test graph is frozen — it only changes when someone deliberately updates the baseline. This matches the pattern already used by `JournalsViewScreenshotTest.kt` (hardcoded dates).

#### Task 4.1 — Static test graph content

**Files**:
- `kmp/src/jvmTest/resources/screenshot-test-graph/pages/Welcome.md`
- `kmp/src/jvmTest/resources/screenshot-test-graph/pages/Properties Example.md`
- `kmp/src/jvmTest/resources/screenshot-test-graph/pages/Linked Page.md`
- `kmp/src/jvmTest/resources/screenshot-test-graph/journals/2026_01_15.md`

**Content requirements**:
- All dates are hardcoded (`2026-01-15` format, not `today`).
- No `SCHEDULED:` or `DEADLINE:` fields.
- `Welcome.md` contains at minimum: a heading block, a nested bullet list, and two `[[wiki links]]` (both to pages that exist in the test graph).
- `Properties Example.md` has at least 3 `key:: value` blocks.
- `2026_01_15.md` is a journal entry with hardcoded date content.

**Estimated effort**: 1h

---

#### Task 4.2 — `DemoGraphScreenshotTest`

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/DemoGraphScreenshotTest.kt` (new)

**What to build**:

Follow the exact pattern of `JournalsViewScreenshotTest.kt` — use `FakePageRepository` and `FakeBlockRepository` seeded by loading the static test graph. Do not load the static graph via `GraphLoader` at screenshot-test time (this avoids coupling screenshot tests to the loading pipeline, which is separately tested in Story 3).

Instead, hard-code the test blocks as Kotlin fixtures (same pattern as existing screenshot tests) but with content that mirrors the static test graph files. The static graph files serve as a human-readable source of truth; the Kotlin fixtures reproduce them for rendering.

Four test methods:
1. `demo_welcome_page_light` — renders `PageView` with Welcome page content, light theme.
2. `demo_welcome_page_dark` — same, dark theme.
3. `demo_journals_light` — renders `JournalsView` with the 2026-01-15 journal entry, light theme.
4. `demo_properties_page_light` — renders `PageView` with Properties Example content, light theme.

Each captures to `build/outputs/roborazzi/demo_<name>_<theme>.png`.

**Estimated effort**: 3h

---

#### Task 4.3 — Record and commit baselines

**What to do**:
1. Run `./gradlew recordRoborazziJvm`.
2. Review generated PNGs in `build/outputs/roborazzi/`.
3. Copy committed baseline path (check where existing baselines live — likely `kmp/src/jvmTest/roborazzi/` or adjacent).
4. Commit the 4 baseline PNG files.

**Estimated effort**: 30min

---

### Story 5: Wire @HelpPage onto Screen and Authoring Docs Classes

**Goal**: Apply `@HelpPage(docs = ...)` to each variant of `sealed class Screen` in `AppState.kt`. Create a docs class for each screen implementing `HowToDoc` and `ReferenceDoc`. Register all docs classes in `FeatureDocRegistry` at app startup.

**Acceptance Criteria**:
- Every `Screen` variant has `@HelpPage(docs = ...)`.
- Every docs class implements at minimum `HowToDoc` and `ReferenceDoc`.
- `FeatureDocRegistry` contains registrations for all docs classes, called from a single init point in `commonMain`.
- `DemoGraphCoverageTest` passes: all docs classes have corresponding `.md` files in the demo graph (files were created in Story 2).
- `DemoGraphIntegrationTest` still passes (no regressions from annotation wiring).

#### Task 5.1 — Docs classes for all 7 Screen variants

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/JournalsDocs.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/FlashcardsDocs.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/AllPagesDocs.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/PageViewDocs.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/NotificationsDocs.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/LogsDocs.kt` (new, may use `ExplanationDoc` only — Logs is not a user feature)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/PerformanceDocs.kt` (new)

Each implements `HowToDoc` and `ReferenceDoc` at minimum. `howTo.title` and `reference.title` must exactly match the filenames in `demo-graph/pages/` (without `.md` extension).

**`HowToContent.title` → filename mapping**:

| Screen | HowToContent.title | ReferenceContent.title |
|--------|--------------------|------------------------|
| `Journals` | `"How to use Daily Notes"` | `"Daily Notes"` |
| `Flashcards` | `"How to use Flashcards"` | `"Flashcards"` |
| `AllPages` | `"How to browse All Pages"` | `"All Pages"` |
| `PageView` | `"How to view and edit a page"` | `"Block Editor Reference"` |
| `Notifications` | `"How to use Notifications"` | (may omit ReferenceDoc if no configurable surface) |
| `Logs` | (may omit HowToDoc — Logs is a debug tool) | `"Logs"` |
| `Performance` | (may omit HowToDoc — Performance is a debug tool) | `"Performance"` |

Note: `Notifications`, `Logs`, and `Performance` are internal/debug screens. A simpler docs class is acceptable. Adjust the Diataxis interface requirement if needed (but document the exception in the class Kdoc).

**Estimated effort**: 3h

---

#### Task 5.2 — Apply `@HelpPage` to `Screen` sealed class

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (modify)

**What to build**:

```kotlin
@HelpPage(docs = JournalsDocs::class)
data object Journals : Screen()

@HelpPage(docs = FlashcardsDocs::class)
data object Flashcards : Screen()

@HelpPage(docs = AllPagesDocs::class)
data object AllPages : Screen()
// ... etc.
```

Import `dev.stapler.stelekit.docs.HelpPage` and each docs class. No other changes to `AppState.kt`.

**Estimated effort**: 30min

---

#### Task 5.3 — `FeatureDocRegistry` initializer

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/FeatureDocRegistryInit.kt` (new)

**What to build**:

A top-level function (not an object) called at app startup:

```kotlin
fun initFeatureDocRegistry() {
    FeatureDocRegistry.register(JournalsDocs::class) { JournalsDocs() }
    FeatureDocRegistry.register(FlashcardsDocs::class) { FlashcardsDocs() }
    FeatureDocRegistry.register(AllPagesDocs::class) { AllPagesDocs() }
    FeatureDocRegistry.register(PageViewDocs::class) { PageViewDocs() }
    FeatureDocRegistry.register(NotificationsDocs::class) { NotificationsDocs() }
    FeatureDocRegistry.register(LogsDocs::class) { LogsDocs() }
    FeatureDocRegistry.register(PerformanceDocs::class) { PerformanceDocs() }
}
```

Call `initFeatureDocRegistry()` from the desktop entry point (`jvmMain/desktop/Main.kt`) and the Android `Application.onCreate()`. No changes to iOS entry point in this story (can be done when iOS is a CI target).

**Estimated effort**: 1h

---

## Dependency Graph

```
Story 1 (Infrastructure)
  ├── Task 1.1 ResourceLoader
  ├── Task 1.2 Diataxis interfaces          ← required by Task 1.3
  ├── Task 1.3 @HelpPage annotation         ← required by Story 5
  ├── Task 1.4 FeatureDocRegistry           ← required by Story 5
  └── Task 1.5 Demo graph skeleton          ← required by Story 2

Story 2 (Content)                           ← requires Task 1.5
  ├── Task 2.1 Core pages
  ├── Task 2.2 Reference pages
  ├── Task 2.3 Remaining feature pages
  └── Task 2.4 Journals + image asset

Story 3 (Integration Tests)                 ← requires Story 2 complete
  ├── Task 3.1 DemoGraphIntegrationTest     ← requires Task 1.1 (ResourceLoader path)
  └── Task 3.2 DemoGraphCoverageTest        ← requires Task 1.3 (@HelpPage) + Story 5

Story 4 (Screenshot Baselines)             ← requires Story 2 content (for inspiration)
  ├── Task 4.1 Static test graph
  ├── Task 4.2 DemoGraphScreenshotTest
  └── Task 4.3 Record baselines

Story 5 (Wire @HelpPage)                   ← requires Tasks 1.2, 1.3, 1.4 + Story 2 pages exist
  ├── Task 5.1 Docs classes
  ├── Task 5.2 Annotate Screen
  └── Task 5.3 Registry initializer
       └── DemoGraphCoverageTest (Task 3.2) becomes meaningful only after Task 5.2
```

**Recommended implementation order**:
Story 1 → Story 2 → Story 4 (can overlap with Story 3) → Story 3 → Story 5

Stories 3 and 4 can be developed in parallel after Story 2. Story 5 depends on Story 1 (annotation + registry) but its coverage test (Task 3.2) is only meaningful after Story 5 wires the annotations.

---

## Integration Checkpoints

### Checkpoint A: After Story 1
- `./gradlew jvmTest` passes (no new failures).
- `FeatureDocRegistry` unit test passes.
- `demo-graph/` directory structure is visible in the classpath (verify with a quick `ClassLoader.getResource("demo-graph")` test).

### Checkpoint B: After Story 2
- Manually open the desktop app and add the demo graph: `File > Add Graph` pointing to `kmp/src/commonMain/resources/demo-graph/` (use the filesystem path for now).
- Verify all 16 pages appear in the sidebar.
- Verify journal dates appear in the Journals view.
- Verify at least one `[[wiki link]]` navigates correctly.

### Checkpoint C: After Story 3
- `./gradlew jvmTest` passes including `DemoGraphIntegrationTest`.
- Review the test output log — verify page count and zero-empty-block assertions print correctly.

### Checkpoint D: After Story 4
- `./gradlew recordRoborazziJvm` generates 4 baseline PNGs.
- Review each PNG manually: confirm no rendering artifacts, correct theme, readable content.
- Commit baselines. Run `./gradlew jvmTest` again to confirm comparison mode passes.

### Checkpoint E: After Story 5
- `./gradlew jvmTest` passes including `DemoGraphCoverageTest`.
- Check the coverage test output: all 7 Screen variants should be reported.
- Run the desktop app and verify the app starts without errors from `initFeatureDocRegistry()`.

---

## Known Issues and Proactive Bug Identification

### Parser Gap: GFM Tables Not Fully Rendered [SEVERITY: Medium]

**Description**: `Tables.md` uses GFM table syntax (`| col | col |`). The current parser does not fully render tables — they will appear as plain text blocks. The integration test `no demo graph block has empty content` may inadvertently pass (the raw Markdown text is non-empty) even though the table is not rendered correctly.

**Mitigation**:
- Add a prose note in `Tables.md` acknowledging the limitation (per requirements).
- Do not add an assertion in `DemoGraphIntegrationTest` that validates table rendering — leave that for a future parser feature story.
- When the parser gains table support, the integration test should be updated to assert table-typed blocks appear for `Tables.md`.

**Files affected**: `demo-graph/pages/Tables.md`, `DemoGraphIntegrationTest.kt`

---

### Parser Gap: Task Markers Not Fully Supported [SEVERITY: Low]

**Description**: `Tasks.md` uses `- [ ]` and `- [x]` syntax. The parser may or may not produce a distinct block type for these — behavior is currently unspecified. The integration test may pass (content is non-empty), but the rendered UI may show raw `[ ]` characters instead of checkboxes.

**Mitigation**:
- Add a note in `Tasks.md` acknowledging partial support.
- Do not assert task-specific block types in the integration test until parser support is confirmed.

**Files affected**: `demo-graph/pages/Tasks.md`

---

### Roborazzi Date Flakiness [SEVERITY: Medium]

**Description**: If any screenshot test renders a `JournalsView` using the production demo graph (which has dated journal files), the Journals header/date display may differ between environments due to locale formatting or timezone differences.

**Mitigation**:
- The static test graph (Task 4.1) uses `2026_01_15.md` only. The `JournalsView` screenshot test must use the static test graph, not the production demo graph.
- All dates in static test graph journal files are hardcoded strings, not computed from `LocalDate.now()`.
- If the `JournalsView` composable ever computes "today" relative dates (e.g., "yesterday"), the screenshot test must mock the clock. Check `JournalsViewScreenshotTest.kt` for existing precedent.

**Files affected**: `DemoGraphScreenshotTest.kt`, static test graph journal files

---

### Android AssetManager Context Init Timing [SEVERITY: Medium]

**Description**: `AndroidResourceLoader` requires an initialized `Context` to call `context.assets.open(...)`. If `initFeatureDocRegistry()` or any other demo graph loading is attempted before `Application.onCreate()` completes (e.g., during static initializer of a companion object), `context` will be null and the load will silently return null.

**Mitigation**:
- `initFeatureDocRegistry()` does not touch `ResourceLoader` — it only registers factory lambdas. No Android risk in Story 5.
- `AndroidResourceLoader` construction (not registration) must happen inside `Application.onCreate()` or later. Document this in `AndroidResourceLoader.kt` Kdoc.
- Add a null-guard in `AndroidResourceLoader.readResource()`: if context is somehow null, log an error and return null (do not throw).
- Android integration test (out of scope for this feature, but planned): verify `AndroidResourceLoader` loads `demo-graph/pages/Start Here.md` successfully from instrumented test.

**Files affected**: `AndroidResourceLoader.kt`, Android `Application` class

---

### `PlatformFileSystem` Whitelist: Classpath Path Not Registered [SEVERITY: High]

**Description**: `JvmFileSystemBase.validatePath()` blocks any path not in the whitelist. The classpath path returned by `ClassLoader.getResource("demo-graph")` (e.g., `/home/user/.gradle/caches/.../demo-graph`) is not in the whitelist by default. `GraphLoader` will fail to read any files.

**Mitigation**:
- In `DemoGraphIntegrationTest`, call `fileSystem.registerGraphRoot(path)` immediately after resolving the classpath path. Verify that `registerGraphRoot` is a public method on the JVM `PlatformFileSystem`.
- If `registerGraphRoot` is not public or does not exist, use a `FakeFileSystem` that pre-loads demo graph content from `ClassLoader.getResourceAsStream()`. This is a fallback and is less desirable than using the real `PlatformFileSystem`.
- Document the whitelist register step prominently in the test — future authors must not forget it.

**Files affected**: `DemoGraphIntegrationTest.kt`, `JvmFileSystemBase.kt`

---

### Wiki Link Case Sensitivity in Validation [SEVERITY: Low]

**Description**: `DemoGraphIntegrationTest` validates wiki links by lowercasing both the link target and the page names. If the parser preserves link casing and the page resolution in `GraphLoader` is case-insensitive, the test may produce false positives (unresolved links pass because lowercasing masks mismatches).

**Mitigation**:
- Match the actual page resolution strategy used by `GraphLoader`. Check `GraphLoader` source to confirm whether page lookup is case-sensitive.
- If case-insensitive: lowercase comparison in tests is correct.
- If case-sensitive: use exact string comparison and require demo graph wiki links to match page filenames exactly.

**Files affected**: `DemoGraphIntegrationTest.kt`

---

### `DemoGraphCoverageTest` Reflection Scope: Only Scans `Screen` [SEVERITY: Low]

**Description**: `DemoGraphCoverageTest.findAnnotatedClasses()` scans `Screen` and its nested objects. If `@HelpPage` is later applied to other classes outside `Screen`, they will not be discovered automatically. The test will silently miss them.

**Mitigation**:
- Document in `DemoGraphCoverageTest.kt` that the scan is limited to `Screen` and must be extended when `@HelpPage` is applied to other top-level classes.
- A future enhancement: use a classpath scanner library (e.g., `ClassGraph`) to scan all classes in the `dev.stapler.stelekit` package. This is acceptable in jvmTest and would make the scan exhaustive. Defer until needed.

**Files affected**: `DemoGraphCoverageTest.kt`

---

### Demo Graph Drift as Parser Evolves [SEVERITY: High — Long-term]

**Description**: If `MarkdownParser`, `PropertiesParser`, or their Jetbrains Markdown library dependency changes in a way that breaks block parsing, the demo graph pages may silently produce zero blocks or malformed content. This is not caught by existing tests until `DemoGraphIntegrationTest` exists.

**Mitigation**:
- `DemoGraphIntegrationTest` is the primary guard: it will fail on any CI run where a parser change breaks demo graph loading.
- Parser library version (`org.jetbrains:markdown:0.7.3`) should be pinned in `gradle/libs.versions.toml` and upgraded only with an explicit test of the demo graph.
- When a parser upgrade is planned: run `DemoGraphIntegrationTest` locally against the new version before committing the version bump.

**Files affected**: `DemoGraphIntegrationTest.kt`, `kmp/build.gradle.kts`

---

## Task Summary

| Story | Task | Effort | New Files |
|-------|------|--------|-----------|
| 1 | 1.1 ResourceLoader | 2h | 2 |
| 1 | 1.2 Diataxis interfaces | 1h | 1 |
| 1 | 1.3 @HelpPage annotation | 0.5h | 1 |
| 1 | 1.4 FeatureDocRegistry | 1h | 1 |
| 1 | 1.5 Demo graph skeleton | 0.25h | 3 |
| 2 | 2.1 Core pages | 3h | 5 |
| 2 | 2.2 Reference pages | 2h | 6 |
| 2 | 2.3 Feature pages | 2h | 5 |
| 2 | 2.4 Journals + asset | 2h | 6 |
| 3 | 3.1 DemoGraphIntegrationTest | 2h | 1 |
| 3 | 3.2 DemoGraphCoverageTest | 2h | 1 |
| 4 | 4.1 Static test graph | 1h | 4 |
| 4 | 4.2 DemoGraphScreenshotTest | 3h | 1 |
| 4 | 4.3 Record baselines | 0.5h | 4 (PNG) |
| 5 | 5.1 Docs classes | 3h | 7 |
| 5 | 5.2 Annotate Screen | 0.5h | 0 (modify AppState.kt) |
| 5 | 5.3 Registry initializer | 1h | 1 |
| **Total** | | **~27h** | **~49** |

---

## Files Created or Modified (by story)

### Story 1 (new files)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ResourceLoader.kt`
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/JvmResourceLoader.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/DiataxisDoc.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/HelpPage.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/FeatureDocRegistry.kt`
- `kmp/src/commonMain/resources/demo-graph/pages/.gitkeep`
- `kmp/src/commonMain/resources/demo-graph/journals/.gitkeep`
- `kmp/src/commonMain/resources/demo-graph/assets/.gitkeep`

### Story 2 (new files)
- `kmp/src/commonMain/resources/demo-graph/pages/Start Here.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Block Editing.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Block Editor Reference.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Page Linking.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Backlinks.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Properties.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Daily Notes.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Search.md`
- `kmp/src/commonMain/resources/demo-graph/pages/All Pages.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Flashcards.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Markdown Formatting.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Tables.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Tasks.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Images.md`
- `kmp/src/commonMain/resources/demo-graph/pages/Keyboard Shortcuts.md`
- `kmp/src/commonMain/resources/demo-graph/pages/About SteleKit.md`
- `kmp/src/commonMain/resources/demo-graph/journals/2026_04_08.md`
- `kmp/src/commonMain/resources/demo-graph/journals/2026_04_09.md`
- `kmp/src/commonMain/resources/demo-graph/journals/2026_04_10.md`
- `kmp/src/commonMain/resources/demo-graph/journals/2026_04_11.md`
- `kmp/src/commonMain/resources/demo-graph/journals/2026_04_12.md`
- `kmp/src/commonMain/resources/demo-graph/assets/stelekit-diagram.png`

### Story 3 (new files)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DemoGraphIntegrationTest.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt`

### Story 4 (new files)
- `kmp/src/jvmTest/resources/screenshot-test-graph/pages/Welcome.md`
- `kmp/src/jvmTest/resources/screenshot-test-graph/pages/Properties Example.md`
- `kmp/src/jvmTest/resources/screenshot-test-graph/pages/Linked Page.md`
- `kmp/src/jvmTest/resources/screenshot-test-graph/journals/2026_01_15.md`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/DemoGraphScreenshotTest.kt`
- 4x PNG baseline files (location per existing Roborazzi convention)

### Story 5 (new + modified files)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/JournalsDocs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/FlashcardsDocs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/AllPagesDocs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/PageViewDocs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/NotificationsDocs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/LogsDocs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/screens/PerformanceDocs.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/FeatureDocRegistryInit.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (modify: add `@HelpPage` to each `Screen` variant)
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` (modify: call `initFeatureDocRegistry()`)
