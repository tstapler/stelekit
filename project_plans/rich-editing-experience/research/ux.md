# Research: UX Patterns for Rich Editing Experience

Agent 5 — UX Research, SDD Phase 2, `rich-editing-experience`

## 1. Comparable UX patterns (competitive scan)

**Slash/command menus (Notion, Obsidian via SlashComplete/Slash Inserter plugins)**
- Notion: typing `/` at cursor opens an inline contextual menu; typing further filters it; selecting an item (e.g. "Link to page") inserts the construct with cursor left in the right spot. Total interaction for "insert a link": **type `/`, type `link`, tap result = 3 actions**, no modal, no leaving the compose surface. Block reordering is a **drag handle (⋮⋮) that appears on hover/long-press to the left of the block** — one continuous drag gesture, no separate "move mode."
- Obsidian core (and the popular SlashComplete/Slash Commander plugins) replicate the same `/`-trigger contextual menu for headings, lists, callouts, code blocks, internal links, and embeds — validating `/` as a convention users now expect from *any* block-based markdown editor, not just Notion.
- Roam Research / Workflowy: outliner-first, so "block reordering" is drag-the-bullet (same continuous-gesture pattern) or keyboard `Alt/Cmd+Shift+Up/Down` on desktop — i.e. the winning pattern is **one gesture or one chord, no mode switch**.
- Bear: a single **BI~U~ formatting-bar toggle** opens a dedicated formatting keyboard/row (iOS) — same "expandable formatting row" shape SteleKit's `MobileBlockToolbar` already uses. Bear also supports typed markdown shortcuts (`**bold**`) recognized live, and a documented set of custom "type it, get formatted" shortcuts requiring zero taps beyond typing the note itself.

**Takeaway for SteleKit**: the `#`/`[[` typed-trigger autocomplete SteleKit already has is the *same* mental model as the Notion/Obsidian `/`-menu — good, keep it. The gap is likely in **block reordering** (SteleKit currently exposes Move Up/Move Down icon buttons in the bottom toolbar row, a multi-tap "select then find button" flow) vs. the competitive **single continuous drag-handle** pattern, and in whether tag/link insertion requires opening the mobile toolbar at all when the user could type the trigger character directly (typed-trigger should always be favored over toolbar-button for the *fastest* path; toolbar button should exist only as the discoverable fallback for users who haven't learned the trigger).

Sources:
- [Slash commands – Obsidian Help](https://help.obsidian.md/plugins/slash-commands)
- [Using slash commands – Notion Help](https://www.notion.com/help/guides/using-slash-commands)
- [Links & backlinks – Notion Help Center](https://www.notion.com/help/create-links-and-backlinks)
- [SlashComplete – Obsidian Plugin](https://www.obsidianstats.com/plugins/slash-complete)
- [How to use Markdown in Bear](https://bear.app/faq/how-to-use-markdown-in-bear/)
- [Quick Tip: Bear's keyboard shortcuts](https://blog.bear.app/2017/11/quick-tip-check-out-bears-keyboard-shortcuts-for-mac-ipad-and-even-iphone/)

## 2. User mental models

**Typed triggers (`#`, `[[`)**: users coming from Notion/Obsidian/Roam/Logseq itself already expect `[[` to open a page-link autocomplete and `#` to open a tag autocomplete — this is now a cross-app convention, not a novel affordance. The expectation includes: menu appears **inline at the cursor** (not a separate modal sheet), filters live as you keep typing, `Enter`/tap-to-select closes it and leaves the cursor positioned to keep typing the sentence. Any deviation (e.g., opening a full-screen picker for `[[`) breaks the learned model.

**Toolbar button vs. keyboard shortcut vs. gesture** — these serve different user segments, not different intents:
- **Toolbar button** = the *discoverable* path. New/mobile users scan visible icons; it must be reachable with one tap and (per Nielsen's "flexibility and efficiency of use" heuristic) coexist with, not replace, faster paths.
- **Keyboard shortcut** = the *expert accelerator* path, valuable on Desktop/Web where a hardware keyboard is assumed. The heuristic literature is explicit that shortcuts should be surfaced *next to* the toolbar action they accelerate (e.g., a tooltip or menu label showing "Ctrl+B") so novices graduate to expert usage through repeated exposure rather than needing to discover it separately.
- **Gesture (swipe/long-press)** = fastest for touch but **the least discoverable of the three** — nothing on screen hints it exists. Only appropriate for actions that already have a fully-discoverable alternative (e.g., swipe-to-delete a block should always coexist with a visible delete affordance), never as the *only* path to a capability.

**"Formatting overflow" (secondary row behind a "more"/chevron button)**: the research is split but leans toward **known-good only when scoped correctly**. NN/g-aligned guidance says an overflow menu is fine when it holds the **2-4 least-frequently-used actions** and is clearly labeled/animated so its connection to the trigger is obvious; it becomes an anti-pattern when (a) frequently-used actions get buried in it "to save space," or (b) the trigger itself is an unlabeled icon with no visual weight. SteleKit's `MobileBlockToolbar.kt` already does this correctly at the code level — the toggle has `contentDescription = "Toggle formatting"` and reveals a labeled row — so the open UX question is empirical: *which* actions are in the primary vs. overflow row, not whether the pattern itself is sound.

Sources:
- [8 User Control UI Patterns Worth Using – UXPin](https://www.uxpin.com/studio/blog/8-user-control-ui-patterns-worth-using/)
- [Contextual Menus: Delivering Relevant Tools for Tasks – NN/g](https://www.nngroup.com/articles/contextual-menus/)
- [Flexibility and Efficiency of Use (Heuristic #7) – NN/g](https://www.nngroup.com/articles/flexibility-efficiency-heuristic/)
- [Mobile editor preview button and toolbar – Discourse Meta](https://meta.discourse.org/t/mobile-editor-preview-button-and-toolbar/113942)

## 3. Accessibility requirements (WCAG/platform-equivalent) + codebase audit

**Requirements applicable to a Compose Multiplatform toolbar:**
- **Touch target size**: WCAG 2.2 SC 2.5.8 (Level AA, the binding minimum) requires ≥24×24 CSS px unless targets have sufficient spacing; WCAG 2.5.5 (AAA) recommends ≥44×44. Platform guidance is stricter than the AA floor: Apple HIG recommends 44×44pt, Material Design recommends 48×48dp — Compose Material3's default `IconButton` touch target already satisfies this without extra work, **provided no custom `Modifier.size()` shrinks it below 48dp.**
- **Screen-reader labels**: every icon-only interactive element needs a non-null `contentDescription` (Compose's TalkBack/VoiceOver equivalent of `aria-label`); decorative icons inside an already-labeled parent (e.g., an `Icon` nested inside a `Modifier.semantics { contentDescription = ... }` button) may correctly pass `contentDescription = null` to avoid double-announcement.
- **Keyboard-only navigation (Desktop/Web)**: all interactive elements must be reachable via Tab and operable via Enter/Space without a mouse; focus order must follow visual/reading order.
- **Focus order**: matches logical reading order, especially important once a formatting overflow row expands/collapses (focus should not jump unpredictably).

**Codebase audit** (grep `contentDescription` across the two named files, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/`):
- `MobileBlockToolbar.kt` (299 lines, 17 `IconButton`s): **every icon-only button already has a contentDescription**, either directly on `Icon(...)` (e.g. `Icons.Default.ContentCopy, contentDescription = "Copy selected"`) or via `Modifier.semantics { contentDescription = "..." }` on the parent `IconButton` with the inner decorative `Icon` correctly set to `contentDescription = null` (e.g. lines 174/178, 189/191, 195/197, 201, 211/213, 219/221, 227/229). No gaps found. No custom `.size()` overrides were found shrinking touch targets below the Material3 default.
- `EditorToolbar.kt` (123 lines): contains **zero** `contentDescription` and **zero** `IconButton`s — expected, because this file is a pure wiring/host composable (state collection + callback plumbing) that delegates all actual UI, including every icon, to `MobileBlockToolbar`. Not a gap.
- `AnnotationToolbar.kt` (in scope per requirements — annotation editor now included) also fully labels its `IconButton`s: "Undo", "Redo", "Delete selected annotation", per-tool `label`, "Calibrate".
- `VoiceCaptureButton.kt` (voice-mode input, also in scope) labels its state-dependent icons: "Start recording", "Stop recording", "Note saved — may be incomplete. Tap to dismiss.", "Note saved. Tap to dismiss.", "Error — tap to dismiss", with decorative companion icons correctly `null`.
- `CommandPalette.kt`: **zero** `contentDescription` hits — this component appears to be text-list based (list items with visible text labels) rather than icon-only buttons, so this is likely not a gap either, but **Phase 3 planning should explicitly verify** whether any icon-only affordances exist inside it before assuming full coverage.

**Conclusion**: contrary to what the research question anticipated, this is *not* where the audit finds gaps — accessibility labeling is already disciplined across every editing surface checked. The gap backlog (agent synthesizing Phase 3) should **not** prioritize "add contentDescription" work; it should verify this holds for any *new* surfaces this project adds (e.g., a command-palette formatting entry, which per baseline doesn't exist yet) and confirm no regressions via existing test conventions (`KeyboardShortcutTest.kt` at `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/KeyboardShortcutTest.kt` covers Ctrl+K-style chords; an analogous semantic-tree assertion test would be needed to make "every icon button has a contentDescription" a regression-proof invariant rather than an audit finding that can silently rot).

Sources:
- [Understanding SC 2.5.8: Target Size (Minimum) – W3C WAI](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html)
- [Understanding SC 2.5.5: Target Size (Enhanced) – W3C WAI](https://www.w3.org/WAI/WCAG21/Understanding/target-size.html)
- [Accessible Target Sizes Cheatsheet – Smashing Magazine](https://www.smashingmagazine.com/2023/04/accessible-tap-target-sizes-rage-taps-clicks/)

## 4. Error states and edge cases

- **Formatting an empty/collapsed selection**: best practice (and what most block editors do) is to apply the formatting as a *toggle-on-type* marker at the cursor (e.g., turn on bold, type text, formatting continues until toggled off or cursor moves past the marked range) rather than silently no-op or throwing an error. If SteleKit's formatting actions currently require a non-empty selection, this is a candidate gap — most competitors (Bear via markdown shortcuts, Notion) let you toggle a format with an empty caret and have subsequently-typed text pick it up.
- **Undo/redo with stale toolbar state**: the toolbar's undo/redo buttons must reflect the *current* `BlockStateManager` history stack reactively (already the architecture per `StateFlow` collection in `EditorToolbar.kt`), but the edge case is **rapid external file changes** (per this repo's `GraphLoader.externalFileChanges` / `DiskConflict` flow) invalidating the in-memory undo stack mid-edit — the toolbar should disable Undo/Redo (not just leave them silently no-op-ing) whenever a disk conflict is pending resolution, since undoing past a disk-resolved state is undefined behavior.
- **Tag suggestion when offline/LLM unavailable**: the existing "Suggest tags" bottom sheet (LLM/local semantic suggestion) needs an explicit degraded state — spinner-that-never-resolves is a known anti-pattern; the button should either gray out with a tooltip/description explaining unavailability, or fall back to a lightweight local heuristic (e.g., frequency-based tag suggestions from existing graph tags) so the *typed* `#` autocomplete (which has no LLM dependency) remains the reliable fallback path regardless of LLM availability.
- **Autocomplete popup vs. on-screen keyboard collision**: on small phones, an inline autocomplete dropdown positioned below the cursor can be pushed off-screen or clipped by the IME. Established mobile pattern: anchor the popup **above** the cursor line when insufficient space exists below (flip positioning, same as Compose `ExposedDropdownMenu`/tooltip flip logic), and never let dismissing the keyboard (tap-outside) also silently dismiss the autocomplete before the user's tap registers as a selection — a known bug class in mobile autocomplete implementations (Algolia's autocomplete library has an open issue for exactly this).

Sources:
- [How to handle the on-screen keyboard without messing up your app usability](https://www.mobilespoon.net/2018/12/10-usability-rules-keyboard-mobile-app.html)
- [Prevent dropDown from hiding when hiding the virtual keyboard · Issue #180 · algolia/autocomplete](https://github.com/algolia/autocomplete/issues/180)

## 5. Jobs-to-be-done and the step-count vs. discoverability tradeoff

- **Functional job**: capture a thought before it's lost — every added tap between "user has an idea" and "idea is on the page in roughly the right shape" is a chance to lose the thought. This is the strongest argument for typed-trigger conventions (`#`, `[[`) and toggle-on-type formatting: they require **zero mode switch away from typing**.
- **Emotional job**: feel in control, not fighting the UI. This is Nielsen's "user control and freedom" plus "flexibility and efficiency of use" heuristics operationalized — a user should never feel the editor is making a choice for them (e.g., silently mangling formatting) or trapping them in a mode (selection mode, formatting-overflow-expanded) without an obvious, low-effort exit.
- **Social job**: produce shareable/exportable well-formatted notes — this matters most for surfaces like the annotation editor and voice-mode transcripts, where the *output* quality (clean markdown, correctly placed tags/links) matters more than the *input* speed, because the artifact is read by someone else (or the same user later, which the note-taking literature treats as equivalent to a social/future-self audience).

**Reprioritization guidance for this project's success metrics**: the requirements ask for "step-count benchmark AND heuristic usability review" per journey — the JTBD analysis says these should not be weighted equally across all journeys. **Functional-job-dominant journeys** (insert tag, insert link, toggle TODO, insert code block — anything done *while* composing a thought) should be optimized primarily for step-count/keystroke minimization, since capture-speed is the job. **Social-job-dominant journeys** (export, annotation labeling, anything producing a shareable artifact) should be optimized primarily for discoverability and correctness review, since a fast-but-wrong result actively fails the job. Block reordering and multi-select operations sit in between (functional but not time-critical) and are where the competitive research in §1 suggests the largest current gap (icon-button move vs. drag-handle).

Sources:
- [Logseq's block structure fixed my note-taking habit in one unexpected way](https://www.xda-developers.com/logseq-block-notes/)
- [Flexibility and Efficiency of Use (Heuristic #7) – NN/g](https://www.nngroup.com/articles/flexibility-efficiency-heuristic/)
- [10 Usability Heuristics for User Interface Design – NN/g](https://www.nngroup.com/articles/ten-usability-heuristics/)

## Summary of codebase references checked

| File | Finding |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt` | 17 IconButtons, all labeled; expandable formatting row + primary row + bottom row confirmed as described in baseline |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorToolbar.kt` | Pure wiring/host composable, no icons of its own — delegates to MobileBlockToolbar |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationToolbar.kt` | Fully labeled IconButtons (Undo/Redo/Delete/Calibrate/per-tool) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/VoiceCaptureButton.kt` | Fully labeled, including state-dependent labels for recording/saved/error states |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/CommandPalette.kt` | No contentDescription hits — likely text-list based; verify in Phase 3 if icon-only affordances are added |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/KeyboardShortcutTest.kt` | Existing test pattern for Ctrl+K-style chord assertions — model for any new shortcut-coverage tests |
