package dev.stapler.stelekit.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Breakpoint tiers that follow Material Design 3 adaptive layout guidance.
 *
 * | Class    | maxWidth      | Typical form factor            |
 * |----------|---------------|--------------------------------|
 * | COMPACT  | < 600 dp      | Phone portrait                 |
 * | MEDIUM   | 600 – 839 dp  | Tablet portrait, phone landscape |
 * | EXPANDED | ≥ 840 dp      | Tablet landscape, desktop      |
 *
 * Compute once at the root of each adaptive subtree and provide downward
 * via [LocalWindowSizeClass].  Never recompute inside leaf composables.
 */
enum class WindowSizeClass { COMPACT, MEDIUM, EXPANDED }

/** Compute the [WindowSizeClass] for a given available width. */
fun windowSizeClassFor(maxWidth: Dp): WindowSizeClass = when {
    maxWidth < 600.dp  -> WindowSizeClass.COMPACT
    maxWidth < 840.dp  -> WindowSizeClass.MEDIUM
    else               -> WindowSizeClass.EXPANDED
}

/**
 * Composition local that carries the [WindowSizeClass] for the current
 * layout subtree.  Defaults to [WindowSizeClass.EXPANDED] so desktop
 * previews and tests need no explicit provider.
 */
val LocalWindowSizeClass = staticCompositionLocalOf { WindowSizeClass.EXPANDED }

// ---------------------------------------------------------------------------
// Convenience extensions so call-sites read like prose
// ---------------------------------------------------------------------------

/** True on phones (portrait). Overlay sidebar, bottom nav, compact dialogs. */
val WindowSizeClass.isCompact:  Boolean get() = this == WindowSizeClass.COMPACT

/** True on tablets (portrait) and large phones (landscape). */
val WindowSizeClass.isMedium:   Boolean get() = this == WindowSizeClass.MEDIUM

/** True on tablets (landscape) and desktops. Full sidebar, menu bar. */
val WindowSizeClass.isExpanded: Boolean get() = this == WindowSizeClass.EXPANDED

/**
 * Whether to use a mobile-optimised layout (overlay sidebar, bottom nav).
 * COMPACT and MEDIUM both use mobile layout — MEDIUM covers tablets in portrait
 * and large phones (including folded form factors like Pixel Fold at ~673dp).
 * Only EXPANDED (≥840dp, tablet landscape / desktop) uses the desktop layout.
 */
val WindowSizeClass.isMobile:   Boolean get() = isCompact || isMedium
