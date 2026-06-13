// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * iOS stub for [MediaAttachmentService].
 *
 * Full implementation requires [PHPickerViewController] via Kotlin/Native ObjC interop —
 * deferred to a follow-up iteration. Consider adopting the `ImagePickerKMP` library
 * (io.github.ismoy:imagepickerkmp) to avoid hand-rolling the ObjC callback bridge.
 *
 * See plan.md pitfalls for: PHPickerViewController vs UIImagePickerController, thread safety,
 * NSCameraUsageDescription requirement, and security-scoped URL handling.
 */
class IosMediaAttachmentService : MediaAttachmentService {
    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? {
        return null
    }
}
