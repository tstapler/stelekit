# ADR-001: Stone Color Token Architecture

**Date**: 2026-04-11  
**Status**: Proposed  
**Deciders**: Tyler Stapler  
**Context**: Stelekit branding workstream

---

## Context

The project inherited color constant names (`LogseqBlue`, `GruvboxBg0`, etc.) from its upstream inspiration. These are palette-literal names — they describe the hex value, not the semantic role of the color in the UI. As the project establishes its own identity under the Stelekit brand, these names must be replaced.

Two architectural approaches exist:

**Option A: Flat semantic constants**  
Define top-level `val` constants like `StoneBackground = Color(0xFF282828)` for each palette role. Simple, no indirection. Theme switching requires replacing an entire `ColorScheme`.

**Option B: Token data class with semantic slots**  
Define a `StelekitColorTokens` data class with named slots (`background`, `surface`, `accent`, etc.) and create named instances for each theme. Composables read tokens via a `CompositionLocal<StelekitColorTokens>`.

---

## Decision

Use **Option A** (flat semantic constants) for the primitive palette values, combined with the existing Material 3 `ColorScheme` mechanism for theme composition.

The `StelekitColors` extended color class (renamed from `LogseqExtendedColors`) handles app-specific slots not covered by Material 3 (`bullet`, `indentGuide`, `sidebarBackground`, `blockRefBackground`).

A `StelekitThemeDefaults` object exposes the predefined theme configurations (stone dark, parchment light) as named presets for future theme override entry points.

---

## Rationale

- Material 3's `ColorScheme` + `CompositionLocalProvider` is already the established pattern in this codebase. Introducing a parallel token system would add complexity without clear benefit at the current project scale.
- The brand strategy specifies concrete hex values per role and per theme. Flat constants make this mapping explicit and easy to audit.
- Full theme swapping (the "text editor theme" goal) is achievable by providing a different `ColorScheme` and `StelekitColors` to `StelekitTheme` — no architectural change needed, just API exposure via `StelekitThemeDefaults`.

---

## Consequences

- All `Logseq*` and `Gruvbox*` color constant names are removed from source
- `StelekitThemeMode` enum stored in `PlatformSettings` requires a migration path from old enum value strings (see `branding-color-tokens.md` Known Issues)
- Future theme packs can be distributed as a named `Pair<ColorScheme, StelekitColors>` and injected at the `StelekitTheme` call site
- Typography customization follows the same pattern: expose `StelekitThemeDefaults.typography` for override
