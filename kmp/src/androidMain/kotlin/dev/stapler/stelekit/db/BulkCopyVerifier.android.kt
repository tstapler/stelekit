// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import android.os.StatFs
import dev.stapler.stelekit.error.DomainError

/**
 * Android's [InsufficientSpaceCheck] (Task 3.1.1d): [StatFs.availableBytes] on the volume backing
 * [destinationRootPath], mirroring `AndroidGitRepository`'s existing `MIN_SHADOW_FREE_BYTES`/
 * `StatFs` pre-flight pattern (`AndroidGitRepository.kt:112-125,805`) for the same class of
 * failure — fail fast with a diagnosable typed error instead of a raw mid-copy `IOException`.
 *
 * [destinationRootPath] must already exist (`StatFs` requires a real path on the target volume);
 * callers check against the staging directory's parent, which is created before the copy begins.
 */
class AndroidInsufficientSpaceCheck : InsufficientSpaceCheck {
    override suspend fun check(
        destinationRootPath: String,
        requiredBytes: Long,
    ): DomainError.StorageError.InsufficientSpace? {
        val available = StatFs(destinationRootPath).availableBytes
        if (available >= requiredBytes) return null
        return DomainError.StorageError.InsufficientSpace(requiredBytes, available)
    }
}
