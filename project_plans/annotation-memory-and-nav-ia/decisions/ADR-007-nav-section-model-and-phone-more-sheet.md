# ADR-007: One `NavSection` model drives the sidebar, the phone "More" sheet and the command palette

**Status**: Accepted (amended 2026-11-08 for the ADR-006 merge decision) | **Date**: 2026-11-08

## Context
Sidebar.kt (1,283 lines) hard-codes a flat list (`:~315-355`); the Android bottom bar's "More" calls `toggleSidebar()` so phones reuse the desktop drawer (architecture.md section 7). Material guidance (ux.md section 2, not read in full): drawers are not combined with a navigation bar; a grouped list with headline + supporting text suits phones. `NavigationSuiteScaffold` has no sections/descriptions and KMP wasm/iOS availability is unclear (build-vs-buy.md section 4).

## Options
1. Keep the custom `Sidebar`; add `SectionHeader` and descriptions only there. Weakness: phone and palette diverge.
2. **Shared pure model `NavSection`/`NavDestinationSpec` in commonMain**; Sidebar renders it; phone "More" becomes a modal bottom sheet rendering the same model; palette commands derive from it.
3. `NavigationSuiteScaffold`. Rejected: no sections/descriptions, KMP uncertainty.

## Decision
Option 2. Section headers use `heading()` semantics; rows are 48 dp minimum with a supporting line (allow 2 lines at 200% font scale); decorative icons `contentDescription = null`. Sections: Daily, Connections, Graph, Developer (hidden unless active), per ux.md section 8. Amended 2026-11-08 (ADR-006 DECIDED: merge) and again in Repair 4: the Library destination is the **first row of the Graph section** (Library, Graph Stats, Git Setup). The earlier one-item "Media" section was dropped because a section whose only row repeats its header is redundant chrome and a double TalkBack announcement; naming the section "Library" had the same flaw. The row `Screen.LibraryStats` is labelled "Graph Stats" in the sidebar (`Sidebar.kt:346` still says "Library Stats"; the rename is task D.3b and is asserted by `NavLabelUniquenessTest`). Owner may override (ux.md ED-16). `NavDestinationSpec` gains an optional `shortLabelKey` (the bottom bar shows "Pages", the sidebar and sheet "All pages"), and `Screen.Gallery` / `Screen.AssetBrowser` are classified `NON_NAV_REDIRECT` aliases that never appear in a section. The Developer section is appended last so showing or hiding it never moves rows above it.

## Consequences
- Drift guard (amended 2026-11-08, Repair 2): `NavDestinationSpec.forScreen` is a compiler-exhaustive `when` over `Screen` with no `else`, plus businessTest `NavModelExhaustivenessTest` (JVM reflection cannot live in commonTest) asserting every sealed subtype is a spec or an explicit non-nav entry. This is the single name and location used in plan D.2 and ux.md; it complements `WiringCompletenessTest`.
- Phone drawer behaviour changes (More opens a sheet). Rollback = revert the D.4 PR; the drawer code stays until D.4 merges.
