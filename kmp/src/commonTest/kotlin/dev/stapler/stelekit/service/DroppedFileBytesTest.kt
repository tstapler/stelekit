// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DroppedFileBytesTest {
    @Test fun `DroppedFileBytes should expose the suggestedName and bytes it was constructed with`() {
        val bytes = byteArrayOf(1, 2, 3)
        val dropped = DroppedFileBytes(suggestedName = "photo.png", bytes = bytes)

        assertEquals("photo.png", dropped.suggestedName)
        assertContentEquals(bytes, dropped.bytes)
    }
}
