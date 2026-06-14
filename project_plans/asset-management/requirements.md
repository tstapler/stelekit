# Asset Management — Requirements

## Project Summary

SteleKit currently stores all user attachments in a flat `<graphRoot>/assets/` directory (plus `assets/images/` for camera captures). As graphs grow, this flat directory becomes an unmanageable pile of hundreds of files with no discoverability. This project delivers:

1. **Phase 1 — Organization + Browser**: Structured intake routing and an in-app asset browser
2. **Phase 2 — On-Device ML Tagging + Cloud-Optional enrichment**: Auto-labeling via on-device ML and optional cloud APIs

---

## Existing Infrastructure (Do Not Break)

| Component | Location | Role |
|---|---|---|
| `MediaAttachmentService` | `service/MediaAttachmentService.kt` | Platform attach interface |
| `AttachmentFileNaming` | `service/AttachmentFileNaming.kt` | Unique filename dedup |
| `ImageStoragePathResolver` | `db/ImageStoragePathResolver.kt` | `assets/images/<date>-<uuid>.jpg` |
| `ImageSidecarSchema` | `db/sidecar/ImageSidecarSchema.kt` | JSON sidecar with `tags` field |
| `ImageSidecarManager/Indexer` | `db/sidecar/` | Sidecar read/write/rebuild |
| `ImageAnnotationRepository` | `repository/ImageAnnotationRepository.kt` | SQLDelight annotation store |
| `SteleKitAssetFetcher` | `ui/components/SteleKitAssetFetcher.kt` | Coil asset loading |

---

## Phase 1 — Organization + Asset Browser

### REQ-1.1 — Typed subfolder routing on attach

When a file is attached (pick, drag-drop, clipboard paste), it is automatically routed to a subfolder based on MIME type:

| Type | Subfolder |
|---|---|
| image/* | `assets/images/` (existing) |
| application/pdf | `assets/pdfs/` |
| audio/* | `assets/audio/` |
| video/* | `assets/video/` |
| text/*, application/json, etc. | `assets/documents/` |
| everything else | `assets/files/` |

The `AttachmentResult.relativePath` markdown link must continue to use `../assets/<subfolder>/<filename>` form for Logseq compatibility.

### REQ-1.2 — User-controlled regrouping

Users must be able to move assets into custom subdirectories via the asset browser (see REQ-1.4). The move operation must:
- Update the file on disk
- Update any `![]()` or `[[]]` references in the markdown pages that link to the old path
- Write the new path into the sidecar (for images)

### REQ-1.3 — Asset metadata index

A lightweight metadata index must be maintained for all assets in the graph:

```
<graphRoot>/.stelekit/asset-index.json (or SQLDelight table)
```

Each entry contains:
- `uuid` (stable identifier, survives renames)
- `filePath` (relative to graphRoot)
- `mediaType` (MIME category: image, pdf, audio, video, document, file)
- `originalName` (as picked/dragged by the user)
- `importedAtMs`
- `tags` (user-defined strings)
- `autoLabels` (populated by Phase 2 ML pipeline, empty until then)
- `pageUuids` (pages that reference this asset)
- `sizeBytes`

### REQ-1.4 — Asset Browser Screen

A new screen accessible via command palette and sidebar ("Assets") that shows all assets in the active graph:

- Grid view for images, list view for other types
- Toggle between All / Images / PDFs / Audio / Video / Documents / Files
- Search by: filename, tag, auto-label, page name
- Sort by: date added, filename, size
- Per-asset actions: open, copy link, rename, move to folder, delete, edit tags
- Drag-to-reorder or move into custom groups

### REQ-1.5 — Custom groups / virtual folders

Users can create named groups (e.g. "Project X photos", "References"). A group is a tag under the hood (stored in the asset index). Assets can belong to multiple groups. Groups appear as a sidebar section in the asset browser.

### REQ-1.6 — Backfill on graph load

On graph open, `GraphLoader` must scan `assets/` recursively, find any asset not already in the index, and add it with inferred metadata (MIME from extension, size from disk).

---

## Phase 2 — On-Device ML Tagging + Cloud-Optional

### REQ-2.1 — On-device image labeling

For each image asset, run on-device ML to extract:
- **Labels**: broad categories (e.g. "outdoor", "architecture", "food")
- **Text (OCR)**: any text detected in the image

Platform implementations:
- Android: ML Kit `ImageLabeling` + `TextRecognition`
- JVM (Desktop): TensorFlow Lite Java binding or a bundled ONNX model via `onnxruntime`
- iOS: Core ML + Vision framework
- WASM/Web: WebAssembly ONNX model (lower priority)

Results stored in `autoLabels` (labels) and `ocrText` (raw OCR string) in the asset index. These are searchable from the asset browser.

### REQ-2.2 — On-device PDF text extraction

For PDFs:
- Android: `PdfRenderer` page-to-bitmap → OCR, OR `PDFBox-Android`
- JVM: Apache PDFBox (text layer extraction, no OCR needed for text PDFs)
- iOS: `PDFKit`

Extracted text stored in `ocrText`. Searchable.

### REQ-2.3 — Cloud-optional enrichment (opt-in per graph)

Users may configure an enrichment provider in graph settings:
- **Google Vision API** (label detection, landmark, text, safe-search)
- **Claude API** (image description, structured tagging via tool use)

When configured, cloud enrichment runs after on-device labeling and merges results into `autoLabels` and a new `cloudDescription` field. The raw on-device result is always kept; cloud is additive.

### REQ-2.4 — Processing pipeline / plugin hooks

A `AssetPipeline` interface (common) defines a processing stage:

```kotlin
interface AssetPipelinePlugin {
    val id: String
    suspend fun process(asset: AssetEntry, graphRoot: String): AssetPipelineResult
}
```

Built-in plugins: `OnDeviceLabelingPlugin`, `OcrPlugin`, `CloudVisionPlugin`, `CloudClaudePlugin`.

Third-party integration point for future plugins (e.g. Zotero metadata fetch for PDF DOIs). Plugin registry is initialized at graph open.

### REQ-2.5 — Processing triggers and throttling

Processing runs:
- On import (background, after copy completes, low priority)
- On-demand via "Analyze" button in asset browser
- Backfill: when a graph is opened and unprocessed assets exist, schedule batched processing (10 assets at a time, yield between batches)

Processing state (`PENDING` / `PROCESSING` / `DONE` / `FAILED`) tracked in asset index.

---

## Non-Goals (Explicitly Out of Scope)

- Sync / cloud backup of assets themselves
- Duplicate detection / dedup
- Asset preview in block editor (images already render; PDFs show as links for now)
- Zotero plugin implementation (architecture only; plugin API is the deliverable)
- WebAssembly ML implementation (defined but not wired up this sprint)

---

## Constraints

- All changes must remain Logseq-compatible (markdown links must remain relative `../assets/...`)
- KMP: platform-specific ML implementations behind expect/actual or service interfaces
- Follow existing Arrow `Either` error-handling pattern at all repository boundaries
- SQLDelight migrations required for any new tables — must appear in `MigrationRunner.all`
- No `rememberCoroutineScope()` passed to classes that outlive composition
- Bounded reads only — no unbounded `getAllAssets()` queries; paginate or use projections

---

## Success Criteria

1. Fresh graph with 500+ files in `assets/` — browser loads in < 2s, search returns results in < 300ms
2. Image import routes to correct subfolder; markdown link is Logseq-valid
3. On-device image labels appear in asset browser within 5s of import on a mid-tier Android device
4. PDF text extracted and searchable within 10s of import
5. Moving an asset from the browser updates all markdown references correctly
6. All existing tests pass; new feature has ≥ 80% test coverage on domain logic
