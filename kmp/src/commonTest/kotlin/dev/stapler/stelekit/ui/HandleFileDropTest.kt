// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.DroppedFileBytes
import dev.stapler.stelekit.service.MediaAttachmentService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for [handleFileDrop] (App.kt) — the per-file drag-and-drop attach dispatch logic
 * extracted from `GraphContent`'s `onFileDrop` wiring during the PR #361 Gate-2 review, since it
 * previously had zero direct test coverage trapped inside a Compose closure.
 */
class HandleFileDropTest {

    private class FakeMediaAttachmentService(
        private val attachBytesResult: Either<DomainError, AttachmentResult>? = null,
        private val attachFilePathResult: Either<DomainError, AttachmentResult>? = null,
    ) : MediaAttachmentService {
        var attachBytesCalls = 0
        var attachFilePathCalls = 0
        var lastAttachFilePath: String? = null

        override suspend fun pickAndAttach(
            graphRoot: String,
            pageRelativePath: String,
        ): Either<DomainError, AttachmentResult>? = null

        override suspend fun attachFilePath(filePath: String, graphRoot: String): Either<DomainError, AttachmentResult>? {
            attachFilePathCalls++
            lastAttachFilePath = filePath
            return attachFilePathResult
        }

        override suspend fun attachBytes(
            bytes: ByteArray,
            suggestedName: String,
            graphRoot: String,
        ): Either<DomainError, AttachmentResult>? {
            attachBytesCalls++
            return attachBytesResult
        }
    }

    @Test
    fun handleFileDrop_attachesDroppedFileBytes_andInvokesOnAttachedWithMarkdown() = runTest {
        val service = FakeMediaAttachmentService(
            attachBytesResult = AttachmentResult(relativePath = "../assets/photo.png", displayName = "photo.png").right(),
        )
        var attachedMarkdown: String? = null
        var errorSeen: DomainError? = null

        handleFileDrop(
            files = listOf(DroppedFileBytes("photo.png", byteArrayOf(1, 2, 3))),
            attachmentService = service,
            graphRoot = "/graph",
            onAttached = { attachedMarkdown = it },
            onError = { errorSeen = it },
        )

        assertEquals(1, service.attachBytesCalls)
        assertEquals("![photo.png](../assets/photo.png)", attachedMarkdown)
        assertNull(errorSeen)
    }

    @Test
    fun handleFileDrop_dispatchesNonBytesFileThroughAttachFilePath_andSkipsSilentlyWhenUnsupported() = runTest {
        val service = FakeMediaAttachmentService(attachFilePathResult = null)
        var attachedMarkdown: String? = null
        var errorSeen: DomainError? = null

        handleFileDrop(
            files = listOf("some-native-file-token"),
            attachmentService = service,
            graphRoot = "/graph",
            onAttached = { attachedMarkdown = it },
            onError = { errorSeen = it },
        )

        assertEquals(1, service.attachFilePathCalls)
        assertEquals("some-native-file-token", service.lastAttachFilePath)
        assertNull(attachedMarkdown)
        assertNull(errorSeen)
    }

    @Test
    fun handleFileDrop_invokesOnError_whenAttachFails() = runTest {
        val error = DomainError.AttachmentError.CopyFailed("disk full")
        val service = FakeMediaAttachmentService(attachBytesResult = error.left())
        var attachedMarkdown: String? = null
        var errorSeen: DomainError? = null

        handleFileDrop(
            files = listOf(DroppedFileBytes("photo.png", byteArrayOf(1, 2, 3))),
            attachmentService = service,
            graphRoot = "/graph",
            onAttached = { attachedMarkdown = it },
            onError = { errorSeen = it },
        )

        assertEquals(error, errorSeen)
        assertNull(attachedMarkdown)
    }
}
