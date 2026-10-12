// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.model.GraphId
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Resolves the per-graph Unix-domain-socket path the embedded MCP server binds to
 * (`~/.stelekit/mcp/<graphId>.sock`). `user.home` is read fresh on every call, not cached, so
 * tests can retarget it via `System.setProperty` between runs.
 */
object McpSocketPath {
    private const val DIR_PERMISSIONS = "rwx------"

    /**
     * Returns the socket path for [graphId], creating the parent directory if absent and
     * (re)asserting `0700` permissions on it every call — not just on first creation — so a
     * directory left with looser permissions by a prior install self-heals (Story 5.1.1).
     */
    fun forGraph(graphId: GraphId): Path {
        val dir = mcpDir()
        Files.createDirectories(dir)
        // Non-POSIX filesystems (Windows/NTFS) have no mode bits; the per-user home dir is the boundary there.
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(DIR_PERMISSIONS))
        }
        return dir.resolve("${graphId.value}.sock")
    }

    private fun mcpDir(): Path =
        Paths.get(System.getProperty("user.home"), ".stelekit", "mcp")
}
