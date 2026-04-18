# Findings: Pitfalls — Voice Capture Mode (KMP Android/iOS)

## Summary

Voice capture in KMP introduces six distinct failure domains, each with platform-specific behavior that cannot be fully abstracted into commonMain. The most dangerous are: (1) silent permission denial on iOS that leaves the app recording nothing with no error, (2) Android audio focus loss that corrupts MediaRecorder state requiring a full teardown/restart, (3) on-device LLM availability gating that can fail at runtime on supported devices (model not yet downloaded), and (4) LLM hallucination of `[[links]]` that silently corrupts the knowledge graph. The recommendation is to use a "always-ask, graceful-degrade" architecture with explicit fallback tiers at every layer: permission → recording → STT → LLM → outliner formatting.

---

## Options Surveyed

### Permission Request Libraries (KMP)

| Option | Platform support | Notes |
|---|---|---|
| Accompanist Permissions | Android only | Jetpack Compose; not shared |
| Moko Permissions | Android + iOS | KMP-native; wraps AVAudioSession + Android runtime permissions |
| Manual expect/actual | All platforms | Maximum control; most boilerplate |
| Compose Multiplatform resource APIs | Not yet available for permissions | Pending CMP roadmap |

**Moko Permissions** is currently the most practical KMP option for requesting `RECORD_AUDIO` (Android) and microphone authorization (iOS) from shared code. [TRAINING_ONLY — verify current Moko Permissions version and CMP compatibility]

### Audio Capture APIs

| API | Platform | Notes |
|---|---|---|
| `AudioRecord` (raw PCM) | Android | Most control; requires manual WAV header for Whisper |
| `MediaRecorder` | Android | Easier; M4A/MP4 output; loses samples on focus loss |
| `AVAudioEngine` | iOS | Low-latency; streaming-friendly |
| `AVAudioRecorder` | iOS | File-based; simpler but less flexible |

### STT Options

| Option | Latency | Cost | Offline | Notes |
|---|---|---|---|---|
| OpenAI Whisper API | ~2–10s | $0.006/min | No | 25 MB / ~30 min max |
| On-device Whisper (whisper.cpp / WhisperKit) | Variable | Free | Yes | iOS: WhisperKit; Android: whisper.cpp via JNI |
| Android SpeechRecognizer | Low | Free | Partial | Requires network for cloud mode; poor accuracy |
| Apple Speech framework | Low | Free | Yes (on-device mode iOS 17+) | Limited to ~1 min per request |
| Android AICore / Gemini Nano | Low | Free | Yes | Pixel 8+ only; model must be pre-downloaded |
| Apple Intelligence (on-device LLM) | Low | Free | Yes | iOS 18.1+; US English only initially |

### LLM Formatting Options

| Option | Format control | Notes |
|---|---|---|
| Prompt-only (zero-shot) | Moderate | Hallucination risk on `[[links]]` |
| Prompt + few-shot examples | Good | Add 3–5 Logseq format examples in system prompt |
| Structured output (JSON mode) | Best | Parse JSON → emit Logseq markdown; eliminates format drift |
| Grammar-constrained decoding | Best | Requires on-device model with GBNF/llama.cpp grammar support |

---

## Trade-off Matrix

| Failure Domain | Permission failure UX | Audio interruption recovery | Model gate fallback | Prompt reliability | Cost risk |
|---|---|---|---|---|---|
| Accompanist-only permissions | iOS blocked entirely | N/A | N/A | N/A | None |
| Moko Permissions | Shared rationale UI possible | N/A | N/A | N/A | None |
| MediaRecorder on focus loss | N/A | Full teardown required | N/A | N/A | None |
| AudioRecord on focus loss | N/A | Can pause/resume with buffer drain | N/A | N/A | None |
| Whisper API only | N/A | N/A | None (single point of failure) | High | $0.006/min × scale |
| On-device Whisper | N/A | N/A | App-bundled; no gate | High | None |
| AICore Gemini Nano | N/A | N/A | Must detect at runtime | Moderate | None |
| Zero-shot LLM prompt | N/A | N/A | N/A | Low (hallucination) | API cost |
| Few-shot + structured output | N/A | N/A | N/A | High | API cost |

---

## Risk and Failure Modes

### 1. Microphone Permissions

#### Android — RECORD_AUDIO

- `RECORD_AUDIO` is a "dangerous" permission requiring runtime request on API 23+. [TRAINING_ONLY — verify]
- If the user selects "Don't ask again" after denying, `shouldShowRequestPermissionRationale()` returns `false` and your rationale dialog is suppressed. The user must manually go to Settings.
- **Mid-flow denial**: If the user revokes the permission via Settings while the app is in the background (rare but possible on Android 11+ with permission auto-reset), `AudioRecord.startRecording()` will throw `SecurityException`. This is unrecoverable without restart.
- Android 12+ introduced the microphone indicator (green dot). Users may tap it and revoke via the Privacy dashboard mid-session — a `SecurityException` in a background coroutine will crash if not caught.
- **Mitigation**: Wrap all audio capture in a `try/catch(SecurityException)`, immediately stop recording, emit a `PermissionRevoked` event to the ViewModel, and show a non-blocking snackbar with a deep link to app permissions settings.

#### iOS — AVAudioSession + Privacy Description

- `NSMicrophoneUsageDescription` must be in `Info.plist` or the app crashes at first permission request. [TRAINING_ONLY — verify]
- `AVAudioSession.requestRecordPermission` returns `.denied` silently — there is no system dialog the second time. The first-time dialog is shown by the OS; subsequent requests are no-ops.
- **Critical iOS pitfall**: If you call `AVAudioEngine.start()` without checking authorization first, it silently records nothing (no audio, no error). You get an empty WAV that Whisper transcribes as silence. This produces a confusing UX ("it recorded but got nothing").
- **Background mode**: iOS terminates audio sessions not declared in `UIBackgroundModes`. If the user locks the screen mid-capture, recording stops with no callback unless the app declares `audio` in background modes. Declaring it triggers App Store review scrutiny.
- **Mitigation**: Always call `AVAudioSession.recordPermission` before starting. If `.undetermined`, request it. If `.denied`, navigate to settings. If `.granted`, configure the session with `.record` category before starting the engine.

#### KMP — commonMain Permission Abstraction

- **Core problem**: There is no Kotlin Multiplatform standard for requesting permissions. `Accompanist Permissions` is Jetpack Compose for Android only and cannot be called from commonMain.
- **Moko Permissions** (`dev.icerock.moko:permissions`) provides `PermissionsController` with an `expect`/`actual` pattern that works in commonMain. It exposes `Permission.RECORD_AUDIO` cross-platform. [TRAINING_ONLY — verify current API surface]
- **Pitfall with Moko**: On iOS, Moko wraps `AVAudioSession.requestRecordPermission` but you still need to configure the `AVAudioSession` category separately before recording — Moko does not do this. Forgetting the category configuration causes `AVAudioEngine` to fail with `AVAudioSessionErrorCodeCannotStartRecording`.
- **Alternative pattern**: Define a `MicrophonePermissionManager` interface in commonMain with `expect`/`actual` implementations. Android: wraps `ActivityResultLauncher`. iOS: wraps `AVAudioSession`. This avoids the Moko dependency but requires more boilerplate.

---

### 2. Audio Focus / Interruption Handling (Android)

- Android requires apps to request `AudioFocus` before recording to respect phone calls and other audio apps. [TRAINING_ONLY — verify AudioFocus API lifecycle]
- **Phone call interruption**: When a call arrives, the system dispatches `AUDIOFOCUS_LOSS_TRANSIENT`. If you do not handle this, `MediaRecorder` continues running but may capture call audio (privacy violation) or corrupt the recording.
- **`MediaRecorder` vs `AudioRecord` on interruption**:
  - `MediaRecorder`: Has no pause/resume on API < 24. On interruption, you must call `stop()` and `release()`. The partial file may not be playable if the MP4 box headers are incomplete. You cannot resume; you must start a new file.
  - `AudioRecord`: Reads raw PCM into a buffer. On interruption you can stop reading, drain the buffer, and resume. The data stream remains coherent. This is the better choice for reliability.
- **`AUDIOFOCUS_LOSS` (permanent)**: Another app took focus permanently (e.g., user opened Spotify). You must stop recording and release resources. Do not silently continue.
- **Mitigation**:
  1. Request `AudioFocus` with `AUDIOFOCUS_GAIN_TRANSIENT` before starting.
  2. Register an `OnAudioFocusChangeListener`.
  3. On `AUDIOFOCUS_LOSS_TRANSIENT`: pause reading; show "Recording paused — call in progress" in the UI.
  4. On `AUDIOFOCUS_LOSS`: stop recording; offer to discard or keep partial.
  5. On `AUDIOFOCUS_GAIN`: resume automatically only if the user had not explicitly stopped.
  6. Abandon audio focus in `onStop()` / after recording ends.

---

### 3. Background Recording Restrictions

#### Android

- **Doze mode** (API 23+): If the device is idle and the screen is off, Doze restricts background work including wakelock-protected threads. An ongoing `AudioRecord` loop in a `Service` with a `FOREGROUND_SERVICE` notification is the correct approach — Doze does not kill foreground services. [TRAINING_ONLY — verify current Doze exemptions]
- **Foreground Service requirement**: Android 14+ requires declaring `foregroundServiceType="microphone"` in the manifest for any foreground service that accesses the microphone. Omitting this causes `ForegroundServiceStartNotAllowedException` on Android 14+. [TRAINING_ONLY — verify exact API level]
- **Background microphone restriction (Android 12+)**: Apps cannot start new microphone access while in the background. The service must already be running in the foreground before the app backgrounds. This means you cannot lazily start recording when the user locks the screen — you must transition to a foreground service before the app leaves the foreground.
- **Driving scenario (screen lock)**: For "hands-free dictation while driving," you must start the foreground service while the app is still visible, then let the screen lock. The foreground notification acts as the user's escape hatch.

#### iOS

- Without a background mode, `AVAudioEngine` is suspended when the app backgrounds. There is no graceful callback — the engine simply stops producing audio samples.
- **`audio` background mode**: Declaring this in `Info.plist` / `UIBackgroundModes` allows continuous recording but App Store review will scrutinize whether it's genuinely needed. Voice memos and podcast apps use this legitimately.
- **Alternative (no background mode)**: Record only while the app is in the foreground. Show a "Recording will pause if you leave the app" warning. This is the safest App Store path.
- **VoIP background mode**: Do not use this to "trick" iOS into keeping the mic open. App Store reviewers will reject apps that abuse VoIP entitlements.

---

### 4. On-Device LLM Availability Gating

#### Android AICore / Gemini Nano

- **Device gate**: Gemini Nano via AICore is limited to Pixel 8 and newer (as of early 2025). Samsung Galaxy S24 series may also have access via a separate integration path. The vast majority of Android users will not have this available. [TRAINING_ONLY — verify current device list]
- **Model download gate**: Even on a supported device, Gemini Nano may not be downloaded. The model (several GB) is downloaded by AICore on Wi-Fi in the background. An app can check `GenerativeModel.isAvailable()` (or equivalent AICore readiness API) but cannot force the download. [TRAINING_ONLY — verify exact API]
- **Runtime availability**: `DownloadConfig` / `AvailabilityListener` patterns exist but are asynchronous. Do not block app startup on this check.
- **Failure mode**: If you call the AICore API on an unsupported device or before the model is ready, you get a runtime exception (`IllegalStateException` or similar). This is not an Android-standard `ActivityNotFoundException` — it requires specific error handling.
- **Mitigation**: Implement a `LlmBackend` interface: `OnDeviceGemini`, `WhisperApiRemote`, `None`. On app start, asynchronously check AICore availability and set the preferred backend. Default to API remote if on-device is unavailable. Cache the result for the session.

#### Apple Intelligence (iOS 18.1+)

- Available only on iPhone 15 Pro and newer with iOS 18.1+. Not available in all regions (US, UK, Australia at launch; EU pending). [TRAINING_ONLY — verify current regional availability]
- Apple does not expose a public Swift API for "send text to Apple Intelligence for summarization/formatting" as of training cutoff. Apple Intelligence is accessible via Writing Tools (system-level text field integration), not a programmatic API. [TRAINING_ONLY — verify if any API was opened post-iOS 18.2]
- **Practical implication**: Apple Intelligence cannot be directly invoked for LLM formatting in SteleKit. The STT → LLM path on iOS must use either a remote API (OpenAI/Claude/etc.) or an embedded on-device model (llama.cpp, WhisperKit companion).
- **WhisperKit** (Argmax): Open-source, Swift-native, runs Whisper on Core ML on-device. Well-maintained as of 2024. This is the recommended on-device STT path for iOS. [TRAINING_ONLY — verify WhisperKit production-readiness]

---

### 5. LLM Prompt Fidelity for Outliner Format

#### Logseq Format Requirements

The target format is:
```
- item text
  - sub-item text
  - another sub-item [[linked-page]]
- next top-level item
```

Key constraints: bullet with `- `, two-space indentation for nesting, `[[Page Name]]` for links, no trailing whitespace, no blank lines between bullets at same level (Logseq treats blank lines as new blocks).

#### Failure Modes

**Hallucinated `[[links]]`**: LLMs will invent `[[page names]]` that do not exist in the graph. Example: a voice note about a meeting with "Sarah" becomes `- Met with [[Sarah Johnson]]` when no such page exists. Whisper's transcript may contain "Sarah" but the LLM extrapolates a full name and wraps it in a link.
- **Risk severity**: High — silently creates dangling links that pollute the graph. Logseq/SteleKit will create stub pages for these, which is confusing.
- **Mitigation options**:
  1. Post-process: strip all `[[...]]` from LLM output; let the user manually linkify.
  2. Prompt constraint: "Do NOT create `[[wiki links]]` unless the exact page name was explicitly stated in the transcript."
  3. Graph-aware prompting: pass a list of existing page names as context; tell the LLM to only link from that list.
  4. Structured output: emit JSON with a `links: []` array the app validates against the graph before rendering.

**Format drift**: LLMs will drift from the required format, especially for long outputs. Common drift patterns:
- Using `*` or `#` instead of `-`
- Using 4-space indent instead of 2-space
- Adding a preamble ("Here are the key points from your recording:") before the bullets
- Adding a summary paragraph after the bullets
- Wrapping everything in a code block

**Mitigation**: Use a strict system prompt with examples. Append "Output ONLY the bullet list. No preamble. No summary." Request a review of the first token — if it is not `-`, retry once. [TRAINING_ONLY — verify if structured output / JSON mode is available in Whisper API context]

**Token limit overflow**: A 30-minute voice recording can produce 4,000–6,000 words of transcript. At ~1.3 tokens/word, this is 5,200–7,800 input tokens, plus the system prompt, plus expected output. For GPT-4o (128k context), this is fine. For smaller on-device models (typically 4k–8k context), this will overflow.
- **Mitigation**: Chunk transcripts > 2,000 words. Send each chunk with the last 2 bullets of the previous chunk as context to maintain continuity. Merge outputs client-side.

**Incomplete output / mid-sentence cutoff**: If `max_tokens` is too low or the model hits its limit, output is truncated mid-bullet. The result is a malformed last bullet.
- **Mitigation**: Set `max_tokens` to at least `len(transcript_words) * 1.5`. Detect truncation by checking if the last line ends with a complete sentence (ends with `.`, `?`, `!`, or `]]`). If not, display a "formatting may be incomplete" warning.

---

### 6. Whisper API Pitfalls

**File size limit**: Whisper API accepts files up to 25 MB. At 128 kbps MP3, 25 MB ≈ 26 minutes of audio. At WAV (16-bit, 16 kHz mono), 25 MB ≈ 13 minutes. [TRAINING_ONLY — verify current limits]
- **Mitigation**: Record to AAC/M4A (much smaller than WAV). Split recordings approaching the limit client-side before upload. Use `AudioRecord` with a circular buffer and split at silence gaps (VAD — Voice Activity Detection) rather than at fixed byte counts to avoid splitting mid-word.

**Audio format requirements**: Whisper API accepts mp3, mp4, mpeg, mpga, m4a, wav, webm. It does NOT accept raw PCM (`.pcm`) or AMR. [TRAINING_ONLY — verify current format list]
- **Android pitfall**: `AudioRecord` produces raw PCM. You must write a WAV header before sending, or transcode to MP3/M4A using `MediaCodec`. Forgetting the header causes a 400 error from the API with a cryptic "could not decode audio" message.
- **iOS pitfall**: `AVAudioEngine` tap produces `AVAudioPCMBuffer`. You must write it to a file with `AVAudioFile` in the desired format. Using the wrong `AVAudioCommonFormat` produces a file the API rejects.

**Rate limits**: Whisper API has per-minute rate limits that vary by tier. A free-tier user sending a 10-minute recording every 5 minutes will hit limits quickly. [TRAINING_ONLY — verify current OpenAI rate limits]
- **Mitigation**: Implement exponential backoff with jitter. Cache in-progress transcription state so a retry does not re-upload the full file. Queue recordings if the API is unavailable.

**Accuracy in noisy / driving environments**: Whisper is trained on diverse data and handles moderate noise well, but:
- Wind noise from a car window or HVAC confuses VAD and can produce spurious tokens.
- Strong accents combined with background noise degrade accuracy significantly.
- Proper nouns (people names, product names) are frequently misspelled by Whisper even in clean audio.
- **Mitigation**: Enable noise suppression on the audio session before recording. On Android, use `AudioRecord` with `VOICE_COMMUNICATION` audio source (applies system-level noise cancellation) rather than `DEFAULT`. On iOS, use `AVAudioSession.Mode.voiceChat` which enables echo cancellation and noise reduction.

**Cost at scale**: At $0.006/minute, 1,000 users recording 5 minutes/day = $30/day = $900/month. This scales linearly and can surprise founders. [TRAINING_ONLY — verify current Whisper pricing]
- **Mitigation**: Default to on-device STT where available. Use Whisper API as a fallback. Meter usage per user. Add a "use on-device" preference toggle.

---

## Migration and Adoption Cost

| Change | Effort | Risk |
|---|---|---|
| Add Moko Permissions to commonMain | Low (1–2 days) | Low; well-understood dependency |
| Implement expect/actual audio capture | High (1–2 weeks per platform) | High; platform APIs are very different |
| Foreground service + notification (Android) | Medium (2–3 days) | Medium; requires manifest changes + UX for notification |
| Background audio mode (iOS) | Low (1 day) | Medium; App Store review risk |
| AICore availability check + fallback | Medium (2–3 days) | Low if behind a feature flag |
| WhisperKit integration (iOS) | Medium (3–5 days) | Low; library is well-maintained |
| whisper.cpp JNI (Android on-device STT) | High (1 week) | High; JNI complexity, binary size |
| LLM structured output pipeline | Medium (3–5 days) | Medium; prompt engineering iteration |
| Audio chunking for long recordings | Medium (2–3 days) | Medium; continuity between chunks is tricky |

---

## Operational Concerns

- **Binary size**: Bundling an on-device Whisper model (e.g., whisper-small = ~500 MB) will exceed default Play Store APK size limits. Requires Play Asset Delivery (dynamic feature modules). iOS App Store has similar concerns with on-demand resources.
- **Battery**: `AudioRecord` with continuous capture, PCM processing, and network upload is a significant battery drain. Use the lowest acceptable sample rate (16 kHz for Whisper, which only uses 16 kHz). Stop recording immediately when the user finishes.
- **Storage**: Uncompressed WAV at 16 kHz mono = ~1.9 MB/minute. A 30-minute recording = 57 MB. Always compress to AAC/M4A before storing locally. Delete the raw audio after successful transcription.
- **Privacy**: Audio data sent to OpenAI Whisper API leaves the device. The privacy policy must disclose this. If the user's jurisdiction requires it (e.g., GDPR), you must get explicit consent before the first upload. Log what is sent and when.
- **Observability**: Transcription and LLM formatting failures are silent from the user's perspective. Add structured logging: `[voice-capture] recording_start`, `[voice-capture] upload_bytes=N`, `[voice-capture] transcription_ms=N tokens=N`, `[voice-capture] llm_ms=N`. Alert on error rate > 5%.

---

## Prior Art and Lessons Learned

- **Otter.ai / Notion AI**: Both found that users do not re-read long AI-formatted notes. The value is in search/retrieval, not reading. This suggests that prompt reliability for perfect Logseq format matters less than ensuring key facts are captured.
- **Apple Voice Memos**: Uses foreground recording only (no background mode on most configurations). Shows a persistent red status bar indicator while recording. This pattern is well understood by users and avoids App Store scrutiny.
- **AudioRecord vs MediaRecorder for reliability**: Multiple Android developers report that `MediaRecorder` produces corrupted MP4 files when interrupted (phone call, notification). `AudioRecord` with manual WAV/AAC encoding is more reliable for uninterrupted capture under real-world conditions.
- **Whisper hallucination on silence**: Whisper is known to hallucinate text (typically "Thank you.", "you", or copyright notices) on silent or nearly-silent audio segments. Always check that the transcript is non-trivially long before sending to the LLM. If `len(transcript) < 10 words`, treat it as empty and prompt the user to try again.
- **LLM refusal on personal content**: Some LLM providers (especially with default safety filters) may refuse to process voice notes containing discussion of medication, mental health, or financial topics. Use a system prompt that establishes a "personal notes assistant" context. Test against common personal topics.

---

## Open Questions

1. Does SteleKit need to work offline (no internet) for the STT → LLM pipeline? If yes, on-device models are required and binary size / device gate become critical constraints.
2. What is the minimum supported Android API level? Foreground service `foregroundServiceType="microphone"` requires targeting API 34+.
3. What is the expected recording length? Short (< 2 min) vs long (> 10 min) drives different architecture decisions (chunking, file format, cost).
4. Is there a maximum acceptable latency from "stop recording" to "journal entry appears"? This determines whether on-device STT is worth the complexity.
5. Should `[[links]]` ever be generated automatically, or should the feature only produce plain text bullets?
6. Is the Whisper API cost acceptable at projected user scale, or is on-device STT required from day 1?
7. What happens if the LLM formats a voice note incorrectly — is there a user-facing edit flow, or is the result final?

---

## Recommendation

Implement in three tiers gated by capability detection:

**Tier 1 (all devices, day 1)**: Remote Whisper API for STT + remote LLM (GPT-4o or Claude) for formatting. No on-device models. Foreground-only recording. Use `AudioRecord` + AAC transcoding on Android, `AVAudioEngine` on iOS. Zero-shot prompt with few-shot Logseq examples. Strip all `[[links]]` from LLM output initially.

**Tier 2 (Pixel 8+ / iOS 17+, post-launch)**: On-device Whisper (whisper.cpp JNI on Android, WhisperKit on iOS) for STT. Remote LLM for formatting. Reduces latency and eliminates Whisper API cost.

**Tier 3 (future)**: On-device LLM for formatting (AICore Gemini Nano on Android when available). True offline voice capture. Requires grammar-constrained decoding for reliable Logseq format.

For the permission layer, use Moko Permissions in commonMain. For Android audio interruption, use `AudioRecord` + `AudioFocus` with a foreground service. Do NOT declare the iOS `audio` background mode for v1 — foreground-only is the safe path. Add a `LlmBackend` abstraction from the start so fallback tiers can be added without refactoring the call site.

---

## Pending Web Searches

The following queries should be run to verify training-knowledge claims:

1. `"AICore" OR "Gemini Nano" android availability 2024 2025 device list pixel`
2. `moko-permissions kotlin multiplatform compose multiplatform 2024 microphone`
3. `android foregroundServiceType microphone api level requirement 2024`
4. `WhisperKit ios swift production ready 2024 argmax`
5. `openai whisper api file size limit format requirements 2024`
6. `openai whisper hallucination silence "thank you" known issue`
7. `android AudioRecord vs MediaRecorder interruption reliability`
8. `AVAudioSession requestRecordPermission ios silent failure empty audio`
9. `apple intelligence public api ios 18 programmatic access writing tools`
10. `logseq markdown format spec bullet indentation two spaces`
11. `android 12 background microphone restriction foreground service`
12. `openai whisper api pricing per minute 2024 2025`

---

## Web Search Results

_Searches run: 2026-04-18._

### 1. Android foregroundServiceType="microphone" — API Level Confirmed

**Query**: `android foregroundServiceType microphone requirement API level manifest 2024`

**Verdict**: CONFIRMED AND CLARIFIED.

- Declaring `android:foregroundServiceType="microphone"` in the manifest is required for
  foreground services accessing the mic. Omitting it **silently denies** mic access on
  devices with targetSdk ≥ 30 (Android 11+) — no crash, just no audio.
- For apps **targeting API 34** (Android 14+): additionally requires
  `android.permission.FOREGROUND_SERVICE_MICROPHONE` in the manifest AND `RECORD_AUDIO` at
  runtime.
- **Background launch restriction (Android 12+)**: A microphone foreground service cannot be
  *started* from the background. It must already be in the foreground when the app
  backgrounds. This confirms the §3 mitigation: "start the foreground service before the app
  leaves the foreground."

**Updated claim**: §3 (Background Recording Restrictions): `[TRAINING_ONLY — verify exact API
level]` — **confirmed: foregroundServiceType="microphone" is mandatory at targetSdk ≥ 30;
the additional `FOREGROUND_SERVICE_MICROPHONE` permission is required at targetSdk ≥ 34**.

**Sources**:
- [Foreground service types are required — Android 14 — Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Foreground service types — Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Guide to Foreground Services on Android 14 — Medium](https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-9d0127dc8f9a)

---

### 2. Whisper Hallucination on Silence — Confirmed

**Query**: `openai whisper hallucination silence "thank you" known issue short audio`

**Verdict**: CONFIRMED. This is a well-documented, unresolved upstream issue.

- Whisper hallucinates "Thank you.", "you", and subtitle-style text on silent/near-silent audio.
- Traced to subtitle training data with end-of-content markers that map to the same tokens.
- "Thank" (token 1044) is a real token — cannot be blocklisted without side effects on real
  transcripts.
- `hallucination_silence_threshold` in whisper.cpp partially mitigates but can drop real
  speech near silence boundaries.
- Calm-Whisper (ICML 2025) achieves >80% reduction in non-speech hallucination via fine-tuning,
  but the standard `whisper-1` API model still exhibits the behavior.

**Recommendation confirmed**: Check `len(transcript.split()) < 10` before passing to the LLM.
A VAD (voice activity detection) gate before upload is the most reliable prevention. These
mitigations are already noted in §6 and §Recommendation — no change needed, but the risk is
now backed by confirmed public evidence.

**Sources**:
- [Hallucination on audio with no speech — openai/whisper Discussion #1606](https://github.com/openai/whisper/discussions/1606)
- [Whisper silent audio hallucination — OpenAI Community](https://community.openai.com/t/whisper-silent-audio-hallucination/1305173)
- [A possible solution to Whisper hallucination — openai/whisper Discussion #679](https://github.com/openai/whisper/discussions/679)
- [Calm-Whisper (arXiv 2025)](https://arxiv.org/html/2505.12969v1)

---

### 3. moko-permissions — Version and CMP Compatibility

**Query**: `moko-permissions kotlin multiplatform compose multiplatform 2025 microphone version`

**Verdict**: CONFIRMED. moko-permissions v0.20.1 (released August 28, 2025) supports:
- Android + iOS (KMP expect/actual)
- Compose Multiplatform via `permissions-compose:0.20.1`
- `Permission.RECORD_AUDIO` cross-platform
- Android API 16+, iOS 12.0+

The `[TRAINING_ONLY — verify current Moko Permissions version and CMP compatibility]` note is
resolved. The library is actively maintained and CMP-compatible. The pitfall noted in §1
(KMP Permission Abstraction) remains valid: Moko handles the *permission request* but you
must still configure `AVAudioSession` category separately before recording on iOS.

**Sources**:
- [icerockdev/moko-permissions — GitHub](https://github.com/icerockdev/moko-permissions)
- [How to Use Moko-Media and Moko-Permissions in CMP — Medium](https://medium.com/@marceloamendes/como-utilizar-o-moko-media-e-o-moko-permissions-no-compose-multiplatform-d576cf5cda70)

---

### 4. WhisperKit (iOS) — Production Ready

**Query**: `WhisperKit ios swift production ready argmax 2025`

**Verdict**: CONFIRMED. WhisperKit by Argmax is **production-ready** as of 2025:
- Actively maintained; featured at ICML 2025.
- Runs Whisper on Core ML (Apple Neural Engine) — fully on-device.
- Supports real-time streaming, word timestamps, VAD.
- Swift Package Manager integration; requires Xcode 16.0+.
- Available under open-source license; Argmax Pro SDK available for enterprise scaling.
- TTSKit (text-to-speech) was also added as an optional product in the same package.

**Updated claim**: §4 `[TRAINING_ONLY — verify WhisperKit production-readiness]` —
**confirmed production-ready**. It is the recommended on-device STT path for iOS in Tier 2.

**Sources**:
- [argmaxinc/WhisperKit — GitHub](https://github.com/argmaxinc/WhisperKit)
- [WhisperKit — Argmax](https://www.argmaxinc.com/blog/whisperkit)

---

### 5. Apple FoundationModels — LLM API Now Public (changes §4)

**Query**: `apple intelligence public api ios 18 programmatic access writing tools`
_(via: `Apple Intelligence FoundationModels framework public API third party iOS 18 2025`)_

**Verdict**: MAJOR UPDATE. Apple's `FoundationModels` framework is now a **public developer
API** (announced WWDC June 2025, available via Apple Developer Program).

**Impact on §4 (On-Device LLM Availability Gating)**:
- The statement "Apple does not expose a public Swift API for 'send text to Apple Intelligence'"
  is now **incorrect**.
- `FoundationModels` is a text-in / text-out API (not STT) available for text generation,
  structured output, and tool calling.
- Device gate: iPhone 15 Pro+, Apple Silicon devices, iOS 18.1+.
- This is directly usable as the LLM formatting step: transcript → Logseq outliner bullets,
  entirely on-device, no network, no API cost.
- The `LlmBackend` interface proposed in §Recommendation should include an
  `AppleIntelligenceLlmBackend` tier for iOS, checked via a capability API at runtime.

**Updated claim**: §4 "Apple does not expose a public Swift API" — **outdated**. FoundationModels
is public as of WWDC 2025. Update Tier 3 in the Recommendation to include it as the iOS
on-device LLM path alongside (or replacing) cloud LLM for supported devices.

**Sources**:
- [Foundation Models — Apple Developer Documentation](https://developer.apple.com/documentation/FoundationModels)
- [Apple's Foundation Models framework — Apple Newsroom (Sept 2025)](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/)
- [Apple Announces Foundation Models Framework — MacRumors](https://www.macrumors.com/2025/06/09/foundation-models-framework/)

---

### 6. ML Kit GenAI Speech Recognition — New Public API (changes §1 Android scope)

**Query**: `ML Kit GenAI speech recognition android availability devices 2025`

**Verdict**: NEW INFORMATION. Google's ML Kit GenAI Speech Recognition (backed by Gemini Nano
/ AICore) is a public stable API as of 2025. This changes the §1 STT options table.

Key facts:
- Basic mode: API 31+, most Android devices.
- Advanced mode: Pixel 9/10, Samsung Galaxy S25/S26, Honor Magic 7/8 Pro, OPPO, Fold7, expanding.
- **Critical**: blocked when app is not the top foreground activity
  (`ErrorCode.BACKGROUND_USE_BLOCKED`). Cannot be used from a background foreground service.
- Streaming output (partial → final).

**Impact on §1 microphone permission pitfalls**: The ML Kit GenAI STT path avoids the
`AudioRecord`/`MediaRecorder` audio focus complexity entirely — it handles audio internally.
However the foreground-only restriction means it cannot be used for background capture. For
Phase 1 (in-app foreground recording), it is a viable free on-device alternative to Whisper.

**Sources**:
- [GenAI Speech Recognition API — Google for Developers](https://developers.google.com/ml-kit/genai/speech-recognition/android)
- [Android Developers Blog: On-device GenAI APIs with ML Kit (May 2025)](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html)
- [ML Kit GenAI APIs — Android Developers](https://developer.android.com/ai/gemini-nano/ml-kit-genai)

---

### 7. Whisper API Pricing — Confirmed

**Query**: `openai whisper api pricing per minute 2024 2025`

**Verdict**: CONFIRMED. `whisper-1` is $0.006/min. New lower-cost option: `gpt-4o-mini-
transcribe` at $0.003/min. The cost-at-scale example in §6 ($30/day for 1,000 users × 5
min/day) remains accurate for `whisper-1`; using `gpt-4o-mini-transcribe` halves it to $15/day.

**Sources**:
- [OpenAI API Pricing](https://openai.com/api/pricing/)
- [OpenAI Whisper API Pricing Apr 2026 — CostGoat](https://costgoat.com/pricing/openai-transcription)
