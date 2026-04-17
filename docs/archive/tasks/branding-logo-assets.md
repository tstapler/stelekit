# Branding: Logo and Brand Asset Infrastructure

**Epic**: Define asset structure, file locations, and design brief for the Stelekit logo and app icon  
**Status**: Planned (design work is manual; this task delivers the specification and infrastructure)

---

## Background

The project has no app icon, no logo, and no brand asset infrastructure. Every platform target requires platform-specific icon sizes at build time. Without these, the desktop app uses the default JVM window icon, Android uses the launcher default, and the GitHub repository has no social preview image.

This task is a planning and infrastructure task. The actual artwork creation is manual design work (by a human designer or via a design tool like Figma or the `/logo-designer` skill). The deliverable here is:
1. The design brief the designer works from
2. The directory structure and filenames assets should be placed into
3. The integration points in the build system that reference those assets

---

## Design Brief

### Concept

The stele — an upright stone slab used across civilizations (Mesopotamia, Egypt, Greece, Rome, Mesoamerica) to inscribe knowledge meant to endure. Not decorative. Not digital. Material.

### Mark (Icon)

**Form**: A minimal geometric upright rectangle, slightly tapered at the top (wider at base, narrower at crown — like a carved stone tablet). A single horizontal carved line across the upper third suggests an inscription header or a ruled boundary — the act of carving text into stone.

**Do**:
- Keep the silhouette simple enough to read at 16x16 pixels
- Design monochrome-first; color version is a stone texture application, not a gradient
- Slightly rounded corners are acceptable but not required — depends on what reads better at small sizes
- The taper should be subtle (the mark should not look like a tombstone or triangle)

**Do not**:
- No gradients
- No drop shadows
- No 3D effects
- No serifs or letterforms inside the mark (it is a standalone shape, not an initials mark)
- No green, blue, or corporate colors — if color is added, use the brand accent palette: stone gray or patina teal

### Wordmark

**Text**: `stelekit` — lowercase, no camelCase, no space

**Type**: Geometric sans-serif. Candidate references: Inter, DM Sans, Geist, or a custom geometric sans. The type should feel hewn, not rounded. Avoid anything that reads as "startup landing page" (no Circular, no Nunito).

**Weight**: Regular to Medium. Not bold — the name is not being shouted.

**Spacing**: Slightly tracked (letter-spacing: 0.02–0.04em) to give the wordmark a considered, carved quality.

### Combination Mark

The primary lockup is the icon mark to the left of the wordmark. The mark should be optically vertically centered against the cap height of the wordmark, not the total height.

A stacked version (mark above wordmark, centered) is useful for square social preview images and mobile app stores.

### Color Usage

| Context | Color |
|---|---|
| Default (light bg) | `#282828` (stone dark) |
| Default (dark bg) | `#EBDBB2` (warm cream) |
| Accent version | `#83A598` (patina teal) |
| Monochrome positive | Black |
| Monochrome negative | White |

No gradients. The mark should work in a single flat color on any background.

---

## Required Asset Sizes

### Desktop (JVM / Compose Window)

Compose for Desktop on JVM reads the window icon from a `Painter` or `BitmapImage`. The standard approach is to bundle an ICO (Windows) and PNG assets (macOS/Linux).

| File | Size | Format | Purpose |
|---|---|---|---|
| `icon_16.png` | 16x16 | PNG | Taskbar (Windows), small contexts |
| `icon_32.png` | 32x32 | PNG | Standard resolution |
| `icon_64.png` | 64x64 | PNG | HiDPI / Retina |
| `icon_128.png` | 128x128 | PNG | macOS Dock |
| `icon_256.png` | 256x256 | PNG | macOS Dock HiDPI, Windows HD |
| `icon_512.png` | 512x512 | PNG | macOS App Store |
| `icon.ico` | Multi-size | ICO | Windows; contains 16, 32, 48, 256 |

### Android

Android adaptive icons are two-layer PNGs (foreground + background) at multiple densities.

| File | Size | Density | Location |
|---|---|---|---|
| `ic_launcher_foreground.png` | 108x108 dp | mdpi | `res/mipmap-mdpi/` |
| `ic_launcher_foreground.png` | 162x162 dp | hdpi | `res/mipmap-hdpi/` |
| `ic_launcher_foreground.png` | 216x216 dp | xhdpi | `res/mipmap-xhdpi/` |
| `ic_launcher_foreground.png` | 324x324 dp | xxhdpi | `res/mipmap-xxhdpi/` |
| `ic_launcher_foreground.png` | 432x432 dp | xxxhdpi | `res/mipmap-xxxhdpi/` |
| `ic_launcher_background.xml` | — | all | `res/mipmap-anydpi-v26/` (solid stone color `#282828`) |
| `ic_launcher.xml` | — | all | `res/mipmap-anydpi-v26/` (adaptive icon manifest) |

Legacy fallback (pre-API 26):
| File | Size | Location |
|---|---|---|
| `ic_launcher.png` | 48x48 | `res/mipmap-mdpi/` |
| `ic_launcher.png` | 72x72 | `res/mipmap-hdpi/` |
| `ic_launcher.png` | 96x96 | `res/mipmap-xhdpi/` |
| `ic_launcher.png` | 144x144 | `res/mipmap-xxhdpi/` |
| `ic_launcher.png` | 192x192 | `res/mipmap-xxxhdpi/` |

### iOS (planned target)

iOS uses an asset catalog (`AppIcon.appiconset`).

| File | Size | Purpose |
|---|---|---|
| `Icon-60@2x.png` | 120x120 | iPhone |
| `Icon-60@3x.png` | 180x180 | iPhone retina |
| `Icon-76.png` | 76x76 | iPad |
| `Icon-76@2x.png` | 152x152 | iPad retina |
| `Icon-83.5@2x.png` | 167x167 | iPad Pro |
| `Icon-1024.png` | 1024x1024 | App Store |

### Web / Favicon

| File | Size | Format | Location |
|---|---|---|---|
| `favicon.ico` | 16, 32, 48 | ICO | `web/` or project root |
| `favicon-32x32.png` | 32x32 | PNG | Web head |
| `apple-touch-icon.png` | 180x180 | PNG | iOS Safari bookmark |
| `og-image.png` | 1200x630 | PNG | GitHub social preview, Open Graph |

### Vector Source

| File | Format | Purpose |
|---|---|---|
| `stelekit-mark.svg` | SVG | Icon mark, scalable master |
| `stelekit-wordmark.svg` | SVG | Wordmark only |
| `stelekit-lockup.svg` | SVG | Mark + wordmark, horizontal |
| `stelekit-lockup-stacked.svg` | SVG | Mark + wordmark, stacked |

---

## Directory Structure

```
assets/
  brand/
    svg/
      stelekit-mark.svg
      stelekit-wordmark.svg
      stelekit-lockup.svg
      stelekit-lockup-stacked.svg
    png/
      icon_16.png
      icon_32.png
      icon_64.png
      icon_128.png
      icon_256.png
      icon_512.png
      og-image.png

kmp/src/androidMain/res/
  mipmap-mdpi/
  mipmap-hdpi/
  mipmap-xhdpi/
  mipmap-xxhdpi/
  mipmap-xxxhdpi/
  mipmap-anydpi-v26/

kmp/src/jvmMain/resources/
  icons/
    stelekit.ico
    icon_256.png        (symlink or copy from assets/)

kmp/src/iosMain/
  AppIcon.appiconset/   (populated when iOS target is re-enabled)
```

---

## Build System Integration Points

### Desktop (JVM)

Compose for Desktop loads the window icon via `painterResource()` or `BitmapPainter(useResource(...))`. The icon file must be on the JVM classpath at `resources/`.

In `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, the `application {}` block should set:
```kotlin
// When assets exist:
// trayIcon = painterResource("icons/icon_256.png")
// Window(icon = ...) is handled in desktop/ui/App.kt
```

The icon path `icons/icon_256.png` must resolve from `kmp/src/jvmMain/resources/icons/`.

### Android

The `AndroidManifest.xml` in `kmp/src/androidMain/` references the launcher icon via `android:icon="@mipmap/ic_launcher"`. This is already a standard Android convention; the task is to populate the `mipmap-*` directories with Stelekit assets instead of the default Android robot.

### iOS

When the iOS target is re-enabled, add the `AppIcon.appiconset` to `kmp/src/iosMain/` and reference it from the Xcode project configuration.

---

## Implementation Plan

### Story 1: Create Asset Directory Structure

#### Task 1.1 — Create Directory Skeleton with Placeholder README
- Files: `assets/brand/svg/.gitkeep`, `assets/brand/png/.gitkeep`, `kmp/src/jvmMain/resources/icons/.gitkeep`
- Create the directory structure with `.gitkeep` files so the scaffold is committed before artwork exists
- Add a `assets/brand/DESIGN_BRIEF.md` that contains the design brief section from this document for the designer's reference
- Effort: 30 minutes

### Story 2: Wire Desktop Icon (Post-Design)

#### Task 2.1 — Integrate Desktop Icon into Main.kt
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`, `kmp/src/jvmMain/resources/icons/`
- After artwork is placed at `kmp/src/jvmMain/resources/icons/icon_256.png`, add icon loading to the `application {}` block
- Effort: 1 hour (after assets exist)

### Story 3: Wire Android Icons (Post-Design)

#### Task 3.1 — Replace Android Default Launcher Icons
- Files: `kmp/src/androidMain/res/mipmap-*/ic_launcher.png`
- After artwork is exported at required densities, replace the default Android launcher icons
- Update `ic_launcher_background.xml` to use `#282828` (stone dark)
- Effort: 1–2 hours (after assets exist)

---

## Known Issues

### Risk: Icon Readability at 16x16
The stele mark's defining feature (the subtle taper and carved line) may not survive at 16x16 pixels. A simplified pixel-level version — just the upright rectangle silhouette with no carved line — may be needed for favicon and small system icon contexts.

**Mitigation**: Design at 512x512 first, then test downscaling. Prepare a "simplified small" variant before committing to the full set.

### Risk: Android Adaptive Icon Safe Zone
Android adaptive icons are cropped to a circle or rounded-square shape by the launcher. The stele mark (a tall rectangle) must fit within the 66dp "safe zone" (inner circle of the 108dp canvas) to avoid being clipped. The mark may need padding or a background panel.

**Mitigation**: Test with the Android adaptive icon previewer in Android Studio before finalizing.

### Dependency: iOS Target Disabled
The iOS asset catalog cannot be fully wired until the iOS target is re-enabled (tracked as `iOS-001` in TODO.md). Create the directory structure and brief now; populate assets when the target is active.

### Scope: Artwork Creation Is Manual
This task delivers the brief and infrastructure. The actual logo design requires a design tool session (Figma, Inkscape, or `/logo-designer` skill). Do not mark this task complete until at least the SVG source files and desktop icon are placed.

---

## Success Criteria

- `assets/brand/svg/` and `assets/brand/png/` directory structure exists and is committed
- `assets/brand/DESIGN_BRIEF.md` contains the design brief
- `kmp/src/jvmMain/resources/icons/` directory exists (with placeholder)
- Android `mipmap-*` directory skeleton exists under `kmp/src/androidMain/res/`
- Desktop build wires icon from `resources/icons/icon_256.png` once that file is placed
- The design brief matches the brand strategy's visual direction (stele motif, monochrome-first, no gradients)
