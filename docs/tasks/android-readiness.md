# Feature Plan: Android Readiness & Note Taking

## 1. Epic Overview

### User Value
The user wants to "start running the app on android and taking my notes". This requires not just a compiling build, but a usable interface for the core "Note Taking" loop: capturing ideas (creating pages/blocks) and organizing them (hierarchy/reordering) on a touch interface.

### Success Metrics
- **Creation**: User can create a new page/journal entry without needing an existing link.
- **Organization**: User can indent/outdent and reorder blocks using touch controls (no physical keyboard).
- **Usability**: Touch targets are sized correctly (min 48dp), and the soft keyboard doesn't obscure the editor.

### Scope
- **In Scope**:
    - Mobile-specific toolbar for block operations (Indent, Outdent, Move Up/Down).
    - "New Page" / "Quick Capture" UI.
    - Touch-friendly Drag-and-Drop.
    - Soft keyboard handling (IME).
- **Out of Scope**:
    - Full feature parity with Desktop (e.g., complex plugins, whiteboards).
    - iOS specifics (handled separately).

## 2. Gap Analysis

| Feature | Desktop Status | Android Gap | Required Action |
|---------|----------------|-------------|-----------------|
| **Editing** | ✅ Basic Text | ⚠️ No Tab/Shift+Tab | **Mobile Toolbar** |
| **Structure** | 🚧 In Progress | ⚠️ No Alt+Up/Down | **Mobile Toolbar** |
| **Reordering** | 🚧 In Progress | ⚠️ Touch Drag | **Touch Validation** |
| **Creation** | ⚠️ Wiki Links Only | ❌ No "New Page" | **Quick Capture UI** |
| **Navigation** | ✅ Sidebar | ✅ Drawer | None |

## 3. Atomic Task Decomposition

### Story 1: Mobile Input & Structure [Priority: High]

#### Task 1.1: Mobile Block Toolbar [2h]
- **Objective**: Provide on-screen controls for indentation and reordering since mobile keyboards lack Tab/Alt keys.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/MobileToolbar.kt` (New)
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/JournalsView.kt`
- **Implementation**:
    - Create a row of icon buttons: [Indent] [Outdent] [Move Up] [Move Down].
    - Show this toolbar above the soft keyboard when a block is focused.
    - Connect to `LogseqViewModel` block operations.

#### Task 1.2: Touch-Friendly Drag & Drop [2h]
- **Objective**: Ensure drag handles work with touch events.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockList.kt`
- **Implementation**:
    - Verify `PointerInputScope` detects drag gestures on Android.
    - Ensure drag handle has sufficient touch padding (48dp).

### Story 2: Quick Capture [Priority: High]

#### Task 2.1: New Page / Quick Capture UI [2h]
- **Objective**: Allow creating a new page or block immediately.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/TopBar.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/CommandPalette.kt`
- **Implementation**:
    - Add a "+" button to the TopBar or a FAB (Floating Action Button).
    - On click, show a dialog/input to enter page name.
    - On submit, create page (via `PageService`) and navigate to it.

## 4. Dependency Visualization

```
[Block Hierarchy: Task 1.1 (ViewModel)] 
       |
       v
[Android: Task 1.1 (Mobile Toolbar)]

[Page Management: Task 2.1 (PageService)]
       |
       v
[Android: Task 2.1 (Quick Capture)]
```

## 5. Context Preparation Guide
- **Files**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
    - `kmp/src/androidMain/kotlin/com/logseq/kmp/MainActivity.kt` (for soft keyboard handling)
