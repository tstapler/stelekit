package dev.stapler.stelekit.platform

/**
 * Abstraction for reading bundled (classpath/asset) resources.
 * Separate from [PlatformFileSystem] which handles mutable user-managed files
 * and has a path security whitelist.
 *
 * Implementations:
 * - JVM: ClassLoader.getResourceAsStream (jvmMain)
 * - Android: AssetManager.open (androidMain) — stub until Story 5+
 * - iOS: NSBundle (iosMain) — stub until Story 5+
 */
interface ResourceLoader {
    /** Returns the text content of the resource at [path], or null if not found. */
    fun readResource(path: String): String?

    /** Returns the names (not full paths) of all files directly in the resource directory at [path]. */
    fun listResourceDirectory(path: String): List<String>
}

expect fun platformResourceLoader(): ResourceLoader
