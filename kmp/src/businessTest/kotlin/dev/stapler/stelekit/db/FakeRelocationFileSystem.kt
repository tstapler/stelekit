// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem

/**
 * Minimal in-memory, flat-namespace [FileSystem] for `GraphRelocationCoordinator` tests (Story
 * 3.1.5). Unlike `dev.stapler.stelekit.db.sidecar.FakeFileSystem`, this one implements
 * [renameFile] (needed by [AtomicFileRelocationStep]) and models directories as plain path
 * prefixes rather than a separate directory set, since every fixture used by these tests is a
 * single flat directory of markdown files (no nested subdirectories).
 */
open class FakeRelocationFileSystem : FileSystem {
    private val files = mutableMapOf<String, ByteArray>()
    private val explicitDirs = mutableSetOf<String>()

    override fun getDefaultGraphPath(): String = "/graph"
    override fun expandTilde(path: String): String = path
    override fun readFile(path: String): String? = files[path]?.decodeToString()
    override fun writeFile(path: String, content: String): Boolean {
        files[path] = content.encodeToByteArray()
        return true
    }

    override fun listFiles(path: String): List<String> {
        val prefix = "${path.trimEnd('/')}/"
        return files.keys.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains("/") }
            .map { it.removePrefix(prefix) }
    }

    override fun listDirectories(path: String): List<String> = emptyList()
    override fun fileExists(path: String): Boolean = path in files
    override fun directoryExists(path: String): Boolean =
        path in explicitDirs || files.keys.any { it.startsWith("${path.trimEnd('/')}/") }

    override fun createDirectory(path: String): Boolean {
        explicitDirs += path
        return true
    }

    override fun deleteFile(path: String): Boolean {
        files.remove(path)
        explicitDirs.remove(path)
        return true
    }

    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = if (path in files) 0L else null
    override fun readFileBytes(path: String): ByteArray? = files[path]
    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        files[path] = data
        return true
    }

    override fun renameFile(from: String, to: String): Boolean {
        val bytes = files.remove(from) ?: return false
        files[to] = bytes
        return true
    }
}
