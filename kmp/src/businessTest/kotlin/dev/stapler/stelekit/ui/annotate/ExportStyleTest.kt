// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.error.DomainError
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportStyleTest {
    @Test
    fun scale_should_BeFourTimesLarger_When_WidthIsFourTimes() {
        assertEquals(4f, exportScale(4000) / exportScale(1000), 0.05f * 4f)
        assertEquals(1f, exportScale(1000))
    }

    @Test
    fun scale_should_NeverCollapseBelowMinimum() = runTest {
        checkAll(Arb.int(1..20_000)) { w -> assertTrue(exportScale(w) >= 0.5f) }
    }

    @Test
    fun message_should_SayOffline_When_TransportFailsOrTimesOut() {
        assertEquals(DRIVE_OFFLINE_MESSAGE, driveExportMessage(DomainError.NetworkError.HttpError(-1, "x")))
        assertEquals(DRIVE_OFFLINE_MESSAGE, driveExportMessage(DomainError.NetworkError.Timeout("x")))
    }

    @Test
    fun message_should_AskSignIn_When_Unauthorized() {
        assertEquals("$DRIVE_SIGN_IN_MESSAGE (x)", driveExportMessage(DomainError.NetworkError.HttpError(401, "x")))
    }

    @Test
    fun message_should_BeSpecific_When_PassThroughOtherwise() {
        assertEquals("oom", driveExportMessage(DomainError.ExportError.EncodingFailed("oom")))
        assertEquals("quota", driveExportMessage(DomainError.NetworkError.HttpError(507, "quota")))
    }
}
