# Hotspot Ranking Ledger

**Computed**: 2026-09-30. **Scope**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/**`.

## Methodology

For every file flagged by `mcp__kibitzer__architecture_assessment` (scope above,
`include_diagram=false`) — findings of type `long-function`, `deep-nesting`,
`long-parameter-list`, or `flag-argument` (this kibitzer configuration does not emit a
`file-size` finding type; line counts are noted in prose for the top candidates as a
corroborating signal instead) — `finding_count` is the number of such findings reported
against that file (one row per finding; a function with both a `long-function` and a
`deep-nesting` finding contributes 2). `churn_commit_count` is the number of commits
touching that file in `git log --since="6 months ago" --name-only -- kmp/src/commonMain`
(generated SQLDelight sources and `.sq` files excluded; verified via `git log --author`
that all 460 matching commits are from Tyler Stapler — no bot/automation noise to filter
in this scope). The hotspot score is:

```
hotspot_score = churn_commit_count × finding_count
```

This is the same complexity-×-churn family of technique as CodeScene/code-maat, substituting
kibitzer's static per-file finding count for a cyclomatic-complexity proxy (this repo disables
Detekt's `CyclomaticComplexMethod` rule — see `docs/architecture-audit-2026-07-04.md`, which ran
an earlier version of this analysis using line-count × revisions instead). The two methodologies
agree closely: that July audit ranked `App.kt` #1 and `StelekitViewModel.kt` #2; `App.kt` has
since been split (PR #367), and this run independently puts `StelekitViewModel.kt` at #1 of what
remains.

The table below lists every file with `hotspot_score >= 50` (32 files), plus `App.kt` and
`GraphContentActiveShell.kt` from PR #367 (included per the task that produced this ledger,
regardless of their current score, since they're the baseline this ledger supersedes).

**For future `/sdd:fix-hotspot` runs**: consult this ledger first rather than recomputing from
scratch. Update the `Status` column as files are refactored (`pending` → `done`, with the PR
link and date), and only regenerate the full ranking if churn/findings data is more than a
few months stale or scope changes.

## Ranking

| File | Kibitzer Findings | Churn (6mo commits) | Hotspot Score | Status |
|---|---|---|---|---|
| `ui/StelekitViewModel.kt` | 29 (15 deep-nesting, 8 long-function, 3 flag-argument, 3 long-parameter-list) | 76 | 2204 | pending |
| `repository/SqlDelightBlockRepository.kt` | 23 (12 deep-nesting, 9 long-function, 2 flag-argument) | 48 | 1104 | pending |
| `ui/state/BlockStateManager.kt` | 18 (9 long-function, 8 deep-nesting, 1 flag-argument) | 44 | 792 | pending |
| `db/GraphLoader.kt` | 13 (8 deep-nesting, 5 long-function) | 58 | 754 | pending |
| `ui/components/Sidebar.kt` | 26 (10 flag-argument, 6 deep-nesting, 6 long-function, 5 long-parameter-list) | 24 | 624 | pending |
| `db/RestrictedDatabaseQueries.kt` | 19 (19 long-parameter-list) | 25 | 475 | pending |
| `ui/components/PerformanceDashboard.kt` | 22 (11 deep-nesting, 10 long-function, 1 long-parameter-list) | 19 | 418 | pending |
| `db/GraphManager.kt` | 9 (7 long-function, 1 deep-nesting, 1 long-parameter-list) | 43 | 387 | pending |
| `ui/screens/JournalsView.kt` | 10 | 27 | 270 | pending |
| `ui/App.kt` | 2 (1 deep-nesting, 1 long-function) | 121 | 242 | done — refactored in PR #367, 2026-09-30, see https://github.com/tstapler/stelekit/pull/367 |
| `ui/components/SearchDialog.kt` | 12 | 19 | 228 | pending |
| `db/GraphWriter.kt` | 9 | 24 | 216 | pending |
| `ui/annotate/AnnotationEditorScreen.kt` | 21 | 8 | 168 | pending |
| `ui/components/BlockItem.kt` | 9 | 18 | 162 | pending |
| `ui/components/settings/SettingsDialog.kt` | 6 | 22 | 132 | pending |
| `ui/ScreenRouter.kt` | 4 | 33 | 132 | pending |
| `ui/components/ReferencesPanel.kt` | 18 | 7 | 126 | pending |
| `ui/components/BlockEditor.kt` | 12 | 10 | 120 | pending |
| `ui/screens/PageView.kt` | 4 | 29 | 116 | pending |
| `db/MigrationRunner.kt` | 3 | 35 | 105 | pending |
| `ui/GraphDialogLayer.kt` | 4 | 24 | 96 | pending |
| `ui/screens/git/GitSetupScreen.kt` | 5 | 19 | 95 | pending |
| `git/GitSyncService.kt` | 6 | 15 | 90 | pending |
| `editor/viewmodel/EditorViewModel.kt` | 11 | 8 | 88 | pending |
| `ui/components/BlockList.kt` | 6 | 14 | 84 | pending |
| `db/DatabaseWriteActor.kt` | 3 | 27 | 81 | pending |
| `ui/screens/AllPagesScreen.kt` | 9 | 8 | 72 | pending |
| `ui/annotate/AnnotationEditorViewModel.kt` | 7 | 9 | 63 | pending |
| `ui/annotate/AnnotationToolbar.kt` | 15 | 4 | 60 | pending |
| `parsing/InlineParser.kt` | 8 | 7 | 56 | pending |
| `repository/DatalogBlockRepository.kt` | 7 | 8 | 56 | pending |
| `repository/JournalService.kt` | 4 | 14 | 56 | pending |
| `ui/GraphContentActiveShell.kt` | 2 | 1 | 2 | done — refactored in PR #367, 2026-09-30, see https://github.com/tstapler/stelekit/pull/367 |

## Top candidate for the next refactor

`ui/StelekitViewModel.kt` is the clear #1: highest finding count (29), second-highest churn (76
commits/6mo), and at 2,773 lines / well over 100 functions it is by a wide margin the largest
file in `commonMain`. It was already independently flagged as a God ViewModel spanning ~18
bounded contexts in `docs/architecture-review-StelekitViewModel-2026-07-04.md` (then 2,574
lines) — it has grown by ~200 lines since without being addressed, making it the natural next
target for `/sdd:fix-hotspot`.
