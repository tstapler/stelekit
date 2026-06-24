package dev.stapler.stelekit.testing

import java.io.File
import java.net.JarURLConnection
import java.net.URL

/**
 * Resolves a classpath resource as a real File, handling both filesystem (Gradle)
 * and JAR-packed (Bazel) resource layouts.
 *
 * In Gradle, resources are real directories on disk. In Bazel, they are packed into
 * JARs. When the resource is inside a JAR, this function extracts it to a temp
 * directory so tests can access it via the normal File API.
 */

private val extractionLock = Any()

fun getClasspathDirectory(loader: ClassLoader, resourceName: String): File {
    val url: URL = loader.getResource(resourceName)
        ?: error("$resourceName not found in classpath")
    if (url.protocol == "file") return File(url.toURI())
    // JAR resource (Bazel packs resources into JARs): extract to temp dir.
    val dest = File(
        System.getenv("TEST_TMPDIR") ?: System.getProperty("java.io.tmpdir"),
        resourceName.replace('/', '_'),
    )
    synchronized(extractionLock) {
        if (!dest.exists()) {
            val tmp = File(dest.parent, dest.name + ".tmp")
            tmp.deleteRecursively()
            tmp.mkdirs()
            try {
                val conn = url.openConnection() as JarURLConnection
                conn.jarFile.use { jar ->
                    val prefix = conn.entryName + "/"
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(prefix) }
                        .forEach { entry ->
                            val target = File(tmp, entry.name.removePrefix(prefix))
                            target.parentFile?.mkdirs()
                            jar.getInputStream(entry).use { input -> input.copyTo(target.outputStream()) }
                        }
                }
                tmp.renameTo(dest)
            } catch (e: Exception) {
                tmp.deleteRecursively()
                throw e
            }
        }
    }
    return dest
}
