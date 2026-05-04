# Settings Architecture — Voice Note Feature

## Research Question
How is `VoiceSettings` currently structured, and what is the cleanest way to add `includeRawTranscript: Boolean`?

---

## Current VoiceSettings Structure (from VoiceSettings.kt)

```kotlin
class VoiceSettings(private val platformSettings: Settings) {
    fun getWhisperApiKey(): String?
    fun setWhisperApiKey(key: String)
    fun getAnthropicKey(): String?
    fun setAnthropicKey(key: String)
    fun getOpenAiKey(): String?
    fun setOpenAiKey(key: String)
    fun getLlmEnabled(): Boolean       // default: true
    fun setLlmEnabled(enabled: Boolean)
    fun getUseDeviceStt(): Boolean     // default: true
    fun setUseDeviceStt(enabled: Boolean)
    fun getUseDeviceLlm(): Boolean     // default: false
    fun setUseDeviceLlm(enabled: Boolean)

    companion object {
        private const val KEY_WHISPER = "voice.whisper_key"
        private const val KEY_ANTHROPIC = "voice.anthropic_key"
        private const val KEY_OPENAI = "voice.openai_key"
        private const val KEY_LLM_ENABLED = "voice.llm_enabled"
        private const val KEY_USE_DEVICE_STT = "voice.use_device_stt"
        private const val KEY_USE_DEVICE_LLM = "voice.use_device_llm"
    }
}
```

**Pattern:** Each setting is a get/set pair delegating to `platformSettings: Settings`. The key is a `const val` string in the companion object, namespaced with `"voice."`.

---

## Persistence Mechanism

The `Settings` interface (from `platform/Settings.kt`) is:

```kotlin
interface Settings {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
}
```

This is a **multiplatform abstraction** over platform key-value stores:
- **Android:** SharedPreferences (via `PlatformSettings` in `androidMain`)
- **JVM Desktop:** likely java.util.prefs or a file-based store
- **iOS:** NSUserDefaults

`VoiceSettings` is created with a `PlatformSettings()` instance passed from the host. Looking at App.kt:
```kotlin
val platformSettings = remember { PlatformSettings() }
```
And `VoiceSettings` is created in the Android entry point / App plumbing, not inside App.kt directly — it's passed in as `voiceSettings: VoiceSettings? = null` parameter to `StelekitApp`.

**Key finding:** `VoiceSettings` is an imperative wrapper — no reactive `StateFlow`, no serialization. All reads return current platform value; all writes are synchronous. Backward compatibility is automatic because `getBoolean(key, defaultValue)` returns the default when the key is absent.

---

## Settings UI (VoiceCaptureSettings.kt)

The settings composable pattern:
1. `remember { mutableStateOf(voiceSettings.get...()) }` — local copy of each setting
2. Switch composable with `onCheckedChange = { value = it; saved = false }`
3. "Save" button calls all `voiceSettings.set...()` and `onRebuildPipeline()`

The `onRebuildPipeline` callback is wired in `App.kt` as `onRebuildVoicePipeline` — this triggers the host (Android Activity / desktop entry) to rebuild `VoicePipelineConfig` with updated values from `VoiceSettings`.

---

## How `includeRawTranscript` Flows

Currently `buildVoiceNoteBlock(formattedText, rawTranscript)` in `VoiceCaptureViewModel` unconditionally includes the `#+BEGIN_QUOTE` block. FR-2 requires:
- When `includeRawTranscript = true` (default): current behavior
- When `includeRawTranscript = false`: omit the `#+BEGIN_QUOTE ... #+END_QUOTE` section

The setting needs to flow from `VoiceSettings` → `VoiceCaptureViewModel` → `buildVoiceNoteBlock`.

**Two options for wiring:**
1. **Option A — Constructor injection:** Pass `includeRawTranscript` as a constructor parameter to `VoiceCaptureViewModel`. The host reads it from `VoiceSettings` when building the VM (same pattern as `voicePipeline`).
2. **Option B — Direct Settings injection:** Pass the full `VoiceSettings` instance to `VoiceCaptureViewModel`. The VM calls `voiceSettings.getIncludeRawTranscript()` at `buildVoiceNoteBlock` time.

**Recommendation: Option A** — matches the existing pattern. `VoicePipelineConfig` already carries pipeline behavior; either add `includeRawTranscript` to `VoicePipelineConfig` OR thread it separately. Adding it to `VoicePipelineConfig` is cleanest: it's a pipeline behavior flag, consistent with `systemPrompt` and `minWordCount`.

---

## Minimal Change Proposal

### 1. Add to `VoiceSettings.kt`

```kotlin
fun getIncludeRawTranscript(): Boolean =
    platformSettings.getBoolean(KEY_INCLUDE_RAW_TRANSCRIPT, true)

fun setIncludeRawTranscript(enabled: Boolean) =
    platformSettings.putBoolean(KEY_INCLUDE_RAW_TRANSCRIPT, enabled)

companion object {
    // existing keys ...
    private const val KEY_INCLUDE_RAW_TRANSCRIPT = "voice.include_raw_transcript"
}
```

**Backward compatible:** default is `true`, so existing users see no change.

### 2. Add to `VoicePipelineConfig.kt`

```kotlin
class VoicePipelineConfig(
    // existing params ...
    val includeRawTranscript: Boolean = true,
)
```

### 3. Update `VoiceCaptureViewModel.buildVoiceNoteBlock`

```kotlin
internal fun buildVoiceNoteBlock(formattedText: String, rawTranscript: String): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val timeLabel = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
    return buildString {
        append("- 📝 Voice note ($timeLabel)")
        append("\n  - ")
        append(formattedText.lines().joinToString("\n  - "))
        if (pipeline.includeRawTranscript) {
            append("\n  #+BEGIN_QUOTE\n  ")
            append(rawTranscript)
            append("\n  #+END_QUOTE")
        }
    }
}
```

### 4. Add Toggle in `VoiceCaptureSettings.kt`

Add a new `Switch` row mirroring the `llmEnabled` toggle:

```kotlin
var includeRawTranscript by remember { mutableStateOf(voiceSettings.getIncludeRawTranscript()) }
```

And in the Save button handler:
```kotlin
voiceSettings.setIncludeRawTranscript(includeRawTranscript)
```

Place the toggle in the "LLM Formatting" section with label "Include raw transcript in note".

### 5. Host wiring (Android Activity / App.kt plumbing)

When rebuilding `VoicePipelineConfig` from `VoiceSettings`, read:
```kotlin
VoicePipelineConfig(
    // existing ...
    includeRawTranscript = voiceSettings.getIncludeRawTranscript(),
)
```

---

## Test Impact

`VoiceNoteBlockFormatTest.kt` has a test:
```
`block contains raw transcript in BEGIN_QUOTE block`
```
This test must be updated or parameterized. When `pipeline.includeRawTranscript = false`, the `#+BEGIN_QUOTE` block should be absent.

---

## 3-Bullet Summary

- **`VoiceSettings` uses a simple imperative get/set pattern over a multiplatform `Settings` interface** — adding `includeRawTranscript` is a 4-line addition (constant + getter + setter), backward-compatible by default `true`.
- **The cleanest wiring is to add `includeRawTranscript: Boolean = true` to `VoicePipelineConfig`** — it mirrors how `systemPrompt` and `minWordCount` already carry pipeline behavior, keeping `VoiceCaptureViewModel` free of a `VoiceSettings` dependency.
- **The existing `VoiceNoteBlockFormatTest` test for `#+BEGIN_QUOTE` will need updating** to cover both the `includeRawTranscript = true` (present) and `= false` (absent) cases.
