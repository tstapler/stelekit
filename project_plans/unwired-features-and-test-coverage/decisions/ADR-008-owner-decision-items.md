# ADR-008: Items the evidence cannot decide: semantic search, JS plugin bridge, RTC, desktop OCR, USB serial

**Status**: Proposed (needs owner input where marked) | **Date**: 2026-10-27

Each path is priced in cost classes (ESTIMATION.md), not human time.

## OD-1 `VectorSearch` family: build semantic search or delete
Why it cannot be wired as is: it embeds every candidate block per query over a caller-supplied `List<Block>` and `InferenceWorker` has no implementation. That breaks the "graph-scale reads must be bounded" rule and would exhaust the 512 MB heap at 9.5k pages.
- **Build** (recommended, adds capability): Epic 9 = spike (M) + schema/`MigrationRunner` (M) + indexer and chunked kNN (2 M) + UI (M) = 650k raw tokens, about 1.7M CU with verification; reuses `cosineSimilarity`; needs an on-device embedding model (size and licence decided by the spike) and a model download path; wall-clock: owner device pass.
- **Delete**: XS (20k raw). Loses the only route to semantic search.
**Constraint on the build path**: chunked kNN (<= 500 vectors per chunk, running top-k) is bounded in memory but still O(all blocks) per interactive query on a 512 MB phone at 9.5k pages. Spike 9.1 must record full-scan latency at graph scale; if it exceeds the spike's budget (proposed 300 ms to first results, INFERRED) the build needs an ANN index or a page-level pre-filter, or the epic is declared infeasible.
Rejected paths: sqlite-vec (extension unavailable on `requery` Android and wasm builds); cloud embeddings (confidential notes leave the device).

## OD-2 `JsPluginBridge`
No code supersedes it; both methods log and return `null`; `getJsBridge()` has no caller; there is no JS engine. 
- **Delete** (recommended): XS. `PluginHost`/`Plugin` are removed with it in Task 5.5d (OD-5), because `PluginRegistry` is the single enabled-state source (Story 5.5); keeping both would be two sources of truth.
- **Build a JS runtime and sandbox** (Logseq plugin compatibility): XL, multi-platform (QuickJS-class engine per target, API surface, permissions), no requirement today.

## RTC (`RTCManager`) — decided DeleteSuperseded, owner confirmation still required
Multi-device is served by `GitSyncService` and folder sync; real-time editing needs a server and CRDT/OT and conflicts with markdown-as-source-of-truth and `GraphWriter`. Alternative (adopt a CRDT library or hosted RTC): XL, outside the single-user scope.

## OD-3 Desktop OCR
Default: `NoOpTextRecognizer` on JVM (ML Kit is Android-only). Alternative: Tesseract/tess4j with native binary and tessdata packaging per OS (about M plus per-OS packaging verification), weaker than ML Kit on photos.

## OD-4 USB serial meters
Built with a fake port (M); the hardware pass needs a USB meter and the owner. Without one, the factory ships verified by fake-port tests only.

## OD-6 Schedule dev-plan PR-A1/PR-A2 (the pull half of scheduled sync)
This plan ships fetch-only staleness visibility (U2) and a cutover task (U16, metric 4's pull half) that depends on `project_plans/dev-console-git-sync-fix` PR-A1 guards and PR-A2 policy. Neither is funded here, so without a decision U16 never starts.
- **Schedule them** (recommended): the owner names who runs the dev-plan units and by when; U16 then follows. Cost is the dev plan's own estimate, not re-specified here.
- **Do not schedule**: record "not scheduled"; metric 4 stays "visibility met, pull pending" in the final report and U16 stays unfunded. Decision requested by the time U2 is released.

## Datalog — decided
Delete, folded into `{{query}}` (`QueryParser`/`QueryExecutor`/`SimpleQuery`); a translator for flat triples would add no capability beyond `{{query}}` forms and was rejected (cost M, zero new reach).
