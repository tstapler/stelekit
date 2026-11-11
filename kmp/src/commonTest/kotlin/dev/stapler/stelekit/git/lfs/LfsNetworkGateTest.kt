// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LFS defers through the same [dev.stapler.stelekit.jobs.schedule.NetworkGate] as scheduled work. */
class LfsNetworkGateTest {
    private val defer = LfsTransferPolicy(deferOnMetered = true)
    private val noDefer = LfsTransferPolicy(deferOnMetered = false)

    @Test
    fun defers_should_BeTrue_When_PolicyDefersAndNetworkIsMetered() {
        assertTrue(defer.defers(meteredDetectionSupported = true, isMetered = true))
    }

    @Test
    fun defers_should_BeTrue_When_PolicyDefersAndMeteringIsUnknownOnADetectingPlatform() {
        assertTrue(defer.defers(meteredDetectionSupported = true, isMetered = null))
    }

    @Test
    fun defers_should_BeFalse_When_PolicyDefersAndNetworkIsUnmetered() {
        assertFalse(defer.defers(meteredDetectionSupported = true, isMetered = false))
    }

    @Test
    fun defers_should_BeFalse_When_DetectionIsUnsupported_ForEveryReading() {
        for (m in listOf(true, false, null)) {
            assertFalse(defer.defers(meteredDetectionSupported = false, isMetered = m), "desktop never defers (isMetered=$m)")
        }
    }

    @Test
    fun defers_should_BeFalse_When_PolicyDoesNotDefer_ForEveryReading() {
        for (supported in listOf(true, false)) for (m in listOf(true, false, null)) {
            assertFalse(noDefer.defers(supported, m))
        }
    }

    @Test
    fun deferralStatus_should_ShareTheNetworkPolicyCopy() {
        assertEquals(NetworkPolicyCopy.WAITING_FOR_UNMETERED, LFS_DEFERRAL_STATUS)
    }
}
