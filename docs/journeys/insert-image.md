---
journey_id: insert-image
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target: "1 toolbar tap (mobile, excluding the OS picker/camera's own step count); Desktop/Web parity is a discoverability finding, not a hard step-count ceiling — design/ux.md criteria 9a/9b"
current_step_count:
  desktop: "No confirmed insert-image mechanism found in BlockEditor.kt/MobileBlockToolbar.kt call sites reachable from Desktop — treated as an open finding, not a confirmed absence (see Notes)."
  android: "Meets target — 1 tap (Attach or Capture) reaches the OS-level picker/camera directly; already close to optimal."
  ios: "Same as Android — 1 tap, meets target."
  web: "CONFIRMED partial gap — OS-level drag-and-drop image insertion onto the page is a confirmed no-op (BUG-004). Whether the 📎/📷 toolbar buttons themselves render/function on Web is unconfirmed pending Phase D verification (no platform gating was found around the EditorToolbar call sites, per insert-tag.md's Notes)."
post_fix_step_count:
  desktop: "RESOLVED (GAP-004, Story D.1.2): confirmed a working Attach-image mechanism exists on Desktop — `App.kt:1408` gates `onAttachImage` on `attachmentService != null`, not on any platform check, and `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/service/JvmMediaAttachmentService.kt` is a real, wired `actual` implementation (siblings: `AndroidMediaAttachmentService.kt`, `IosMediaAttachmentService.kt`, `WasmMediaAttachmentService.kt`). Desktop Attach-image already meets the ≤1-tap target via the same `MobileBlockToolbar` 📎 button and `EditorToolbar`/`EditorCapabilities` wiring used on mobile — no code change was required, only re-verification. Capture-photo remains mobile-only by design (no meaningful Desktop camera-capture UX asserted)."
heuristic_findings: |
  Visibility-of-system-status violation: Web drag-and-drop image insertion is a confirmed complete no-op (BUG-004, PageDropTarget.kt) with zero feedback of any kind; Capture-photo's asynchronous pending-import path (App.kt:1508-1510) also gives no immediate confirmation, unlike Attach-image's synchronous insert (see Heuristic findings section).
test_ids: []
status: audited
last_verified: 2026-07-06
---

# Insert image

## Trigger
User wants to insert an image into a block, either via the mobile toolbar's Attach/Capture buttons or (on platforms where it exists) drag-and-drop / paste.

## Current step sequence — mobile Attach-image / Capture-photo (Android/iOS)

1. **Attach-image (📎)**: `MobileBlockToolbar.kt:216-223` (`Icons.Default.AttachFile`, `contentDescription = "Attach image"`), conditionally rendered only if the `onAttachImage` lambda is non-null. Wired to `App.kt:1408-1429` — opens a file picker via `attachmentService.pickAndAttach(...)`, then on success inserts `![alt](relativePath)` markdown directly at the cursor via `blockStateManager.insertTextAtCursor` (line 1424). Direct file-copy + markdown insertion, no intermediate preview/confirmation step.
2. **Capture-photo (📷)**: `MobileBlockToolbar.kt:224-231` (`Icons.Default.CameraAlt`, `contentDescription = "Capture photo"`). Wired to `App.kt:1484-1515` — requests camera permission if needed, snapshots the current page/block UUID, calls `SensorModule.cameraProvider.capturePhoto()`, and on success stores `pendingCapturePageUuid`/`pendingCaptureBlockUuid`/`pendingCaptureFile` (lines 1508-1510) for later import — **not inserted synchronously inline** the way Attach-image is (asynchronous import path).
3. Total: 1 toolbar tap → OS-level picker/camera flow (outside this app's step-count control) → image inserted. Already close to optimal per design/ux.md (j)'s finding — no redesign asserted as required by this journey alone.

## Current step sequence — Desktop/Web

1. No confirmed Attach-image/Capture-photo equivalent mechanism was traced on Desktop in this pass (the `App.kt:1408-1429`/`1484-1515` wiring is reachable from the same `EditorToolbar`/`MobileBlockToolbar` call sites found on all platforms per `insert-tag.md`'s Notes — camera capture specifically is unlikely to be meaningful on Desktop/JVM, but file-picker Attach-image plausibly is). This should be empirically confirmed (build + manually exercise) during Phase A.2's benchmarking rather than assumed either way — flagged as an open finding, not resolved here.
2. **Web-specific, already diagnosed (BUG-004, `docs/bugs/open/BUG-004-wasm-page-drop-target-noop.md`, open, Medium severity)**: OS-level drag-and-drop of an image file onto the page is a confirmed complete no-op. Root cause: Compose-for-WASM renders the entire UI into a single `<canvas>` DOM element; the browser's native HTML5 drag-and-drop API (`dragenter`/`dragover`/`drop`) fires on DOM nodes, and Compose's `dragAndDropTarget` modifier cannot hit-test per-composable regions for files dropped from outside the browser window — this requires native OS/browser bridging that Compose-for-WASM does not currently provide. Affected file: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt` (current no-op `actual`). This is cited as existing, already-diagnosed evidence per pre-mortem.md's evidence-over-narrative guidance — not re-discovered here as a fresh finding.

## Notes
- Per design/ux.md (j): this document does not assert that a mobile UI change is required for Attach/Capture — the confirmed-relevant gap is **platform parity** (Desktop/Web mechanism, and Web's BUG-004 drag-and-drop no-op specifically), not the mobile two-button flow itself.
- If Phase A's benchmarking (Epic A.2) or the ux-expert heuristic review confirms real discoverability confusion between the 📎 and 📷 icons specifically, design/ux.md (j) proposes a single 🖼 "Insert image" button opening a 2-option inline chooser as a conditional (audit-gated) fix — not assumed here.

## Heuristic findings

1. **Visibility of system status — VIOLATION (Web) / mixed (mobile).** Web drag-and-drop is "a confirmed complete no-op" (BUG-004) — the user drags a file onto the page and gets zero feedback of any kind, no error, no highlight, nothing, because Compose-for-WASM's canvas-based rendering cannot hit-test the native browser drop event. On mobile, Attach-image gives clear synchronous feedback (markdown "inserts... directly at the cursor via `blockStateManager.insertTextAtCursor`," line 1424, visible immediately), but Capture-photo does not: it "stores `pendingCapturePageUuid`/`pendingCaptureBlockUuid`/`pendingCaptureFile`... for later import — not inserted synchronously inline" (`App.kt:1508-1510`) — the user takes a photo with no immediate on-screen confirmation it will end up in the block.
2. **Consistency and standards — mixed.** Construction is consistent: 📎 and 📷 share the same `IconButton` + `contentDescription` + conditional-lambda pattern as insert-tag's 🏷 and insert-link's `[[ ]]` (`MobileBlockToolbar.kt:216-223`, `224-231` vs. `211`, `201`). Behaviorally, though, the two adjacent buttons are inconsistent with each other: Attach-image inserts synchronously inline while Capture-photo defers to an async pending-import path — same toolbar row, two different completion models with no visible cue distinguishing them in advance.
3. **Discoverability — flagged, not confirmed.** The doc itself surfaces this as an open, acknowledged risk rather than a confirmed finding: "If Phase A's benchmarking... confirms real discoverability confusion between the 📎 and 📷 icons specifically, design/ux.md (j) proposes a single 🖼 'Insert image' button" — i.e., icon-level ambiguity between Attach and Capture is a named risk pending empirical confirmation, not yet a verified violation.
4. **Minimal memory load — PASS (mobile).** Both mobile paths require "1 toolbar tap... Already close to optimal" reaching an OS-level picker/camera — no syntax or shortcut to recall at all, the strongest recognition-over-recall showing of any of the 7 journeys on mobile. Desktop/Web is an open finding rather than a pass or fail ("no confirmed insert-image mechanism found... treated as an open finding, not a confirmed absence").
