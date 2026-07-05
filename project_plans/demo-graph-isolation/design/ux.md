# UX Design: Demo Graph Isolation

**Feature**: Demo Graph Isolation  
**Status**: Pre-implementation design  
**Surfaces designed**: 5  
**UX acceptance criteria**: 18

---

## Overview

The demo graph is an ephemeral, in-memory experience. Users explore sample content without creating persistent files. The UX must make the ephemeral nature immediately clear, keep the demo out of the graph switcher, and provide a graceful error fallback.

---

## Surface 1: Onboarding — Graph Selection Step

### Wireframe

```
┌────────────────────────────────────────────────┐
│                                                │
│         Choose Your Graph                      │
│                                                │
│   Where would you like to store your notes?    │
│                                                │
│  ┌──────────────────────────────────────────┐  │
│  │  ~/Documents/stelekit                    │  │
│  │                                          │  │
│  │  [ Select Graph Directory ]              │  │
│  │                                          │  │
│  │  ──────────────── or ────────────────   │  │
│  │                                          │  │
│  │  [ Try Demo Graph ]                      │  │
│  │  Explore sample notes — no files saved   │  │
│  └──────────────────────────────────────────┘  │
│                                                │
│  [ ← Back ]                      [ Next → ]   │
└────────────────────────────────────────────────┘
```

### Button changes

**Current**: Button text "Load Demo Graph"  
**New**: Button text "Try Demo Graph" with a subtitle label below it reading "Explore sample notes — no files saved"

The subtitle is `MaterialTheme.typography.bodySmall` in `onSurfaceVariant` color, placed directly beneath the button. It clarifies ephemerality before the user clicks.

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Arrives at GRAPH_SELECTION step | Card shows default path + "Select Graph Directory" button + separator + "Try Demo Graph" |
| 2 | Taps "Try Demo Graph" | System loads in-memory demo graph (no SQLite write, no disk access) |
| 3 | Loading succeeds | `onGraphSelected` callback fires with a sentinel demo identifier; onboarding advances to KEYMAP_INTRO step |
| 4 | Taps "Select Graph Directory" (desktop/iOS) | Native directory picker opens; on selection advances to KEYMAP_INTRO as today |
| 5 | "Next →" on KEYMAP_INTRO | `onComplete()` fires; main app opens |

### Error handling

If demo content fails to load while still on the onboarding screen, the error is surfaced after the user reaches the main app (see Surface 5: Error State). The onboarding step itself does not show an inline error — the user advances to KEYMAP_INTRO regardless and sees the error in the main app.

---

## Surface 2: Main App — Demo Banner (Sidebar)

### Wireframe

```
┌────────────────────────────────────┐
│  [×]                               │  ← collapse sidebar
├────────────────────────────────────┤
│  ┌──────────────────────────────┐  │
│  │ 📁 Demo Graph            ∨   │  │  ← GraphSwitcher pill (primaryContainer bg)
│  └──────────────────────────────┘  │
├────────────────────────────────────┤
│  ┌──────────────────────────────┐  │
│  │ ℹ  Exploring the demo —     │  │
│  │    changes won't be saved   [×] │  │  ← Demo banner
│  └──────────────────────────────┘  │
├────────────────────────────────────┤
│  Navigation                        │
│   Today                            │
│   All Pages                        │
│   …                                │
└────────────────────────────────────┘
```

### Demo banner component

```
┌─────────────────────────────────────────┐
│  [ℹ]  Exploring the demo —          [×] │
│       changes won't be saved            │
└─────────────────────────────────────────┘
```

- Background: `MaterialTheme.colorScheme.tertiaryContainer` (informational, not warning/error)
- Icon: `Icons.Default.Info`, size 18 dp, tint `onTertiaryContainer`
- Text: `MaterialTheme.typography.bodySmall`, color `onTertiaryContainer`
- Dismiss icon: `Icons.Default.Close`, size 18 dp, `IconButton`
- Placed immediately below the `HorizontalDivider` that follows `GraphSwitcher`, above the Navigation section
- Visible only when demo graph is active
- Dismissed state is session-scoped only (not persisted): banner reappears each time the user loads the demo

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Demo graph loads | Banner appears below GraphSwitcher pill |
| 2 | Taps [×] dismiss | Banner slides out (animated fadeOut + shrinkVertically); navigation section moves up |
| 3 | User navigates pages, edits blocks | Changes held in memory only; no disk writes |
| 4 | User opens another graph | Demo state cleared; banner removed; real graph loads normally |

### Accessibility

- Banner container: `contentDescription = "Demo graph notice: Exploring the demo, changes won't be saved"`
- Dismiss button: `contentDescription = "Dismiss demo notice"`

---

## Surface 3: Graph Switcher Pill — Demo Active State

### Wireframe

```
Collapsed (default):
┌──────────────────────────────────────────┐
│  📁  Demo Graph                      ∨   │  ← primaryContainer bg
└──────────────────────────────────────────┘

Expanded (dropdown open):
┌──────────────────────────────────────────┐
│  📁  Demo Graph                      ∧   │
└──────────────────────────────────────────┘
```

### Behavior changes when demo is active

- `currentGraphName` = `"Demo Graph"`
- `contentDescription` = `"Graph: Demo Graph (demo), tap to switch graph"` (per spec)
- Demo graph is **omitted** from `availableGraphs` passed to the dropdown — it never appears as a list item
- The pill itself still taps to open the dropdown for switching to a real graph

### Dropdown when demo is active

```
┌─────────────────────────────────────────┐
│  📁  My Notes            [active]  [🗑] │  ← real graphs only
│  📁  Work Journal                  [🗑] │
│  ─────────────────────────────────────  │
│  [+] Open local folder...               │
│  [↓] Clone from URL...                  │
└─────────────────────────────────────────┘
```

No "Demo Graph" entry appears in this list. The demo is not a persistent graph and has no removal button. Switching to any real graph exits the demo and clears demo state.

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Taps graph pill | Dropdown opens showing only real graphs (demo omitted) |
| 2 | Selects a real graph | Demo state cleared; real graph loads; banner disappears |
| 3 | Taps "Open local folder..." | Native picker opens; on confirm, real graph loads; demo state cleared |
| 4 | Taps outside dropdown | Dropdown closes; demo graph remains active |

---

## Surface 4: Graph Switcher Dropdown — Normal (No Demo Active)

For completeness — no changes from current behavior when demo is not active.

```
┌─────────────────────────────────────────┐
│  📁  My Notes            [active]  [🗑] │
│  📁  Work Journal                  [🗑] │
│  ─────────────────────────────────────  │
│  [+] Open local folder...               │
│  [↓] Clone from URL...                  │
└─────────────────────────────────────────┘
```

This surface is unchanged. It is documented here so that the acceptance criteria can assert the demo graph never appears in it.

---

## Surface 5: Demo Load Error State

### Wireframe

```
┌──────────────────────────────────────────────────────────────┐  ← main app
│  [Sidebar]  [Content area — empty graph]                     │
│                                                              │
│                    ┌─────────────────────────┐              │
│                    │ Could not load demo      │              │
│                    │ content. Starting with   │              │
│                    │ an empty graph.          │              │
│                    └─────────────────────────┘              │
│                         ← Snackbar, bottom of screen        │
└──────────────────────────────────────────────────────────────┘
```

### Behavior

- The app does **not** return to the onboarding screen
- An empty in-memory graph is loaded instead; the sidebar shows "Demo Graph" pill
- A `Snackbar` appears at the bottom of the content area with the message: `"Could not load demo content. Starting with an empty graph."`
- The snackbar uses default duration (`SnackbarDuration.Short` — 4 seconds) with no action button
- The demo banner still appears in the sidebar (user is still in demo mode)

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Demo content fails to load | In-memory empty graph loaded; main app displayed |
| 2 | (automatic) | Snackbar appears: "Could not load demo content. Starting with an empty graph." |
| 3 | Snackbar auto-dismisses after 4 s | User continues with empty demo; sidebar banner still visible |
| 4 | User taps graph pill | Dropdown shows real graphs; user can switch out of demo |

### No dead ends

The error path always leaves the user in a functional main app state with the graph switcher available. There is no spinner, no blocking dialog, and no way to become stuck.

---

## Full Interaction Flow: Loading the Demo

```
[Onboarding: GRAPH_SELECTION]
        │
        ▼ User taps "Try Demo Graph"
[System: load in-memory demo graph]
        │
        ├─── success ───────────────────────────────────────┐
        │                                                   │
        ▼                                                   ▼
[onGraphSelected(sentinel)] ← advances to KEYMAP_INTRO  [onGraphSelected(sentinel)]
        │                                                   │
        ▼                                                   │
[Onboarding: KEYMAP_INTRO]                                  │
        │                                                   │
        ▼ User taps "Finish"                               │
[Main App opens]  ◄─────────────────────────────────────────┘
        │
        ▼
[Sidebar: "Demo Graph" pill + demo banner]
        │
        ├─── demo content loaded OK ─── pages browsable
        │
        └─── demo content failed ─────── snackbar + empty graph

[User taps graph pill → dropdown (real graphs only)]
        │
        ▼ selects real graph
[Demo state cleared → real graph loads → banner gone]
```

---

## Interaction Flow: Dismissing the Banner

```
[Demo active: banner visible]
        │
        ▼ User taps [×] on banner
[Banner: fadeOut + shrinkVertically animation]
        │
        ▼
[Navigation section moves up to fill space]
        │
        ▼
[Demo still active — only the banner is gone]
        │
        Note: banner does NOT reappear within this session
              but DOES reappear on next demo load
```

---

## UX Acceptance Criteria

### AC-1: Onboarding button text

"The 'Try Demo Graph' button is visible on the GRAPH_SELECTION onboarding step alongside the subtitle 'Explore sample notes — no files saved'."

### AC-2: Demo load advances onboarding

"After tapping 'Try Demo Graph', the user advances to the KEYMAP_INTRO step within 2 seconds without any additional tap required."

### AC-3: Demo completes onboarding

"After tapping 'Finish' on KEYMAP_INTRO following a demo load, the main app opens and the sidebar is visible."

### AC-4: GraphSwitcher pill shows correct name

"When the demo graph is active, the GraphSwitcher pill displays 'Demo Graph' as the graph name."

### AC-5: GraphSwitcher pill contentDescription

"When the demo graph is active, the GraphSwitcher pill's accessibility content description reads 'Graph: Demo Graph (demo), tap to switch graph'."

### AC-6: Demo banner appears

"When the demo graph is active, a banner reading 'Exploring the demo — changes won't be saved' is visible in the sidebar below the GraphSwitcher pill."

### AC-7: Demo banner is dismissible

"Tapping the [×] on the demo banner removes the banner within 300 ms (animation completes) and the banner does not reappear during the same session."

### AC-8: Demo excluded from graph switcher dropdown

"Opening the graph switcher dropdown when the demo graph is active shows no entry labeled 'Demo Graph'; only real user graphs and the 'Open local folder...' / 'Clone from URL...' actions are present."

### AC-9: Switching to real graph exits demo

"Selecting a real graph from the dropdown while in demo mode loads that graph, clears the demo banner from the sidebar, and the GraphSwitcher pill shows the real graph's name."

### AC-10: No persistent artifacts from demo

"After using the demo graph and switching to a real graph, no SQLite database files related to the demo appear in the user's file system."

### AC-11: Error fallback — no return to onboarding

"If demo content fails to load, the user is shown the main app (not the onboarding screen) and can interact with the graph switcher."

### AC-12: Error fallback — snackbar message

"If demo content fails to load, a snackbar appears with the text 'Could not load demo content. Starting with an empty graph.' and auto-dismisses within 5 seconds."

### AC-13: Error fallback — banner still present

"If demo content fails to load, the demo banner ('Exploring the demo — changes won't be saved') is still visible in the sidebar."

### AC-14: Error fallback — exit path available

"When demo content fails to load, the user can tap the GraphSwitcher pill to open the dropdown and switch to a real graph within 2 taps."

### AC-15: Keyboard navigation — onboarding

"The 'Try Demo Graph' button is reachable via keyboard Tab navigation on desktop, and activatable via Enter/Space."

### AC-16: Keyboard navigation — banner dismiss

"The demo banner dismiss button [×] is reachable via keyboard Tab navigation and activatable via Enter/Space."

### AC-17: Screen reader — banner

"A screen reader announces the demo banner container as 'Demo graph notice: Exploring the demo, changes won't be saved' and the dismiss button as 'Dismiss demo notice'."

### AC-18: Color contrast

"The demo banner text ('Exploring the demo — changes won't be saved') on `tertiaryContainer` background meets WCAG AA color contrast ratio of ≥ 4.5:1 in both light and dark themes."

---

## No Dead Ends — Checklist

| Scenario | Exit path |
|----------|-----------|
| Demo loads successfully | Graph switcher → real graph |
| Demo banner dismissed | Graph switcher → real graph (banner gone, switcher still works) |
| Demo content fails to load | Snackbar auto-dismisses; graph switcher still reachable |
| User on KEYMAP_INTRO after demo | "Finish" → main app (always available) |
| User on KEYMAP_INTRO after demo | "← Back" → GRAPH_SELECTION (can choose real graph instead) |

Every error and demo state has a clear forward path. No state leaves the user without the graph switcher available.

---

## Component Summary

| Component | File (existing or new) | Change type |
|-----------|------------------------|-------------|
| `GraphSelectionStep` | `ui/onboarding/Onboarding.kt` | Modify: rename button, add subtitle |
| `GraphSwitcher` | `ui/components/Sidebar.kt` | Modify: contentDescription when demo; filter demo from list |
| `DemoBanner` | `ui/components/Sidebar.kt` or new file | New composable |
| `LeftSidebar` | `ui/components/Sidebar.kt` | Modify: conditionally render `DemoBanner` |
| Snackbar (error) | `ui/App.kt` or `StelekitViewModel` | New: emit on demo load failure |
