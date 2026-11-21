// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin

/**
 * Runs [block] as the single holder of [key] for callers outside [GitSyncService] (setup test, clone): a held key
 * returns `SyncInProgress` without running [block], an unresolvable key ([key] null) returns [unresolved] and
 * runs nothing. A press that queued a follow-up against this holder is dropped on release; it was already refused.
 */
internal suspend fun <T> RepoOpGate.withExclusiveLease(
    key: String?,
    origin: SyncOrigin,
    unresolved: DomainError.GitError,
    block: suspend () -> Either<DomainError.GitError, T>,
): Either<DomainError.GitError, T> {
    if (key == null) return unresolved.left()
    val lease = tryAcquire(key, origin) ?: return DomainError.GitError.SyncInProgress(heldBy(key)).left()
    try {
        return block()
    } finally {
        var next = releaseOrHandOff(lease)
        while (next != null) next = releaseOrHandOff(next.lease)
    }
}
