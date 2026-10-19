// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.createShortTempDirectory
import dev.stapler.stelekit.model.GraphId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 5.1.1: `McpSocketPath` must assert `0700` on the socket directory every call, not just
 * on first creation — covers a prior run (or manual tampering) leaving it looser.
 */
private const val SECURE_PERMISSIONS = "rwx------"
private const val LOOSE_PERMISSIONS = "rwxr-xr-x"
private val TEST_GRAPH_ID = GraphId("g1")

class McpSocketPathPermissionTest {

    @Test
    fun `McpSocketPath forGraph corrects a pre-existing 0755 mcp directory to 0700`() = withTempHome { home ->
        val mcpDir = mcpDirUnder(home)
        Files.createDirectories(mcpDir)
        Files.setPosixFilePermissions(mcpDir, PosixFilePermissions.fromString(LOOSE_PERMISSIONS))
        assertEquals(LOOSE_PERMISSIONS, PosixFilePermissions.toString(Files.getPosixFilePermissions(mcpDir)))

        McpSocketPath.forGraph(TEST_GRAPH_ID)

        assertEquals(SECURE_PERMISSIONS, PosixFilePermissions.toString(Files.getPosixFilePermissions(mcpDir)))
    }

    @Test
    fun `McpSocketPath forGraph leaves an already-0700 mcp directory unchanged`() = withTempHome { home ->
        val mcpDir = mcpDirUnder(home)
        Files.createDirectories(mcpDir)
        Files.setPosixFilePermissions(mcpDir, PosixFilePermissions.fromString(SECURE_PERMISSIONS))

        McpSocketPath.forGraph(TEST_GRAPH_ID)

        assertEquals(SECURE_PERMISSIONS, PosixFilePermissions.toString(Files.getPosixFilePermissions(mcpDir)))
    }

    @Test
    fun `McpSocketPath forGraph returns the expected per-graph sock path`() = withTempHome { home ->
        val path: Path = McpSocketPath.forGraph(TEST_GRAPH_ID)

        assertEquals(mcpDirUnder(home).resolve("${TEST_GRAPH_ID.value}.sock"), path)
        assertTrue(Files.isDirectory(path.parent))
    }
}

private fun mcpDirUnder(home: String): Path = Paths.get(home, ".stelekit", "mcp")

/** Runs [block] with `user.home` pointed at a fresh temp directory, restored afterward. */
internal fun <T> withTempHome(block: (String) -> T): T {
    val original = System.getProperty("user.home")
    val tempHome = createShortTempDirectory("stelekit-mcp-test-home")
    System.setProperty("user.home", tempHome.toString())
    try {
        return block(tempHome.toString())
    } finally {
        System.setProperty("user.home", original)
        tempHome.toFile().deleteRecursively()
    }
}
