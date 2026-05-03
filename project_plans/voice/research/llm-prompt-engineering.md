# LLM Prompt Engineering — Voice Note Feature

## Research Question
How should `DEFAULT_VOICE_SYSTEM_PROMPT` be updated to produce rich Logseq markdown (bullets, #tags, key:: value properties, **bold**, *italic*, TODO markers)?

---

## Current Prompt (from VoicePipelineConfig.kt)

```kotlin
const val DEFAULT_VOICE_SYSTEM_PROMPT = """You are a Logseq note-taking assistant. Convert the following voice transcript into well-structured Logseq outliner syntax.

Rules:
- Use "- " bullet format for each main point
- Use 2-space indentation for sub-points
- Add [[Page Name]] wiki links ONLY for proper nouns or topics explicitly named in the transcript — do NOT invent links for terms not spoken
- Do not add a preamble or summary
- Do not add content not present in the transcript

Transcript:
{{TRANSCRIPT}}"""
```

**What it produces today:** flat bullet lists with wiki links. No tags, properties, emphasis, or TODO markers.

---

## Logseq Markdown Feature Reference

Logseq's outliner format supports the following constructs that are absent from the current prompt:

| Feature | Syntax | Example |
|---|---|---|
| Hashtag | `#tag` | `#meeting #project` |
| Property | `key:: value` | `date:: 2026-05-02` |
| Bold | `**text**` | `**important**` |
| Italic | `*text*` | `*maybe*` |
| TODO marker | `TODO` at line start | `- TODO call Alice` |
| DONE marker | `DONE` at line start | `- DONE review PR` |
| Wikilink | `[[Page Name]]` | `[[Alice]]` |

---

## Analysis of Current Prompt Weaknesses

1. **No few-shot examples.** Without examples, the model uses its training prior for "Logseq format" which omits tags/properties.
2. **No mention of `#tags`.** The model never produces them.
3. **No mention of properties (`key:: value`).** The model never emits property blocks.
4. **No bold/italic guidance.** The model is conservative and never uses emphasis.
5. **No TODO guidance.** Action items stay as plain bullets.
6. **Constraint is well-stated** ("do not invent content not present in the transcript") — keep this.

---

## Best Practices for Structured-Output Prompts

From the LLM prompting literature and Anthropic guidelines:

1. **Role + task framing** up front (already done — keep).
2. **Explicit enumeration of all output features** — the model only produces constructs it has been explicitly shown.
3. **Few-shot examples** are the single highest-signal intervention for structured output. Each example should cover one feature.
4. **Explicit "only if present" constraints** for every feature that should not be invented.
5. **Ordering matters**: place the most important rules (don't invent content) last so they appear closest to the generation point.
6. **Keep `{{TRANSCRIPT}}` placeholder** — the current replacement logic in `processTranscript()` uses `pipeline.systemPrompt.replace("{{TRANSCRIPT}}", rawTranscript)`.

---

## Candidate Updated Prompt

```
You are a Logseq note-taking assistant. Convert the following voice transcript into well-structured Logseq outliner syntax.

Logseq syntax you may use:
- "- " bullet for each main point (required)
- 2-space indentation for sub-points
- [[Page Name]] wiki links — ONLY for proper nouns or topics explicitly named
- #tag — ONLY for topics or categories explicitly spoken (e.g. "#meeting", "#todo")
- key:: value property blocks — ONLY when the speaker states a clear key/value (e.g. "date:: 2026-05-02", "project:: Stelekit")
- **bold** for words the speaker stressed or called out as important
- *italic* for titles, technical terms, or qualified statements ("*maybe*", "*draft*")
- TODO at the start of a bullet for action items the speaker explicitly commits to
- DONE at the start of a bullet for completed actions explicitly mentioned

Examples of each feature:

Input: "met with Alice today about the Stelekit release, she said to make it a priority"
Output:
- Met with [[Alice]] about [[Stelekit]] release #meeting
  - She flagged this as a priority
- TODO Follow up with Alice on release timeline

Input: "project is stelekit, date is May 2nd, need to review the export feature"
Output:
- project:: Stelekit
- date:: 2026-05-02
- TODO Review the export feature

Input: "I think the new design is okay, maybe try bold colours, definitely update the readme"
Output:
- The new design is acceptable
  - Consider *bold* colours as a possibility
- TODO Update the README

Hard rules (never violate):
- Do NOT invent topics, names, tags, or properties not mentioned in the transcript
- Do NOT add a preamble, summary, or closing line
- Do NOT add content not present in the transcript
- Use TODO only when the speaker explicitly commits to an action

Transcript:
{{TRANSCRIPT}}
```

---

## Constraint Analysis

The new prompt satisfies all FR-1 requirements:
- `#tags` — added with "only when explicitly spoken" guard
- `key:: value` — added with "only when the speaker states a clear key/value" guard
- `**bold**` — added for words "the speaker stressed or called out as important"
- `*italic*` — added for titles, technical terms, qualified statements
- `TODO`/`DONE` — added with "explicitly commits to / explicitly mentioned" guards
- Does not invent content — explicitly restated in hard rules

---

## Implementation Notes

- The prompt lives in `VoicePipelineConfig.kt` as a `const val` — update in place.
- The `replace("{{TRANSCRIPT}}", rawTranscript)` call in `VoiceCaptureViewModel.processTranscript()` continues to work unchanged.
- No new parameters needed in `VoicePipelineConfig` for the prompt change — existing `systemPrompt` field defaults to the updated constant.
- Callers that inject a custom `systemPrompt` are unaffected.

---

## 3-Bullet Summary

- **Current prompt produces flat bullets only** — it never instructs the model to use tags, properties, emphasis, or TODO markers, so none appear in output.
- **Few-shot examples are the highest-value addition** — three short input/output pairs covering tags, properties, and TODO markers will reliably activate these features without token overhead for typical transcripts.
- **All new features require explicit "only if present" guards** to satisfy the "do not invent content" constraint in FR-1; the candidate prompt above adds those guards for every new construct.
