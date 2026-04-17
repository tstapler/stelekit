# ADR-002: ksoup for HTML-to-Text Extraction in commonMain

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The URL import path fetches raw HTML from a URL and must convert it to plain text before passing it to `ImportService.scan()`. SteleKit is a Kotlin Multiplatform project targeting JVM, Android, iOS, and (optionally) Web. HTML extraction must work without forking into platform-specific code wherever possible.

Three options were evaluated:

**Option A — Pure-Kotlin regex stripper in commonMain**: A set of `replace()` calls that remove `<script>`, `<style>`, and all other HTML tags, collapse whitespace, and decode common HTML entities. No new dependency.

**Option B — ksoup (KMP jsoup port) in commonMain**: `com.fleeksoft.ksoup:ksoup:0.2.6` is an actively maintained KMP port of jsoup that targets JVM, Android, iOS, JS, and WASM. Provides a full DOM API and `body().text()` for plain-text extraction, plus `doc.title()` for the page title.

**Option C — expect/actual with jsoup on JVM+Android, NSAttributedString on iOS**: Platform-specific implementations: jsoup 1.17.x in `jvmMain` and `androidMain`; `NSAttributedString(data:documentType:.html)` via Kotlin/Native in `iosMain`. JS/Web implementation deferred.

---

## Decision

**Option B: ksoup (`com.fleeksoft.ksoup:ksoup:0.2.6`) as a single `commonMain` dependency.**

```kotlin
// kmp/build.gradle.kts — commonMain dependencies
implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
```

---

## Rationale

1. **Single implementation, all targets**: ksoup eliminates the need for three separate HTML parsing implementations. A single `Ksoup.parse(html).body().text()` call works on JVM, Android, iOS, and Web from `commonMain`.

2. **Confirmed maintenance status**: Web search (2026-04-14) confirmed `com.fleeksoft.ksoup:ksoup:0.2.6` is on Maven Central, actively maintained, and supports all KMP targets including WASM. Source: https://github.com/fleeksoft/ksoup

3. **jsoup-compatible API**: Developers familiar with jsoup (standard in Android development) need no ramp-up. `Ksoup.parse()`, `doc.body()`, `element.text()`, and CSS selectors are identical to the jsoup API.

4. **Reject Option A (regex stripper)**: Regex HTML stripping is fragile against malformed HTML, table nesting, base64-embedded content, and unusual whitespace. It cannot extract the `<title>` reliably and does not handle HTML entities comprehensively. Acceptable only as a temporary scaffold — not as a production implementation.

5. **Reject Option C (expect/actual jsoup)**: Three implementations that must be maintained independently increases long-term cost. jsoup is a JVM-only library and cannot be used in `commonMain`; its use in `jvmMain` and `androidMain` would duplicate ~95% of the implementation. The iOS implementation via `NSAttributedString` is significantly different in behavior and harder to test. ksoup provides equal or better coverage for less maintenance burden.

---

## Consequences

**Positive**:
- One implementation file handles all targets
- `doc.title()` extracts the page title for pre-filling the import page name field — no regex needed
- CSS selector support available if richer extraction (e.g., main content area detection) is needed in v2
- ksoup bundle impact (~300–600 KB) is acceptable for Desktop; on iOS it is compiled into the binary

**Negative / Risks**:
- ksoup is a community port, not the official jsoup library. Maintenance continuity is less certain than jsoup itself. Mitigation: ksoup's API mirrors jsoup, so migrating to Option C later (if ksoup becomes unmaintained) is a mechanical translation of the single `UrlFetcherJvm.kt` file
- ksoup 0.2.6 has not been benchmarked against the 2 MB response size limit in this project. If parsing is slow for large inputs, the size cap enforced before calling `Ksoup.parse()` is the mitigation
- HTML-rendered JavaScript pages will produce empty or near-empty text — the 200-char warning threshold in `UrlFetcherJvm` handles user communication for this case
