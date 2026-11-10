# ADR-001: Sketches live in new unit-space tables, not in `image_annotations`

Status: ACCEPTED (plan review patch, 2026-11-09) | Epic: E2, E3

## Context
`ImageAnnotation` requires a photo `filePath` and a block/page owner; `MeasurementAnnotation` stores normalised 0..1 points and a `Double` metre value (research/codebase.md section 1). A blank-canvas sheet has neither a photo nor a pixel basis.

## Decision
Add additive tables `sketch_projects`, `sketch_sheets`, `sketch_primitives`, `sketch_dimensions`. Sheet geometry (points) is integer sixteenths of an inch; observed dimension values are exact rationals (num/den) with a rational precision (ADR-002), so values such as `5.4"` and `37.9"` are stored exactly; `sketch_projects` also carries the reconcile base (`base_revision`, `last_synced_sidecar_hash`) and `sketch_dimensions` precision columns are nullable (a count has none). The tables are a CACHE of the sidecar bundle (ADR-005). Column-level definitions live once, in plan Section 5. An imported PNG drawing reuses `ImageAnnotation` + `Calibration` (Story 4.5, behind C.1); an imported SVG is a sheet row with `source_asset_uuid`. Each table is in `SteleDatabase.sq` (new section at the end of the file) and appended to `MigrationRunner.all`; indexes are in `MigrationRunner.all` too; `@DirectSqlWrite` stubs and committed regenerated sources follow CLAUDE.md. The project slug is a generated opaque `proj-<6 hex>`, never derived from a page title or display name.

No foreign-key `ON DELETE CASCADE` is relied on (driver-dependent): `deleteProject` deletes leaf-first inside one actor transaction.

## Alternatives rejected
- Extend `MeasurementAnnotation` with a unit-space mode: couples the sketch to Epic C.1 and to a `Double` value column.
- Sidecar JSON only (no tables): loses bounded queries and the Library join; kept as the synced authoritative copy (ADR-005), not the live store.

## Consequences
Rollback = `homeSketch` flag off and ignore tables. There is NO story dependency between the sketch schema (2.1) and C.1 (6.2a): C.1 is an `AddColumn` entry in a different section of the schema; whichever merges second rebases, regenerates the sources and recommits them (the "SQLDelight generated sources" CI job enforces it). This replaces the former rule that 2.1 waits for C.1 (architecture review M8).
