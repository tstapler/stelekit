# Stack Research: Robust Demo Graph

**Phase**: 2 — Stack Analysis  
**Date**: 2026-04-13  
**Researcher**: Claude Code (Haiku 4.5)

---

## 1. KMP Resource Loading

### Current State

**Resource directories exist but are mostly empty:**

- `kmp/src/jvmMain/resources/icons/` — contains only app icon (`icon.png`)
- `kmp/src/jsMain/resources/` — exists, unused
- `kmp/src/commonMain/` — **no resources directory yet**
- `kmp/src/androidMain/` — no resources directory (uses Android resources system)

**No existing resource loading patterns found:**

The codebase does **not** currently load bundled assets via:
- `getResourceAsStream()` (ClassLoader approach)
- `Res.readBytes()` or `Res.string()` (Compose Multiplatform resources)
- Any custom resource abstraction in `PlatformFileSystem`

The only resource reference in the codebase is:
- `/kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt` — references `pagesRes.getOrNull()` but this is a Kotlin `Result` object, not a file resource.

### Compose Multiplatform Resource System

**Configured**: The build.gradle.kts includes `org.jetbrains.compose.components:components-resources:1.7.3` as a dependency (line 58), which provides access to Compose Multiplatform resources.

**Not configured**: No explicit `composeResources {}` block exists in `kmp/build.gradle.kts`. This means:
- Resources in `src/commonMain/resources/` would be copied to each platform's classpath automatically by Gradle
- However, no Compose `Res` code generation has been set up; would require enabling it if using Compose's resource system

### Implication

**The project uses Compose Multiplatform but has not adopted its resource system.** Instead, it relies on **platform-specific classpath loading**:

- **JVM/Desktop**: `ClassLoader.getResourceAsStream("path/to/resource")` works directly
- **Android**: Resources must be bundled in `res/` or accessed via `Context.getResources()`
- **iOS**: Resources must be bundled into the app bundle at build time

**Recommendation for demo graph**: Place it in `kmp/src/commonMain/resources/demo-graph/` (following Compose Multiplatform convention), but implement platform-specific loaders in `PlatformFileSystem` to read from classpath (JVM/Android) or app bundle (iOS).

---

## 2. PlatformFileSystem: Bundled vs. Real Paths

### Interface Definition

**File**: `/kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt` (lines 5–26)

```kotlin
interface FileSystem {
    fun getDefaultGraphPath(): String
    fun expandTilde(path: String): String
    fun readFile(path: String): String?
    fun writeFile(path: String, content: String): Boolean
    fun listFiles(path: String): List<String>
    fun listDirectories(path: String): List<String>
    fun fileExists(path: String): Boolean
    fun directoryExists(path: String): Boolean
    fun createDirectory(path: String): Boolean
    fun deleteFile(path: String): Boolean
    fun pickDirectory(): String?
    suspend fun pickDirectoryAsync(): String? = pickDirectory()
    fun getLastModifiedTime(path: String): Long?
    fun hasStoragePermission(): Boolean = true
    fun getLibraryDisplayName(): String? = null
    fun displayNameForPath(path: String): String = ...
    fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) { }
    fun stopExternalChangeDetection() { }
}
```

### Current Implementation: JVM

**File**: `/kmp/src/jvmCommonMain/kotlin/com/logseq/kmp/platform/JvmFileSystemBase.kt` (lines 1–173)

**Key methods**:
- `readFile(path: String): String?` — reads from filesystem using `File(validatedPath).readText()`
- `listFiles(path: String): List<String>` — lists files in a directory
- `fileExists(path: String): Boolean` — checks file existence
- All methods include **path validation** via `validatePath()`, which enforces a whitelist of allowed directories for security

**Whitelist implementation** (lines 18–25):
```kotlin
private val whitelist = ConcurrentHashMap.newKeySet<String>().apply {
    val home = System.getProperty("user.home")
    if (home != null) {
        add(Paths.get(home).toAbsolutePath().normalize().toString())
    }
}
```

The whitelist includes `~user.home` by default and is expanded via `registerGraphRoot()` when user picks a graph directory.

### Gap: No Bundled/Classpath Support

**Current `PlatformFileSystem` does NOT support loading bundled resources** because:

1. **`validatePath()` requires paths to be in the whitelist** — classpath paths like `/demo-graph/pages/Welcome.md` would fail validation since they're not under `user.home`
2. **No special handling for `classpath://` or resource:// URIs** — the implementation only works with real filesystem paths
3. **`readFile()` uses `File(path)` directly** — this cannot read from classpath or JAR resources

### Android Implementation

**File**: `/kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (lines 1–150+)

Android's implementation adds SAF (Storage Access Framework) support but similarly:
- Uses `File` for local filesystem access
- Uses `DocumentFile` for scoped storage/SAF URIs
- **No built-in support for bundled asset reading**

To access bundled resources on Android, would need to use:
```kotlin
val inputStream = context.assets.open("demo-graph/pages/Welcome.md")
val content = inputStream.bufferedReader().readText()
```

### iOS Implementation

**File**: `/kmp/src/iosMain/kotlin/com/logseq/kmp/platform/PlatformFileSystem.kt` (unknown size)

Not examined in detail, but iOS likely uses `NSBundle` for resource access.

### Solution: New Method Required

**Recommendation**: Add an optional method to `FileSystem` interface to support reading bundled resources without filesystem validation:

```kotlin
// In FileSystem.kt
fun readBundledResource(resourcePath: String): String? = null  // default no-op
```

Then implement platform-specific logic:
- **JVM**: `ClassLoader.getResourceAsStream("demo-graph/...")`
- **Android**: `context.assets.open("demo-graph/...")`
- **iOS**: `NSBundle.main.contents(ofPath:)`

Alternatively, create a separate `ResourceLoader` abstraction that is **not** subject to path whitelisting.

---

## 3. KSP (Kotlin Symbol Processing) Setup

### Current Status: Not Configured

**File**: `/kmp/build.gradle.kts` (lines 4–11)

```gradle
plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("app.cash.sqldelight")
    id("io.github.takahirom.roborazzi") version "1.59.0"
}
```

**Findings**:
- **No KSP plugin** (`com.google.devtools.ksp`) is listed
- **No custom Gradle tasks** for annotation processing
- **No KSP processor dependencies** in the dependency lists (commonMain, jvmMain, etc.)

### Adding KSP Would Require

1. **Plugin dependency** in `build.gradle.kts`:
   ```gradle
   id("com.google.devtools.ksp") version "2.0.21-1.0.21"  // KSP version must match Kotlin version
   ```

2. **Processor library** in dependencies:
   ```gradle
   dependencies {
       ksp("org.example:my-ksp-processor:1.0.0")
   }
   ```

3. **KSP configuration block** (if cross-platform processing is needed):
   ```gradle
   kotlin {
       sourceSets {
           commonMain {
               dependencies {
                   ksp("some-processor:1.0")
               }
           }
       }
   }
   ```

4. **Custom KSP processor implementation** — a separate Gradle module that:
   - Implements `SymbolProcessor` interface
   - Scans for `@HelpPage` annotations
   - Verifies referenced `.md` files exist in resources
   - Generates error/warning code

### Cross-Platform KSP Limitations

**Critical issue**: KSP runs during compilation, but **each platform compiles separately in KMP**:

- **JVM compilation** → KSP can find bundled resources in JVM classpath
- **Android compilation** → KSP sees Android resources
- **iOS compilation** → KSP sees iOS bundle resources
- **Incremental compilation** → KSP cache must be invalidated when resources change

This means building the iOS target would need a separate KSP processor configured for iOS resources, adding significant complexity.

### Recommendation

**Skip KSP for now.** Instead, use **test-time enforcement** (see Section 5 below), which is simpler and more maintainable given the current project structure.

---

## 4. Annotation Processing in KMP

### Kotlin Version

**Current**: **2.0.21** (from `/android/build.gradle` line 6)

```gradle
ext {
    kotlin_version = '2.0.21'
    composeCompilerVersion = '2.0.1'
    androidxActivityVersion = '1.9.2'
}
```

This is a modern Kotlin version (released late 2024). Annotations and reflection work as expected.

### Existing Annotations

**Found**: Only one custom annotation in the codebase:

**File**: `/kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DirectRepositoryWrite.kt`

```kotlin
@RequiresOptIn(
    message = "Repository writes must go through DatabaseWriteActor to prevent SQLITE_BUSY. Use the actor instead.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class DirectRepositoryWrite
```

This shows the codebase **already uses annotations for compile-time safety**. A `@HelpPage` annotation would follow a similar pattern.

### No Reflection-Based Scanning

**Finding**: The codebase does **not** use reflection to scan annotations at runtime.

The `DirectRepositoryWrite` annotation is enforced by:
- **Compiler check**: the `@RequiresOptIn` causes the Kotlin compiler to error on direct calls without opt-in
- **Not runtime reflection**

For `@HelpPage`, we would need to add runtime reflection **only in tests** (jvmTest), since:
- **JVM tests can use reflection** to scan for annotations at runtime
- **Android/iOS tests would need stubs** (or we skip annotation scanning on those platforms)
- **Production code should not use reflection** for classpath scanning (expensive, unavailable on iOS)

### Approach: Annotation + Test-Based Verification

```kotlin
// commonMain: Define annotation
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpPage(val page: String)

// jvmTest: Scan annotations and verify pages exist
@Test
fun all_help_pages_exist() {
    val annotatedPages = javaClass.classLoader
        .loadClass("full.package.path.ClassName")
        .annotations
        .filterIsInstance<HelpPage>()
        .map { it.page }
    
    val demoGraphPages = loadDemoGraphPageNames()
    for (page in annotatedPages) {
        assertTrue(page in demoGraphPages)
    }
}
```

This approach:
- Defines the annotation in common code ✓
- Only uses reflection in jvmTest (no cross-platform reflection issues) ✓
- No KSP setup required ✓
- Works with Kotlin 2.0.21 ✓

---

## 5. Test Resource Loading Pattern

### Current Test Patterns

#### Pattern 1: Temp Directory Fixture (GraphLoaderTest.kt)

**File**: `/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderTest.kt` (lines 20–58)

```kotlin
private fun createFixtureGraph(): File {
    val tempDir = File(System.getProperty("user.home"), "graphloader_test_${System.currentTimeMillis()}")
    val pagesDir = File(tempDir, "pages").also { it.mkdirs() }
    val journalsDir = File(tempDir, "journals").also { it.mkdirs() }

    // Write fixture markdown
    File(pagesDir, "contents.md").writeText(
        """
        - First item on the contents page
        	- Nested child of first item
        - Second item on the contents page
        """.trimIndent()
    )
    
    return tempDir
}

@Test
fun testLoadDemoGraph() = runBlocking {
    val graphDir = createFixtureGraph()
    try {
        val fileSystem = PlatformFileSystem()
        val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)
        graphLoader.loadGraph(graphDir.absolutePath) {}
        // ... assertions
    } finally {
        graphDir.deleteRecursively()
    }
}
```

**Findings**:
- Writes fixture markdown to a **temp directory in `user.home`** (timestamped for uniqueness)
- Uses **real filesystem** for loading (via `PlatformFileSystem()`)
- Cleans up after test with `deleteRecursively()`
- **No classpath resource loading**

#### Pattern 2: Mock FileSystem (GraphLoaderIntegrationTest.kt)

**File**: `/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt` (lines 15–37)

```kotlin
private val fileSystem = object : FileSystem {
    val files = mutableMapOf<String, String>()

    override fun readFile(path: String): String? = files[path]
    override fun writeFile(path: String, content: String): Boolean { 
        files[path] = content; return true 
    }
    override fun listFiles(path: String): List<String> = 
        files.keys.filter { it.startsWith(path) }.map { it.substringAfterLast("/") }
    // ... other methods
}

@Test
fun `test full loading pipeline with hierarchy`() = runBlocking {
    val content = """
- Parent Block
  - Child 1
    - Grandchild 1
  - Child 2
- Root 2
    """.trimIndent()
    
    val path = "/graph/pages/testpage.md"
    fileSystem.files[path] = content
    
    graphLoader.loadGraph("/graph") { _ -> }
    // ... assertions
}
```

**Findings**:
- Uses an **in-memory mock `FileSystem`** with `mutableMapOf` storage
- **No real filesystem I/O**
- Excellent for unit testing (fast, isolated)
- **Cannot test actual bundled resources** (paths are arbitrary)

### No Existing Classpath Resource Loading

**Critical Finding**: The codebase has **no existing examples of loading resources from classpath** via:
- `javaClass.classLoader.getResourceAsStream()`
- `javaClass.getResource()`
- `ClassLoader.getSystemResourceAsStream()`

This means:

1. **No established pattern to follow** — we'll be introducing a new approach
2. **Tests currently use temp directories or mocks** — not classpath resources
3. **Classpath resource loading will be new** to the codebase

### Proposed Pattern for Demo Graph Tests

For the demo graph to be loaded in tests, we need a new pattern:

```kotlin
// jvmTest/kotlin/dev/stapler/stelekit/db/DemoGraphIntegrationTest.kt

class DemoGraphIntegrationTest {
    
    private fun loadResourcePath(resourceName: String): String? {
        val resource = javaClass.classLoader.getResource(resourceName)
        return resource?.path ?: run {
            // Fallback: try file:// protocol
            val url = javaClass.classLoader.getResource(resourceName) ?: return null
            url.toURI().path
        }
    }
    
    @Test
    fun `demo graph loads successfully`() = runBlocking {
        val demoGraphPath = loadResourcePath("demo-graph")
            ?: fail("demo-graph resource not found in classpath")
        
        val fileSystem = PlatformFileSystem()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)
        
        // Load from classpath
        graphLoader.loadGraph(demoGraphPath) {}
        
        val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        assertTrue(pages.isNotEmpty(), "Demo graph must have pages")
    }
}
```

**Crucial implementation detail**: The classpath path returned by `ClassLoader.getResource()` is filesystem-compatible on JVM (e.g., `/home/user/.m2/.../demo-graph`), so it works directly with `GraphLoader` and `PlatformFileSystem` without modification.

---

## Summary: Stack Readiness

| Dimension | Status | Notes |
|-----------|--------|-------|
| **Resource loading** | ✗ Not implemented | No classpath resource system yet; Compose Multiplatform `Res` not enabled |
| **PlatformFileSystem** | ⚠ Needs extension | Supports filesystem paths only; no bundled resource support; whitelisting prevents classpath paths |
| **KSP setup** | ✗ Not configured | Would require plugin + processor + per-platform configuration; not standard in project |
| **Annotations** | ✓ Ready | Kotlin 2.0.21 supports annotations; one example exists (`DirectRepositoryWrite`) |
| **Reflection** | ✓ Limited | JVM tests can use reflection; iOS/Android unavailable; test-based enforcement is viable |
| **Test resources** | ⚠ Needs creation | Existing tests use temp dirs or mocks; no classpath resource pattern yet |

### Minimal Changes Required

1. **Create** `kmp/src/commonMain/resources/demo-graph/` directory structure
2. **Add** optional method to `FileSystem` for classpath reading (or create separate `ResourceLoader`)
3. **Create** `DemoGraphIntegrationTest.kt` that uses `ClassLoader.getResource("demo-graph")` pattern
4. **Create** `HelpPage.kt` annotation in commonMain (optional, for future enhancement)
5. **Modify** JVM `PlatformFileSystem` to support `/` paths in resource lookup (or use separate loader)

### Recommended Architecture

- **Don't use KSP** — test-based enforcement is sufficient
- **Don't modify classpath path handling in `PlatformFileSystem`** — it's properly restrictive for security
- **Create a new `ResourceLoader` interface** for bundled resources (separate from filesystem)
- **Platform implementations**:
  - JVM: `ClassLoader.getResourceAsStream()`
  - Android: `context.assets.open()`
  - iOS: `NSBundle.main`
- **Tests**: Load demo graph via classpath and verify in `DemoGraphIntegrationTest`

---

## Files Relevant to Implementation

**Core interfaces**:
- `/kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`

**JVM implementations**:
- `/kmp/src/jvmCommonMain/kotlin/com/logseq/kmp/platform/JvmFileSystemBase.kt`
- `/kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

**Android implementations**:
- `/kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

**Test examples**:
- `/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderTest.kt`
- `/kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderIntegrationTest.kt`

**Build configuration**:
- `/kmp/build.gradle.kts` (no changes needed for resource inclusion; Gradle handles it automatically)
- `/android/build.gradle` (Kotlin version: 2.0.21)

---

## Phase 3 Recommendations

1. **Start with test-based enforcement** — lowest risk, highest pragmatism
2. **Do not invest in KSP** — complexity not justified given single use case
3. **Create `ResourceLoader` abstraction** — separates bundled resources from filesystem paths
4. **Implement platform-specific loaders** — each target handles resource reading natively
5. **Verify classpath integration** — ensure Gradle includes `commonMain/resources/` in JAR/APK/app bundle

