// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditLockTest {
    @Test
    fun `isEditing reflects beginEdit and endEdit immediately on the calling thread`() {
        val lock = EditLock()
        repeat(2_000) { i ->
            lock.beginEdit()
            assertTrue(lock.isEditing.value, "isEditing lagged beginEdit on iteration $i")
            lock.endEdit()
            assertFalse(lock.isEditing.value, "isEditing lagged endEdit on iteration $i")
        }
    }

    @Test
    fun `isEditing stays true until every nested edit ends`() {
        val lock = EditLock()
        lock.beginEdit()
        lock.beginEdit()
        lock.endEdit()
        assertTrue(lock.isEditing.value)
        lock.endEdit()
        assertFalse(lock.isEditing.value)
    }
}
