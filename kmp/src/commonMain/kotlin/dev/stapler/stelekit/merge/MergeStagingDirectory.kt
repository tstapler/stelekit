// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.model.GraphId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** Filesystem failure or rejected name in the merge staging/manifest stores. */
data class MergeStorageError(val message: String)

/** MergeId becomes a file name, so it must be a plain token; page names never reach the filesystem. */
internal fun isSafeMergeIdToken(value: String): Boolean =
    value.isNotEmpty() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
        value.any { it.isLetterOrDigit() }

/**
 * `<appDataDir>/.stele-merge-staging-<MergeId>/` holding a `.marker` and one `<n>.json`
 * [StagedPage] per page. Mirrors `RelocationStagingDirectory`: a directory without a readable
 * marker is never swept.
 */
class MergeStagingDirectory private constructor(
    private val fileSystem: FileSystem,
    val dir: Path,
    val marker: Marker,
) {
    @Serializable
    data class Marker(
        val mergeId: String,
        val sourceGraphId: String,
        val targetGraphId: String,
        val startedAtEpochMs: Long,
    )

    fun writePage(index: Int, page: MergePage): Either<MergeStorageError, Unit> {
        val path = pagePath(index).getOrNull() ?: return MergeStorageError("invalid page index $index").left()
        return io { fileSystem.write(path) { writeUtf8(StagedPage.encode(page)) } }
    }

    /** Lazy, ascending by page index; only the page being visited is held in memory. */
    fun readAll(): Sequence<Either<StagedPageError, MergePage>> {
        val indices = try {
            fileSystem.list(dir).mapNotNull { pageIndexOf(it.name) }.sorted()
        } catch (e: IOException) {
            return emptySequence()
        }
        return indices.asSequence().map { i ->
            val text = try {
                pagePath(i).getOrNull()?.let { path -> fileSystem.read(path) { readUtf8() } }
            } catch (e: IOException) {
                null
            }
            if (text == null) StagedPageError.Malformed("unreadable staged page $i").left() else StagedPage.decode(text)
        }
    }

    fun pageCount(): Int = try {
        fileSystem.list(dir).count { pageIndexOf(it.name) != null }
    } catch (e: IOException) {
        0
    }

    fun delete(): Either<MergeStorageError, Unit> = io { fileSystem.deleteRecursively(dir) }

    /** `<dir>/<index>.json`, accepted only if it lies inside [dir] per [PageFileResolver.isWithin]. */
    fun pagePath(index: Int): Either<MergeStorageError, Path> {
        if (index < 0) return MergeStorageError("negative page index").left()
        val path = dir / "$index.json"
        return if (PageFileResolver.isWithin(dir.toString(), path.toString())) path.right()
        else MergeStorageError("path outside staging dir").left()
    }

    companion object {
        const val STAGING_PREFIX = ".stele-merge-staging-"
        private const val MARKER_FILE_NAME = ".marker"
        const val DEFAULT_GRACE_PERIOD_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val json = Json { ignoreUnknownKeys = true }

        fun create(
            fileSystem: FileSystem,
            appDataDir: String,
            mergeId: MergeId,
            sourceGraphId: GraphId,
            targetGraphId: GraphId,
            startedAtEpochMs: Long,
        ): Either<MergeStorageError, MergeStagingDirectory> {
            val dir = dirFor(appDataDir, mergeId).getOrNull()
                ?: return MergeStorageError("invalid merge id").left()
            val marker = Marker(mergeId.value, sourceGraphId.value, targetGraphId.value, startedAtEpochMs)
            return io {
                fileSystem.createDirectories(dir)
                fileSystem.write(dir / MARKER_FILE_NAME) { writeUtf8(json.encodeToString(Marker.serializer(), marker)) }
                MergeStagingDirectory(fileSystem, dir, marker)
            }
        }

        /** Re-opens an existing staging directory (resume/retry); null if absent or marker unreadable. */
        fun open(fileSystem: FileSystem, appDataDir: String, mergeId: MergeId): MergeStagingDirectory? {
            val dir = dirFor(appDataDir, mergeId).getOrNull() ?: return null
            val marker = readMarker(fileSystem, dir) ?: return null
            return MergeStagingDirectory(fileSystem, dir, marker)
        }

        /**
         * Deletes `.stele-merge-staging-*` dirs whose marker is older than [gracePeriodMillis].
         * A dir with a missing/corrupt marker is never deleted. Blocking IO; call off the main thread.
         */
        fun sweep(
            fileSystem: FileSystem,
            appDataDir: String,
            nowEpochMs: Long,
            gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS,
        ) {
            val root = appDataDir.toPath()
            val children = try {
                fileSystem.list(root)
            } catch (e: IOException) {
                return
            }
            for (child in children) {
                if (!child.name.startsWith(STAGING_PREFIX)) continue
                if (!PageFileResolver.isWithin(root.toString(), child.toString())) continue
                val marker = readMarker(fileSystem, child) ?: continue
                if (nowEpochMs - marker.startedAtEpochMs <= gracePeriodMillis) continue
                try {
                    fileSystem.deleteRecursively(child)
                } catch (e: IOException) {
                    // best effort; retried next launch
                }
            }
        }

        private fun dirFor(appDataDir: String, mergeId: MergeId): Either<MergeStorageError, Path> {
            if (!isSafeMergeIdToken(mergeId.value)) return MergeStorageError("invalid merge id").left()
            val root = appDataDir.toPath()
            val dir = root / "$STAGING_PREFIX${mergeId.value}"
            return if (PageFileResolver.isWithin(root.toString(), dir.toString())) dir.right()
            else MergeStorageError("path outside app data dir").left()
        }

        private fun readMarker(fileSystem: FileSystem, dir: Path): Marker? = try {
            json.decodeFromString(Marker.serializer(), fileSystem.read(dir / MARKER_FILE_NAME) { readUtf8() })
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun pageIndexOf(name: String): Int? =
            if (name.endsWith(".json")) name.removeSuffix(".json").toIntOrNull()?.takeIf { it >= 0 } else null
    }
}

internal inline fun <T> io(block: () -> T): Either<MergeStorageError, T> = try {
    block().right()
} catch (e: IOException) {
    MergeStorageError(e.message ?: "io error").left()
}

/** Startup hook: sweeps stale staging dirs and expired manifests. */
fun sweepMergeArtifacts(fileSystem: FileSystem, appDataDir: String, nowEpochMs: Long) {
    MergeStagingDirectory.sweep(fileSystem, appDataDir, nowEpochMs)
    MergeManifestStore(fileSystem, appDataDir).expire(nowEpochMs)
}
