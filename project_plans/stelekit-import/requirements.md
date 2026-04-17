# Requirements: SteleKit Import Feature

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-14

## Problem Statement

Users research topics across many sources (Claude conversations, web pages, arbitrary text) and want to consolidate findings into their SteleKit knowledge graph without manually identifying and tagging connections to existing topics. The primary user is a solo knowledge worker who wants to reduce the friction between "I found something interesting" and "it's in my graph with connections."

## Success Criteria

- Paste raw text or provide a URL → new page created in the active graph
- Imported content is automatically scanned against existing page titles and converted to `[[wiki links]]`
- Topics detected in the content that don't yet exist as pages are surfaced as suggestions for new pages
- Time from source material to linked graph page drops from minutes (manual) to seconds (automated)

## Scope

### Must Have (MoSCoW)
- **Paste raw text import**: User pastes arbitrary text (e.g. a Claude conversation summary) into an import UI and a new page is created from it
- **URL import**: User provides a URL, content is fetched and a new page is created from the retrieved text
- **Auto-tag existing topics**: Imported content is scanned against existing page titles; matches are converted to `[[wiki links]]` inline
- **Suggest new tags**: Topics detected in the imported content that don't exist as pages yet are surfaced as suggestions to create new pages

### Out of Scope (v1)
- AI summarization — user provides the summary; we do not generate one from raw content
- Full web scraping / scheduled crawling — manual one-at-a-time import only
- Bulk import — importing many pages at once
- Cross-graph import — importing into a non-active graph

## Constraints

- **Tech stack**: Kotlin Multiplatform only — no new runtimes or build targets
- **Offline-first**: URL fetching is optional / graceful-fallback; paste import must work without internet
- **No external AI API**: Auto-tagging must work using local graph data only — no OpenAI/Claude API calls for topic matching
- **Platform priority**: JVM/Desktop is the primary target; Android/iOS can follow

## Context

### Existing Work

The following subsystems already exist and should be reused or extended:

| System | Relevance |
|--------|-----------|
| **Multi-word term highlighting** | Already detects multi-word topics from existing page titles in block text — core algorithm for auto-tagging |
| **Unlinked references** | Finds occurrences of page names in content that are not yet wikilinked — directly applicable to import scanning |
| **GraphLoader / GraphWriter** | File-based import/export infrastructure; new import creates pages via `GraphWriter` |
| **Page export (MD/HTML/JSON)** | Export serialization pipeline — import is the mirror operation |
| **Search index** | Page title index can drive topic matching for auto-tagging |

### Stakeholders

- Solo user / knowledge worker (primary)
- Future: team knowledge sharing (out of scope for v1)

## Research Dimensions Needed

- [ ] Stack — URL fetching in KMP (ktor-client, okhttp, platform expectations), HTML-to-text extraction
- [ ] Features — comparable import UX in Obsidian, Roam, Logseq, Notion; how they handle auto-linking on import
- [ ] Architecture — where import fits in the existing data flow (ViewModel → GraphWriter), how to reuse the term-highlight matcher pipeline for auto-tagging, UI entry point (command palette, menu, dedicated screen)
- [ ] Pitfalls — encoding issues, large paste performance, URL fetch failure modes, false-positive tag matching on common words
