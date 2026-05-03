package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.util.ContentHasher

/**
 * Manages `.stelekit/pages/<slug>.meta.json` NDJSON sidecar files.
 *
 * Each sidecar is a newline-delimited JSON file (one object per line, one block per line)
 * that maps block content hashes to stable UUIDs. This enables UUID recovery after a git
 * pull that changed block order but not content: if a block's content hash matches a
 * sidecar entry, its UUID is preserved regardless of position.
 *
 * Sidecar format (one line per block):
 * {"uuid":"<uuid>","hash":"<sha256>","pos":<int>,"parent":"<uuid-or-null>"}
 *
 * The file diffs cleanly in `git diff` because only the lines for edited blocks change.
 *
 * NOTE: Sidecar files should NOT be committed to git alongside markdown files.
 * Add `.stelekit/` to your graph's `.gitignore` to prevent accidental commits.
 */
class SidecarManager(
    private val fileSystem: FileSystem,
    private val graphPath: String,
) {
    private val logger = Logger("SidecarManager")

    data class SidecarEntry(
        val uuid: String,
        val pos: Int,
        val parentUuid: String?,
    )

    /**
     * Writes the sidecar for [pageSlug] from the given block list.
     * Creates `.stelekit/pages/` if it doesn't exist.
     */
    suspend fun write(pageSlug: String, blocks: List<Block>) {
        val path = sidecarPath(pageSlug)
        ensureSidecarDir()
        val ndjson = buildString {
            for (block in blocks) {
                val hash = block.contentHash ?: ContentHasher.sha256ForContent(block.content)
                val parentJson = if (block.parentUuid != null) "\"${block.parentUuid}\"" else "null"
                appendLine("""{"uuid":"${block.uuid}","hash":"$hash","pos":${block.position},"parent":$parentJson}""")
            }
        }.trimEnd()
        fileSystem.writeFile(path, ndjson)
    }

    /**
     * Reads the sidecar for [pageSlug].
     * Returns a map of content-hash → [SidecarEntry], or null if the sidecar doesn't exist.
     *
     * When multiple blocks share the same content hash (identical content on the same page),
     * the first encountered entry wins. This is a known limitation — duplicate-content blocks
     * will share a UUID lookup result, but position-derived fallback still differentiates them
     * at the DB level.
     */
    suspend fun read(pageSlug: String): Map<String, SidecarEntry>? {
        val path = sidecarPath(pageSlug)
        val content = fileSystem.readFile(path) ?: return null
        val result = mutableMapOf<String, SidecarEntry>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val entry = parseSidecarLine(trimmed)
                if (entry != null) {
                    // First match wins for duplicate-content blocks
                    if (!result.containsKey(entry.first)) result[entry.first] = entry.second
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("SidecarManager: failed to parse sidecar line: $trimmed", e)
            }
        }
        return result.ifEmpty { null }
    }

    fun sidecarPath(pageSlug: String): String =
        "$graphPath/.stelekit/pages/$pageSlug.meta.json"

    private fun ensureSidecarDir() {
        val dir = "$graphPath/.stelekit/pages"
        if (!fileSystem.directoryExists(dir)) {
            fileSystem.createDirectory("$graphPath/.stelekit")
            fileSystem.createDirectory(dir)
        }
    }

    // Parse a single NDJSON line: {"uuid":"...","hash":"...","pos":N,"parent":"..."|null}
    // Uses a minimal hand-rolled parser to avoid kotlinx.serialization dependency changes.
    private fun parseSidecarLine(line: String): Pair<String, SidecarEntry>? {
        fun extractString(key: String): String? {
            val prefix = "\"$key\":\""
            val idx = line.indexOf(prefix)
            if (idx < 0) return null
            val start = idx + prefix.length
            val end = line.indexOf('"', start)
            if (end < 0) return null
            return line.substring(start, end)
        }

        fun extractInt(key: String): Int? {
            val prefix = "\"$key\":"
            val idx = line.indexOf(prefix)
            if (idx < 0) return null
            val start = idx + prefix.length
            val end = (start until line.length).firstOrNull { !line[it].isDigit() && line[it] != '-' }
                ?: return null
            return line.substring(start, end).toIntOrNull()
        }

        fun extractNullableString(key: String): String? {
            val nullPattern = "\"$key\":null"
            if (line.contains(nullPattern)) return null
            return extractString(key)
        }

        val uuid = extractString("uuid") ?: return null
        val hash = extractString("hash") ?: return null
        val pos = extractInt("pos") ?: return null
        val parent = extractNullableString("parent")
        return hash to SidecarEntry(uuid = uuid, pos = pos, parentUuid = parent)
    }
}
