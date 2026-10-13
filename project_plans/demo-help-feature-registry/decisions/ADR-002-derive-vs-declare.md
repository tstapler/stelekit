# ADR-002: Derive the obligation list, declare the feature rows

**Status**: Proposed
**Date**: 2026-10-11
**Deciders**: Repo owner (Tyler Stapler)
**Related**: ADR-001 (shape), ADR-003 (enforcement), `../implementation/plan.md` Stories A1.1, A1.3, B3.1

## Context

A registry that is only declared goes stale at discovery: nothing forces a new feature into it. A registry that is only derived cannot name features that exist in no enum (cross-graph copy/share, wiki-subdir detection, diagnostics export, `{{query}}` blocks: zero coverage and no single source entity, `../research/features.md` A3/A4).

Existing sources of truth (VERIFIED in `../research/features.md` A1): `Screen` (15 subclasses, `ui/AppState.kt:36`), `SettingsCategory` (13 entries, `ui/components/settings/SettingsDialog.kt:453`), `EditorCommand` ids (24, `EssentialCommands.getAll()` at `editor/commands/EssentialCommands.kt:856`), `SyncState` (14 members, `git/model/SyncState.kt:9`), `ShortcutTable` (9 bindings, `ui/components/ShortcutTable.kt:24`), `BlockType` (12). Reflection is JVM-only, `sealedSubclasses` needs kotlin-reflect (pinned in `MODULE.bazel:199` but not a `business_tests` dep), and detekt does not run under Bazel (`../research/stack.md`).

## Decision

Hybrid, as hassfest / Kubernetes feature gates do (`../research/features.md` Part B 2, 6):

1. **Derive the obligation list** in jvmMain (see ADR-001 addendum) without kotlin-reflect: `FeatureCandidates.all()` returns candidate ids (`screen:Flashcards`, `settings:VAULT`, `cmd:text.bold`, `sync:Idle`) from explicit lists. List completeness is cross-checked by the mechanism chosen in spike S0.1 (Java `getPermittedSubclasses`, kotlin-reflect with a Bazel dep, or a bundled-source regex).
2. **Declare the rows** (ADR-001). Each row `covers` candidate ids. Many-to-one is required: 14 sync states roll up to one feature, commands roll up by prefix.
3. **Two-way structural check** (businessTest): candidate without entry or exemption; entry covering a non-existent candidate; exemption with blank detail, no owner, or past expiry; duplicate ids; candidate claimed by two entries. Detail in plan Story A1.1.
4. `SyncState` yields one candidate per state (14) and a feature row `covers` all of them explicitly, so a new state surfaces as an unregistered-candidate warning (adversarial review B2). No wildcard coverage.
5. Out of derivation on purpose: i18n keys, `BlockType`/`FormatAction` (syntax features are declared rows with `kind = SYNTAX`; shortcut candidates ARE derived (`shortcut:<FormatAction>`, 9); `ShortcutTableConsistencyTest` keeps pinning the table itself), ComponentGallery (component-level sibling, `../research/features.md` Answers).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Pure declare | Self-stale at discovery (the failure that already hit `KNOWN_EXEMPT_SCREENS`) |
| Pure derive (annotations/reflection) | Misses features that are not a class/enum entry; SOURCE-retention exemptions invisible; reflection not portable to Bazel `business_tests` today |
| Derive via Konsist/KSP | Cost without added capability (ADR-001) |
| Detekt rule per source (copy `MissingHelpPageAnnotationRule` for enums, `SyncState`, commands) | PSI-only, cannot read demo pages or site mdx, Gradle-only (not in the Bazel PR gate). Kept for Screens until parity only |

## Consequences

Positive: discovery cannot go stale; a compile error is the first signal for new Screens and sync states; the same registry data serves coverage, staleness and (optionally) a Help menu.

Negative: the explicit candidate lists are one more place to touch (mitigated: the registry test names the missing entry, plus the completeness check); `EssentialCommands.getAll()` contains hidden/palette-excluded commands that need roll-up rows or exemptions (most are `hidden = true`, `EssentialCommands.kt` header comment); candidate-id naming becomes a stable contract (ids must not derive from `t()` output or titles: `../research/pitfalls.md` 2h).

Revisit: if the detekt Screen rule proves necessary for IDE-time feedback after B3.1, reinstate it as a thin rule that checks the registry file (plan T-B3.1.3).
