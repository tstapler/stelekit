# Requirements: llm-provider

**Date**: 2026-06-13
**Type**: feature addition

## Problem Statement

Users must manually tag blocks after writing them, which is tedious and inconsistent.
An "untagged blocks" view already exists in the app, but there is no way to trigger
auto-tagging for a specific page, journal entry, or section directly from the editing view —
users must navigate away to the untagged view and tag blocks one at a time.

They want tag suggestions (or automatic application) triggered from multiple entry points:
- A page/journal/section-level action from the editing view ("Tag untagged blocks on this page")
- Block-level action / context menu when a single block is selected
- The Android share widget after a note is captured
- After voice input transcription completes

The system should dynamically leverage whatever is available — local graph matching first,
LLM-assisted suggestions when configured.

## Users / Consumers

Both human users and automated systems:
- **Human users**: trigger tagging manually via a block action (select block → "Suggest tags")
- **Share widget pipeline**: fires tagging automatically after a note is captured via the Android share sheet
- **Voice input pipeline**: fires tagging automatically after voice transcription completes

## Success Metrics

Hybrid confidence model:
- **High-confidence matches** (direct page-name matches in the graph) are auto-applied to the block without user interaction
- **Low-confidence suggestions** (LLM-inferred or fuzzy matches) are presented in a suggestion UI with explicit Accept (✓) / Dismiss (✗) buttons per chip
- Local suggestions appear in <100ms; LLM suggestions append async in the background with no spinner (progressive enhancement)
- Feature degrades gracefully to local-only matching when offline or no LLM is configured

## Constraints

- **Must work offline**: local direct-match tagging must be the baseline with zero network dependency
- LLM calls use the **existing configured LLM provider** (Anthropic/OpenAI key already in VoiceSettings) — no new API key settings screen; the feature is active automatically when a key is already configured
- Must not require a mandatory network call to suggest any tags

## Resolved Decisions

| Question | Decision |
|----------|----------|
| New tag names from LLM? | **User-configurable toggle** in the suggestion UI: "Allow new tags" (default off). When off, LLM output is filtered to existing pages only. |
| Latency SLA | Local tier: <100ms (synchronous, no spinner). LLM tier: async, results append when ready — no total wait budget. |
| API key storage | **Reuse existing VoiceSettings LLM keys** (Anthropic key, OpenAI key). No new key management. Tags feature is automatically active if any key is configured. |
| LLM providers | Whatever is already configured: Anthropic Claude (primary), OpenAI-compatible endpoint (secondary). |
| Block context menu | "Suggest tags" added to the block's **overflow/action menu** (three-dot or toolbar), not long-press (avoids conflict with selection mode). |
| Page-scope accepted tag target | Tags are applied **inline to each individual block** in the suggestion sheet; the sheet groups suggestions by block, showing a snippet of block text above each tag chip row. |

## Scope

### In Scope

- **Tag suggestion engine** with two tiers:
  1. **Local matcher**: scan block text for substrings matching existing page names (reuse `PageNameIndex` + `AhoCorasickMatcher`); high-confidence, offline
  2. **LLM provider**: call the already-configured LLM (via existing `ClaudeTopicEnricher` / `LlmFormatterProvider` pattern) for semantic suggestions; low-confidence
- **Entry points** that trigger the engine:
  - **Page/journal/section scope**: "Suggest tags for this page" in the page overflow menu — processes all untagged blocks; shows grouped suggestion sheet
  - **Block scope**: "Suggest tags" in the block action/overflow menu for a single block
  - **Share widget**: automatic trigger after note capture (via WorkManager)
  - **Voice input**: automatic trigger after transcription completes
- **Suggestion UI**: bottom sheet with block-text snippet + tag chips; each chip has explicit ✓ Accept and ✗ Dismiss buttons; "Allow new tags" toggle at top
- **No new settings screen** — reuses existing LLM provider configuration

### Out of Scope

- New LLM API key settings screen (reuse existing voice LLM keys)
- Bulk-tagging existing blocks retroactively from a dedicated view (the page-scope trigger in the editor covers this)
- On-device / local LLM inference (remote API only for MVP)

## Open Questions

1. Should the LLM receive only the block text, or also surrounding block context (parent/siblings)?
