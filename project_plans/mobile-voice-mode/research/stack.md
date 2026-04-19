# Findings: Stack — KMP Voice-Capture Feature

_Research date: 2026-04-18. Sections marked [TRAINING_ONLY — verify] indicate claims from
training data not confirmed by live web search._

---

## Summary

SteleKit already has Ktor 3.1.3 in `commonMain`, Kotlin serialization, and coroutines. That
baseline covers the LLM client side without adding new dependencies. Audio recording has no
KMP-native abstraction — it requires `expect/actual` with thin platform adapters on every target.
Speech recognition similarly lives behind platform APIs; the best default is OpenAI Whisper via
HTTP (multipart upload from commonMain) because it is the only STT option that works identically
across Android, iOS, and Desktop from a single code path. On-device STT (Android AICore /
Apple Intelligence) is an optional accelerator added as pluggable backends behind a common
`SpeechToTextProvider` interface.

---

## Options Surveyed

### 1. Audio Recording APIs

| Layer | Option | KMP Support |
|---|---|---|
| commonMain | No standard API exists | — |
| androidMain | `android.media.AudioRecord` (raw PCM) or `MediaRecorder` (MP4/AAC) | Android-only |
| iosMain | `AVAudioEngine` / `AVAudioRecorder` (Obj-C interop via cinterop) | iOS-only |
| jvmMain | `javax.sound.sampled.TargetDataLine` (raw PCM) | JVM/Desktop-only |
| KMP library | `KmpAudio` / `multiplatform-audio` — no mature, maintained library confirmed [TRAINING_ONLY — verify] | Partial |

**Conclusion**: Audio recording must be an `expect/actual`. The common interface emits a
`Flow<ByteArray>` of raw PCM chunks; each platform implements recording and streaming into that
flow. The common layer collects chunks, concatenates, and dispatches to the STT provider.

**Recommended audio format**: 16 kHz, 16-bit mono PCM → WAV wrapper. This matches Whisper's
native input and Android/iOS SpeechRecognizer expectations.

---

### 2. Speech-to-Text Options

#### 2a. OpenAI Whisper API (remote)

- Endpoint: `POST https://api.openai.com/v1/audio/transcriptions`
- Accepts WAV/MP3/M4A up to 25 MB; returns plain text or verbose JSON with timestamps.
- Implementable in `commonMain` via Ktor multipart form upload.
- Cost: $0.006 / min (Whisper-1, as of training cutoff) [TRAINING_ONLY — verify current pricing].
- Accuracy: state-of-the-art English WER ~2–5% on clean speech.
- Latency: 1–5 s for a 1-min clip over a good connection.
- **Works on all targets** (Android, iOS, Desktop, Web) — single implementation.

#### 2b. Android SpeechRecognizer (on-device + cloud hybrid)

- `android.speech.SpeechRecognizer` — streaming, returns partial and final results.
- On Android 13+ (API 33) supports `createOnDeviceSpeechRecognizer()` for full offline use
  [TRAINING_ONLY — verify API 33 availability and exact method name].
- Requires `RECORD_AUDIO` permission; streams audio internally; no file upload.
- Limit: Android-only, not shareable with iOS/Desktop. Requires a separate adapter.
- Accuracy: competitive with Whisper for US English; variable for accented speech.
- Cost: free (Google cloud STT bundled via Play Services on most devices).
- Min SDK: SteleKit targets minSdk 24; `SpeechRecognizer` available since API 8.

#### 2c. iOS SFSpeechRecognizer (on-device + Siri cloud)

- `Speech.SFSpeechRecognizer` (iOS 10+) with `SFSpeechAudioBufferRecognitionRequest`.
- Supports `.requiresOnDeviceRecognition = true` (iOS 13+, device-dependent model availability).
- Exposed to Kotlin/Native via cinterop (framework `Speech`).
- Apple mandates privacy usage description in `Info.plist` (`NSSpeechRecognitionUsageDescription`).
- Request duration limit: ~1 min per recognition task; must chunk for unlimited recording
  [TRAINING_ONLY — verify current limit, historically 60 s].
- Cost: free; data leaves device only if on-device model unavailable.

#### 2d. Android AICore / Gemini Nano (on-device ML)

- Android AICore (Android 14+, API 34+) provides `DownloadCallback`-based model access
  [TRAINING_ONLY — verify exact API surface].
- Gemini Nano is the on-device model; speech input is handled indirectly through
  `android.speech` or the AICore Multimodal API.
- As of training cutoff: AICore STT API is not a stable public SDK — primary interface is still
  `SpeechRecognizer` or file-based. Gemini Nano is used for text processing, not raw ASR.
- **Verdict**: AICore is relevant for the LLM formatting step (grammar/structure), not STT.
  Treat as a future backend for `TextFormatterProvider`.

#### 2e. Apple Intelligence / on-device LLM (iOS 18+)

- Apple Intelligence (iOS 18+, Apple Silicon devices) exposes Writing Tools and summarization
  through UIKit/SwiftUI APIs, not a developer-callable STT or LLM inference API.
- No public `NaturalLanguage` or `CoreML`-based Apple Intelligence inference API exists for
  third-party apps as of training cutoff [TRAINING_ONLY — verify].
- **Verdict**: not usable as an STT or LLM backend via Kotlin/Native today. Revisit when Apple
  publishes an inference API.

---

### 3. LLM API Client Options

#### 3a. Ktor (existing in project)

- Already in `commonMain` (`io.ktor:ktor-client-core:3.1.3`). Zero additional dependency.
- Each platform engine is already configured: OkHttp (Android/JVM), Darwin (iOS).
- Supports streaming SSE (`response.bodyAsChannel()` + line parsing).
- JSON serialization: `kotlinx.serialization` already present.
- **All LLM providers** (OpenAI, Anthropic, Groq, etc.) expose REST+JSON; Ktor covers all of
  them from a single `HttpClient` in `commonMain`.

#### 3b. OpenAI Kotlin SDK (official)

- `com.aallam.openai:openai-kotlin` — KMP-compatible, uses Ktor under the hood
  [TRAINING_ONLY — verify current version and KMP support matrix].
- Targets: JVM, Android, iOS (via Ktor Darwin engine), JS.
- Provides typed request/response models for Chat, Audio, Embeddings, etc.
- Adds ~1–2 MB to app size; brings its own Ktor version which may conflict with project's 3.1.3.
- Version alignment risk: if `openai-kotlin` pins Ktor 2.x, it will conflict with SteleKit's 3.x.

#### 3c. Anthropic SDK (official)

- No official KMP SDK exists as of training cutoff [TRAINING_ONLY — verify].
- Anthropic publishes a JVM/Android SDK (`com.anthropic:sdk`) but it is not KMP-compatible.
- Approach for commonMain: call Messages API directly via Ktor (`POST /v1/messages`).
  Anthropic's REST API is well-documented and stable.

#### 3d. Other KMP AI/LLM Wrappers

- `kmp-openai` — no well-known library with this exact artifact ID found [TRAINING_ONLY — verify
  via Maven/GitHub search; see Pending Web Searches].
- `kotlin-ai` / `langchain4j-kotlin` — LangChain4j has a Kotlin integration but is JVM-only
  [TRAINING_ONLY — verify].
- `koog` — JetBrains' own KMP AI agent framework; targets KMP with coroutines-native agents
  [TRAINING_ONLY — verify; was announced ~2025].
- **Verdict**: no third-party KMP LLM wrapper is mature enough to prefer over direct Ktor calls.
  Roll a thin `LlmClient` in `commonMain` backed by Ktor.

---

### 4. Plugin / Extensibility Architecture

The feature must allow third parties to add STT and LLM backends. Recommended pattern:

```kotlin
// commonMain
interface SpeechToTextProvider {
    val id: String
    val supportsOnDevice: Boolean
    suspend fun transcribe(audio: AudioCapture): String
}

interface TextFormatterProvider {
    val id: String
    suspend fun format(rawTranscript: String, context: JournalContext): FormattedOutline
}

interface AudioRecorder {
    fun startRecording(): Flow<ByteArray>  // raw PCM chunks
    suspend fun stopRecording()
}
```

Platform-specific providers register themselves in `androidMain`/`iosMain`/`jvmMain`. A
`ProviderRegistry` in `commonMain` holds the active set. This is the same pattern as SteleKit's
existing `RepositoryFactory` abstraction.

---

## Trade-off Matrix

| Option | KMP Compat | Offline | Accuracy | Cost | Setup Complexity | Extensible |
|---|---|---|---|---|---|---|
| Whisper API (Ktor) | Full (commonMain) | No | Excellent | $0.006/min | Low — Ktor already present | Yes — default backend |
| Android SpeechRecognizer | Android only (expect/actual) | Partial (API 33+) | Good | Free | Medium | Yes — adapter |
| iOS SFSpeechRecognizer | iOS only (expect/actual) | Partial (iOS 13+) | Good | Free | Medium (cinterop) | Yes — adapter |
| openai-kotlin library | KMP (JVM/Android/iOS) | No | Excellent | $0.006/min | Medium (Ktor version risk) | Limited |
| Direct Ktor (LLM calls) | Full (commonMain) | No | N/A | Per provider | Low — already present | Yes |
| Android AICore / Gemini Nano | Android 14+ only | Yes | Unknown (STT not direct) | Free | High (early API) | Possible future |
| Apple Intelligence | None (no public API) | Yes (conceptually) | N/A | Free | N/A | No |
| koog (JetBrains) | KMP (unverified) | No | N/A | Per provider | Unknown | Unknown |

---

## Risk and Failure Modes

### STT Risks

1. **Whisper upload size limit (25 MB)**: A 1-hour recording at 16 kHz 16-bit mono WAV = ~115 MB.
   Must chunk audio server-side or stream. Mitigation: chunk at 10-min intervals (~11.5 MB each),
   or compress to MP3/Opus before upload (10:1 ratio → ~11.5 MB/hr).

2. **iOS SFSpeechRecognizer 60-second limit**: Must create a new recognition task per chunk.
   Stitching transcripts requires careful overlap detection to avoid dropped words at boundaries.

3. **Android SpeechRecognizer network dependency**: On older devices (< API 33) the on-device
   model may not be present; the recognizer silently falls back to Google cloud STT, which
   requires network. Unexpected failures in airplane mode.

4. **API key theft**: Keys stored on-device are extractable. Mitigation: support per-user key
   entry (no bundled key), proxy mode (user's own backend), or OAuth token exchange.

5. **Whisper hallucinations on silence**: Whisper produces confabulated text on silent or
   noise-only audio. Mitigation: VAD (voice activity detection) gate before sending segments.

### LLM Risks

1. **Streaming response parsing**: SSE parsing via Ktor `bodyAsChannel()` is low-level. A malformed
   chunk from the provider can stall the flow. Need robust chunked-line parser with timeout.

2. **Context window for long transcripts**: A 1-hour recording may produce ~10,000 words (~13,000
   tokens). GPT-4o context (128k) handles this, but older models (gpt-3.5, 4k context) will
   truncate. Must select model with adequate context or summarize incrementally.

3. **Prompt injection via transcript**: A malicious speaker could embed prompt-injection text.
   Mitigation: wrap transcript in XML delimiters; instruct model the enclosed text is untrusted
   audio content.

### Audio Recording Risks

1. **Background audio interruption (iOS)**: `AVAudioSession` category must be set to
   `.record` or `.playAndRecord`; phone calls, Siri, and other apps will interrupt the session.
   Must implement `AVAudioSessionInterruptionNotification` handler.

2. **ANR risk (Android)**: `AudioRecord` must run on a non-main thread. Use a dedicated
   `CoroutineDispatcher(IO)` for the recording loop.

3. **Large in-memory buffer**: 1 hour at 16 kHz 16-bit mono = ~115 MB RAM. Stream to a temp
   file rather than buffering in memory.

---

## Migration and Adoption Cost

### Baseline (Ktor + Whisper API only, no on-device)

- New code: `AudioRecorder` expect/actual (3 platform implementations) + `WhisperSttProvider`
  (commonMain, ~150 lines) + `LlmFormatterProvider` (commonMain, ~200 lines).
- No new Gradle dependencies beyond what exists.
- Estimated effort: 1–2 sprints for a working end-to-end path.

### Adding Android SpeechRecognizer adapter

- New code: `AndroidSpeechRecognizerProvider` in `androidMain` (~100 lines).
- No new dependencies; `android.speech` is part of the Android SDK.
- Estimated effort: 0.5 sprints.

### Adding iOS SFSpeechRecognizer adapter

- New code: cinterop binding for `Speech.framework` (add to `iosMain/cinterop`) +
  `IosSpeechRecognizerProvider` (~150 lines).
- Estimated effort: 1 sprint (cinterop setup is the bulk of the work).

### Adding `openai-kotlin` library

- Risk: Ktor version conflict. Would need to verify `com.aallam.openai:openai-kotlin` supports
  Ktor 3.x before adopting. If it does not, the project must either stay on direct Ktor calls
  or shade the library. Recommend staying with direct Ktor unless typed models are a priority.

---

## Operational Concerns

1. **API key management**: No server-side proxy in SteleKit today. The initial design should
   store the user's own API key in platform keychain (`KeyStore` on Android, `Keychain` on iOS,
   `SecretService`/file on Desktop). Do not bundle a shared key.

2. **Offline graceful degradation**: When no network is available and no on-device provider is
   configured, the UI must inform the user and disable the mic button rather than silently failing.

3. **Audio file cleanup**: Temp WAV files written to the cache directory must be deleted after
   successful transcription. A leak of 115 MB/hr per recording session will fill storage quickly.

4. **Battery impact**: Continuous `AudioRecord` with 16 kHz sampling is low CPU but the Whisper
   upload + LLM call are network-intensive. Warn users if on a metered connection.

5. **Privacy disclosure**: Both Android (`RECORD_AUDIO` permission) and iOS
   (`NSSpeechRecognitionUsageDescription`, `NSMicrophoneUsageDescription`) require explicit
   user consent UI before recording. These disclosures must be truthful about cloud processing.

---

## Prior Art and Lessons Learned

- **Whisper.cpp on-device (JVM/Desktop)**: whisper.cpp has JNI bindings (`io.github.ggerganov:
  whisper-jni`) that run on desktop JVM [TRAINING_ONLY — verify artifact coordinates]. This
  enables fully offline Desktop transcription. Not feasible for Android (APK size ~150 MB for
  the model) without dynamic model download.

- **Vosk** (offline ASR): Apache-licensed, KMP-unfriendly (C library requiring JNI), supports
  offline English with ~5% WER. Usable on JVM Desktop and Android via JNI. Not usable on iOS
  without additional porting work. Not recommended as primary path.

- **Logseq's own voice note feature (if any)**: Logseq does not have a built-in voice-to-journal
  feature as of training cutoff. This is a differentiating feature for SteleKit.

- **Obsidian's approach**: Community plugins (e.g., `obsidian-whisper`) implement voice notes
  by recording locally and sending WAV to Whisper API. The plugin pattern directly validates
  the plugin-API approach planned here.

- **Apple WWDC 2024 / on-device ML**: Apple Intelligence Writing Tools have no public inference
  API. Third-party apps cannot call the on-device LLM directly. Apple may expose this via a
  future `FoundationModels` framework — watch WWDC 2025/2026.

- **Google AI Edge / MediaPipe**: Google provides on-device ASR via MediaPipe `AudioClassifier`
  and `SpeechEmbedder`, but not a full transcription pipeline. Gemini Nano on-device for Android
  supports text tasks via `com.google.android.gms:play-services-mlkit-subject-segmentation`-style
  API; actual on-device ASR is still via `SpeechRecognizer` [TRAINING_ONLY — verify current
  MediaPipe Audio Task API].

---

## Open Questions

1. **Desktop audio**: Should Desktop (JVM) support voice capture at all in v1? `javax.sound.sampled`
   works but the UX story (laptop mic, no mobile form factor) is weak. Recommend deferring Desktop
   audio to v2.

2. **Chunking strategy**: At what interval should the audio be chunked for upload — fixed time
   (e.g., 10 min), silence detection (VAD), or on stop? VAD is more accurate but adds complexity.

3. **LLM prompt design**: The formatting prompt (raw transcript → outliner bullets) needs iteration.
   Should we store the prompt in a user-editable template, or hard-code v1?

4. **OpenAI Whisper vs Whisper on `openai-kotlin`**: If `openai-kotlin` aligns with Ktor 3.x,
   does it provide enough value (typed models, retry logic) to justify the dependency over raw Ktor?
   Requires live Maven search (see Pending Web Searches).

5. **Streaming transcription**: Whisper API is batch (upload file, receive transcript). Is there
   appetite for real-time streaming STT (e.g., OpenAI Realtime API, Deepgram, AssemblyAI)?
   These use WebSockets; Ktor 3.x has WebSocket support in `ktor-client-websockets`. Scope for v2.

6. **koog (JetBrains KMP agent framework)**: Is it stable enough and does it add value over a
   hand-rolled `LlmFormatterProvider`? Requires current GitHub/Maven research.

7. **VAD library in KMP**: Is there a KMP-compatible voice activity detection library, or must
   it be a platform expect/actual using WebRTC VAD (Android), `AVAudioEngine` noise detection
   (iOS)?

---

## Recommendation

### Default architecture (v1)

```
commonMain
  AudioRecorder (expect/actual interface)
  WhisperSttProvider       ← implements SpeechToTextProvider, uses Ktor multipart POST
  OpenAiFormatterProvider  ← implements TextFormatterProvider, uses Ktor streaming SSE
  AnthropicFormatterProvider ← same interface, different endpoint
  ProviderRegistry         ← holds active STT + LLM provider, swappable

androidMain
  AndroidAudioRecorder     ← AudioRecord, 16 kHz 16-bit mono, streams ByteArray flow
  AndroidSpeechRecognizerProvider ← optional on-device STT backend (API 33+)

iosMain
  IosAudioRecorder         ← AVAudioEngine tap, same format
  IosSpeechRecognizerProvider ← optional on-device STT (SFSpeechRecognizer, chunked)

jvmMain
  JvmAudioRecorder         ← javax.sound.sampled (Desktop, v2 scope)
```

**STT default**: OpenAI Whisper API via Ktor (commonMain). No platform-specific code for the
happy path. Platform on-device providers are opt-in backends registered at app startup.

**LLM default**: OpenAI Chat Completions API via raw Ktor (`gpt-4o`, streaming). Anthropic as
a second built-in backend. No third-party KMP SDK dependency until `openai-kotlin` Ktor 3.x
compatibility is confirmed.

**No new Gradle dependencies required** for the Whisper + GPT-4o path. The iOS `SFSpeechRecognizer`
adapter requires adding `Speech.framework` to the cinterop definition.

### Decision rationale

- Ktor is already in `commonMain`; adding a Whisper HTTP call is 50 lines, not a new dependency.
- Whisper is the only STT option that works identically on all three targets from one code path.
- Platform native STT (SpeechRecognizer, SFSpeechRecognizer) are better UX (lower latency,
  free, partial results) but require per-platform code — implement as optional backends behind
  the `SpeechToTextProvider` interface for users who prefer them.
- The `ProviderRegistry` pattern mirrors SteleKit's existing `RepositoryFactory` — low cognitive
  overhead for the existing team.

---

## Pending Web Searches

The following searches were not executed (WebSearch not available). The parent agent should run
these to fill gaps marked [TRAINING_ONLY — verify]:

1. `site:central.sonatype.com "com.aallam.openai" "openai-kotlin"` — current version, Ktor
   compatibility (2.x vs 3.x), KMP target list.

2. `"kmp-openai" OR "kotlin-openai" site:github.com` — discover any other KMP OpenAI wrappers.

3. `"koog" site:github.com jetbrains kotlin multiplatform AI agent` — verify koog's KMP support
   and stability.

4. `android "AICore" "SpeechRecognizer" OR "ASR" API site:developer.android.com` — verify whether
   Android AICore exposes a direct ASR API or only text tasks.

5. `"SFSpeechRecognizer" duration limit site:developer.apple.com` — confirm current 60-second
   per-task limit or any iOS 17/18 relaxation.

6. `"whisper.cpp" JNI OR "whisper-jni" maven` — confirm Maven artifact coordinates for JVM
   on-device Whisper.

7. `"openai realtime api" kotlin ktor websocket` — assess feasibility of streaming STT via
   OpenAI Realtime API in KMP.

8. `"MediaPipe" "AudioTask" kotlin android transcription 2025` — confirm current MediaPipe
   Audio transcription API availability for Android.

9. `"Apple Intelligence" "FoundationModels" WWDC 2025 third party API` — check if Apple has
   released a public on-device inference API since WWDC 2024.

10. `"multiplatform audio" kotlin kmp recording site:github.com` — discover any maintained
    KMP audio recording libraries that would replace the expect/actual approach.

---

## Web Search Results

_Searches run: 2026-04-18. Queries listed per finding._

### 1. `openai-kotlin` Ktor 3.x Compatibility

**Query**: `openai-kotlin Ktor 3.x compatibility version 2025 2026`

**Verdict**: CONFIRMED AND UPDATED. `com.aallam.openai:openai-kotlin` (now branded `openai-client`)
**v4.1.0** fully supports Ktor 3.x. The Ktor 2.x conflict mentioned in the training data is
resolved. SteleKit uses Ktor 3.1.3; the library is compatible. Ktor itself is now at 3.4.0
(January 2026). The version-alignment risk noted under §3b is no longer a blocking concern —
`openai-kotlin` 4.x upgrades are safe to evaluate.

**Updated claim**: §3b: "Brings its own Ktor version which may conflict with project's 3.1.3" —
the conflict existed in older 3.x releases of `openai-kotlin` but is resolved in v4.1.0.

**Sources**:
- [Issue #411: Release compatible with ktor 3.x — aallam/openai-kotlin](https://github.com/aallam/openai-kotlin/issues/411)
- [Releases — aallam/openai-kotlin](https://github.com/aallam/openai-kotlin/releases)
- [Ktor 3.4.0 Is Now Available — The Kotlin Blog](https://blog.jetbrains.com/kotlin/2026/01/ktor-3-4-0-is-now-available/)

---

### 2. Android AICore / ASR Public API

**Query**: `Android AICore public ASR speech recognition API 2025` +
`ML Kit GenAI speech recognition android availability devices 2025`

**Verdict**: UPDATED. Android now has a **public on-device ASR API via ML Kit GenAI**
(`developers.google.com/ml-kit/genai/speech-recognition/android`), built on top of Android
AICore / Gemini Nano. This is a stable public SDK — the training-data claim that "AICore STT
API is not a stable public SDK" is now outdated.

Key facts verified:
- **Basic mode**: available on API 31+ (most Android devices).
- **Advanced mode** (Gemini Nano quality): Pixel 10, Pixel 9 series, Samsung Galaxy S25/S26,
  Honor, OPPO Find N5/X9, and more. Device list is expanding.
- **Critical constraint**: GenAI API inference is only permitted when the app is the **top
  foreground application**. Background / foreground service use returns
  `ErrorCode.BACKGROUND_USE_BLOCKED`. This means the ML Kit GenAI STT path cannot be used
  from a foreground service during screen-off recording.
- The API provides streaming transcription (partial → final results), not batch.

**Updated claim**: §2d: "AICore STT API is not a stable public SDK" — **incorrect as of 2025**.
ML Kit GenAI Speech Recognition is a public stable API. The foreground-only constraint limits
its usefulness for background recording scenarios.

**Sources**:
- [GenAI Speech Recognition API — Google for Developers](https://developers.google.com/ml-kit/genai/speech-recognition/android)
- [ML Kit GenAI APIs — Android Developers](https://developer.android.com/ai/gemini-nano/ml-kit-genai)
- [Android Developers Blog: On-device GenAI APIs with ML Kit](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html)

---

### 3. Whisper API Accepted Audio Formats (m4a / AAC)

**Query**: `openai whisper API accepted audio formats m4a aac supported 2025`

**Verdict**: CONFIRMED. The Whisper API explicitly accepts: **flac, mp3, mp4, mpeg, mpga,
m4a, ogg, wav, webm**. `.m4a` is in the supported list. The architecture recommendation to
use `.m4a` (AAC) from `MediaRecorder` (Android) and `AVAudioRecorder` (iOS) is valid — no
format conversion required before upload. Raw PCM (`.pcm`) and AMR are NOT accepted.

**Updated claim**: §Q1 (architecture.md) `[TRAINING_ONLY — verify Whisper API accepted formats
for m4a]` — **confirmed: m4a is accepted**. The pitfalls.md note about raw PCM requiring
manual WAV header is also confirmed.

**Sources**:
- [Audio API FAQ — OpenAI Help Center](https://help.openai.com/en/articles/7031512-whisper-audio-api-faq)
- [OpenAI Community: m4a format issue](https://community.openai.com/t/wisper-api-not-recognizing-m4a-file-format/141251)

---

### 4. Whisper API Pricing (current)

**Query**: `openai whisper API pricing per minute 2025 2026`

**Verdict**: CONFIRMED at **$0.006/minute** for `whisper-1`. The training-data figure is
accurate. Additionally, newer models are now available:
- `gpt-4o-transcribe`: $0.006/min (higher accuracy, same price)
- `gpt-4o-mini-transcribe`: $0.003/min (lower cost option)

The cost estimate in §2a ($0.006/min) remains valid. The `gpt-4o-mini-transcribe` option at
half the price is worth considering for v1.

**Sources**:
- [OpenAI API Pricing](https://openai.com/api/pricing/)
- [OpenAI Whisper API Pricing Apr 2026 — CostGoat](https://costgoat.com/pricing/openai-transcription)

---

### 5. Android SpeechRecognizer On-Device Mode API Level

**Query**: `android SpeechRecognizer createOnDeviceSpeechRecognizer API 33 offline 2024`

**Verdict**: CONFIRMED with correction. `createOnDeviceSpeechRecognizer()` was introduced at
**API 31** (Android 12), not API 33. It is available on API 33 but the minimum API level for
the on-device factory method is 31. SteleKit targets minSdk 24; this feature requires a
runtime API check (`Build.VERSION.SDK_INT >= 31`).

**Updated claim**: §2b: "Android 13+ (API 33) supports `createOnDeviceSpeechRecognizer()`" —
**incorrect; available from API 31**.

**Sources**:
- [SpeechRecognizer — Android Developers](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android Speech To Text — The missing guide (Medium)](https://medium.com/reveri-engineering/android-speech-to-text-the-missing-guide-part-1-824e2636c45a)

---

### 6. koog (JetBrains KMP AI Agent Framework)

**Query**: `koog JetBrains kotlin multiplatform AI agent framework 2025 stable`

**Verdict**: CONFIRMED AND UPDATED. Koog is real, actively maintained by JetBrains, and
**targets KMP** (JVM, Android, iOS, JS, WasmJS). Current version: **0.7.3** (as of late
2025/early 2026). Key facts:
- Open-sourced at KotlinConf May 2025; v0.5.0 shipped October 2025 with Agent-to-Agent (A2A)
  Protocol support, OpenTelemetry observability, Ktor plugin, MCP tool support.
- Still pre-1.0 (0.x versions). API stability is not guaranteed.
- Designed for **agent workflows** (multi-step tool use, graph-based strategies), not a simple
  LLM call wrapper. For SteleKit's formatting step (single LLM call), koog adds overhead with
  little benefit. The raw Ktor recommendation stands.

**Updated claim**: §3d: "koog — KMP support unverified" — **confirmed KMP support**. Stability
caveat: pre-1.0, not recommended for production until 1.0.

**Sources**:
- [JetBrains/koog — GitHub](https://github.com/JetBrains/koog)
- [Koog 0.5.0 Is Out — JetBrains AI Blog](https://blog.jetbrains.com/ai/2025/10/koog-0-5-0-is-out-smarter-tools-persistent-agents-and-simplified-strategy-design/)
- [The Kotlin AI Stack — Kotlin Blog](https://blog.jetbrains.com/kotlin/2025/09/the-kotlin-ai-stack-build-ai-agents-with-koog-code-smarter-with-junie-and-more/)

---

### 7. Apple Intelligence / FoundationModels Public API

**Query**: `Apple Intelligence FoundationModels framework public API third party iOS 18 2025`

**Verdict**: MAJOR UPDATE. Apple **announced and shipped** the `FoundationModels` framework at
WWDC 2025. Third-party developers can now call Apple's on-device LLM directly. Key facts:
- Announced June 9, 2025 at WWDC; available via Apple Developer Program.
- Supports: text generation, structured output, tool calling, guided generation.
- Works **offline** (on-device), no inference cost.
- Supported languages: English, French, German, Italian, Portuguese (Brazil), Spanish,
  Chinese (Simplified), Japanese, Korean.
- **Device requirement**: Apple Intelligence-capable devices only (iPhone 15 Pro+, Apple
  Silicon Macs, iPad with A17 Pro+).
- **Not STT**: `FoundationModels` is a text-in / text-out API. It does not replace Whisper for
  audio transcription. It is directly applicable as an **LLM formatting backend** — the
  transcript → outliner formatting step could use `FoundationModels` on supported devices
  instead of a cloud API call.

**Updated claim**: §2e and §3c: "No public `FoundationModels` API exists" — **incorrect as of
WWDC 2025**. FoundationModels is now a public developer API. It is a viable `LlmProvider`
implementation for `iosMain` on supported hardware.

**Sources**:
- [Foundation Models — Apple Developer Documentation](https://developer.apple.com/documentation/FoundationModels)
- [Apple Announces Foundation Models Framework — MacRumors](https://www.macrumors.com/2025/06/09/foundation-models-framework/)
- [Apple's Foundation Models framework unlocks new intelligent app experiences — Apple Newsroom](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/)

---

### 8. Whisper Hallucination on Silence — Confirmed

**Query**: `openai whisper hallucination silence "thank you" known issue short audio`

**Verdict**: CONFIRMED. Whisper hallucination on silent/near-silent audio is a well-documented,
unresolved issue. Specific confirmed behaviors:
- Hallucinated tokens include "Thank you.", "you", and subtitle-style credits (traced to
  subtitle training data with end-of-content markers).
- "Thank" (token 1044) is a legitimate token that cannot be blocklisted without side effects.
- A `hallucination_silence_threshold` parameter exists in whisper.cpp but can cause false
  positives that drop real speech near silence boundaries.
- Research (Calm-Whisper, ICML 2025) shows >80% reduction in non-speech hallucination is
  achievable with fine-tuning, but the standard API model still exhibits the issue.

**Mitigation confirmed**: Check `len(transcript.split()) < 10` and treat as empty. Additionally,
sending audio with known speech (via VAD gate) is the most reliable prevention.

**Sources**:
- [Hallucination on audio with no speech — openai/whisper Discussion #1606](https://github.com/openai/whisper/discussions/1606)
- [Whisper silent audio hallucination — OpenAI Community](https://community.openai.com/t/whisper-silent-audio-hallucination/1305173)
- [Calm-Whisper: Reduce Whisper Hallucination (arXiv, 2025)](https://arxiv.org/html/2505.12969v1)
