# Findings: Features — UX Patterns in Comparable Android Apps

**Authored**: 2026-04-22
**Input**: `project_plans/android-features-integration/requirements.md`

---

## Summary

Quick-capture entry points (widget, Quick Settings tile, share target) are a solved problem in the productivity-app space, but implementations vary widely in capture speed, reliability, and UX polish. The fastest apps (Google Keep, Todoist) achieve a note or task saved in 1–3 taps from the home screen. The slowest (Obsidian, Joplin) require 4–7 steps or a third-party workaround. Share-target UX best practice has converged on a lightweight overlay (bottom sheet or dialog) that pre-fills the shared content and dismisses without loading the full app. Quick Settings tiles are niche but high-value for power users operating with their phone screen locked or half-open. For SteleKit — a Markdown outliner with a daily-journal workflow — the key design pressure is: **preserve capture speed even when the graph is not loaded**, because Markdown graphs can take 1–3 s to parse on first open.

---

## Options Surveyed

### Google Keep

**Widget**: Two types as of 2025 — "Quick capture" (action launcher, 4×1 or 5×1) and "Single note" (display widget). The Quick capture widget presents icon buttons for text, list, audio, image, and drawing notes. Each button opens a focused compose screen with the keyboard raised immediately. **Taps to saved note: 2** (tap widget button → type → auto-save on back/home).

**Quick Settings tile**: Not a first-party feature.

**Share target**: Appears in the system share sheet. Tapping opens a small "Save to Keep" translucent overlay activity with the shared text or URL pre-filled. **Taps to saved note: 2** (tap Keep in share sheet → tap Save).

**UX strengths**: Keyboard raised immediately on tap; auto-save on exit means no explicit "Save" button needed; share overlay is non-disruptive.

---

### Todoist

**Widget**: Multiple types including "Add task" (single button). Opens a bottom-sheet Quick Add dialog with smart date parsing and project selector. **Taps to saved task: 2** (tap button → type → tap confirm). [TRAINING_ONLY — verify exact flow]

**Quick Settings tile**: One of the few productivity apps with an official "Add task" tile. Swiping down and tapping the tile opens Quick Add. **Taps to saved task: 2** (swipe down → tap tile → type → confirm).

**Share target**: Registered as a share target. Shared URLs or text are pre-filled into Quick Add as the task name. **Taps to saved task: 2** (share → tap Todoist → tap Save).

**UX strengths**: Tile is a first-class feature; Quick Add is consistent across widget, tile, and share (one mental model).

**UX weaknesses**: Tile requires user to manually add it (Android 13+ prompts help); Quick Add is title-only, not rich text.

---

### Obsidian

**Widget**: No first-party Android widget as of mid-2025. [TRAINING_ONLY — verify] Community workarounds exist (Fleeting Notes, Zettel Notes, Automate flows). **Taps to saved note with workaround: 3–5**.

**Quick Settings tile**: Not available natively. Requires Tasker/MacroDroid hacks.

**Share target**: Registered as a share target but opens the full app — no lightweight overlay. Vault must load before the editor is ready (several seconds on large vaults and mid-range hardware). **Taps to saved note: 3** (share → tap Obsidian → wait for vault load → tap Save).

**UX weaknesses**: Vault load time kills capture speed; no first-party widget; no tile. User forums document significant frustration. [TRAINING_ONLY — verify current forum sentiment]

---

### Notion

**Widget**: Released a Quick Add widget for Android in February 2023; also supports AI shortcuts on select plans. **Taps to saved note: 2** (tap widget → type → tap Save). [TRAINING_ONLY — verify current behavior]

**Quick Settings tile**: Not a documented first-party feature. [TRAINING_ONLY — verify]

**Share target**: Registered as a share target but opens a destination picker before saving (which workspace? which page?). **Taps to saved note: 3–4** (share → tap Notion → select destination → tap Add).

**UX weaknesses**: No fixed inbox means every share requires a routing decision; app load time is high (web-based client).

---

### Bear (iOS-only — reference only)

**Widget**: A single-tap floating button that raises the keyboard immediately with no intermediate screen. Note auto-saves on dismiss. Uses a translucent activity equivalent with `stateAlwaysVisible`. **Taps to saved note: 1** (tap widget + type = saved on dismiss).

**UX lessons**: Bear achieves the minimum possible friction. The Android equivalent is a widget button that triggers a lightweight `Activity` with `windowIsTranslucent = true` and `android:windowSoftInputMode = stateAlwaysVisible`.

---

### Drafts (iOS-only — reference only)

**Widget**: Always opens to a new blank draft with keyboard raised. **Taps to saved note: 1** (tap widget — keyboard is already up).

**UX lessons**: Drafts' "capture first, organize later" philosophy avoids the destination-picker problem entirely. Every capture goes to an inbox; the user routes it when they have time. For SteleKit, today's daily journal page is the natural equivalent inbox.

---

### Joplin

**Widget**: No first-party widget. Community third-party widget exists. **Taps to saved note: 3–5** (tap third-party widget → compose → sync). [TRAINING_ONLY — verify current official widget status]

**Quick Settings tile**: Not available.

**Share target**: Registered as a share target. Shared content opens inside the full Joplin app. **Taps to saved note: 3** (share → tap Joplin → tap Save).

**UX weaknesses**: Absence of official widget is a frequently cited complaint; long-standing forum thread with significant votes and no official response.

---

### Simplenote

**Widget**: Basic "New note" widget. Opens the app to a blank new note with keyboard raised. **Taps to saved note: 2** (tap widget → type → auto-save on back).

**Quick Settings tile**: Not available.

**Share target**: Registered as a share target. Shared text becomes the body of a new note with no routing step (no notebook picker). **Taps to saved note: 2** (share → tap Simplenote → auto-save).

**UX strengths**: Zero routing friction — inbox model; fast because data model is trivially simple.

---

## Trade-off Matrix

| App | Taps to Save (widget) | Taps to Save (share) | Tile Available | Offline Reliable | Visual Complexity | Customizable Entry Point |
|---|---|---|---|---|---|---|
| Google Keep | 2 | 2 | No | Yes | Low | Low (fixed icons) |
| Todoist | 2 | 2 | **Yes (official)** | Yes | Medium | Medium (project picker) |
| Obsidian | 3–5 (workaround) | 3 | No | Delayed (sync) | High | High (plugin ecosystem) |
| Notion | 2 | 3–4 | No | Partial | High | Low |
| Bear (iOS) | 1 | N/A | N/A | N/A | Low | Low |
| Drafts (iOS) | 1 | N/A | N/A | N/A | Low | High |
| Joplin | 3–5 (workaround) | 3 | No | Delayed (sync) | Medium | Low |
| Simplenote | 2 | 2 | No | Yes | Low | Low |

**Key insight**: Apps that achieve 2-tap capture share a common pattern — a lightweight compose overlay that saves to a fixed inbox location with no routing decision required. Apps that require routing (Notion) or vault initialization (Obsidian, Joplin) pay a 1–3 tap penalty and measurable user frustration.

---

## Risk and Failure Modes

**App process killed**: Android aggressively kills background processes. A widget that relies on the main app process being alive will silently fail or lag on low-memory devices. The capture path must work as a standalone lightweight `Activity` that does not depend on the graph being preloaded.

**Graph not yet parsed**: SteleKit parses Markdown graphs on first open. If the widget capture activity tries to initialize the full graph before allowing the user to type, capture latency will be 1–5 seconds on large graphs. Correct pattern: accept input first, defer graph insertion to background.

**Quick Settings tile discoverability**: Invisible until manually added. On Android 13+ the OS shows a system prompt offering to add the tile on first use, but only if the app calls `StatusBarManager.requestAddTileService()`.

**Share intent data formats**: Share targets receive `ACTION_SEND` with `EXTRA_TEXT`, `EXTRA_SUBJECT`, and/or `EXTRA_STREAM` (for images/files). Apps that only handle `EXTRA_TEXT` silently drop image shares.

**Share overlay dismissed accidentally**: A translucent overlay can be dismissed by the user tapping outside it, losing unsaved content. Note content must be auto-saved on every keystroke or committed on any dismiss path (back button, outside tap, home gesture).

**Offline capture**: Write locally first; sync asynchronously. Keep and Simplenote succeed here; Obsidian-style sync-before-save loses offline captures.

**Android 14 share sheet changes**: `ChooserTargetService` (old API) was removed. Apps must use `ShortcutManagerCompat.pushDynamicShortcut()` with `SHORTCUT_CATEGORY_SHARE` for Direct Share.

---

## Migration and Adoption Cost

**Widget**: Jetpack Glance capture widget. Estimated effort: 3–5 days including setup and launcher testing.

**Quick Settings tile**: `TileService` subclass. Estimated effort: 1–2 days.

**Share target**: `Activity` with `intent-filter` + `ShortcutManagerCompat` for Direct Share. Estimated effort: 2–3 days for basic share + 1–2 additional days for Direct Share shortcuts.

**Shared capture Activity** (used by all three): Built once, launched by widget, tile, and share intent. Estimated effort: 2–3 days.

**Total estimated implementation cost**: 6–10 days of Android-focused engineering.

---

## Operational Concerns

**Widget resize behavior**: Define `minWidth`/`minHeight` and gracefully degrade at smaller sizes (e.g., collapse to a single "+" icon at 1×1).

**Accessibility**: Widget tap targets must be at least 48×48 dp. Screen reader (TalkBack) labels must be set. Tiles must include a content description. Share target activities must be keyboard-navigable.

**Dark mode**: Widgets follow system dark/light mode. Glance supports `ColorProviders` for adaptive theming.

**Battery impact**: A capture-only widget has no polling requirements. If a display widget is added later, use `WorkManager` for periodic updates with minimum 15-minute intervals.

**App update path**: Widget layouts cached by the launcher can persist after an APK update. Define a `widgetConfiguration` activity that resets widget state on upgrade to prevent stale UI.

---

## Prior Art and Lessons Learned

1. **Todoist tile as the benchmark**: Opens Quick Add (a lightweight dialog), not the full task list. The tile does not toggle state. This is the correct pattern for a capture tile.

2. **Bear/Drafts philosophy**: "Capture first, organize later" — every capture goes to an inbox. SteleKit's natural equivalent: the daily journal page. All quick captures should default to appending to today's journal page.

3. **Google Keep's share overlay**: The translucent activity overlay that dismisses back to the originating app is the correct share-target UX. Users cite frustration with apps (Obsidian, Notion) that replace the current context with a full app session.

4. **Obsidian's community workarounds reveal unmet demand**: The large third-party widget ecosystem for Obsidian demonstrates that quick-capture is a high-priority user need even for power users. SteleKit shipping first-party support immediately differentiates it from Obsidian on Android.

5. **Simplenote inbox model**: No folder picker → 2-tap capture → zero friction. SteleKit mitigates organizational inflexibility by defaulting to today's journal (a natural inbox) while exposing a page picker as a secondary option.

---

## Open Questions

- [ ] **Default capture destination**: Should the widget/tile always append to today's daily journal page, or should there be a per-widget configurable destination? Blocks: widget/tile interaction model.
- [ ] **Offline write strategy**: When the graph is not yet loaded, where does the captured note go? Option A: "pending captures" file merged on next graph load. Option B: Open full app but defer graph initialization until after user types. Blocks: capture Activity architecture.
- [ ] **Direct Share targets**: Should SteleKit expose specific note pages (e.g., "Daily Journal", recent pages) as Direct Share targets? Blocks: `ShortcutManagerCompat` integration scope.
- [ ] **Voice capture**: Out of scope for v1; worth flagging as high-value for v2. Does not block current planning.
- [ ] **Tile discoverability prompt timing**: When should `StatusBarManager.requestAddTileService()` fire? Options: first launch, after first note saved, from Settings screen. Blocks: tile onboarding UX.

---

## Recommendation

### Widget

**Jetpack Glance capture widget** with minimal footprint: a single "+" button at 1×1, expanding to action buttons at 2×1 or wider. **Default destination: today's daily journal page.** Widget button opens a lightweight `Activity` with:
- `android:theme="@style/Theme.Translucent"` (overlay, not full screen)
- `android:windowSoftInputMode="stateAlwaysVisible"` (keyboard raised immediately)
- A single multi-line `TextField` pre-filled with nothing
- A "Save" button and a "Choose page" secondary action
- Auto-save on any dismiss path (back, home, outside tap)

The capture Activity must **not** block on graph initialization. Write captured text to a local "inbox buffer" (SharedPreferences or DataStore entry) and process it into the graph asynchronously via a `WorkManager` job. This guarantees offline reliability and sub-200 ms time-to-type from widget tap.

### Quick Settings Tile

**`TileService`** whose single behavior is: tap → open the shared capture `Activity`. Tile icon: simple pencil or "+" symbol. Call `StatusBarManager.requestAddTileService()` after the user saves their first note (reward-at-success timing). No toggle state required.

### Share Target

Register the capture `Activity` for `text/plain`, `text/html`, and `image/*`. On receive:
1. Pre-fill the text field with `EXTRA_TEXT` (and `EXTRA_SUBJECT` as a note title if present).
2. Show a compact destination row (today's journal pre-selected; recent pages as chip alternatives).
3. On Save, append to the selected page via the inbox buffer → WorkManager path.
4. `finish()` and return to the originating app.

Register `ShortcutManagerCompat` Direct Share shortcuts for today's journal page and up to 4 recently visited pages, updated on each graph load.

### What to avoid

- Do not open the full app UI on widget tap, tile tap, or share — use the overlay Activity.
- Do not require graph initialization before the keyboard appears.
- Do not show a destination picker by default — default to today's journal and make routing opt-in.
- Do not use `AlarmManager` for widget updates — use `WorkManager`.
- Do not block on sync before marking a capture as "saved" — write locally first.

---

## Pending Web Searches

1. `Obsidian Android widget official release 2025` — confirm whether Obsidian shipped a first-party Android widget after mid-2024.
2. `Todoist Quick Add widget Android 2025 bottom sheet UI taps` — confirm current tap count and UI for 2025 Todoist release.
3. `Notion Android quick add widget 2025 share target destination picker review` — verify whether Notion's share-target friction was reduced in 2024–2025.
4. `Jetpack Glance AppWidget limitations known issues 2025` — verify current Glance stability before committing over `RemoteViews`.
5. `Android StatusBarManager requestAddTileService tile adoption rate UX` — find empirical data on whether the tile prompt improves user adoption.

## Web Search Results

*(To be populated by parent agent)*
