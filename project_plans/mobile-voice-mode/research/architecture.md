# Findings: Architecture

**Feature**: Voice capture → STT → LLM → outliner format → journal
**Date**: 2026-04-18
**Codebase read**: `App.kt`, `PluginSystem.kt`, `PlatformFileSystem.kt`, `TopicEnricher.kt`,
`ClaudeTopicEnricher.kt`, `UrlFetcher.kt`, `JournalService.kt`, `PlatformBottomBar.android.kt`,
`ADR-002-topic-enricher-plugin-interface.md`

---

## Summary

The voice pipeline has five distinct seams: audio capture, speech-to-text, LLM formatting, journal
insertion, and UI orchestration. Three of these (STT, LLM, and potentially audio capture) are
natural plugin extension points. The codebase already has a proven seam pattern — `suspend fun
interface` with a `NoOp` default injected at ViewModel construction time — established by
`TopicEnricher`/`UrlFetcher`. This architecture extends that pattern directly. The only new
complexity is the `expect/actual` audio capture layer, which must live in `androidMain` /
`iosMain` because there is no KMP audio recording API in `commonMain`.

Recommended architecture: a `VoiceCaptureViewModel` in `commonMain` orchestrating a pipeline
through three `suspend fun interface` seams — `AudioRecorder`, `SpeechToTextProvider`, and
`LlmProvider` — all injected at construction time with `NoOp` defaults. The ViewModel exposes a
single `StateFlow<VoiceCaptureState>` that drives a `VoiceCaptureButton` composable embedded in
`PlatformBottomBar.android.kt`.

---

## Options Surveyed

### Q1 — Audio Capture: expect/actual shape and data representation

**Option A: `expect class AudioRecorder`**
A platform class with `suspend fun start()`, `suspend fun stop(): ByteArray`. Android actual uses
`AudioRecord` (PCM) or `MediaRecorder` (encoded); iOS actual uses `AVAudioRecorder`.
- Pro: encapsulates all platform API inside the actual; commonMain sees only a typed Kotlin class.
- Con: `expect class` rules in KMP are strict — every property/method in the expect must be in every
  actual. Adding methods later requires all actuals to update in lockstep.

**Option B: `expect fun interface AudioRecorder`** (preferred)
A `suspend fun interface` in `commonMain/platform/` with a single `suspend fun record(): Flow<AudioChunk>` or `suspend fun recordToFile(): PlatformAudioFile`. Actuals are in `androidMain` / `iosMain`.
- Pro: matches the existing `TopicEnricher` / `UrlFetcher` seam pattern. Single-method interface.
  No `expect class` rigidity.
- Con: `expect fun interface` is not directly supported in KMP — `expect interface` is, but
  `fun interface` keyword only applies to actual implementations. [TRAINING_ONLY — verify exact
  KMP syntax for expect interfaces]

**Option C: `expect object AudioRecorderFactory`**
Factory that returns a platform audio recorder instance. Used in `androidMain` / `iosMain`.
- Same structural result as Option B but with a Factory wrapper.

**Data representation — ByteArray vs File vs Flow:**
- `ByteArray`: simple, no disk I/O, but holds entire recording in memory. Problematic for long
  recordings. Acceptable for a typical 10–60s voice note.
- `File` (temp file path): avoids memory spike; Whisper API accepts a file upload directly.
  Requires cleanup logic. Works well with Android `MediaRecorder` (outputs to file natively) and
  iOS `AVAudioRecorder` (also outputs to URL/file).
- `Flow<ByteArray>` (streaming PCM): enables real-time display of audio level for the mic
  animation. Adds complexity; STT providers vary on whether they accept streaming audio.

**Recommendation**: Use a temp file. Both `MediaRecorder` (Android) and `AVAudioRecorder` (iOS)
naturally record to a file. The Whisper API and most STT services accept file upload. A temp file
avoids memory spikes. Expose it as a `PlatformAudioFile` value class wrapping a path string — the
same cross-platform path convention already used by `PlatformFileSystem`.

**Audio format**: `m4a` / AAC is the lowest-friction choice — both platforms record it natively,
Whisper accepts it, file sizes are small (~1 MB/min). PCM/WAV is universal but large.
[TRAINING_ONLY — verify Whisper API accepted formats for m4a]

---

### Q2 — STT Provider Interface

**Option A: Batch interface**
```kotlin
fun interface SpeechToTextProvider {
    suspend fun transcribe(audioFile: PlatformAudioFile): TranscriptResult
}
```
- Pro: matches the `TopicEnricher` pattern exactly. Simplest interface; easy to test with a
  `FakeSpeechToTextProvider`. Compatible with Whisper API (POST audio file → transcript string).
- Con: no progress feedback during transcription. For a 60s audio file, Whisper API typically
  responds in 2–8s — acceptable without streaming.

**Option B: Streaming interface**
```kotlin
fun interface SpeechToTextProvider {
    fun transcribeStreaming(audioFile: PlatformAudioFile): Flow<TranscriptChunk>
}
```
- Pro: enables real-time partial results; better UX for long recordings.
- Con: significantly more complex. Whisper API does not offer streaming; only platform
  SpeechRecognizer / SFSpeechRecognizer support streaming. Forces asymmetric implementations.
  Requirements say "result shown only after processing" — streaming is out of scope.

**Sealed result type:**
```kotlin
sealed interface TranscriptResult {
    data class Success(val text: String) : TranscriptResult
    data object Empty : TranscriptResult
    sealed interface Failure : TranscriptResult {
        data object NetworkError : Failure
        data class ApiError(val code: Int) : Failure
        data object AudioTooShort : Failure
        data object PermissionDenied : Failure
    }
}
```
Models the same pattern as `FetchResult` in `UrlFetcher.kt`.

**Recommendation**: Batch interface, Option A. Out-of-scope requirement confirmed in
`requirements.md`: "Real-time transcription display while speaking (result shown only after
processing)". The sealed `TranscriptResult` type handles all failure modes without exceptions
crossing the provider boundary.

**Built-in providers to ship:**
1. `WhisperSpeechToTextProvider` (Ktor + Whisper API, user-supplied key) — commonMain, works on both platforms
2. `AndroidSpeechToTextProvider` (Android `SpeechRecognizer` — free, on-device) — androidMain
3. `NoOpSpeechToTextProvider` (returns `TranscriptResult.Empty`) — default, tests

---

### Q3 — LLM Provider Interface

**Option A: Batch string-in / string-out**
```kotlin
fun interface LlmProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}
```
- Pro: identical seam pattern to `TopicEnricher`. Caller controls the prompt; provider is purely
  mechanical. Testable with a `FakeLlmProvider`.
- Con: no streaming. Acceptable given requirements scope.

**Option B: Streaming Flow**
```kotlin
interface LlmProvider {
    fun formatStreaming(transcript: String, systemPrompt: String): Flow<String>
}
```
- Pro: progressive display of formatted output.
- Con: requirements say no real-time display during processing. Adds complexity.

**Sealed result type:**
```kotlin
sealed interface LlmResult {
    data class Success(val formattedText: String) : LlmResult
    sealed interface Failure : LlmResult {
        data object NetworkError : Failure
        data class ApiError(val code: Int) : Failure
        data object Timeout : Failure
        data class MalformedResponse(val raw: String) : Failure
    }
}
```

**System prompt strategy**: The system prompt (instruction to format as Logseq outliner with
`[[wikilinks]]`) should be a constructor parameter of `VoiceCaptureViewModel`, not baked into
the interface. This makes the prompt configurable without changing the `LlmProvider` contract.
Follows the principle established by `ClaudeTopicEnricher` where the prompt is internal to the
implementation.

**Built-in providers:**
1. `ClaudeLlmProvider` (Ktor + Anthropic Messages API, user-supplied key) — mirrors `ClaudeTopicEnricher` exactly, commonMain
2. `OpenAiLlmProvider` (Ktor + OpenAI Chat API, compatible with any OpenAI-compatible endpoint)
3. `NoOpLlmProvider` (returns transcript verbatim, no formatting) — default
4. `AndroidOnDeviceLlmProvider` (Android AICore / Gemini Nano) — androidMain only, gated on API availability [TRAINING_ONLY — verify Android AICore/ML Kit Gemini Nano Kotlin API]

---

### Q4 — Pipeline Orchestration

**Option A: ViewModel owns the pipeline**
`VoiceCaptureViewModel` calls `audioRecorder.recordToFile()`, then `sttProvider.transcribe()`,
then `llmProvider.format()`, then `journalService.appendToToday()`. All within a
`viewModelScope.launch {}` coroutine. Matches `ImportViewModel.scanJob` pattern exactly — a single
`Job` field that can be cancelled if the user dismisses.

**Option B: UseCase / domain service**
A `VoicePipelineUseCase` in `domain/` that owns the sequential calls. ViewModel launches the
use case and observes a `StateFlow`.
- Pro: separates orchestration concern from UI state management.
- Con: `audioRecorder` has a coroutine lifecycle (must be cancelled on ViewModel disposal). Pushing
  it into a UseCase without a scope is awkward. ADR-002 explicitly decided against domain-layer
  async injection for this same reason: "ViewModel already manages a coroutine scope and a
  `scanJob`; the enricher coroutine is a natural second job alongside it."

**Option C: Service (long-lived)**
A `VoiceCaptureService` (Android foreground service) for background recording.
- Requirements: "user must actively trigger each recording", no background listening.
  Foreground service is overkill and out of scope.

**Recommendation**: Option A. ViewModel owns the pipeline. A single `pipelineJob: Job?` field,
cancellable on user abort or ViewModel disposal. Pipeline errors are caught in `try/catch` and
mapped to `VoiceCaptureState.Error(...)`. Same pattern as `ImportViewModel.scanJob`.

**Error handling**: Wrap each stage call in `try/catch`. Map exceptions to sealed `VoiceCaptureState`
variants. Propagate partial results where useful (e.g., if LLM fails, offer the raw transcript as
the fallback insert). Never throw across provider boundaries — providers return sealed results, not
exceptions, so the ViewModel only catches `CancellationException`.

---

### Q5 — Plugin Registration

**Option A: Constructor injection with NoOp defaults** (seam pattern)
Providers injected into `VoiceCaptureViewModel(sttProvider = NoOpSpeechToTextProvider(), llmProvider = NoOpLlmProvider(), ...)`. Host app (Android `MainActivity`, iOS `Main.kt`) wires real providers.
- Pro: zero infrastructure; no registry, no DI framework, no annotation processor. Testable.
  Matches every other seam in this codebase.
- Con: host app must explicitly wire each provider. Fine for v1 with a small set of built-ins.

**Option B: Koin DI**
Define a Koin module for voice providers. App module includes it; tests replace with test module.
- Pro: clean separation of wiring from construction. Standard in many KMP projects.
- Con: Koin is not currently used in SteleKit (`PluginSystem.kt` uses direct constructor injection,
  `GraphContent` in `App.kt` uses `remember { }` blocks). Introducing Koin for one feature adds a
  new dependency and pattern inconsistent with the rest of the codebase.
  [TRAINING_ONLY — verify whether Koin is in `kmp/build.gradle.kts`]

**Option C: PluginHost registry**
Register `SpeechToTextProvider` implementations as `Plugin` instances in `PluginHost`. The
existing `PluginHost` class has `registerPlugin(plugin: Plugin)` and `getAllPlugins()`.
- Con: `Plugin` interface has `onEnable`/`onDisable` lifecycle, `id`/`name`/`version` metadata.
  This lifecycle is appropriate for user-installed JS plugins, not for built-in provider backends.
  Mixing the two concerns would complicate `PluginHost`. ADR-002 also explicitly deferred full
  plugin registry to a future `stelekit-plugin-api` project.

**Option D: ServiceLoader (JVM only)**
`java.util.ServiceLoader` for JVM/Android, manual registration for iOS.
- Con: platform-specific; not KMP-compatible without wrappers. iOS has no equivalent.

**Recommendation**: Option A. Extend the seam pattern. `StelekitApp` gains two optional
parameters: `sttProvider: SpeechToTextProvider = NoOpSpeechToTextProvider()` and
`llmProvider: LlmProvider = NoOpLlmProvider()`. `GraphContent` passes them through to
`VoiceCaptureViewModel`. This is identical to how `urlFetcher: UrlFetcher = NoOpUrlFetcher()` is
threaded today. Third-party plugins provide implementations at app-assembly time.

---

### Q6 — Compose UI Integration: Mic Button State Machine

**State machine:**
```
Idle → Recording → Transcribing → Formatting → Done
                                              ↘ Error
Any state → Idle (on user cancel / dismiss)
```

Mapped to a sealed class:
```kotlin
sealed interface VoiceCaptureState {
    data object Idle : VoiceCaptureState
    data object Recording : VoiceCaptureState
    data object Transcribing : VoiceCaptureState
    data object Formatting : VoiceCaptureState
    data class Done(val insertedText: String) : VoiceCaptureState
    data class Error(val stage: PipelineStage, val message: String) : VoiceCaptureState
}

enum class PipelineStage { RECORDING, TRANSCRIPTION, LLM, JOURNAL_INSERT }
```

**UI placement**: The mic button fits naturally in `PlatformBottomBar.android.kt`. The current
bottom nav has 4 items (Journals, Pages, Search, Notifications). A 5th mic item can be added as
a `FloatingActionButton`-style center affordance, or replace Search (since search is a dialog
trigger, not a destination). Given `isLeftHanded` is already handled, the mic button position
(left vs right) can follow the same flag.

The button shows:
- `Idle`: mic icon, tap to start
- `Recording`: pulsing red circle + stop icon, tap to stop
- `Transcribing` / `Formatting`: spinner with label
- `Done`: brief checkmark then revert to Idle
- `Error`: error icon with toast/snackbar; tap to retry or dismiss

`VoiceCaptureViewModel` is created in `GraphContent` alongside the existing ViewModels (same
`remember { }` pattern). It is passed down to `PlatformBottomBar` as a state parameter so the
bottom bar remains a composable that only observes state — it does not own the ViewModel.

---

## Trade-off Matrix

| Axis | Seam pattern (recommended) | Koin DI | PluginHost registry |
|---|---|---|---|
| Extensibility | Good — any impl injected at assembly | Good — module swap | Poor — lifecycle mismatch |
| Testability | Excellent — NoOp defaults | Good — test module | Fair — requires lifecycle stubs |
| Platform coupling | Low — expect/actual only in audio capture | Low | Low |
| Boilerplate | Low — matches existing code | Medium — Koin setup | Medium — Plugin wrapper |
| DI compatibility | N/A — no DI needed | Requires Koin dependency | Requires PluginHost wiring |
| Consistency with codebase | Excellent — identical to TopicEnricher | Poor — new pattern | Fair — extends existing but mismatches |

---

## Risk and Failure Modes

**R1 — Microphone permission denied**
Both Android (`RECORD_AUDIO`) and iOS (`NSMicrophoneUsageDescription`) require runtime permission.
The `AudioRecorder` actual must check permission before starting and return `TranscriptResult.Failure.PermissionDenied` (surfaced to UI as a rationale dialog). Android 13+ requires explicit request; iOS permission is granted once at first use.

**R2 — Audio focus conflict (Android)**
`MediaRecorder` does not request audio focus by default. Background music will bleed into the
recording. The Android actual should request `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT` before
starting and abandon it after stopping. [TRAINING_ONLY — verify AudioFocus API behavior with MediaRecorder]

**R3 — Whisper API key not configured**
`WhisperSpeechToTextProvider` receives an empty or missing API key. Should fail fast at
construction time with a descriptive `IllegalStateException`, not at transcription time. The UI
should surface a settings prompt to add an API key.

**R4 — LLM hallucinates wikilinks**
The LLM may invent `[[links]]` to non-existent pages. This is acceptable per requirements
(existing pages list is not provided to the LLM in v1). Future enhancement: pass page name index
as context.

**R5 — Recording too short / silence**
Whisper returns an empty transcript for silence or sub-1s audio. `WhisperSpeechToTextProvider`
should map empty responses to `TranscriptResult.Empty`, which the ViewModel surfaces as a
dismissible "Nothing was captured" message.

**R6 — No internet connectivity**
Both Whisper and LLM calls require network. The pipeline should check connectivity before launching
and return `Failure.NetworkError` immediately rather than waiting for a timeout.

**R7 — iOS AVAudioSession category conflict**
`AVAudioRecorder` requires setting `AVAudioSession.sharedInstance().setCategory(.record)`.
This silences other audio. Must be restored to `.playback` or `.ambient` after recording. The
iOS actual must handle `AVAudioSession` activation/deactivation within the recording lifecycle.
[TRAINING_ONLY — verify AVAudioSession best practices for record + restore]

**R8 — Journal page creation race**
If `JournalService.ensureTodayExists()` is called from two coroutines simultaneously (voice
capture + JournalsViewModel startup), a duplicate page could be created. `JournalService` already
uses a `Mutex` for this case — confirmed in `JournalService.kt` line 36. No additional
synchronization needed.

---

## Migration and Adoption Cost

**Adding the feature from scratch (no existing voice code):**
- New files: ~8 (interfaces, NoOp implementations, ViewModel, 2 platform actuals, UI component,
  1 platform bottom bar modification)
- Modified files: `App.kt` (add `sttProvider`/`llmProvider` params), `PlatformBottomBar.android.kt`
  (add mic button), `GraphContent` (create `VoiceCaptureViewModel`)
- No schema changes (journal insertion uses existing `JournalService`)
- No new dependencies beyond what's needed for Ktor (already present for `ClaudeTopicEnricher`)

**Adding a new STT provider (third-party):**
- Implement `SpeechToTextProvider` (single method)
- Pass instance to `StelekitApp` at assembly time
- Zero changes to core code

**Adding a new LLM provider:**
- Same as above for `LlmProvider`

---

## Operational Concerns

- **API cost**: Whisper API is ~$0.006/min of audio [TRAINING_ONLY — verify current pricing].
  A 1-minute voice note costs less than a cent. Not a concern for personal use.
- **Latency**: Typical Whisper API round-trip for 60s audio: 3–8s. Typical Claude Haiku
  formatting call: 1–3s. Total pipeline: 5–12s from tap-to-stop to journal insert.
- **Temp file cleanup**: The audio temp file should be deleted after `transcribe()` returns,
  regardless of success or failure. `VoiceCaptureViewModel` owns cleanup, not the platform recorder.
- **Background/foreground transitions**: If the app is backgrounded during recording (Android),
  `MediaRecorder` may be killed. The `ON_PAUSE` lifecycle observer in `GraphContent` should call
  `audioRecorder.stop()` to gracefully end recording and avoid a dangling file.

---

## Prior Art and Lessons Learned

**TopicEnricher / ADR-002 (this codebase)**
The core lesson: push async, side-effectful providers to the ViewModel layer via constructor
injection with NoOp defaults. Do not inject into domain services. This pattern scales directly
to the voice pipeline — confirmed by reading `ImportViewModel.kt`, `TopicEnricher.kt`, and the ADR.

**`UrlFetcher` (this codebase)**
The `sealed class FetchResult` pattern shows how to model multi-mode failure without exceptions.
`TranscriptResult` and `LlmResult` follow the same shape.

**`PlatformFileSystem` (this codebase)**
Demonstrates the `expect class` approach for a large platform abstraction. Audio capture does
**not** need an `expect class` — a plain `interface` in `commonMain` + actual implementations
registered at assembly time is sufficient and less rigid. `PlatformFileSystem` uses `expect class`
because it must be passed to many constructors and Compose `remember {}` blocks that need a typed
reference. `AudioRecorder` is only used in `VoiceCaptureViewModel`, so a plain interface suffices.

**Whisper Memos / Audiopen (comparable products)**
[TRAINING_ONLY — no live web search available] These apps use a "record → upload → display"
flow without streaming display. Their UX confirms the batch model is sufficient for user
satisfaction: users accept a 5–10s processing delay after tapping stop.

**Android SpeechRecognizer vs Whisper API**
Android `SpeechRecognizer` is free and on-device but has a 60-second hard limit and requires an
active internet connection on most devices (unless using on-device mode, which is limited).
[TRAINING_ONLY — verify Android 13+ on-device SpeechRecognizer availability] For v1,
`WhisperSpeechToTextProvider` as the primary with `AndroidSpeechToTextProvider` as an opt-in
alternative covers both use cases cleanly.

---

## Open Questions

1. **Should `AudioRecorder` be an `expect interface` or a plain `interface` in `commonMain`?**
   If it's `expect interface`, KMP requires actual declarations in all targets including `jvmMain`
   (desktop). Since audio capture is mobile-only per requirements, a plain `interface` in
   `commonMain/platform/` with actual classes registered in mobile entry points avoids forcing
   a stub into `jvmMain`.

2. **Is Ktor already in `commonMain` dependencies?**
   `ClaudeTopicEnricher` uses Ktor, so it must be. Confirm that `ktor-client-core`,
   `ktor-client-content-negotiation`, and `ktor-serialization-kotlinx-json` are in
   `kmp/build.gradle.kts`. If so, no new network dependency is needed for
   `WhisperSpeechToTextProvider` or `ClaudeLlmProvider`.

3. **Audio format for Whisper API**: Does the Whisper API accept `.m4a` / AAC directly from
   Android `MediaRecorder` and iOS `AVAudioRecorder`? Or is conversion to `.wav` / `.mp3` needed?

4. **`StelekitApp` parameter threading**: `sttProvider` and `llmProvider` follow the same path
   as `urlFetcher` (injected in `StelekitApp`, threaded to `GraphContent`, then to
   `VoiceCaptureViewModel`). Should they be bundled into a `VoicePipelineConfig` data class to
   avoid growing `StelekitApp`'s parameter list further?

5. **iOS bottom bar**: `PlatformBottomBar` on iOS (if it exists) also needs a mic button.
   Confirm whether there is an `iosMain` actual for `PlatformBottomBar` or if it shares the
   Android composable.

6. **On-device STT/LLM availability gating**: Android AICore / Gemini Nano requires API 31+
   and specific device hardware. How should the ViewModel handle a provider that reports
   "unavailable" at runtime? A capability check method (`suspend fun isAvailable(): Boolean`)
   on the provider interface would allow graceful fallback — but adds interface surface area.

---

## Recommendation

Adopt the following architecture:

**Layer 1 — Audio Capture (platform-specific)**
Plain interface `AudioRecorder` in `commonMain/platform/`. Android actual: `AndroidAudioRecorder`
using `MediaRecorder` (outputs `.m4a` temp file). iOS actual: `IosAudioRecorder` using
`AVAudioRecorder`. Result type: `PlatformAudioFile(path: String)` value class.

**Layer 2 — STT Provider (commonMain interface, multiple impls)**
`suspend fun interface SpeechToTextProvider { suspend fun transcribe(audio: PlatformAudioFile): TranscriptResult }`
Default: `NoOpSpeechToTextProvider`. V1 built-in: `WhisperSpeechToTextProvider` (Ktor).
Optional: `AndroidSpeechToTextProvider` (platform SpeechRecognizer).

**Layer 3 — LLM Provider (commonMain interface, multiple impls)**
`suspend fun interface LlmProvider { suspend fun format(transcript: String, systemPrompt: String): LlmResult }`
Default: `NoOpLlmProvider`. V1 built-in: `ClaudeLlmProvider` (mirrors `ClaudeTopicEnricher`).

**Layer 4 — Pipeline Orchestration (VoiceCaptureViewModel, commonMain)**
Sequential pipeline in a single coroutine `Job`. Exposes `StateFlow<VoiceCaptureState>` and
two commands: `startRecording()` / `stopRecording()`. Calls `JournalService` directly for
journal insertion (no new abstraction needed — `JournalService` already exists and handles
today-page creation). Cleans up temp audio file after transcription regardless of success/failure.

**Layer 5 — UI (PlatformBottomBar + VoiceCaptureButton composable)**
`VoiceCaptureButton` composable in `commonMain/ui/components/`. Observes `VoiceCaptureState`
and renders the appropriate icon/animation. Added to `PlatformBottomBar.android.kt` (and iOS
equivalent). `VoiceCaptureViewModel` created in `GraphContent` alongside existing ViewModels.

**Plugin registration**: Constructor injection with NoOp defaults, following the established seam
pattern. `StelekitApp` gains `sttProvider` and `llmProvider` parameters (both optional with NoOp
defaults). Android `MainActivity` wires the real providers when API keys are configured.

This design introduces zero new framework dependencies, is immediately testable with fakes,
and extends naturally to a future plugin registry without breaking changes.

---

## Pending Web Searches

These claims are marked `[TRAINING_ONLY]` and should be verified:

1. **KMP expect interface syntax**: `"kotlin multiplatform expect interface fun interface"`
   — Confirm whether `expect fun interface` is valid or only `expect interface` is.

2. **Whisper API accepted formats**: `"openai whisper api audio formats m4a aac supported"`
   — Confirm `.m4a` / AAC is accepted without conversion.

3. **Whisper API pricing 2026**: `"openai whisper api pricing per minute 2026"`
   — Confirm current cost.

4. **Android SpeechRecognizer on-device mode**: `"android speechrecognizer on device mode api level offline"`
   — Confirm API level and device requirements for offline speech recognition.

5. **Android AICore Gemini Nano Kotlin API**: `"android aicore gemini nano kotlin coroutines api 2025 2026"`
   — Confirm availability and Kotlin API surface for on-device LLM.

6. **AVAudioSession record and restore**: `"AVAudioSession setCategory record restore ambient swift kotlin"`
   — Confirm best practice for category switching around recording on iOS.

7. **MediaRecorder audio focus**: `"android mediarecorder audiofocus request record"`
   — Confirm whether audio focus must be manually requested when using `MediaRecorder`.

8. **Koin in KMP projects with Compose Multiplatform**: `"koin kotlin multiplatform compose 2025 viewmodel"`
   — Gather context on whether Koin is the de-facto standard for KMP DI (to revisit Option B).

---

## Web Search Results

_Searches run: 2026-04-18._

### 1. Whisper API — m4a / AAC Accepted Format (confirmed)

**Query**: `openai whisper API accepted audio formats m4a aac supported 2025`

**Verdict**: CONFIRMED. The Whisper API officially accepts: **flac, mp3, mp4, mpeg, mpga,
m4a, ogg, wav, webm**. `.m4a` is explicitly listed. The architecture recommendation (§Q1) to
use `.m4a` from `MediaRecorder` (Android) and `AVAudioRecorder` (iOS) requires **no format
conversion** before upload. Max file size: 25 MB.

**Updated claim**: §Q1 `[TRAINING_ONLY — verify Whisper API accepted formats for m4a]` —
**confirmed: m4a is accepted**.

**Sources**:
- [Audio API FAQ — OpenAI Help Center](https://help.openai.com/en/articles/7031512-whisper-audio-api-faq)
- [OpenAI Community: m4a format issue thread](https://community.openai.com/t/wisper-api-not-recognizing-m4a-file-format/141251)

---

### 2. Whisper API Pricing

**Query**: `openai whisper API pricing per minute 2025 2026`

**Verdict**: CONFIRMED. `whisper-1`: $0.006/min. New option `gpt-4o-mini-transcribe`:
$0.003/min (half price). The §Operational Concerns cost figure is accurate.

**Sources**:
- [OpenAI API Pricing](https://openai.com/api/pricing/)
- [OpenAI Whisper API Pricing Apr 2026 — CostGoat](https://costgoat.com/pricing/openai-transcription)

---

### 3. Android SpeechRecognizer On-Device API Level

**Query**: `android SpeechRecognizer createOnDeviceSpeechRecognizer API 33 offline 2024`

**Verdict**: CONFIRMED WITH CORRECTION. `createOnDeviceSpeechRecognizer()` was introduced at
**API 31** (Android 12), not API 33. It is available on API 33 but the minimum level is 31.
SteleKit's minSdk 24 requires a runtime API check before calling the factory method.

**Updated claim**: The `AndroidSpeechToTextProvider` in §Q2 should gate on
`Build.VERSION.SDK_INT >= 31`, not 33.

**Sources**:
- [SpeechRecognizer — Android Developers](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [createOnDeviceSpeechRecognizer — Microsoft Learn (Android bindings)](https://learn.microsoft.com/en-us/dotnet/api/android.speech.speechrecognizer.createondevicespeechrecognizer?view=net-android-34.0)

---

### 4. Android AICore / ML Kit GenAI — Now a Public STT API

**Query**: `Android AICore public ASR speech recognition API 2025`

**Verdict**: MAJOR UPDATE. Google shipped **ML Kit GenAI Speech Recognition API** in 2025,
backed by Gemini Nano via AICore. This is a stable public API — the `[TRAINING_ONLY]` note in
§Q3 that "Android AICore STT is not a stable public SDK" is now outdated.

Key constraints for architecture:
- Requires API 31+; Advanced (Gemini Nano) mode requires Pixel 9/10 and similar high-end devices.
- **Blocked in background** (`ErrorCode.BACKGROUND_USE_BLOCKED`): cannot be called from a
  foreground service or background context. Only works when app is top foreground activity.
- Streaming (partial + final) results — not batch.

**Architectural impact**: `AndroidOnDeviceLlmProvider` described in §Q3 is now also applicable
as an `AndroidSpeechToTextProvider`. The foreground-only constraint is compatible with
Phase 1 (in-app recording). A capability check via `checkFeatureStatus()` is required before
surfacing the option in settings.

**Updated claim**: §Q3 `[TRAINING_ONLY — verify Android AICore/ML Kit Gemini Nano Kotlin API]`
— **confirmed as public API**. Foreground-only restriction is the key architectural constraint.

**Sources**:
- [GenAI Speech Recognition API — Google for Developers](https://developers.google.com/ml-kit/genai/speech-recognition/android)
- [ML Kit GenAI APIs — Android Developers](https://developer.android.com/ai/gemini-nano/ml-kit-genai)
- [SpeechRecognizer (ML Kit) — Google Developers](https://developers.google.com/android/reference/kotlin/com/google/mlkit/genai/speechrecognition/SpeechRecognizer)

---

### 5. Apple FoundationModels — New iOS LLM Backend

**Query**: `Apple Intelligence FoundationModels framework public API third party iOS 18 2025`

**Verdict**: MAJOR UPDATE. Apple released `FoundationModels` at WWDC 2025. Third-party
developers can now call Apple's on-device LLM directly for text generation, structured output,
and tool calling. This was a `[TRAINING_ONLY]` unknown; it is now confirmed.

**Architectural impact on §Q3**: A fourth `LlmProvider` implementation should be planned:
`AppleIntelligenceLlmProvider` in `iosMain`, backed by `FoundationModels`. It provides:
- Offline formatting (no network call)
- No API cost
- Hardware gate: iPhone 15 Pro+, Apple Silicon, iOS 18.1+
- Text-in / text-out (not STT)

This is compatible with the `LlmProvider` interface (`suspend fun format(transcript, systemPrompt): LlmResult`). The capability check maps to `isAvailable(): Boolean` on the provider.

**Updated claim**: §Q3 `[TRAINING_ONLY — verify Android AICore/ML Kit Gemini Nano Kotlin API]`
and the note on Apple Intelligence — `FoundationModels` is now a real, accessible framework.

**Sources**:
- [Foundation Models — Apple Developer Documentation](https://developer.apple.com/documentation/FoundationModels)
- [Apple Announces Foundation Models Framework — MacRumors](https://www.macrumors.com/2025/06/09/foundation-models-framework/)
- [WWDC 2025 Session 301 — Deep dive into Foundation Models](https://developer.apple.com/videos/play/wwdc2025/301/)

---

### 6. openai-kotlin v4.x — Ktor 3.x Compatible

**Query**: `openai-kotlin Ktor 3.x compatibility version 2025 2026`

**Verdict**: CONFIRMED. `openai-kotlin` v4.1.0 supports Ktor 3.x. The version-conflict risk
that would have blocked adoption (noted under §Migration and Adoption Cost) is resolved. The
library can now be evaluated without Ktor shading concerns. The raw Ktor recommendation still
stands for simplicity, but adopting `openai-kotlin` 4.x is no longer risky from a version
conflict standpoint.

**Sources**:
- [Issue #411: ktor 3.x — aallam/openai-kotlin](https://github.com/aallam/openai-kotlin/issues/411)
- [Releases — aallam/openai-kotlin](https://github.com/aallam/openai-kotlin/releases)
