// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.RetentionPolicy
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.util.ContentHasher

/**
 * In-memory [ProjectSidecarStore] for saver tests: files live in [files], every write first runs [latency] (a virtual-time delay supplied by the test),
 * the number of overlapping writes is recorded in [maxConcurrentWrites], and [nextOutcome] scripts the next write result.
 */
class ScriptedSidecarStore(var latency: suspend () -> Unit = {}, val onWrite: () -> Unit = {}) : ProjectSidecarStore {
    val files = linkedMapOf<String, ByteArray>()
    val writes = mutableListOf<Map<String, ByteArray>>()
    val conflictCopies = mutableListOf<ByteArray>()
    var nextOutcome: SidecarWriteOutcome? = null
    var maxConcurrentWrites = 0
        private set
    private var inFlight = 0

    override suspend fun read(slug: String): SidecarRead = if (files.isEmpty()) SidecarRead.Absent else SidecarRead.Present(files.toMap())

    override suspend fun write(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome =
        writeNow(slug, files, opaque)

    override suspend fun writeNow(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome {
        inFlight++
        maxConcurrentWrites = maxOf(maxConcurrentWrites, inFlight)
        try {
            latency()
            nextOutcome?.let { scripted -> nextOutcome = null; return scripted }
            val stale = opaque.any { (path, sha) -> this.files[path]?.let(ContentHasher::sha256) != sha }
            if (stale) return SidecarWriteOutcome.StaleOpaque
            writes += files
            onWrite()
            this.files.putAll(files)
            return SidecarWriteOutcome.Written
        } finally {
            inFlight--
        }
    }

    override suspend fun writeConflict(slug: String, files: Map<String, ByteArray>): Either<DomainError, String> = "conflict".right()

    override suspend fun writeSourceSnapshot(slug: String, hash8: String, bytes: ByteArray): SidecarWriteOutcome = SidecarWriteOutcome.Written

    override suspend fun readSourceSnapshot(slug: String, hash8: String): ByteArray? = null

    override suspend fun writeBackup(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = "backup".right()

    override suspend fun writeSheetConflictCopy(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> {
        conflictCopies += bytes
        return "conflicts/$sheetUuid.v2.conflict-${ContentHasher.sha256(bytes).take(HASH8)}.json".right()
    }

    override suspend fun pruneConflictCopies(slug: String, sheetUuid: String, unresolved: Set<String>): Either<DomainError, Int> = 0.right()

    override suspend fun retentionSize(slug: String): RetentionPolicy.Size = RetentionPolicy.Size(0, 0, 0, 0)

    override suspend fun deleteV2Sheet(slug: String, sheetUuid: String): Either<DomainError, Unit> =
        DomainError.FileSystemError.DeleteFailed(sheetUuid, "not scripted").left()

    override suspend fun readV2SheetFile(slug: String, sheetUuid: String): ByteArray? = files["sheets/$sheetUuid.v2.json"]

    override suspend fun flushPending(): Int = 0

    override suspend fun slugsOnDisk(): List<String> = emptyList()

    private companion object {
        const val HASH8 = 8
    }
}
