# ADR-001: Two-Stage Review Dialog Before Link Commit

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The import feature scans pasted text against existing page names using `AhoCorasickMatcher` and proposes converting matches to `[[wiki links]]`. The core design question is whether to apply those links automatically or require user confirmation before any content is mutated.

`AhoCorasickMatcher` enforces word boundaries and a `MIN_NAME_LENGTH = 3` threshold, but it has no concept of match quality, term frequency, or domain relevance. Any page in the graph with a name of 3+ characters is a potential match. A graph containing pages named "The", "And", "Use", "App", or "Run" would produce hundreds of false-positive link suggestions for any substantive piece of text.

Three UX patterns were evaluated:

**Option A — Two-stage dialog (review before commit)**: Apply `ImportService.scan()`, show the proposed linked text and matched page name list, require the user to click "Import page" before saving anything. No content is written until the user confirms.

**Option B — Silent auto-link on paste**: Insert wiki links automatically when the user pastes text; the linked text is immediately written to a new page without a review step.

**Option C — Suggest only, no auto-insert**: Show matched terms as a side-panel suggestion list; the user manually applies each one by clicking. No links are inserted automatically.

---

## Decision

**Option A: two-stage review dialog.**

Stage 1 collects the source (paste text or URL), the proposed page name, and triggers the scan. Stage 2 shows the linked-text preview and the list of matched page names before the user confirms the save.

---

## Rationale

1. **False-positive safety**: `AhoCorasickMatcher` matches on lexical identity, not semantic relevance. Short page names common in any knowledge graph ("The", "And", "SQL", "API") will match in arbitrary text at high frequency. A review step is the only reliable safeguard that generalizes across all user graphs without a domain-specific stopword list.

2. **Consistency with the existing unlinked-references UX**: `GlobalUnlinkedReferencesViewModel` already follows the pattern of presenting suggestions for the user to accept before writing. Users who are familiar with unlinked references will recognize the import review step as the same mental model.

3. **Reject Option B (silent auto-link)**: There is no mitigation for false positives that works across all user graphs. Even a stopword list cannot account for short page names that are meaningful in the user's domain (e.g., a page named "Run" in a fitness graph is meaningful; "And" never is). Silent mutation of imported content is a trust-destroying action if it produces wrong links.

4. **Reject Option C (suggest-only)**: The existing unlinked-references feature already shows detected-but-not-linked page names after a page is created. If import only suggests without auto-inserting, the feature provides no concrete improvement over the existing workflow of pasting text and then visiting the unlinked-references panel.

---

## Consequences

**Positive**:
- Users retain full control over which links are committed before any page is created
- The review stage provides a useful preview of what the imported page will look like
- False-positive risk is contained to the suggestion list, not the stored content

**Negative / Risks**:
- The review step adds 2–5 seconds of friction compared to silent auto-link
- v1 does not support per-suggestion toggle (all accepted or all rejected at once) — individual link removal is deferred to v2 or post-import editing
- If the scan produces 50 suggestions, the review panel may feel cluttered; a suggestion cap (50 by default) mitigates this
