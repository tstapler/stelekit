# Validation: editor-subsystem-dead-code

**Date**: 2026-10-13

## Execution gates (must pass before merge)

| Gate | Command | Pass condition |
|---|---|---|
| G1 — Baseline green | `bazel test //kmp:business_tests` | 0 failures before any change |
| G2 — Post-deletion build | `bazel test //kmp:business_tests` | 0 failures after deleting dead packages |
| G3 — UI test gate | `bazel test //kmp:jvm_tests` (with display) or `./gradlew testDebugUnitTest` | 0 new failures |
| G4 — New tests pass | `bazel test //kmp:business_tests` after writing `CommandManagerTest.kt` | All new test cases pass |
| G5 — No reflection usage | `grep -r "stelekit.editor.Editor\|stelekit.editor.viewmodel\|stelekit.editor.persistence\|stelekit.editor.text\|stelekit.editor.state\|stelekit.editor.blocks\|stelekit.editor.input" kmp/src --include="*.kt" --include="*.xml"` | 0 matches |

## Unit test cases (AC-3: editor.commands branch coverage)

| ID | Description | Input | Expected |
|---|---|---|---|
| TC-CMD-001 | CommandManager executes known command | valid command ID | `CommandResult.Success` |
| TC-CMD-002 | CommandManager returns error for unknown ID | unknown ID string | `CommandResult.Error` |
| TC-CMD-003 | CommandConfig.isAvailable — hidden command excluded | `hidden=true` | `false` |
| TC-CMD-004 | CommandConfig.isAvailable — requiresBlock, no block | `requiresBlock=true, currentBlockId=null` | `false` |
| TC-CMD-005 | CommandConfig.isAvailable — requiresSelection, empty | `requiresSelection=true, selectionStart==selectionEnd` | `false` |
| TC-CMD-006 | CommandConfig.isAvailable — all requirements met | happy path | `true` |
| TC-CMD-007 | EssentialCommands.getAll() returns non-empty list | — | list.isNotEmpty() |
| TC-CMD-008 | Hidden commands filtered from getAvailableCommands | context with no block/selection | no hidden commands in result |

## AC checklist

| AC | Criterion | Validation method |
|---|---|---|
| AC-1 | Dead package audit confirmed | G5 grep + read-back of import grep |
| AC-2 | Dead packages deleted | G2 build green; `git log --stat` shows 29 files removed |
| AC-3 | `editor.commands` branch coverage ≥ 30% | TC-CMD-001 through TC-CMD-008 pass; optional `jacocoTestReport` XML spot-check |
| AC-4 | No regressions | G3 gate green |
| AC-5 | `editor/format/` resolved (deleted) | G5 grep shows 0 remaining references |

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| A missed import causes compile failure | Low — grep confirmed | Batch delete + compile immediately |
| `EssentialCommands` object initializer branches inflate branch total | Medium | Use direct `CommandConfig.isAvailable()` tests |
| `bazel test //kmp:jvm_tests` UI tests need X11 | High on Wayland | Use `scripts/jvm-display-check.sh`; fall back to `//kmp:business_tests` |
