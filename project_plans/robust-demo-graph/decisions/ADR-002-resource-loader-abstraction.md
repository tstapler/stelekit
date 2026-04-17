# ADR-002: ResourceLoader Abstraction Separate from PlatformFileSystem

**Status**: Accepted  
**Date**: 2026-04-13  
**Deciders**: Tyler Stapler  
**Feature**: Robust Demo Graph

---

## Context

The bundled demo graph must be loadable on every target (JVM/Desktop, Android, iOS). Each platform uses a different mechanism to access bundled resources:

| Platform | Mechanism |
|----------|-----------|
| JVM/Desktop | `ClassLoader.getResourceAsStream("demo-graph/pages/foo.md")` |
| Android | `context.assets.open("demo-graph/pages/foo.md")` via `AssetManager` |
| iOS | `NSBundle.main.path(forResource:ofType:)` then read via `NSFileManager` |

The existing `PlatformFileSystem` interface (`FileSystem.kt`) is not suitable for this role:

1. **Security whitelist**: `JvmFileSystemBase.kt` enforces a path whitelist (`user.home` + registered graph roots). Classpath paths do not appear in the whitelist and would fail `validatePath()`.
2. **Semantic mismatch**: `FileSystem` models a mutable filesystem (read, write, list, delete, create directories). Bundled resources are read-only and are not user-managed files.
3. **Android `Context` dependency**: Android's `PlatformFileSystem` already requires `Context` for SAF and local storage. Bundled asset loading also requires `Context`, but the timing and lifecycle of this initialization differs from file I/O.

Two candidate approaches were evaluated:

**Option A — Add `readBundledResource(path: String): String?` to the `FileSystem` interface.**
All existing platform implementations gain a default `null` return; each platform overrides it.

**Option B — Define a separate `ResourceLoader` interface.**
A narrow interface with one responsibility: read bytes/text from a bundled resource path. Platform implementations are independent of `FileSystem`.

---

## Decision

**Adopt Option B** — a new `ResourceLoader` interface separate from `FileSystem`.

### Interface Definition

```kotlin
// commonMain: platform/ResourceLoader.kt
interface ResourceLoader {
    /**
     * Read a bundled resource by its classpath-relative path.
     * Returns null if the resource does not exist.
     *
     * Path uses forward-slash separators regardless of platform.
     * Example: "demo-graph/pages/Welcome.md"
     */
    fun readResource(path: String): String?

    /**
     * List the immediate children of a bundled resource directory.
     * Returns empty list if the path does not exist or is not a directory.
     *
     * Example: listResourceDirectory("demo-graph/pages") returns
     * ["Welcome.md", "Block Editing.md", ...]
     */
    fun listResourceDirectory(path: String): List<String>
}
```

### Platform Implementations

**JVM** (`jvmMain/platform/JvmResourceLoader.kt`):
```kotlin
class JvmResourceLoader : ResourceLoader {
    override fun readResource(path: String): String? =
        javaClass.classLoader
            ?.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()

    override fun listResourceDirectory(path: String): List<String> {
        val url = javaClass.classLoader?.getResource(path) ?: return emptyList()
        // Walk the directory from the filesystem URL returned by ClassLoader
        return File(url.toURI()).listFiles()?.map { it.name } ?: emptyList()
    }
}
```

**Android** (`androidMain/platform/AndroidResourceLoader.kt`):
```kotlin
class AndroidResourceLoader(private val context: Context) : ResourceLoader {
    override fun readResource(path: String): String? = try {
        context.assets.open(path).bufferedReader().readText()
    } catch (e: IOException) { null }

    override fun listResourceDirectory(path: String): List<String> = try {
        context.assets.list(path)?.toList() ?: emptyList()
    } catch (e: IOException) { emptyList() }
}
```

**iOS** (`iosMain/platform/IosResourceLoader.kt`):
```kotlin
class IosResourceLoader : ResourceLoader {
    override fun readResource(path: String): String? {
        val resourcePath = NSBundle.mainBundle.resourcePath ?: return null
        val fullPath = "$resourcePath/$path"
        return NSFileManager.defaultManager.contentsAtPath(fullPath)
            ?.let { NSString.create(it, NSUTF8StringEncoding) as? String }
    }

    override fun listResourceDirectory(path: String): List<String> {
        val resourcePath = NSBundle.mainBundle.resourcePath ?: return emptyList()
        val fullPath = "$resourcePath/$path"
        return NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(fullPath, error = null)
            ?.filterIsInstance<String>() ?: emptyList()
    }
}
```

### Integration with GraphLoader

`GraphLoader` gains an optional secondary constructor / factory function that accepts a `ResourceLoader` instead of a `FileSystem`:

```kotlin
// Usage in DemoGraphIntegrationTest (JVM)
val loader = ResourceLoader.forCurrentPlatform()  // expect() / actual()
val graphLoader = GraphLoader.fromResources(
    resourceLoader = loader,
    resourceRoot = "demo-graph",
    pageRepository = InMemoryPageRepository(),
    blockRepository = InMemoryBlockRepository(),
    searchRepository = InMemorySearchRepository()
)
graphLoader.loadGraph("demo-graph") {}
```

Alternatively — and simpler for the initial implementation — the JVM integration test uses `ClassLoader.getResource("demo-graph")?.path` to get a real filesystem path that the existing `GraphLoader` + `PlatformFileSystem` can read directly, bypassing the whitelist by registering the path:

```kotlin
// Simplest JVM test approach (no ResourceLoader changes to GraphLoader needed)
val demoGraphUrl = javaClass.classLoader.getResource("demo-graph")
    ?: fail("demo-graph not found in classpath")
val demoGraphPath = File(demoGraphUrl.toURI()).absolutePath
val fileSystem = PlatformFileSystem().also { it.registerGraphRoot(demoGraphPath) }
val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo, searchRepo)
graphLoader.loadGraph(demoGraphPath) {}
```

This simpler approach is preferred for the first implementation of `DemoGraphIntegrationTest`. The full `ResourceLoader` abstraction is the target state for cross-platform loading (Android, iOS).

---

## Consequences

### Positive
- `FileSystem` remains focused on mutable user-managed files with security whitelisting intact.
- `ResourceLoader` is a narrow single-responsibility interface with no security concerns (all paths are bundled and trusted).
- Android's `Context` dependency for asset loading is isolated to `AndroidResourceLoader` — it does not pollute `PlatformFileSystem` lifecycle.
- The interface is testable without any filesystem access (a test double returns in-memory strings).
- JVM tests can use the simpler `ClassLoader.getResource` + filesystem path approach immediately, deferring full `ResourceLoader` wiring until Android/iOS loading is needed.

### Negative
- Two resource-loading abstractions in the codebase (`FileSystem` and `ResourceLoader`) may confuse contributors unfamiliar with the distinction.
- Android `AndroidResourceLoader` requires `Context` to be initialized before use; this mirrors the existing Android `PlatformFileSystem` init requirement.
- iOS implementation requires verifying that `commonMain/resources/` files are correctly copied into the app bundle by Gradle.

### Neutral
- `commonMain/resources/demo-graph/` is automatically placed on the JVM and Android classpaths by Gradle. No `build.gradle.kts` changes are needed for basic resource inclusion.
- The Compose Multiplatform `composeResources` system was evaluated but not adopted: it requires a `composeResources` block and generates type-safe accessors, which adds build complexity without proportionate benefit for this use case.

---

## Alternatives Rejected

**Option A (add to FileSystem interface)**: Rejected because it violates the Single Responsibility Principle — `FileSystem` has security whitelisting that is inappropriate for trusted bundled resources. Adding a whitelist exemption for `demo-graph/` paths would weaken the security model of the entire abstraction.

**Compose Multiplatform `composeResources`**: Evaluated. Would provide a cross-platform type-safe resource API. Rejected because it requires enabling `composeResources` in `build.gradle.kts` and restructuring the resources directory to `src/commonMain/composeResources/files/`. The project does not currently use this system, and introducing it solely for the demo graph adds significant build infrastructure complexity. It remains a viable future upgrade path.
