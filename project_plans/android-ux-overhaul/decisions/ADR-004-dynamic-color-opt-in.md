# ADR-004: Dynamic Color Opt-In — DYNAMIC as Explicit Choice, Static Themes Preserved

**Status**: Accepted
**Date**: 2026-04-12
**Feature**: Android UX Overhaul — F6 Material You Dynamic Color

---

## Context

Material You dynamic color (`dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`) generates a `ColorScheme` from the user's current wallpaper on Android 12+ (API 31+). Two strategies were considered for integrating this into `StelekitTheme`:

**Option A — DYNAMIC as default on Android 12+**: On API 31+, `StelekitThemeMode.SYSTEM` automatically uses dynamic colors. Users must opt out to get the static Stone/Parchment themes.

**Option B — DYNAMIC as explicit opt-in**: A new `StelekitThemeMode.DYNAMIC` enum value is added. It is only available in Settings on Android 12+. `LIGHT`, `DARK`, `STONE`, and `SYSTEM` continue to use their existing static palettes regardless of Android version.

## Decision

**Option B — DYNAMIC as explicit opt-in, static themes preserved.**

Changes:
1. Add `DYNAMIC` to `StelekitThemeMode` enum in `Theme.kt`.
2. In `StelekitTheme`, add a `DYNAMIC` branch to both `darkTheme` and `colorScheme` `when` expressions, guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` inside `androidMain`. The actual `dynamicLightColorScheme`/`dynamicDarkColorScheme` call must live in `androidMain` — these functions are not available in `commonMain`.
3. `StelekitExtendedColors` in `DYNAMIC` mode is derived from the generated `ColorScheme`'s M3 roles rather than the static stone palette (see derivation in pitfalls research).
4. Settings UI: the "Dynamic (Material You)" option is conditionally shown only on Android 12+ (detected via `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`, which must be an `androidMain` check surfaced via a `expect/actual` boolean `isDynamicColorSupported()`).
5. On Desktop, iOS, Web: `DYNAMIC` is a valid enum value but behaves identically to `SYSTEM` — it falls through to the system-based light/dark selection. It is never shown in Settings on non-Android platforms.

The `StelekitTheme` composable needs a `context` parameter only for the `DYNAMIC` branch. The cleanest approach is to use `expect/actual getDynamicColorScheme(isDark: Boolean): ColorScheme?` — `androidMain` returns the wallpaper-derived scheme, all other platforms return `null`. `StelekitTheme` calls this and falls back to the static scheme if null.

## Rationale

| Criterion | Option A (Default Dynamic) | Option B (Opt-In, chosen) |
|---|---|---|
| User expectation | Breaking change — Parchment users on Android 12+ lose their theme | No change to existing users |
| Custom palette preservation | Stone/Parchment themes broken on API 31+ | All existing themes work unchanged |
| `StelekitExtendedColors` compatibility | Requires all extended colors to be re-derived dynamically | Only `DYNAMIC` mode requires re-derivation |
| Contrast safety | Must validate dynamic scheme against custom extended colors | Scope limited to `DYNAMIC` mode |
| Settings complexity | Need an "opt-out" rather than "opt-in" | Simpler — show one extra option only on qualifying devices |

SteleKit's Stone and Parchment themes are distinctive brand elements and a key part of the app's identity for existing users (many of whom are Logseq migrants). Making dynamic color the default would silently break these themes for API 31+ users. The opt-in model follows Obsidian's approach: Material You is available but is not the default.

## Consequences

**Positive:**
- No existing user's theme changes after the update. `STONE`, `LIGHT`, `DARK`, `SYSTEM` behave identically to pre-F6.
- The extended colors derivation complexity is isolated to the `DYNAMIC` branch — the existing `LightExtendedColors`, `DarkExtendedColors`, `StoneExtendedColors` are untouched.
- Easy to validate: only one new Settings option to test, only on Android 12+ devices.

**Negative / Watch-outs:**
- `StelekitThemeMode.DYNAMIC` is a `commonMain` enum value that exists on all platforms. On Desktop/Web, if `DYNAMIC` is somehow selected (e.g., via a settings migration from an Android device), it must fall back gracefully to `SYSTEM` behavior. The `expect/actual getDynamicColorScheme()` returning null on non-Android platforms handles this.
- The `StelekitExtendedColors` derivation from M3 roles (`onSurfaceVariant`, `outlineVariant`, `surfaceVariant`, `primaryContainer`) must be verified for contrast across a range of wallpaper hues. A highly saturated red wallpaper generates a red-tinted `primaryContainer` — `blockRefBackground` derived from `primaryContainer.copy(alpha = 0.3f)` on a white background may still be visible, but test with a contrast checker.
- `PaleStone` bullet contrast ratio on `ParchmentBackground` is ~2.3:1 (pre-existing, not introduced by F6). Track separately but do not block F6 ship on it — it is a static-theme issue.
- The Settings UI change must use a `Build.VERSION.SDK_INT` check. This check must live in `androidMain` and be surfaced to `commonMain` via `expect/actual isDynamicColorAvailable(): Boolean`. Do not place `Build.VERSION` checks directly in `commonMain` — it will compile but couples `commonMain` to Android platform specifics.

## Patterns Applied

- **Progressive disclosure**: The DYNAMIC option is hidden from users on unsupported platforms — they cannot choose a feature that is unavailable to them.
- **Opt-in over opt-out**: Following Material Design's own guidance that dynamic color is a choice, not a mandate. Preserves user trust and existing customizations.
- **expect/actual for platform guards**: `isDynamicColorAvailable()` and `getDynamicColorScheme()` are the `expect/actual` surface — a minimal boundary that keeps `Build.VERSION` out of `commonMain` while still allowing the theme logic to live in `commonMain`'s `StelekitTheme`.
- **Null-safe fallback**: `getDynamicColorScheme()` returns `ColorScheme?`. A `null` result triggers fallback to the existing static scheme — safe on all platforms and on Android 11 and below.
