// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmProviderRegistryTest {

    private class FakeProvider(
        override val id: String,
        override val kind: LlmProviderKind,
        private val availability: LlmProviderAvailability,
        override val supportsLongFormOutput: Boolean = true,
    ) : LlmProvider {
        override val displayName: String = id
        override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> LlmResult.Success("unused") }
        override suspend fun checkAvailability(): LlmProviderAvailability = availability
    }

    private val remoteAvailable = FakeProvider("anthropic", LlmProviderKind.REMOTE, LlmProviderAvailability.Available)
    private val remoteUnavailable = FakeProvider(
        "openai", LlmProviderKind.REMOTE, LlmProviderAvailability.Unavailable("no key"),
    )
    private val onDeviceAvailable = FakeProvider(
        "android-ondevice", LlmProviderKind.ON_DEVICE, LlmProviderAvailability.Available,
        supportsLongFormOutput = false,
    )
    private val onDevicePreparing = FakeProvider(
        "android-ondevice", LlmProviderKind.ON_DEVICE, LlmProviderAvailability.Preparing("downloading"),
        supportsLongFormOutput = false,
    )

    // ─── Table-driven availableForFeature matrix ────────────────────────────────

    private data class Case(
        val name: String,
        val providers: List<LlmProvider>,
        val excludeShortFormOnly: Boolean,
        val expectedIds: Set<String>,
    )

    private val cases = listOf(
        Case(
            name = "all unavailable -> empty",
            providers = listOf(remoteUnavailable),
            excludeShortFormOnly = false,
            expectedIds = emptySet(),
        ),
        Case(
            name = "on-device-only available + excludeShortFormOnly=true -> empty",
            providers = listOf(onDeviceAvailable),
            excludeShortFormOnly = true,
            expectedIds = emptySet(),
        ),
        Case(
            name = "on-device-only available + excludeShortFormOnly=false -> contains it",
            providers = listOf(onDeviceAvailable),
            excludeShortFormOnly = false,
            expectedIds = setOf("android-ondevice"),
        ),
        Case(
            name = "mixed remote+on-device available -> both",
            providers = listOf(remoteAvailable, onDeviceAvailable),
            excludeShortFormOnly = false,
            expectedIds = setOf("anthropic", "android-ondevice"),
        ),
        Case(
            name = "preparing on-device counts as usable/soon-usable",
            providers = listOf(onDevicePreparing),
            excludeShortFormOnly = false,
            expectedIds = setOf("android-ondevice"),
        ),
        Case(
            name = "mixed available+unavailable -> only available",
            providers = listOf(remoteAvailable, remoteUnavailable),
            excludeShortFormOnly = false,
            expectedIds = setOf("anthropic"),
        ),
    )

    @Test
    fun `availableForFeature_should_MatchExpected_ForEachTableDrivenCase`() = runTest {
        for (case in cases) {
            val registry = LlmProviderRegistry(case.providers)
            val result = registry.availableForFeature(LlmFeature.GRAPH_EDIT_SYNTHESIS, case.excludeShortFormOnly)
            assertEquals(case.expectedIds, result.map { it.id }.toSet(), "case: ${case.name}")
        }
    }

    @Test
    fun `availableForFeature_should_ReturnEmpty_When_AllProvidersUnavailable`() = runTest {
        val registry = LlmProviderRegistry(listOf(remoteUnavailable))
        assertTrue(registry.availableForFeature(LlmFeature.TAG_SUGGESTION).isEmpty())
    }

    @Test
    fun `availableForFeature_should_ExcludeShortFormOnly_When_ExcludeFlagTrue_AndOnDeviceOnlyAvailable`() = runTest {
        val registry = LlmProviderRegistry(listOf(onDeviceAvailable))
        val result = registry.availableForFeature(LlmFeature.GRAPH_EDIT_SYNTHESIS, excludeShortFormOnly = true)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `availableForFeature_should_IncludeOnDevice_When_ExcludeFlagFalse`() = runTest {
        val registry = LlmProviderRegistry(listOf(onDeviceAvailable))
        val result = registry.availableForFeature(LlmFeature.TAG_SUGGESTION, excludeShortFormOnly = false)
        assertEquals(listOf("android-ondevice"), result.map { it.id })
    }

    @Test
    fun `availableForFeature_should_ReturnBoth_When_RemoteAndOnDeviceAvailable`() = runTest {
        val registry = LlmProviderRegistry(listOf(remoteAvailable, onDeviceAvailable))
        val result = registry.availableForFeature(LlmFeature.VOICE_FORMATTING)
        assertEquals(setOf("anthropic", "android-ondevice"), result.map { it.id }.toSet())
    }

    // ─── all() / find() ──────────────────────────────────────────────────────────

    @Test
    fun `all_should_ReturnEveryRegisteredProvider_RegardlessOfAvailability`() {
        val registry = LlmProviderRegistry(listOf(remoteAvailable, remoteUnavailable))
        assertEquals(setOf("anthropic", "openai"), registry.all().map { it.id }.toSet())
    }

    @Test
    fun `find_should_ReturnNull_When_IdNotRegistered`() {
        val registry = LlmProviderRegistry(listOf(remoteAvailable))
        assertEquals(null, registry.find("does-not-exist"))
    }

    @Test
    fun `find_should_ReturnProvider_When_IdRegistered`() {
        val registry = LlmProviderRegistry(listOf(remoteAvailable))
        assertEquals(remoteAvailable, registry.find("anthropic"))
    }
}
