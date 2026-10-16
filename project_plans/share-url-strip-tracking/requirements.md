# Requirements: Share URL Strip Tracking

## Problem Statement

When users copy blocks or pages from SteleKit to share externally, the exported content preserves ad-tracking query parameters (e.g. `utm_source`, `utm_campaign`, `fbclid`, `gclid`, `msclkid`) that were part of URLs when the notes were captured. These parameters serve no purpose outside the original ad context and are unwanted noise in shared content.

## Goal

Add an opt-in toggle in the Share/Export dialog that strips well-known ad-tracking query parameters from all URLs present in the exported content (Markdown, Plain Text, HTML, JSON formats), while leaving the rest of the URL — including other legitimate query parameters — intact.

## Scope

- Affects the **Share/Export dialog** only (not the stored notes in the graph — this is a view-time transformation, not a write-back).
- All four export formats: Markdown, Plain Text, HTML, JSON.
- All share destinations: Clipboard, Share via…, Save to File, Google Docs.
- Opt-in (default off): the toggle persists across app sessions via `PlatformSettings`.

## Acceptance Criteria

1. **AC-1 Toggle visible**: The Share/Export dialog displays a checkbox/switch labelled "Strip tracking parameters from URLs" (or equivalent) in the options section.
2. **AC-2 Default off**: The toggle defaults to off; no URLs are modified unless the user enables it.
3. **AC-3 Persisted**: The toggle state persists across app restarts via `PlatformSettings` (same mechanism used by `LlmSettings`, `CaptureTargetSettings`, etc.).
4. **AC-4 Known parameters stripped**: When enabled, the following query parameters are removed from every URL in the exported content: `utm_source`, `utm_campaign`, `utm_medium`, `utm_term`, `utm_content`, `utm_id`, `fbclid`, `gclid`, `msclkid`, `dclid`, `twclid`, `mc_eid`, `mc_cid`, `igshid`, `yclid`, `wbraid`, `gbraid`.
5. **AC-5 Non-tracking parameters preserved**: Query parameters not in the known-tracking list are retained verbatim.
6. **AC-6 All formats covered**: Stripping applies to all four export formats (Markdown, Plain Text, HTML, JSON).
7. **AC-7 All URL node types covered**: Stripping applies to `UrlLinkNode`, `MdLinkNode`, `ImageNode`, and raw URL text nodes in the inline AST.
8. **AC-8 Malformed URLs not crashed**: If a URL cannot be parsed as a valid URI, it is passed through unchanged rather than causing an exception.
9. **AC-9 Pure logic in `commonMain`**: The URL-stripping utility is a pure function in `commonMain` with no platform dependencies, covered by property-based tests in `commonTest`.
10. **AC-10 Existing export tests unaffected**: All existing export tests pass (no regressions).

## Out of Scope

- Write-back: stripping is never applied to the stored markdown file, only to exported content.
- URL rewriting in the block editor.
- Custom configurable parameter lists (only the built-in known-tracking list is supported in this feature).
- Redirect-following / canonical-URL resolution.

## Affected Files (high-confidence)

| File | Change |
|------|--------|
| `commonMain/.../export/TrackingParamStripper.kt` | **New** — pure URL stripping utility |
| `commonMain/.../export/MarkdownExporter.kt` | Pass stripped URL through `appendNode` |
| `commonMain/.../export/HtmlExporter.kt` | Same |
| `commonMain/.../export/PlainTextExporter.kt` | Same |
| `commonMain/.../export/JsonExporter.kt` | Same |
| `commonMain/.../export/ExportOptions.kt` | **New** — `data class ExportOptions(val stripTrackingParams: Boolean)` |
| `commonMain/.../export/ExportService.kt` | Thread `ExportOptions` through to exporters |
| `commonMain/.../export/PageExporter.kt` | Add `ExportOptions` parameter to `export()` |
| `commonMain/.../ui/ShareExportCoordinator.kt` | Read setting; pass options to `resolveExportContent` |
| `commonMain/.../ui/components/ShareDialog.kt` | Add toggle UI in `ShareContent` |
| `commonMain/.../ui/AppState.kt` | Add `shareStripTrackingParams: Boolean` field |
| `commonMain/.../export/ShareExportSettings.kt` | **New** — `Settings`-backed persistence |
| `commonTest/.../export/TrackingParamStripperTest.kt` | **New** — property-based + example tests |
