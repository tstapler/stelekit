# Validation: Multi-Block Text Selection & Export Preview

**Project:** `multi-block-select-export-preview`
**Date:** 2026-11-20

---

## Checklist

### Requirements Coverage

| AC | Addressed In Plan | Notes |
|----|------------------|-------|
| AC-1.1 Cross-block drag selection (desktop) | Phase C.3 | Pointer input at BlockList level |
| AC-1.2 Selection highlight (full + partial) | Phase C.4 | Custom overlay using TextLayoutResult |
| AC-1.3 Cmd/Ctrl+C copies selected text | Phase C.5 | Key event handler + ViewModel copy |
| AC-1.4 Single click clears selection | Phase C.6 | Existing click handler extended |
| AC-1.5 Does not interfere with edit mode | Phase C.3 (note) | Drag gesture deactivates when a block is in edit mode |
| AC-1.6 Feature flag | Phase C.1 | `feature.multi_block_drag_select` Settings key |
| AC-2.1 Preview action in dialog | Phase A.5 | OutlinedButton → SharePreviewPane |
| AC-2.2 Real-time format switching | Phase A.3 | Re-trigger LaunchedEffect on format change |
| AC-2.3 Selectable preview text | Phase A.4 | SelectionContainer wrapping BasicText |
| AC-2.4 Preview reflects scope | Phase A.3 | Re-trigger on scope change |
| AC-2.5 HTML shown as source | Phase A.4 | No live web view; just monospace text |
| AC-2.6 Background compute + loading indicator | Phase A.3/A.4 | DB dispatcher + CircularProgressIndicator |
| AC-2.7 Copy button in preview | Phase A.4 | FilledTonalButton → existing exportPage |

All ACs are addressed. ✓

---

## Architectural Fit

- `buildExportString` is a minimal extraction from `ExportService.exportToClipboard` —
  no schema changes, no new repository interfaces, no new coroutine scopes.
- `BulletListExporter` fits the existing `PageExporter` interface exactly — zero structural changes.
- Phase C reuses the existing `blockBounds` map infrastructure from drag-to-reorder; no
  second pointer-tracking mechanism is introduced.
- All new `AppState` fields follow the existing pattern (Boolean/String? defaults, no sealed
  class needed for the preview state).

---

## Gaps / Decisions Needed

**GAP-001**: Should the cross-block selection also capture blocks that are in *edit mode* at
drag time? The current plan excludes them (the edit-mode `BasicTextField` owns its own
selection). An owner decision is needed: "skip edit-mode blocks in a drag selection" vs.
"deactivate any active edit before starting a drag select."

**GAP-002**: The `BulletListExporter` needs to decide how to handle nested blocks: flatten
all to `•` bullets (losing hierarchy), or preserve indentation (making it identical to
PlainTextExporter with `•` prefix). Owner preference needed.

**GAP-003**: Preview capping for `JournalRange` scope (plan notes 50 pages): confirm cap
value and the truncation message text.

---

## Risk Summary

| Risk | Phase | Severity | Plan Response |
|------|-------|----------|---------------|
| Cross-block drag is high effort on Android | C | High | Desktop-first; feature flag |
| `TextLayoutResult` memory pressure | C | Medium | Store only during active drag |
| Preview jank on large pages | A | Low | Async + loading indicator + 500-block cap |
| `JournalRange` preview too slow | A | Medium | Disable preview for JournalRange or cap at 50 pages |

---

## Verdict

The plan is internally consistent and architecturally sound. Phase A (Export Preview) and
Phase B (Bullet List) are low-risk and can be shipped independently. Phase C (Drag Select)
is high effort and should be developed behind a feature flag after Phase A lands.

Recommended ship order: **B → A → C**.
