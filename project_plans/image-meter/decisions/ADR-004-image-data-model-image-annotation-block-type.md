# ADR-004: Image Data Model: `image_annotation` blockType Extending Existing Block

**Date**: 2026-05-16
**Status**: Accepted
**Deciders**: Tyler Stapler

## Context

The image-meter feature introduces annotated images as a first-class entity in SteleKit graphs. An annotated image needs to:
- Appear on a page, be linked from other pages, appear in backlinks, and be searchable (same as any block)
- Carry calibration metadata (calibration method, pixels-per-meter, unit, GPS, bearing)
- Own a variable number of measurement annotations (distances, areas, angles, labels)
- Survive graph export and import (NFR-2 graph portability)
- Support export to Google Drive with measurement data attached

The architectural question is whether annotated images should be modeled as a new top-level entity alongside `Block` and `Page`, or as an extension of the existing `Block` model.

## Decision

Annotated images are represented as **`Block` records with `blockType = "image_annotation"`**. This is an extension of the existing `validBlockTypes` set, consistent with how other block variants (`bullet`, `paragraph`, `heading`, `code`) are discriminated.

**Block content** (the `block.content` field) stores the Logseq-compatible Markdown image reference:
```
![Image description](../assets/images/2026-05-16-<uuid>.jpg)
```

**Block properties** (the `block.properties` JSON field, surfaced as `::key:: value` in Logseq notation) carry calibration and source metadata:
```
::image-id:: <uuid>
::calibration:: ble-laser
::px-per-meter:: 412.5
::unit:: m
::location:: 49.2827,-123.1207
::bearing:: 273.4
::source:: camera
```

**Two supplementary storage layers** hold measurement-specific data that would be too large or too relational for block properties:

**Layer A — SQLDelight tables** (fast indexed query, gallery view, tag filtering):
- `image_annotations` table: one row per annotated image, keyed by `uuid` (== `block.properties["image-id"]`)
- `measurement_annotations` table: one row per annotation primitive (distance, area, angle, label, grid), with `points_json` storing normalized [0,1] coordinates
- Foreign key: `image_annotations.block_uuid → blocks.uuid ON DELETE CASCADE`

**Layer B — JSON sidecar files** (graph-portable plain text, NFR-2 compliance):
- Path: `<graph>/.stelekit/images/<image-uuid>.measure.json`
- Written atomically on every annotation save; mirrors the SQLDelight rows
- Authoritative for export and DB recovery (same pattern as UUID sidecar recovery in `SidecarManager`)

The `ImageAnnotationRepository` and `MeasurementAnnotationRepository` are new repository interfaces added to `RepositorySet`, following the exact same Arrow `Either`, `DatabaseWriteActor`, and `PlatformDispatcher.DB` patterns as `SqlDelightBlockRepository` and `SqlDelightPageRepository`.

A `MeasurementSyncer` writes named measurement values back to the parent block's properties after save (e.g., `distance:wall_A` → `3.24 m`), making measurements queryable through the existing block property system and usable in template syntax.

## Alternatives Considered

**New top-level entity: `AnnotatedImage` alongside `Block` and `Page`**
- Would require duplicating search indexing, backlink tracking, page-block associations, export serialization, and block rendering dispatch — all of which already work for `Block`.
- Graph portability (NFR-2) would require a separate export format for `AnnotatedImage` entities; currently graph export serializes blocks as Markdown, so any non-block entity would need a separate mechanism.
- Rejects the principle of reuse already established in the codebase.
- Creates a parallel entity hierarchy that diverges from the Logseq data model SteleKit is migrating from.

**Storing all measurement data in block properties only**
- Block properties are a flat key-value map (JSON string); they are not designed for ordered lists of measurement primitives with multi-point geometries.
- Storing 20 measurements with 4–8 coordinate points each as block properties would produce a properties blob that degrades block search performance and becomes unreadable in the Markdown export.
- Eliminated because measurement data requires table structure and indexed queries, not flat key-value storage.

**Storing measurement data as SQLDelight BLOBs (binary) inside the `blocks` table**
- BLOBs in SQLDelight are opaque to queries; gallery view, tag filtering, and measurement aggregation queries would be impossible without deserializing every row.
- BLOBs break graph portability — they cannot be represented in Markdown or as human-readable sidecar files.
- Eliminated because it conflicts with both NFR-2 (portability) and NFR-3 (deterministic reproduction from stored inputs).

**Dedicated separate database file for image annotations**
- Adds operational complexity (two database files, two migration paths, two write actors).
- No clear benefit over new tables in the existing `SteleDatabase.sq` schema, since the image annotation tables have foreign keys into `blocks`.
- Eliminated.

## Consequences

**Positive**
- Zero new infrastructure for search, backlinks, page linking, export, or block rendering dispatch — all of these work for free because `image_annotation` blocks are blocks.
- Graph portability: the Markdown representation in `block.content` (`![](../assets/images/...)`) is valid Logseq syntax; the JSON sidecar in `.stelekit/images/` carries measurement data in a plain-text, git-diffable format.
- SQLDelight tables for `image_annotations` and `measurement_annotations` are recoverable from sidecars on import, using the same DB-recovery pattern as UUID sidecar recovery (`ImageSidecarIndexer` parallels `SidecarManager`).
- `MeasurementSyncer` makes measurement values queryable from block properties — usable in page queries, templates, and search without any changes to the query engine.
- `ON DELETE CASCADE` on `image_annotations.block_uuid → blocks.uuid` ensures measurement data is automatically cleaned up when the owning block is deleted, preventing orphaned records.
- The `IN_MEMORY` backend path for tests is trivially satisfied by implementing `ImageAnnotationRepository` and `MeasurementAnnotationRepository` with in-memory maps, consistent with the test infrastructure pattern.

**Negative / Risks**
- Two-layer storage (SQLDelight + JSON sidecar) requires a dual-write on every annotation save. The sidecar write must be atomic (write-to-temp, rename) to avoid corrupt sidecars on process death mid-write.
- The SQLDelight tables must stay in sync with the sidecar files; divergence (e.g., if a sidecar is edited externally) requires a conflict resolution strategy. Current design treats the sidecar as authoritative on import/recovery; SQLDelight is rebuilt from sidecars.
- Block properties for image metadata (`::calibration::`, `::px-per-meter::`, etc.) will appear in the raw Markdown export. These are valid Logseq properties and will render as metadata in Logseq; however, users who open the exported graph in Logseq directly will see these properties on image blocks, which may be unexpected.
- `@DirectSqlWrite` annotations and `RestrictedDatabaseQueries` forwarding stubs must be added for every new `INSERT`/`UPDATE`/`DELETE` query in the two new tables, following the existing write-enforcement convention.
- The `RepositorySet` extension (two new repository fields) requires updating all `RepositorySet` construction sites and the factory methods in `RepositoryFactory` for both `IN_MEMORY` and `SQLDELIGHT` backends.
