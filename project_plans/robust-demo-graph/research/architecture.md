# Architecture Research: Robust Demo Graph

**Phase**: 2 — Architecture Research  
**Date**: 2026-04-13  
**Researcher**: Claude Code  

## 1. Class Hierarchy Entry Points

### App.kt Structure
The top-level `StelekitApp()` composable is the entry point. Key observations:

- **ScreenRouter** (lines 500-566) dispatches between screens:
  - `Screen.PageView` → `PageView()`
  - `Screen.Journals` → `JournalsView()`
  - `Screen.Flashcards` → `FlashcardsScreen()`
  - `Screen.AllPages` → `AllPagesScreen()`
  - `Screen.Notifications`, `Screen.Logs`, `Screen.Performance`

- **Screens are composable functions**, not classes or objects. The AppState controls which screen is active.

- **Screen enum** (AppState.kt) defines all user-facing screens as sealed class variants:
  ```kotlin
  sealed class Screen {
      data object Journals : Screen()
      data object Flashcards : Screen()
      data object AllPages : Screen()
      data object Notifications : Screen()
      data object Logs : Screen()
      data object Performance : Screen()
      data class PageView(val page: Page) : Screen()
  }
  ```

### Screens Directory
Screens are composable functions, not classes:
- `JournalsView.kt` — composable function
- `PageView.kt` — composable function
- `JournalsViewModel.kt`, `SearchViewModel.kt` — supporting ViewModels

### Components Directory (30 files)
Components are all composable functions. Key editing/rendering components:
- `BlockEditor.kt` — the main block editing composable
- `BlockRenderer.kt` — renders blocks in read-only view mode
- `BlockViewer.kt` — viewing surface
- `BlockList.kt` — renders a list of blocks
- `MarkdownEngine.kt` — inline markdown rendering
- `Sidebar.kt`, `TopBar.kt` — UI chrome

### No Existing Feature Abstraction
- **No `Feature` interface or sealed class exists** in commonMain.
- **No existing annotations** with `@Retention` or `@Target` for feature registration.
- One annotation file found (`DirectRepositoryWrite.kt`) but unrelated to feature documentation.

**Implication**: A new abstraction must be designed from scratch.

---

## 2. KSP vs. Reflection vs. Gradle Task

### Build Configuration Analysis
**kmp/build.gradle.kts findings:**

- **KSP not currently configured**. Plugin list (lines 4-11):
  ```
  kotlin("multiplatform")
  kotlin("plugin.compose")
  kotlin("plugin.serialization")
  id("com.android.library")
  id("org.jetbrains.compose")
  id("app.cash.sqldelight")
  id("io.github.takahirom.roborazzi")
  ```

- **No custom Gradle tasks** for code generation or annotation processing.

- **Test framework**: Kotlin test + Roborazzi (desktop screenshot testing). No reflection-based test runners found.

- **Compose Multiplatform resources**: Build file references `compose.desktop` and `nativeDistributions` but **no `composeResources` block** is configured. Resources are in `src/jvmMain/resources/` (currently empty).

### Test Fixture Pattern
Both `GraphLoaderTest` and `GraphLoaderIntegrationTest` use **in-memory fixture graphs**:

```kotlin
// GraphLoaderIntegrationTest.kt:17-33
private val fileSystem = object : FileSystem {
    val files = mutableMapOf<String, String>()
    override fun readFile(path: String): String? = files[path]
    override fun writeFile(path: String, content: String): Boolean { 
        files[path] = content; return true 
    }
    // ... other methods ...
}
```

**No existing pattern loads from classpath/bundled resources.**

### Reflection-Based Scanning Limitations
- **Cross-platform issue**: Java reflection (ServiceLoader, classpath scanning) only works on JVM.
- SteleKit targets JVM, Android, and iOS. Reflection is unavailable on iOS.
- Test-only reflection on jvmTest is viable but won't catch iOS/Android issues at build time.

### Recommendation: Test-Based Enforcement (Pragmatic Fit)
Given the codebase:

1. **KSP would require:**
   - Adding `com.google.devtools.ksp` plugin
   - Configuring per-target processor (JVM, Android, iOS)
   - Writing a KSP processor
   - Complex incremental compilation setup
   - **High implementation cost, not currently standard in the project.**

2. **Gradle custom task:**
   - Could scan compiled class bytecode or generated artifact lists
   - Would require build infrastructure not yet in place
   - Adds complexity to the Gradle lifecycle
   - **Medium cost, but maintainability risk.**

3. **Test-based enforcement (recommended):**
   - Leverages existing jvmTest infrastructure
   - Simple to implement: hardcoded list + assertion
   - Can be extended with reflection on JVM; iOS/Android versions can be stubs
   - **Fits existing architecture; low cost; sufficient quality gate**
   - Works with optional interface or annotation approach

**Test approach: Create `DemoGraphCoverageTest` in jvmTest that:**
- Loads the bundled demo graph from classpath via test resources
- Verifies every `.md` page parses under `GraphLoader`
- Optionally: scan for `@HelpPage` annotations (JVM only) and assert referenced pages exist
- Fail build if any page has unresolved wiki-links or empty blocks

---

## 3. Demo Graph Directory Layout

### Existing Resource Directories
```
kmp/src/jvmMain/resources/          ← exists, currently empty
kmp/src/jsMain/resources/           ← exists
kmp/src/commonMain/                 ← no resources dir yet
```

### Recommended Structure
For **Compose Multiplatform** + **KMP consistency**, place demo graph in:

```
kmp/src/commonMain/resources/demo-graph/
├── pages/
│   ├── Welcome.md
│   ├── Block Editing.md
│   ├── Page Linking.md
│   ├── Properties.md
│   ├── Daily Notes.md
│   └── [other feature pages].md
├── journals/
│   ├── 2026_04_11.md
│   ├── 2026_04_12.md
│   └── 2026_04_13.md
└── assets/
    └── demo_image.png
```

**Rationale:**

1. **commonMain placement**: Resources in commonMain are available to all targets (JVM, Android, iOS, JS). Each platform extracts them during build.

2. **Compose Multi-platform standard**: Per Compose documentation, resources in `src/commonMain/resources/` are copied to each platform's classpath automatically.

3. **No additional configuration needed**: Gradle's `kotlin {}` block already includes `composeResources` by default in recent versions. If not, a single block in build.gradle.kts suffices:
   ```gradle
   kotlin {
       composeResources {
           srcDirs += project.file("src/commonMain/resources")
       }
   }
   ```

### Classpath Loading Pattern
For **tests and classpath-based loading**, resources become available via:

- **JVM/Desktop**: `ClassLoader.getResourceAsStream("demo-graph/pages/Welcome.md")`
- **Android**: Via app resources (requires copying at build time or embedding in AAB/APK)
- **iOS**: Via bundle resources (requires copying at build time)
- **Tests**: `System.getResourceAsStream()` in jvmTest

**Implementation approach**: Create a platform-agnostic `DemoGraphLoader` that:
- On JVM: reads from classpath directly
- On Android/iOS: delegates to platform-specific resource loading
- In tests: uses test resources or test FileSystem mock

---

## 4. Integration Test Pattern

### Current GraphLoader Test Pattern
From `GraphLoaderTest.kt` and `GraphLoaderIntegrationTest.kt`:

**GraphLoaderTest approach** (in-memory):
```kotlin
val tempDir = File(System.getProperty("user.home"), "graphloader_test_${System.currentTimeMillis()}")
val pagesDir = File(tempDir, "pages").also { it.mkdirs() }
File(pagesDir, "contents.md").writeText("...")

graphLoader.loadGraph(tempDir.absolutePath) {}
```

**GraphLoaderIntegrationTest approach** (mocked FileSystem):
```kotlin
private val fileSystem = object : FileSystem {
    val files = mutableMapOf<String, String>()
    override fun readFile(path: String): String? = files[path]
    // ...
}
graphLoader.loadGraph("/graph") {}
```

### Proposed DemoGraphIntegrationTest
Create a new test in `jvmTest/kotlin/dev/stapler/stelekit/db/DemoGraphIntegrationTest.kt`:

```kotlin
class DemoGraphIntegrationTest {
    
    @Test
    fun `demo graph loads without errors`() = runBlocking {
        val demoGraphPath = javaClass.classLoader
            .getResource("demo-graph")?.path 
            ?: fail("demo-graph not found in classpath")
        
        val fileSystem = PlatformFileSystem()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)
        
        // Load the bundled demo graph
        graphLoader.loadGraph(demoGraphPath) { progress -> 
            println("Loading: $progress")
        }
        
        // Assertions
        val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        assertNotEmpty(pages, "Demo graph must have at least one page")
        
        // Check that no blocks are empty
        for (page in pages) {
            val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
            for (block in blocks) {
                assertTrue(block.content.isNotBlank(), 
                    "Block on ${page.name} is empty")
            }
        }
        
        // Check wiki-links resolve (optional: can add link resolver)
    }
    
    @Test
    fun `demo graph pages match help page annotations`() = runBlocking {
        // JVM-only: scan for @HelpPage annotations
        val annotatedPages = findHelpPageAnnotations()
        val demoGraphPages = loadDemoGraphPageNames()
        
        for (pageName in annotatedPages) {
            assertTrue(pageName in demoGraphPages, 
                "Help page '$pageName' declared but missing in demo graph")
        }
    }
}
```

**Key points:**

- Uses existing `GraphLoader` without modification
- Loads from `classpath://demo-graph` (will exist once resources are checked in)
- Assertions verify **no parse errors**, **no empty blocks**, **wiki-links valid**
- Optional annotation scanning adds enforcement on JVM test suite
- On failure, build exits; PR checks catch missing demo pages immediately

---

## 5. Screenshot Test Integration

### Existing Pattern
From `DesktopScreenshotTest.kt` and `JournalsViewScreenshotTest.kt`:

- **Setup**: Create fake repositories with seeded data
- **Render**: Set composable content via `composeTestRule.setContent { ... }`
- **Capture**: Call `composeTestRule.onRoot().captureRoboImage("path.png")`

**Example** (JournalsViewScreenshotTest.kt:75-85):
```kotlin
val pageRepo = FakePageRepository(initialPages = listOf(page))
val blockRepo = FakeBlockRepository(blocksByPage = mapOf(pageUuid to listOf(...)))
val journalService = JournalService(pageRepo, blockRepo)
val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
val viewModel = JournalsViewModel(journalService, blockStateManager, scope)

composeTestRule.setContent {
    StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
        JournalsView(viewModel = viewModel, blockRepository = blockRepo)
    }
}
composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/...")
```

### Proposed DemoGraphScreenshotTest
Create a new test in `jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/DemoGraphScreenshotTest.kt`:

```kotlin
class DemoGraphScreenshotTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    private fun loadDemoGraphPage(pageName: String): Page {
        val demoGraphPath = javaClass.classLoader
            .getResource("demo-graph")?.path 
            ?: fail("demo-graph not found")
        
        val fileSystem = PlatformFileSystem()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)
        
        graphLoader.loadGraph(demoGraphPath) {}
        
        val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        return pages.find { it.name.equals(pageName, ignoreCase = true) }
            ?: fail("Page '$pageName' not found in demo graph")
    }
    
    @Test
    fun demo_graph_welcome_page() {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val page = loadDemoGraphPage("Welcome")
        
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                PageView(
                    page = page,
                    blockRepository = blockRepo,
                    pageRepository = pageRepo,
                    blockStateManager = /* ... */,
                    currentGraphPath = "/demo-graph"
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            "build/outputs/roborazzi/demo_welcome_page.png"
        )
    }
    
    @Test
    fun demo_graph_journals_index() {
        // Similar: load journal pages, render JournalsView
    }
}
```

**Advantages:**

- Renders real demo graph content, not fake data
- Baselines capture actual feature surfaces
- Roborazzi automatically detects visual regressions
- Can be run locally for manual review before commit
- Screenshots become living documentation

---

## 6. Annotation vs. Interface Approaches

### Annotation-Based Approach

```kotlin
// In commonMain/kotlin/dev/stapler/stelekit/feature/HelpPage.kt
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpPage(val page: String)
```

**Usage:**
```kotlin
@HelpPage("Block Editing")
object BlockEditorFeature

@HelpPage("Daily Notes")
@Composable
fun JournalsView(...) { ... }
```

**Pros:**
- Lightweight; no interface inheritance required
- Can annotate functions (composables) directly
- Semantically clear: "this feature documents on page X"

**Cons:**
- Requires reflection scanning (JVM only) for runtime enforcement
- No compile-time check that page exists
- Runtime overhead (scanning classpath on app startup)

### Interface-Based Approach

```kotlin
// In commonMain/kotlin/dev/stapler/stelekit/feature/Feature.kt
interface Feature {
    val helpPageName: String
    val helpPagePath: String
        get() = "demo-graph/pages/$helpPageName.md"
}

// Usage
object BlockEditorFeature : Feature {
    override val helpPageName = "Block Editing"
}
```

**Pros:**
- Compile-time check: must implement
- No reflection; works on all platforms (JVM, Android, iOS)
- Integrates naturally with object/class hierarchy
- Can add other feature metadata later (author, since, etc.)

**Cons:**
- Requires all screens/components to inherit/implement
- Harder to annotate composable functions
- More boilerplate

### Hybrid Approach (Recommended)

**Combine both** for maximum flexibility:

```kotlin
// commonMain
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpPage(val page: String)

interface Feature {
    val helpPageName: String
}

// Usage example 1: implement interface
data object BlockEditorFeature : Feature {
    override val helpPageName = "Block Editing"
}

// Usage example 2: annotate enum/class
@HelpPage("Daily Notes")
sealed class JournalFeature : Feature {
    override val helpPageName = "Daily Notes"
}

// Usage example 3: annotate a composable (for future extension)
@HelpPage("Search")
@Composable
fun SearchDialog(...) { ... }
```

**Test-time enforcement** (jvmTest):
```kotlin
@Test
fun all_help_pages_are_valid() {
    // Scan for @HelpPage annotations (JVM reflection)
    val annotatedPages = findAnnotatedPages()
    
    // Load demo graph page names
    val demoGraphPages = loadDemoGraphPageNames()
    
    for (page in annotatedPages) {
        assertTrue(page in demoGraphPages, 
            "@HelpPage('$page') declared but '$page.md' missing")
    }
}
```

---

## 7. Recommended Architecture

### Summary: Tiered Enforcement Strategy

**Goal**: Ensure every user-facing feature has corresponding help documentation in the bundled demo graph, enforced at test time.

### Step 1: Define the Feature Abstraction

Create two files in `commonMain`:

**File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/feature/Feature.kt`**
```kotlin
package dev.stapler.stelekit.feature

interface Feature {
    val helpPageName: String
    val helpPageDescription: String
        get() = ""
}
```

**File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/feature/HelpPage.kt`**
```kotlin
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpPage(
    val page: String,
    val description: String = ""
)
```

### Step 2: Update Screen Enum to Require Help Pages

**File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`** (modify existing):
```kotlin
sealed class Screen {
    // Existing variants, each with associated help page
    data object Journals : Screen() { 
        const val HELP_PAGE = "Daily Notes"
    }
    data object Flashcards : Screen() {
        const val HELP_PAGE = "Flashcards"
    }
    data object AllPages : Screen() {
        const val HELP_PAGE = "All Pages"
    }
    // ... etc
}
```

Alternatively, create a companion mapping:
```kotlin
object ScreenHelpPages {
    val map = mapOf(
        Screen.Journals::class to "Daily Notes",
        Screen.PageView::class to "Page Viewing",
        // ...
    )
}
```

### Step 3: Bundle Demo Graph as Resources

**Directory structure:**
```
kmp/src/commonMain/resources/demo-graph/
├── pages/
│   ├── Welcome.md
│   ├── Block Editing.md
│   ├── Page Linking.md
│   ├── Properties & Metadata.md
│   ├── Daily Notes.md
│   ├── Page Viewing.md
│   ├── All Pages.md
│   ├── Search.md
│   └── Flashcards.md
├── journals/
│   ├── 2026_04_11.md
│   ├── 2026_04_12.md
│   └── 2026_04_13.md
└── assets/
    └── demo_image.png
```

### Step 4: Create DemoGraphIntegrationTest

**File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DemoGraphIntegrationTest.kt`**

```kotlin
class DemoGraphIntegrationTest {
    @Test
    fun `all demo graph pages parse successfully`() = runBlocking {
        val demoGraphPath = loadResourcePath("demo-graph")
            ?: fail("demo-graph resource not found in classpath")
        
        val fileSystem = PlatformFileSystem()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)
        
        graphLoader.loadGraph(demoGraphPath) {}
        
        val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        assertTrue(pages.isNotEmpty(), "Demo graph must contain at least one page")
        
        // Verify no empty blocks
        for (page in pages) {
            val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
            for (block in blocks) {
                assertTrue(block.content.isNotBlank(), 
                    "Page '${page.name}' has empty block (uuid: ${block.uuid})")
            }
        }
    }
    
    @Test
    fun `all declared help pages exist in demo graph`() = runBlocking {
        val demoGraphPath = loadResourcePath("demo-graph") ?: return@runBlocking
        
        val fileSystem = PlatformFileSystem()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)
        
        graphLoader.loadGraph(demoGraphPath) {}
        
        val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        val pageNames = pages.map { it.name.lowercase() }.toSet()
        
        // Define all help pages that should exist
        val requiredPages = setOf(
            "Welcome",
            "Block Editing",
            "Page Linking",
            "Properties & Metadata",
            "Daily Notes",
            "Page Viewing",
            "All Pages",
            "Search"
        )
        
        for (required in requiredPages) {
            assertTrue(required.lowercase() in pageNames,
                "Help page missing: '$required'")
        }
    }
    
    private fun loadResourcePath(resourceName: String): String? {
        val resource = javaClass.classLoader.getResource(resourceName)
        return resource?.path
    }
}
```

### Step 5: Create Screenshot Tests

**File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/DemoGraphScreenshotTest.kt`**

```kotlin
class DemoGraphScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    
    private fun loadDemoGraphPage(pageName: String): Pair<Page, List<Block>> {
        val demoGraphPath = javaClass.classLoader
            .getResource("demo-graph")?.path 
            ?: fail("demo-graph not found")
        
        val fileSystem = PlatformFileSystem()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)
        
        runBlocking { graphLoader.loadGraph(demoGraphPath) {} }
        
        val pages = runBlocking { 
            pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        }
        val page = pages.find { it.name.equals(pageName, ignoreCase = true) }
            ?: fail("Page '$pageName' not found in demo graph")
        
        val blocks = runBlocking {
            blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        }
        
        return page to blocks
    }
    
    @Test
    fun demo_welcome_page_light() {
        val (page, blocks) = loadDemoGraphPage("Welcome")
        
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                PageView(
                    page = page,
                    blockRepository = FakeBlockRepository(mapOf(page.uuid to blocks)),
                    pageRepository = FakePageRepository(listOf(page)),
                    blockStateManager = createMockBlockStateManager(),
                    currentGraphPath = "demo-graph",
                    onToggleFavorite = {},
                    onRefresh = {},
                    onLinkClick = {},
                    viewModel = createMockViewModel(),
                    isDebugMode = false
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            "build/outputs/roborazzi/demo_welcome_light.png"
        )
    }
    
    @Test
    fun demo_journals_page_light() {
        val journalPages = loadAllDemoJournalPages()
        
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                JournalsView(
                    viewModel = createJournalsViewModel(journalPages),
                    blockRepository = createBlockRepoWithJournals(journalPages),
                    isDebugMode = false,
                    onLinkClick = {}
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            "build/outputs/roborazzi/demo_journals_light.png"
        )
    }
}
```

### Step 6: CI/CD Integration

In your CI workflow (GitHub Actions, etc.):

```yaml
# .github/workflows/test.yml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: 21
      
      - name: Run integration tests
        run: ./gradlew jvmTest
        # Fails if DemoGraphIntegrationTest assertions fail:
        # - Missing demo-graph resource
        # - Any page fails to parse
        # - Help page declared but missing in demo-graph
      
      - name: Update Roborazzi baselines (if approved)
        run: ./gradlew recordRoborazzi
        if: github.event_name == 'pull_request_target'
```

### Step 7: PR Workflow

**Requirements for new feature PRs:**

1. **Add help page markdown** to `kmp/src/commonMain/resources/demo-graph/pages/<Feature Name>.md`
2. **Update demo graph index** in Welcome page
3. **Update Screen enum or help page registry** with new screen name
4. **Add screenshot test** for the new page (if user-facing screen)

**CI checks:**
- `jvmTest` fails if:
  - New `@HelpPage("Foo")` declared but `demo-graph/pages/Foo.md` missing
  - Any demo graph page fails to parse
  - Wiki-links in demo graph are unresolved
- `recordRoborazzi` fails if new screenshot test not captured

---

### Directory Layout Summary

```
kmp/
├── src/
│   ├── commonMain/
│   │   ├── kotlin/
│   │   │   └── dev/stapler/stelekit/
│   │   │       ├── feature/
│   │   │       │   ├── Feature.kt           [NEW]
│   │   │       │   └── HelpPage.kt          [NEW]
│   │   │       └── ui/AppState.kt           [MODIFY: add help page mappings]
│   │   └── resources/
│   │       └── demo-graph/                  [NEW]
│   │           ├── pages/
│   │           │   ├── Welcome.md
│   │           │   ├── Block Editing.md
│   │           │   └── ...
│   │           ├── journals/
│   │           │   └── *.md
│   │           └── assets/
│   │               └── *.png
│   └── jvmTest/
│       ├── kotlin/
│       │   ├── dev/stapler/stelekit/
│       │   │   ├── db/
│       │   │   │   └── DemoGraphIntegrationTest.kt [NEW]
│       │   │   └── ui/screenshots/
│       │   │       └── DemoGraphScreenshotTest.kt  [NEW]
│       │   └── resources/
│       │       └── (symlink or copy of demo-graph) [optional for local test runs]
│       └── roborazzi/
│           └── (baselines generated by Roborazzi)
├── build.gradle.kts                         [no changes needed if composeResources configured]
└── project_plans/
    └── robust-demo-graph/
        ├── requirements.md
        └── research/
            └── architecture.md              [THIS FILE]
```

### Enforcement Gates

1. **Build-time**: Demo graph files checked in; Gradle build includes resources
2. **Compile-time**: Feature classes/functions typed via `Feature` interface (optional)
3. **Test-time** (primary gate):
   - `DemoGraphIntegrationTest` verifies no parse errors
   - Annotation scanner (JVM) asserts `@HelpPage` references exist
   - Screenshot tests capture regressions
4. **PR review**: Code reviewer checks demo graph markdown quality and completeness

### Success Criteria

- Any PR that adds a new user-facing feature must include a corresponding demo graph page
- `./gradlew jvmTest` fails if demo pages are missing or broken
- Screenshot baselines prevent visual regressions in demo content
- New contributors can quickly understand features by exploring the demo graph
- Help pages are always in sync with code (no stale documentation)

---

## Diataxis Interface Design

### Interface Hierarchy

Create a new package: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/`

```kotlin
// Root marker — all docs implement this
interface DiaxisDoc

// Required for every user-facing feature
interface HowToDoc : DiaxisDoc {
    val howTo: HowToContent
}
interface ReferenceDoc : DiaxisDoc {
    val reference: ReferenceContent
}

// Optional — for complex features only
interface TutorialDoc : DiaxisDoc {
    val tutorial: TutorialContent
}
interface ExplanationDoc : DiaxisDoc {
    val explanation: ExplanationContent
}
```

Content data classes are minimal `@Serializable` holders — only fields needed to render a Logseq `.md` file, no UI dependencies:

```kotlin
@Serializable
data class HowToContent(
    val title: String,
    val description: String,
    val steps: List<String>,
    val tips: List<String> = emptyList(),
    val commonMistakes: List<String> = emptyList(),
    val relatedTasks: List<String> = emptyList()
)

@Serializable
data class ReferenceContent(
    val title: String,
    val description: String,
    val sections: List<ReferenceSection> = emptyList(),
    val commands: List<CommandReference> = emptyList(),
    val keyboardShortcuts: List<KeyboardShortcut> = emptyList()
)
// ... TutorialContent, ExplanationContent follow same pattern
```

### KClass Reference in Annotations — KMP Compatibility

`@HelpPage(docs = BlockEditorDocs::class)` uses `KClass<out DiaxisDoc>`. The annotation capture itself is safe on all platforms (no reflection at annotation time). **Runtime instantiation via reflection is NOT safe on iOS/JS.**

**Solution — factory registry (no reflection):**

```kotlin
object FeatureDocRegistry {
    private val registry = mutableMapOf<KClass<out DiaxisDoc>, () -> DiaxisDoc>()

    inline fun <reified T : DiaxisDoc> register(docClass: KClass<T>, noinline factory: () -> T) {
        registry[docClass] = factory as () -> DiaxisDoc
    }

    fun getDoc(docClass: KClass<out DiaxisDoc>): DiaxisDoc? = registry[docClass]?.invoke()
}
```

Features register at app startup in `commonMain` (not per-platform):
```kotlin
// AppInitializer.kt
FeatureDocRegistry.register(BlockEditorDocs::class) { BlockEditorDocs() }
```

This works identically on JVM, Android, iOS, and JS — no reflection, just a map lookup.

### Generation vs. Validation

**Decision: validation of checked-in files** (not code generation).

Rationale:
- `.md` files are human-authored and checked in alongside code — atomic commits, reviewable diffs
- Build-time KSP is not currently in the project and has per-target complexity (see Pitfalls)
- **Primary enforcement gate**: `DemoGraphCoverageTest` (JVM test) scans the classpath for all
  classes annotated with `@HelpPage`, resolves the `docs` KClass, checks which Diataxis interfaces
  it implements, and asserts the corresponding `.md` files exist and are non-empty in the demo graph

```kotlin
// DemoGraphCoverageTest.kt (jvmTest)
@Test fun allFeaturesHaveRequiredHelpPages() {
    val features = reflectAllHelpPageAnnotations()   // JVM-only reflection, test scope only
    features.forEach { (symbol, docsClass) ->
        if (docsClass is HowToDoc) assertPageExists("How to ${...}.md", symbol)
        if (docsClass is ReferenceDoc) assertPageExists("Reference/${...}.md", symbol)
    }
}
```

Reflection is acceptable in JVM tests — it's not shipped to production targets.

### Proposed File Structure

```
kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/
├── DiaxisDoc.kt          # Interface hierarchy + content data classes
├── HelpPage.kt           # @HelpPage annotation
└── FeatureDocRegistry.kt # Factory registry (cross-platform)

kmp/src/commonMain/resources/demo-graph/
├── pages/
│   ├── Start Here.md
│   ├── Block Editing.md          ← HowToDoc for BlockEditorDocs
│   ├── Block Editor Reference.md ← ReferenceDoc for BlockEditorDocs
│   └── ...
├── journals/
│   ├── 2026-01-15.md
│   └── ...
└── assets/
    └── stelekit-logo.png

kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/
└── DemoGraphCoverageTest.kt  # Asserts all @HelpPage pages exist + non-empty
```

