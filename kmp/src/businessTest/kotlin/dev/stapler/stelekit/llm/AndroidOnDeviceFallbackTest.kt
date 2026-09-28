// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the registry-level plumbing for the Android on-device provider is correct in isolation
 * (plan.md Story 4.3/Task 4.3a) — a fake [LlmProvider] stands in for the real
 * `AndroidOnDeviceLlmProvider` so this test has no real ML Kit SDK dependency and runs in
 * `businessTest`, not `androidUnitTest`.
 *
 * This is *not* the consumer-level wiring (`LlmTagProvider` actually calling the registry to
 * pick a provider for `TAG_SUGGESTION`) — that migration is Epic 8 Story 8.2's job. This test
 * only proves `LlmProviderRegistry.availableForFeature(TAG_SUGGESTION)` returns/omits the
 * on-device provider correctly given its availability state.
 */
class AndroidOnDeviceFallbackTest {

    private class FakeProvider(
        override val id: String,
        override val kind: LlmProviderKind,
        private val availability: LlmProviderAvailability,
    ) : LlmProvider {
        override val displayName: String = id
        override val supportsLongFormOutput: Boolean = kind != LlmProviderKind.ON_DEVICE
        override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> LlmResult.Success("unused") }
        override suspend fun checkAvailability(): LlmProviderAvailability = availability
    }

    private fun onDeviceProvider(availability: LlmProviderAvailability) =
        FakeProvider("android-ondevice", LlmProviderKind.ON_DEVICE, availability)

    private fun remoteProvider(availability: LlmProviderAvailability = LlmProviderAvailability.Available) =
        FakeProvider("anthropic", LlmProviderKind.REMOTE, availability)

    @Test
    fun `availableForFeature_should_ContainOnDevice_When_NoRemoteKeyConfigured_AndOnDeviceAvailable`() = runTest {
        val registry = LlmProviderRegistry(listOf(onDeviceProvider(LlmProviderAvailability.Available)))

        val result = registry.availableForFeature(LlmFeature.TAG_SUGGESTION)

        assertEquals(listOf("android-ondevice"), result.map { it.id })
    }

    @Test
    fun `availableForFeature_should_ReturnEmpty_When_NoRemoteKeyConfigured_AndOnDeviceUnavailable`() = runTest {
        val registry = LlmProviderRegistry(
            listOf(onDeviceProvider(LlmProviderAvailability.Unavailable("unsupported device")))
        )

        val result = registry.availableForFeature(LlmFeature.TAG_SUGGESTION)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `availableForFeature_should_ContainBoth_When_RemoteKeyPresent_AndOnDeviceAlsoAvailable`() = runTest {
        val registry = LlmProviderRegistry(
            listOf(remoteProvider(), onDeviceProvider(LlmProviderAvailability.Available))
        )

        val result = registry.availableForFeature(LlmFeature.TAG_SUGGESTION)

        // Ordering is left to the caller (Epic 8 decides precedence, not the registry) — assert
        // membership, not order.
        assertEquals(setOf("anthropic", "android-ondevice"), result.map { it.id }.toSet())
    }

    @Test
    fun `availableForFeature_should_ContainOnDevice_When_OnDevicePreparing`() = runTest {
        val registry = LlmProviderRegistry(
            listOf(onDeviceProvider(LlmProviderAvailability.Preparing("downloading")))
        )

        val result = registry.availableForFeature(LlmFeature.TAG_SUGGESTION)

        assertEquals(listOf("android-ondevice"), result.map { it.id })
    }
}
