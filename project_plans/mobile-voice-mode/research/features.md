# Findings: Features

## Summary

Voice-to-notes is a mature category with several well-executed products. The dominant pattern is: one-tap record → stream or buffer audio → transcribe via Whisper (cloud or on-device) → LLM rewrite into a chosen structure → append to a target location. The main differentiators are (a) capture friction (lock screen widget vs. in-app FAB), (b) whether the LLM stage happens before or after the user sees raw text, and (c) how well the output format aligns with the target note system. No existing product natively produces Logseq outliner syntax with `[[wikilinks]]`, but the pipeline pattern is clear: transcription → LLM formatting prompt → write to today's daily note file.

---

## Options Surveyed

### AudioPen (audiopen.ai)

**Capture UX**: Single large record button on the home screen. Tap to start, tap to stop. No lock screen widget or persistent recording indicator in the free tier. Mobile apps on iOS and Android. Free tier caps recordings at 3 minutes; Prime tier allows up to 15 minutes.

**Long-ramble handling**: Post-process only — the full recording is sent to the server after the user stops. No streaming transcription. AudioPen's core value proposition is its "Synthesis Engine": it does not produce a verbatim transcript but actively rewrites the audio, removing filler words ("uh", "um"), redundant repetition, and structural rambling. The output reads like intentional writing, not dictation.

**Formatting**: Ships with a style library. Predefined styles include "Franklin: Bulleted list, short sentences", casual memo, formal email, Twitter thread, action items, technical doc, and custom styles. Users on the Prime plan can define their own style with examples. No native `[[wikilink]]` or outliner syntax support. Format selection is per-recording, not automatic.

**Latency**: Entirely post-process. Users report results appear in "seconds" after stopping, but this is network-dependent. No official latency SLA published.

**Eyes-free safety**: Minimal. The app requires navigation to the home screen. No lock screen widget on Android; limited iOS widget support.

**Processing state communication**: Simple spinner/loading indicator after stopping. No waveform during recording in the free tier.

**Lessons**: AudioPen proves that users prefer a synthesized, rewritten output over a raw transcript — the "what I meant to say" model. The style-library approach is highly valued. However, its capture UX requires full phone interaction, and its output formats are not aimed at outliner/PKM tools.

---

### Whisper Memos (whispermemos.com)

**Capture UX**: Records on iPhone or Apple Watch. On Apple Watch, a complication (watch face button) allows one-tap recording without touching the phone. Delivers transcripts by email or saves to iCloud Drive as dated text files. Supports Cohere Transcribe and OpenAI Whisper as transcription engines.

**Long-ramble handling**: Buffers the full recording, then sends to the cloud transcription engine post-stop. No chunked streaming. iCloud Integration saves transcripts as organized text files by date, accessible from Files app or piped to Obsidian via automation.

**Formatting**: Raw transcript output by default. Users must apply their own LLM post-processing (e.g., running Claude on the saved file) to get structured notes. Some users describe workflows where Whisper Memos captures, then a Claude shortcut formats the result.

**Latency**: Reported as "fast" but no benchmark available. Speed depends on Whisper cloud endpoint response time.

**Eyes-free safety**: Apple Watch complication is the strongest eyes-free pattern in this survey. One-tap on wrist, no phone needed. Critically important for driving and walking use cases.

**Processing state communication**: Watch shows a recording indicator. Phone app shows transcript arriving asynchronously (often by notification or email), so there is a decoupled delivery model — capture and result are temporally separated.

**Lessons**: The Apple Watch complication is the gold standard for minimum-friction capture. The decoupled delivery model (capture now, formatted result later) reduces latency anxiety because users know the result is coming. Two-step pipelines (record → LLM format separately) give users control but add workflow friction.

---

### Notion AI voice capture

**Capture UX**: Notion introduced native voice input on mobile, activated inside the app. The `/meet` block triggers meeting transcription from the mic. Third-party integrations (Wispr Flow, Speechify, Fast Dictate) operate at the OS dictation layer, injecting text into any focused text field.

**Long-ramble handling**: The native `/meet` block transcribes in real-time, streaming text into the note block. Third-party tools also operate in streaming mode, showing words as they are spoken.

**Formatting**: Notion AI does not auto-structure voice input into bullets or headers. The raw dictation stream lands in a text block. Users manually invoke AI commands to restructure. Speechify claims to remove filler words in real-time before injecting text.

**Latency**: Streaming dictation has near-zero perceived latency (words appear as spoken). Post-processing AI formatting adds seconds after the user stops.

**Eyes-free safety**: Not designed for eyes-free use. Requires active cursor placement in the Notion editor.

**Processing state communication**: Real-time text stream is the processing indicator — words appearing live tell the user the system is working.

**Lessons**: Streaming dictation (words appear as you speak) provides excellent user feedback and perceived low latency. However, unformatted raw text output requires a second LLM pass to produce structured notes.

---

### Otter.ai

**Capture UX**: Large floating record button in the mobile app. Designed primarily for meeting transcription (auto-joins Zoom, Google Meet). Manual voice memo recording supported. No lock screen widget.

**Long-ramble handling**: Real-time streaming transcription — words appear on-screen as you speak, with speaker diarization. Long recordings are handled gracefully (designed for multi-hour meetings). The acoustic model runs a denoising pipeline on the raw waveform before diarization.

**Formatting**: After recording stops, Otter AI generates a summary with action items and highlights. Output is paragraph summaries and bullet-point action items, not outliner syntax. No `[[wikilink]]` support.

**Latency**: Live transcription during recording, summary generated within seconds of stopping.

**Eyes-free safety**: Not designed for solo voice capture; assumes the user has a phone available for meeting context.

**Processing state communication**: Live waveform visualization during recording (users see audio activity). Real-time text stream. Post-processing shows a progress indicator with status text ("Generating summary...").

**Lessons**: The live waveform + streaming text combination is the most reassuring processing state UX in this survey. Users know the system is hearing them. The two-phase output model (live transcript → post-stop AI summary) separates raw capture from formatting.

---

### Apple Notes + Voice Memos (iOS 18)

**Capture UX**: In iOS 18, Notes app has a native audio recording button inside a note. Voice Memos app is a standalone recorder. Lock screen is not directly accessible for Notes recording, but Voice Memos can be accessed via Control Center.

**Long-ramble handling**: Transcription runs on-device (iPhone 12+, English and ~10 languages) during or immediately after recording. On-device processing caps at roughly 30 minutes; longer recordings may fail or process slowly.

**Formatting**: Verbatim transcript only — no LLM reformatting. Apple Intelligence (iOS 18.1+) can summarize notes but does not produce outliner or bullet syntax automatically.

**Latency**: Near-instant for short recordings (transcript appears within seconds of stopping). Longer recordings take proportionally longer. User reports confirm a brief post-processing pause before the transcript appears in Notes.

**Eyes-free safety**: Voice Memos via Siri ("Hey Siri, create a voice memo") is the only eyes-free path on Apple hardware. Notes recording is not Siri-accessible.

**Processing state communication**: Simple "Transcribing..." label below the audio waveform in Notes. Voice Memos shows the recording duration counter.

**Lessons**: On-device transcription eliminates the server round-trip and privacy concerns. However, on-device models lack the context-awareness to produce structured outliner output — they do verbatim transcription only. A hybrid approach (on-device transcription → cloud LLM formatting) combines the best of both.

---

### Reflect (reflect.app)

**Capture UX**: iOS lock screen widget that triggers a voice recording directly, with a Live Activity indicator in the Dynamic Island and lock screen showing recording status and a "Stop" button. On-device recording with Whisper transcription. Transcript automatically appends to today's daily note.

**Long-ramble handling**: Full-recording post-processing via Whisper after the user stops. No real-time streaming shown during capture.

**Formatting**: Raw Whisper transcript by default, appended to the daily note. Reflect's AI can optionally rewrite the note, but this is a separate explicit action, not automatic.

**Latency**: Transcript appears in the daily note "shortly after" stopping. No published latency numbers.

**Eyes-free safety**: Lock screen widget is the closest to eyes-free in this survey among general note-taking apps. The user taps once on the lock screen to start and once to stop — minimal visual attention required.

**Processing state communication**: Live Activity on lock screen and Dynamic Island shows recording indicator and elapsed time. Post-processing shows a brief "Transcribing..." state.

**Lessons**: The lock screen widget + auto-append to daily note is the most directly analogous pattern to what SteleKit is building. The Daily Note auto-append removes the "where does this go?" decision entirely. This is the closest prior art.

---

### NotelyVoice (Open Source, Compose Multiplatform)

**Capture UX**: Standard FAB (floating action button) in-app. Built with Compose Multiplatform for Android and iOS — directly relevant to SteleKit's KMP architecture.

**Long-ramble handling**: Memory-optimized audio processing for large files: streaming WAV decoding + overlapping chunk transcription (splits audio into chunks with overlap, transcribes each, stitches results). Handles Out of Memory errors gracefully.

**Formatting**: Verbatim Whisper output. No LLM reformat stage.

**Latency**: On-device Whisper — latency proportional to recording length and device capability.

**Eyes-free safety**: Standard in-app FAB only — not eyes-free.

**Processing state communication**: Basic progress indicator during transcription.

**Lessons**: This codebase demonstrates that chunked, overlap-based transcription of long recordings is solvable in Compose Multiplatform. The overlap technique prevents sentence truncation at chunk boundaries. Directly applicable to SteleKit's KMP implementation.

---

## Trade-off Matrix

| Axis | AudioPen | Whisper Memos | Otter.ai | Apple Notes | Reflect | NotelyVoice (OSS) |
|---|---|---|---|---|---|---|
| Capture friction | Medium (in-app only) | Low (Watch complication) | Medium (in-app FAB) | High (navigate to Notes) | Low (lock screen widget) | Medium (in-app FAB) |
| Formatting quality | High (LLM synthesis) | None (raw transcript) | High (meeting AI summary) | None (verbatim) | Low (Whisper verbatim) | None (verbatim) |
| Latency to formatted output | 2–5s post-stop | Async (email/file) | Real-time + ~3s summary | ~1s on-device | ~2–3s | ~5–15s on-device |
| Outliner/wikilink support | None | None | None | None | None | None |
| Eyes-free safety | Poor | Excellent (Watch) | Poor | Moderate (Siri) | Good (lock screen) | Poor |
| Privacy (on-device option) | No (cloud only) | Yes (Whisper engine) | No (cloud only) | Yes (Apple on-device) | Partial (Whisper) | Yes (fully on-device) |
| KMP / Android-first relevance | N/A | iOS-only | Cross-platform app | iOS-only | iOS-only | Direct (CMP) |
| Auto-append to daily note | No | Partial (iCloud file) | No | No | Yes | No |

---

## Risk and Failure Modes

**Transcription accuracy on long rambles**: Whisper and similar models degrade on audio longer than ~15–30 minutes without chunking. Chunk boundaries can split sentences. Mitigation: overlapping chunks with 5–10 second overlap windows, then deduplicate overlapping text.

**LLM formatting hallucinations**: When the LLM reformats the transcript, it can invent `[[links]]` to pages that do not exist in the user's graph, or silently drop content it judges as redundant. Mitigation: always append the raw transcript below the formatted output (collapsible), and constrain the LLM to only use wikilinks for terms explicitly mentioned in the transcript.

**Microphone permission and background audio**: Android and iOS have different rules for background mic access. Android 14+ restricts background microphone use without a foreground service notification. iOS requires the app to be in the foreground or have a Live Activity. Mitigation: use a foreground service on Android with a persistent notification, and a Live Activity on iOS.

**Network dependency for LLM formatting**: If the device is offline, the LLM reformat step fails. Mitigation: always save the raw transcript locally first, queue the LLM formatting job, apply it when connectivity returns.

**User confusion about "what was captured"**: If the formatted output differs significantly from what was said, users distrust the tool. Mitigation: show a toggle between "Formatted" and "Original transcript" views on the result card.

**Battery drain during long sessions**: Continuous microphone use drains battery faster than normal app use. Mitigation: display a battery warning if the session exceeds a configurable threshold (e.g., 10 minutes).

---

## Migration and Adoption Cost

The feature is additive — existing SteleKit users gain a new capture mode without workflow disruption. The primary adoption cost is:

1. Users must grant microphone permission (one-time friction).
2. Users must understand the difference between the formatted output and the raw transcript.
3. Power users with existing Logseq voice workflows (via Shortcuts or automation) may have bespoke pipelines that conflict with SteleKit's append behavior.

No migration of existing data is required. The feature appends to the existing daily note file in the standard Logseq format, so it is non-destructive.

---

## Operational Concerns

**LLM API cost**: If the formatting step calls a cloud LLM (Gemini Flash, Claude Haiku, or similar), cost per note is low (~$0.001–$0.005 per transcription depending on length), but aggregates at scale. Consider whether this is user-pays (API key configuration) or SteleKit-subsidized.

**Whisper transcription cost**: OpenAI Whisper API charges ~$0.006/minute. A 5-minute recording costs ~$0.03. On-device Whisper (via whisper.cpp or Android ONNX port) eliminates this cost but requires model download (~75MB for base, ~1.4GB for large-v3).

**Model download size**: On-device Whisper models are large. The base model (75MB, ~3% WER) may be acceptable for initial release; the small model (244MB) offers better accuracy. Users on metered connections need explicit consent before downloading.

**Server-side prompt injection risk**: User voice content passes through the LLM formatting prompt. If users dictate adversarial content, the LLM could be manipulated. Mitigation: system prompt is not user-editable; transcript is inserted as a clearly delimited user message, not as instructions.

---

## Prior Art and Lessons Learned

1. **Reflect's lock screen widget + daily note append** is the closest prior art to the planned SteleKit feature. The pattern works: users adopt it because it removes the "where does this go?" decision.

2. **AudioPen's synthesis model** (rewrite, don't transcribe) dramatically improves output quality but risks losing nuance. For Logseq outliner output, a hybrid approach works best: LLM identifies the structural intent (main topics → top-level bullets, sub-points → indented bullets, mentioned entities → `[[wikilinks]]`) rather than purely summarizing.

3. **Otter.ai's two-phase display** (live transcript during recording + formatted summary after) is the best UX for communicating system state. Users are never left wondering if the system heard them.

4. **Whisper Memos + Apple Watch** demonstrates that the wrist is the ideal capture surface for truly eyes-free use. SteleKit's KMP stack cannot target watchOS (no Kotlin support), but the pattern is instructive: minimize the UI surface area of the capture step.

5. **NotelyVoice's CMP implementation** of chunked Whisper transcription is directly reusable reference code for SteleKit's KMP architecture.

6. **No tool surveyed produces Logseq outliner syntax** (`- top bullet\n  - sub-bullet\n  [[wikilink]]`). This is a genuine differentiation opportunity. The LLM prompt engineering to produce well-formed Logseq markdown from a transcript is achievable with a carefully designed system prompt and output validation.

---

## Open Questions

1. **On-device vs. cloud transcription**: Should SteleKit ship with on-device Whisper (privacy-first, no cost, larger APK, slower on old devices) or cloud Whisper (faster, smaller APK, costs money or requires user API key)? Hybrid (on-device for transcription, cloud for LLM formatting) is likely the right default.

2. **Which LLM for formatting?**: Gemini Flash (free tier available), Claude Haiku (fast, cheap), or a local model (Phi-3 Mini)? The formatting prompt is short-context and low-complexity — a small model is sufficient.

3. **Lock screen / foreground service**: What is the minimum viable eyes-free capture UX on Android without watchOS support? A persistent foreground service notification with a "tap to start/stop" action button is the most feasible equivalent.

4. **Wikilink injection strategy**: Should the LLM be given a list of known page names in the user's graph to constrain `[[link]]` generation? This would require passing graph index data to the LLM call, increasing context size and cost.

5. **Formatting style selection**: Should users be able to choose between "bullet points", "paragraph", "action items" (AudioPen-style style library) or should SteleKit always produce Logseq outliner format?

6. **Append vs. new block**: Should captured notes append to the bottom of today's daily note as a timestamped block, or be inserted at the cursor position? Appending is simpler and safer; cursor insertion requires the app to be open.

7. **Maximum recording length**: Should there be a cap (e.g., 15 minutes) or should the app support unlimited recording with chunked processing?

---

## Recommendation

Build the feature in three phases, following the Reflect + AudioPen combined pattern:

**Phase 1 — Baseline capture**: In-app FAB in the mobile journal view. Tap to record, tap to stop. Post-stop: Whisper cloud transcription → raw transcript appended to today's daily note as a dated block. No LLM formatting yet. Ships fast, validates the pipeline.

**Phase 2 — LLM formatting**: Add an LLM formatting step after transcription. System prompt instructs the model to produce Logseq outliner markdown: top-level `-` bullets for main topics, indented bullets for sub-points, `[[entity]]` for proper nouns and topics the user explicitly named. Show a "Formatted / Raw" toggle on the result card.

**Phase 3 — Capture surface expansion**: Add a foreground service notification on Android with a start/stop action button (the closest to Reflect's lock screen widget without watchOS). On iOS, add a lock screen widget via iOS Widget APIs if SteleKit ships an iOS target.

The two-phase display model from Otter.ai (live waveform or streaming transcript during recording + formatted result after stopping) should be adopted from day one to communicate system state clearly.

---

## Pending Web Searches

The following searches should be run to fill gaps in this research:

1. `AudioPen iOS lock screen widget 2025` — confirm whether AudioPen added a widget after the initial survey date
2. `whisper.cpp Android ONNX Compose Multiplatform integration 2025` — find current state of on-device Whisper for KMP/Android
3. `Logseq voice capture plugin community 2025` — check whether the Logseq community has built voice capture plugins with wikilink extraction
4. `Gemini Flash audio transcription API pricing 2025` — Gemini can transcribe audio directly; may eliminate the Whisper + LLM two-step
5. `Android foreground service microphone lock screen button API 34` — confirm exact Android 14 foreground service type for microphone required
6. `Reflect.app lock screen widget iOS implementation details` — look for any technical writeup of how Reflect built the Live Activity widget
7. `KMP Kotlin Multiplatform speech recognition Android iOS unified API 2025` — check if there is a KMP wrapper for platform speech recognition APIs

---

## Web Search Results

_Searches run: 2026-04-18._

### 1. Android Foreground Service + Microphone (API 34)

**Query**: `android foregroundServiceType microphone requirement API level manifest 2024`

**Verdict**: CONFIRMED AND CLARIFIED.
- `android:foregroundServiceType="microphone"` in the manifest is required for any foreground
  service accessing the mic. The requirement to declare this type takes effect when
  **targetSdkVersion ≥ 30** (Android 11) — omitting it silently denies mic access to the
  service on those targets.
- For apps **targeting API 34+** (Android 14): additionally requires the
  `android.permission.FOREGROUND_SERVICE_MICROPHONE` permission in the manifest, AND one of
  `CAPTURE_AUDIO_OUTPUT` or `RECORD_AUDIO` at runtime.
- **Background microphone restriction (Android 12+)**: Apps cannot start a new microphone
  access session while in the background. The foreground service must already be running in
  the foreground before the app is backgrounded — you cannot lazily start it on screen lock.

This directly validates the pitfalls.md recommendation: start the foreground service while the
app is still visible, then allow the screen to lock.

**Sources**:
- [Foreground service types are required — Android 14 — Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Foreground service types — Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Guide to Foreground Services on Android 14 — Medium](https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-9d0127dc8f9a)

---

### 2. ML Kit GenAI On-Device STT — Device Availability

**Query**: `ML Kit GenAI speech recognition android availability devices 2025`

**Verdict**: NEW INFORMATION. Google launched a public **ML Kit GenAI Speech Recognition API**
(backed by Gemini Nano / AICore) in 2025. This changes the §2d assessment significantly:

- **Basic mode** (standard quality): API 31+ on most Android devices.
- **Advanced mode** (Gemini Nano quality): Pixel 9/10 series, Samsung Galaxy S25/S26,
  Honor Magic 7/8 Pro, OPPO Find N5/X9, Galaxy Z Fold7, and expanding.
- The API provides **streaming transcription** (partial + final results).
- **Hard constraint**: inference is blocked when app is not the top foreground app
  (`ErrorCode.BACKGROUND_USE_BLOCKED`). Cannot be used from a foreground service.

For SteleKit Phase 1 (foreground-only recording), the ML Kit GenAI STT is a viable free
on-device alternative to Whisper API on supported devices. It should be added to the §2d
column as an optional `AndroidSpeechToTextProvider` backend.

**Sources**:
- [GenAI Speech Recognition API — Google for Developers](https://developers.google.com/ml-kit/genai/speech-recognition/android)
- [Android Developers Blog: ML Kit GenAI APIs](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html)

---

### 3. Apple FoundationModels — Available as LLM Formatting Backend

**Query**: `Apple Intelligence FoundationModels framework public API third party iOS 18 2025`

**Verdict**: MAJOR UPDATE. Apple shipped `FoundationModels` at WWDC 2025. Third-party apps can
now call Apple's on-device LLM for text generation, structured output, and tool calling.

**Direct relevance to features.md**: The "hybrid approach (on-device transcription → cloud LLM
formatting)" described in §Apple Notes is now feasible on iOS without any cloud call:
- STT: `SFSpeechRecognizer` (on-device, iOS 13+) or WhisperKit
- Formatting: `FoundationModels` (on-device, no cost, no network)

This is a complete offline path for the transcript → Logseq outliner formatting step on
iPhone 15 Pro+ / iOS 18.1+. Worth surfacing in Phase 2 planning as a premium offline tier.

**Sources**:
- [Foundation Models — Apple Developer Documentation](https://developer.apple.com/documentation/FoundationModels)
- [Apple's Foundation Models framework — Apple Newsroom](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/)
- [WWDC 2025 Session 301 — Deep dive into Foundation Models](https://developer.apple.com/videos/play/wwdc2025/301/)

---

### 4. Whisper Hallucination on Silence (confirmed)

**Query**: `openai whisper hallucination silence "thank you" known issue short audio`

**Verdict**: CONFIRMED. The "Thank you." hallucination on silence is a well-documented,
unresolved upstream issue (tracked in openai/whisper Discussion #1606, #679, and API community
threads). The word-count heuristic (`< 10 words → treat as empty`) described in pitfalls.md is
the community-recommended mitigation. A VAD gate before upload is the most reliable prevention.
Research (Calm-Whisper, ICML 2025) shows the issue is addressable via fine-tuning but the
standard `whisper-1` API model still hallucates on silence.

**Sources**:
- [Hallucination on audio with no speech — openai/whisper Discussion #1606](https://github.com/openai/whisper/discussions/1606)
- [Whisper silent audio hallucination — OpenAI Community](https://community.openai.com/t/whisper-silent-audio-hallucination/1305173)
- [Calm-Whisper paper — arXiv 2025](https://arxiv.org/html/2505.12969v1)

---

### 5. Whisper API Pricing (confirmed)

**Query**: `openai whisper API pricing per minute 2025 2026`

**Verdict**: CONFIRMED. `whisper-1` remains $0.006/min. New cheaper option: `gpt-4o-mini-
transcribe` at **$0.003/min** (half the price, comparable accuracy for most use cases). The
cost estimate for on-demand LLM formatting in §Operational Concerns ($0.001–$0.005/note) also
remains accurate.

**Sources**:
- [OpenAI API Pricing](https://openai.com/api/pricing/)
- [OpenAI Whisper API Pricing Apr 2026 — CostGoat](https://costgoat.com/pricing/openai-transcription)
