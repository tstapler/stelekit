# Research Synthesis: Mobile Voice Mode

**Date**: 2026-04-18
**Sources**: stack.md, features.md, architecture.md, pitfalls.md (all web-search-verified)
**Next step**: Write ADRs covering audio capture adapter, STT provider interface, LLM provider interface, and UX state machine

---

## Decision Required

Four architectural decisions must be made before implementation begins:

1. **Audio capture shape** — `expect class` vs plain `interface` + constructor injection; output format (PCM vs m4a file)
2. **STT provider interface and tier defaults** — which providers ship in v1, which are deferred, and how tiering works
3. **LLM provider interface and tier defaults** — same question for the formatting step
4. **UX state machine** — what states the mic button exposes and how Phase 1/2/3 scope gates land

---

## Context

SteleKit is a KMP app targeting Android and iOS from a single `commonMain`. It already has:
- Ktor 3.1.3 in `commonMain` (OkHttp engine on Android, Darwin on iOS)
- `kotlinx.serialization` in `commonMain`
- A proven `suspend fun interface` + `NoOp` seam pattern, established by `TopicEnricher` / ADR-002
- `JournalService` with `ensureTodayExists()` guarded by a `Mutex`
- `PlatformBottomBar.android.kt` with existing 4-item nav bar

The feature adds a voice capture pipeline: mic button → audio file → STT → LLM format → `JournalService.appendToToday()`. No new framework dependencies are required for the Whisper + cloud LLM happy path.

**Scope from requirements.md** (confirmed constraints for synthesis):
- Must Have: single-tap trigger, unlimited-duration recording, STT, LLM → Logseq format, journal insert, extensible provider API
- Out of Scope: real-time transcription display, background/passive listening, Desktop support, multi-language

---

## Options Considered

### Option A — `expect class AudioRecorder` (monolithic platform class)

Every property and method in `expect` must mirror in every `actual`. Adding a capability later forces all platform actuals to update in lockstep. Desktop requires a stub `actual` even though Desktop is explicitly out of scope. `PlatformFileSystem` uses this approach because it is passed to many constructors; `AudioRecorder` is only used in one ViewModel.

**Verdict**: Eliminated. Unjustified rigidity for a single-consumer class.

### Option B — Plain `interface AudioRecorder` in `commonMain`, platform impls injected at assembly (recommended)

Matches ADR-002 seam pattern exactly. No `expect`/`actual` required. Android wires `AndroidAudioRecorder`, iOS wires `IosAudioRecorder`, Desktop gets `NoOpAudioRecorder` by default. Adding a capability to the interface does not force platform stubs.

**Verdict**: Selected. Zero KMP machinery overhead; identical to how `TopicEnricher` works.

### Option C — Raw PCM streaming (`Flow<ByteArray>`)

Enables live waveform animation. Requires assembling PCM into a file before Whisper upload anyway (Whisper rejects raw PCM). "Real-time transcription display while speaking" is explicitly out of scope in requirements.md.

**Verdict**: Deferred to Phase 2 (waveform animation only). v1 records to a temp `.m4a` file.

### Option D — OpenAI Whisper as the only STT path (commonMain only)

Simpler: one implementation, works on all targets. No platform-specific STT code. Costs $0.006/min (`whisper-1`) or $0.003/min (`gpt-4o-mini-transcribe`). Requires network. The Whisper API explicitly accepts `.m4a` (confirmed by web search — no format conversion needed).

**Verdict**: Selected as the v1 default (`WhisperSpeechToTextProvider`). Platform STT providers are optional built-in adapters behind the same interface.

### Option E — Android ML Kit GenAI `createOnDeviceSpeechRecognizer` as Tier 1 default

Web-search-confirmed: ML Kit GenAI Speech Recognition is a stable public API (API 31+, basic mode; Gemini Nano quality on Pixel 9/10 and Galaxy S25/S26+). Free, on-device, streaming output. **Hard constraint**: blocked when app is not the top foreground activity (`ErrorCode.BACKGROUND_USE_BLOCKED`). Cannot be used from a foreground service.

**Verdict**: Valid Tier 1 for Phase 1 (foreground-only recording). Add as `AndroidMlKitSpeechToTextProvider` behind the `SpeechToTextProvider` interface. Users on supported devices get free on-device STT; fallback to Whisper API when unavailable or backgrounded.

### Option F — `SFSpeechRecognizer` (iOS) as Tier 1 default

Free, on-device capable (iOS 13+ with device model), no cost, streaming output. `requiresOnDeviceRecognition = true` on supported devices. Duration limit per recognition task requires chunking for long recordings.

**Verdict**: Valid Tier 1 for iOS. Add as `IosSpeechToTextProvider`.

### Option G — WhisperKit (iOS, Tier 2)

Confirmed production-ready at ICML 2025 (Argmax). Runs on Core ML / Apple Neural Engine. Fully offline. VAD, word timestamps, streaming. Swift Package Manager. Device gate: iPhone 12+ (hardware Neural Engine).

**Verdict**: Tier 2 / Phase 2 for iOS on-device. Not v1 — adds cinterop complexity and model download UX.

### Option H — FoundationModels (iOS LLM, Tier 2)

Apple shipped `FoundationModels` at WWDC 2025. Text-in / text-out API. Offline, no cost. Device gate: iPhone 15 Pro+, iOS 18.1+. Directly usable as `LlmProvider` in `iosMain`. Not STT — does not replace Whisper.

**Verdict**: Tier 2 LLM backend for iOS. Planned but not v1. Add as `AppleIntelligenceLlmProvider` in `iosMain` behind the `LlmProvider` interface.

### Option I — `openai-kotlin` v4.1.0 library

Web-search-confirmed: `com.aallam.openai:openai-kotlin` v4.1.0 supports Ktor 3.x. The prior Ktor 2.x conflict risk is resolved. However, the library adds ~1–2 MB and typed models that are not needed for the narrow `transcribe()` and `format()` call surface. Raw Ktor is 50 lines per provider and already present.

**Verdict**: Not adopted in v1. Raw Ktor calls are simpler and already present. Revisit if typed model coverage becomes valuable.

### Option J — Koin DI for provider wiring

Not currently used in SteleKit. Introducing it for one feature adds a new dependency and pattern inconsistent with the rest of the codebase (`App.kt` uses `remember {}` blocks, not a DI graph).

**Verdict**: Eliminated. Constructor injection with NoOp defaults is the established pattern.

### Option K — `MediaRecorder` (Android) vs `AudioRecord` (raw PCM)

Multiple Android developers and the pitfalls research confirm: `MediaRecorder` produces corrupted MP4 box headers when interrupted by a phone call or audio focus loss, because it has no pause/resume on API < 24. `AudioRecord` reads raw PCM into a buffer; on interruption you can pause reads and resume. Whisper rejects raw PCM — but `.m4a` from `MediaRecorder` is accepted directly (web-search-confirmed).

**Critical resolution**: Use `AudioRecord` (raw PCM) for reliability, then encode to `.m4a` via `MediaCodec` before upload. This avoids the `MediaRecorder` corruption failure mode while producing a Whisper-compatible format. The encoding step adds ~50 lines of Android code.

**Verdict**: `AudioRecord` + `MediaCodec` AAC encoder on Android. `AVAudioRecorder` (outputs `.m4a` natively) on iOS.

---

## Dominant Trade-off

**On-device STT (free, private, foreground-only) vs cloud Whisper (paid, universal, any context)**

This is the central tension. The resolution is a tiered architecture where the tier is selected at runtime based on platform, API level, and whether the app is in the foreground:

```
Android Phase 1 (foreground):  ML Kit GenAI STT (free, API 31+) → fallback: Whisper API
Android Phase 3 (background):  Whisper API only (ML Kit GenAI is foreground-blocked)
iOS Phase 1 (foreground):      SFSpeechRecognizer (free, iOS 13+) → fallback: Whisper API
iOS Phase 2 (foreground):      WhisperKit on-device → fallback: Whisper API
iOS LLM Phase 2:               FoundationModels (iPhone 15 Pro+) → fallback: cloud LLM
```

The `SpeechToTextProvider` and `LlmProvider` interfaces hide this entirely from `VoiceCaptureViewModel`.

---

## Recommendation

### Interface Shapes (follow ADR-002 seam pattern exactly)

```kotlin
// commonMain — audio capture (plain interface, not expect/actual)
interface AudioRecorder {
    suspend fun recordToFile(): PlatformAudioFile   // blocks until stopRecording() called
    suspend fun stopRecording()
}

// commonMain — STT provider seam
fun interface SpeechToTextProvider {
    suspend fun transcribe(audio: PlatformAudioFile): TranscriptResult
}

sealed interface TranscriptResult {
    data class Success(val text: String) : TranscriptResult
    data object Empty : TranscriptResult
    sealed interface Failure : TranscriptResult {
        data object NetworkError : Failure
        data class ApiError(val code: Int, val message: String) : Failure
        data object AudioTooShort : Failure
        data object PermissionDenied : Failure
    }
}

// commonMain — LLM formatting provider seam
fun interface LlmProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}

sealed interface LlmResult {
    data class Success(val formattedText: String) : LlmResult
    sealed interface Failure : LlmResult {
        data object NetworkError : Failure
        data class ApiError(val code: Int, val message: String) : Failure
        data object Timeout : Failure
        data class MalformedResponse(val raw: String) : Failure
    }
}
```

All interfaces follow ADR-002's `suspend fun interface` + `NoOp` default pattern. `VoiceCaptureViewModel` receives all three via constructor injection with NoOp defaults. `StelekitApp` gains `sttProvider` and `llmProvider` optional parameters threaded through to `GraphContent` — identical to how `urlFetcher` is wired today.

### Provider Tiers

**STT providers (ship in priority order):**

| Tier | Provider | Impl location | Condition |
|------|----------|--------------|-----------|
| 0 | `NoOpSpeechToTextProvider` | `commonMain` | Default / tests |
| 1a | `AndroidMlKitSpeechToTextProvider` | `androidMain` | API 31+, foreground-only; uses `createOnDeviceSpeechRecognizer` |
| 1b | `IosSpeechToTextProvider` | `iosMain` | iOS 13+; `SFSpeechRecognizer` with chunking |
| 2 | `WhisperSpeechToTextProvider` | `commonMain` | Ktor multipart POST; user-supplied API key; fallback on all platforms |
| 3 | `WhisperKitSpeechToTextProvider` | `iosMain` (Phase 2) | iPhone 12+; on-device Core ML |

**LLM providers (ship in priority order):**

| Tier | Provider | Impl location | Condition |
|------|----------|--------------|-----------|
| 0 | `NoOpLlmProvider` | `commonMain` | Default / tests (returns transcript verbatim) |
| 1 | `ClaudeLlmProvider` | `commonMain` | Ktor + Anthropic Messages API; user-supplied key |
| 1 | `OpenAiLlmProvider` | `commonMain` | Ktor + OpenAI Chat Completions; user-supplied key |
| 2 | `AppleIntelligenceLlmProvider` | `iosMain` (Phase 2) | FoundationModels; iPhone 15 Pro+, iOS 18.1+; offline, no cost |

### VoiceCaptureViewModel State Machine

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
// Any state → Idle on cancel or dismiss
```

### UX Phases

**Phase 1** (v1 scope): FAB-style mic button in `PlatformBottomBar`. Tap to record (enters `Recording` state), tap again to stop. Post-stop: Whisper API transcribes → `ClaudeLlmProvider` or `OpenAiLlmProvider` formats → appended to today's journal as a timestamped block. Raw transcript preserved as a collapsible block below the formatted output. No lock screen access.

**Phase 2** (post-v1): Add `AndroidMlKitSpeechToTextProvider` (foreground) and `IosSpeechToTextProvider` as default on-device options — user gets free STT without an API key. Add `AppleIntelligenceLlmProvider` on iOS. Add live waveform animation via `Flow<ByteArray>` from `AudioRecord`.

**Phase 3** (post-v1): Foreground service on Android with notification action button (lock-screen access). Requires `android:foregroundServiceType="microphone"` in manifest + `FOREGROUND_SERVICE_MICROPHONE` permission (targetSdk ≥ 34). The service must be started while the app is still visible — cannot be started from background (Android 12+). On iOS, lock screen widget via iOS Widget APIs.

### Audio Pipeline

```
Android:  AudioRecord (raw PCM, VOICE_COMMUNICATION source) 
          → MediaCodec AAC encoder 
          → .m4a temp file in cacheDir
          → WhisperSpeechToTextProvider (Ktor multipart POST)
          → delete temp file (in finally block)

iOS:      AVAudioRecorder (AVAudioSession.setCategory(.record) before start)
          → .m4a temp file in NSTemporaryDirectory
          → WhisperSpeechToTextProvider (Ktor multipart POST)
          → delete temp file (in finally block)
          → restore AVAudioSession category to .playback/.ambient after stop
```

Whisper API file size limit is 25 MB. At 128 kbps AAC, 25 MB ≈ 26 minutes. For recordings approaching this limit, split at silence gaps (VAD) before upload. For Phase 1, a simple warning at 20 minutes is sufficient.

### Critical Non-Negotiable Rules

These are confirmed failure modes from web-search-verified evidence. Each must be enforced at implementation time:

1. **iOS AVAudioSession category must be set before `AVAudioEngine`/`AVAudioRecorder` starts.** Forgetting it produces silent recording — empty audio, no error, Whisper returns "Thank you." Silent failure is the worst UX outcome.

2. **Use `AudioRecord` (raw PCM) on Android, not `MediaRecorder`.** `MediaRecorder` writes corrupted MP4 box headers on audio focus loss (phone call interruption). `AudioRecord` can pause and resume cleanly. Encode to AAC via `MediaCodec` before upload.

3. **Android 14+: `foregroundServiceType="microphone"` in manifest AND `FOREGROUND_SERVICE_MICROPHONE` permission (Phase 3 only).** Omitting either silently denies mic access — no crash, no audio. The foreground service must be running in the foreground *before* the app is backgrounded; it cannot be started from the background (Android 12+).

4. **Gate Whisper uploads on word count.** Whisper hallucinate "Thank you." and similar tokens on silent/near-silent audio (confirmed, tracked in openai/whisper discussion #1606, ICML 2025 Calm-Whisper). Rule: if `transcript.split().size < 10`, treat as `TranscriptResult.Empty` and do not call the LLM. A VAD gate before upload is the deeper fix.

5. **`[[link]]` hallucination in v1: do not pass the graph page index to the LLM.** Instead, constrain the system prompt: "Do NOT create `[[wiki links]]` unless the exact page name was explicitly stated in the transcript." Always append the raw transcript below the formatted output (collapsible) so the user can verify what was captured vs what the LLM produced.

6. **Microphone permission: request before recording, fail fast on denial.** Android: wrap `AudioRecord.startRecording()` in `try/catch(SecurityException)`. iOS: check `AVAudioSession.recordPermission` before configuring the session — if `.denied`, navigate to Settings. Never call `AVAudioEngine.start()` without confirmed `.granted`.

7. **`VOICE_COMMUNICATION` audio source on Android.** Use this `AudioSource` constant (not `DEFAULT`) when constructing `AudioRecord`. It applies system-level noise cancellation and echo suppression, which improves Whisper accuracy significantly in driving/ambient-noise scenarios.

8. **Temp file cleanup in `finally` block.** The audio temp file must be deleted after `transcribe()` returns regardless of success or failure. A leaked `.m4a` file at ~1 MB/min will fill device storage. `VoiceCaptureViewModel` owns cleanup, not the platform recorder.

---

## Open Questions Before Committing

These require short implementation spikes before the ADRs can be finalized:

1. **ML Kit GenAI STT reliability for 5–10 minute recordings.** The API provides streaming partial + final results, not batch. Does it handle long sessions stably, or does it require chunking like `SFSpeechRecognizer`? A 15-minute spike recording on a Pixel 9 would answer this.

2. **FoundationModels structured output for Logseq format.** Does `FoundationModels` produce well-structured outliner output with a simple system prompt on first try? The formatting constraint (`- bullets`, 2-space indent, `[[links]]` only for named entities) is strict. A spike with 3–5 sample transcripts would establish whether zero-shot works or whether guided generation / few-shot is required.

3. **Optimal system prompt for Logseq outliner format.** No tool surveyed produces Logseq `- item\n  [[link]]` syntax natively. The prompt template needs iteration: top-level bullets for main topics, 2-space-indented sub-bullets, `[[Entity]]` only for proper nouns explicitly named in speech, no preamble, no trailing summary. Spike: 10 diverse transcript samples → measure format compliance rate.

4. **`StelekitApp` parameter threading strategy.** `sttProvider` and `llmProvider` would be the 3rd and 4th optional parameters added following `urlFetcher`. Consider bundling them in a `VoicePipelineConfig` data class before threading further grows the constructor signature.

5. **iOS bottom bar existence.** Does `PlatformBottomBar` have an `iosMain` actual, or does it share the Android composable? The mic button must appear on both platforms. Confirm before designing the `VoiceCaptureButton` placement.

---

## Sources

### Web-Search-Verified Claims

All findings below are confirmed by live web search performed 2026-04-18.

**ML Kit GenAI Speech Recognition (Android)**
- [GenAI Speech Recognition API — Google for Developers](https://developers.google.com/ml-kit/genai/speech-recognition/android)
- [Android Developers Blog: On-device GenAI APIs with ML Kit (May 2025)](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html)
- [ML Kit GenAI APIs — Android Developers](https://developer.android.com/ai/gemini-nano/ml-kit-genai)

**`createOnDeviceSpeechRecognizer` introduced at API 31 (not 33)**
- [SpeechRecognizer — Android Developers](https://developer.android.com/reference/android/speech/SpeechRecognizer)

**Apple FoundationModels framework (WWDC 2025)**
- [Foundation Models — Apple Developer Documentation](https://developer.apple.com/documentation/FoundationModels)
- [Apple's Foundation Models framework — Apple Newsroom (Sept 2025)](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/)
- [WWDC 2025 Session 301 — Deep dive into Foundation Models](https://developer.apple.com/videos/play/wwdc2025/301/)

**WhisperKit production-ready (ICML 2025)**
- [argmaxinc/WhisperKit — GitHub](https://github.com/argmaxinc/WhisperKit)

**openai-kotlin v4.1.0 — Ktor 3.x compatible**
- [Issue #411: ktor 3.x — aallam/openai-kotlin](https://github.com/aallam/openai-kotlin/issues/411)
- [Releases — aallam/openai-kotlin](https://github.com/aallam/openai-kotlin/releases)

**Whisper API accepted formats (m4a confirmed)**
- [Audio API FAQ — OpenAI Help Center](https://help.openai.com/en/articles/7031512-whisper-audio-api-faq)

**Whisper API pricing ($0.006/min whisper-1, $0.003/min gpt-4o-mini-transcribe)**
- [OpenAI API Pricing](https://openai.com/api/pricing/)

**Whisper hallucination on silence (confirmed, unresolved upstream)**
- [openai/whisper Discussion #1606](https://github.com/openai/whisper/discussions/1606)
- [Calm-Whisper (arXiv 2025)](https://arxiv.org/html/2505.12969v1)

**Android foregroundServiceType="microphone" requirements**
- [Foreground service types are required — Android 14 — Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Foreground service types — Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)

**moko-permissions v0.20.1 — CMP compatible**
- [icerockdev/moko-permissions — GitHub](https://github.com/icerockdev/moko-permissions)

**koog (JetBrains) — KMP confirmed, pre-1.0**
- [JetBrains/koog — GitHub](https://github.com/JetBrains/koog)

### Prior Art

- **Reflect.app** — lock screen widget + auto-append to daily note is the closest prior art. Validates the daily note auto-append UX.
- **AudioPen** — "what I meant to say" LLM synthesis model (rewrite, not transcribe). AudioPen proves users prefer formatted output over verbatim transcript.
- **Otter.ai** — two-phase display (live transcript during recording + formatted summary after) is the best system-state UX. Adopt for Phase 2.
- **NotelyVoice (OSS, Compose Multiplatform)** — chunked overlap transcription for long recordings is solved in KMP. Reference for chunking implementation.
- **ADR-002 (this codebase)** — `TopicEnricher` seam pattern is the template for all three provider interfaces in this feature.
