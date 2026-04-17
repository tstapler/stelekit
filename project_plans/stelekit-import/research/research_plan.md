# Research Plan: SteleKit Import Feature

**Date**: 2026-04-14
**Input**: `project_plans/stelekit-import/requirements.md`

## Subtopics

### 1. Stack — KMP URL Fetching & HTML Extraction
**File**: `research/stack.md`
**Search cap**: 4 searches
**Key questions**:
- Best KMP library for HTTP/URL fetching (ktor-client, okhttp, platform expectations)
- HTML-to-plain-text extraction in KMP (commonMain vs platform-specific)
- Clipboard access in Compose Multiplatform Desktop
**Axes for trade-off matrix**: KMP compatibility, offline support, HTML parsing capability, bundle size

### 2. Features — Import UX in Comparable Tools
**File**: `research/features.md`
**Search cap**: 4 searches
**Key questions**:
- How does Logseq handle paste/import with auto-linking?
- How does Obsidian handle URL import and auto-tag suggestions?
- What UX patterns (dialog, sidebar, command palette) work best for import?
**Axes for trade-off matrix**: Auto-link accuracy, UX friction, new-page suggestion quality, discoverability

### 3. Architecture — Integration with Existing SteleKit Subsystems
**File**: `research/architecture.md`
**Search cap**: 3 searches
**Key questions**:
- How to thread import through ViewModel → GraphWriter pipeline
- How to reuse the multi-word term highlight matcher for auto-tagging on import
- Where does the import UI live (command palette entry, dedicated screen, inline paste handler)?
**Axes for trade-off matrix**: Code reuse, separation of concerns, testability, UI discoverability

### 4. Pitfalls — Known Failure Modes
**File**: `research/pitfalls.md`
**Search cap**: 3 searches
**Key questions**:
- False-positive wiki-link matching (common words, partial matches)
- Large-paste performance (50KB+ text scanning against page title index)
- URL fetch failure modes (timeouts, paywalls, encoding)
- Encoding edge cases (UTF-8, special chars in page titles)
**Axes**: Impact severity, likelihood, mitigation complexity

## Synthesis Target
`research/synthesis.md` — ADR-Ready summary for `/plan:adr`
