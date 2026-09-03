// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureEnrichmentCoordinatorTest {

    // Tests that need a genuinely-built matcher (real Dispatchers.Default background thread +
    // runBlocking) live in jvmTest's CaptureEnrichmentCoordinatorRealDispatcherTest — wasmJs has
    // no runBlocking (single-threaded runtime can't block), so they can't live in commonTest.

    private fun makeCoordinator(
        scope: CoroutineScope,
        pageRepo: InMemoryPageRepository = InMemoryPageRepository(),
        topicEnricher: TopicEnricher = NoOpTopicEnricher(),
    ) = CaptureEnrichmentCoordinator(pageRepo, scope, topicEnricher)

    // -------------------------------------------------------------------------
    // scan() — matcher-not-ready cases (no background build needed, TestDispatcher is fine)
    // -------------------------------------------------------------------------

    @Test
    fun scan_matcherNotBuilt_returnsMatcherNotReadyImmediately() = runTest(UnconfinedTestDispatcher()) {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            // No pages saved — matcher never builds (PageNameIndex leaves it null).
            val coordinator = makeCoordinator(scope)
            val outcome = coordinator.scan("Meeting notes")
            assertEquals(ScanOutcome.MatcherNotReady, outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scan_matcherNotReady_shortCircuitsWithoutInvokingWithTimeoutOrNull() = runTest(UnconfinedTestDispatcher()) {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val coordinator = makeCoordinator(scope)
            // budgetMs = 0 would fail withTimeoutOrNull immediately if it were entered at all;
            // the matcher-null short-circuit must return before that timeout is ever applied.
            val outcome = coordinator.scan("Meeting notes", budgetMs = 0)
            assertEquals(ScanOutcome.MatcherNotReady, outcome)
        } finally {
            scope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // enhance()
    // -------------------------------------------------------------------------

    private class FakeTopicEnricher(
        private val result: suspend () -> List<TopicSuggestion>,
    ) : TopicEnricher {
        override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>) = result()
    }

    @Test
    fun enhance_malformedProviderOutput_sanitizesBlankDuplicateAndOutOfRangeConfidence() = runTest(UnconfinedTestDispatcher()) {
        val malformed = listOf(
            TopicSuggestion(term = "  ", confidence = 47f, source = TopicSuggestion.Source.AI_ENHANCED),
            TopicSuggestion(term = "Kotlin", confidence = 0.9f, source = TopicSuggestion.Source.AI_ENHANCED),
            TopicSuggestion(term = "kotlin", confidence = 0.3f, source = TopicSuggestion.Source.AI_ENHANCED),
        )
        val enricher = FakeTopicEnricher { malformed }
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val coordinator = makeCoordinator(scope, topicEnricher = enricher)
            val result = coordinator.enhance("text", emptyList())

            // Blank term dropped; "kotlin" dropped as a case-insensitive duplicate of "Kotlin";
            // the surviving "Kotlin" entry's already-in-range confidence (0.9f) is left as-is
            // by coerceIn(0f, 1f) — sanitize() only clamps values that fall outside [0,1].
            assertEquals(1, result.size)
            assertEquals("Kotlin", result[0].term)
            assertEquals(0.9f, result[0].confidence)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun enhance_outOfRangeConfidence_isClampedTo0f1f() = runTest(UnconfinedTestDispatcher()) {
        val malformed = listOf(TopicSuggestion(term = "Overconfident", confidence = 47f, source = TopicSuggestion.Source.AI_ENHANCED))
        val enricher = FakeTopicEnricher { malformed }
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val coordinator = makeCoordinator(scope, topicEnricher = enricher)
            val result = coordinator.enhance("text", emptyList())
            assertEquals(1, result.size)
            assertEquals(1f, result[0].confidence)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun enhance_noOpTopicEnricher_returnsLocalUnchanged() = runTest(UnconfinedTestDispatcher()) {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val coordinator = makeCoordinator(scope, topicEnricher = NoOpTopicEnricher())
            val local = listOf(TopicSuggestion(term = "Local", confidence = 0.5f, source = TopicSuggestion.Source.LOCAL))
            val result = coordinator.enhance("text", local)
            assertEquals(local, result)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun enhance_enricherThrows_degradesToLocalSuggestions() = runTest(UnconfinedTestDispatcher()) {
        val enricher = FakeTopicEnricher { throw IllegalStateException("boom") }
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val coordinator = makeCoordinator(scope, topicEnricher = enricher)
            val local = listOf(TopicSuggestion(term = "Local", confidence = 0.5f, source = TopicSuggestion.Source.LOCAL))
            val result = coordinator.enhance("text", local)
            assertEquals(local, result)
        } finally {
            scope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // resolveTopicEnricher()
    // -------------------------------------------------------------------------

    private class FakeProvider(
        override val id: String,
        override val kind: LlmProviderKind,
        private val availability: LlmProviderAvailability,
        formatterResponseJson: String = "unused",
    ) : LlmProvider {
        override val displayName: String = id
        override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> LlmResult.Success(formatterResponseJson) }
        override suspend fun checkAvailability(): LlmProviderAvailability = availability
    }

    /** In-memory [Settings] fake — mirrors `VoicePipelineFactoryTest.MapSettings`. */
    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    @Test
    fun resolveTopicEnricher_providerAvailableForCaptureEnrichment_returnsClaudeTopicEnricher() = runTest {
        val provider = FakeProvider("android-ondevice", LlmProviderKind.ON_DEVICE, LlmProviderAvailability.Available)
        val registry = LlmProviderRegistry(listOf(provider))

        val enricher = CaptureEnrichmentCoordinator.resolveTopicEnricher(registry)

        assertIs<ClaudeTopicEnricher>(enricher)
    }

    @Test
    fun resolveTopicEnricher_noProviderConfigured_returnsNoOpTopicEnricher() = runTest {
        val registry = LlmProviderRegistry(emptyList())

        val enricher = CaptureEnrichmentCoordinator.resolveTopicEnricher(registry)

        assertIs<NoOpTopicEnricher>(enricher)
    }

    // Fix 1 (security) regression: resolveTopicEnricher() must honor LlmSettings' per-feature
    // CAPTURE_ENRICHMENT selection instead of always falling back to the first available provider.

    @Test
    fun resolveTopicEnricher_featureDisabled_returnsNoOpEvenWhenProviderAvailable() = runTest {
        // A provider IS available for CAPTURE_ENRICHMENT — proves the disabled sentinel takes
        // precedence over availableForFeature().firstOrNull(), not just that an empty registry
        // degrades to NoOp (already covered above).
        val provider = FakeProvider("android-ondevice", LlmProviderKind.ON_DEVICE, LlmProviderAvailability.Available)
        val registry = LlmProviderRegistry(listOf(provider))
        val llmSettings = LlmSettings(MapSettings())
        llmSettings.setSelectedProviderId(LlmFeature.CAPTURE_ENRICHMENT, LlmProviderRegistry.DISABLED_SENTINEL)

        val enricher = CaptureEnrichmentCoordinator.resolveTopicEnricher(registry, llmSettings)

        assertIs<NoOpTopicEnricher>(enricher)
    }

    @Test
    fun resolveTopicEnricher_specificProviderSelected_resolvesThatExactProviderNotFirstAvailable() = runTest {
        // Two available providers, each with a distinguishable formatter response — proves the
        // SECOND (non-first) provider was actually wired in via LlmProviderRegistry.find(), not
        // coincidentally the first one availableForFeature() would have returned.
        val first = FakeProvider(
            "first-provider", LlmProviderKind.ON_DEVICE, LlmProviderAvailability.Available,
            formatterResponseJson = """[{"term":"FromFirstProvider","confidence":0.9}]""",
        )
        val second = FakeProvider(
            "second-provider", LlmProviderKind.REMOTE, LlmProviderAvailability.Available,
            formatterResponseJson = """[{"term":"FromSecondProvider","confidence":0.9}]""",
        )
        val registry = LlmProviderRegistry(listOf(first, second))
        val llmSettings = LlmSettings(MapSettings())
        llmSettings.setSelectedProviderId(LlmFeature.CAPTURE_ENRICHMENT, "second-provider")

        val enricher = CaptureEnrichmentCoordinator.resolveTopicEnricher(registry, llmSettings)
        val claude = assertIs<ClaudeTopicEnricher>(enricher)
        val result = claude.enhance("text", emptyList())

        assertEquals(1, result.size)
        assertEquals("FromSecondProvider", result[0].term)
    }
}
