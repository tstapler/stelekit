// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await

private fun storageEstimateSupported(): Boolean =
    js("typeof navigator.storage !== 'undefined' && typeof navigator.storage.estimate === 'function'")

private fun storageEstimatePromise(): kotlin.js.Promise<JsAny> = js("navigator.storage.estimate()")
private fun estimateQuota(estimate: JsAny): Double = js("estimate.quota || 0")
private fun estimateUsage(estimate: JsAny): Double = js("estimate.usage || 0")

/**
 * Web's [InsufficientSpaceCheck] (Task 3.1.1d): `navigator.storage.estimate()` — no `StatFs`
 * equivalent exists on the web platform, and no space-check primitive existed here before this
 * (`research/features.md` §4). `estimate()` reports origin-wide quota/usage, not a per-directory
 * figure — [destinationRootPath] is unused, mirroring the File System Access/OPFS model where
 * quota isn't scoped below the origin.
 *
 * Best-effort: an unsupported browser or a rejected promise proceeds rather than blocking the
 * copy, mirroring `HostDirectoryInterop.requestStoragePersistence`'s never-throws convention.
 */
class WasmJsInsufficientSpaceCheck : InsufficientSpaceCheck {
    override suspend fun check(
        destinationRootPath: String,
        requiredBytes: Long,
    ): DomainError.StorageError.InsufficientSpace? {
        if (!storageEstimateSupported()) return null
        return try {
            val estimate: JsAny = storageEstimatePromise().await()
            val available = (estimateQuota(estimate) - estimateUsage(estimate)).toLong()
            if (available >= requiredBytes) null else DomainError.StorageError.InsufficientSpace(requiredBytes, available)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }
}
