// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

class JvmMediaAttachmentServiceTest {
    @Test fun `attachBytes should return null on JVM because the platform does not override the default`() = runTest {
        val service = JvmMediaAttachmentService()

        val result = service.attachBytes(byteArrayOf(1, 2, 3), "x.png", "/tmp/g")

        assertNull(result)
    }
}
