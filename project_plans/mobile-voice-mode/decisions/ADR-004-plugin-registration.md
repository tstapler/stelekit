# ADR-004: Provider Registration and Wiring

**Status**: Proposed
**Date**: 2026-04-18

## Context

Three provider interfaces require wiring at app startup: `AudioRecorder`, `SpeechToTextProvider`,
and `LlmFormatterProvider`. The question is how platform entry points provide real implementations
to `VoiceCaptureViewModel`, which lives in `commonMain`.

Options evaluated:

1. **Constructor injection with `NoOp` defaults** — the seam pattern established by ADR-002
   (import-topic-suggestions project) and used by `TopicEnricher`, `UrlFetcher`, and `PageSaver`.
2. **Koin DI** — not currently used anywhere in SteleKit. `App.kt` uses `remember { }` blocks.
3. **`PluginHost` registry** — exists in the codebase but is designed for user-installed JS plugins
   with `onEnable`/`onDisable` lifecycle. Architectural mismatch for built-in provider backends.
4. **`ServiceLoader`** (JVM) — platform-specific, no iOS equivalent.

The existing `StelekitApp` composable already threads one optional provider via constructor
parameter: `urlFetcher: UrlFetcher = remember { NoOpUrlFetcher() }`. The voice pipeline adds
two more providers. A `VoicePipelineConfig` data class can bundle them to keep `StelekitApp`'s
parameter count manageable.

## Decision

Use **constructor injection with `NoOp` defaults** — identical to the existing seam pattern.

```kotlin
// commonMain — voice/VoicePipelineConfig.kt
data class VoicePipelineConfig(
    val audioRecorder: AudioRecorder = NoOpAudioRecorder,
    val sttProvider: SpeechToTextProvider = NoOpSpeechToTextProvider,
    val llmProvider: LlmFormatterProvider = NoOpLlmFormatterProvider,
    val systemPrompt: String = DEFAULT_VOICE_SYSTEM_PROMPT,
)
```

`StelekitApp` gains one optional parameter:

```kotlin
@Composable
fun StelekitApp(
    fileSystem: PlatformFileSystem,
    graphPath: String,
    pluginHost: PluginHost = remember { PluginHost() },
    encryptionManager: EncryptionManager = remember { DefaultEncryptionManager() },
    urlFetcher: UrlFetcher = remember { NoOpUrlFetcher() },
    voicePipeline: VoicePipelineConfig = remember { VoicePipelineConfig() },  // new
)
```

`GraphContent` receives `voicePipeline: VoicePipelineConfig` and passes it to
`VoiceCaptureViewModel`:

```kotlin
val voiceCaptureViewModel = remember {
    VoiceCaptureViewModel(
        audioRecorder = voicePipeline.audioRecorder,
        sttProvider = voicePipeline.sttProvider,
        llmProvider = voicePipeline.llmProvider,
        systemPrompt = voicePipeline.systemPrompt,
        journalService = repos.journalService,
        scope = scope,
    )
}
```

**Android `MainActivity`** wires real providers when API keys are configured:

```kotlin
StelekitApp(
    // ... existing params ...
    voicePipeline = VoicePipelineConfig(
        audioRecorder = AndroidAudioRecorder(context),
        sttProvider = buildSttProvider(whisperApiKey),
        llmProvider = buildLlmProvider(anthropicKey, openAiKey),
    )
)

private fun buildSttProvider(whisperKey: String?): SpeechToTextProvider =
    if (whisperKey.isNullOrBlank()) NoOpSpeechToTextProvider
    else WhisperSpeechToTextProvider(httpClient, whisperKey)

private fun buildLlmProvider(anthropicKey: String?, openAiKey: String?): LlmFormatterProvider =
    when {
        !anthropicKey.isNullOrBlank() -> ClaudeLlmFormatterProvider(httpClient, anthropicKey)
        !openAiKey.isNullOrBlank() -> OpenAiLlmFormatterProvider(httpClient, openAiKey)
        else -> NoOpLlmFormatterProvider
    }
```

**iOS entry point** (`Main.kt`/`MainViewController.kt`) follows the same pattern with
`IosAudioRecorder`.

**Desktop** (`Main.kt`): passes the default `VoicePipelineConfig()` — all `NoOp` providers,
voice capture is mobile-only.

**Tests** inject `FakeAudioRecorder`, `FakeSpeechToTextProvider`, `FakeLlmFormatterProvider`
directly into `VoiceCaptureViewModel`.

## Rationale

**Seam pattern consistency**: Every provider in SteleKit is wired this way (`TopicEnricher`,
`UrlFetcher`, `PageSaver`). Deviating for this feature introduces a second DI pattern in the
codebase. Cognitive consistency is a project value — from ADR-002: "Inject `topicEnricher` into
`ImportViewModel` as a constructor parameter ... mirroring the `pageSaver` seam pattern exactly."

**`VoicePipelineConfig` bundles the new parameters**: `StelekitApp` currently has 5 parameters.
Adding 3 more individually would make the signature unwieldy. A `data class` bundles them with a
sensible default and communicates that these three concerns belong together. This is the same
refactor that would apply to `urlFetcher` + any future enrichment providers.

**`NoOp` defaults ensure local-first behavior**: A developer running the app without API keys
configured gets the pipeline in a "no-op" state — the mic button is visible but the
`VoiceCaptureViewModel` surfaces "No STT provider configured" rather than crashing. No null
checks needed anywhere in the ViewModel.

**Plugin registry is out of scope**: Full auto-discovery via `ServiceLoader` or a plugin
registry is deferred to the `stelekit-plugin-api` project (per ADR-002, import-topic-suggestions).
Third-party plugins provide `SpeechToTextProvider` and `LlmFormatterProvider` implementations
and pass them to `StelekitApp` at assembly time — identical to how `TopicEnricher` works today.

## Consequences

- `StelekitApp` gains `voicePipeline: VoicePipelineConfig = remember { VoicePipelineConfig() }`.
- `GraphContent` gains `voicePipeline: VoicePipelineConfig` and passes it to `VoiceCaptureViewModel`.
- Android `MainActivity` must construct `AndroidAudioRecorder` with a `Context` reference.
  `AndroidAudioRecorder` must not hold a long-lived `Context` after recording ends (use
  `applicationContext` only).
- iOS entry point must construct `IosAudioRecorder`.
- `VoiceSettings` (Story 2) reads API keys from the Android Keystore / iOS Keychain. The
  platform entry point reconstructs providers when settings change (or uses a
  `StateFlow<VoicePipelineConfig>` if hot-swapping is desired in a future story).
- No new Gradle dependencies for provider wiring — Ktor is already in `commonMain`.

## Alternatives Considered

**Koin DI**: Eliminated. Not currently used in SteleKit. Introduces a new dependency and a second
DI pattern. `App.kt` uses `remember { }` blocks throughout — Koin would be the only exception.

**`PluginHost` registry**: Eliminated. `Plugin` interface has `onEnable`/`onDisable` lifecycle
and metadata fields appropriate for user-installed JS plugins, not built-in provider backends.
ADR-002 (import-topic-suggestions) explicitly deferred full plugin registry to `stelekit-plugin-api`.

**Individual `StelekitApp` parameters for each provider**: Eliminated. Would grow `StelekitApp`
to 8+ parameters. `VoicePipelineConfig` is the cleaner aggregation.
