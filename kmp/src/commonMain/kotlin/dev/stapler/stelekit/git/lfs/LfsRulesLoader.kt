// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException

/**
 * Loads the attribute layers that can affect a set of changed paths from the working tree: every
 * ancestor directory's `.gitattributes` plus `.git/info/attributes`. Evaluated on every call and
 * never cached, so an edit to `.gitattributes` (or a pull that changes it) is seen on the next sync.
 */
class LfsRulesLoader(
    private val fileSystem: FileSystem,
    private val repoRoot: String,
    private val ignoreCase: Boolean = false,
) {
    fun load(changedPaths: Collection<String>): AttributeRuleSet {
        val directories = LinkedHashSet<String>()
        directories += ""
        for (path in changedPaths) {
            var dir = path.substringBeforeLast('/', "")
            while (dir.isNotEmpty() && directories.add(dir)) dir = dir.substringBeforeLast('/', "")
        }
        val files = directories.mapNotNull { dir ->
            val location = if (dir.isEmpty()) ".gitattributes" else "$dir/.gitattributes"
            readText("$repoRoot/$location")?.let { GitAttributesParser.parse(it, dirPrefix = dir) }
        }
        val info = readText("$repoRoot/.git/info/attributes")
            ?.let { GitAttributesParser.parse(it, dirPrefix = "", allowMacros = true, sourceName = ".git/info/attributes") }
        return AttributeRuleSet(files, info, ignoreCase)
    }

    private fun readText(path: String): String? =
        try {
            fileSystem.readFile(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}

/** [LfsContentProbe] over a [FileSystem] rooted at the repository. */
class FileSystemLfsContentProbe(private val fileSystem: FileSystem, private val repoRoot: String) : LfsContentProbe {
    override suspend fun size(path: String): Long? = guarded { fileSystem.getFileSize("$repoRoot/$path") }

    override suspend fun readHead(path: String, maxBytes: Int): ByteArray? = guarded {
        fileSystem.readFileBytes("$repoRoot/$path")?.let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
    }

    private inline fun <T> guarded(block: () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
