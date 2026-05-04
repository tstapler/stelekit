# Android SpeechRecognizer Silence Tuning + Min Word Count

## Research Question
What are the correct Intent extras for extending SpeechRecognizer silence tolerance, and what is the safest way to remove the `minWordCount` guard?

---

## Current Implementation (AndroidSpeechRecognizerProvider.kt)

### Intent extras currently set

```kotlin
val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
}
```

### Current values summary

| Extra | Current value | Meaning |
|---|---|---|
| `EXTRA_LANGUAGE_MODEL` | `LANGUAGE_MODEL_FREE_FORM` | Dictation mode |
| `EXTRA_PREFER_OFFLINE` | `true` | Use on-device if available |
| `EXTRA_MAX_RESULTS` | `1` | Return only top result |
| `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` | 3,000 ms | Stop listening after 3s of silence |
| `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` | 1,500 ms | Consider utterance possibly done after 1.5s |

**Notable absence:** `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` is not set — FR-5 requires adding it at 2,000 ms.

---

## Android SDK Extra Documentation

### `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`

- **Class:** `android.speech.RecognizerIntent`
- **API level:** Added in API 8 (Android 2.2)
- **Type:** `long` (milliseconds)
- **Meaning:** The amount of time after the user stops speaking that the recognizer will wait before returning results.
- **Default (Google recognizer):** ~1,500–2,000 ms
- **FR-5 target:** 6,000 ms

### `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`

- **Class:** `android.speech.RecognizerIntent`
- **API level:** Added in API 8 (Android 2.2)
- **Type:** `long` (milliseconds)
- **Meaning:** The amount of silence the recognizer tolerates while potentially still listening (e.g., mid-sentence pause).
- **Default:** ~500–1,000 ms
- **FR-5 target:** 3,000 ms

### `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`

- **Class:** `android.speech.RecognizerIntent`
- **API level:** Added in API 8 (Android 2.2)
- **Type:** `long` (milliseconds)
- **Meaning:** Minimum duration of speech input before the recognizer considers the input complete. Prevents very short utterances from triggering premature end-of-speech.
- **Not currently set.** FR-5 requires adding at 2,000 ms.

### Important caveat: OEM / AOSP recognizer honoring

These extras are **hints to the recognition service**, not hard guarantees. The Google Recognition Service (GOOG-Speech) typically honors them; AOSP SpeechRecognizer and some OEM implementations may ignore them. The current code already uses `EXTRA_PREFER_OFFLINE = true`, which routes to the on-device recognizer — behavior may vary by device and Android version. This is inherent to the Android SpeechRecognizer API and cannot be worked around.

---

## Exact Lines to Change in AndroidSpeechRecognizerProvider.kt

### Current (lines 108–114)

```kotlin
val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
}
```

### Updated (FR-5)

```kotlin
val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_000L)
}
```

---

## `minWordCount` Guard — Analysis and Removal

### Current implementation (VoicePipelineConfig.kt + VoiceCaptureViewModel.kt)

**VoicePipelineConfig.kt:**
```kotlin
class VoicePipelineConfig(
    // ...
    val minWordCount: Int = 10,
)
```

**VoiceCaptureViewModel.processTranscript() (lines 116–123):**
```kotlin
val wordCount = rawTranscript.split(Regex("\\s+")).count { it.isNotBlank() }
if (wordCount < pipeline.minWordCount) {
    _state.value = VoiceCaptureState.Error(
        PipelineStage.TRANSCRIBING,
        "Recording too short — try speaking for a few more seconds"
    )
    return
}
```

### Tests that reference `minWordCount`

In `VoiceCaptureViewModelTest.kt`, these tests exercise the word-count gate:

1. `word-count gate under 10 words emits Error at TRANSCRIBING` — transcript "too short" (2 words)
2. `9-word transcript emits Error at TRANSCRIBING` — 9 words → error
3. `10-word transcript reaches Done state` — 10 words → Done

These tests must be **deleted or repurposed** when the guard is removed (FR-4).

### What to remove

**In `VoicePipelineConfig.kt`:** Delete `val minWordCount: Int = 10`.

**In `VoiceCaptureViewModel.processTranscript()`:** Delete lines 116–123:
```kotlin
// DELETE:
val wordCount = rawTranscript.split(Regex("\\s+")).count { it.isNotBlank() }
if (wordCount < pipeline.minWordCount) {
    _state.value = VoiceCaptureState.Error(
        PipelineStage.TRANSCRIBING,
        "Recording too short — try speaking for a few more seconds"
    )
    return
}
```

After removal, `processTranscript()` proceeds directly to the `Formatting` state for any non-empty transcript. The `TranscriptResult.Empty` path (which returns an error for a blank result) is already handled in `startPipeline()` before `processTranscript` is called — that guard is independent and should remain.

### Safety analysis of removing the guard

The `minWordCount` guard was introduced to prevent the LLM from receiving trivial transcripts (single words, noise). After removal:

- Short transcripts (e.g., "yes", "okay") will be sent to the LLM. This is acceptable — the LLM will output a minimal bullet like `- Yes.` and the voice note will be inserted.
- The `TranscriptResult.Empty` guard in `startPipeline()` still blocks completely blank transcripts from ever reaching `processTranscript`.
- The `MAX_TRANSCRIPT_CHARS = 10_000` truncation guard is unaffected.
- LLM cost impact: negligible. Short transcripts = short prompts = fewer tokens.

**Verdict: safe to remove.** The only remaining guard against a truly empty result is `TranscriptResult.Empty` handled before `processTranscript` is called.

---

## 3-Bullet Summary

- **The three silence extras (`COMPLETE`, `POSSIBLY_COMPLETE`, `MINIMUM_LENGTH`) are all available since API 8** — no API level restrictions apply; the change is two value updates (`3_000L → 6_000L`, `1_500L → 3_000L`) plus one new `putExtra` for `MINIMUM_LENGTH_MILLIS = 2_000L`, all in `listenInternal()` of `AndroidSpeechRecognizerProvider.kt`.
- **`minWordCount` removal requires deleting the field from `VoicePipelineConfig` and 7 lines from `processTranscript()`** — the `TranscriptResult.Empty` guard already upstream in `startPipeline()` continues to block truly blank results, so there is no safety regression.
- **Three existing unit tests in `VoiceCaptureViewModelTest` must be deleted** (`word-count gate under 10 words`, `9-word transcript`, `10-word transcript`) — they test behavior that will no longer exist after FR-4.
