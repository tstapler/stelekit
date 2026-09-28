# Research: Build vs. Buy — Capture Auto-Enrich

**Dimension**: Build vs. buy / reuse-vs-reinvent
**Date**: 2026-08-10

## Confirming the reuse premise

All four named files exist and are usable as internal libraries, confirmed by reading each:

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt` (146 lines) —
  `object ImportService` with pure function `scan(rawText, matcher, existingNames)` →
  `ScanResult(linkedText, matchedPageNames, topicSuggestions)`. No `CaptureViewModel`-specific
  coupling; already Import-agnostic.
- `domain/TopicExtractor.kt` (163 lines) — `TopicExtractor.extract(rawText, existingNames)`,
  dedups against `existingNames` (lowercased) and applies the 0.2 confidence floor from
  ADR-004 before returning candidates.
- `domain/TopicSuggestion.kt` (14 lines) — plain data class + `Source` enum
  (`LOCAL_HEURISTIC` / `AI_ENHANCED`).
- `domain/ClaudeTopicEnricher.kt` (88 lines) — `class ClaudeTopicEnricher(private val
  claudeProvider: LlmFormatterProvider) : TopicEnricher`. As of the landed `llm-service` Epic 8
  Story 8.3 migration (commit `13de1d80ca`, "Epic 8 — migrate voice/tag-suggestion/topic-
  enricher onto the registry"), it delegates to the shared `LlmFormatterProvider` contract
  (`ClaudeLlmFormatterProvider.withDefaults` by default) instead of an independent HTTP/retry
  stack — the hand-rolled 429-retry path pitfalls research flagged is gone.
- `voice/LlmFormatterProvider.kt` + `ClaudeLlmFormatterProvider.kt` /
  `OpenAiLlmFormatterProvider.kt` / `GeminiLlmFormatterProvider.kt` all exist.

**`llm-service` status — resolves the requirements doc's open question**: it is landed, not
partial. `git log` on `voice/` shows the full Epic 1→8 sequence merged, ending in Epic 8's
consumer migration (`13de1d80ca` + three follow-up review-fix commits, most recent
`6fa5f670b7`). `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/` contains
`LlmProviderRegistry.kt`, `LlmProviderRegistryFactory.kt`, `LlmProvider.kt`,
`LlmProviderKind.kt`, `LlmSettings.kt`, `LlmFeature.kt`, `RemoteLlmProvider.kt`,
`CustomOpenAiCompatibleLlmProvider.kt`, `LlmCredentialStore.kt`. **Conclusion: this item can
consume the unified `LlmProviderRegistry`/`LlmFeature` selection directly** rather than
calling `ClaudeTopicEnricher`'s old standalone dependency path — the fallback branch in the
requirements ("must call `ClaudeTopicEnricher`'s existing dependency directly") does not
apply.

The reuse-only premise holds: **there is no algorithmic gap** — matching, suggestion
generation, dedup, confidence thresholding, and LLM-provider selection are all shipped,
tested, in-house code with pure/composable entry points.

## 1. Existing OSS library/framework for any genuinely new piece?

No new algorithmic component is needed. The two candidate "new utility" surfaces both have
adequate answers already in-repo or in the Kotlin stdlib — no OSS dependency addition is
justified:

- **Debounce/timeout for the enrichment pass**: Kotlin coroutines' stdlib
  `withTimeoutOrNull { ... }` is sufficient to implement the "never block save past the
  responsiveness budget" requirement. No library needed. (Existing precedent:
  `PageNameIndex` and repo-wide dispatcher/guard conventions in `CLAUDE.md` already lean on
  plain coroutine primitives, not a timeout library.)
- **Shared suggestion-chip Compose component**: checked whether one already exists,
  independent of `ImportScreen.kt`, in `ui/components/`. **It does not.** The chip UI
  (`TopicSuggestionTray`, `TopicSuggestionChip`) lives as `private @Composable` functions
  directly inside `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt`
  (lines 440–620), not as a standalone/exported component. See §4 for the extraction
  recommendation — this is a code-organization move (promote existing composables to a
  shared file), not new UI-framework work; no OSS component library is warranted (the
  existing chips are built directly on Material3 `SuggestionChip`/`LazyRow`/`Icon` primitives
  already in the dependency graph).

**Verdict: 100% of this item is wiring existing in-house code + promoting one existing
private composable to a shared location.** No new OSS dependency needed.

## 2. SaaS/managed API

**N/A**, as expected for an internal wiring task — confirmed, not just assumed. The LLM tier
this item needs (remote Claude/OpenAI/Gemini *and* on-device ML Kit/Gemini Nano/iOS
Foundation Models) is already fully provided by the landed `llm-service` `LlmProviderRegistry`
(§ above). No new managed API integration is required or in scope; the requirements doc's
"Out of Scope" section explicitly excludes finishing `llm-service`'s own UI, and this research
confirms `llm-service` itself needs no further backend work for this item to consume it.

## 3. Risk: LLM-generated implementation reinventing existing logic

**Explicit risk, flag in the plan.** Given an implementer working from the requirements doc
alone (without having read `ImportService.kt`/`TopicExtractor.kt` closely), the most likely
reinvention failure modes are:

- **Aho-Corasick / matching**: writing a new regex- or substring-based scan directly in
  `CaptureViewModel.performSave()` instead of calling `ImportService.scan(text, matcher,
  existingNames)`. `AhoCorasickMatcher` and the `PageNameIndex.matcher: StateFlow<
  AhoCorasickMatcher?>` it's built from are non-trivial (trie construction, alias/stemming
  variants in `PageNameIndex.buildEntries()` — see `addAliasIfEligible`,
  `extractParentheticalBase`, `stemVariants`) and must not be reapproximated.
- **Suggestion dedup**: `TopicExtractor.extract()` already handles case-insensitive dedup
  against `existingNames` and the 0.2 confidence floor from ADR-004. A new implementation
  might re-filter or re-threshold suggestions inside `CaptureViewModel`, producing behavior
  that silently diverges from the Import screen's (violates the "matching in-app Import
  behavior" success criterion).
- **Timeout handling**: rolling a custom cancellation/race mechanism instead of the
  stdlib `withTimeoutOrNull`, risking a leaked coroutine or a save that's still blocked on
  enrichment.
- **LLM call plumbing**: instantiating `ClaudeLlmFormatterProvider` (or similar) directly
  with a hardcoded API key path instead of going through `LlmProviderRegistry`/`LlmFeature`
  selection, silently bypassing whatever provider (including on-device) the user has actually
  configured.

**Guard recommendation for the implementation plan**: name `ImportService.scan()`,
`TopicExtractor.extract()`, and `LlmProviderRegistry`/`LlmFeature` as the *only* allowed entry
points for matching/suggestion/provider-selection logic in the task breakdown, and make "no
new matcher/dedup/timeout code" an explicit code-review checklist item (mirrors the
requirements doc's own "Out of Scope: New matching/suggestion algorithms" line — this is
just operationalizing that constraint for whoever implements it).

## 4. Fork/extract vs. parallel reimplementation for the chip UI

Read `ImportScreen.kt`'s chip UI in full (`TopicSuggestionTray` lines 440–543,
`TopicSuggestionChip` lines 545–620) against ADR-004's spec. Findings:

**`TopicSuggestionChip`** (single-chip composable, lines 545–620) is **already generic** —
its signature is `(suggestion: TopicSuggestion, onAccepted: () -> Unit, onDismissed: () ->
Unit)`, with no `ImportState`/`ImportViewModel` coupling. It implements exactly the ADR-004
chip anatomy (confidence dot color, accepted/unaccepted visual states, dismiss/accept icon
buttons) that the requirements doc says `CaptureScreen` should mirror. It is `private`, so it
cannot currently be reused as-is.

**`TopicSuggestionTray`** (container, lines 440–543) is **Import-review-specific**: it takes
the whole `ImportState`, renders the `claudeStatus` badge with Import-specific wording
("AI-enhanced" / "AI unavailable"), and its "Accept All" confirmation dialog text hardcodes
Import's copy ("This will create up to $pendingCount new stub pages"). `CaptureScreen`'s
bottom-sheet context (per the requirements' "responsiveness budget" and "dismissible chips in
the capture bottom sheet" framing) is a materially different container — likely a compact
inline tray rather than a full `Scaffold` review stage — so a literal `ImportState`-typed
container doesn't fit.

**Recommendation**: **extract, don't duplicate, at the chip level; don't force the tray
container to be shared.**
- Promote `TopicSuggestionChip` (and its confidence-dot color logic) out of `ImportScreen.kt`
  into a shared file, e.g. `ui/components/TopicSuggestionChip.kt`, made non-private. This is a
  pure move/visibility change — zero logic changes, zero risk to `ImportScreen`'s existing
  Roborazzi screenshot coverage beyond a possible import-path update.
- Have `CaptureScreen` build its own lightweight tray composable around the shared chip,
  sized for the bottom-sheet context, rather than reusing `TopicSuggestionTray` wholesale or
  passing a synthetic `ImportState` into it just to satisfy its signature.
- This satisfies the requirements doc's "mirrors `ImportViewModel.confirmImport()`" and
  "mirrors the existing Import review-stage distinction" language (behavioral/visual parity)
  without a forced UI-container fork that would need to fight `ImportState`'s Import-specific
  fields.

## Summary

1. No OSS library gap — matching, dedup, LLM-provider selection are fully shipped in-house;
   `llm-service` (the one dependency the requirements doc flagged as "verify current state")
   is confirmed **fully landed** (Epic 1–8, ending commit `13de1d80ca` + review fixes), so
   this item can consume `LlmProviderRegistry`/`LlmFeature` directly.
2. SaaS/managed API is N/A, confirmed — the registry already covers remote and on-device
   tiers.
3. Real risk is an implementer reinventing Aho-Corasick matching, suggestion dedup, or
   timeout handling instead of calling `ImportService.scan()`/`TopicExtractor.extract()`/
   `withTimeoutOrNull` — call this out explicitly as a plan/code-review guard.
4. Extract `TopicSuggestionChip` (already generic) into a shared component; keep
   `TopicSuggestionTray`'s container Import-specific and build a purpose-fit tray for
   `CaptureScreen`'s bottom-sheet layout.
