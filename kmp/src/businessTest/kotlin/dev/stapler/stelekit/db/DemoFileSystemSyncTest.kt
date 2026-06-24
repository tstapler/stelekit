// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import java.io.File
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
 */
class DemoFileSystemSyncTest {

    private val demoGraphPagesDir: File by lazy {
        val url = javaClass.classLoader.getResource("demo-graph/pages")
            ?: fail("demo-graph/pages not found on classpath — check that commonMain resources are on the test classpath")
        File(url.toURI())
    }

    private val generatedFileSource: String by lazy {
        // Walk up from the classpath resource to find the module root containing the generated file.
        // The generated file lives in wasmJsMain, not on the test classpath — read it as a File.
        var dir = demoGraphPagesDir
        while (dir != dir.parentFile) {
            val candidate = dir.resolve(
                "src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt"
            )
            if (candidate.exists()) return@lazy candidate.readText()
            dir = dir.parentFile
        }
        fail(
            "Generated DemoFileSystem.kt not found — run :kmp:generateDemoFileSystem first.\n" +
            "Search started from: $demoGraphPagesDir"
        )
    }

    @Test
    fun `every demo-graph page appears in generated DemoFileSystem`() {
        val mdFiles = demoGraphPagesDir.listFiles { f -> f.extension == "md" }
            ?: fail("demo-graph/pages is not a readable directory: $demoGraphPagesDir")

        val missing = mdFiles.filter { f ->
            "\"pages/${f.name}\"" !in generatedFileSource
        }

        assertTrue(
            missing.isEmpty(),
            "These demo-graph pages are missing from the generated DemoFileSystem.kt:\n" +
            missing.joinToString("\n") { "  pages/${it.name}" } +
            "\nRun :kmp:generateDemoFileSystem to regenerate."
        )
    }
}
