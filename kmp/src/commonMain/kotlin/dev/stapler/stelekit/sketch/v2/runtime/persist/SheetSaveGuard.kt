// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.util.ContentHasher

/** What comparing the on-disk sheet with the sha the saver last loaded or wrote found. */
sealed interface BaseCheck {
    /** The file is what the saver last saw; saving may replace it. */
    data object Match : BaseCheck

    /** The file changed underneath the saver (or vanished, or is unreadable): [diskSha] is its sha, null when there is none. */
    data class Changed(val diskSha: String?) : BaseCheck
}

/**
 * The compare-and-swap rule of the saver (Story 1.4.4): a save may replace the sheet file only when the bytes on disk
 * still hash to `baseSha`, the sha of the file it loaded or last wrote. A sheet that was never saved (`baseSha == null`)
 * may be written only where no different file exists. Pure; the caller holds the slug lock across check and write.
 */
object SheetSaveGuard {
    fun sha(bytes: ByteArray): String = ContentHasher.sha256(bytes)

    fun check(baseSha: String?, diskBytes: ByteArray?, newSha: String): BaseCheck {
        val diskSha = diskBytes?.let(::sha)
        return when {
            baseSha == null && (diskSha == null || diskSha == newSha) -> BaseCheck.Match
            baseSha != null && diskSha == baseSha -> BaseCheck.Match
            else -> BaseCheck.Changed(diskSha)
        }
    }

    /**
     * Keeps the in-memory scene [bytes] beside the changed file as `<slug>/conflicts/<sheet>.v2.conflict-<hash8>.json`
     * (never under `sheets/`, so it is neither scanned nor hashed). Returns the path.
     */
    suspend fun writeConflictCopy(store: ProjectSidecarStore, slug: String, sheet: SheetUuid, bytes: ByteArray): Either<DomainError, String> =
        store.writeSheetConflictCopy(slug, sheet.value, bytes)
}
