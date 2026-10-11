// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.cache.PlatformLock
import dev.stapler.stelekit.cache.withLock
import dev.stapler.stelekit.model.GraphId

/** One run's spilled source pages, indexed from 0. */
interface MergeStaging {
    /** Human-readable location for error messages. */
    val label: String

    fun writePage(index: Int, page: MergePage): Either<MergeStorageError, Unit>

    /** Lazy, ascending by page index. */
    fun readAll(): Sequence<Either<StagedPageError, MergePage>>

    fun readPage(index: Int): MergePage?

    fun pageCount(): Int

    fun delete(): Either<MergeStorageError, Unit>
}

interface MergeStagingStore {
    fun create(
        mergeId: MergeId,
        sourceGraphId: GraphId,
        targetGraphId: GraphId,
        startedAtEpochMs: Long,
    ): Either<MergeStorageError, MergeStaging>

    /** Re-opens a staging area left by an earlier run; null if absent. */
    fun open(mergeId: MergeId): MergeStaging?
}

/** Append-only record of one run's writes; each call is durable before it returns. */
interface MergeManifestLog {
    fun appendPage(entry: ManifestPageEntry): Either<MergeStorageError, Unit>

    fun complete(): Either<MergeStorageError, Unit>
}

interface MergeManifests {
    fun begin(
        mergeId: MergeId,
        sourceGraphId: String,
        targetGraphId: String,
        startedAtEpochMs: Long,
    ): Either<MergeStorageError, MergeManifestLog>

    /** Re-attach to an existing manifest to keep appending (resume). */
    fun writerFor(mergeId: MergeId): MergeManifestLog?

    fun load(mergeId: MergeId): MergeManifest?

    /** Runs whose manifest never reached Complete (process death mid-copy). */
    fun findInterrupted(): List<MergeManifest>

    fun delete(mergeId: MergeId): Either<MergeStorageError, Unit>
}

/** What `PageMergeService` needs to stage pages and journal writes. */
open class MergeStorage(val staging: MergeStagingStore, val manifests: MergeManifests)

/** Disk-backed storage under [appDataDir] (Desktop/Android). */
fun okioMergeStorage(fileSystem: okio.FileSystem, appDataDir: String): MergeStorage =
    MergeStorage(OkioMergeStagingStore(fileSystem, appDataDir), MergeManifestStore(fileSystem, appDataDir))

class OkioMergeStagingStore(
    private val fileSystem: okio.FileSystem,
    private val appDataDir: String,
) : MergeStagingStore {
    override fun create(
        mergeId: MergeId,
        sourceGraphId: GraphId,
        targetGraphId: GraphId,
        startedAtEpochMs: Long,
    ): Either<MergeStorageError, MergeStaging> =
        MergeStagingDirectory.create(fileSystem, appDataDir, mergeId, sourceGraphId, targetGraphId, startedAtEpochMs)

    override fun open(mergeId: MergeId): MergeStaging? = MergeStagingDirectory.open(fileSystem, appDataDir, mergeId)
}

/**
 * iOS/Web storage: there is no app-data directory, so the current run lives in memory. It holds one
 * run at a time (a new [MergeStagingStore.create] replaces the previous one) and the service clears
 * staging on completion/cancel. Nothing survives a restart, so [MergeManifests.findInterrupted] is
 * always empty: the "interrupted copy / Resume" path is a deliberate no-op here, there being nothing
 * to resume from. Undo works for the latest run of the current session only.
 */
class InMemoryMergeStorage : MergeStorage(InMemoryStagingStore(), InMemoryManifests())

private class InMemoryStaging(override val label: String) : MergeStaging {
    private val lock = PlatformLock()
    private val pages = HashMap<Int, MergePage>()

    override fun writePage(index: Int, page: MergePage): Either<MergeStorageError, Unit> {
        if (index < 0) return MergeStorageError("negative page index").left()
        lock.withLock { pages[index] = page }
        return Unit.right()
    }

    override fun readAll(): Sequence<Either<StagedPageError, MergePage>> {
        val indices = lock.withLock { pages.keys.sorted() }
        return indices.asSequence().map { i ->
            readPage(i)?.right() ?: StagedPageError.Malformed("missing staged page $i").left()
        }
    }

    override fun readPage(index: Int): MergePage? = lock.withLock { pages[index] }

    override fun pageCount(): Int = lock.withLock { pages.size }

    override fun delete(): Either<MergeStorageError, Unit> {
        lock.withLock { pages.clear() }
        return Unit.right()
    }
}

private class InMemoryStagingStore : MergeStagingStore {
    private val lock = PlatformLock()
    private var current: Pair<String, InMemoryStaging>? = null

    override fun create(
        mergeId: MergeId,
        sourceGraphId: GraphId,
        targetGraphId: GraphId,
        startedAtEpochMs: Long,
    ): Either<MergeStorageError, MergeStaging> {
        if (!isSafeMergeIdToken(mergeId.value)) return MergeStorageError("invalid merge id").left()
        val staging = InMemoryStaging("memory:${mergeId.value}")
        lock.withLock {
            current?.second?.delete()
            current = mergeId.value to staging
        }
        return staging.right()
    }

    override fun open(mergeId: MergeId): MergeStaging? =
        lock.withLock { current?.takeIf { it.first == mergeId.value }?.second }
}

private class InMemoryManifests : MergeManifests {
    val lock = PlatformLock()
    private var current: Pair<MergeId, Log>? = null

    class Log(
        private val mergeId: String,
        private val sourceGraphId: String,
        private val targetGraphId: String,
        private val startedAtEpochMs: Long,
        private val lock: PlatformLock,
    ) : MergeManifestLog {
        private val pages = ArrayList<ManifestPageEntry>()
        private var complete = false

        override fun appendPage(entry: ManifestPageEntry): Either<MergeStorageError, Unit> {
            lock.withLock { pages += entry }
            return Unit.right()
        }

        override fun complete(): Either<MergeStorageError, Unit> {
            lock.withLock { complete = true }
            return Unit.right()
        }

        fun snapshot(): MergeManifest = lock.withLock {
            MergeManifest(
                mergeId, sourceGraphId, targetGraphId, startedAtEpochMs,
                if (complete) MergeStatus.Complete else MergeStatus.InProgress, pages.toList(),
            )
        }
    }

    override fun begin(
        mergeId: MergeId,
        sourceGraphId: String,
        targetGraphId: String,
        startedAtEpochMs: Long,
    ): Either<MergeStorageError, MergeManifestLog> {
        if (!isSafeMergeIdToken(mergeId.value)) return MergeStorageError("invalid merge id").left()
        val log = Log(mergeId.value, sourceGraphId, targetGraphId, startedAtEpochMs, lock)
        lock.withLock { current = mergeId to log }
        return log.right()
    }

    override fun writerFor(mergeId: MergeId): MergeManifestLog? =
        lock.withLock { current?.takeIf { it.first == mergeId }?.second }

    override fun load(mergeId: MergeId): MergeManifest? =
        lock.withLock { current?.takeIf { it.first == mergeId }?.second }?.snapshot()

    override fun findInterrupted(): List<MergeManifest> = emptyList()

    override fun delete(mergeId: MergeId): Either<MergeStorageError, Unit> {
        lock.withLock { if (current?.first == mergeId) current = null }
        return Unit.right()
    }
}
