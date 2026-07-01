# Graph Sections — UX Design

**Feature:** Graph Sections (user-facing label: "Sections")
**Platform:** Kotlin Multiplatform Compose — Desktop JVM (primary), WASM (secondary)
**Date:** 2026-06-24

---

## Overview

Sections partition a single knowledge graph into named, independently-syncable subsets.
Users on a primary desktop have all sections active; users on a work machine or WASM
browser select only the sections they need. Links to pages in unsynced sections degrade
gracefully to tombstone placeholders rather than broken links.

Seven surfaces are designed below: Section Settings screen, Create/Edit Section dialog,
WASM first-open section selector, tombstone link visual treatment, "Sync section?" dialog,
sidebar section indicator, and error states.

---

## Surface 1 — Section Settings Screen (Desktop)

### Placement

Reached via graph settings sidebar → "Sections" entry (visible only when `.stele-sections`
exists in the graph root). Hidden from graphs that have no manifest (backward compat AC-5).

### ASCII Wireframe

```
┌──────────────────────────────────────────────────────────────────────┐
│  Graph Settings                                             [×] Close │
├───────────────┬──────────────────────────────────────────────────────┤
│  General      │  Sections                                            │
│  Sync         │                                                      │
│  ▶ Sections   │  ┌────────────────────────────────────────────────┐  │
│  Hotkeys      │  │  ● personal                         [Edit] [✕] │  │
│               │  │  Journals and private notes                     │  │
│               │  │  Paths: journals/  private/                     │  │
│               │  │  Pages: 312    ◉ Active ─────────────────────  │  │
│               │  └────────────────────────────────────────────────┘  │
│               │                                                      │
│               │  ┌────────────────────────────────────────────────┐  │
│               │  │  ● tech                             [Edit] [✕] │  │
│               │  │  Technology and engineering research            │  │
│               │  │  Paths: tech/  pages/Technology/               │  │
│               │  │  Pages: 847    ○ Inactive ───────────────────  │  │
│               │  └────────────────────────────────────────────────┘  │
│               │                                                      │
│               │  ┌────────────────────────────────────────────────┐  │
│               │  │  ● work                             [Edit] [✕] │  │
│               │  │  Work projects and meeting notes                │  │
│               │  │  Paths: work/  pages/Work/                     │  │
│               │  │  Pages: 203    ◉ Active ─────────────────────  │  │
│               │  └────────────────────────────────────────────────┘  │
│               │                                                      │
│               │  ┌────────────────────────────────────────────────┐  │
│               │  │  ○ default                          [Edit] [✕] │  │
│               │  │  All untagged pages                             │  │
│               │  │  Paths: (catch-all)                            │  │
│               │  │  Pages: 118    ◉ Active ─────────────────────  │  │
│               │  └────────────────────────────────────────────────┘  │
│               │                                                      │
│               │  [ + Create section ]                                │
│               │                                                      │
│               │  Note: Deactivating a section on a git repo will    │
│               │  update sparse-checkout on the next pull.           │
└───────────────┴──────────────────────────────────────────────────────┘
```

Color dot (●) uses the section's configured color. Empty circle (○) indicates the
`default` section (no assigned color beyond the theme default).

### Interaction Flow

| User action | System response |
|---|---|
| Navigate to Settings → Sections | Screen renders list from `SectionManifest`; page counts fetched from DB (`countPagesBySection`); active state read from `GraphInfo.sectionConfig` |
| Toggle section OFF (Active → Inactive) | Show inline spinner on the row; call `GraphManager.toggleSection(name, false)`; delete pages in that section from DB; update sparse-checkout paths (desktop git); toggle switches to OFF; page count grays out |
| Toggle section ON (Inactive → Active) | Show inline spinner; call `GraphManager.toggleSection(name, true)`; re-index section pages via `loadGraphProgressive`; toggle switches to ON; page count updates |
| Click Edit | Opens Create/Edit Section dialog pre-populated with section data |
| Click Delete (✕) | Opens Delete Section confirmation dialog |
| Click Create section | Opens Create/Edit Section dialog with empty fields |

### Error / Edge Cases

| Condition | Behavior |
|---|---|
| Toggle while git worktree has uncommitted changes | Toggle is blocked; inline error: "Commit or stash your changes before changing section selection." |
| Section has 0 pages | Page count shows "0 pages" (not hidden) |
| Only one section defined | Delete (✕) is disabled with tooltip: "A graph must have at least one section." |
| Re-index takes >2 s | Progress bar appears below the row being re-indexed: "Indexing… 245/500 pages" |
| Sparse-checkout fails (git not in PATH) | Warning banner: "Git not found — sparse-checkout skipped. Section filter applied in-app only." Section toggle still succeeds. |

---

## Surface 2 — Create/Edit Section Dialog

### ASCII Wireframe

```
┌──────────────────────────────────────────────────────┐
│  Create section                               [×]    │
├──────────────────────────────────────────────────────┤
│                                                      │
│  Name *                                              │
│  ┌────────────────────────────────────────────────┐  │
│  │ tech                                           │  │
│  └────────────────────────────────────────────────┘  │
│  Used in your manifest and as the section ID.        │
│                                                      │
│  Description                                         │
│  ┌────────────────────────────────────────────────┐  │
│  │ Technology and engineering research            │  │
│  └────────────────────────────────────────────────┘  │
│                                                      │
│  Color                                               │
│  ┌──────┐ ● #3498DB  [Pick color]                   │
│  │      │                                            │
│  └──────┘                                            │
│                                                      │
│  Path patterns                                       │
│  Pages in these directories belong to this section.  │
│                                                      │
│  ┌───────────────────────────────────────────┐ [−]  │
│  │ tech/                                     │      │
│  └───────────────────────────────────────────┘      │
│  ┌───────────────────────────────────────────┐ [−]  │
│  │ pages/Technology/                         │      │
│  └───────────────────────────────────────────┘      │
│  [ + Add path ]                                      │
│                                                      │
│  ⚠ "tech/" overlaps with section "work" path        │
│    "tech/Work/". Pages there will go to first        │
│    matching section.                                 │
│                                                      │
├──────────────────────────────────────────────────────┤
│                 [Cancel]         [Save section]      │
└──────────────────────────────────────────────────────┘
```

### Interaction Flow

| User action | System response |
|---|---|
| Type in Name field | Validate: non-empty, no spaces → shown as slug preview below; check for name collision with existing sections (case-insensitive) |
| Click Pick color | Native/Compose color picker opens; selected hex populates the preview swatch and text field |
| Type hex in color field directly | Swatch updates live; invalid hex shows red outline |
| Click Add path | Appends a new empty text field; focus moves to it |
| Type in path field | Trailing `/` is automatically appended if missing on blur; path validated as a relative directory (no `..`, no leading `/`) |
| Click [−] on a path | Removes that path field immediately |
| Click Save section (create) | Writes new `GraphSection` to `SectionManifest`; triggers `writeSectionManifest`; closes dialog; Section Settings list refreshes |
| Click Save section (edit) | Updates existing section in manifest; if paths changed, re-assigns pages to correct sections via `SectionFilter`; closes dialog |
| Click Cancel | Discards all changes; closes dialog |

### Error / Edge Cases

| Condition | Behavior |
|---|---|
| Name already exists (create mode) | "Name 'tech' is already taken. Choose a different name." Save disabled. |
| Name field empty on save | "Name is required." Save disabled. |
| Path is blank on save | Empty path fields are removed automatically; user is warned if all paths cleared: "No paths — this section will match no pages until you add one." |
| Path overlap with another section | Non-blocking warning shown inline (see wireframe); user may save anyway. |
| Color field invalid hex | "Invalid color — use format #RRGGBB." Save disabled. |
| Editing the `default` section name | Name field is disabled with tooltip: "The default section name cannot be changed." |

---

## Surface 3 — WASM First-Open Section Selector

### Context

Shown on first WASM load when no OPFS cache exists (or cache SHA mismatches remote HEAD).
Displayed after the app fetches the `.stele-sections` manifest from the git remote but
before any page content is downloaded.

### ASCII Wireframe

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  SteleKit                              myname/my-graph @ main   │
│                                                                 │
│  ─────────────────────────────────────────────────────────────  │
│                                                                 │
│  Choose sections to sync                                        │
│  Select which parts of your graph to download to this browser.  │
│  You can change this later in Settings.                         │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ ☑  ● personal                                            │  │
│  │    Journals and private notes                             │  │
│  │    ~312 pages · ~18 MB                                   │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ ☐  ● tech                                                │  │
│  │    Technology and engineering research                    │  │
│  │    ~847 pages · ~54 MB                                   │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ ☐  ● work                                                │  │
│  │    Work projects and meeting notes                        │  │
│  │    ~203 pages · ~11 MB                                   │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ ☑  ○ default                                             │  │
│  │    All untagged pages (always included)                   │  │
│  │    ~118 pages · ~6 MB                                    │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  Selected: 2 sections · ~430 pages · ~24 MB estimated          │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  [      Sync selected sections →      ]                 │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Note: Pages in unselected sections will show as               │
│  unavailable links if referenced from your synced content.     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Loading / Progress State (after Sync is clicked)

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  SteleKit                              myname/my-graph @ main   │
│                                                                 │
│  Syncing sections…                                              │
│                                                                 │
│  ● personal                                                     │
│  ████████████████████░░░░  312 / 312 pages    ✓ Done           │
│                                                                 │
│  ○ default                                                      │
│  ████████░░░░░░░░░░░░░░░░   82 / 118 pages    Syncing…         │
│                                                                 │
│  This may take a moment on first load. Your sections will be    │
│  cached locally for offline use after this.                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Interaction Flow

| User action | System response |
|---|---|
| Screen first appears | Manifest fetched; sections listed with estimated counts and sizes; `default` is pre-checked and its checkbox is disabled (always required) |
| Check/uncheck a section | Running total (sections, pages, MB) updates at bottom |
| Click Sync selected sections | Button becomes disabled; progress view appears; `SectionSyncService.syncSection` called per selected section; sections download in parallel (per the implementation plan) |
| All sections complete | Brief "Done!" confirmation state (500 ms); then transitions to the main graph UI |

### Error / Edge Cases

| Condition | Behavior |
|---|---|
| Zero sections selected (only `default` possible but forced) | Sync button disabled with label "Select at least one section" |
| Network error during manifest fetch | Full-screen error: "Could not reach your graph remote. Check your connection." Retry button. If OPFS cache present, offer "Open cached version" button. |
| Manifest has no sections (bare graph) | Selector is skipped; proceed directly to graph load with `default` section |
| Storage quota warning (>80% full) | Yellow banner: "Your browser storage is nearly full (~X MB remaining). Consider selecting fewer sections." Sync still allowed. |
| Tab crash mid-sync (detected on reload) | Sync marker absent → resume sync automatically; show "Resuming interrupted sync…" inline |

---

## Surface 4 — Tombstone Link Visual Treatment

### Context

Rendered inline in the block/note editor wherever a `[[Page Name]]` wikilink resolves
to a page in an inactive section (determined by `SectionResolver`).

### Visual Specification

```
Normal link:         [[TypeSystems]]
                     ──────────────   (solid underline, theme link color)

Tombstone link:      [[My Diary]]
                     - - - - - - -   (dashed underline, section color tint)
```

- Dashed underline: 1 dp, dash pattern 4dp on / 2dp off
- Text color: section color at 80% opacity (so it is readable but visually distinct)
- Background: none (no chip or badge — inline flow only)
- Cursor: pointer (hand cursor on hover)

#### Tooltip on Hover

```
┌────────────────────────────────────────────────────────┐
│  ● personal                                            │
│  "My Diary" is in section 'personal', which is not    │
│  synced to this device.                               │
│                                                        │
│  Click to add 'personal' to this device.              │
└────────────────────────────────────────────────────────┘
```

Tooltip appears after 400 ms hover. It shows the section color dot, section name, a
plain-language explanation, and a CTA.

### Interaction Flow

| User action | System response |
|---|---|
| Hover over tombstone link | Tooltip appears after 400 ms |
| Click tombstone link | "Sync section?" dialog opens (Surface 5) |
| Mouse leaves link | Tooltip dismisses immediately |

### Error / Edge Cases

| Condition | Behavior |
|---|---|
| Section name not found in current manifest (manifest edited externally) | Fallback tooltip: "This page is not available in your synced sections." No section color dot. Click still opens "Sync section?" dialog but with a warning that the section is unknown. |
| Multiple links to the same inactive section on one page | Each link shows the tombstone independently; no deduplication in the editor view |
| Page re-synced while editor is open | Tombstone is replaced with a normal link on next recomposition (state update from `PageNameIndex`) |

---

## Surface 5 — "Sync section?" Dialog

### Context

Triggered by: (a) clicking a tombstone link, or (b) "Activate" button from Section
Settings when the section is inactive. Behavior differs slightly between desktop and
WASM (sparse-checkout vs OPFS fetch).

### ASCII Wireframe — Desktop Variant

```
┌──────────────────────────────────────────────────────┐
│  Add section to this device?                  [×]    │
├──────────────────────────────────────────────────────┤
│                                                      │
│  ● personal                                          │
│  Journals and private notes                          │
│  ~312 pages                                          │
│                                                      │
│  What will happen:                                   │
│  • Your active section list will be updated.         │
│  • Git sparse-checkout will expand to include        │
│    journals/ and private/.                           │
│  • Files will be available on the next git pull.     │
│  • Pages will be indexed and links resolved.         │
│                                                      │
│  You can deactivate sections anytime in Settings.    │
│                                                      │
├──────────────────────────────────────────────────────┤
│              [Cancel]     [Add 'personal']           │
└──────────────────────────────────────────────────────┘
```

### ASCII Wireframe — WASM Variant

```
┌──────────────────────────────────────────────────────┐
│  Download section to this browser?            [×]    │
├──────────────────────────────────────────────────────┤
│                                                      │
│  ● personal                                          │
│  Journals and private notes                          │
│  ~312 pages · ~18 MB                                 │
│                                                      │
│  What will happen:                                   │
│  • Pages will be downloaded to your browser's        │
│    local storage (OPFS) for offline use.             │
│  • This will use approximately 18 MB of storage.     │
│  • Links to 'personal' pages will become active.     │
│                                                      │
│  You can remove cached sections in Settings.         │
│                                                      │
├──────────────────────────────────────────────────────┤
│            [Cancel]    [Download 'personal']         │
└──────────────────────────────────────────────────────┘
```

#### Progress State (WASM after confirm)

```
┌──────────────────────────────────────────────────────┐
│  Downloading 'personal'…                             │
├──────────────────────────────────────────────────────┤
│                                                      │
│  ████████████░░░░░░░░░░░░  187 / 312 pages           │
│                                                      │
│  Caching pages locally — this may take a moment.     │
│                                                      │
│                                   [Cancel download]  │
└──────────────────────────────────────────────────────┘
```

### Interaction Flow

| User action | System response |
|---|---|
| Dialog opens | Section name, description, page count, and size (WASM only) shown; platform-appropriate explanation shown |
| Click Add / Download | Dialog transitions to progress state; `GraphManager.toggleSection(name, true)` called; on desktop, sparse-checkout updated; on WASM, `SectionSyncService.syncSection` called with progress callback |
| Operation completes | Dialog dismisses; tombstone link that triggered this (if any) re-renders as a normal link |
| Click Cancel (before confirm) | Dialog dismisses; no changes |
| Click Cancel download (during WASM sync) | Sync aborted; incomplete OPFS data cleaned up; sync marker not written; dialog dismisses |

### Error / Edge Cases

| Condition | Behavior |
|---|---|
| Desktop: uncommitted changes detected | "You have uncommitted changes. Commit or stash before adding a section." Confirm button disabled. |
| WASM: storage quota exceeded | Progress state transitions to error: "Not enough storage. Free up space and try again." Retry button. |
| WASM: network error mid-download | Error state in dialog: "Download interrupted. Check your connection and retry." Retry button. Partial OPFS data is cleaned up on retry. |
| Section name in manifest but 0 matching pages | Dialog shows "0 pages" and note: "No pages currently match the configured paths for this section." Confirm is still allowed. |

---

## Surface 6 — Sidebar Section Indicator

### Context

The page list sidebar shows a section color dot next to each page title when more than
one section is active. A filter dropdown allows narrowing the list to one section.

### ASCII Wireframe

```
┌─────────────────────────────────────────┐
│  Pages                      [All ▼]    │
├─────────────────────────────────────────┤
│  🔍 Search pages…                       │
├─────────────────────────────────────────┤
│  ● TypeSystems                          │
│  ● Rust Lifetimes                       │
│  ● WebAssembly Notes                    │
│  ──────────────────────────────────     │
│  ● My Diary                             │
│  ● 2026-01-15                           │
│  ──────────────────────────────────     │
│  ○ Inbox                                │
│  ○ Quick Notes                          │
└─────────────────────────────────────────┘

                    ┌──────────────────┐
  Filter dropdown:  │ All sections     │
                    │ ● tech           │
                    │ ● personal       │
                    │ ○ default        │
                    └──────────────────┘
```

Color dots: 6 dp circles. Tech pages blue (●), personal pages red/orange (●), default
section pages empty circle (○). The dot color comes from `page.section.color` looked up
against the in-memory `SectionManifest`.

Dots are hidden (no dot rendered) when only one section is active or when all sections
share the same effective state (primary desktop, full-merge view).

### Section Filter Behavior

| Filter state | Page list content |
|---|---|
| "All sections" | Unfiltered list — all pages from all active sections |
| One section selected (e.g. "tech") | Only pages with `section_name = 'tech'` shown; page count in header updates |
| Section deactivated while filter active | Filter resets to "All sections" automatically |

### Interaction Flow

| User action | System response |
|---|---|
| Click "All ▼" dropdown | Dropdown opens with one entry per active section + "All sections" at top |
| Select a section | Page list immediately switches to `getPagesBySection(name)` query result |
| Hover over a color dot | Tooltip: "Section: tech" (section name) |
| Select a section in dropdown → click a page | Page opens normally; filter persists until changed |

### Error / Edge Cases

| Condition | Behavior |
|---|---|
| Single section active (primary device, single section) | Dots not shown; filter dropdown not shown |
| Section has no pages matching the filter (empty result) | Page list shows empty state: "No pages in 'tech' yet." |
| Page section field missing (null, migration in progress) | Page shows with gray dot (●); tooltip: "Section unknown" |

---

## Surface 7 — Error States

### 7.1 Sparse-Checkout Errors (Desktop)

#### 7.1.1 Git Not Installed

```
┌───────────────────────────────────────────────────────────────────┐
│  ⚠  Git not found                                           [×]  │
│                                                                   │
│  Sparse-checkout requires git to be installed and available       │
│  in your PATH.                                                    │
│                                                                   │
│  Section filtering is still applied within the app, but          │
│  your git worktree will continue to include all sections'         │
│  files on disk until git is available.                            │
│                                                                   │
│  Install git and restart SteleKit to enable sparse-checkout.      │
│                                                                   │
│                   [Dismiss]   [How to install git]               │
└───────────────────────────────────────────────────────────────────┘
```

- "How to install git" opens the git-scm.com download page in the default browser.
- Section toggle still completes (in-memory filtering succeeds).

#### 7.1.2 Network Error During Git Pull (after sparse-checkout update)

Not shown as a dialog — this is a git pull failure, which is outside SteleKit's sync
initiation. The app surfaces a note in Section Settings: "Sparse-checkout updated. Run
`git pull` to materialize the newly added sections from your remote."

#### 7.1.3 Merge Conflict Detected

```
┌───────────────────────────────────────────────────────────────────┐
│  ✕  Merge conflict — section change blocked                [×]   │
│                                                                   │
│  Your git worktree has unresolved merge conflicts.               │
│  Section selection cannot be changed until conflicts are          │
│  resolved.                                                        │
│                                                                   │
│  Resolve conflicts in your terminal or editor, then return        │
│  here to change your section selection.                           │
│                                                                   │
│                                         [Dismiss]                │
└───────────────────────────────────────────────────────────────────┘
```

---

### 7.2 OPFS Sync Errors (WASM)

#### 7.2.1 Rate Limit (HTTP 429 from git host)

```
┌─────────────────────────────────────────────────────────┐
│  ✕  Download rate limited                         [×]  │
│                                                         │
│  GitHub has rate-limited this request.                  │
│  Wait a few minutes and try again.                      │
│                                                         │
│  If this keeps happening, consider syncing fewer        │
│  sections at once.                                      │
│                                                         │
│               [Try again in 60 s]   [Dismiss]          │
└─────────────────────────────────────────────────────────┘
```

"Try again in 60 s" shows a countdown and auto-retries when it reaches 0.

#### 7.2.2 Auth Error (HTTP 401 / 403)

```
┌─────────────────────────────────────────────────────────┐
│  ✕  Not authorized                                [×]  │
│                                                         │
│  SteleKit could not access your repository.             │
│  Your token may have expired or lacks the required      │
│  permissions (repo: read).                              │
│                                                         │
│               [Update token]          [Dismiss]        │
└─────────────────────────────────────────────────────────┘
```

"Update token" navigates to the Git Sync settings screen where the token can be updated.

#### 7.2.3 Storage Quota Exceeded

```
┌─────────────────────────────────────────────────────────┐
│  ✕  Browser storage full                          [×]  │
│                                                         │
│  Your browser has run out of available storage.         │
│  (~0 MB remaining)                                      │
│                                                         │
│  Options:                                               │
│  • Select fewer sections to download                   │
│  • Clear cached sections in Settings → Sections →      │
│    Manage cache                                         │
│  • Free up storage in your browser settings            │
│                                                         │
│     [Manage cache]           [Select fewer sections]   │
└─────────────────────────────────────────────────────────┘
```

#### 7.2.4 OPFS Data Lost / Evicted (iOS Safari)

```
┌─────────────────────────────────────────────────────────┐
│  ✕  Local cache cleared                           [×]  │
│                                                         │
│  Your browser cleared SteleKit's local storage.         │
│  This can happen when device storage is low.            │
│                                                         │
│  Your sections need to be re-downloaded.                │
│                                                         │
│             [Re-download sections →]                   │
└─────────────────────────────────────────────────────────┘
```

"Re-download sections" re-runs the section selector flow (Surface 3) with previously
selected sections pre-checked.

---

### 7.3 Manifest Parse Error

```
┌──────────────────────────────────────────────────────────────────┐
│  ⚠  Could not read section manifest                        [×]  │
│                                                                  │
│  The file .stele-sections in your graph root could not be        │
│  parsed as valid TOML.                                           │
│                                                                  │
│  Error: unexpected character 'X' at line 12, column 4           │
│                                                                  │
│  SteleKit will load the graph as a single default section        │
│  until the manifest is fixed.                                    │
│                                                                  │
│  [Open .stele-sections]           [Continue with default]       │
└──────────────────────────────────────────────────────────────────┘
```

"Open .stele-sections" launches the file in the system default editor. The graph loads
with `SectionManifest.DEFAULT` (backward-compat fallback). Error is shown once per session
and not shown again on subsequent page navigations.

---

## UX Acceptance Criteria

### Section Settings Screen

| ID | Criterion |
|---|---|
| UX-AC-01 | A human can open Section Settings from the graph settings sidebar and see all sections listed with name, description, paths, page count, color, and active/inactive state. |
| UX-AC-02 | Toggling a section inactive removes its color dot from page list sidebar entries within 3 seconds. |
| UX-AC-03 | Toggling a section active causes its pages to appear in the page list within 5 seconds on a graph of 1000 pages. |
| UX-AC-04 | When git is not installed, toggling a section shows the "Git not found" warning but still completes the toggle. |
| UX-AC-05 | The "Sections" entry in graph settings is not visible for a graph without a `.stele-sections` file. |
| UX-AC-06 | A section with uncommitted git changes cannot be toggled; an inline error message explains why. |

### Create/Edit Section Dialog

| ID | Criterion |
|---|---|
| UX-AC-07 | A human can create a new section with a name, description, color, and two path patterns, and it appears in the Section Settings list after saving. |
| UX-AC-08 | Attempting to save a section with a duplicate name shows an error message and prevents saving. |
| UX-AC-09 | Clicking "+ Add path" adds a new empty field; clicking [−] next to a path removes it. |
| UX-AC-10 | The color picker preview swatch updates immediately when a color is selected or a valid hex is typed. |
| UX-AC-11 | A path overlap warning is shown when two sections share a common prefix, but saving is still allowed. |
| UX-AC-12 | Editing an existing section pre-populates all fields with the current values. |

### WASM First-Open Section Selector

| ID | Criterion |
|---|---|
| UX-AC-13 | On first WASM load, a human sees a list of available sections before any page content is downloaded. |
| UX-AC-14 | Each section in the list shows its name, description, estimated page count, and estimated size. |
| UX-AC-15 | The `default` section is pre-checked and its checkbox is disabled (cannot be deselected). |
| UX-AC-16 | A running total of selected sections, pages, and estimated MB updates as the user checks/unchecks sections. |
| UX-AC-17 | After clicking "Sync selected sections", a per-section progress bar appears showing N / total pages. |
| UX-AC-18 | A network error during manifest fetch shows a "could not reach remote" screen with a Retry button. |
| UX-AC-19 | When OPFS cache is present but stale, the selector shows again with previously selected sections pre-checked. |

### Tombstone Link Visual Treatment

| ID | Criterion |
|---|---|
| UX-AC-20 | A link to a page in an inactive section renders with a dashed underline in the section's color, not a solid underline. |
| UX-AC-21 | Hovering over a tombstone link for 400 ms shows a tooltip naming the section and explaining that the page is not synced. |
| UX-AC-22 | A tombstone link is visually distinguishable from a broken link (unknown page) — broken links show no dashed underline and no color tint. |
| UX-AC-23 | After a section is activated, tombstone links on the current page resolve to normal links within one recomposition cycle. |

### "Sync section?" Dialog

| ID | Criterion |
|---|---|
| UX-AC-24 | Clicking a tombstone link opens the "Sync section?" dialog naming the correct section. |
| UX-AC-25 | The dialog text differs between Desktop ("sparse-checkout") and WASM ("download to browser") variants. |
| UX-AC-26 | On WASM, the dialog shows the estimated size of the section being downloaded. |
| UX-AC-27 | Clicking Cancel closes the dialog with no changes to the active section set. |
| UX-AC-28 | On WASM, a download progress bar appears after confirming, and the dialog can be cancelled mid-download. |
| UX-AC-29 | After confirming, the dialog dismisses and the page re-renders with the newly active section's links resolved. |

### Sidebar Section Indicator

| ID | Criterion |
|---|---|
| UX-AC-30 | When multiple sections are active, each page in the sidebar list shows a color dot matching its section color. |
| UX-AC-31 | When only one section is active (or the graph has no `.stele-sections`), no color dots are shown. |
| UX-AC-32 | Hovering over a color dot shows a tooltip with the section name. |
| UX-AC-33 | The section filter dropdown contains one entry per active section plus "All sections" at the top. |
| UX-AC-34 | Selecting a section in the filter immediately updates the page list to show only that section's pages. |
| UX-AC-35 | Selecting "All sections" in the filter restores the full unfiltered page list. |

### Error States

| ID | Criterion |
|---|---|
| UX-AC-36 | When git is not found, the "Git not found" warning appears once and can be dismissed; it does not block section use. |
| UX-AC-37 | When a git merge conflict is detected, the section toggle is blocked and the reason is clearly stated. |
| UX-AC-38 | When OPFS download hits a 429 rate-limit, the dialog shows a countdown and auto-retries. |
| UX-AC-39 | When OPFS download hits a 401/403 auth error, the dialog offers a direct path to update the git token. |
| UX-AC-40 | When storage quota is exceeded, the error dialog offers links to manage the cache or select fewer sections. |
| UX-AC-41 | When OPFS data has been evicted, the "Local cache cleared" screen offers to re-download with previous selections pre-checked. |
| UX-AC-42 | When `.stele-sections` cannot be parsed, the manifest error dialog shows the parse error line/column and loads the graph with the default section. |
| UX-AC-43 | The manifest parse error is shown once per session, not on every page navigation. |

---

## Summary

| Surface | Count |
|---|---|
| Section Settings screen | 1 |
| Create/Edit Section dialog | 1 |
| WASM first-open section selector | 1 |
| Tombstone link visual treatment | 1 |
| "Sync section?" dialog | 1 |
| Sidebar section indicator | 1 |
| Error states (7 distinct error screens) | 1 (grouped) |
| **Total surfaces** | **7** |

| Metric | Count |
|---|---|
| UX acceptance criteria | **43** |
