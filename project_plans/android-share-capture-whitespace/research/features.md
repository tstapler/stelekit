# Research: Features — Android Share Capture Whitespace Normalization

## 1. Existing whitespace/normalization code in this codebase

No existing utility does the general-purpose whitespace collapsing this feature needs. What
exists is narrower and line-scoped, not a reusable base:

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownPreprocessor.kt:13-50` —
  `normalize()` rewrites **leading indentation** on list items to a canonical 4-space-per-level
  scheme (`calculateLevel()`, line 60-104, treats 1 tab or ~2 spaces as one level). It is
  indentation-*restructuring*, not whitespace-*collapsing*, and it is structure-aware (list-item
  vs. continuation line vs. blank line) — it never touches whitespace that appears mid-line.
- `parser/PropertiesParser.kt:17,32,64` — `Regex("""^\s*([\w\-_]+)::\s*(.*)$""")` plus per-line
  `.trim()`. Trims individual lines/fields only.
- `parser/TimestampParser.kt:23,46` — regex-extracts `SCHEDULED:`/`DEADLINE:` timestamps, then
  `.trim()`s what's left. Field-scoped, not general collapsing.
- `outliner/OutlinerPipeline.kt:44,48,67,78,94,113` — six separate `.trim()` calls on individual
  lines/values during block-tree construction. Also `PROPERTY_REGEX`, `TAG_REGEX`
  (`Regex("(?:^|\\s)#([^\\s#]+)")`), `PAGE_REF_REGEX`, `BLOCK_REF_REGEX` — all line- or
  token-scoped matches, none collapse repeated whitespace or touch NBSP/blank-run normalization.
- Repo-wide grep for ` ` / NBSP / `\s{2,}`-style collapsing regexes: no hits anywhere in
  `kmp/src/commonMain`. This confirms the bug report's premise — nothing upstream in the
  parse/outline pipeline normalizes NBSP or collapses whitespace runs today, so
  `buildShareText()` output flows into `OutlinerPipeline` completely unnormalized on that axis.

**Consistency implication for planning:** because nothing else in the codebase does this kind of
collapsing, there's no existing convention to match, but there *is* an existing convention to
respect: `MarkdownPreprocessor` and `OutlinerPipeline` both treat **leading/structural
whitespace as semantically significant** (indent level = outline nesting depth; 2-space vs
4-space list indentation is meaningfully different). Any new whitespace-collapsing pass that
runs before this pipeline sees the text needs to not contradict that convention — see the flag
in §3 below.

## 2. Edge cases beyond the 8 listed acceptance criteria

- **Tabs mixed with spaces in the same run** — AC1 says "2+ consecutive regular spaces or tabs
  collapse to a single space" but doesn't specify the run can be *mixed* (`" \t \t"`). A
  character-class regex (`[ \t]{2,}`) handles this naturally; a two-pass approach (collapse
  spaces, then separately collapse tabs) would miss mixed runs. Worth an explicit test case.
- **Unicode whitespace beyond NBSP** — AC2 only calls out U+00A0. Browser/HTML paste sources
  commonly also carry U+2003 (em space), U+2002 (en space), U+2009 (thin space), U+200B
  (zero-width space — technically not whitespace but often introduced by JS-rendered text and
  invisible), and U+FEFF (BOM/zero-width no-break space) at the start of pasted content. None of
  these are in scope per AC2's literal wording (NBSP only), but they produce the same visual bug
  the user is filing this ticket about. Flag for planning: decide explicitly whether the fix is
  NBSP-only (matches AC as written) or should use a broader Unicode-whitespace class — don't
  silently expand scope during implementation.
- **`\s`-based regex would silently violate AC4** — Java/Kotlin's `\s` character class includes
  `\n`, `\r`, `\t`, `\f`, `\x0B`, and space. If the space/tab collapsing regex is naively written
  as `\s{2,}` instead of `[ \t]{2,}`, two lines separated by a single `\n` immediately followed by
  leading whitespace on the next line (e.g. `"line one\n  line two"`) would get its `\n` consumed
  by the same collapse pass, silently merging lines that AC4 requires to stay separate. This is
  an implementation pitfall, not an AC gap, but worth calling out explicitly in the plan since the
  two ACs (space-collapse vs. blank-line-collapse) must be implemented as genuinely separate
  passes/regexes operating on disjoint character classes.
- **Whitespace-only lines that aren't literally blank** — AC3's second clause ("newlines
  separated only by whitespace-only lines") requires detecting lines that are all-whitespace,
  not just zero-length. A naive `\n{3,}` regex only catches the *first* half of AC3 (pure blank
  lines); the whitespace-only-line case (e.g. a line containing a single NBSP or a stray space —
  the literal browser-DOM artifact cited in the problem statement) requires either running NBSP
  normalization *before* blank-line collapsing, or a line-based (`isBlank()`-per-line) approach
  rather than a single global regex. Order-of-operations between AC1/AC2 and AC3 matters and
  should be made explicit in the plan (normalize NBSP→space and collapse runs first, then treat
  now-whitespace-only lines as blank for AC3's purposes).
- **Markdown syntax chars (`-`, `*`, `#`) at line starts** — confirmed safe *if* blank-line
  collapsing is implemented as true line-based blank detection (`line.isBlank()`), since a line
  starting with `- `, `* `, or `#` is never blank and won't be misclassified. This is safe by
  construction, not something requiring special-case code — but only if the implementation
  avoids a purely-regex `\n\s*\n\s*\n` style pattern that could be tempted to treat "mostly
  blank" runs sloppily. Note it as a specific test case (e.g. `"para one\n\n\n- bullet"` must
  become `"para one\n\n- bullet"`, not corrupt the bullet).
- **Leading/internal indentation collapsed away** — see §3, this is the most consequential flag.
- **Emoji / multi-byte char adjacency** — Kotlin strings are UTF-16; emoji outside the BMP are
  surrogate pairs. Standard regex space/tab character classes only match literal ASCII space/tab
  code units, so a regex like `[ \t]{2,}` will not touch surrogate pairs and is safe next to
  emoji. Worth one test case (e.g. `"🎉  🎊"` → `"🎉 🎊"`) to prove this rather than assume it,
  since Kotlin `String.trim()`/regex behavior around supplementary-plane characters has caused
  bugs elsewhere in this codebase's history (see `MEMORY.md` note on wasmJs platform-layer
  field-snapshot bugs — different issue, but a reminder that "should be fine" assumptions about
  string/char handling in this repo warrant a test).
- **Very long single-line paste** — share payloads can be an entire selected webpage's text as
  one long line. `[ \t]{2,}` / line-based blank-run regexes are linear-time (no nested
  quantifiers, no catastrophic backtracking risk), so this is a non-issue for the *regex design*
  chosen above, but is worth one bounded-size test/benchmark note rather than assuming it away.
- **Mixed CRLF/LF within one payload** — requirements already flag this as required (AC7 CRLF
  test case), but note the concrete failure mode if unhandled: a payload with `\r\n\r\n` (CRLF
  blank line) mixed with bare `\n\n` elsewhere in the *same* string (plausible if share text was
  assembled by joining subject/body from different sources, which `buildShareText()` already
  does at line 197: `"$title\n$body"` — title from `EXTRA_SUBJECT` and body from clipboard could
  have different line-ending conventions). The normalization should canonicalize `\r\n`→`\n` (and
  stray `\r`→`\n`) as a first step, before any collapsing logic runs, or AC3's "3+ consecutive
  newlines" count will silently undercount CRLF-delimited blank runs.

## 3. Unstated user need — flag for planning to reconcile (not to override)

**AC1's literal wording ("2+ consecutive regular spaces or tabs collapse to a single space")
does not scope the collapsing to inline/running text — it would also collapse leading
indentation.** This is a real conflict with an existing, deliberate convention in this codebase:
`OutlinerPipeline.calculateLevel()` and `MarkdownPreprocessor.calculateLevel()` (lines 60-104 and
same-named function) both treat 2-space (or 4-space) leading indentation as *structurally
significant* — it's how nested outline levels and CommonMark sub-blocks are represented in
Logseq/SteleKit markdown.

Concrete failure scenario: a user shares text that already looks like a Logseq outline or a code
snippet — e.g. forwarding a previously-exported block, or sharing a snippet from a code
editor/terminal app via Android's share sheet:

```
function foo() {
    return 1;
}
```

or

```
- parent bullet
  - child bullet
```

Under AC1 as literally written, the 4-space code indent or 2-space nested-bullet indent are each
runs of 2+ regular spaces and would collapse to a single space, destroying the code structure or
flattening the outline nesting the user was trying to preserve. This is very plausibly *not* what
the acceptance criteria intended (the problem statement is specifically about browser/HTML DOM
artifacts — repeated spaces from rendered whitespace, not intentional code/outline indentation),
but the AC text as written does not distinguish "leading whitespace" from "internal/running
whitespace."

This is exactly the class of unstated need the research question calls out: users pasting code
snippets, ASCII art, or already-structured outline text via share sheet would reasonably expect
*that* whitespace preserved, while still wanting the browser-garbage case (repeated spaces
*within* a sentence, NBSP droppings) cleaned up. The acceptance criteria conflate both under one
rule.

**Flagging this for planning to reconcile** — options planning may want to weigh (not resolving
here, per task scope):
1. Scope AC1's collapsing to non-leading whitespace only (e.g. collapse runs found after the
   first non-whitespace character on a line, leave leading indentation untouched).
2. Accept AC1 as globally-applied and treat "share text with meaningful indentation" as
   explicitly out of scope for this ticket (i.e. the share-capture path is document-prose only,
   never code/outline paste) — if so, this should be a documented, deliberate trade-off, not a
   silent side effect discovered post-ship.
3. Some hybrid (e.g. skip collapsing on lines that look like markdown list items or start with
   4+ spaces, mirroring `MarkdownPreprocessor.isListItem()`'s heuristic).

No code change is proposed here; this is purely a flag for the planning phase given the
requirements doc's explicit instruction that AC1-8 are locked ("derived 1:1 from the backlog
item's acceptance criteria").

## Key files referenced

- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201` (`buildShareText`)
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:42` (`updateText`), `:54`
  (`save()`, existing `.trim()`)
- `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt` (12 existing `@Test`
  cases to preserve per AC5)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownPreprocessor.kt:13-106`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/PropertiesParser.kt:15-74`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/TimestampParser.kt:16-46`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/outliner/OutlinerPipeline.kt:13-113`
