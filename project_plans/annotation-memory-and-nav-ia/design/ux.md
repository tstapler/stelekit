# UX Design: annotation-memory-and-nav-ia

Inputs: `requirements.md` (incl. Amendments 2 and 3), `research/ux.md`, `implementation/plan.md` Epics A/C/D, ADR-006/007/008.
Labels: VERIFIED = taken from a cited artifact; PROPOSED = copy proposed by this design (the owner may reword it); DECIDED = settled by the owner on 2026-11-08. **ADR-006 is DECIDED: Assets and Gallery are merged into one destination, working title "Library"**, and the non-drag placement/move mode is included (OD-1..OD-4). Every string lives in an I18n key (plan D.2b), so a rename is one edit. Surface IDs S1-S16 and UX IDs UX-01..UX-41 are stable; new surfaces (S17, S18) and criteria (UX-42..UX-55) were appended earlier, and Repair 4 (triad iteration 2) appends S19, S20 and UX-56..UX-72; Repair 5 (triad iteration 3, final) fixes the search contradiction (LD-3/LD-7 now one definition), removes the Annotated/Orphaned dimming rule, and appends LD-11..LD-20, ED-18..ED-27 and UX-73..UX-82. Decisions the owner has not yet made are written as numbered **Defaults - owner may override** (section "Defaults - owner may override" at the end of this file: LD-1..LD-20 for Library, ED-1..ED-27 for the editor and cross-cutting behaviour); the plan and validation.md cite them by number, so an override is one edit here plus the named task.

**Vocabulary (ED-2, applies to every string in this file)**: a photo with at least one measurement is **Annotated** (chip, badge, filter, upgrade notice); "measurement(s)" is only the count noun ("3 measurements"); points placed but not yet forming a measurement are **unfinished points**; the buttons are **Clear points**, **Cancel move**, **Cancel export**, **Finish** (completes a multi-point shape), **Deselect** (clears a selection) and **Close** (leaves the editor). The words "Photos", "Files", "Gallery/Files", "measured photos", "unplaced" and a bare "Cancel"/"Done" no longer appear in normative text; "Gallery" and "Assets" survive only in the one-time upgrade notice and the legacy-alias descriptions.

The earlier placeholders `{PHOTOS}` / `{FILES}` no longer exist: both are the single "Library" destination.

## 0. Surface inventory

| # | Surface | Type | Plan story |
|---|---|---|---|
| S1 | Desktop/tablet sidebar, grouped | Interactive | D.2, D.3 |
| S2 | Phone bottom bar + "More" sheet | Interactive | D.4 |
| S3 | Command palette nav commands | Interactive | D.5 |
| S4 | Decision record: Assets and Gallery merged into Library (DECIDED) | Non-runtime | D.1 |
| S5 | Library screen: grid, empty, loading, error, missing file, permission | Interactive | D.6a-D.6c, D.7 |
| S6 | Library filters (Annotated, type, Orphaned, tag, search, sort; contradiction rules; compact sheet), detail, orphan Annotate | Interactive | D.6a, D.6d, D.6e, D.6g, D.7a |
| S7 | Library first-run tip and upgrade notice | Interactive | D.9a, D.9c |
| S8 | Editor: low-resource degradation (chip L1, L2) | Interactive | A.2c, A.5c, A.0c |
| S9 | Editor: post-kill launch banner and Logs replay | Interactive + log | A.0b |
| S10 | Editor: unsaved-changes dialog | Interactive | C.6, C.6b |
| S11 | Editor: loupe geometry and edge behaviour | Interactive | C.2, C.9 |
| S12 | Editor: "Place at crosshair" non-drag mode | Interactive | C.8c |
| S13 | Editor: select/move handles | Interactive | C.8, C.8e, features M3/M4 |
| S14 | Editor: persistence/legacy/feedback messages (retry banner, "Recalibrate", "Points too close", orphan Annotate) | Interactive | C.1c, C.5b, C.9c |
| S15 | Editor: export (Drive) error/offline states | Interactive | C.7, C.7c |
| S16 | Logs exit-reason line | Non-interactive (condensed) | A.2a, A.0b |
| S17 | Editor open / main-image loading state; tap-vs-drag; cancel affordance | Interactive | C.9f, C.9g |
| S18 | Editor accessibility: focus order, targets, timeouts, reduced motion | Test plan | C.9h, C.9i |
| S19 | Settings > Annotation, editor overflow menu, Library Add menu and per-asset overflow flows | Interactive | C.9l, D.6f |
| S20 | Configuration change: fold, unfold, rotate (editor and Library) | Interactive + test plan | C.9k, D.6e |

---

## S1. Desktop/tablet sidebar, grouped

### Wireframe (expanded sidebar)
```
+--------------------------------+
| Navigation                     |  <- existing header (heading)
|                                |
| DAILY                          |  <- section header, heading()
|  [icon] Journals               |
|         Today's notes and past days
|  [icon] All pages              |
|         Browse and search every page
|  [icon] Flashcards             |
|         Review cards from your notes
| ------------------------------ |  <- full-width divider between groups
| CONNECTIONS                    |
|  [icon] Unlinked References    |
|         Mentions that aren't linked yet
|  [icon] Notifications          |
|         Suggestions and sync alerts
| ------------------------------ |
| GRAPH                          |
|  [icon] Library                |
|         Every photo and file on your pages
|  [icon] Graph Stats  (Size and health of this graph)
|  [icon] Git Setup      (Back up and sync with Git)
| ------------------------------ |
| Favorites ...                  |
| ------------------------------ |
| DEVELOPER   (only while on Logs or Performance; appended LAST so it never shifts rows above it)
|  [icon] Logs / Performance
+--------------------------------+
```
Section order and copy: `research/ux.md` section 8 adjusted for the merge. **The Library row is the first row of the existing Graph section (Library, Graph Stats, Git Setup)** instead of a one-item section: the files live in this graph's assets folder, so Library is a view of graph contents like Stats and Git; a section named "Media" or "Library" would hold one row whose label repeats its header (redundant chrome and a double TalkBack announcement). Sections are Daily, Connections, Graph, Developer (ED-16 covers discoverability risk and the fallback rename). The sidebar row for `Screen.LibraryStats` is labelled **Graph Stats** (today `Sidebar.kt:346` still says "Library Stats", which would collide with the new Library name; asserted by `NavLabelUniquenessTest`, task D.2a). Headers rendered in sentence case or small caps per theme; header text short enough not to truncate. The sidebar and sheet row says "All pages"; the phone bottom bar uses the short label "Pages" for the same destination (decided; `shortLabelKey`).

### Interaction flow
1. User opens sidebar (always visible on desktop; toggled on narrow desktop windows).
2. Pointer/keyboard focus lands on the first row (Journals); Tab/Arrow Down moves row by row, passing over (not stopping on) section headers for keyboard focus, but screen readers announce them.
3. Activating a row navigates (`Screen.*`) and marks it `selected`; sidebar stays open on desktop, closes on narrow-window overlay mode.
4. Description is visible text in the row. No tooltip carries unique information.
5. Collapsing the sidebar (existing control) hides all text; rails keep icons with `contentDescription` equal to the label (decorative-icon rule applies only when text is visible).

### Error and edge states
| Situation | Behaviour | Exit |
|---|---|---|
| Developer section while not on Logs/Performance | Section absent (D.2 AC). When it appears (navigating to Logs) it is appended after all other sections, so no row above it changes bounds (asserted in D.3a); with reduced motion on it appears without animation | Reach Logs through the Logs link in error banners and Settings (existing) |
| Label or description longer than width | Description wraps to 2 lines; at font scale 2.0 may wrap to 2 lines and is never ellipsized below one full word | n/a |
| Destination unavailable (e.g. Git Setup on a platform without Git) | Row hidden (not disabled) unless a disabled row has a reason line "Not available on this device" | n/a |
| Unknown `Screen` not in spec | Build/CI fails (`NavModelExhaustivenessTest`), never a runtime blank row | n/a |
| Narrow window, sidebar overlay | Esc or scrim click closes it; focus returns to the toggle | Esc / scrim / toggle |

---

## S2. Phone bottom bar and "More" sheet

### Wireframe
```
Bottom bar (411dp wide)               More sheet (modal bottom sheet, 90% height max)
+--------------------------------+    +----------------------------------------+
|  [J]      [P]    (FAB)  [S] [M]|    |  ----  (drag handle; 48dp target)  [X] |
| Journals Pages          Search More   |  More                    <- sheet title (heading)
+--------------------------------+    |  DAILY                                 |
Items: Journals, Pages, Search, More. |   Journals  - Today's notes and past days
Center gap kept for voice FAB.        |   All pages - Browse and search every page (bar label "Pages")
                                      |   Flashcards - Review cards from your notes
                                      |  CONNECTIONS ...                       |
                                      |  GRAPH                                 |
                                      |   Library - Every photo and file on your pages |
                                      |   Graph Stats, Git Setup ... (DEVELOPER last, only when active)|
                                      +----------------------------------------+
```
Rows already reachable from the bar (Journals, Pages, Search) are shown in the sheet too (single source: `NavSection`); the currently selected row is `selected`.

### Interaction flow
1. Tap More: sheet slides up; `toggleSidebar` is not invoked (D.4 AC). TalkBack focus moves to the sheet title.
2. Scroll the list if taller than the viewport (the sheet is scrollable at 200% font scale).
3. Tap a row: sheet dismisses, destination opens, focus lands on the destination's title.
4. Dismiss without choosing: swipe down, tap scrim, tap the X button, system Back, or TalkBack "Dismiss" action. Focus returns to the More button.

### Error and edge states
| Situation | Behaviour | Exit |
|---|---|---|
| Navigation throws or screen fails to load | Sheet has already closed; destination shows its own error state with Retry and Back (S5) | Back returns to the previous screen |
| Landscape / small height | Sheet content scrolls; X and drag handle remain visible (sticky header) | X / Back |
| Font scale 2.0 | Descriptions wrap to at most 2 lines; row min height 48dp grows; no clipping | scroll |
| Bottom-bar label truncated | Not allowed below one full word; if it would, label shortens via I18n short-label key | n/a |
| Rapid double tap on a row | Second tap ignored once navigation started (no duplicate back-stack entry) | n/a |

---

## S3. Command palette

Commands derived from `NavDestinationSpec` (D.5): "Go to Library", one per other destination, "Show annotated photos" (Library with all other filters cleared and the Annotated filter on, LD-8) and "Annotate a photo".

```
+----------------------------------------------+
| > library                                    |
|----------------------------------------------|
|  Go to Library      Every photo and file ... |  <- description reused as secondary text
|  Show annotated photos  Library, Annotated   |
|  Annotate a photo   Pick an image and measure|
+----------------------------------------------+
```
Flow: type, arrow keys to choose, Enter activates, Esc closes. "Annotate a photo" opens the image picker.
Errors: picker cancelled returns to the palette-closed state with no message (user action). Picker returns an unreadable/unsupported file: snackbar "Couldn't open that image. Choose a JPEG, PNG or HEIC photo." with action "Choose another". Permission denied: message links to the existing permission recovery screen ("Photo access is off. Open settings"). No results for a query: "No matching commands" and Esc exits.

---

## S4. Decision record: Assets and Gallery merged into Library (ADR-006 DECIDED 2026-11-08)

Not a runtime surface any more; kept so the history of the options stays readable. The owner chose **B (merge)**; A and C are **rejected**.

| | A. Keep both, add sections (rejected) | B. Merge into one Library (**chosen**) | C. Rename and re-scope (rejected) |
|---|---|---|---|
| Sidebar rows | `Gallery`, `Assets` with descriptions | **`Library` - "Every photo and file on your pages"** (first row of the Graph section) | `Measured Photos`, `Files` |
| Phone More sheet | Two rows | **One row, first under GRAPH** | Two rows |
| Cross-links | None | **Filters inside one screen: Annotated, media type, Orphaned, tag, search; sort** | Badge, chip, "Open in Photos" |
| Change size | Smallest | Largest (two ViewModels, routes, tests) - accepted, mitigated by aliases, a parity table and retire-last (plan D.11) | Small |

Decision record fields (ADR-006): option B; label "Library" (working title, one I18n edit); description; tags are annotation tags only, not unified; "Does Library list unannotated images" yes (it lists every file; the Annotated chip narrows). Exit path for the old question: none needed, the gate is closed.

Baseline of today's confusion (for the first-open task test, UX-46): Gallery is the annotated-only subset of Assets, so an annotated photo appears in both, an attachment in one, and the flat sidebar names neither job.

---

## S5. Library screen (merged Gallery and Assets): grid and states

### Wireframe
```
+-----------------------------------------------------------+
| <- Library                         [Sort v]   [+ Add]     |
| [search: Search files and photos...]                      |
| Type:  [Images][PDFs][Audio][Video][Docs][Other]   multi-select, OR within the group |
| Show:  [Annotated][Orphaned]    (Annotated and non-image Type chips dim each other with a reason, LD-1) |
| Tags:  [site][kitchen][+5 more] multi-select, OR within the group (overflow rule LD-14)  [Clear filters] |
| 7 annotated items                          <- status line, polite live region (debounced, templates LD-17)
|   (below 600 dp width or at font scale >= 1.5: one [Filters (2)] button + Search + Sort; the chips live in a bottom sheet, LD-6)
| +--------+ +--------+ +--------+                          |
| | thumb  | | thumb  | |  PDF   |  annotated card: icon + word "Annotated", "3 measurements"
| |Annotated| |        | | icon   |  3rd card: a non-image file, type icon, no Annotate
| +--------+ +--------+ +--------+                          |
| (pages of 100; footer spinner while paging)               |
+-----------------------------------------------------------+
```
Add menu: Capture photo, Attach file, Choose photo to annotate (Android only; absent on desktop/wasm with the reason "Photo picker is available on Android"). Every added file is anchored to a page (LD-13), and the result under active filters or a non-default sort is defined in LD-12.

### States
| State | What the user sees | Action / exit |
|---|---|---|
| Loading (first page) | Skeleton grid of 6 placeholder cards; `stateDescription` "Loading library" announced once | Back |
| Loading next page | Footer spinner row, content stays interactive | scroll up / Back |
| Empty (no files at all) | "Nothing in your Library yet. Capture a photo or attach a file; it is added to {page name}, the page you were last on (LD-13)." [Attach a file] [Capture photo] | either button, or Back |
| Empty (filtered) | "No items match these filters." [Clear filters]; for Annotated only: "No annotated photos yet. Open an image and tap Annotate." [Show all]. A contradiction can never cause this state (the conflicting chip is dimmed, LD-1); it arises only from search text or from filters that genuinely match nothing | Clear filters / Show all / Back |
| Error (load failed) | "Couldn't load your Library." secondary "Your files are safe. Check storage access and try again." [Retry] [Open Logs] (replaces `Error: $err`) | Retry, Open Logs, Back |
| Error (page 2+ failed) | Inline footer "Couldn't load more." [Retry]; loaded cards remain | Retry |
| Missing file (row exists, file gone) | Placeholder tile with a broken-image icon, a visible text label "File missing" and an error-tinted border; tap opens a sheet. Annotated photo: "This file is no longer in your graph. [Remove measurements] [Keep]" (Remove asks "Delete measurements for this photo? This can't be undone."). Non-annotated asset: "This file is no longer in your graph. [Remove from Library] [Keep]" (removes only the index row; pages are untouched) | Keep / Cancel / Back |
| Permission denied | Empty-state variant "Photo access is off" + [Open settings] linking to the existing permission recovery screen | Settings / Back |
| Offline | Not applicable: local only (Drive export covered in S15) | n/a |
| Low-memory while scrolling | Thumbnails fall back to a **loading skeleton tile** (neutral filled surface, small outlined image glyph at reduced contrast, no text, no error tint, same card `contentDescription` as the real card) and are re-requested when scrolling stops or pressure subsides; no crash; no message (silent, logged). It must never be mistaken for "File missing" (ED-9, `LibraryThumbnailPlaceholderTest`) | n/a |
| Thumbnail permanently undecodable | A neutral card with a "cannot preview" glyph (an image outline with a slash, distinct from the broken-image icon of "File missing" and from the low-memory skeleton), the visible words "Can't preview", and no error tint; one failed decode (not an out-of-memory error) is remembered for the session per item id plus file size and modified time, so there is no retry loop; the card overflow offers "Try again"; the card still opens detail or editor, where the editor shows its error state (ED-13) with the copy "This image can't be decoded. It may be damaged or in an unsupported format." (LD-18, `LibraryThumbnailPlaceholderTest`) | Try again / open card / Back |
| Filter returns while a load is in flight | Previous list stays visible with a thin progress bar; the newest filter wins; no flash of empty | n/a |

Interaction: **tap rule** - tapping an **annotated image** opens the editor (Back returns to the same scroll position, filters, sort **and keyboard/TalkBack focus on the originating card**, LD-9); tapping any other card opens the asset detail; the card overflow menu (S19) always offers Details, Annotate (images) and the existing per-asset actions. The Annotated badge is what predicts the behaviour; the rule is part of the cold-user sessions (ED-17). Long-press is not used. Card `contentDescription`: "Annotated photo, {capture date}, {n} measurements, tag {tag}" or "{type} file, {name}, {size}, on page {page}" (unique per card). "Import from Gallery" no longer exists.

---

## S6. Library filters, detail and orphan behaviour

### Filter semantics
Full rules are LD-1..LD-20 in "Defaults - owner may override"; the summary a tester needs:
- Chips are toggle buttons (selected = filled + check icon; >= 48 dp) in three labelled groups: **Type** (Images, PDFs, Audio, Video, Docs, Other; multi-select, OR inside the group), **Show** (Annotated, Orphaned; each its own AND condition) and **Tags** (multi-select, OR inside the group). Groups combine with AND, search is one more AND. No "All" chip: no chip selected in a group means no restriction. [Clear filters] is visible whenever any chip or search text is active.
- Contradictions are prevented, not explained after the fact: Annotated and tag chips need annotated photos, so every non-image Type chip is dimmed while either is on, and Annotated and the tag chips are dimmed while a non-image Type chip is on (focusable, `stateDescription` carries the reason, a tap shows it in the reasons live region, LD-1). Annotated with Orphaned is a legal combination (LD-16).
- Search: text field with clear (x) button; every whitespace-separated word must match the start of a word in the file name, an annotation tag or an annotation label (LD-3); at least 2 characters, a single character shows the hint "Type at least 2 characters to search" and does not search; an empty result shows the filtered-empty state, not an error.
- Sort menu: Recently added (default), Recently annotated, Most measurements, Name A-Z; unannotated items sort after annotated ones for the two annotation sorts (LD-2); the Type chip membership is the table in LD-11. The selected order is checked and announced.
- Filters and search text last for the session (Back, rotation, fold, process-death restore) and reset on a fresh launch; the sort order is remembered across launches (LD-4).

### Detail view (asset detail, unchanged screen)
```
| Detail: kitchen.jpg                                          |
| [Annotate]  (images not yet annotated)                       |
| [Open annotation]  (annotated images only)                   |
| Referenced on: page "Renovation"      [Open page]            |
```
### States
| State | What the user sees | Action / exit |
|---|---|---|
| Badge/join failure | The page query returned an error: the Library error state (S5) with Retry; there is no partial list without the annotated state because it comes from the same query | Retry / Back |
| Orphan asset (no page) Annotate | The Annotate control is **disabled with a visible reason line** "Add this file to a page to annotate it" and a text button [Open pages] beside it; no snackbar, no hidden tap-then-fail; no annotation is created (ED-8; supersedes the enabled-plus-snackbar wording of 2026-11-08, owner may override) | [Open pages] / Back |
| Annotate on a non-image | Annotate is not shown for non-image types (Library chips that need photos are dimmed for the same reason, LD-1) | n/a |
| Open annotation | Opens the editor for that annotation; Back returns to the detail, then the Library with filters kept | Back |

---

## S7. First-run tip and upgrade notice (D.9)

Two variants, never shown together; both inline at the top of Library and shown on every Library open until dismissed (persisted dismissal); the badge/notice state machine is LD-20.
```
Fresh install (tip):
+--------------------------------------------------------+
| (i) Everything attached to your pages lives here. Tap  |
|     Annotated to see photos you've annotated.  [Got it] |
+--------------------------------------------------------+

Existing user (upgrade notice):
+--------------------------------------------------------------------------+
| (i) Gallery and Assets are now Library. Use the Annotated filter to see  |
|     your annotated photos.           [Got it]  [Show annotated photos]   |
+--------------------------------------------------------------------------+
```
- Which variant: a persisted `navIaVersion` setting. Absent plus a graph already registered at launch = existing user (notice); absent on a fresh install = tip; present = nothing.
- Also shown when an old route (palette command, deep link, saved screen) lands on Library through an alias and the notice has not been dismissed.
- **Reach for sidebar-only users (ED-16, state machine LD-20)**: until Library is first opened (the badge clears on that open; the notice stays until dismissed), the Library row (sidebar, More sheet row and the bottom-bar More button) carries a small "New" dot badge with a text equivalent (`stateDescription` "New: Gallery and Assets moved here"); the dot is not colour-only (a dot plus the word New in the sheet and sidebar row). After dismissal the explanation stays findable at **Settings > About > What's new** (a permanent entry "Gallery and Assets are now Library"). Fresh installs see neither badge nor entry's highlight.
- "Got it" and the system Back dismiss; "Show annotated photos" applies the Annotated filter and dismisses.
- If the preference store read/write fails: the notice/tip shows each time until dismissed in-session and the failure is logged; never blocks the screen.
- Not modal, does not trap focus; TalkBack reads it before the grid. Follows the reduced-motion setting (no slide-in).

---

## S8. Editor low-resource degradation (A.2c, A.5c)

Degradation levels (from `LoupeDegradationLevel`): L0 full, L1 smaller window/zoom, L2 no loupe. Triggered by `TRIM_MEMORY_RUNNING_CRITICAL` (and optionally measured CPU/jank signal, an engineering decision recorded in ADR-003). Messaging principles: degrade first, speak once, never block placement.

### Wireframes
```
L0: normal.                         L1: chip appears once, auto-dismiss 6s or on dismiss (persists while an accessibility service is on, UX-51)
+------------------------------+    +----------------------------------+
|        image canvas          |    | [i] Low memory: magnifier simplified [x] |
|     (loupe 120dp)            |    |        image canvas (loupe 96dp, lower zoom)
+------------------------------+    +----------------------------------+

L2: persistent chip, loupe off, crosshair + Place-at-crosshair mode offered
+------------------------------------------------+
| [!] Precision magnifier off (low memory)  [Details] [x?]  |  <- stays while L2; [x] collapses to icon, not removed
|            image canvas (no loupe)             |
|    [Place at crosshair]  (suggested; non-drag mode, OD-1 included) |
+------------------------------------------------+
Details sheet: "Your device is low on memory, so the magnifier is off.
 Placement and measurements still work. Closing other apps can bring it back."  [OK]
```
### Flow
1. Memory pressure event arrives. L1 applies at the next press (never mid-gesture), chip shown once per session per level; `stateDescription`/live region polite announcement "Low memory. Magnifier simplified."
2. Further pressure: L2. The chip becomes persistent but collapses to a 48dp icon button on dismiss, so the state is always discoverable. Placement continues by press-and-hold drag-to-aim without the loupe, or Place-at-crosshair (S12; a quick tap moves the crosshair, ED-3), which is highlighted as the precise alternative.
3. Recovery: pressure subsides, level steps back only on the next editor open (no flapping mid-session); the chip is removed at that open. Logged via `Logger` (`editor.trim level=...`).
4. Image too large (only if engineering confirms measurements use the original): a one-line inline note "Showing a reduced-resolution preview; measurements use the original." Without that confirmation, this message is omitted (UNVERIFIED claim in research/ux.md section 6).

### Error and edge states
| Situation | Behaviour | Exit |
|---|---|---|
| OOM/Throwable inside the editor coroutine | Caught by scope handler (A.2b); non-modal banner "Something went wrong. Your placed measurements are kept." [Retry] [Close editor]; error logged | Retry / Close editor / Back |
| Loupe decode fails once | Silent fall to L1 for that press, no message | n/a |
| Decode of main image fails | Full-canvas error: "Couldn't open this image." [Retry] [Back to Library] | Retry / Back |
| Chip overlaps controls | Chip anchors above the tool bar, never over the canvas center or loupe path | dismiss |
| Reduced motion | Chip appears without animation | n/a |

---

## S9. Post-kill launch banner and Logs replay (A.2a)

After the process is killed while the editor was open (exit reason from `getHistoricalProcessExitReasons`, API 30+; `PreviousSessionLog` already replays it in Logs).
```
+------------------------------------------------------------+
| The app closed unexpectedly while annotating.   [View log] [Dismiss] |
+------------------------------------------------------------+
```
- Shown once on next launch at the top of the main screen. The sentence "Your points were saved up to the last placement" is added only if C.5 persistence (ADR-005) is confirmed to guarantee it; otherwise omitted (UNVERIFIED, ux.md question 3).
- [View log] opens the Logs screen scrolled to the replayed session; [Dismiss] and Back close the banner. Banner never reappears for the same exit record.
- If exit reason is unavailable (API < 30): banner wording drops the cause and still appears when `last-crash.txt` exists.
- If reading the record fails: no banner (log only); no blocking state.

---

## S10. Unsaved-changes dialog (C.6)

```
Back pressed with 1 unfinished point:
+--------------------------------------+
| Discard 1 unfinished point?          |
| Placed measurements are already saved.|  <- CONDITIONAL: show only once Open Question 2 is confirmed (ADR-005/C.5); otherwise omit this line
|         [Keep editing]   [Discard]   |
+--------------------------------------+
```
- Back with only committed annotations and no in-progress points: no dialog, editor closes (C.6 AC).
- Plural: "Discard 3 unfinished points?" (plural rule via I18n).
- Default focus on [Keep editing] (non-destructive). Discard is a text button using the error/secondary color AND the word "Discard"; it is not distinguished by color alone.
- Dismiss paths: Keep editing, tapping outside the dialog, or Back again (all keep editing). Discard closes the editor and keeps committed annotations.
- Error state: if a save is still queued (persistence queue non-empty) when the user chooses Discard or Back: the editor waits up to 2 s showing "Saving..." then closes; if the save fails, the editor stays open with the retry banner (S14) rather than losing data. Exit: [Retry] / [Close anyway] (confirm "Close without saving the last change?").

---

## S11. Loupe geometry and edge behaviour (C.2, C.9)

```
Normal:                       Near top (flip below):          Near left edge (slide right):
      +-------+                      (+)  finger                  (+)  finger at x=2dp
      | zoom  |  120dp                 \                          +-------+  loupe shifted right,
      |  +    |                      +-------+                    |  +    |  crosshair still
      +---v---+                      | zoom  |                    +-------+  under the touch point
        (+) finger                   +-------+
```
Rules (acceptance criteria use these):
- Loupe is always inside the canvas with a 16dp margin; if the canvas is smaller than loupe + margins (panorama 360x90 dp), the loupe shrinks to fit rather than throwing (C.2).
- The crosshair inside the loupe marks the exact aim point; the aim point can reach the image edge even when the loupe is clamped. A static crosshair dot is also drawn at the true touch location on the canvas so the user sees both.
- First press shows a placeholder (neutral fill, no transparent hole) until the decode lands; no flash of empty circle.
- Loupe never covers the point being aimed at: placement side flips near top and near left/right edges.
- Handedness: loupe offset mirrors for left-handed use. Setting location (decided 2026-11-08): **Settings > Annotation > "Magnifier side"** (Right default), with a shortcut item "Magnifier side" in the editor overflow menu that opens the same setting; it is not a system setting (C.9a, UX-55).
- **Tap versus drag (ED-3, UX-49)**: touch contact is classified by two thresholds only, 120 ms and 8 dp, so no band is ambiguous. (1) Released before 120 ms with movement <= 8 dp = **quick tap**: it never places a point from the raw fingertip (the least accurate path for a measuring tool) and never shows the loupe; it moves the Crosshair there and switches to Crosshair mode with a one-time hint (setting "Quick tap" can be changed to "Place point", then the point is placed at the contact plus the "Fingertip offset"). (2) Held >= 120 ms, or moved > 8 dp at any time = **aim**: the loupe appears (at 120 ms or at the moment the slop is exceeded, whichever comes first) and **lift places** at the aim position. There is no upper time limit: a long press without movement is still an aim. (3) Lift **off the canvas** (beyond the 12 dp edge tolerance) **cancels** with no point (OD-3 included; feedback below); a second finger or a system gesture discards the press. Mouse and stylus follow ED-3b. First-use hint (shown once per hint version, including to **existing** users on their first editor open after the update, ED-5): "Press and hold, drag to aim, lift to place. Drag off the image to cancel. A quick tap moves the crosshair."
- **In-gesture cancel feedback (ED-4, UX-57)**: while the aim point is outside the cancel boundary (beyond the canvas by more than the 12 dp edge tolerance, so edge-hugging presses that must reach the image edge never cancel), the loupe dims to 40% and shows the label "Release to cancel" with an X icon, one haptic tick fires on entering and one on re-entering the zone, and a polite announcement "Release to cancel" is made once per entry. Releasing inside the tolerance clamps to the image edge and places. Letterboxed images: the cancel boundary is the **canvas** (the touch-receiving area), and the hint copy says "Drag off the image area"; with reduced motion the dim is instant.
- **Clear points beyond Undo (S17, UX-50)**: while any unfinished point exists, a labelled [Clear points] button (icon plus word, >= 48 dp) in the tool bar clears the unfinished points; Undo removes the last unfinished point, then the last committed annotation.
- Errors: decode failure shows the neutral placeholder and continues (see S8). Pointer cancel (system gesture, incoming call) discards the in-progress press with no point created.

---

## S12. "Place at crosshair" non-drag mode (C.8c, C.8f, C.8g, WCAG 2.5.7; OD-1 INCLUDED)

Drag-to-aim (S11) remains the **default**; this mode is the alternative, reachable from the Mode toggle and suggested by the L2 chip.

### Wireframe
```
+------------------------------------------+
|              image canvas                |
|                  +  <- fixed crosshair, moved by the nudge pad over a FIXED image
|  (the image does not pan; the crosshair starts at the image center)
+------------------------------------------+
| Mode: [Drag] [Crosshair*]                 |   <- segmented toggle, selected = filled + check icon
| Step: [Fine*][Medium][Coarse][Exact]      |   <- screen-relative steps (ED-6)
|            [ ^ ]                          |   plus-shaped D-pad, cells >= 56dp, centre = primary button
|         [<] [Place] [>]    [Undo]         |   labels: "Nudge left, fine step", "Place point"
|            [ v ]           [Clear points] |   D-pad sits on the magnifier-side setting's side
+------------------------------------------+
+------------------------------------------+
```
(Design choice: crosshair moves over a fixed image, starting at the image center (0.5, 0.5) per C.8 AC; the loupe shows under the crosshair, not above a finger. Tapping the canvas in this mode moves the crosshair there and places nothing; only [Place point] places. Dragging the crosshair itself and the step selector are included, OD-2. Full canvas gesture table: ED-19; minimum canvas size and the landscape or large-font fallback layout: ED-21.)

### Flow
1. User switches Mode to Crosshair (or taps the suggested [Place at crosshair] from the L2 chip).
2. Crosshair appears at the center. User moves it by nudge buttons or by dragging the crosshair itself (optional; the buttons are the non-drag alternative). **Steps are screen-relative (ED-6)**: Fine = 1 screen pixel, Medium = 8 dp, Coarse = 5% of the canvas width, Exact = 1 image pixel; each converts to image pixels through the current scale (never less than 1 image pixel), so every Fine tap visibly moves the crosshair on a 48 MP photo. **Press and hold auto-repeats**: after 400 ms, 12 steps per second, the stride doubling each second held up to 8x, stopping on release. The loupe is shown under the crosshair whenever it is not degraded.
3. [Place point] adds a point at the crosshair; the existing tool logic (DISTANCE needs 2 points, AREA closes, etc.) continues. Live region: "Point 2 placed at 42 percent across, 61 percent down" and, on commit, "Measurement 3.20 m added" with one haptic tick. **Position announcements (ED-6)**: when a nudge or repeat stops, a polite, rate-limited (one per 600 ms) announcement "Crosshair at 42 percent across, 61 percent down" is made; left/right nudges are physical directions and do not mirror in right-to-left locales.
4. [Undo] removes the last in-progress point; then the last committed annotation (same as the toolbar Undo).
5. Switching back to Drag keeps placed points; the crosshair position is remembered for the session.

### Errors and edges
| Situation | Behaviour | Exit |
|---|---|---|
| Nudge past the image edge | Clamped at 0.0/1.0; announces "Left edge" once | continue |
| Place with coincident points | Hint "Points too close" and nothing created (C.9c) | nudge, Place again |
| Loupe L2 (off) | Mode still works; crosshair is the precision aid | n/a |
| Tool not selected | [Place point] disabled with reason line "Choose a measuring tool first" | pick a tool |
| Small window, landscape phone or 200% font | The canvas keeps at least 200 dp of height and 40% of the window height; otherwise the layout falls back (side rail, then a [Nudge pad] sheet), ED-21 | use either layout |
| Keyboard (desktop) | Arrow keys nudge by the selected step, Shift+Arrow = 10 x the selected step, Enter places, Esc leaves crosshair mode; a visible focus ring (ED-12) is on the focused control | Esc |
| First entry to Crosshair mode | One-time hint (ED-5) "Move the crosshair with the arrows, then tap Place point.", announced politely, dismissible, re-readable from the editor overflow > Help: placing points | dismiss |
| TalkBack or switch access is on at editor open | The editor **starts in Crosshair mode** (setting "Start in Crosshair mode": Auto, Always, Never; Auto = on when an accessibility service is enabled, ED-6); switching to Drag stays possible and is remembered | toggle |

---

## S13. Select/move handles

```
Selected distance line:
   (O)-------------(O)     O = vertex handle: visible 16dp dot inside a 48dp touch area,
    ^ ring + filled center      selected state = ring + thicker line (not color-only)
  Toolbar: [Delete] [Undo] [Deselect]  (visible whenever something is selected)
```
Flow: Select tool, tap a vertex or a line to select (selected annotation announced: "Distance 3.20 m selected"). Drag a vertex: the loupe appears (same rules as S11) and a haptic tick fires at pick-up and drop. Releasing commits the move as one undo entry. Tap empty canvas or press [Deselect] deselects.
Errors/edges:
| Situation | Behaviour | Exit |
|---|---|---|
| Overlapping handles | Resolution order (ED-1, `HandleHitResolver`): a vertex handle beats any line body; among vertices within the 24 dp handle radius of the touch, the **nearest wins**; a tie within 1 dp goes to the vertex of the already selected annotation, then to the last placed. Beyond 24 dp from every vertex the touch selects the nearest line (no drag). Vertices closer together than the 48 dp touch areas are the accepted case: the **equivalent non-drag path** (included, C.8f): select the annotation and choose Move vertex 1/2 (48 dp toolbar buttons or TalkBack custom actions); the crosshair jumps to that vertex, nudge buttons move it, [Apply] commits one undo entry, [Cancel move] restores | Deselect / Cancel move |
| Drag leaves the canvas | Handle pins to the edge; release commits at the edge | Undo |
| Pointer cancel | Vertex snaps back to its pre-drag position, no undo entry | n/a |
| Delete | Immediate with snackbar "Deleted measurement" [Undo] (6 s), not a confirm dialog | Undo |
| Select with TalkBack | Each committed annotation is a focusable node "Distance, 3.20 m, from point 1 to point 2", Role.Button, custom actions: Select, Delete, Move vertex 1, Move vertex 2 | n/a |

---

## S14. Editor messages: persistence, legacy calibration, hints

| Condition | Presentation | Copy (PROPOSED) | Actions / exit |
|---|---|---|---|
| Initial load failed (`Either.Left`) | Non-modal banner above the toolbar, editing allowed, no bulk delete issued (C.5) | "Couldn't load saved measurements. New ones are kept, but earlier ones aren't shown." | [Retry] [Close editor] |
| Save failed | Banner with live region | "Couldn't save the last change." | [Retry] [Keep editing] |
| Legacy calibration (pre-fix) | Badge on the calibration indicator, with text, not color alone | "Recalibrate for accuracy" | tap opens calibration; [Not now] hides until next open |
| Points too close | Transient hint (2 s) near the last point | "Points too close" | none needed |
| Orphan-asset Annotate | Disabled control with a visible reason line in Library and the asset detail (S6, ED-8) | "Add this file to a page to annotate it" | [Open pages] |
| Calibration undo | Snackbar | "Calibration changed. [Undo]" | Undo restores previous value only; annotations kept (ADR-005) |
| Commit | Haptic tick plus polite live region; transient point-count chip, never blocks next press | "Measurement 3.20 m added" | n/a |

All banners use icon plus text, are not red/green only, have a >= 48dp dismiss/action target, and do not cover the canvas center.

---

## S15. Export (Drive) states (C.7)

Entry: export icon in the editor toolbar (existing; the hard-coded blue `0xFF1565C0` is replaced by a themed color with >= 4.5:1 contrast in both themes).
| State | Presentation | Exit |
|---|---|---|
| In progress | Icon becomes a progress indicator, label "Exporting..." ; editing remains possible | [Cancel export] |
| Success | Snackbar "Exported to Drive" [Open] | dismiss |
| Offline (included, OD-4; message only, no retry queue) | Snackbar "Offline. Saved on this device. Try again when connected." [Retry] | Retry / dismiss |
| Not signed in / permission | "Sign in to Google Drive to export." [Sign in] | Sign in / dismiss |
| Decode/encode failure incl. OOM (caught as `Throwable`, C.7) | "Couldn't export this image." [Retry] [Export smaller] ("smaller" uses a reduced pixel bound) | Retry / dismiss |
| Missing source file | "The photo's file is missing." | dismiss |

---

## S16. Logs exit-reason line (non-interactive, condensed)

Sample (from plan A.2a AC): `exit reason=EXCESSIVE_RESOURCE_USAGE importance=CACHED status=0 pss=205MB desc="excessive cpu 61000 during 300000 dur=300000 limit=25"`.
Acceptance bullets:
- Line contains reason name, importance name, status, PSS and the verbatim description.
- Up to 10 previous exit records are listed, newest first.
- Log text is selectable/copyable (owner pastes from phone) and the Logs screen has a [Copy] action with a "Copied" confirmation.
- Each editor degradation event is one `editor.trim level=N` line.
- No image paths or contents are logged (requirements: security).

---

## S17. Editor open / main-image loading state, tap versus drag, cancel affordance (C.9f, C.9g)

### Open and loading
```
Cold open (main image loading)            Error after 15 s or decode failure
+------------------------------+          +------------------------------------+
| <- Photo        [tools...]   |          | <- Photo                           |
|        (spinner)             |          |  Couldn't open this image.         |
|     Opening photo...         |          |  [Retry]   [Back to Library]       |
| (placement + loupe disabled) |          +------------------------------------+
+------------------------------+
```
- Placement, loupe and select are disabled until the file's `NativeImageSize` is known; a tap on the canvas shows the transient hint "Photo is still opening" (no point is created). Back and Close work at all times.
- **"Still opening..." comes before any hard error (ED-13)**: 0 to 3 s "Opening photo..."; from 3 s the text becomes "Still opening... large photos can take a moment" (polite, once) with a visible [Back to Library]; the hard error appears only on a real decode failure or at a 15 s ceiling (fake-clock testable), never at a time a slow-but-succeeding decode could still beat.
- **Retry versus a stale decode (ED-13)**: each open or Retry gets an increasing generation number and cancels the previous job; a result or failure from an older generation is discarded, so a late failure never replaces a newer success and Retry never causes two live decodes.
- "Opening photo..." is announced once (polite). If the image is already in the shared Coil memory cache, it shows as a placeholder immediately and the spinner is skipped.
- Error: after the 15 s ceiling without a decode, or on a decode failure, the spinner is replaced by the error above (logged with dimensions only). Exit: Retry, Back to Library, system Back.
- Reduced motion: no spinner animation beyond the platform's static progress indicator.

### Gestures (decided 2026-11-08, revised by ED-3; acceptance criteria UX-49)
| Gesture (placement tool, Drag mode, touch) | Result |
|---|---|
| Quick tap: released before 120 ms, moved <= 8 dp | Crosshair moves to the contact and the editor enters Crosshair mode (nothing placed, no loupe). The switch is visible and announced (the mode toggle changes to Crosshair with its check and fill, the live region says "Crosshair mode. Move the crosshair with the arrows, then tap Place point.", ED-18); with setting Quick tap = "Place point": one point at contact + Fingertip offset and no mode change |
| Held >= 120 ms, or moved beyond 8 dp | Loupe appears; lift places at the aim position (no upper time limit) |
| Lift off the canvas (beyond the 12 dp edge tolerance), including over the toolbar region | Cancels: no point; in-gesture "Release to cancel" feedback (S11). Dragging toward a screen edge that the system back gesture owns also ends in a pointer cancel, which is the same outcome; the exclusion and inset rule is ED-20 |
| Mouse or stylus click (ED-3b) | Places precisely at the pointer position at once (pointer devices are not fingertip-imprecise); a drag beyond the slop shows the loupe and release places; Esc during a drag cancels; hover shows the aim dot, never the loupe |
| Second finger lands / system gesture / pointer cancel | Press discarded: no point |
| Select tool, tap | Selects the nearest annotation within 24 dp; never places |
| Crosshair mode, tap on canvas | Moves the crosshair there; never places (only [Place point] places) |
| Crosshair mode, hold or drag starting on empty canvas (moved > 8 dp) | Relative drag: the crosshair moves by the finger's delta and does not jump to the finger; the loupe shows under the crosshair; lifting does **not** place and there is no cancel zone (ED-19) |
| Crosshair mode, drag starting on the crosshair (within 24 dp of it) | Same relative drag; [Place point] places |

### Cancel affordance (UX-50)
```
Tool bar with unfinished points:  [Undo] [Clear points (x)] [Finish*]     Without: [Undo]        (* only for multi-point tools with enough points)
```
[Clear points] is text plus icon, >= 48 dp, and appears whenever at least one unfinished point exists; it discards only unfinished points. Undo is a separate action. **Label vocabulary (ED-2)**: "Cancel" is never a bare label: Clear points (unfinished points), Cancel move (non-drag vertex move), Cancel export (Drive); "Finish" completes a multi-point shape, "Deselect" ends a selection, "Close" leaves the editor; the word "Done" is not used. The first-use hint names both: "Undo removes the last point. Clear points removes unfinished points."

---

## S18. Editor accessibility: test plan and timeouts (C.9h, C.9i; UX-51, UX-52)

| Aspect | Specification | Test |
|---|---|---|
| Focus / TalkBack order | In every state (idle, unfinished points, selection, Crosshair mode, error banner, degradation chip, loading): top-bar title, banners and chip, canvas annotation nodes in creation order, tool bar, mode toggle, nudge pad | `EditorFocusOrderTest` |
| Touch targets | **Audited surface (ED-1)**: toolbar buttons, mode toggle, step selector, D-pad and [Place point], [Clear points], [Finish], [Deselect], [Apply]/[Cancel move], chips and their dismiss controls, banner and dialog actions, snackbar actions, overflow items: each >= 48 dp with >= 8 dp between neighbours. **Not audited as controls: canvas hit areas** (vertex handles, line bodies). Canvas handles keep a nominal 48 dp touch area but are governed by the nearest-wins and handle-priority rule (S13) and by the equivalent non-drag path (Move vertex), which is what WCAG 2.2 SC 2.5.8 (Target Size, Minimum) permits through its equivalent-control exception. | `EditorTouchTargetAuditTest` (controls) + `HandleHitResolverTest` and `CanvasHandleEquivalentPathTest` (canvas) |
| Contrast | Text >= 4.5:1, graphics >= 3:1, light and dark, from C.9d tokens | `ThemeContrastTest` |
| Reduced motion | Loupe appearance, chips, skeletons, sheets, the cancel-zone dim and the nav badge do not animate when reduced motion is on. **Signal source (ED-15)**: Android reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (what the system "Remove animations" option sets; INFERRED from platform behaviour, verify on the Pixel_Fold in C.9d); desktop and web use the in-app Settings > Appearance > "Reduce motion" switch (default off) because the JVM has no portable OS signal. The signal is an injectable `ReducedMotionProvider` | `ReducedMotionTest` (fake provider) |
| Live regions | Polite for commit, degradation, save, point-count; assertive only for the fatal-error banner | `LiveRegionPolitenessTest` |
| Accessibility timeouts | The recommended timeout (`AccessibilityManager.getRecommendedTimeoutMillis` with the icon, text and control flags) applies whenever it exceeds the nominal duration, **including when only "Time to take action" is set and no screen reader is on**; with a touch-exploration or switch service on, a message that carries an action never auto-dismisses. Snackbars (ED-14): one at a time, a newer informational one replaces the current informational one, an action-bearing one is never replaced (later ones queue, at most 3); deletes inside one window coalesce into "Deleted 2 measurements [Undo]"; dismiss by swipe, Esc, or the TalkBack "Dismiss" custom action; the snackbar sits above the toolbar and D-pad and never covers them. The save-failed banner stays persistent with an action and is announced assertively once. | `TimedMessageAccessibilityTest`, `EditorSnackbarQueueTest` (fake `AccessibilityManager`) |
| Headings and pane titles | Library title, each Library filter group label, Settings > Annotation title and each setting group, every dialog and sheet title (unsaved-changes, Help: placing points, details, Delete file, filter sheet), and the editor top-bar title are `heading()`; dialogs and sheets also set a pane title. Android has no landmark role, so groups use `isTraversalGroup` with a label | `ScreenHeadingsTest` (Library, Settings > Annotation, dialogs, Help) |
| Banner, canvas and snackbar order | Traversal: top-bar title, banners and chips, canvas annotation nodes, toolbar, mode toggle, nudge pad, then the snackbar **last**; the snackbar is a polite live region so it is announced on arrival without moving focus, and it is drawn above the toolbar and D-pad (ED-25) | `EditorFocusOrderTest` states `banner`, `snackbar` |
| System Back precedence | See ED-24 | `EditorBackPrecedenceTest` |
| Real TalkBack | Manual pass on the owner's phone: focus order above, Move vertex actions, snackbar persistence | device evidence in validation.md |

---

## S19. Settings > Annotation, editor overflow, Library Add menu and card overflow (C.9l, D.6f; UX-69, UX-70)

### Settings > Annotation (new screen in the existing settings store; ED-10)
```
+------------------------------------------------+
| <- Annotation                                  |
| Magnifier side        (o) Right  ( ) Left      |   <- label was "Magnifier side"; kept (UX-55)
| Quick tap             (o) Move crosshair  ( ) Place point |
| Fingertip offset      [---o------] 0 dp        |   <- 0-48 dp step 4; disabled until Quick tap = Place point,
|                        reason line: "Used only when a quick tap places a point."
| Start in Crosshair mode (o) Auto ( ) Always ( ) Never |   <- Auto = on when TalkBack/switch access is enabled
+------------------------------------------------+
```
Every row is a >= 48 dp radio group or slider with a visible label and current value; changes apply immediately and persist; a persistence failure keeps the in-session value and logs (never blocks). Back returns to Settings.

### Editor overflow (the "More options" button in the top bar, 48 dp, `contentDescription` "More options")
Items: **Help: placing points** (opens a sheet with the gesture hint and the Crosshair hint, ED-5), **Magnifier side** (opens the same setting as Settings > Annotation), **Annotation settings** (opens Settings > Annotation), **Recalibrate** (only while a legacy-calibration badge is showing). Each item is >= 48 dp; Esc or Back closes the menu and returns focus to the button; with TalkBack the menu announces its item count.

### Library Add menu (ED-11)
| Item | Available | Failure and edge states |
|---|---|---|
| Capture photo | Android with a camera; absent on desktop/wasm with the reason "Camera is available on Android" | Permission denied: inline message "Camera access is off." [Open settings]; capture cancelled: silent; capture saved: new card appears at the top (Recently added) |
| Attach file | All platforms | Picker cancelled: silent; unreadable/unsupported file: snackbar "Couldn't add that file." [Retry]; in progress: the Add button shows progress and is disabled; success: snackbar "Added {name} to {page}" [Open page] (LD-12, LD-13) |
| Choose photo to annotate | Android only | Cancelled: silent; unreadable: snackbar "Couldn't open that image. Choose a JPEG, PNG or HEIC photo." [Choose another] |

### Library card overflow and delete (LD-10)
Items: **Details**, **Annotate** (images; disabled with the reason line for orphans, ED-8), **Open annotation** (annotated images), **Open page** (when referenced), and the existing per-asset actions kept as they are in `AssetActionMenu`. **Delete file** asks "Delete {name}? It is used on {n} page(s) and has {m} measurement(s); those links and measurements will be removed. [Delete] [Keep]" (default focus Keep; Delete is a worded text button); in progress shows a progress indicator on the card; failure shows a snackbar "Couldn't delete {name}." [Retry]; success shows "Deleted {name}" without Undo (a deleted file cannot be restored, which is why the confirmation names the consequences).

## S20. Configuration change: fold, unfold, rotate (C.9k, D.6e; UX-61, UX-62; ED-7)

| State | Editor | Library |
|---|---|---|
| Retained | Unfinished points, crosshair position (stored as normalized image coordinates, so it stays on the same image point), mode, tool, step, selection, calibration indicator, open dialogs (unsaved-changes, details sheet), overflow menu, the pending Delete-Undo snackbar for its remaining time | Filters, search text, sort, selected chips, open filter sheet, scroll anchor by first visible item id (also after process death, LD-19), focus on the originating card |
| Recomputed | Canvas layout, loupe clamp, D-pad placement (follows the handedness setting), cancel boundary | Column count = floor(width / 160 dp), minimum 2; compact (< 600 dp) versus wide filter layout, switching live as the Pixel Fold crosses 600 dp (cover about 411 dp, inner display about 673 dp) |
| Discarded | A press that is in flight (finger down) is discarded with no point | Nothing |
**Nav shell swap at 600 dp (ED-22)**: crossing 600 dp swaps the phone shell (bottom bar plus More sheet) and the wide shell (sidebar). Retained: the current destination, its scroll and state (route level), and the Developer section's expanded or collapsed state (one `AppState` flag shared by sidebar and sheet). Discarded: an open More sheet is dismissed on unfold (the sidebar shows the same rows); on fold the sidebar closes and the More sheet is not reopened. Focus moves to the current destination's row (sidebar) or its bar item or the More button (phone). An open editor is a full-screen route and is unaffected. Test: `NavShellConfigChangeTest`.

Posture on a half-open fold (tabletop): layout follows the window size only; no posture-specific UI. Tests: `EditorConfigChangeTest` and `LibraryConfigChangeTest` (Robolectric qualifier change plus state restoration), and a Pixel_Fold fold/unfold pass recorded as device evidence.

## Cross-cutting accessibility

- **Focus/TalkBack order**: editor order is top app bar title, then banners and the degradation chip (they carry state), then canvas annotation nodes in creation order, tool bar, mode toggle, nudge pad. The sheet title is first in More.
  - Sheet: title, section header, rows in visual order, next header, rows, X last.
  - Library: title, tip or notice (if any), search, filter groups (each labelled group: "Filter by media type", "Show", "Filter by tag"), status line, grid cards in reading order, footer retry. Chips in a group use roving focus on desktop (Left/Right move, Tab leaves the group).
- **Headings**: every section header (Daily, Connections, Graph, Developer) and the sheet title use `heading()`.
- **Targets**: rows, chips, nudge buttons, handle touch areas, dismiss buttons, drag handle >= 48dp; 8dp spacing between adjacent targets.
- **Non-color-only**: selected row (`selected` semantics plus a leading indicator bar), selected tool/mode (check icon + fill), Annotated badge (icon + word), selected annotation (ring + line weight), error banners (icon + text), Discard (word).
- **Font scale 200%**: descriptions wrap to 2 lines, rows grow, no truncation; the Library grid reduces columns and the filter bar collapses to the Filters sheet (LD-6).
- **Contrast**: text >= 4.5:1, icons/graphics >= 3:1 in light and dark; chip and badge colors tokenised in the theme.
- **Live regions**: polite for commit/degradation/save messages; assertive only for the fatal-error banner.
- **Reduced motion**: sheet, chip, skeleton shimmer respect the system setting.
- **Icons**: decorative icons next to text use `contentDescription = null`; bottom-bar icons are decorative because the text label is visible (fixes the double announcement noted in research/ux.md section 4).
- **Keyboard (desktop)**: Tab order follows visual order; Enter/Space activates; Esc closes sheet, palette, dialogs and crosshair mode. **Focus-visible (ED-12)**: every focusable control (chips, cards, nudge buttons, rows) shows a >= 2 dp focus ring at >= 3:1 contrast when focus arrives by keyboard (not on pointer press). **Library grid (LD-9)**: arrow keys move between cards, Home/End jump to first/last, PageUp/PageDown move a screenful, Enter activates the tap rule, Shift+F10 or the Menu key opens the card overflow. **Focus restore**: Back from the editor or detail returns focus to the originating card (by item id; to the grid if the item is gone).

## Flow completeness check (exit path and error state)

| Flow | Exit path | Error state | Gap |
|---|---|---|---|
| Open sidebar and navigate (S1) | Esc/scrim/toggle | Coverage test + destination error state | None |
| Phone More sheet (S2) | Swipe/X/scrim/Back | Destination error + Retry | None |
| Command palette (S3) | Esc | Picker/permission errors | None |
| Decision record (S4) | n/a (DECIDED, non-runtime) | n/a | None |
| Library screen (S5) | Back | Load/page/missing/permission | None |
| Library filters, detail, orphan (S6) | Clear filters/Back | Load failure, orphan disabled-with-reason (ED-8) | None |
| First-run tip / upgrade notice (S7) | Got it/Back | Preference failure | None |
| Degradation (S8) | Dismiss/collapse chip; editor Back | OOM banner, decode fail | None |
| Post-kill banner (S9) | Dismiss/View log | Missing record | None |
| Unsaved dialog (S10) | Keep editing | Queue drain failure | None |
| Loupe (S11) | Off-canvas release cancel (with in-gesture feedback); [Clear points] button | Decode fail placeholder | None (OD-3 included; plan C.9a, C.9f, C.9j) |
| Crosshair mode (S12) | Esc/mode toggle; [Cancel move] on a move | Edge clamp, too close | None (OD-1/OD-2 included; plan C.8c, C.8f, C.8g) |
| Select/move (S13) | Done/Undo | Pointer cancel | None (OD-4 included; plan C.8f) |
| Messages (S14) | Dismiss/Retry | itself | None |
| Export (S15) | Cancel export/dismiss | Offline/auth/OOM | None (offline is message only, no queue; plan C.7c) |
| Open / loading (S17) | Back / Close / Retry | Timeout, decode failure | None |
| Accessibility timeouts (S18) | Dismiss or persist | n/a | None |
| Settings > Annotation, editor overflow, Add menu, card overflow (S19) | Esc/Back/dismiss | Permission denied, unreadable file, delete failure with Retry | None (plan C.9l, D.6f) |
| Fold, unfold, rotate (S20) | n/a (state retained) | In-flight press discarded without a point | None (plan C.9k, D.6e) |

## UX acceptance criteria (human-testable)

Navigation (S1-S4, S7)
1. UX-01 A user can read the sidebar (desktop) and state, without opening the screen, what Library contains (every photo and file on the pages) and that the Annotated filter narrows it to measured photos, from the visible description text alone. (amended 2026-11-08, ADR-006 DECIDED)
2. UX-02 Sidebar shows section headers Daily, Connections, Graph in that order, with the Library row first in Graph; Developer appears only while on Logs or Performance. No section holds a single row whose label repeats its header. (amended Repair 4, ED-16)
3. UX-03 Every destination row shows a headline plus a one-line description of at most 10 words as visible text (no tooltip dependency).
4. UX-04 On a 411dp phone, More opens a grouped modal sheet (not the drawer) in one tap; any destination is reachable in <= 2 taps from the bottom bar.
5. UX-05 The More sheet can be dismissed by swipe, scrim tap, X button and system Back; focus returns to the More button.
6. UX-06 With font scale 200%, no row description is clipped beyond 2 lines and row height >= 48dp.
7. UX-07 TalkBack reads each section header as a heading, then its rows in visual order; bottom-bar items are announced once.
8. UX-08 The current destination is identifiable without color (selected semantics plus an indicator shape).
9. UX-09 The palette lists "Go to Library", "Show annotated photos" and "Annotate a photo"; Enter on the first result navigates. (amended)
10. UX-10 The owner's decision is recorded in ADR-006 with option, working title and strings (DECIDED 2026-11-08: merge into Library); the title can be changed with one I18n edit. (amended)
11. UX-11 The first-run tip shows once on the first Library open, dismisses via Got it or Back, and never returns; failure to persist does not block the screen.

Library screens (S5, S6)
12. UX-12 Library empty state offers Attach a file and Capture Photo; the filtered-empty state offers Clear filters / Show all. (amended)
13. UX-13 Every load error on Library shows a plain-language message and a Retry button that re-runs the load; Back remains available.
14. UX-14 Library with 8,000+ rows scrolls to the end without a visible stall; paging footer shows progress; a failed page shows an inline Retry.
15. UX-15 A Library card whose file is missing shows "File missing" and offers Keep and Remove (with confirmation; the Remove wording differs for annotated and non-annotated items).
16. UX-16 Two different Library cards have different screen reader descriptions including date and measurement count.
17. UX-17 In Library, the Annotated badge shows icon plus word and the Annotated chip filters in pages of at most 100; the annotated state comes from the same query as the page, so a failure shows the Library error state with Retry rather than a half-rendered list. (amended)
18. UX-18 "Open annotation" appears only on annotated images in the detail view and opens that annotation's editor; Back returns to the detail, then to Library with filters kept. (amended)

Editor degradation and messages (S8-S10, S14, S15)
19. UX-19 Under simulated memory pressure the user can still place a point; at L1 a dismissible chip "Low memory: magnifier simplified" appears once; at L2 a persistent chip "Precision magnifier off (low memory)" appears and the Place-at-crosshair mode is offered.
20. UX-20 Degradation never changes the loupe mid-gesture and never shows a modal.
21. UX-21 An unexpected error inside the editor shows a banner with Retry and Close editor, and committed measurements remain.
22. UX-22 After an unexpected process kill the next launch shows one dismissible banner with View log; the banner does not repeat for the same record.
23. UX-23 The Logs exit line shows reason, importance, status, PSS and description, and can be copied.
24. UX-24 Back with only committed annotations closes the editor with no dialog.
25. UX-25 Back with N unfinished points shows "Discard N unfinished point(s)?" with Keep editing (default focus) and Discard; tap-outside keeps editing.
26. UX-26 If saving is pending or failed when leaving, the user sees "Saving..." or a retry banner and never silently loses data.
27. UX-27 A failed initial load shows a retry banner and does not delete saved measurements.
28. UX-28 A legacy calibration shows the text badge "Recalibrate for accuracy" and changes no stored value until the user acts.
29. UX-29 Export failure states (offline, not signed in, encode/OOM) each show a specific message and a next action; the Drive icon meets 4.5:1/3:1 contrast in both themes.

Loupe, placement, selection (S11-S13)
30. UX-30 On a 4:1 panorama the loupe stays inside the canvas and never crashes the app.
31. UX-31 Pressing within 2dp of any edge shows a filled loupe with no transparent area, and the crosshair stays under the touch point; the aim point can reach the image edge.
32. UX-32 First press shows a placeholder, not an empty circle.
33. UX-33 Placement works one-handed without dragging via Crosshair mode: 10 Nudge Right taps at the Fine step (1 screen pixel) then Place adds a point 10 screen pixels to the right of the start; the steps are screen-relative (ED-6); nudge buttons are >= 48dp with labels.
34. UX-34 Desktop users can nudge with arrow keys (Shift = 10 x the selected step), place with Enter and leave with Esc.
35. UX-35 Each commit gives one haptic tick and a "Measurement X added" announcement; two points < 0.2% apart show "Points too close" and create nothing.
36. UX-36 Selected annotation vertices have >= 48dp touch areas and a selected style that is not color-only; Delete and Undo are visible whenever something is selected.
37. UX-37 Delete shows a 6 s Undo snackbar; Undo restores it.
38. UX-38 Dragging off the canvas on release cancels the placement with no point created (included, OD-3).
39. UX-39 TalkBack exposes each committed annotation as a focusable button with a descriptive label and Select/Delete actions.

General
40. UX-40 No dead ends: every error banner, dialog, sheet and snackbar in S1-S15 has either an action or a dismiss path plus system Back.
41. UX-41 All new UI text meets 4.5:1 (text) and 3:1 (graphics) contrast in light and dark, and respects reduced-motion.

Library, upgrade, editor gestures and accessibility (added 2026-11-08; IDs appended, existing IDs unchanged)
42. UX-42 Library filter groups combine with AND, chips inside the Type and Tag groups with OR (LD-1); the Annotated chip shows only annotated items in pages of at most 100; a polite, debounced status line states the settled result ("7 annotated items"); [Clear filters] is visible whenever a filter or search is active.
43. UX-43 Tapping an annotated image opens the editor and any other card opens the detail; the Annotated badge is icon plus word; the card overflow always offers Details.
44. UX-44 A fresh install sees the first-run tip once; an existing install sees "Gallery and Assets are now Library..." once (never both); dismissal persists; a persistence failure never blocks the screen.
45. UX-45 The old Gallery and Assets routes (palette entries, global-search "Show all", saved screen, deep link) land on Library with the matching filter or search, and Back returns to the previous screen (no alias entry on the back stack).
46. UX-46 First-open task test: from the nav alone, a first-time user locates "my annotated photos" and "a file attachment" with zero wrong taps on the desktop sidebar and on the 411 dp phone shell.
47. UX-47 Library's Add menu offers Capture photo, Attach file and (Android only) Choose photo to annotate; there is no "Import from Gallery" stub; every Add item that is unavailable on a platform is absent with a documented reason.
48. UX-48 While the editor's main image is loading, the canvas shows "Opening photo..." with placement disabled; from 3 s it shows "Still opening..." with Back to Library; on a decode failure or at the 15 s ceiling it shows "Couldn't open this image." with Retry and Back to Library; Back works throughout (ED-13).
49. UX-49 Touch: a quick tap (< 120 ms, <= 8 dp) moves the crosshair and places nothing (or, with Quick tap = Place point, places at contact + Fingertip offset); a hold >= 120 ms or a move > 8 dp shows the loupe and lift places, with no upper time limit; lifting beyond the 12 dp edge tolerance off the canvas cancels; a second finger cancels; Select taps select; Crosshair-mode taps move the crosshair and place nothing. Every press maps to exactly one outcome.
50. UX-50 While any unfinished point exists a visible, labelled [Clear points] button (>= 48 dp) discards the unfinished points; Undo remains separate; no control is labelled a bare "Cancel" or "Done" (Cancel move, Cancel export, Finish, Deselect, Close are the names).
51. UX-51 With an accessibility service on, timed messages last at least the recommended timeout, and messages that carry an action never auto-dismiss.
52. UX-52 The editor's TalkBack focus order matches S18 in every state, and every interactive node is >= 48 dp.
53. UX-53 Showing or hiding the Developer section moves no row above it.
54. UX-54 Annotate on an orphan asset is disabled with a visible reason line "Add this file to a page to annotate it" and an [Open pages] button beside it (ED-8), in the Library card overflow, the detail screen and the palette flow; the editor never opens.
55. UX-55 The magnifier-side (handedness) setting is at Settings > Annotation > "Magnifier side" with an editor-overflow shortcut; Left mirrors the loupe offset.

Repair 4 additions (triad iteration 2; IDs appended, existing IDs unchanged)
56. UX-56 Library chips that would contradict the current filters (any non-image Type with Annotated or a tag) are dimmed, stay focusable, expose the reason in `stateDescription`, and a tap on one shows the reason in the reasons live region; Annotated with Orphaned is allowed; no combination of enabled chips produces a silent empty result (LD-1, LD-16).
57. UX-57 While a drag-to-aim is outside the cancel boundary the loupe dims to 40%, shows "Release to cancel" with an X icon, fires one haptic on entry, and announces politely once; a press hugging the image edge (inside the 12 dp tolerance) still places at the edge (ED-4).
58. UX-58 At 411 dp width or font scale >= 1.5 the Library filter bar is one [Filters (n)] button plus Search and Sort; the chips live in a bottom sheet with [Clear filters]; at 411 dp and 200% font scale the first card's top edge is visible without scrolling (LD-6).
59. UX-59 Desktop keyboard: every control shows a focus-visible ring on keyboard focus; Library grid arrow/Home/End/PageUp/PageDown move focus; chip groups are labelled and use roving focus; Back from editor or detail restores focus to the originating card (LD-9, ED-12).
60. UX-60 The editor shows "Opening photo..." then, from 3 s, "Still opening..." with [Back to Library]; the hard error appears only on decode failure or at 15 s; Retry cancels the previous decode and results of an older attempt are discarded (ED-13).
61. UX-61 Rotation, fold and unfold keep the editor's unfinished points, crosshair position (same image point), mode, tool, step, selection, open dialogs and sheets; an in-flight press is discarded without a point (ED-7).
62. UX-62 Rotation, fold and unfold keep the Library's filters, sort, search text, open filter sheet and scroll anchor (by item id); the grid column count and the compact-or-wide filter layout follow the new width (ED-7, LD-6).
63. UX-63 Crosshair steps are screen-relative (Fine 1 screen px, Medium 8 dp, Coarse 5% of canvas width, Exact 1 image px); press-and-hold auto-repeats after 400 ms; stopping announces the position as percentages, rate-limited (ED-6).
64. UX-64 With TalkBack or switch access enabled the editor starts in Crosshair mode (setting Auto) and the choice is remembered (ED-6).
65. UX-65 The placement hint and the Crosshair hint are shown once per hint version, including to existing users on their first editor open after the update, are announced politely, persist their dismissal, and are re-readable from editor overflow > Help: placing points (ED-5).
66. UX-66 Mouse and stylus clicks place at the pointer position at once; drags show the loupe and release places; Esc cancels a drag; hover shows the aim dot only (ED-3b).
67. UX-67 A low-memory thumbnail placeholder is a text-free skeleton tile with no error tint and the card's normal description; "File missing" is a broken-image icon with visible text and an error-tinted border; the two are visually and semantically distinct (ED-9).
68. UX-68 Until dismissed or Library is first opened, the Library row (sidebar, More sheet, bottom-bar More button) carries a New badge with a text equivalent for existing installs; Settings > About > What's new keeps the explanation findable (ED-16).
69. UX-69 Settings > Annotation offers Magnifier side, Quick tap, Fingertip offset (disabled with a reason unless Quick tap = Place point) and Start in Crosshair mode; the editor overflow offers Help: placing points, Magnifier side and Annotation settings; each item is >= 48 dp, closes with Esc/Back and has no dead end (ED-10).
70. UX-70 The Library Add menu and card overflow have defined states: camera permission denied, picker cancelled (silent), unreadable file, in-progress, failure with Retry; deleting a file asks for confirmation naming referencing pages and measurements (LD-10, ED-11).
71. UX-71 Editor snackbars show one at a time, an action-bearing one is never replaced (later ones queue, max 3), deletes coalesce, dismissal works by swipe, Esc and the TalkBack Dismiss action, and the recommended timeout applies even without a screen reader (ED-14).
72. UX-72 Reduced motion is read from `ANIMATOR_DURATION_SCALE` on Android and from Settings > Appearance > Reduce motion elsewhere, through an injectable provider (ED-15).

Repair 5 additions (triad iteration 3, final; IDs appended)
73. UX-73 After Add: a new item that matches the current filters is scrolled into view and outlined, announced politely; one that is filtered out shows the snackbar "Added {name}. Hidden by current filters." [Clear filters]; a non-default sort offers [Show] (LD-12).
74. UX-74 The status line follows the LD-17 templates for every combination of Type, Annotated, Orphaned, tags and search, singular for 1 item, "10,000+" at the cap; the count, the chip-reason message and the transient snackbar are three separate live regions.
75. UX-75 A quick tap that enters Crosshair mode switches the mode toggle visibly and announces the switch; with Quick tap = Place point there is no mode change (ED-18).
76. UX-76 In Crosshair mode a drag (from empty canvas or from the crosshair) moves the crosshair relatively, lift never places, and the layout keeps the canvas at least 200 dp and 40% of the window tall by falling back to a side rail or a [Nudge pad] sheet (ED-19, ED-21).
77. UX-77 Cancelling a placement works without using a screen edge: lifting over the toolbar region cancels, a system back gesture ends the press as a cancel, and a press that begins inside the system gesture inset places nothing (ED-20).
78. UX-78 Crossing 600 dp keeps the current destination and the Developer section state, dismisses an open More sheet on unfold, and moves focus to the current destination (ED-22).
79. UX-79 System Back in the editor closes the topmost layer first in the order of ED-24 and never leaves the editor while a layer, a press, a move or a selection is open.
80. UX-80 A permanently undecodable image shows a distinct "Can't preview" card and no retry loop; a low-memory skeleton and a missing file stay distinct from it (LD-18).
81. UX-81 An annotation with N vertices exposes TalkBack actions to select the next or previous vertex and move the selected one, plus direct "Move vertex i" actions when N <= 6 (ED-23).
82. UX-82 The upgrade badge and notice follow the LD-20 state table: the badge clears when Library is opened, the notice persists until dismissed, neither shows on a fresh install, and the state is per install.

Total: 82 UX acceptance criteria across 20 surfaces.

## Owner decisions and open design questions
All DECIDED on 2026-11-08, labelled one-to-one with the plan's Owner Decisions table:
- **OD-1** Crosshair / non-drag placement and move mode: **INCLUDED** (S12, UX-33/34/36/39, L2 "Place at crosshair" offer in UX-19); drag-to-aim stays the default. Tasks C.8c, C.8f, C.8g.
- **OD-2** Crosshair step selector (1 px / 10 px / 1% of width) and drag-the-crosshair: **included** with OD-1. Task C.8c.
- **OD-3** Off-canvas release cancels placement (UX-38, S11 first-use hint): **included** with OD-1. Tasks C.9a, C.9f.
- **OD-4** TalkBack "Move vertex N" actions (S13, UX-36/39) and the Drive-export offline message (S15, UX-29; message only, no retry queue): **included** with OD-1. Tasks C.8f, C.7a, C.7c.
- **ADR-006** Merge Assets and Gallery into one "Library": **DECIDED**.
- **LD-1..LD-20 and ED-1..ED-27** (section "Defaults - owner may override", Repair 4): defaults chosen by the agent, not owner decisions; ED-8 supersedes the owner's 2026-11-08 orphan-Annotate snackbar wording and ED-16 chooses the nav section for Library.

Other questions (not owner decisions):
2. Does autosave guarantee "saved up to the last placement" (S9, S10 copy)? (ADR-005 / C.5; the lines stay gated on the answer.)
3. Is the "reduced-resolution preview" message true (S8 step 4)? Omit if unconfirmed.
4. ~~Drive export: message only, or retry queue?~~ Decided: message only (OD-4).
5. ~~Handedness setting location~~ Decided: Settings > Annotation > "Magnifier side" with an editor-overflow shortcut (UX-55).
6. ~~Bottom-bar label "Pages" vs sheet row "All Pages"~~ Decided: bar "Pages" (short label), sidebar and sheet "All pages" (`shortLabelKey`, D.2b).
7. Does the "Library" title survive owner review? Working title; one I18n edit.

## Defaults - owner may override

Added by Repair 4 (triad iteration 2, 2026-11-08). These are decisions the triad found missing; each is a **default the agent chose** (not an owner decision) with the reason, so the owner can override any one without reopening the rest. "Override cost" names the task that changes. The plan, validation.md and ADR-006/ADR-008 cite these numbers.

### Library (LD)

| # | Default | Why | Override cost |
|---|---|---|---|
| LD-1 | **Filter groups and contradictions.** Three groups: **Type** (Images, PDFs, Audio, Video, Docs, Other; multi-select, **OR inside the group**, membership table LD-11), **Show** (Annotated, Orphaned; each an independent AND condition), **Tags** (multi-select, OR inside the group). Groups combine with **AND**; search is one more AND. There is no "All" chip: an empty group means no restriction ([Clear filters] resets everything). **Contradictory combinations are prevented by dimming, not by an empty state**: Annotated or any tag requires an annotated photo, so while either is on **every non-image Type chip (PDFs, Audio, Video, Docs, Other) is dimmed**, and while a non-image Type chip is on **Annotated and the tag chips are dimmed**. **Annotated with Orphaned is not a contradiction** (an annotation can outlive the last page that references its file, LD-16): both may be on and the result is annotated photos that are on no page. A dimmed chip stays focusable, announces its reason in `stateDescription` ("Unavailable: only photos can be annotated") and a tap shows that reason in the reasons live region (LD-17). Pure rule: `LibraryFilterRules.disabledReason(chip, state)` and `normalize(state)` (task D.6g). | An annotation belongs to an image, so these pairs are empty by construction; preventing them avoids a confusing zero-result screen, and one rule works in the wide bar and the compact sheet. Annotated plus Orphaned is reachable data, so blocking it would hide a real recovery case. Chosen over the "empty state with Clear conflicting filter" alternative because it needs no extra surface and is unit-testable as a pure function. | D.6g, D.6a |
| LD-2 | **Sort and paging.** Default **Recently added** (asset index time, newest first). Recently annotated = latest annotation time, newest first; Most measurements = measurement count (LD-15), highest first; Name A-Z = case-insensitive (ASCII fold, LD-3). **Unannotated items sort after annotated ones** for the two annotation sorts (nulls last), ordered among themselves by Recently added. Entering through the Gallery alias or "Show annotated photos" keeps the user's sort (default Recently added). **Paging is keyset, not OFFSET.** Every sort key tuple is non-null and ends with the item id: Recently added `(imported_at_ms DESC, item_id DESC)`; Recently annotated `(COALESCE(last_annotated_at_ms,-1) DESC, imported_at_ms DESC, item_id DESC)`; Most measurements `(annotated DESC, measurement_count DESC, imported_at_ms DESC, item_id DESC)`; Name A-Z `(lower(file_name) ASC, item_id ASC)`. A page request carries the last row's key tuple (`after`, null for page 1) and the query adds the expanded lexicographic predicate `k1 < a1 OR (k1 = a1 AND k2 < a2) OR ...` (no row-value syntax, so older Android SQLite works; `>` for ASC keys). Guarantee: an item that exists for the whole scroll is returned exactly once, in order, across pages; an item added or removed while scrolling appears or disappears on the next refresh, not mid-scroll. | Deterministic global order across pages is required by D.7; keyset paging also removes the O(offset) cost on an 8,000-row graph. | D.7a |
| LD-3 | **Search (one definition).** The query is trimmed and split into tokens on whitespace **and** on the separator set S = space, `_`, `-`, `.`, `,`, `/`, `"`, `[`, `]` (so `IMG_0012` is the tokens `img` and `0012`); at most 5 tokens count, extra ones are ignored. It needs **at least 2 characters after trimming**: 0 characters = unfiltered, 1 character = no search runs, the list is unchanged and the reasons region shows "Type at least 2 characters to search". **Match rule**: an item matches when **every token is a word-prefix match** in at least one of three fields: the file name (last path segment), any annotation tag, any annotation label (different tokens may match different fields). A word starts at the beginning of the field or after any character of S. Case-insensitive for **ASCII letters only** (SQLite `LIKE` without ICU; non-ASCII letters match with their exact case, so `é` does not match `É`; accepted v1 limit). `%` and `\` typed by the user are literal: each token is escaped for `LIKE` with `\` and `ESCAPE '\'` (`_` is in S, so it splits the token before escaping). 300 ms debounce. Not page names, not file contents. SQL shape: LD-7. | These are the three things a user remembers about a file or a measured photo (annotate-then-find-later job); page names are reachable through the page, and content search would be an unbounded scan. Word-prefix on every token gives "kit sink" the expected narrowing without a full-text index. | D.7a, D.7c, D.6g |
| LD-4 | **Persistence.** Filters, search text and the open filter sheet last for the session, including Back, rotation, fold/unfold and process-death restore (saved state); a fresh launch opens Library unfiltered. The **sort order is remembered across launches** (preference `libraryDefaultSort`). | Filters are a task state (stale filters on a new launch read as missing files), sort is a taste. | D.7b |
| LD-5 | **Count and live region.** The status line updates 400 ms after the last filter or search change and announces once per settled change (polite); page loads never announce. Wording: the templates of LD-17. The count is `countLibrary(query)`, capped at 10,000 and shown as "10,000+". | Avoids a flickering announcement per chip tap and bounds the cost of the count query. | D.6a, D.7a |
| LD-6 | **Compact-width pattern.** Below 600 dp window width **or** at font scale >= 1.5 the filter bar is one **[Filters (n)]** button (n = active chips), a Search field and Sort; the chips live in a modal bottom sheet (same pattern as More) with [Clear filters] and a live "Show {n} items" button; active chips are summarised as one text line under the bar. At 411 dp and font scale 2.0 the first card's top edge is visible without scrolling. | The triad estimated the full chip rows would use more than half of a 411 dp phone; a sheet avoids hidden horizontal scroll and wrapping. | D.6e |
| LD-7 | **Exact SQL-level semantics** (one definition for `LibraryFilterRulesTest`, `LibraryScreenFilterTest`, `LibraryFilterSqlParityTest` and D.7a; table and column names are VERIFIED against `SteleDatabase.sq`: `asset_index(uuid, file_path, relative_path, media_type, imported_at_ms, is_orphan, ...)`, `image_annotations(uuid, ..., imported_at_ms, tags TEXT JSON array default '[]')`, `measurement_annotations(image_uuid, label, ...)`; the join key from asset to annotation is fixed by spike D.7a-0). For a normalized `LibraryQuery`: `WHERE (:types empty OR media_type IN :types [LD-11]) AND (NOT :annotated OR EXISTS annotation row of the item) AND (NOT :orphaned OR is_orphan = 1) AND (:tags empty OR EXISTS annotation row whose tags contain any selected tag) AND <search> AND <keyset predicate of LD-2>`, `ORDER BY <LD-2 key tuple>`, `LIMIT :limit` (<= 100). **`<search>`** is, for each of up to 5 tokens `:p1..:p5` (NULL when unused): `(:pi IS NULL OR hay(file_name) LIKE :pi ESCAPE '\' OR EXISTS (SELECT 1 FROM image_annotations a WHERE <joined to item> AND hay(a.tags) LIKE :pi ESCAPE '\') OR EXISTS (SELECT 1 FROM image_annotations a JOIN measurement_annotations m ON m.image_uuid = a.uuid WHERE <joined to item> AND hay(m.label) LIKE :pi ESCAPE '\'))`, with `:pi = '% ' || esc(token) || '%'` built in Kotlin (`LibrarySearch.patterns(query)`, `esc` prefixes `%` and `\` with `\`) and `hay(x) = ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(x,'_',' '),'-',' '),'.',' '),',',' '),'/',' '),'"',' '),'[',' '),']',' ')`, so every field is searched as a space-separated word list that starts with a space and `% tok%` means word-prefix. `file_name` is the basename of `relative_path` (`substr(relative_path, length(rtrim(relative_path, replace(relative_path,'/',''))) + 1)`) unless spike D.7a-0 finds a name column. Tags are matched on the stored JSON text; `"`, `[`, `]` and `,` are in S, so each tag begins a word and no `json_each`/JSON1 dependency is needed. A leading-`%` pattern cannot use an index: the search path is a bounded LIMITed scan of the filtered range, `QueryPlanAuditTest` allows `SCAN` on that path only and asserts the LIMIT, and `countLibrary` is `SELECT COUNT(*) FROM (SELECT 1 ... LIMIT 10001)` (cap 10,000, displayed "10,000+"). A property test (`LibraryFilterSqlParityTest`) asserts that the in-memory reference filter and the SQL agree on random item sets and normalized states, with the dedicated search cases listed in validation.md (multi-word, `%`/`\` literals, 1 character, ASCII case, non-ASCII case). | One definition for UI, tests and SQL. | D.6g, D.7a |
| LD-8 | **Entry points normalize.** "Show annotated photos" and the Gallery alias clear every other filter and the search, then set Annotated; the AssetBrowser(q) alias and global-search "Show all" clear filters and set the search text. A saved or deep-linked state that arrives with a contradiction is passed through `normalize`: the explicit entry filter wins, conflicting chips are dropped, and a polite message names what was cleared. | Predictable results from every door; the task test depends on it. | D.11a, D.6g |
| LD-9 | **Keyboard and focus (desktop).** Arrow keys move between cards, Home/End jump to first/last, PageUp/PageDown move a screenful, Enter activates the tap rule, Shift+F10 or Menu opens the overflow, Esc in Search clears it. Each filter group is a labelled group ("Filter by media type", "Show", "Filter by tag") with roving focus (Left/Right inside, Tab leaves). A focus-visible ring (>= 2 dp, >= 3:1) shows on keyboard focus only. Back from the editor or detail restores focus to the originating card by item id (to the grid when the item is gone). | WCAG 2.1.1, 2.4.7 and TalkBack orientation; the scroll and filters already survive Back, focus must too. | D.6a, D.10a |
| LD-10 | **Per-asset overflow, delete, detail.** As in S19: Delete file asks for confirmation naming referencing pages and measurements, with progress, failure-with-Retry and no Undo; a missing non-annotated asset offers [Remove from Library] / [Keep]. | The merge surfaces the existing actions for annotated photos too; destructive paths need a stated consequence. | D.6f |
| LD-11 | **Type chip membership** (one pure function, `LibraryMediaClass.of(mediaType)`, used by SQL, reference filter and tests; VERIFIED against `AssetMediaType.fromMimeType`, `asset/AssetModels.kt`, which assigns the stored `asset_index.media_type` value): Images = `IMAGE` (MIME `image/*`); PDFs = `PDF` (`application/pdf`); Audio = `AUDIO` (`audio/*`); Video = `VIDEO` (`video/*`); Docs = `DOCUMENT` (any other `application/*`, for example Office files); Other = `FILE` **and any stored value not in the five above** (everything else, including `text/*` and a file whose MIME was unknown, so an image saved with an unknown MIME is Other and cannot be annotated: accepted). SQL: `media_type IN ('IMAGE')` etc.; Other = `media_type NOT IN ('IMAGE','PDF','AUDIO','VIDEO','DOCUMENT')`. No extension sniffing. | The enum already exists and is the only classification the index stores. | D.6g, D.7a |
| LD-12 | **After Add under an active filter or sort.** The added item's id is remembered. (a) It matches the current filters and search and sorts near the top (Recently added): the list reloads from the top, scrolls to the item and outlines it until the next interaction (static outline under reduced motion), polite announcement "Added {name}" and snackbar "Added {name} to {page}" [Open page]. (b) It does not match (filtered out, for example Annotated is on): the filters stay as they are and the snackbar reads "Added {name} to {page}. Hidden by current filters." [Clear filters]. (c) It matches but would sort deep or last (Recently annotated and Most measurements put unannotated files last; Name A-Z): no deep seek; the snackbar adds [Show], which sets the session sort to Recently added (the persisted `libraryDefaultSort` is not changed) and scrolls to the item. Snackbars follow ED-14. | The user must always learn where the file went without losing their filter context. | D.6f |
| LD-13 | **Page anchoring of captured, picked and attached files.** Every file added from Library is added to a page, so none is an orphan on arrival and every photo can be annotated (ED-8). The target is the page the user last viewed in the main pane (`lastViewedPageUuid`, persisted per graph); with none it is today's journal page (created through the existing journal service on first use). The existing capture, picker and attach pipelines append the embed block to that page; the target page name is shown in the success snackbar (LD-12) and in the empty state. A user who wants another page uses the page itself; moving a file between pages is not in v1. | ED-8 makes orphans non-annotatable, so an Add flow that created orphans would contradict it. | D.6f, D.6c |
| LD-14 | **Tag group overflow.** The Tags group shows the 8 most-used tags (usage count descending, then name), always preceded by the selected tags (which are not counted against the 8), then a [+N more] chip (N = further distinct tags from the bounded `getDistinctAnnotationTags(limit = 200)`, "200+" at the cap) that opens a sheet with a search field and a multi-select list (rows >= 48 dp). Chips wrap, never scroll horizontally; the compact filter sheet uses the same 8 + [+N more]. With no annotation tags the whole group is hidden. | Bounded rendering and a bounded query; an unbounded chip row would break at 8,000 annotations. | D.6a, D.7a |
| LD-15 | **Measurement count for an image on several pages.** A Library item is one asset (one card). `measurementCount` = the sum of `measurement_annotations` over all `image_annotations` rows of that asset (an image annotated from two pages has two annotation rows and one card); `annotated` = at least one annotation row; `lastAnnotatedAt` = the latest of their times (column resolved in D.7a-0). The card shows the total ("5 measurements"); a tap opens the annotation row with the latest time; the asset detail lists each page's count with its own [Open annotation]. Tag filter and search match any annotation row of the asset. | One file, one card; no double counting in sort or count. | D.7a, D.6b |
| LD-16 | **Annotated and orphaned.** An annotation can outlive the last page that references its file (`is_orphan = 1` and at least one annotation row). The item is shown with the Annotated badge and the caption "Not on any page"; [Open annotation] stays enabled, [Annotate] is not offered (already annotated), and Annotated + Orphaned filters combine (LD-1). | Hiding these items would lose measurements the user can still open. | D.6g, D.6b |
| LD-17 | **Status-line templates and live regions.** Template: `{count} {subject}{qualifiers}`. `count` is exact below 10,000, "10,000+" at the cap (read "more than 10,000"); "1 item" is singular. `subject`: "items"; with Annotated "annotated items"; exactly one Type chip uses its noun (images, PDFs, audio files, videos, documents, other files) and "annotated images" with Annotated; two or more Type chips keep "items" and add the qualifier "of type Images, PDFs". Qualifiers in this order joined by ", ": "not on any page" (Orphaned); "tagged {a}", "tagged {a} or {b}", "tagged {a} or {n} more" (1, 2, 3+ tags); "matching '{q}'" (q cut to 20 characters plus an ellipsis). Examples: "7 annotated items"; "1 item"; "12 images not on any page"; "3 annotated images tagged site or kitchen, matching 'sink'"; "10,000+ items of type Images, PDFs". Zero results show "No items" (the filtered-empty state carries the explanation). **Three separate live regions**: (1) the count line (polite, once per settled change); (2) the reasons region (polite): dimmed-chip reasons, "Type at least 2 characters to search", the entry-point normalization message (LD-8); (3) the transient snackbar region (Add results, LD-12). A reason never replaces the count. Test `LibraryStatusLineTest` (table of combinations) plus `LiveRegionPolitenessTest`. | The count must stay stable while reasons and results come and go. | D.6a |
| LD-18 | **Permanently undecodable image or thumbnail.** Distinguish by cause: an out-of-memory or low-memory condition = skeleton tile with retry on scroll idle (ED-9); a decoder exception for a file that exists = **Undecodable**: remembered for the session per item id plus file size and modified time, no automatic retry, the "Can't preview" card of S5, "Try again" in the card overflow; a missing file = "File missing". The editor on an undecodable file shows the ED-13 error state with the copy "This image can't be decoded. It may be damaged or in an unsupported format." and [Back to Library]; [Retry] is user-initiated only. | Without the state a corrupt file would loop through the retry-on-scroll path forever. | D.6b, D.6f, C.9g |
| LD-19 | **Library scroll anchor after process death.** Saved state holds the filters, search, sort, the first visible item id and its key tuple. On restore the ViewModel loads pages from the top until the anchor id is in the loaded set, at most 10 pages (1,000 items), then scrolls to it; if the item is gone or beyond that bound it stays at the top, silently. | Keyset paging has no cheap "jump to row N"; a bounded forward load is predictable. | D.6e, D.7b |
| LD-20 | **Upgrade badge and notice state machine** (per install, not per graph or user; keys `navIaVersion`, `libraryBadgeCleared`, `upgradeNoticeDismissed`, `libraryTipDismissed`; pure reducer `LibraryOnboardingState` in commonMain). Fresh install (no graph registered at first launch): `navIaVersion` = 1, tip shown on every Library open until "Got it", no badge, no What's new highlight. First launch after update (`navIaVersion` absent and a graph registered): `navIaVersion` = 1, badge shown, notice shown. **Opening Library clears the badge** (`libraryBadgeCleared`) but the **notice stays on every Library open until dismissed**. Got it, system Back on the notice and "Show annotated photos" set `upgradeNoticeDismissed` (and clear the badge if still set). Later launches show nothing; Settings > About > What's new stays. Adding or switching graphs changes nothing. Clearing app data restarts detection. A preference read or write failure shows badge and notice for the session (in-session dismissal respected) and logs. Test `UpgradeNoticeStateMachineTest` (every row). | The earlier text said both "first open only" and "until dismissed"; this fixes one behaviour per element. | D.9c, D.9d |

### Editor and cross-cutting (ED)

| # | Default | Why | Override cost |
|---|---|---|---|
| ED-1 | **Touch-target audit surface and handle rule.** `EditorTouchTargetAuditTest` audits **controls** only (toolbar, mode toggle, step selector, D-pad, buttons, chips, banner/dialog/snackbar actions, overflow items): >= 48 dp, >= 8 dp spacing. **Canvas hit areas are not audited as controls.** Canvas rule (`HandleHitResolver`, pure): a vertex handle beats any line body; among vertices within a 24 dp radius of the touch the **nearest wins**; a tie within 1 dp goes to the selected annotation's vertex, then to the last placed; beyond 24 dp from every vertex the touch selects the nearest line and does not drag. Vertices closer than the 48 dp areas are accepted, with the **equivalent non-drag path** Move vertex 1/2 (toolbar buttons and TalkBack actions, controls that do pass the audit). Rationale: WCAG 2.2 SC 2.5.8 (Target Size, Minimum) permits an exception where an equivalent control meeting the criterion exists; this is quoted from the WCAG text, not re-fetched in this run (UNVERIFIED wording, the exception's existence is VERIFIED knowledge of the spec). | 48 dp areas with 8 dp spacing cannot hold for two vertices of a short line; a resolver plus an equivalent alternative keeps the test meaningful instead of permanently red or silently exempt. | C.8e, C.9h |
| ED-2 | **Vocabulary.** Annotated (state), measurements (count noun only), unfinished points; buttons Clear points / Cancel move / Cancel export / Finish / Deselect / Close; no bare "Cancel" or "Done". The word to validate with real users is Annotated vs measured (ED-17). | Consistency across chip, badge, dialog and tooltips. | I18n keys only |
| ED-3 | **Tap and hold** (touch): thresholds 120 ms and 8 dp only. Quick tap (< 120 ms, <= 8 dp) moves the crosshair and enters Crosshair mode (never places from the raw fingertip, never shows the loupe; the switch is visible and announced, ED-18); hold >= 120 ms or move > 8 dp shows the loupe and lift places; no upper time limit, so the former 120-300 ms band is gone. Settings: Quick tap = Move crosshair (default) or Place point, with Fingertip offset 0-48 dp (default 0) applied to Place point. | A quick tap at the raw fingertip is the least accurate path and bypasses the loupe; making tap an aim step keeps precision and removes ambiguity. | C.9f, C.9l |
| ED-3b | **Mouse and stylus.** Pointer-type aware: a click places at the pointer position at once; a drag beyond 8 dp shows the loupe and release places; Esc cancels a drag; hover shows the aim dot, never the loupe; the stylus barrel button has no function in v1. | Pointers are precise; the touch rules would slow them down. | C.9j |
| ED-4 | **In-gesture cancel feedback.** Outside the cancel boundary (canvas bounds + 12 dp edge tolerance): loupe dims to 40%, label "Release to cancel" with an X icon, one haptic on entry and one on re-entry, one polite announcement per entry; releasing inside the tolerance clamps to the image edge and places. Hint copy says "Drag onto the toolbar or off the image to cancel" and the cancel boundary does not depend on a screen edge (ED-20). | Visibility of system status; keeps UX-31 (aim reaches the edge) safe. | C.9j |
| ED-5 | **Hints.** Placement hint and Crosshair hint are shown once per hint version (`annotationHintVersion`), **including to existing users on their first editor open after the update**; polite announcement; dismissal persisted; re-readable from editor overflow > Help: placing points. | Behaviour changed for people who already place points. | C.9a, C.9l |
| ED-6 | **Crosshair ergonomics and accessibility.** Screen-relative steps Fine (1 screen px, default), Medium (8 dp), Coarse (5% of canvas width), Exact (1 image px); press-and-hold auto-repeat after 400 ms at 12/s, stride doubling each second up to 8x; plus-shaped D-pad (cells >= 56 dp, centre = Place point) on the magnifier-side setting's side; nudges do not mirror in RTL; position announced politely as percentages (rate-limited 600 ms) and at edges; with TalkBack or switch access on, the editor starts in Crosshair mode (setting Start in Crosshair mode: Auto/Always/Never). | 1 image pixel on a 48 MP photo is below one screen pixel, so the earlier steps were invisible; drag is awkward under touch exploration. | C.8c, C.8i |
| ED-7 | **Configuration change.** Editor and Library state retained across rotation, fold and unfold as listed in S20; an in-flight press is discarded; tests `EditorConfigChangeTest`, `LibraryConfigChangeTest` plus a Pixel_Fold device pass. | The owner uses a Pixel Fold. | C.9k, D.6e |
| ED-8 | **Orphan Annotate** is **disabled with a visible reason line** and an [Open pages] button (no snackbar). This **supersedes** the owner's 2026-11-08 "enabled plus snackbar" wording because a tap that fails and relocates the user is a dead end; the owner may restore the earlier behaviour. A page picker that adds the file to a page was considered and rejected as a new data operation. | Error prevention. | C.9c, D.6d |
| ED-9 | **Thumbnail placeholders.** Low-memory = neutral skeleton tile, no text, no error tint, normal card description, retried on scroll idle; missing file = broken-image icon + visible "File missing" + error-tinted border. | A user must not think files vanished under memory pressure. | D.6b |
| ED-10 | **Settings > Annotation and editor overflow** as in S19, UX-69: Magnifier side, Quick tap, Fingertip offset, Start in Crosshair mode; overflow = Help: placing points, Magnifier side, Annotation settings, Recalibrate (legacy only). | New surfaces needed flows. | C.9l |
| ED-11 | **Library Add menu states** as in S19 (permission denied, cancelled silent, unreadable file, in progress). | Completes the flow table. | D.6f |
| ED-12 | **Desktop keyboard accessibility in the editor**: focus-visible ring on keyboard focus, labelled chip groups, Tab order = visual order, Esc leaves modes. | WCAG 2.4.7. | C.9h |
| ED-13 | **Editor load.** 0-3 s "Opening photo..."; from 3 s "Still opening..." with [Back to Library]; hard error only on decode failure or at a 15 s ceiling (replaces the 8 s timeout); generation-numbered decodes so Retry cancels the previous job and a stale result or failure is discarded. | A slow 48 MP decode on a low-end phone can exceed 8 s and then succeed. | C.9g |
| ED-14 | **Snackbars.** One at a time; informational ones replace each other; an action-bearing one is never replaced, later ones queue (max 3); deletes in one window coalesce into one Undo; dismiss by swipe, Esc, or the TalkBack Dismiss action; positioned above toolbar and D-pad; the recommended timeout applies whenever `getRecommendedTimeoutMillis` exceeds the nominal one, including with only "Time to take action" set; the save-failed banner stays and is announced assertively once. | Data-loss messaging and TalkBack stacking. | C.8h, C.9i |
| ED-15 | **Reduced-motion source.** Android: `ANIMATOR_DURATION_SCALE == 0` (the "Remove animations" option; INFERRED, verify on device in C.9d); other platforms: Settings > Appearance > Reduce motion (default off); injectable `ReducedMotionProvider`. | No single OS flag exists; tests need a provider. | C.9d, C.9i |
| ED-16 | **Nav section and upgrade reach.** Library is the first row of the **Graph** section (Daily, Connections, Graph, Developer): no one-item section, no new vocabulary, the files live in the graph. Risk: a cold user may not look under Graph for photos; the fallback is a header rename to "Files and graph" (one I18n edit) if two or more of the five cold-user sessions (ED-17) miss it. Existing installs get a New badge on the Library row (sidebar, More sheet, More button) until the notice is dismissed or Library is opened, and Settings > About > What's new keeps the explanation. | Sidebar-only users never open Library, so an inline notice alone is missed. | D.2b, D.9d |
| ED-17 | **Cold-user evidence.** 3 to 5 sessions with people who have not seen the app (owner-run, informal, think-aloud, on the phone shell): find annotated photos, find a file attachment, place a two-point measurement, undo an accidental point, and (for one session) re-find Library after dismissing the notice. Record wrong taps and time to first correct tap. Pass: at least 4 of 5 sessions find both destinations with at most one wrong tap, at least 4 of 5 place a correct measurement unaided within two minutes, and at least 4 of 5 find Clear points. Failures feed I18n/hint fixes (Annotated vs measured, header name, thresholds). Owner/device step (validation.md, W11). | `NavFirstOpenTaskTest` proves only that a path exists. | D.9e |
| ED-18 | **Quick tap mode switch is visible and announced** (amends ED-3). When a quick tap moves the crosshair and enters Crosshair mode, the mode toggle changes to Crosshair (check and fill), the live region says "Crosshair mode. Move the crosshair with the arrows, then tap Place point.", and the first time per hint version the Crosshair hint (ED-5) shows; switching back is the toggle. With Quick tap = Place point there is no mode change. Test `QuickTapModeSwitchTest`. | The switch is the surprising part; making it visible and announced removes the surprise without changing the aim-step design. | C.9f |
| ED-19 | **Crosshair-mode canvas gestures.** Tap on canvas: move the crosshair there, place nothing. Hold or drag starting on empty canvas (moved > 8 dp): **relative drag**, the crosshair moves by the finger's delta (it does not jump under the finger), the loupe shows under the crosshair; lifting does **not** place and there is no cancel zone in this mode. Drag starting within 24 dp of the crosshair: the same relative drag. A second finger or a pointer cancel ends the drag and leaves the crosshair where the last move put it. Only [Place point] (or Enter) places. | Without a rule, hold or drag in Crosshair mode was undefined and could place on lift. | C.8c |
| ED-20 | **Cancel versus the system back gesture.** (a) A touch whose down point is inside the left or right system-gesture inset (`WindowInsets.systemGestures`, at least 24 dp) is not a placement press: no point, no loupe. (b) While a placement press with the loupe is active the canvas sets `systemGestureExclusion` on a 24 dp strip at each vertical side centred on the finger, within the platform's 200 dp per-edge limit (Android 10+, gesture navigation only). (c) If the system still takes the gesture, the pointer cancel discards the press with no point, which is the cancel outcome. (d) **Cancel needs no edge**: the cancel boundary is the canvas bounds plus 12 dp measured anywhere in the window, so lifting over the toolbar or bottom region (reached by dragging down) cancels with the same feedback. Tests: `PlacementCancelZoneTest` (pure; the toolbar region is inside the cancel zone), `SystemGestureInsetPressTest`, `CancelZoneFeedbackTest` extended. | Dragging off a side edge on a phone with gesture navigation triggers Back. | C.9j |
| ED-21 | **Crosshair-mode layout minimum and fallback.** The canvas keeps at least 200 dp of height and 40% of the window height. Layout order: (1) bottom cluster (step selector + D-pad, about 240 dp) when the canvas rule holds; (2) landscape phone (window height under 480 dp and width at least 480 dp): the cluster moves to a side rail on the magnifier side, canvas at least 50% of the width; (3) font scale >= 1.5, or the rules above cannot be met: the cluster collapses to a 48 dp [Nudge pad] button that opens a non-modal bottom sheet (canvas stays visible above it; Place point stays reachable from the sheet and the toolbar). Test `CrosshairLayoutTest` at 640x360 landscape, 411x891 at font scale 2.0 and the 673 dp inner display. | Controls must not squeeze the canvas to nothing. | C.9m |
| ED-22 | **Nav shell swap on fold, unfold or resize across 600 dp.** As specified in S20: current destination and its state retained, Developer section state shared, an open More sheet dismissed on unfold, focus moves to the current destination in the new shell. Test `NavShellConfigChangeTest`. | The Pixel Fold crosses 600 dp. | D.4c |
| ED-23 | **TalkBack vertex actions for N-vertex shapes.** Every annotation node exposes "Select next vertex", "Select previous vertex" and "Move selected vertex (vertex {i} of {N})" (enters Crosshair move on that vertex, announcing "Vertex {i} of {N} selected"); when N <= 6 it also exposes direct "Move vertex 1" ... "Move vertex N" actions. Tests `MoveVertexActionsTest` for N = 2, 4, 6 and 12. | A 12-vertex area would otherwise add 12 actions to every node. | C.8f |
| ED-24 | **System Back precedence in the editor** (Esc follows the same order on desktop and additionally leaves Crosshair mode after step 5): (1) open overflow menu; (2) open dialog or sheet (Help, details, Delete confirm, Nudge pad); (3) an active press or drag (cancelled, no point); (4) a non-drag vertex move in progress (Cancel move); (5) a selection (Deselect); (6) unfinished points, which opens the S10 dialog; (7) a queued save, which shows "Saving..." first (S10); (8) leave the editor. A visible snackbar is not a layer (Back does not dismiss it). Test `EditorBackPrecedenceTest`. | Back must undo the smallest thing first and never discard silently. | C.9m |
| ED-25 | **Traversal and headings.** Banners and chips precede the canvas in traversal; the snackbar is traversed last and announced as a polite live region on arrival; headings and pane titles per the S18 row. Tests `EditorFocusOrderTest` (banner and snackbar states), `ScreenHeadingsTest`. | Banners carry state; a snackbar must not steal focus. | C.9h, D.10a |
| ED-26 | **Unfinished points and process death.** Unfinished points are retained across configuration change (C.9k) but, per ADR-005, **not** restored after process death. When the saved state shows that unfinished points existed, the restored editor shows a polite one-time note "Unfinished points weren't kept after the app restarted. Placed measurements are saved." Tool and selection are restored (C.9e). Test `EditorProcessDeathRestoreTest`. | Silent loss would look like a bug. | C.9e |
| ED-27 | **Contrast tie.** `ThemeContrastTest` includes: the "Release to cancel" label on the 40% dim scrim (text >= 4.5:1), its X icon (>= 3:1), dimmed Library chips (label >= 3:1 against the chip container, so a dimmed chip is still readable; the reason text in the reasons region >= 4.5:1), the selected-chip check icon, the "Can't preview" glyph and the New badge, in light and dark. | Dimmed states are where contrast is usually forgotten. | C.9d, D.6a |
