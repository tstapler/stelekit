# ADR-007: No WebView, no new runtime dependency; MIT ports only with notice

**Status**: Accepted (2026-11-14, plan sign-off; each port remains blocked until its upstream license is re-checked in Spike 0.1.5). Input spike matrix: `research/input-spike.md`.
**Date**: 2026-11-14

## Decision
- No WebView/Excalidraw embed, no tldraw (non-MIT license key), no KMP drawing library, no linear-algebra dependency (Multik stays the named fallback only if Spike 0.1.2 shows large or sparse systems and wasmJs support is verified).
- Selective MIT ports (perfect-freehand, a rough.js subset, Excalidraw element/binding/math behaviour) keep copyright notices in file headers and a `NOTICE` entry; each port has golden tests generated from the JS original. License text of each upstream is re-checked in Spike 0.1.5 before code is written (research marked MIT as SEARCH/INFERRED).
- LGPL/GPL sources are never ported (ADR-003). The repo license is Elastic-2.0.
- Importers are untrusted-input code: no HTTP client reachable (`SketchImportImportDirectionTest`), size/depth/count caps, SVG data URLs rejected.
- Compose pointer input stays the input source; `compose-stylus` is evaluated only inside Spike 0.1.4 and adopted only through its own ADR.
