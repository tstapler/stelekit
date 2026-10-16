# Validation: Share URL Strip Tracking

## AC Validation Matrix

| AC | Criterion | Validation Method | Status |
|----|-----------|-------------------|--------|
| AC-1 | Toggle visible in share dialog | Robolectric `androidUnitTest` — assert Switch composable exists in `ShareContent` | pending |
| AC-2 | Default off | Unit test: `ShareExportSettings(FakeSettings()).getStripTrackingParams() == false` | pending |
| AC-3 | Toggle persisted across restarts | Unit test: set true, create new `ShareExportSettings` from same `Settings` instance, read back true | pending |
| AC-4 | Known tracking params stripped | `TrackingParamStripperTest` — example tests for each of the 17 params | pending |
| AC-5 | Non-tracking params preserved | `TrackingParamStripperTest` — `?page=1&ref=footer` → unchanged | pending |
| AC-6 | All four formats covered | `StelekitViewModelShareExportTest` — exercise `stripTrackingParams=true` for markdown/html/plain/json | pending |
| AC-7 | All URL node types | `TrackingParamStripperTest` + exporter unit tests for `UrlLinkNode`, `MdLinkNode`, `ImageNode` | pending |
| AC-8 | Malformed URLs not crashed | `TrackingParamStripperTest` — `strip("not-a-url?")` returns input unchanged | pending |
| AC-9 | Pure in `commonMain` | No platform imports in `TrackingParamStripper.kt`; verified by Gradle source set constraints | pending |
| AC-10 | No regressions | `bazel test //kmp:business_tests //kmp:jvm_tests` green | pending |

## Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `PageExporter.export()` signature change breaks third-party implementations | Low (no public API; all implementations are internal) | Default `options = ExportOptions()` ensures source compatibility |
| Regex corner cases (e.g. `?&key=val` with leading `&`) | Low | `split('&').filter { it.isNotEmpty() }` handles empty segments |
| iOS / wasmJs targets: `TrackingParamStripper` uses JVM-only APIs | None (pure stdlib string ops — no JVM platform classes) | Confirmed: uses only `String.indexOf`, `String.split`, `String.substringBefore` |
| Settings key collision | None | Namespaced as `share.strip_tracking_params`; no conflicts in existing key space |
| `exportPage` keyboard shortcut ignores setting | Low | `ShareExportCoordinator.exportPage()` reads `uiState.value.shareStripTrackingParams`; only requires `AppState` to be initialized from `ShareExportSettings` on construction |

## Open Questions

1. **Should the stripping toggle also apply to the Clipboard keyboard shortcuts (`exportPage`, `exportSelectedBlocks`)?**  
   The plan says yes (reads from `AppState` which persists the setting). The alternative is to limit it to the share dialog only. Recommend: yes — consistent behaviour is less surprising.

2. **Should `ImageNode` URLs be stripped?**  
   In practice, image nodes referencing external URLs (e.g. from Markdown pasted into notes) could carry tracking. But local asset URIs (`assets://...`) have no `?` and pass through. Recommend: yes, strip `ImageNode.url` — the pass-through for fragment-free URLs is safe.

3. **Parameter list extensibility**  
   The hard-coded list covers the 17 most common trackers. A future enhancement (out of scope) could allow users to add custom parameters. The `TRACKING_PARAMS` set in `TrackingParamStripper` is a clean extension point for that.

## Estimate

| Phase | Work | Weighted Cost |
|-------|------|---------------|
| Phase 1: Core stripper + exporter wiring | ~80 LOC new, ~40 LOC edits | Low |
| Phase 2: Settings + ViewModel | ~60 LOC new, ~30 LOC edits | Low |
| Phase 3: UI toggle | ~30 LOC | Low |
| Tests | ~100 LOC | Low |

Total: straightforward contained feature, no platform-specific work, no DB changes, no migration needed. All changes are in `commonMain` and `commonTest`. Estimated 1–2 output-token-weighted agent waves.
