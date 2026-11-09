# ADR-006: Navigation IA for Gallery and Assets: merge into one "Library" destination

**Status**: DECIDED / Accepted by the owner (Tyler), 2026-11-08 | **Date**: 2026-11-08 (decided); proposed earlier the same day as an owner decision gate

## Context
VERIFIED (ux.md section 1, architecture.md section 7): Gallery = rows of `ImageAnnotation` (annotated images only); Assets = index of every file in the graph assets folder. Annotating an asset makes it appear in both. The sidebar is one flat list under a single "Navigation" header with no descriptions (`Sidebar.kt:340-341`); on phones both sit behind "More" -> drawer. Neither name states its job. Gallery is the annotated-only subset of Assets, so the two destinations overlap by construction.

## Options (history kept; two are now rejected)
- **A. Keep both; add sections and descriptions only.** **Rejected (2026-11-08)**: the overlap that confuses users stays; two mental models remain.
- **B. Merge into one "Library" with filters.** One mental model; merges two ViewModels and data sources, touches deep links, search routes, `DemoGraphCoverageTest`, goldens; sort orders conflict; largest regression surface. **Chosen (2026-11-08).** Earlier text listed these costs as reasons to avoid B; the owner accepted them, and the plan mitigates each (route aliases, parity table, query-level join, retire-after-parity).
- **C. Rename and re-scope by job, inside A's grouped nav** (earlier ux.md recommendation; names "Measured Photos" / "Files"; Annotated badge and chip on Files; "Open in Photos"). **Rejected (2026-11-08)**: it keeps two destinations and a cross-link to explain the overlap that a single destination removes.

## Decision
**Merge Assets and Gallery into ONE destination, working title "Library"**, replacing `Screen.Gallery` and `Screen.AssetBrowser` as nav destinations.
- **Filters** (at least): Annotated, media type (Images, PDFs, Audio, Video, Documents, Other), Orphaned, tag, search. **Sort**: Recently added (default), Recently annotated, Most measurements, Name A-Z.
- **Route compatibility (decided in the plan repair)**: `Screen.Library(initialSearch, initialFilter)` is the one destination. `Screen.Gallery` and `Screen.AssetBrowser(q)` stay for one release as redirect aliases (`NON_NAV_REDIRECT`) rewritten in `navigateTo` to `Library(initialFilter = Annotated)` and `Library(initialSearch = q)`; persisted/restored/deep-linked old values still resolve (plan D.8c, D.11a). `Screen.AssetDetail` is unchanged.
- **Nav**: one row "Library" ("Every photo and file on your pages") as the **first row of the existing Graph section** (Library, Graph Stats, Git Setup); sections are Daily, Connections, Graph, Developer (ADR-007; amended in Repair 4, replacing the one-item "Media" section). Title is a working title; the exact strings are I18n keys (D.2b), a rename is one edit. The sidebar row formerly labelled "Library Stats" (`Sidebar.kt:346`, `Screen.LibraryStats`) is labelled "Graph Stats" so the name Library is unique (`NavLabelUniquenessTest`, D.2a).
- **Tags**: the tag filter uses annotation tags; assets carry no tags in v1; tags are not otherwise unified.
- **"Import from Gallery" stub**: deleted (it never displayed: `onImportPhoto = null`); Library has an Add menu (Capture photo, Attach file, Choose photo to annotate on Android).
- **Existing users** see a one-time notice "Gallery and Assets are now Library" (plan D.9c); new users get a first-run tip.
- **Reads**: paged (<= 100) with the `annotated` join at query level, bounded tag projection, no unbounded getAll (ADR-008).

## Plan consequence
Epic D is reworked as stories D.1-D.11 (single Library screen/ViewModel, bounded reads, parity table, redirects, retire old screens last). The cross-link tasks of the earlier option C (badge via chunked lookup, "Open in Photos") are replaced by the in-query `annotated` state and the detail screen's Annotate / Open annotation actions. Implementation is parameterised so a rename is one edit: labels and descriptions live in I18n keys read through `NavDestinationSpec`; tests assert via the same constants, never literals.

## Consequences
- The largest regression surface in Epic D is the old screens' behaviour: the D.6 parity table lists all 14 behaviours with a test each, and the old screens are deleted only in D.11b after it is green.
- Success Metric 4 gains an outcome measure (first-open task test) and a baseline description (requirements.md Amendment 3).
- Superseded: the earlier "recommendation C", "Plan effect" notes that tasks D.6a-D.6b are skipped under option A, and the owner decision gate (W2).

## Defaults - owner may override (Repair 4, triad iteration 2, 2026-11-08)

Chosen by the agent to unblock testable specs; each can be overridden without reopening the merge decision. Full text with rationale: `design/ux.md` section "Defaults - owner may override".

1. **Section name (ED-16)**: Library lives in the Graph section, not in a one-item "Media"/"Library" section. Fallback if 2 or more of 5 cold-user sessions miss it: rename the header to "Files and graph" (one I18n edit).
2. **Vocabulary (ED-2)**: "Annotated" is the state word (chip, badge, notice, tip: "photos you've annotated"); "measurements" is the count noun only; "unfinished points"; Clear points / Cancel move / Cancel export / Finish / Deselect / Close. To be validated in the cold-user sessions.
3. **Filter model (LD-1)**: Type chips multi-select, OR inside the group; Annotated, Orphaned and tags are separate AND conditions; non-image Type chips and Annotated/tag chips dim each other with a reason (no silent empty result); Annotated with Orphaned is allowed (LD-16).
4. **Tap rule kept (decision 7)** but part of the cold-user sessions (ED-17): annotated image card opens the editor, others open detail.
5. **Entry points (LD-8)**: "Show annotated photos" and the Gallery alias clear other filters; the AssetBrowser alias and global-search "Show all" set search only; restored contradictions are normalized.
6. **Upgrade reach (ED-16)**: New badge on the Library row (sidebar, More sheet, More button) until the notice is dismissed or Library opens; Settings > About > What's new keeps the text.
7. **Orphan Annotate (ED-8)**: disabled with a visible reason and [Open pages] (supersedes the snackbar wording of the same day; the owner may restore it).
8. **Cold-user evidence (ED-17)**: 3 to 5 sessions, pass thresholds in ux.md; owner/device step W11.
