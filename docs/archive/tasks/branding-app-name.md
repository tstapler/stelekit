# Branding: App Identity String Rename

**Epic**: Replace all user-visible "Logseq" strings with "Stelekit"  
**Status**: Planned

---

## Background

The application still presents itself as "Logseq" in several user-facing locations:

- **Window title**: `Window(title = "Logseq", ...)` in `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/App.kt`
- **Onboarding copy**: `"Welcome to Logseq"` appears in I18n.kt in both English and Chinese translations (`welcome`, `onboarding.welcome.title`, `onboarding.welcome.desc` which calls it "A privacy-first knowledge management platform")
- **Composable function name**: `LogseqApp` in `App.kt` — this is an internal identifier but affects code readability and grep results
- **ViewModel class name**: `LogseqViewModel` in `LogseqViewModel.kt` — same: internal but creates noise

The goal is to replace user-visible strings only. Package paths (`dev.stapler.stelekit`) are already correct and must not change. Internal class/function identifiers like `LogseqApp` and `LogseqViewModel` are in scope for this task because they affect developer experience, but file structure and package names are out of scope.

### Preserve: Format Compatibility References

The following are references to a file format, not a brand name, and must be preserved:
- `parsing/LogseqParser.kt` — class name references the format it parses
- Any comment or string like "Logseq graph format", "Logseq-compatible", "Opens your existing Logseq graph"
- The description in README/onboarding that explains Stelekit opens Logseq-format graphs

---

## Scope of Changes

### User-Visible Strings (Required)

| Location | Current | Target |
|---|---|---|
| Window title | `"Logseq"` | `"Stelekit"` |
| Onboarding welcome title (EN) | `"Welcome to Logseq"` | `"Welcome to Stelekit"` |
| Onboarding welcome title (ZH) | `"欢迎使用 Logseq"` | `"欢迎使用 Stelekit"` |
| Onboarding welcome description (EN) | `"A privacy-first knowledge management platform."` | `"A local-first outliner. Your notes stay on your disk, forever."` |
| Onboarding welcome description (ZH) | `"隐私优先的知识管理平台。"` | `"本地优先的大纲工具。您的笔记永远保存在您的磁盘上。"` |

### Internal Identifiers (Rename for Code Clarity)

| Identifier | Current | Target | File |
|---|---|---|---|
| Composable | `LogseqApp` | `StelekitApp` | `ui/App.kt`, `desktop/ui/App.kt` |
| ViewModel class | `LogseqViewModel` | `StelekitViewModel` | `ui/LogseqViewModel.kt` |
| ViewModel file | `LogseqViewModel.kt` | `StelekitViewModel.kt` | Rename |

Note on file rename: Kotlin file renames require updating the import in every file that references the class. The rename of `LogseqViewModel` is coupled with the color token rename (Task 2.1 in `branding-color-tokens.md`) — both touch `App.kt`. Coordinate these tasks or execute in the same branch.

---

## Implementation Plan

### Story 1: Update User-Visible Strings

#### Task 1.1 — Fix Window Title
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/App.kt`
- Change `title = "Logseq"` → `title = "Stelekit"`
- Effort: 15 minutes

#### Task 1.2 — Update I18n Strings for Onboarding
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/i18n/I18n.kt`
- Update English translations:
  - `"welcome"` → `"Welcome to Stelekit"`
  - `"onboarding.welcome.title"` → `"Welcome to Stelekit"`
  - `"onboarding.welcome.desc"` → `"A local-first outliner. Your notes stay on your disk, forever."`
- Update Chinese translations:
  - `"welcome"` → `"欢迎使用 Stelekit"`
  - `"onboarding.welcome.title"` → `"欢迎使用 Stelekit"`
  - `"onboarding.welcome.desc"` → `"本地优先的大纲工具。您的笔记永远保存在您的磁盘上。"`
- Effort: 30 minutes

#### Task 1.3 — Audit All UI Files for Remaining "Logseq" User Strings
- Files: All `*.kt` under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/` and `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/`
- Search for literal string `"Logseq"` in UI source; inspect each hit to determine if it is:
  - (a) User-visible label — rename to "Stelekit"
  - (b) Format compatibility reference ("Logseq graph format") — preserve
  - (c) Internal code identifier — handled in Story 2
- Effort: 1 hour

### Story 2: Rename Internal Composable and ViewModel Identifiers

#### Task 2.1 — Rename LogseqApp → StelekitApp
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/App.kt`
- Rename the composable function `LogseqApp` → `StelekitApp`
- Update the import and call in `desktop/ui/App.kt`
- Effort: 30 minutes

#### Task 2.2 — Rename LogseqViewModel → StelekitViewModel
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/LogseqViewModel.kt` (rename to `StelekitViewModel.kt`)
- Rename the class from `LogseqViewModel` to `StelekitViewModel`
- Update all imports and usages: `App.kt`, `AppState.kt`, `screens/JournalsView.kt`, any other file that imports `LogseqViewModel`
- Effort: 1–2 hours (class rename has multiple import sites)

---

## File Change Summary

| File | Change Type |
|---|---|
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/App.kt` | Update window title; update `LogseqApp` call |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/i18n/I18n.kt` | Update 6 translation strings (EN + ZH) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | Rename `LogseqApp` → `StelekitApp`; update theme/mode references if coordinated with color task |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/LogseqViewModel.kt` | Rename file + class to `StelekitViewModel` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` | Update any `LogseqViewModel` type references |
| Multiple screen/view files | Update `LogseqViewModel` import and usage |

---

## Known Issues

### Risk: ViewModel Rename Touches Many Files
`LogseqViewModel` is the central application ViewModel. It is likely referenced in:
- `App.kt` (instantiation)
- `AppState.kt` (type references)
- `screens/JournalsView.kt` (passed as parameter or used directly)
- `screens/PageView.kt`
- Potentially `TopBar.kt`, `Sidebar.kt`

A partial rename (class renamed but import not updated in one file) will cause a compile error, not a runtime issue. Run `./gradlew :kmp:compileKotlinJvm` after each file to catch misses early.

**Mitigation**: Do the ViewModel rename in a single focused commit. Use IDE rename refactoring if available to catch all call sites automatically.

### Coordination: Color Token Task Overlap
Both this task (Task 2.1, renaming `LogseqApp`) and the color token task (Task 2.1 in `branding-color-tokens.md`, updating `LogseqTheme` references in `App.kt`) modify `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`. These tasks should be executed in the same branch or sequentially to avoid a merge conflict on that file.

### Note: Chinese Translation Accuracy
The updated Chinese description string was drafted without a native speaker review. If the project has Chinese-speaking contributors, have them verify `"本地优先的大纲工具。您的笔记永远保存在您的磁盘上。"` before merge.

### Preserve: LogseqParser.kt Class Name
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/LogseqParser.kt` contains `class LogseqParser`. This refers to the Logseq markdown format the parser handles, not the Stelekit brand. Do not rename this class or file in this task. It may be worth renaming to `LogseqFormatParser` or `LogseqCompatParser` in a future cleanup task to clarify intent, but that is out of scope here.

---

## Success Criteria

- The desktop application window title reads "Stelekit"
- The onboarding welcome screen reads "Welcome to Stelekit"
- The onboarding description no longer says "privacy-first knowledge management platform"; uses brand-aligned copy
- `grep -r '"Logseq"' kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/` returns zero results for user-facing string literals
- `grep -r '"Logseq"' kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/` returns zero results
- `LogseqParser.kt` is untouched (format reference preserved)
- `./gradlew :kmp:compileKotlinJvm` passes with zero errors
