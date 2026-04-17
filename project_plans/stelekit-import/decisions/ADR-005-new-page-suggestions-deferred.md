# ADR-005: Defer New-Page Suggestions to v2

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The original requirements include: "Topics detected in the imported content that don't yet exist as pages are surfaced as suggestions to create new pages." This feature — identifying *new* topics in imported text that are not yet in the graph — is distinct from the auto-linking feature, which identifies topics that *already exist* as pages.

`AhoCorasickMatcher` can only match patterns it was initialized with (existing page names). It cannot identify *new* topic candidates in arbitrary text. A separate mechanism is needed.

Three options were evaluated for v1:

**Option A — Noun-phrase extraction via regex NLP**: A regex-based pipeline that identifies noun phrases (capitalized sequences, proper nouns, technical terms) as candidate new-page names. No external dependency, but low precision — many false candidates in typical prose.

**Option B — User-supplied candidate input field**: Add a text field in the import UI where the user manually types names of new pages they want to create from the imported content. Low engineering effort; puts the cognitive work on the user.

**Option C — Defer entirely to v2**: v1 includes only auto-linking to *existing* pages via `AhoCorasickMatcher`. New-page suggestion is a follow-on feature with its own design and implementation track.

---

## Decision

**Option C: defer new-page suggestions to v2.**

v1 `ImportService.scan()` returns only `matchedPageNames` (existing pages found in the text). The `ImportResult` type includes no new-page suggestion field. The `ImportScreen` review stage shows no "create new page" UI.

---

## Rationale

1. **AhoCorasickMatcher cannot identify new topics**: The matcher is a trie over known page names. It has no output for patterns not in its vocabulary. Building new-page suggestion requires a different algorithm — either NLP noun-phrase extraction, a heuristic (capitalized multi-word sequences), or an LLM call (prohibited by requirements). None of these are designed.

2. **Option B (manual input) provides marginal value**: A text field for the user to type candidate new-page names is essentially a page-creation widget that already exists elsewhere in the app. It adds UI complexity without adding intelligence to the import flow.

3. **Scope discipline**: The requirements allow phased delivery. The auto-linking feature (matching existing pages) already differentiates SteleKit from Logseq and Obsidian, which do not auto-link on import at all. Shipping that differentiation in v1 is more valuable than shipping a low-fidelity new-page suggestion feature alongside it.

4. **v2 design is unblocked**: Deferring does not constrain the v2 design. `ImportResult` can be extended with a `suggestedNewPageNames: List<String>` field without breaking the existing `ImportService` contract. `ImportScreen` stage 2 can add a "Create new pages" checklist without structural changes to the two-stage layout.

---

## Consequences

**Positive**:
- v1 scope is reduced by one undesigned subsystem
- `ImportService` remains a single pure function with a simple signature
- No noun-phrase extraction library or heuristic is introduced that would need maintenance

**Negative / Risks**:
- The requirements' "suggest new tags" success criterion is not met in v1; this must be communicated as a known deferral
- After the imported page is created, users can visit the unlinked-references panel to find terms they may want to create pages for — this is a partial workaround that already exists
- If v2 adds new-page suggestions, `ImportResult` must be extended and `ImportScreen` must add a third review sub-section; the two-stage layout must accommodate a conditional third stage or expand stage 2
