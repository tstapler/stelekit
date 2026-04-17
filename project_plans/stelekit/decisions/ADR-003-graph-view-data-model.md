# ADR-003: Graph View Data Model — Page-Level Graph with Lazy Edge Loading

**Date**: 2026-04-16
**Status**: Proposed
**Feature**: Graph View (Knowledge Graph Visualization)

---

## Context

Logseq's graph view operates at the *page* level (each node = one page; each edge =
one or more `[[wikilink]]` references between pages). SteleKit's existing repositories
operate at the *block* level — blocks contain wiki links, not pages directly.

Two granularities were considered:

| Granularity | Nodes | Edges | Typical count |
|-------------|-------|-------|---------------|
| **Page-level** | Pages | Page→Page wiki links | ~500–5 000 nodes |
| **Block-level** | Blocks | Block→Block references | 10 000–100 000+ nodes |

## Decision

**Page-level graph** with edges derived from `[[wikilink]]` occurrences in blocks,
loaded lazily in background after initial render.

## Rationale

* **Logseq parity**: Logseq's graph view shows pages, not blocks.
* **Performance**: Block-level graphs are 10–100× larger, requiring aggressive
  LOD strategies that add significant complexity before any value is delivered.
* **Existing query infrastructure**: `BlockRepository.getLinkedReferences(pageName)`
  already returns blocks mentioning a page by name, so deriving page→page edges is
  a groupBy operation over existing data — no new SQL queries needed for MVP.
* **Incremental delivery**: A `GraphViewRepository` interface is introduced so
  a future block-level view can be added without changing the Canvas composable.

## Consequences

* **Positive**: Small node set; reuses existing reference queries; direct Logseq
  feature parity.
* **Negative**: Loses block-level granularity. Page namespaces must be handled
  explicitly (namespace separator `/` creates implicit parent–child edges).
* **Neutral**: Edge weight (number of references between two pages) is computed and
  stored per edge, allowing future visual encoding (edge thickness, opacity).

## Patterns Applied

* **Repository** — `GraphViewRepository` provides `getGraphNodes()` and
  `getPageEdges(pageUuid)` flows.
* **Value Object** — `GraphNode(uuid, name, namespace, isJournal, degree)` and
  `GraphEdge(fromUuid, toUuid, weight)` are immutable.
* **Lazy Loading** — initial render uses page stubs (nodes only); edges are populated
  asynchronously and cause a physics re-seed.
