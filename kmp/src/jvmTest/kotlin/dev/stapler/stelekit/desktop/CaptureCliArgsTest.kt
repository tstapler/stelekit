// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.desktop

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureCliArgsTest {

    @Test
    fun parseCaptureArgs_should_ReturnCapturedText_When_CaptureTextFlagPresent() {
        val result = parseCaptureArgs(arrayOf("--capture-text", "Remember to call mom"))

        assertEquals("Remember to call mom", result)
    }

    @Test
    fun parseCaptureArgs_should_ReturnNull_When_CaptureTextFlagAbsentOrMissingValue() {
        assertNull(parseCaptureArgs(arrayOf()))
        assertNull(parseCaptureArgs(arrayOf("--other-flag", "value")))
        assertNull(parseCaptureArgs(arrayOf("--capture-text")))
    }
}
