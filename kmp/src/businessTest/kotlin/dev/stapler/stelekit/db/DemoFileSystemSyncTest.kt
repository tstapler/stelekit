// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import java.io.File
import java.net.JarURLConnection
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asserts that every .md file in demo-graph/pages/ appears as a key in the
 * generated DemoFileSystem.kt. Catches silent omissions in the Gradle generation
 * task (wrong path separator, missing file, etc.).
 *
 * Requires :kmp:generateDemoFileSystem to have run before this test executes.
 * The jvmTest task is wired to dependsOn(generateDemoFileSystem) in build.gradle.kts.
 * Under Bazel, DemoFileSystem.kt is bundled as a classpath resource via the BUILD.
 */
class DemoFileSystemSyncTest {

    /**
     * Lists the names of all .md files in demo-graph/pages/ from the classpath.
     * Handles both file:// (Gradle) and jar:// (Bazel) resource URLs.
     */
    private fun listMdFileNames(): List<String> {
        val url = javaClass.classLoader.getResource("demo-graph/pages")
            ?: fail("demo-graph/pages not found on classpath — check that commonMain resources are on the test classpath")
        return when (url.protocol) {
            "file" -> {
                val dir = File(url.toURI())
                dir.listFiles { f -> f.extension == "md" }
                    ?.map { it.name }
                    ?: fail("demo-graph/pages is not a readable directory: $dir")
            }
            "jar" -> {
                val connection = url.openConnection() as JarURLConnection
                connection.jarFile.use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("demo-graph/pages/") && it.name.endsWith(".md") }
                        .map { it.name.removePrefix("demo-graph/pages/") }
                        .toList()
                }
            }
            else -> fail("Unexpected URL protocol for demo-graph/pages: ${url.protocol}")
        }
    }

    private val generatedFileSource: String by lazy {
        // Bazel: DemoFileSystem.kt is bundled as a classpath resource via the BUILD.
        javaClass.classLoader.getResourceAsStream("DemoFileSystem.kt")
            ?.bufferedReader()
            ?.readText()
            ?: run {
                // Gradle fallback: walk up from the classpath resource directory.
                val url = javaClass.classLoader.getResource("demo-graph/pages")
                    ?: fail("demo-graph/pages not found on classpath")
                check(url.protocol == "file") {
                    "Cannot locate DemoFileSystem.kt: resource is in a JAR but not exposed as a classpath resource. " +
                    "Ensure DemoFileSystem.kt is bundled via the BUILD file."
                }
                var dir = File(url.toURI())
                while (dir != dir.parentFile) {
                    val candidate = dir.resolve(
                        "src/commonMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt"
                    )
                    if (candidate.exists()) return@run candidate.readText()
                    dir = dir.parentFile
                }
                fail(
                    "Generated DemoFileSystem.kt not found — run :kmp:generateDemoFileSystem first.\n" +
                    "Search started from: $dir"
                )
            }
    }

    @Test
    fun `every demo-graph page appears in generated DemoFileSystem`() {
        val mdFileNames = listMdFileNames()
        assertTrue(mdFileNames.isNotEmpty(), "No .md files found in demo-graph/pages")

        val missing = mdFileNames.filter { name ->
            "\"pages/$name\"" !in generatedFileSource
        }

        assertTrue(
            missing.isEmpty(),
            "These demo-graph pages are missing from the generated DemoFileSystem.kt:\n" +
            missing.joinToString("\n") { "  pages/$it" } +
            "\nRun :kmp:generateDemoFileSystem to regenerate."
        )
    }
}
