# Findings: Stack — KMP URL Fetching & HTML Extraction

## Summary

SteleKit already ships Ktor 3.1.3 in `commonMain` (pulled in as a transitive requirement of
`coil-network-ktor3`) and per-platform Ktor engines in every target (`ktor-client-okhttp` on JVM
and Android, `ktor-client-darwin` on iOS). This means **HTTP/URL fetching has zero incremental
dependency cost** — the client just needs to be wired up in feature code.

HTML-to-plain-text extraction is the harder problem. No KMP-native HTML parser library matches the
maturity of JVM incumbents (jsoup, HtmlCleaner). The viable approaches are: (a) a pure-Kotlin
regex/string pipeline in `commonMain`, (b) platform-specific `expect/actual` with jsoup on JVM and
Android and a native parser on iOS, or (c) a lightweight pure-Kotlin HTML parser
(`kotlinx-html` is write-only; `ksoup` is the closest read-side KMP option).

Clipboard access in Compose Multiplatform Desktop requires calling into the AWT
`java.awt.Toolkit.getSystemClipboard()` API from `jvmMain`, wrapped behind an `expect/actual`
interface, since the Compose `ClipboardManager` API available in `commonMain` was read/write only
for text in CMP ≤ 1.7 and does not provide raw HTML clipboard content.

---

## Options Surveyed

### A — HTTP Fetching: Ktor (existing)

Ktor `HttpClient` is already a transitive dependency in every source set. Using it directly avoids
adding any new dependencies. Ktor's `HttpClient` supports coroutines natively, making it a natural
fit for the existing coroutine-based architecture.

The client is online-only. Offline graceful fallback requires catching `IOException` /
`UnresolvedAddressException` around `client.get(url)` and falling back to a user-visible error or
cached content.

### B — HTML Parsing: Pure-Kotlin regex stripper (commonMain)

A lightweight function that strips `<script>`, `<style>`, and all other HTML tags via regex/`replace`
calls, collapses whitespace, and decodes common HTML entities. No new dependency; lives entirely in
`commonMain`.

Limitations: fragile against malformed HTML, cannot reconstruct meaningful structure (headings,
lists, links), and is not spec-compliant. Suitable only for "best-effort plain text" use cases.

### C — HTML Parsing: ksoup (KMP HTML parser)

`ksoup` (com.mohamedrejeb.ksoup or `io.github.fleeksoft/ksoup`) is a Kotlin Multiplatform port of
jsoup. It supports parsing HTML into a DOM, querying via CSS selectors, and extracting text. It
targets JVM, Android, iOS, and JS. [TRAINING_ONLY — verify: exact artifact coordinates and latest
stable version; check whether the fleeksoft fork is the maintained one as of early 2026]

This is the highest-fidelity pure-KMP option. Bundle size impact is moderate (~300–600 KB for the
parsing tables). [TRAINING_ONLY — verify actual AAR/JAR size]

### D — HTML Parsing: expect/actual with jsoup on JVM+Android, NSAttributedString on iOS

jsoup 1.17.x is a battle-tested JVM HTML parser. It can be added to `jvmMain` and `androidMain`.
iOS can use `NSAttributedString(data:documentType:.html)` via Kotlin/Native interop to extract plain
text from HTML.

Web target would require a fourth implementation (browser DOM `innerText`). JS/Web is currently
gated behind a property (`enableJs=true`) and is not a primary target, so this can be deferred.

This approach maximises parse quality per platform but requires maintaining three implementations.

### E — Clipboard (Desktop JVM): AWT Toolkit

`java.awt.Toolkit.getDefaultToolkit().systemClipboard` provides `getContents()` for text and
`DataFlavor.stringFlavor` / `DataFlavor.fragmentHtmlFlavor`. This is the standard approach for
Compose Desktop clipboard access beyond what `LocalClipboardManager` exposes.

On Android, `ClipboardManager` (Android system service) provides the equivalent. iOS uses
`UIPasteboard.general`. In `commonMain`, a `ClipboardProvider` interface with `expect/actual` in
each platform source set is the correct abstraction.

### F — Clipboard (commonMain): Compose `LocalClipboardManager`

CMP's `LocalClipboardManager` (`androidx.compose.ui.platform`) provides `getText()` for plain text
in `commonMain`. This is sufficient if the import feature only needs text already on the clipboard
(e.g. URL string or pre-copied plain text). It does not expose raw HTML clipboard flavors on
desktop. [TRAINING_ONLY — verify whether CMP 1.7.x `LocalClipboardManager` exposes `getClip()` with
MIME-typed content, which was added in Jetpack Compose 1.7 on Android]

---

## Trade-off Matrix

| Option | KMP compatibility | Offline support | HTML parsing capability | Bundle size | Notes |
|---|---|---|---|---|---|
| A — Ktor (existing) | Full (already present) | None (network-only; wrap in try/catch) | N/A (fetching only) | Zero incremental | Engines already wired per platform |
| B — Regex stripper | Full (commonMain) | N/A | Low — tag removal only, no structure | Zero incremental | Fragile; acceptable for MVP fallback |
| C — ksoup (KMP jsoup port) | Full (JVM, Android, iOS, JS) | N/A | High — DOM + CSS selectors, text extraction | ~300–600 KB [TRAINING_ONLY] | Best single-dependency option for HTML parsing |
| D — expect/actual jsoup + NSAttributedString | Partial (3 impls required) | N/A | High per platform | ~500 KB on JVM/Android | Max quality; JS deferred; most maintenance |
| E — AWT Toolkit (Desktop) | JVM only (jvmMain) | N/A — UI feature | N/A (clipboard access) | Zero incremental | Required for raw HTML clipboard flavor on Desktop |
| F — CMP LocalClipboardManager | Full (commonMain) | N/A | N/A (text only) | Zero incremental | Sufficient for plain-text paste; no HTML flavor |

---

## Risk and Failure Modes

**Network failures**: Ktor will throw `IOException` subtypes on DNS failure, TCP timeout, TLS
errors. These must be caught at the call site and surfaced as user-visible errors; the feature
should degrade gracefully (show an error, allow manual paste fallback).

**Redirect chains and content negotiation**: Some URLs redirect to login walls, paywalls, or
JavaScript-rendered SPAs. Ktor fetches raw HTML; JS-rendered content will be empty. No mitigation
short of a headless browser (out of scope).

**HTML parsing edge cases**: Malformed HTML, deeply nested tables, base64-embedded content, and
very large pages (>5 MB) can cause OOMs or slow parses. A size cap (e.g. 2 MB) should be enforced
before parsing.

**ksoup maturity risk**: ksoup is a community port of jsoup, not the official library. Maintenance
continuity and API stability are less certain than jsoup itself. [TRAINING_ONLY — verify current
maintenance status and open issues]

**iOS clipboard permissions**: iOS 14+ shows a banner when an app reads the clipboard without user
interaction. The import UI should be triggered explicitly by user action to qualify for the
in-interaction exemption. [TRAINING_ONLY — verify current UIPasteboard privacy rules for iOS 16+]

**Desktop clipboard HTML flavor availability**: `DataFlavor.fragmentHtmlFlavor` availability on
Linux (X11/Wayland) clipboard managers is inconsistent. Plain text fallback must always be present.

---

## Migration and Adoption Cost

- **Ktor wiring**: Near zero. A shared `HttpClient` factory function in `commonMain` (or reuse of
  Coil's internal client) is a 10–20 line addition. No new Gradle coordinates.
- **Ktor engine config** (timeouts, redirect policy, user-agent): ~30 lines in a shared
  `commonMain` factory; engines are already declared in each platform source set.
- **ksoup addition**: One new Gradle coordinate in `commonMain`. API is similar to jsoup, so
  developers familiar with jsoup will onboard quickly.
- **expect/actual clipboard interface**: ~50–80 lines across 3 platform source sets
  (`jvmMain`, `androidMain`, `iosMain`) plus the `commonMain` interface.
- **No schema migrations required** — import feature populates existing Page/Block models.

---

## Operational Concerns

- **No server-side component**: All fetching happens from the user's device. Rate limiting, IP
  blocks, and robots.txt compliance are the user's implicit responsibility, not the app's.
- **Privacy**: URLs typed or pasted by the user are sent directly from the device to the target
  server. No analytics or proxying should be added.
- **Test strategy**: Network calls should be abstracted behind a `UrlFetcher` interface so unit
  tests can inject a fake. Ktor provides `MockEngine` for integration tests.
- **Coroutine scope**: Fetch + parse should run in a background coroutine (IO dispatcher) and not
  block the UI thread. The existing ViewModel coroutine scope is the right host.

---

## Prior Art and Lessons Learned

- **Coil 3 + Ktor integration in this codebase**: Coil already uses `ktor-client-core` with
  platform engines configured in each source set. The same pattern (shared core, platform engine)
  is established and working. The import feature should follow the same pattern rather than
  introducing a second HTTP stack.
- **Logseq (upstream)**: Logseq's URL import uses the Electron `fetch` API with a server-side
  CORS proxy for some sources. SteleKit is native and can call URLs directly, avoiding the proxy
  complexity.
- **jsoup in Android apps**: jsoup is widely used in Android development for HTML scraping. Its
  `Jsoup.parse(html).body().text()` idiom is well-understood. Using ksoup provides the same API
  surface in KMP.
- **Compose Desktop clipboard**: Multiple open-source KMP apps (e.g. Compose Multiplatform sample
  apps) use AWT `Toolkit` for clipboard access in `jvmMain`. This is the idiomatic approach.

---

## Open Questions

1. Is `ksoup` (fleeksoft fork) actively maintained as of early 2026, and what is the current stable
   version? (Web search required)
2. Does CMP 1.7.x `LocalClipboardManager` expose MIME-typed clipboard content on Desktop, or is
   AWT `Toolkit` still required for HTML flavor access?
3. Should the import feature reuse Coil's internal Ktor `HttpClient` instance, or instantiate a
   separate client with import-specific configuration (timeout, user-agent, redirect policy)?
4. What is the maximum page size the app should attempt to fetch and parse before aborting?
5. Does the project target iOS with clipboard import in MVP scope, or is Desktop-first sufficient?
6. Is there a requirement to preserve HTML structure (headings → Logseq heading blocks, lists →
   nested blocks) or is plain text extraction sufficient for the initial import?

---

## Recommendation

**HTTP fetching**: Use the existing `ktor-client-core` dependency directly. Add a
`UrlFetcher` interface in `commonMain` with a Ktor-backed production implementation. Catch
network exceptions and surface them as a sealed `ImportResult` type. Zero new dependencies.

**HTML extraction**: Add `ksoup` as a single `commonMain` dependency. It provides jsoup-compatible
DOM parsing across all targets without `expect/actual` fragmentation. If `ksoup` maintenance proves
insufficient (see open question 1), fall back to Option D (expect/actual jsoup + NSAttributedString)
as the next-best alternative. Do not use a pure-regex stripper except as a temporary scaffold.

**Clipboard (Desktop)**: Implement a `ClipboardProvider` `expect/actual` interface.
- `jvmMain`: AWT `Toolkit.getSystemClipboard()` for both plain text and HTML flavor
- `androidMain`: Android `ClipboardManager` system service
- `iosMain`: `UIPasteboard.general`
- `commonMain`: expose `fun getPlainText(): String?` and (where available) `fun getHtmlText(): String?`

For MVP, `LocalClipboardManager.getText()` in `commonMain` is sufficient if only URL-string paste
is needed (user copies a URL, pastes into an import dialog). The richer `expect/actual` interface
is needed only if the feature should import HTML directly from the clipboard.

---

## Pending Web Searches

The following searches should be run by the parent agent to verify training-knowledge claims:

1. `ksoup KMP kotlin multiplatform HTML parser 2025 fleeksoft maven coordinates`
   — Verify current artifact ID, group, latest stable version, and maintenance status.

2. `"ktor-client-core" "3.1" KMP multiplatform HTTP client coroutines example 2025`
   — Confirm no breaking changes in Ktor 3.x API for basic GET requests vs Ktor 2.x.

3. `compose multiplatform 1.7 ClipboardManager getClip MIME type desktop`
   — Determine whether CMP 1.7 exposed richer clipboard APIs that eliminate the need for
   AWT `Toolkit` on Desktop.

4. `jsoup 1.17 android multiplatform jvmMain androidMain gradle`
   — Confirm that jsoup can be declared separately in `jvmMain` and `androidMain` without
   conflicts (needed if ksoup is rejected).

5. `UIPasteboard iOS 16 privacy banner in-app triggered paste exemption`
   — Verify current iOS clipboard privacy rules and whether explicit user action exempts the
   banner for import use cases.

6. `"DataFlavor.fragmentHtmlFlavor" linux wayland clipboard java awt`
   — Confirm availability and behaviour of HTML clipboard flavor on Linux under both X11
   and Wayland (via XWayland).

## Web Search Results

**Query 1 — ksoup maven coordinates** (2026-04-14):
- **CONFIRMED**: `com.fleeksoft.ksoup:ksoup:0.2.6` on Maven Central. Actively maintained, supports JVM, Android, iOS, JS, WASM. Two variants: `ksoup` (string parsing) and `ksoup-network` (URL fetch + parse). Source: https://github.com/fleeksoft/ksoup, https://central.sonatype.com/artifact/com.fleeksoft.ksoup/ksoup

**Query 3 — Compose Multiplatform clipboard** (2026-04-14):
- The old `ClipboardManager` is deprecated in favor of a new `Clipboard` interface with suspend functions supporting all targets including web. HTML MIME type access on Desktop still requires AWT `Toolkit` via `jvmMain`; there is a known tracking issue (CMP-7624) for richer cross-platform clipboard APIs. The `[TRAINING_ONLY]` AWT approach remains the correct path for HTML clipboard flavor on Desktop. Source: https://medium.com/@yamin.khan.mahdi/reading-clipboard-text-across-all-platforms-in-compose-multiplatform-cmp-7474ffc03f09
