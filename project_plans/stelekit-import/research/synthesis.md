# Research Synthesis: SteleKit Import Feature

**Date**: 2026-04-14
**Sources**: `research/stack.md`, `research/features.md`, `research/architecture.md`, `research/pitfalls.md`

---

## Decision Required

How to implement a paste-and-URL import flow that auto-links existing graph topics and suggests new ones, without requiring an external AI API, while reusing SteleKit's existing term-matching infrastructure.

---

## Context

SteleKit already has `AhoCorasickMatcher` + `PageNameIndex` for multi-word term detection in block text, `GraphWriter.savePage()` for page creation, and a command palette for feature entry points. The import feature must work offline-first (URL fetch is optional), be KMP-only, and require no external AI. No major PKM tool (Logseq, Obsidian, Roam) currently provides automatic wiki-linking to existing notes on import without a plugin — giving SteleKit a differentiator.

---

## Options Considered

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| **A: ImportService + ImportViewModel + two-stage dialog** | Pure domain service applies `AhoCorasickMatcher` to paste; review screen shows proposed links before commit | Most code reuse, safest UX — user must confirm links |
| **B: Silent auto-link on paste** | Links inserted automatically, no review step | Fast but risky: false positives pollute new page silently |
| **C: Suggest only, no auto-insert** | Shows matched terms as suggestions user manually accepts | Safest, lowest false-positive risk, but high friction |
| **D: External AI for topic extraction** | LLM suggests new pages from content semantics | Highest quality suggestions but violates offline/no-API constraints |

---

## Dominant Trade-off

**Review friction vs. false-positive safety.** `AhoCorasickMatcher` enforces word boundaries and a 3-char minimum but cannot filter common short page names that happen to exist (e.g., a page titled "The" would match everywhere). Silent auto-linking would produce garbage for any graph with short or common page names. The recommendation must force a user review step before writing links.

---

## Recommendation

**Choose: Option A — ImportService + ImportViewModel + two-stage review dialog**

**Because**: All matching primitives already exist (`AhoCorasickMatcher`, `PageNameIndex`, `SearchRepository`). The domain service is a pure function requiring no new infrastructure. A review screen before commit eliminates false-positive risk and matches the UX pattern users of unlinked-references already understand. No external dependency is added for the core paste flow.

**Accept these costs**:
- An extra review step adds ~2 seconds of friction vs. silent auto-link
- URL fetching requires `ksoup` as a new dependency (`com.fleeksoft.ksoup:ksoup:0.2.6`) for HTML stripping
- "Suggest new pages" (topics not yet in graph) requires a separate mechanism — noun-phrase extraction is not yet designed; v1 defers new-page suggestions to a follow-up

**Reject these alternatives**:
- **Option B (silent auto-link)**: Rejected because false-positive matches on common page names are certain; no mitigation short of an exhaustive stopword list which does not generalize across user graphs
- **Option C (suggest only)**: Rejected because it provides no concrete value over the existing unlinked-references feature already in the product
- **Option D (external AI)**: Rejected because it violates the offline-first and no-external-API constraints from requirements

---

## Architecture Summary

### New components (minimal surface area)

| Component | Location | Description |
|-----------|----------|-------------|
| `ImportService` | `domain/ImportService.kt` | Pure function: `(rawText, matcher) → ImportResult(linkedText, matchedPageNames)`. Runs on `Dispatchers.Default`. |
| `ImportViewModel` | `ui/screens/ImportViewModel.kt` | Holds `ImportState`; debounces `ImportService` call (300ms); on confirm calls `GraphWriter.savePage`. |
| `ImportScreen` | `ui/screens/ImportScreen.kt` | Two-stage dialog: (1) source selection (paste tab + URL tab), (2) link review with proposed `[[links]]` highlighted and toggle list. |
| `UrlFetcher` | `domain/UrlFetcher.kt` | `expect`/`actual` interface wrapping `ktor-client` GET + ksoup HTML-to-text; graceful fallback on failure. |
| `Screen.Import` | Sealed class extension | New navigation destination; entry point via command palette `"import.paste-text"`. |

### Reused without modification

- `AhoCorasickMatcher` + `PageNameIndex` — term detection
- `GraphWriter.savePage` — page creation
- `StelekitViewModel.suggestionMatcher` (StateFlow) — injected into `ImportViewModel`
- Command palette `updateCommands()` — new entry added here

### New dependency

```kotlin
// commonMain — HTML parsing for URL import
implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
```

`ktor-client` (3.1.3) and all platform engines are already declared — no additional HTTP dependency needed.

---

## Pitfalls to Address in v1

| Priority | Issue | Mitigation |
|----------|-------|------------|
| P0 | False-positive wiki links | Review step before commit (never silent auto-apply) |
| P0 | Page title collision on save | Pre-save collision check in `ImportViewModel`; prompt user to rename |
| P1 | Large-paste scan perf (>50KB) | Cap suggestions at 50, debounce scan, run on `Dispatchers.Default` |
| P1 | CRLF line endings from Windows paste | Normalize `\r\n → \n` before parsing |
| P2 | Non-ASCII title matching (NFC normalization) | Apply `com.doist.x:normalize:1.2.0` NFC normalization to both input text and page names before matching |
| P2 | URL fetch failure (timeout, paywall, JS-only) | Show error state in UI; degrade to plain text paste; never block on network |

---

## Open Questions Before Committing

- [ ] **New-page suggestion mechanism** — `AhoCorasickMatcher` only matches *existing* page names. Noun-phrase extraction for suggesting *new* pages is undesigned. Decide: defer to v2, or include a manual "add as new page" input field in the review screen? **Blocks ADR for the new-page suggestion subsystem.**
- [ ] **URL fetch scope on iOS** — `ktor-client-darwin` requires App Transport Security exceptions for HTTP (non-HTTPS) URLs; verify this is acceptable or restrict to HTTPS-only in v1.
- [ ] **HTML clipboard flavor on Desktop (Linux/Wayland)** — AWT `DataFlavor.fragmentHtmlFlavor` behavior under Wayland (via XWayland) is unverified. Plain-text paste is always available; HTML flavor is a "nice to have" for richer import.

---

## Sources

- [`research/stack.md`](stack.md) — KMP URL fetching, ksoup, Compose clipboard
- [`research/features.md`](features.md) — Competitive UX analysis (Logseq, Obsidian, Roam, Notion, Readwise)
- [`research/architecture.md`](architecture.md) — Existing codebase primitives, integration points
- [`research/pitfalls.md`](pitfalls.md) — Failure modes and mitigations
