# Coverage wave C: `mcp`, `tags`, `llm` (JVM row)

Source and method as in `wave-A.md`. `mcp` is the lowest T2 package on the JVM row (73.8% against a 70 target); `tags` and `llm` gaps are error-mapping branches in classes the baseline marks partly covered.

| File | Baseline | After (new tests alone added) |
|---|---|---|
| `mcp/McpServerController.kt` | 0/43 | 23/43 |
| `tags/LlmTagProvider.kt` | 61/71 | 71/71 |
| `llm/LlmSuggestionWriter.kt` | 92/104 | 104/104 |

Package totals, raw XML basis: `mcp` 519/703 (73.8%) to 546/703 (77.7%), `tags` 91.4% to 94.5%, `llm` 94.2% to 96.3%. The `mcp` +27 includes 4 lines attributed to inlined kotlinx-coroutines frames (`Emitters.kt`, `SafeCollector.common.kt`), not project code.

Tests: `McpServerControllerTest`, `LlmTagProviderBranchesTest`, `LlmSuggestionWriterEditsTest`.

Android-row note (the "Robolectric-only" packages `tags`, `voice`, `llm`, `capture`): the android-row percentages for these packages are an artifact of Robolectric running a small subset of the suite. Their `commonMain` classes already run at 77-96% on the JVM row (for example `VoiceCaptureViewModel` 144/165, `TagSuggestionViewModel` 152/164, `LlmSynthesisService` 71/82). What only the android row can see is `androidMain` glue (`AndroidAudioRecorder`, `AndroidSpeechRecognizerProvider`, `MlKitLlmFormatterProvider`), which needs `AudioRecord`, `SpeechRecognizer` and ML Kit AICore, so a Robolectric test of it would assert on stubs and was not written.
