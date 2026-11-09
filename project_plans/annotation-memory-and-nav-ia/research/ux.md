# UX Research: Assets vs Gallery IA, nav grouping, annotation editor

Project: annotation-memory-and-nav-ia. Labels: VERIFIED = read in code or fetched from a source this run; INFERRED = reasoned, not confirmed.

Paths below are relative to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/` unless noted.

## 1. Current state in this app (VERIFIED from code)

### Navigation surfaces
- **Desktop/tablet sidebar** (`ui/components/Sidebar.kt:~310-358`): one header, "Navigation". Under it, ungrouped items separated only by 4dp spacers. Order: Journals, (New <Section> Journal), All Pages, Flashcards | Unlinked References, Notifications | **Gallery** (PhotoLibrary icon), **Assets** (FolderOpen icon) | Library Stats, Git Setup. Logs and Performance appear only when already on those screens. Favorites follow. There are no descriptions, no section headers inside "Navigation", and no tooltips.
- **Android bottom bar** (`androidMain/.../ui/PlatformBottomBar.android.kt:23-33`): Journals, Pages, Search, "More". "More" only calls `onToggleSidebar()`, so on phones Gallery and Assets sit behind a drawer-style sidebar that is the same flat list as desktop. A center gap is reserved for the voice FAB.
- **Command palette:** no Gallery or Assets commands found. Grep of `ui/` found only `StelekitViewModel.navigateToGallery()` (line ~1368), and no caller outside the ViewModel. The Assets screen is reachable from global search ("Assets" section, `ui/assets/AssetsSearchSection.kt`, and `GraphDialogLayer.kt:130` navigates to `Screen.AssetBrowser(q)`).
- **Onboarding/first-run:** `first-open-screens.txt` lists "Assets browser" only. Gallery is not in the first-open smoke list. I found no first-run copy that explains either screen.

### Gallery (`ui/gallery/*`)
- Data source: `ImageAnnotationRepository.getAllImageAnnotations()`, or by tag. This is **only images that already have an `ImageAnnotation` row** (a measurement/annotation record with calibration, unit, tags, sensor data, `capturedAtMs`, `importedAtMs`).
- Shows a sortable grid (date captured / date imported / measurement count) and tag filter chips. A tap opens the annotation editor. There is no search. The empty state reads "No annotated images yet - Capture a photo to start measuring distances, areas, and angles", with a "Capture Photo" button. The "Import from Gallery" button is never wired (`onImportPhoto = null`, "future work"). The error state is plain text, "Failed to load gallery: ...", with no retry.
- Entry points: only the sidebar item and `navigateToGallery()`.

### Assets (`ui/assets/*`)
- Data source: `AssetRepository`, which indexes the graph's assets folder. It holds any file (images, PDFs, audio, video, documents, other), including files attached to pages.
- Has search, filter chips (All / Images / PDFs / Audio / Video / Documents / Files / **Orphaned** / tag), grid/list toggle, and an action menu (move, tag, and so on). Detail view shows tags, "Pages" (backlinks), and ML status. The detail screen has an **Annotate / measure image** icon. It calls `resolveOrCreateAnnotation(asset)` and then opens the editor (`ScreenRouter.kt:402-408`).
- The empty states are copy-complete per filter, e.g. "No assets yet - Attach a file to any page to see it here".

### Overlap (the core finding)
- Gallery is a **subset view**. Annotating any image asset creates an `ImageAnnotation`, so after annotation the same file appears in both screens. Gallery is "images I measured". Assets is "every file in the graph".
- Neither name says that. "Gallery" suggests all photos, and the Android empty copy of "Import from Gallery" means the system photo picker, which adds confusion. "Assets" is Logseq jargon that a phone user would not recognise as "my attachments".
- Gallery has no path to non-measured photos, and Assets filtered to Images has no measurement info (count, calibration, tags from the annotation).
- Two tag systems may coexist (annotation tags vs asset tags). I did not verify whether they sync. **UNVERIFIED, flag for the planner.**

## 2. Comparable patterns (external)

| Product | Photo-centric view | File-centric view | Relationship / lesson |
|---|---|---|---|
| Google Photos vs Drive | Photos: backup, viewing, editing of photos/video; files only, no folders | Drive: general file storage, folders; no photo editor | Same storage pool, different jobs: "pictures I look at" vs "files I manage" ([Internxt](https://blog.internxt.com/google-photos-vs-google-drive/), [Zapier](https://zapier.com/blog/google-drive-vs-google-photos/)). Users still confuse them, which is the exact risk here. |
| Apple Photos vs Files | Photos: library of media; Files: documents and folders | | Same split. INFERRED from general knowledge, not fetched. |
| Obsidian | Attachments are files in the vault, shown in the file explorer and in a core "Attachment" setting. No photo-centric view. | | Plain file-centric only. INFERRED, not fetched. |
| Logseq | "All assets" listing (file-centric) | | Same as this app's Assets. The jargon "assets" originates here (INFERRED). |
| Notion, Evernote, Bear | Attachments are inline in notes; Evernote/Bear offer attachment search/filter inside notes | | No separate photo gallery. Media is discovered through notes. INFERRED. |

Takeaways:
1. Products that split "photos" from "files" always describe the split by **job** (look at / manage) and make one the default entry point. Both are never equal top-level peers without help text.
2. Note apps that serve both (Notion, Evernote, Bear) avoid the split by treating media as note attachments plus a type filter. That is the "merge with filters" model.
3. The Photos/Drive confusion in the sources above is evidence that a two-destination split needs a visible description.

### Navigation structure guidance
- Material 3 navigation drawer: optional short **section labels** (subheads) group related destinations; full-width dividers separate groups, not single items; labels must be short enough not to truncate; recommended for 5 or more destinations or more than one level; do not combine with a navigation bar. Material now says the drawer is **no longer recommended** in M3 Expressive and suggests an expanded navigation rail instead ([drawer guidelines](https://m3.material.io/components/navigation-drawer/guidelines), [rail](https://m3.material.io/components/navigation-rail/guidelines), [bar](https://m3.material.io/components/navigation-bar/guidelines)). Search result summary; I did not read the spec pages in full.
- Implication for this app: the Android pattern here (bottom bar plus a drawer under "More") already conflicts with the M3 advice "do not combine drawer with navigation bar". A grouped "More" **sheet or full-screen list** (Settings-style grouped list with a headline plus supporting text per row) is the more M3-aligned option for phones. On desktop, an expanded rail or sidebar with section headers is fine.
- Material list items support a two-line "headline + supporting text" layout, which is the standard way to carry descriptions without tooltips (INFERRED from M3 list component; not fetched).

## 3. Mental models and jobs-to-be-done (owner persona)

Persona (from requirements): a solo user who photographs things to measure them, and keeps attachments in a Logseq-style graph on phone and desktop.

| JTBD | Where it is today | Friction |
|---|---|---|
| "I just took or imported a photo and want to measure on it" | Gallery empty-state "Capture Photo" or page attach, then Annotate | Entry is split between Gallery and Assets detail icon |
| "Find that photo I measured last week" | Gallery (sort by date captured, tags) | OK, but Gallery does not show unannotated photos |
| "Find the PDF/file I attached to a page" | Assets (filter, search) | OK; the name "Assets" is jargon |
| "Which photos did I attach but never measure?" | Assets, Images filter | No way to see annotated vs not |
| "Clean up unused files" | Assets, Orphaned filter | Discoverable only if you open Assets |
| "Where is this image used?" | Asset detail, Pages | Fine |

Likely mental model: **"Photos I measured" and "files I attached"** are two jobs. The user will mostly start from the first. Whether the owner agrees is an owner question.

## 4. Accessibility (INFERRED from guidance; code checks noted)

- **Touch targets:** WCAG 2.2 SC 2.5.8 (AA) requires 24x24 CSS px, or spacing that substitutes; Android/Material guidance is 48x48dp ([W3C understanding doc](https://raw.githack.com/w3c/wcag/main/understanding/22/target-size-minimum.html), [Deque](https://docs.deque.com/devtools-mobile/2025.7.2/en/android-touch-target-spacing)). Target 48dp for nav rows and for the loupe/select handles. Note the editor draws points; confirm point hit areas are at least 48dp (not verified).
- **Descriptions:** put them as visible supporting text in the row (headline + 1 line) rather than tooltips. Tooltips are hover-only, so phones and TalkBack users never see them. Visible text is read as part of the row, and no extra focus stop is needed. Keep each description to about 6-10 words and make sure it is not clipped at 200% font scale (allow 2 lines).
- **Screen-reader order:** the order should follow visual order: section header, then the rows in it. Mark section headers with `semantics { heading() }`. Selected state must use `selected` semantics, not just colour. Icons beside text rows should use `contentDescription = null` (decorative), and the bottom-bar icon labels are duplicated today (`Icon(contentDescription = item.label)` plus `Text(item.label)`; INFERRED: a duplicate announcement risk, worth a TalkBack check).
- **Gallery card:** `contentDescription = "Annotated image - tap to open editor"` is identical for every card (`GalleryScreen.kt:177`). Include date, tag, or measurement count so cards are distinguishable. Assets cards use the file name, which is better but may be a UUID-like name.
- **Canvas:** the editor canvas is labelled "Annotation canvas - tap to place points" (`AnnotationEditorScreen.kt:999`). Touch placement with a loupe has no non-touch alternative; WCAG 2.2 SC 2.5.7 (Dragging Movements) wants a single-pointer alternative for drag. Suggest a "place at center crosshair" mode or nudge buttons for select/move. INFERRED, owner-priority question.
- **Colour:** the Google Drive export icon uses hard-coded blue `0xFF1565C0` (`AnnotationEditorScreen.kt:850`); check contrast in dark theme.

## 5. Error and edge states

| State | Gallery today | Assets today | Recommendation |
|---|---|---|---|
| Empty | Camera icon, copy, Capture button; Import button unwired | Per-filter copy, no action button | Add an action to Assets empty ("Attach a file" or "Import"); wire Gallery's import or remove the dead parameter; point Gallery's empty state to Assets ("Already have photos? Open one in Assets and tap Annotate") |
| Large sets (thousands) | Loads all annotations via `getAllImageAnnotations()` as a standing flow (INFERRED: unbounded; check against CLAUDE.md "graph-scale reads must be bounded") | Has status line; paging not checked | Paginate or lazy-grid with thumbnails capped; part of the memory benchmark scenario (f) |
| Error | Red text, no retry | `Error: $err` | Add a Retry action and a plain-language message |
| Offline | Not applicable (local) except Drive export | Same | Drive export should say "Offline - saved locally, will retry" (INFERRED requirement) |
| Permissions | Storage/camera denial not surfaced in these screens (INFERRED; `PermissionRecoveryScreen` exists) | Same | Link to the existing permission recovery screen from the empty state when the cause is a denial |
| Missing file (annotation row exists, file gone) | Not verified | Orphaned filter covers unreferenced, not missing | Show a placeholder tile plus "File missing" |

## 6. Annotation editor UX

Observed in code (VERIFIED): the loupe is a 120dp view (`LoupeOverlay`, `AnnotationEditorScreen.kt:~1610`) that shows a zoomed region, with a decode per press sized by `loupeDecodeSize`. It is placed above the finger, flipping below near the top edge, and is clamped to the canvas with margins. Comment at line 1060: "drag to the crosshair, release to place, at any distance."

Expectations from comparable precision-pointing UIs (INFERRED from general practice, not cited):
- **Finger occlusion:** offset the magnifier above the contact point (done) and keep the aim point visible via a crosshair. Keep the loupe off the finger path when the finger is near the top (done via flip).
- **Drag-to-aim, release-to-place:** this is the right model. Add a cancel gesture (drag out of the canvas, or a second finger, or tap Undo) so that a mistaken press does not create a point.
- **Edge behaviour:** the aim point must be allowed to reach the image edge even when the loupe is clamped. The crosshair should keep tracking the finger even though the loupe window is clamped (reported misalignment fixed in #558/#560; verify with `LoupeAlignmentE2ETest`).
- **Select/move:** selected points need a visible handle of at least 48dp touch area, a selected-state style that is not colour-only, and the same loupe while dragging. Provide Delete and Undo in a visible place. Haptic tick on place and on snap.
- **Back-to-back placement:** show a transient confirmation (point count) rather than modal feedback, and do not block on the next press.

### Low-memory degradation messaging
Principles: never crash silently; degrade in steps; say what happened once, plainly.
1. **Step 1, silent:** shrink the loupe decode size, reduce zoom, then reuse a single bitmap. No message.
2. **Step 2, inline, non-modal:** show a snackbar or a small chip, "Low memory: magnifier simplified." Use `stateDescription` so TalkBack announces it once. The chip should be dismissible.
3. **Step 3, loupe off:** fall back to placing without a loupe, with a persistent "Precision magnifier off (low memory)" chip. Offer "Close other apps" guidance only here.
4. **Step 4, image too large:** "Showing a reduced-resolution preview; measurements still use the original" (only if that is true; confirm with the engineers).
5. **After a kill:** `PreviousSessionLog` already replays the exit reason in the Logs screen. Add a one-time launch banner: "The app closed unexpectedly while annotating. Your points were saved up to the last placement" (only if autosave guarantees this; it is **UNVERIFIED** here).
6. Log the degradation level through `Logger` so it reaches the persisted sink (no logcat).
No `onTrimMemory`/low-memory hook was found in `commonMain`/`androidMain` for the editor (grep found none for the annotate path). That is a gap for engineers.

## 7. IA options for Assets vs Gallery

### Option A: Keep both, add sections and descriptions
- Sidebar and "More" sheet: a "Library" group with **Photos** ("Photos you've measured or annotated") and **Files** ("Everything attached to your pages") as rows with supporting text. Keep data models unchanged.
- Pros: smallest change, no deep-link or fixture change (aside from the label strings and `first-open-screens.txt`), reversible. Meets the success metric on its own.
- Cons: keeps two overlapping destinations; users must still learn the distinction; renaming strings touches tests that match "Gallery"/"Assets".

### Option B: Merge into one "Library" with filters
- One destination with a segmented control or chips: All / Photos (annotated) / Images / PDFs / ... / Orphaned. Annotation info (measurement count, tags) shows on image tiles.
- Pros: one mental model (Notion/Evernote style); no question of "which one"; annotated state is visible on every image.
- Cons: biggest change. Merges two ViewModels and two data sources (annotation rows and asset rows); sort orders conflict (date captured vs file added); touches `Screen.Gallery`, `Screen.AssetBrowser`, deep links, command-palette and search routes, tests, and `first-open-screens.txt`. Memory risk: a single large grid with mixed media. Also a risk of losing Gallery's focused measuring flow.

### Option C: Rename and re-scope (recommended middle path)
- **Rename** Gallery to "Measured Photos" (or "Annotations") and Assets to "Files" (or "Attachments"). Re-scope so the Files screen links to its annotation: an "Annotated" badge on image tiles, an "Annotated" filter chip, and an "Open in Photos" action; Photos' empty state links to Files. Keep both destinations and data models.
- Pros: removes jargon ("Assets", "Gallery"), makes the overlap explicit, small code change (labels, one chip, one badge, empty-state links), keeps both screens' strengths, and leaves a clean path to Option B later.
- Cons: still two destinations; the badge needs the asset-to-annotation join (a `resolveOrCreateAnnotation`-style lookup, not verified to be cheap at thousands of assets; must respect bounded reads).

### Recommendation
**Option C, delivered inside Option A's grouped navigation.** Keep two destinations, rename them by job, add the "Annotated" cross-link, and group the nav with descriptions. Revisit a merge only if usage shows the owner moves between them often. Names are an owner decision; alternatives: "Photos" / "Files", "Measurements" / "Attachments".

## 8. Proposed grouped navigation and copy

Section headers use `heading()` semantics; each row is headline plus one line of supporting text; rows have a 48dp minimum height.

**Daily** (what you open every day)
- Journals: "Today's notes and past days"
- All Pages: "Browse and search every page"
- Flashcards: "Review cards from your notes"

**Connections**
- Unlinked References: "Mentions that aren't linked yet"
- Notifications: "Suggestions and sync alerts"

**Library** (media and files)
- Measured Photos (was Gallery): "Photos you've annotated or measured"
- Files (was Assets): "Everything attached to your pages: images, PDFs, audio"

**Graph** (maintenance)
- Library Stats: "Size and health of this graph"
- Git Setup: "Back up and sync with Git"

**Developer** (hidden unless on screen, as today)
- Logs: "App activity and crash history"
- Performance: "Frame and memory metrics"

Android: bottom bar stays Journals / Pages / Search / More. "More" opens a **modal bottom sheet or full-screen grouped list** with the same sections (rather than reusing the sidebar drawer), so phone and desktop share one `NavSection` data model. Command palette: add "Go to Measured Photos" and "Go to Files" commands, and a "Annotate a photo" command that opens the picker. First-run: a one-time tip on first Library open, e.g. "Photos you measure show up here. Every attached file lives in Files."

Empty-state copy drafts:
- Measured Photos: "No measured photos yet. Capture or pick a photo, then add points to measure it. You can also open any image in Files and tap Annotate."
- Files: "No files yet. Attach an image, PDF, or audio file to a page and it will appear here."

## 9. Open questions for the owner / planner
1. Preferred names (Option C labels).
2. Do annotation tags and asset tags need to be unified?
3. Is annotation autosave guaranteed per placement (for the post-crash banner copy)?
4. Should Gallery list unannotated images?
5. Add a non-drag alternative for placement/move (WCAG 2.5.7)? Owner priority.
6. Material's drawer deprecation: confirm that moving phone "More" to a sheet is in scope for this project.

## Sources
- [Material 3 navigation drawer guidelines](https://m3.material.io/components/navigation-drawer/guidelines)
- [Material 3 navigation rail](https://m3.material.io/components/navigation-rail/guidelines)
- [Material 3 navigation bar](https://m3.material.io/components/navigation-bar/guidelines)
- [WCAG 2.2 Target Size (Minimum) understanding](https://raw.githack.com/w3c/wcag/main/understanding/22/target-size-minimum.html)
- [Deque Android touch target spacing](https://docs.deque.com/devtools-mobile/2025.7.2/en/android-touch-target-spacing)
- [Google Photos vs Drive (Internxt)](https://blog.internxt.com/google-photos-vs-google-drive/)
- [Google Drive vs Photos (Zapier)](https://zapier.com/blog/google-drive-vs-google-photos/)
