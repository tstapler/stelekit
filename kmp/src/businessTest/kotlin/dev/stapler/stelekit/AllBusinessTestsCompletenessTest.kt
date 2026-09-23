package dev.stapler.stelekit

import dev.stapler.stelekit.docs.DocRepoLocator
import org.junit.Test
import org.junit.runners.Suite
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertTrue

/**
 * Structural guard for the bug class documented atop [AllBusinessTests]: a new `*Test.kt` file
 * under `kmp/src/businessTest` compiles fine (Bazel's `srcs` glob picks it up) but silently never
 * runs under `bazel test //kmp:business_tests` unless it's also added to
 * [AllBusinessTests]'s `@Suite.SuiteClasses` list — `./gradlew jvmTest` masks the gap because it
 * uses JUnit's default auto-discovery instead of this suite.
 *
 * Two discovery paths, mirroring [DocRepoLocator]'s Bazel/Gradle split:
 *  1. **Gradle** (preferred when available): [DocRepoLocator.repoRoot] resolves to a real on-disk
 *     path, so this walks `kmp/src/businessTest/kotlin` directly and reads each `*Test.kt` file's
 *     `package` line + filename to compute its class name — precise even though, under Gradle,
 *     `jvmTest.dependsOn(businessTest)` compiles both source sets into *one* merged JVM test
 *     compilation, so a naive classpath/reflection scan run from this task would also pick up
 *     unrelated `jvmTest`-only classes (verified: it does — `DatabaseIntegrationTest`,
 *     `GraphLoaderTest`, benchmark tests, etc. all showed up as false positives before this file
 *     switched to reading real source files instead).
 *  2. **Bazel** (fallback, when `DocRepoLocator.repoRoot` throws — no real file for its anchor
 *     resource inside Bazel's sandboxed runfiles jar): scans `java.class.path` for compiled
 *     `*Test.class` files under the `dev.stapler.stelekit` package tree and keeps only classes
 *     carrying a JUnit `@Test`-annotated method. This is accurate under Bazel specifically because
 *     `//kmp:business_tests` is its own isolated `kt_jvm_test` target — unlike Gradle's merged
 *     jvmTest compilation, its classpath contains only `kmp/src/businessTest`'s own classes.
 *
 * Would have caught this repo's 2026-09-14 regression (PR #327): 15 of 16 new
 * relocation/storage-move test classes were compiled but unregistered.
 */
class AllBusinessTestsCompletenessTest {

    @Test
    fun `every businessTest JUnit test class is registered in AllBusinessTests`() {
        val registered: Set<String> = AllBusinessTests::class.java
            .getAnnotation(Suite.SuiteClasses::class.java)
            .value
            .map { it.java.name }
            .toSet()
        assertTrue(registered.isNotEmpty(), "Could not read @Suite.SuiteClasses off AllBusinessTests")

        val discovered = discoverTestClasses()
        assertTrue(
            discovered.size >= registered.size / 2,
            "Test-class discovery only found ${discovered.size} candidates under " +
                "dev.stapler.stelekit, far fewer than the ${registered.size} already registered in " +
                "AllBusinessTests — the scan itself is likely broken (see discoverTestClasses in " +
                "AllBusinessTestsCompletenessTest.kt), which would make the completeness check below " +
                "a false pass rather than a real one."
        )

        val missing = discovered - registered
        assertTrue(
            missing.isEmpty(),
            "The following businessTest classes compile under kmp/src/businessTest but are NOT " +
                "registered in AllBusinessTests's @Suite.SuiteClasses, so they silently never run " +
                "under `bazel test //kmp:business_tests` (only Gradle's `./gradlew jvmTest` " +
                "auto-discovers them):\n  ${missing.sorted().joinToString("\n  ")}\n\n" +
                "Fix: add each class's import + `<ClassName>::class` entry to " +
                "kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt"
        )
    }

    private fun discoverTestClasses(): Set<String> =
        discoverFromRealSources() ?: discoverFromClasspath()

    /** Gradle path: read every `.kt` file under `kmp/src/businessTest/kotlin` directly off disk.
     * Returns null if [DocRepoLocator.repoRoot] can't resolve a real filesystem path (the Bazel
     * case). */
    private fun discoverFromRealSources(): Set<String>? {
        val repoRoot = try {
            DocRepoLocator.repoRoot
        } catch (e: Throwable) {
            return null
        }
        val businessTestRoot = repoRoot.resolve("kmp/src/businessTest/kotlin")
        if (!businessTestRoot.isDirectory) return null

        return businessTestRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .mapNotNull { file ->
                val text = file.readText()
                if (!Regex("""@Test\b""").containsMatchIn(text)) return@mapNotNull null
                val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                "$pkg.${file.nameWithoutExtension}"
            }
            .toSet()
    }

    /** Bazel fallback: scan `java.class.path` for compiled `*Test.class` files under the
     * `dev.stapler.stelekit` package tree, loading each (without initializing, so no
     * static-initializer side effects) to
     * confirm it actually carries a JUnit `@Test`-annotated method. `kotlin.test.Test` is a JVM
     * typealias for `org.junit.Test` (via the `kotlin-test-junit` dependency wired in on both
     * Gradle and Bazel here), so this catches both annotation spellings. Safe from the
     * Gradle-merged-compilation contamination problem because `//kmp:business_tests` is its own
     * isolated Bazel target. */
    private fun discoverFromClasspath(): Set<String> {
        val packageName = "dev.stapler.stelekit"
        val packagePath = packageName.replace('.', '/')
        val classLoader = javaClass.classLoader
        val candidateBinaryNames = mutableSetOf<String>()

        System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it) }
            .forEach { entry ->
                when {
                    entry.isDirectory -> collectFromDirectory(entry, packagePath, packageName, candidateBinaryNames)
                    entry.isFile && entry.extension == "jar" -> collectFromJar(entry, packagePath, candidateBinaryNames)
                }
            }

        return candidateBinaryNames
            .filter { it.substringAfterLast('.').endsWith("Test") && '$' !in it }
            .filter { hasJUnitTestMethod(it, classLoader) }
            .toSet()
    }

    private fun collectFromDirectory(classpathRoot: File, packagePath: String, packageName: String, out: MutableSet<String>) {
        val packageDir = File(classpathRoot, packagePath)
        if (!packageDir.isDirectory) return
        packageDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { file ->
                val relative = file.relativeTo(packageDir).path.removeSuffix(".class")
                out += packageName + "." + relative.replace(File.separatorChar, '.')
            }
    }

    private fun collectFromJar(jarFile: File, packagePath: String, out: MutableSet<String>) {
        runCatching {
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("$packagePath/") && it.name.endsWith(".class") }
                    .forEach { entry -> out += entry.name.removeSuffix(".class").replace('/', '.') }
            }
        }
    }

    private fun hasJUnitTestMethod(binaryName: String, classLoader: ClassLoader): Boolean {
        val clazz = try {
            Class.forName(binaryName, false, classLoader)
        } catch (e: Throwable) {
            // A class that can't even be loaded/linked can't be a runnable test class either;
            // not a completeness-check false negative.
            return false
        }
        return runCatching {
            clazz.declaredMethods.any { it.isAnnotationPresent(Test::class.java) }
        }.getOrDefault(false)
    }
}
