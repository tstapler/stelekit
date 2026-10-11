// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecretRedactionTest {
    @Test
    fun `userinfo and query tokens are redacted`() {
        val out = redactSecrets("cannot open https://user:tok@host/x?token=abc&a=b timed out")
        assertEquals("cannot open https://host/x?token=<redacted>&a=<redacted> timed out", out)
        assertFalse("tok@" in out || "abc" in out)
    }

    @Test
    fun `text without secrets is unchanged`() {
        assertEquals("Connection refused", redactSecrets("Connection refused"))
    }
}
