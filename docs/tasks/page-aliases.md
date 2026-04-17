# Page Aliases Implementation Plan

## Objective
Enable support for page aliases. When a page has an `alias:: ` property, it should be searchable by those aliases, and clicking an alias (e.g., in autocomplete) should navigate to the main page.

## Context
Logseq uses page properties to define aliases. A page `Logseq` might have `alias:: KMP, Desktop`.
Currently, our `Page` model stores these in a `properties` map.
`InMemorySearchRepository` only searches by page name.

## Scope (Atomic)
- **Data**: Extract aliases from page properties.
- **Search**: Index and search pages by their aliases.
- **UI**: Show aliases in search results/autocomplete.
- **Navigation**: Ensure navigation works correctly for aliased names.

## Prerequisites
- [x] Basic Search System
- [x] Page Properties Support

## Atomic Steps

### 1. Repository Support (1h)
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/InMemorySearchRepository.kt`
- **Task**: Update `searchPagesByTitle` and `searchWithFilters` to check the `alias` property of pages.
- **Logic**: Split the `alias` property by comma and check if any alias matches the query.

### 2. Autocomplete Support (1h)
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
- **Task**: Update `searchPages` to include alias matches in the results.
- **UI**: (Optional) Indicate in the UI that a result is an alias match.

### 3. Navigation Support (1h)
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
- **Task**: Ensure `navigateToPageByName` can handle alias names by finding the actual page they point to.

## Validation Strategy
1. Create a page "Logseq" with `alias:: KMP`.
2. Search for "KMP" in Ctrl+K -> Should show "Logseq".
3. Type `[[KMP` in editor -> Autocomplete should suggest "Logseq".
4. Click "KMP" in autocomplete -> Should insert `[[Logseq]]` (or `[[KMP]]` and still work).
   *Note: Logseq usually inserts the alias but it points to the same page ID.*
