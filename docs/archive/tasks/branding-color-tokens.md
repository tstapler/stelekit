# Branding: Color Token Redesign

**Epic**: Rename and redesign the color system from Logseq/Gruvbox names to semantic stone tokens  
**Status**: Planned  
**ADR**: [ADR-001: Stone Color Token Architecture](../../project_plans/stelekit/decisions/ADR-001-stone-color-tokens.md)

---

## Background

`Color.kt` and `Theme.kt` currently use two naming schemes:
- `Logseq*` names (`LogseqBlue`, `LogseqTeal`, `LogseqLightBackground`) — borrowed from the upstream project
- `Gruvbox*` names (`GruvboxBg0`, `GruvboxFg1`, `GruvboxAqua`) — palette names from the Gruvbox color scheme

Both schemes are palette-literal rather than semantic. They describe what color they are, not what role they fill. The class `LogseqExtendedColors`, enum `LogseqThemeMode`, composable `LogseqTheme`, and accessor `LogseqTheme.colors` all carry the upstream name.

The brand strategy calls for:
1. A stone/marble palette as the default dark theme (warm `#282828` background, patina teal accent, ochre highlight)
2. A parchment/limestone palette as the default light theme
3. Semantic token names so users can swap entire themes — like a text editor theme system
4. Full elimination of `Logseq*` and `Gruvbox*` constant names from user-facing code

---

## Goals

- Replace all `Logseq*` and `Gruvbox*` color constant names with semantic stone token names
- Implement the new stone/marble dark palette and parchment/limestone light palette as specified in the brand strategy
- Rename `LogseqExtendedColors` → `StelekitColors`, `LogseqThemeMode` → `StelekitThemeMode`, `LogseqTheme` → `StelekitTheme`
- Preserve the existing Gruvbox theme as a named variant ("Stone Dark" becomes the default; "Gruvbox" can remain as a legacy option if desired, or be folded into the new Stone Dark)
- Update every call site that references the old names
- Do NOT rename package paths or move files

---

## New Palette Specification

### Semantic Token Names

| Token | Role | Dark (Stone) | Light (Parchment) |
|---|---|---|---|
| `StoneBackground` | Page/app background | `#282828` | `#F5F0E8` |
| `StoneSurface` | Sidebar, cards, panels | `#3C3836` | `#EDE8DC` |
| `StoneOnBackground` | Primary text | `#EBDBB2` | `#3C3836` |
| `StoneMuted` | Secondary text, placeholders | `#928374` | `#7C7369` |
| `PatinaAccent` | Links, primary interactive | `#83A598` | `#458588` |
| `GraniteBullet` | Bullet points, indent guides | `#504945` | `#B8AFA0` |
| `OchreHighlight` | Highlight yellow, secondary | `#FABD2F` | `#D79921` |

Additional palette constants retained for completeness (error states, etc.):
- `StoneDanger` — `#FB4934` (Gruvbox red, unchanged)
- `StoneSuccess` — `#B8BB26` (Gruvbox green, unchanged)
- `StoneWarning` — `#FE8019` (Gruvbox orange, unchanged)
- `StonePurple` — `#D3869B` (Gruvbox purple, unchanged)

---

## Implementation Plan

### Story 1: Rename Color Constants in Color.kt

**Goal**: Replace all named constants; add new stone/parchment palette values.

#### Task 1.1 — Rewrite Color.kt with Stone Token Names
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt`
- Remove all `Logseq*` and `Gruvbox*` named constants
- Add semantic stone token constants for both dark and light themes
- Add shared alpha constants: `StoneIndentGuideAlpha = 0.1f`, `StoneBlockRefAlpha = 0.08f`
- Retain full Gruvbox palette as private/internal constants with comment noting they underpin stone dark
- Effort: 1–2 hours

#### Task 1.2 — Rewrite Theme.kt with Renamed Types and New Palettes
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt`
- Rename `LogseqExtendedColors` → `StelekitColors`
- Rename `LocalLogseqColors` → `LocalStelekitColors`
- Rename `LogseqThemeMode` → `StelekitThemeMode`; update enum values: `LIGHT`, `DARK` (stone dark), `SYSTEM`, `STONE` (keep for clarity — or keep DARK)
- Rename composable `LogseqTheme` → `StelekitTheme`
- Rename accessor object `LogseqTheme` → `StelekitTheme`
- Replace `LightColorScheme` values with parchment palette tokens
- Replace `DarkColorScheme` values with stone dark palette tokens
- Replace `GruvboxColorScheme` with the stone dark values (they are effectively the same palette; Gruvbox was already the intended default)
- Update `logseqTypography` → `stelekitTypography`
- Effort: 2–3 hours

### Story 2: Update All Call Sites

**Goal**: Zero compilation errors after rename; no `Logseq*` names remain in source.

#### Task 2.1 — Update App.kt (common)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- Replace `LogseqTheme(...)` calls → `StelekitTheme(...)`
- Replace `LogseqThemeMode.SYSTEM` and other enum refs → `StelekitThemeMode.*`
- Replace `LogseqApp` composable name with `StelekitApp` (internal rename; the entry points that call it must also update)
- Effort: 1 hour

#### Task 2.2 — Update Desktop App.kt (jvmMain)
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/App.kt`
- Update import and call to `StelekitApp`
- Effort: 30 minutes

#### Task 2.3 — Update Settings.kt
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Settings.kt`
- Replace `LogseqThemeMode` type references → `StelekitThemeMode`
- Update theme chip labels if they display enum names
- Effort: 30 minutes

#### Task 2.4 — Audit and Update Remaining UI Files
- Files: All `*.kt` under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/`
- Grep for `Logseq` and `Gruvbox` in all UI source; update any remaining references to `LocalLogseqColors`, `LogseqTheme.colors`, etc.
- Key files likely needing updates: `TopBar.kt`, `Sidebar.kt`, `BlockItem.kt`, `BlockGutter.kt`, `MainLayout.kt`
- Effort: 1–2 hours

### Story 3: Theme Extensibility Architecture

**Goal**: Expose semantic tokens such that a future theme override mechanism can swap the full palette.

#### Task 3.1 — Document Theme Extension Pattern
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt`
- Add KDoc comment on `StelekitTheme` explaining the token architecture: "Override `StelekitTheme` by providing a custom `ColorScheme` and `StelekitColors` via `CompositionLocalProvider`. All UI components read from `MaterialTheme.colorScheme` and `LocalStelekitColors`."
- Add a `StelekitThemeDefaults` object exposing the stone dark and parchment light schemes as named presets so future code can reference them
- Effort: 1 hour

---

## File Change Summary

| File | Change Type |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt` | Full rewrite |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt` | Full rewrite |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | Update imports + call sites |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/App.kt` | Update import + composable name |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Settings.kt` | Update enum type refs |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` | Audit/update |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` | Audit/update |
| Multiple other UI components | Audit for `Logseq`/`Gruvbox` references |

---

## Known Issues

### Potential Bug: Theme Enum Display Name in Settings
The `GeneralSettings` composable in `Settings.kt` renders theme chips via `mode.name.lowercase().replaceFirstChar { it.uppercase() }`. If `StelekitThemeMode` enum values are renamed (e.g., `DARK` stays `DARK`), chips display correctly. But if a value is added or removed, the Settings UI will change automatically without a deliberate UX review.

**Mitigation**: Add a `displayName: String` property to `StelekitThemeMode` entries rather than deriving display names from enum constants.

### Potential Bug: Composition Local Mismatch After Partial Rename
If any composable accesses `LocalLogseqColors.current` after the local is renamed to `LocalStelekitColors`, it will compile against the old `staticCompositionLocalOf` default (Unspecified colors) and produce invisible or transparent UI elements — no crash, silent failure.

**Mitigation**: Complete the rename in a single PR; run `./gradlew :kmp:compileKotlinJvm` as a gate. Add a search for `LocalLogseq` before merging.

### Risk: GruvboxColorScheme Removal
The Gruvbox theme option is currently user-selectable from Settings. Removing or renaming it is a user-visible change. The new `StelekitThemeMode.DARK` (stone dark) uses the same underlying palette values, so the visual result is identical — but users who set their theme to `GRUVBOX` in `PlatformSettings` will have a stale stored value.

**Mitigation**: Either keep `GRUVBOX` as a non-displayed alias in the enum (maps to stone dark), or add a migration in `PlatformSettings` read path that converts `"GRUVBOX"` → `"DARK"` on first load.

---

## Success Criteria

- `./gradlew :kmp:compileKotlinJvm` passes with zero errors
- `grep -r "Logseq" kmp/src` returns only results in `parsing/LogseqParser.kt` and file-format-related code (acceptable references to the format, not the brand)
- `grep -r "Gruvbox" kmp/src` returns zero results
- The desktop app renders correctly in both light and stone dark mode
- Settings theme picker shows human-readable theme names
