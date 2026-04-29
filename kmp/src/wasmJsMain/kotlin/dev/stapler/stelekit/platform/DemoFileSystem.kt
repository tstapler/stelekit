// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * An in-memory FileSystem implementation for the browser demo.
 * Provides pre-seeded content so the wasmJs demo loads with meaningful pages
 * without requiring a real filesystem or SQLite driver.
 */
class DemoFileSystem : FileSystem {
    private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    private val journalFileName =
        "${today.year}_${today.monthNumber.toString().padStart(2, '0')}_${today.dayOfMonth.toString().padStart(2, '0')}.md"

    private val demoFiles = mapOf(
        "journals/$journalFileName" to """
            - Welcome to SteleKit!
              - This is a [[block outliner]]. Each bullet is a block.
              - Press **Enter** to add a block, **Tab** to indent, **Shift-Tab** to outdent.
              - Type `[[` to create a link to another page — try `[[Getting Started]]`
            - Your notes stay in plain Markdown files. No proprietary format.
            - Navigate to [[Getting Started]] to see how the outliner works.
        """.trimIndent(),
        "pages/getting-started.md" to """
            - SteleKit is a local-first outliner
              - Everything is a block
              - Blocks nest to any depth
                - Like this
                  - And this
            - Key shortcuts
              - **Enter** — new block below
              - **Tab / Shift-Tab** — indent / outdent
              - **Alt+Up / Alt+Down** — move block up / down
              - **Cmd/Ctrl+K** — open search
            - Links
              - Type `[[page name]]` to link to any page
              - Backlinks are tracked automatically — see [[Backlinks Demo]]
        """.trimIndent(),
        "pages/backlinks-demo.md" to """
            - This page demonstrates bidirectional linking
            - [[Getting Started]] links here, so it appears in the backlinks panel
              - Open [[Getting Started]] and look for the Backlinks section on the right
            - Every `[[link]]` you create is indexed in real time
            - No manual backlink maintenance needed
        """.trimIndent(),
    )

    override fun getDefaultGraphPath(): String = "/demo"
    override fun expandTilde(path: String): String = path

    override fun readFile(path: String): String? {
        val relative = path.removePrefix("/demo/")
        return demoFiles[relative]
    }

    override fun writeFile(path: String, content: String): Boolean = true

    override fun listFiles(path: String): List<String> {
        val prefix = path.removePrefix("/demo/").let { if (it.isEmpty()) "" else "$it/" }
        return demoFiles.keys
            .filter { if (prefix.isEmpty()) true else it.startsWith(prefix) }
            .map { "/demo/$it" }
    }

    override fun listDirectories(path: String): List<String> {
        return if (path == "/demo" || path == "/demo/") {
            listOf("/demo/journals", "/demo/pages")
        } else emptyList()
    }

    override fun fileExists(path: String): Boolean {
        val relative = path.removePrefix("/demo/")
        return demoFiles.containsKey(relative)
    }

    override fun directoryExists(path: String): Boolean = path.startsWith("/demo")

    override fun createDirectory(path: String): Boolean = true
    override fun deleteFile(path: String): Boolean = true
    override fun pickDirectory(): String? = null
    override suspend fun pickDirectoryAsync(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
}
