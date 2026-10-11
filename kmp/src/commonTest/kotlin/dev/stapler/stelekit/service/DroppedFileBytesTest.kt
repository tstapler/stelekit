// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DroppedFileBytesTest {
    @Test fun `DroppedFileBytes should expose the suggestedName and bytes it was constructed with`() {
        val bytes = byteArrayOf(1, 2, 3)
        val dropped = DroppedFileBytes(suggestedName = "photo.png", bytes = bytes)

        assertEquals("photo.png", dropped.suggestedName)
        assertContentEquals(bytes, dropped.bytes)
    }

    @Test fun `DroppedFileBytes should compare equal for distinct instances with the same name and byte content`() {
        val a = DroppedFileBytes("photo.png", byteArrayOf(1, 2, 3))
        val b = DroppedFileBytes("photo.png", byteArrayOf(1, 2, 3))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun `DroppedFileBytes should compare unequal when name or bytes differ`() {
        val original = DroppedFileBytes("photo.png", byteArrayOf(1, 2, 3))

        assertNotEquals(original, DroppedFileBytes("other.png", byteArrayOf(1, 2, 3)))
        assertNotEquals(original, DroppedFileBytes("photo.png", byteArrayOf(1, 2, 4)))
    }
}
