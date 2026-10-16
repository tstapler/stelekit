# Research: Share URL Strip Tracking

## Codebase Findings

### Export Pipeline

The export pipeline is:

```
ShareDialog (UI)
  → ShareExportCoordinator.resolveExportContent()
    → ExportService.exportToString() / exportToClipboard()
      → PageExporter.export(page, blocks, resolvedRefs)
        → InlineParser(block.content).parse() → List<InlineNode>
          → MarkdownExporter / HtmlExporter / PlainTextExporter / JsonExporter
```

URL nodes in the AST (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/`):
- `UrlLinkNode(url: String, text: List<InlineNode>)` — Markdown `[text](url)` and autolinks `<url>`
- `MdLinkNode(label: String, url: String)` — plain Markdown reference links
- `ImageNode(alt: String, url: String)` — `![alt](url)` — typically asset refs, but could be external URLs

All four exporters iterate over `InlineNode` trees produced by `InlineParser`. The URL value lives on each node directly.

### Settings Persistence Pattern

`LlmSettings` (`commonMain`) wraps `Settings` (a `multiplatform-settings` KMP abstraction) using namespaced keys. `CaptureTargetSettings` follows the same pattern. This is the established way to persist per-user preferences across sessions.

### Share Dialog

`ShareContent` (`commonMain/ui/components/ShareDialog.kt`) drives the share dialog. It reads from `AppState` and delegates actions to `StelekitViewModel`. Adding a toggle here follows the pattern of the existing scope and format selectors.

`AppState` (`commonMain/ui/AppState.kt`) currently holds `shareDialogVisible`, `shareFormat`, `shareScope`, etc. A new `shareStripTrackingParams: Boolean` field is the correct place to hold the toggle's transient state.

`ShareExportCoordinator` bridges the view model and the export service. It calls `resolveExportContent()`, which calls `ExportService.exportToString()` / `exportToClipboard()`. The stripping option needs to flow from `AppState` → `ShareExportCoordinator` → `ExportService` → `PageExporter`.

### ExportOptions Pattern (Decision Point)

Two approaches exist for threading the stripping flag to the exporters:

**Option A — Add `ExportOptions` parameter to `PageExporter.export()`**  
Clean interface extension. Every exporter gets an optional `ExportOptions` parameter with a default of `ExportOptions()` (all off) to preserve backward compatibility. Allows future export options (e.g. strip emails, flatten wiki links) to piggyback cleanly. Requires updating 4 exporter implementations plus tests.

**Option B — Wrap exporter at `ExportService` level**  
`ExportService.exportToString()` post-processes the result string with a regex-based URL-stripping pass. Avoids touching individual exporters. But: URL syntax differs by format (HTML attributes vs. Markdown vs. JSON), so a single regex is fragile — it would need separate passes per format and risks false positives in code blocks or property values.

**Decision: Option A** is preferred. The AST walk already splits URL nodes cleanly; passing the option at the exporter boundary is the correct abstraction level.

### Known Tracking Parameters

Standard tracking parameters drawn from industry documentation (IAB Tech Lab, Google, Meta, Microsoft, Twitter/X, Klaviyo, Mailchimp):

| Param | Source |
|-------|--------|
| `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`, `utm_id` | Google Analytics / UTM standard |
| `fbclid` | Facebook |
| `gclid`, `dclid`, `wbraid`, `gbraid` | Google Ads |
| `msclkid` | Microsoft Ads |
| `twclid` | Twitter/X |
| `mc_eid`, `mc_cid` | Mailchimp |
| `igshid` | Instagram |
| `yclid` | Yandex |

### URL Parsing in KMP

`kotlin.stdlib` on JVM provides `java.net.URI` / `java.net.URL`. On non-JVM targets (iOS, wasmJs), these are not available — `ktor-http` is already on the classpath and provides `Url` / `URLBuilder` as a KMP-safe abstraction.

However, `ktor-http`'s `URLBuilder` is in `io.ktor:ktor-http` (`commonMain`). Checking the Gradle deps:

Per `kmp/build.gradle.kts` (inferred from the rest of the codebase's ktor usage — the network-layer code references Ktor in the git/LLM providers), Ktor is available. A `TrackingParamStripper` using `URLBuilder` would be platform-safe.

**Fallback**: If introducing Ktor at the export layer is undesirable, a lightweight regex-based stripper is acceptable for a first version: scan for `?...` or `&...` and remove matching key–value pairs. This avoids a new dependency and is easier to test.

**Decision: use regex-based stripping** (no new dependency in the export layer; query-string parsing is straightforward for well-formed URLs and the fallback for malformed URLs is to pass through unchanged).

### Test Coverage Approach

Pure function in `commonMain` → `commonTest`. Property-based tests with `kotest-property` (already on classpath):
- Round-trip invariant: stripping is idempotent (strip twice == strip once).
- Non-tracking params preserved.
- Malformed URLs unchanged.
- Full URL with fragment preserved.

## References

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/` — all exporter source files
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ShareExportCoordinator.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShareDialog.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` — share dialog state fields (lines 185-193)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSettings.kt` — settings persistence pattern
