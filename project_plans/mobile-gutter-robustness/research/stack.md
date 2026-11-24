# Mobile Gutter Robustness — Technology Stack Research

## Versions in use

- Compose Multiplatform plugin: **1.10.3** (`settings.gradle.kts`)
- Material3 (Android): **1.4.0**; KMP: `compose.material3` (resolved by CMP 1.10.3 → M3 1.4.x)
- Compose UI (Android): **1.10.6**
- Compose Foundation: `compose.foundation` via CMP plugin

No new Gradle dependencies are needed. All required APIs — `ModalBottomSheet`, `LocalHapticFeedback`,
`minimumInteractiveComponentSize`, gesture combinators — ship with the versions already on the
classpath.

---

## 1. Touch-target expansion (small visual, large hit area)

### Problem
- Collapse caret: 18 dp `.size` with `.clickable` directly on the `Icon` — hit area is 18 dp, not 48 dp.
- Bullet dot: 6 dp `Box` with `.dragGesture` — hit area is 6 dp on mobile.

### Recommended pattern (already established by the drag handle in `BlockGutter.kt:99–151`)
Wrap the small visual element in a `Box(Modifier.size(48.dp))` and move the gesture/click modifier
onto the outer Box, with the inner element centered. This is the same technique M3's `IconButton`
uses internally and matches the existing drag-handle implementation.

```kotlin
// Collapse caret — fixed for mobile
Box(
    modifier = Modifier
        .size(if (isMobile) 48.dp else 18.dp)   // large hit area on mobile
        .clickable { onToggleCollapse() }
        .semantics { contentDescription = if (isCollapsed) "Expand" else "Collapse" },
    contentAlignment = Alignment.Center
) {
    Icon(
        imageVector = …,
        contentDescription = null,       // semantics on the outer Box
        modifier = Modifier.size(18.dp)
    )
}
```

**`minimumInteractiveComponentSize`** (from `androidx.compose.material3`) is an alternative: it
enforces a 48 dp minimum for _Material_ components (Button, Checkbox, etc.) but it does **not**
apply to `Box + clickable` or raw `pointerInput`. For non-Material interactive regions the explicit
`size(48.dp)` outer Box is the right tool.

For the bullet dot on mobile (target ≥ 24 dp) the same wrapper approach applies, with the visual
dot staying 6 dp and the `dragGesture` Modifier attached to a 24 dp or 48 dp outer Box.

---

## 2. ModalBottomSheet (quick-action menu)

`ModalBottomSheet` is already used in this codebase — see
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusSheet.kt` (imports
`androidx.compose.material3.ModalBottomSheet` with `@ExperimentalMaterial3Api`).

The opt-in annotation is **required** at both the call site and the composable that hosts it:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockGutterActionSheet(
    onDismiss: () -> Unit,
    onIndent: () -> Unit,
    // …
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // ListItem rows for each action
    }
}
```

Conditional display: `var showSheet by remember { mutableStateOf(false) }` gated by
`LocalWindowSizeClass.current.isMobile`. The tap that opens it comes from a `combinedClickable`
(see §4).

---

## 3. Haptic feedback on drag start

`LocalHapticFeedback` / `HapticFeedbackType` are already used in this codebase — confirmed in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`:

```kotlin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

val haptic = LocalHapticFeedback.current
// …
haptic.performHapticFeedback(HapticFeedbackType.LongPress)
```

For drag-start haptic, call `haptic.performHapticFeedback(HapticFeedbackType.LongPress)` inside the
`onDragStart` lambda of the `dragGesture` Modifier when `isMobile` is true. The `haptic` reference
must be captured in the composable scope and closed over in the lambda (it cannot be accessed from
a plain `Modifier` extension function — pass it as a parameter to `dragGesture` or into
`BlockGutter`'s lambda arguments).

`HapticFeedbackType` values in CMP 1.10.3 / `compose.ui`: `LongPress`, `TextHandleMove`. For drag
start, `LongPress` is the correct semantic signal (matches what `detectDragGesturesAfterLongPress`
already triggers natively on Android — the system fires haptic on long-press recognition before our
`onDragStart` even fires, so the call is a belt-and-suspenders guard for cases where the drag
starts immediately without long-press, which won't happen on Android but could on a future mobile
target).

---

## 4. Gesture conflict: tap vs drag on the drag handle

**Current state**: the drag handle `Box` has two layered `pointerInput` modifiers — hover detection
and `dragGesture` — but no tap handler. Adding a tap for the quick-action menu must not interfere
with drag.

**Recommended approach**: use `combinedClickable` for tap (and optionally long-press) alongside
the existing `dragGesture` pointerInput. `combinedClickable` and `pointerInput` compose safely when
placed on separate Modifiers in the chain:

```kotlin
Box(
    modifier = Modifier
        .size(48.dp)
        .combinedClickable(
            onClick = { if (isMobile) showActionSheet = true },
            onLongClick = null,          // drag-gesture handles long-press separately
        )
        .dragGesture(…)
)
```

Alternatively, use a single `pointerInput` with `detectTapGestures(onTap = …)` chained via
`awaitEachGesture`, but `combinedClickable` is cleaner and already used elsewhere in the codebase.

**Key constraint**: `detectDragGesturesAfterLongPress` (the mobile path) consumes the long-press
pointer event. If `combinedClickable`'s `onLongClick` and `detectDragGesturesAfterLongPress` are
both present on the same node, they race. The safe rule: use `combinedClickable` with `onClick`
only (no `onLongClick`) and let the drag Modifier own the long-press. A tap that starts and ends
without significant movement will fire `onClick`; a press that moves will be claimed by `dragGesture`
before `onClick` fires.

---

## 5. `isMobile` gate

`LocalWindowSizeClass` and the `isMobile` extension are already defined and provided in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/WindowSizeClass.kt`:

```kotlin
val WindowSizeClass.isMobile: Boolean get() = isCompact || isMedium
```

Usage inside `BlockGutter`:

```kotlin
val isMobile = LocalWindowSizeClass.current.isMobile
```

This is a zero-cost read (composition local) and is already used in `MainLayout.kt`,
`GraphContentActiveShell.kt`, and similar files.

---

## 6. No new dependencies required

| Feature | API | Source |
|---|---|---|
| Touch target | `Box` + `Modifier.size` + `clickable` | `compose.foundation` (already on classpath) |
| ModalBottomSheet | `ModalBottomSheet` | `compose.material3` (already on classpath) |
| Haptic feedback | `LocalHapticFeedback` + `HapticFeedbackType` | `compose.ui` (already on classpath) |
| Gesture combinator | `combinedClickable` | `compose.foundation` (already on classpath) |
| Mobile gate | `LocalWindowSizeClass.current.isMobile` | project-local `WindowSizeClass.kt` |

The `@ExperimentalMaterial3Api` opt-in for `ModalBottomSheet` must be propagated to any composable
that calls it, the same way `SyncStatusSheet.kt` already does it.
