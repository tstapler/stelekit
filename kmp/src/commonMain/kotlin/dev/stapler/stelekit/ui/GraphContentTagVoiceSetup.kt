// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.tags.LlmTagProvider
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.tags.TagSuggestionEngine
import dev.stapler.stelekit.tags.TagSuggestionViewModel
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.voice.VoicePipelineConfig

/** Bundles [GraphContent]'s tag-suggestion and voice-capture stack (Parameter Object pattern). */
internal class GraphContentTagVoiceStack(
    val tagSettings: TagSettings,
    val qrTransferSettings: dev.stapler.stelekit.transfer.qrcode.QrTransferSettings,
    val hasTagSuggestionLlmProvider: Boolean,
    val tagSuggestionViewModel: TagSuggestionViewModel?,
    val voiceCaptureViewModel: VoiceCaptureViewModel,
)

/**
 * Resolves the tag-suggestion engine's LLM tier through the unified registry (Epic 8 Story 8.2) —
 * `availableForFeature()` performs a live availability check (e.g. on-device model readiness), so
 * resolution is async via `produceState`: the engine briefly has no LLM tier on first composition
 * until it resolves, then recomposes with it. Also builds the voice-capture view model, which
 * shares the same [TagSuggestionEngine].
 */
@Composable
internal fun rememberGraphContentTagVoiceStack(
    deps: GraphContentDeps,
    llmProviderRegistry: dev.stapler.stelekit.llm.LlmProviderRegistry,
    llmSettings: dev.stapler.stelekit.llm.LlmSettings,
    viewModel: StelekitViewModel,
): GraphContentTagVoiceStack {
    val repos = deps.repos
    val platformSettings = deps.platformSettings
    val voicePipeline = deps.coreServices.voicePipeline

    val tagSettings = remember(platformSettings) { TagSettings(platformSettings) }
    // Story 4.1.1: threads a real QrTransferSettings instance down to PageView's "Send via QR"
    // menu (Story 3.1.4) — without this, qrTransferSettings stays null at every call site and the
    // menu item never renders on any platform regardless of the flag's stored value.
    val qrTransferSettings = remember(platformSettings) {
        dev.stapler.stelekit.transfer.qrcode.QrTransferSettings(platformSettings)
    }
    val tagSuggestion = rememberTagSuggestionStack(tagSettings, llmProviderRegistry, llmSettings, viewModel)
    val voiceCaptureViewModel = rememberVoiceCaptureViewModel(voicePipeline, repos, viewModel, tagSuggestion.tagEngine)

    return GraphContentTagVoiceStack(
        tagSettings = tagSettings,
        qrTransferSettings = qrTransferSettings,
        hasTagSuggestionLlmProvider = tagSuggestion.hasLlmProvider,
        tagSuggestionViewModel = tagSuggestion.viewModel,
        voiceCaptureViewModel = voiceCaptureViewModel,
    )
}

/** The tag-suggestion-engine half of [rememberGraphContentTagVoiceStack] (split out for length). */
private class TagSuggestionStack(
    val tagEngine: TagSuggestionEngine?,
    val viewModel: TagSuggestionViewModel?,
    val hasLlmProvider: Boolean,
)

@Composable
private fun rememberTagSuggestionStack(
    tagSettings: TagSettings,
    llmProviderRegistry: dev.stapler.stelekit.llm.LlmProviderRegistry,
    llmSettings: dev.stapler.stelekit.llm.LlmSettings,
    viewModel: StelekitViewModel,
): TagSuggestionStack {
    val tagLlmProviderState = rememberTagLlmProvider(tagSettings, llmProviderRegistry, llmSettings)
    val tagEngine = remember(viewModel.pageNameIndex, tagSettings.isEnabled(), tagLlmProviderState.value) {
        buildTagSuggestionEngine(tagSettings, viewModel, tagLlmProviderState.value)
    }
    // Epic 8 Story 8.4a straggler fix: TagSuggestionSettings' "hasLlmKey" gate used to read
    // voiceSettings.getAnthropicKey()/getOpenAiKey() directly — those always return null once
    // LlmCredentialMigration has run (the whole point of the migration is clearing them), which
    // would have permanently disabled the LLM-tier switch in Settings for every migrated
    // install, including ones with a valid registry provider (remote key or on-device). Now
    // reflects "is any provider available for TAG_SUGGESTION at all" via the registry,
    // independent of the tier's enabled/disabled toggle state.
    val hasTagSuggestionLlmProviderState = produceState(initialValue = false, llmProviderRegistry) {
        value = llmProviderRegistry.availableForFeature(dev.stapler.stelekit.llm.LlmFeature.TAG_SUGGESTION).isNotEmpty()
    }
    val tagSuggestionViewModel = remember(tagEngine) {
        if (tagEngine != null) TagSuggestionViewModel(tagEngine, onPropose = viewModel::proposeLlmSuggestion) else null
    }
    DisposableEffect(tagSuggestionViewModel) {
        onDispose { tagSuggestionViewModel?.close() }
    }
    // Warm up the on-device model as soon as a provider is available — long before the user
    // taps "Suggest tags" so the first request hits a warm runtime instead of a cold one.
    LaunchedEffect(tagSuggestionViewModel) { tagSuggestionViewModel?.preload() }

    return TagSuggestionStack(tagEngine, tagSuggestionViewModel, hasTagSuggestionLlmProviderState.value)
}

private fun buildTagSuggestionEngine(
    tagSettings: TagSettings,
    viewModel: StelekitViewModel,
    llmProvider: dev.stapler.stelekit.llm.LlmProvider?,
): TagSuggestionEngine? {
    if (!tagSettings.isEnabled()) return null
    return TagSuggestionEngine(
        pageNameIndex = viewModel.pageNameIndex,
        llmTagProvider = llmProvider?.let { LlmTagProvider(it.formatter) },
        checkAvailability = llmProvider?.let { p -> { p.checkAvailability() } },
    )
}

@Composable
private fun rememberTagLlmProvider(
    tagSettings: TagSettings,
    llmProviderRegistry: dev.stapler.stelekit.llm.LlmProviderRegistry,
    llmSettings: dev.stapler.stelekit.llm.LlmSettings,
) = produceState<dev.stapler.stelekit.llm.LlmProvider?>(initialValue = null, llmProviderRegistry, llmSettings, tagSettings) {
    value = if (tagSettings.isLlmTierEnabled()) {
        when (val selectedId = llmSettings.getSelectedProviderId(dev.stapler.stelekit.llm.LlmFeature.TAG_SUGGESTION)) {
            // Existing-install guard (Story 8.2b) explicitly disabled this feature —
            // never fall through to Auto.
            dev.stapler.stelekit.llm.LlmProviderRegistry.DISABLED_SENTINEL -> null
            // "Auto" — first available provider in registry order (on-device included).
            null -> llmProviderRegistry.availableForFeature(dev.stapler.stelekit.llm.LlmFeature.TAG_SUGGESTION).firstOrNull()
            // Explicit selection — no Auto fallback if the id is stale/removed.
            else -> llmProviderRegistry.find(selectedId)
        }
    } else null
}

@Composable
private fun rememberVoiceCaptureViewModel(
    voicePipeline: VoicePipelineConfig,
    repos: RepositorySet,
    viewModel: StelekitViewModel,
    tagEngine: TagSuggestionEngine?,
): VoiceCaptureViewModel {
    val voiceCaptureViewModel = remember(voicePipeline, tagEngine) {
        VoiceCaptureViewModel(
            voicePipeline,
            repos.journalService,
            currentOpenPageUuid = { viewModel.uiState.value.currentPage?.uuid?.value },
            tagSuggestionEngine = tagEngine,
        )
    }
    DisposableEffect(voiceCaptureViewModel) {
        onDispose { voiceCaptureViewModel.close() }
    }
    return voiceCaptureViewModel
}
