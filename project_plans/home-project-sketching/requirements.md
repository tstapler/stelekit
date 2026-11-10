# Requirements: home-project-sketching

**Date**: 2026-11-09
**Type**: feature addition (extends the image/annotation/asset pipeline)
**Complexity**: 3 — multiple epics; external integrations (wiki sync, LLM, other-app import/export)

Derived from the owner's `/goal` and from the owner's private wiki (a closet design page, a build-plan page, and three rendered drawings). **Privacy: this repository is PUBLIC.** No street address, household name, wiki page title or real project slug may appear in anything committed under `project_plans/` or in test fixtures; the project is referred to as `proj-closet-1` and the two outer zones as `left`/`right`. The private research notes live at `~/.cache/stelekit-private/home-project-sketching/` (git-excluded) and are enforced out of the repo by a CI guard (plan Story 0.2). The interview was skipped because the goal text and wiki answer every required information goal.

## Problem Statement
The owner plans home projects (current: primary-bedroom closet remodel) and keeps the measurements in three unconnected places: a YAML dimension model rendered by CadQuery into elevation/section drawings, a markdown measurements table and build-plan prose in the wiki, and photos. SteleKit can measure *on a photo* (distance/area/angle/label/grid-ref annotations with calibration) but cannot (a) sketch a plan/elevation/section from scratch, (b) treat an existing drawing as a calibrated sheet, (c) reconcile a number that appears in the drawing, the table and the prose, or (d) hand a consistent, provenance-tagged measurement model to an LLM when wiki content syncs. Separately, the image/asset pipeline still has known gaps from the memory/nav planning (`project_plans/annotation-memory-and-nav-ia`): Library parity, benchmark promotion, crash behaviour changes, calibration basis, tag-JSON escaping.

## Baseline
- Closet zone widths exist as: `closet.yaml` (source of truth), the wiki table, the PNG/SVG elevation and section, and prose (the opening width, rod heights, niche heights, depth). Consistency is checked by eye. The existing arithmetic: the five zone widths sum to the opening; the middle sum is the opening minus the outer zones. The real quantities live only in the private `~/.cache/stelekit-private/home-project-sketching/closet-constants.md` and are referred to as `FC.*` names in the plan (R3-V M8).
- Annotation editor measures photos only; no blank-canvas sketch; no dimension reconciliation; annotations are not exposed to the wiki-sync/LLM path in a fused form.

## Users / Consumers
- The owner on Android phone/foldable and desktop; another household member reads the wiki.
- Downstream: the LLM analysis that runs on wiki sync (consumes a machine-readable fused model + human-readable summary); the Logseq wiki pages (markdown the app writes/reads).

## Success Metrics
1. Closet golden fixture: running the SHIPPED extractors (SVG labels and geometry, markdown table, minimal YAML, prose) over a frozen, sanitised snapshot of the closet sources, through the project's dimension registry, yields a fused model in which every one of the ≥10 closet dimensions has ≥2 independent sources, and the arithmetic invariants (zone sum = `FC.opening`, middle sum = `FC.zoneMiddle`, rod-clearance values, stud-offset list) are checked automatically; seeded disagreements (e.g. the table says `FC.zoneLeftLongTableMutated`, the drawing says `FC.zoneLeftLongTable`) are reported as conflicts with both sources named. Expected counts are hand-written constants in the test source (not only a stored golden); CI refuses a golden update. The metric is claimed only once the sanitised real snapshot (owner decision OD-H1) replaces any synthetic placeholder. Baseline: 0 automated checks.
2. Owner can create a dimensioned plan/elevation/section sketch in-app without any photo, with exact-fraction dimensions (1/16" granularity for sheet geometry, FEET_INCHES; observed dimension values are exact rationals so values such as `5.4"` and `37.9"` are representable), and export it as SVG + a machine-readable model. Canvas performance is falsified relatively: frame time of the 200-primitive scenario is compared with an empty-canvas run on the same runner (plus a mutation probe), not against an absolute emulator fps. Baseline: not possible.
3. On wiki sync, the LLM-facing artifact (JSON + markdown digest) is regenerated for any project whose sources changed and passes a schema/privacy gate; the digest contains a pre-computed verdict line for "do all closet zone widths sum to the opening?" plus all five zone values, and the prompt built from it passes a hard-block outbound gate (checked by a golden prompt test with a stubbed model — no live LLM in CI; whether a real model answers correctly is NOT claimed in CI and would need an owner-supplied key).
4. Pipeline carry-over items closed, where "closed" is defined per item: Library parity = every named `LibraryParityTest` method (one per row of the D.6 parity table) is green AND `rg 'GalleryViewModel|AssetBrowserViewModel|AssetItemCard' kmp androidApp` returns nothing (deletion alone does not satisfy it); RapidPlacement/BackgroundIdle = both scenarios run in `android-benchmark.yml` and appear in its summary; v0.107.0 release = `gh release view v0.107.0 --json assets` lists APK, desktop distributables and the formula; tag JSON escaping = round-trip test with quotes/backslashes green; calibration basis = `calibration_basis_version` column present and legacy rows unchanged. An item may instead be re-scoped only by an owner-approved entry in the plan's Owner decisions table.

## Size
Large (3–15M CU), informational — full scope is the default (ESTIMATION.md). 
**Wall-clock blockers**: v0.107.0 release dispatch (owner approval); owner's `importance=` Logs line after next kill (gates crash behaviour changes); ≥10 CI runs before benchmark gate promotion (B.7b); PRs #564/#565 review/CI; owner review of UX choices for sketch tooling; no live-LLM evaluation without owner-provided key.

## Constraints
- KMP/Compose, Arrow `Either`, SQLDelight + `MigrationRunner`, `DatabaseWriteActor`, bounded reads (CLAUDE.md).
- Wiki is a separate git repo (`wiki-path`); the app must write only through existing graph writers (no behind-the-back writes to the active graph).
- No raw imagery or filenames in LLM/share artifacts without the existing redaction gate (`LogRedactor`/privacy check) — home layout is personal data.
- Android debugging via persisted logs, not logcat; device checks on `Pixel_Fold` AVD.

## Non-functional Requirements
- **Performance SLO**: sketch canvas frame time with 200 primitives stays within a calibrated ratio of an empty-canvas run on the same runner, with p95 frame time ≤ 24 ms on the `Pixel_Fold` AVD (relative form, Story 3.8; an absolute emulator fps is not asserted); fusion of one project < 1 s for ≤500 dimensions; budgets live in `benchmarks/budgets/`.
- **Scalability**: tens of projects, ≤500 dimensions each.
- **Security classification**: names, addresses, page titles and filenames are confidential; closet DIMENSIONS are the owner's data (whether sanitised fixtures with real numbers are committed is the owner's call, OD-H1; the plan keeps the real quantities out of plan prose either way). LLM calls only on explicit user action or a new, separate per-graph opt-in ("Analyse projects after sync", off by default), AND only with an explicitly chosen provider for the project-analysis feature (an unset provider means disabled, never Auto). Outbound payloads pass a hard-block gate and contain only the opaque project slug, never page titles, addresses or filenames; imported drawings and sketch-managed files are excluded from the cloud image-description pipeline on every path into the asset index (registration, backfill, hash dedupe and file content; plan Story 0.3).
- **Data residency**: local-first; nothing leaves device except via existing LLM provider settings.

## Scope
### In Scope
- Survey of aligned apps (see research): measurement/CAD/sketch tools — e.g. Magicplan, Moasure, Shapr3D, SketchUp, Floorplanner, Planner 5D, Sweet Home 3D, FreeCAD/CadQuery workflows, Omni/Logseq/Obsidian canvas — to find missing features and interchange formats. Interchange decision (ADR-004): SVG with `data-*` plus a JSON sidecar and CSV. DXF export and FreeCAD/STEP metadata exchange are DESCOPED from this plan (recorded as later add-ons); no DXF/STEP is read or written.
- Sketch mode: blank-canvas plan/elevation/section sheets, snapped lines/rectangles with typed dimensions, constraints (equal, sum-to-total), named dimensions/zones, layers, per-sheet scale.
- Drawing import: SVG/PNG elevation & section as calibrated sheets (calibration from named known dimensions), dimension extraction from SVG text/metadata where present (CadQuery output) and manual tagging otherwise.
- Measurement fusion: a project model joining table rows, drawing dimensions, YAML (`closet.yaml`) values and prose numbers with provenance, units, tolerance, conflict detection and invariant checks.
- Wiki bridge: read measurement tables from pages, write the fused digest back to a dedicated page the app owns outright (ADR-006: new-file create, then marker-and-hash-guarded replace of that owned page; existing user pages are only ever read or spliced; if the owner declines the exception (OD-H7) the digest is written as an asset file instead and this requirement is amended), LLM-facing JSON/markdown artifact regenerated on sync.
- Remaining image/asset pipeline improvements: finish Epic D parity and delete old screens; Epic C (annotation quality: calibration basis, narrow-canvas crash, loupe/placement polish, non-drag placement mode); Epic A behaviour changes after owner's `importance=` data; benchmark scenario migration to Library; tag JSON escaping fix; release v0.107.0 assets.
### Out of Scope
- Full parametric 3D CAD/BIM; photogrammetry; live LLM integration tests in CI; replacing the owner's CadQuery pipeline (we interoperate with it); iOS-specific work.

## Rabbit Holes
- Parsing arbitrary PNG drawings (OCR of dimension text) — treat as manual tagging first; OCR is a later spike.
- Constraint solving beyond sum/equal/offset — use a tiny purpose-built solver, not a general CAD kernel.
- Fractional-inch parsing/printing round-trips (1/16" rounding, `17 5/16"` vs `17.3125`) — one canonical rational type.
- Wiki round-tripping of tables: splice rules from cross-graph-merge ADRs apply.
- Android storage/SAF for SVG import; wasmJs limits for new commonMain code.

## Alternatives Considered
Adopt an existing app as the sketch surface (Magicplan/Moasure/Shapr3D) and only import its output; extend SteleKit's annotation editor (chosen as default for plan/elevation sheets); embed Excalidraw-style canvas; DXF-first interchange. Decision deferred to research/ADRs.

## Feasibility Risks
- Existing `ImageAnnotation`/`MeasurementAnnotation` model is photo-pixel-normalised; a sketch needs a unit-space model (new table + migration).
- Wiki table formats vary; extraction may need LLM assistance, which must remain optional.
- CadQuery SVG may not carry semantic dimension labels.

## Observability Requirements
Log fusion runs (counts of dimensions/conflicts, no values) through `Logger`; reuse `DiagnosticsSample`; probe scenario for sketch canvas and fusion in the benchmark harness.

## Risk Control
Feature flag `homeSketch` default off until verified on device; additive migrations only; fused-model writes behind the existing write actor; rollback = flag off + ignore new tables.

## Open Questions (answers recorded in the ADRs)
- Which interchange for sketches: SVG with `data-*` dimension attributes vs DXF vs both?
- Should fused models live in the graph as pages/blocks, a sidecar JSON, or a DB table?
- Where does `closet.yaml` get read (wiki repo path, or user imports)?
- Which survey apps expose importable exports worth supporting (Magicplan DXF/PDF, Moasure CSV)?
