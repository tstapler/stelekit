# ADR-004: SVG with `data-*` and embedded metadata is the interchange; own minimal reader; DXF and FreeCAD/STEP descoped

Status: ACCEPTED, reader library choice GATED on Story 4.1a | Date: 2026-11-09 | Epic: E3, E4

## Context
Answers requirements open questions 1 and 4. The owner's CadQuery SVGs have no `viewBox`, `id` or `data-*` (research/wiki-corpus-summary.md section 3) but are deterministic: one `<g transform="scale() translate()">`, absolute-pixel `<text>` labels. No maintained KMP DXF library exists (stack-and-pitfalls.md section 1). Batik is JVM-only.

## Decision
- Reader: own subset reader over Ksoup in XML mode (Ksoup is already in `kmp/build.gradle.kts` and `MODULE.bazel`). Story 4.1a is a spike on a real sanitised SVG: keep Ksoup if `viewBox`/`text-anchor` case and `<text>` whitespace survive on JVM and the wasmJs test sources compile; otherwise `xmlutil`, whose build-file edits (`kmp/build.gradle.kts`, `MODULE.bazel`, lock repin) happen in Story 4.1b only. Outcome is appended here as an amendment. Subset: `viewBox`, `line rect polyline polygon`, `g transform` (translate, scale, matrix; reject skew), path `M L H V Z`, `text/tspan`, `data-*`, `title/desc/metadata`.
- Writer: root `<svg viewBox>` in sixteenths, `data-dim-id`, `data-value`, `data-source` per dimension group, `<metadata>` JSON holding the model; plus the sidecar JSON and a CSV of dimensions.
- Explicit descope (adversarial review minor 5): DXF export (write-only ASCII, possible later add-on), DXF read, FreeCAD/STEP metadata exchange, and Magicplan/Moasure CSV/DXF import are NOT in this plan. Requirements In Scope was reworded to match.

## Consequences
The importer calibrates from the transform scale and a named known dimension (`Total Width: <FC.opening>`), not from `px` size; view direction (x sign) is sheet metadata (discrepancy D1).
