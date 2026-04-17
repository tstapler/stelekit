# Findings: Pitfalls — Import Failure Modes and Risks

**Date**: 2026-04-14
**Feature**: SteleKit Import (paste text / URL → auto-linked page)
**Research method**: Codebase reading + training knowledge

---

## Summary

The import feature combines four technically independent sub-systems, each with its own failure surface:

1. **Term matcher** — The existing `AhoCorasickMatcher` + `PageNameIndex` pipeline is already word-boundary-aware and enforces `minNameLength = 3`, but it lacks any concept of a stopword list, minimum match quality, or match-count cap. At import time the entire pasted document is a new scanning target, so every short page name in the graph (e.g., a page named "The", "And", or "Art") will match everywhere in the text.
2. **Performance** — Aho-Corasick construction is O(total pattern length) and search is O(text length + matches). A 50 KB paste against 500 page titles is feasible in a single pass, but `extractSuggestions` must parse inline markdown nodes first; that parse is currently done per-block in the editor and has not been stress-tested against multi-kilobyte single-text inputs.
3. **URL fetching** — No URL client exists in the codebase yet; the constraints document (requirements.md) restricts the stack to KMP/ktor-client. Networking failure modes are numerous: timeouts, TLS errors, HTTP redirects, paywalls, JavaScript-rendered pages, and encoding mismatches.
4. **Page creation** — `GraphWriter.getPageFilePath` uses `FileUtils.sanitizeFileName`, which handles most special characters, but collisions between auto-generated import titles and existing page names are not guarded against anywhere in the current code.

The two highest-impact / highest-likelihood risks are **false-positive wiki-link matching on short page names** and **duplicate page creation on re-import**. Both are straightforward to mitigate with low implementation cost.

---

## Options Surveyed

### 1. False-Positive Wiki-Link Matching

| Option | Description |
|--------|-------------|
| **A — Stopword list** | Maintain a hardcoded set of common English words (function words: "the", "and", "or", "is", …). Exclude them from `PageNameIndex` regardless of whether a page with that name exists. |
| **B — Minimum name length** | Increase `MIN_NAME_LENGTH` from 3 to a higher value (e.g. 5). Simple but blunt — rejects legitimate short page names like "KMP" or "SQL". |
| **C — User-review gate** | Never auto-apply wiki links; always surface matches as suggestions the user must accept/reject. This is already the model for `GlobalUnlinkedReferencesViewModel`; apply the same to import. |
| **D — Frequency threshold** | Skip a page-name match if the term appears more than N times in the pasted text (indicates generic usage rather than topic reference). |
| **E — Stopword list + user-review gate (combined)** | Apply stopword filtering at `PageNameIndex` to remove function words, then route all remaining matches through the accept/reject suggestion UI. |

**Recommended**: Option E. Stopwords eliminate the most egregious false positives at construction time; the suggestion-review gate provides a final human check. No auto-application of wiki links during import without user confirmation.

---

### 2. Large-Paste Performance

| Option | Description |
|--------|-------------|
| **A — Single-pass on whole text** | Run `AhoCorasickMatcher.findAll` directly on the entire pasted string. O(text + matches). No block parsing overhead for a raw paste. |
| **B — Chunk the text** | Split the input into paragraphs/lines and process each chunk independently. Loses multi-word matches that span chunk boundaries. |
| **C — Async + progress UI** | Run the scan in a coroutine on `Dispatchers.Default`, emit progress, allow cancellation. |
| **D — Cap total suggestions** | Limit the number of suggestions emitted (e.g. 100 max) regardless of how many the matcher finds. |

**Recommended**: Options A + C + D. Run the single Aho-Corasick pass on the full text (correct and fast), do it on a background dispatcher, cap suggestions at a configurable limit (100 is reasonable for v1).

---

### 3. URL Fetch Failure Modes

| Option | Description |
|--------|-------------|
| **A — Short timeout + user retry** | Set a 10-second timeout; surface a clear error message with a retry button. |
| **B — Graceful fallback to URL-as-title** | If fetch fails, pre-fill the import form with just the URL and an empty body so the user can paste content manually. |
| **C — Detect JS-rendered pages and warn** | Heuristic: if the fetched HTML is very short or contains `noscript` hints, warn the user that the page may require JavaScript rendering. |
| **D — Strip HTML server-side via a proxy** | Out of scope — requires a server component. |
| **E — Ktor-client with configurable timeout + status-code handling** | Return typed error sealed class: `Success`, `Timeout`, `HttpError(code)`, `ParseError`, `NetworkUnavailable`. |

**Recommended**: Options A + B + E. A typed error result lets the ViewModel show targeted messages. Graceful fallback to manual paste keeps the user unblocked.

---

### 4. Encoding Edge Cases

| Option | Description |
|--------|-------------|
| **A — Force UTF-8 in HTTP response parser** | Ktor-client charset detection can mis-detect encoding on non-UTF-8 pages (ISO-8859-1, Windows-1252). Explicitly request UTF-8 and transcode if needed. |
| **B — Normalize page title to NFC** | Unicode normalization (NFC) ensures that visually identical page names that differ in codepoint representation do not create duplicate pages. |
| **C — Validate/sanitize on import** | Pass the imported title through `Validation.validateName` (already exists in `Models.kt`) before saving. This rejects null bytes and control characters. |
| **D — Percent-encode special chars in filename** | `FileUtils.sanitizeFileName` already handles `/`, `:`, `\`, `*`, `?`, `"`, `<`, `>`, `|`. No new work needed for filenames. |

**Recommended**: Options A + B + C. NFC normalization is the only genuinely missing guard; the rest is already in place or handled by ktor-client.

---

### 5. Duplicate Page Creation

| Option | Description |
|--------|-------------|
| **A — Pre-check by name before save** | Call `PageRepository.getPageByName(name)` before calling `GraphWriter.savePage`. If a page with that name already exists, surface a conflict dialog. |
| **B — Content-hash deduplication** | Hash the imported content; store the hash as a page property. On import, check for pages with the same hash. |
| **C — URL-based deduplication** | For URL imports, store the source URL as a page property. Check for existing pages with the same URL before creating a new one. |
| **D — Import-from-same-source timestamp** | Track last-imported timestamp per source URL in a sidecar file. |

**Recommended**: Options A + C. Name-collision check is cheap and covers paste imports. URL deduplication (stored as `source:: <url>` property) covers re-import of the same web page. Option B (content hashing) is overkill for v1 because `findDuplicateBlocks` already exists and can be run post-import if needed.

---

### 6. Title Collision (Auto-Generated Title Conflicts with Existing Page)

| Option | Description |
|--------|-------------|
| **A — Suggest title from content + let user edit** | Pre-fill a title field from the first line or URL slug; require user confirmation before saving. Never silently overwrite. |
| **B — Append numeric suffix** | If "My Import" exists, generate "My Import (2)", then "My Import (3)", etc. |
| **C — Reject with error** | Block the save and show an error forcing the user to rename. |

**Recommended**: Option A. Show an editable title field in the import dialog; the `PageRepository.getPageByName` check runs when the user confirms. This is consistent with how Logseq handles title conflicts.

---

## Trade-off Matrix

| Failure Mode | Impact Severity | Likelihood | Mitigation Complexity | Recommended Mitigation |
|---|---|---|---|---|
| False-positive wiki-link matching | High — wrong links corrupt knowledge graph | High — any short page name (≥3 chars) in the graph will fire | Low — stopword list + suggestion gate already exist as patterns | Stopword filter at `PageNameIndex` + user-review gate (never auto-apply) |
| Large-paste performance | Medium — UI freeze, slow response | Medium — depends on graph size and paste size | Low — Aho-Corasick is already async-friendly; add background dispatch + suggestion cap | Async scan on `Dispatchers.Default` + cap at 100 suggestions |
| URL fetch failures | Medium — user gets no content | High — paywalls, JS-only pages, and timeouts are common | Medium — requires typed error model + UI states | Typed error sealed class + graceful fallback to paste mode |
| Encoding edge cases (UTF-8, NFC) | Medium — broken page names, lookup misses | Low-Medium — primarily affects non-ASCII page names | Low — NFC normalization is a one-liner | NFC normalize title on ingest; existing `Validation.validateName` blocks control chars |
| Duplicate page creation | Medium — cluttered graph, confusing backlinks | Medium — re-import of the same URL is a natural user action | Low — `getPageByName` check + URL property | Pre-save name check + store `source::` URL property |
| Title collision (auto-generated title) | Medium — silent overwrite or error | Low — only when auto-generated title matches an existing page exactly | Low — editable title field in dialog + pre-save check | Editable title field; save blocked until name is confirmed unique |

---

## Risk and Failure Modes

### FM-1: False-Positive Wiki-Link Matching

**What can go wrong**: The `PageNameIndex` currently allows any page name with `length >= 3`. If the graph contains pages named "The", "And", "Use", "Run", "Now", "App", "Day", etc., every occurrence of those words in 50 KB of imported text will be flagged as an unlinked reference. With 50 KB of text and a graph of 200 short-named pages, the suggestion list could easily contain thousands of entries, making the review step unusable.

**Code evidence**:
- `PageNameIndex.kt` line 59: `const val MIN_NAME_LENGTH = 3` — the minimum is already low.
- `AhoCorasickMatcher.findAll` applies word-boundary checks but has no frequency cap or quality score.
- `extractSuggestions` in `MarkdownEngine.kt` correctly skips existing wiki-link and inline-code spans but does not filter by match count or term frequency.

**Additional sub-risk**: Case-insensitive matching means a page named "Art" will match "article", "artificial", "artisan" — but the word-boundary check in `AhoCorasickMatcher.isWordBoundary` prevents this specific sub-case. However a page named "I" (the pronoun) would match the first-person pronoun everywhere. The `MIN_NAME_LENGTH = 3` threshold does not protect against 3-letter function words like "the", "and", "for", "are", "but".

**Mitigation**:
- Add an optional `stopwords: Set<String>` parameter to `PageNameIndex`. Default to a small, conservative English function-word list (~50 words).
- Never auto-apply links during import. Route all matches through the existing `UnlinkedRefEntry` accept/reject pattern.
- Consider adding a `maxSuggestionsPerPage: Int = 100` cap to the import scan.

---

### FM-2: Large-Paste Performance

**What can go wrong**: `extractSuggestions` calls `InlineParser(content).parse()` which is an O(N) scan. For a 50 KB string this is acceptable, but it has not been benchmarked. The bigger concern is running this synchronously on the main thread, causing an ANR on Android or a frozen UI on Desktop.

`AhoCorasickMatcher` construction is O(sum of pattern lengths). Rebuilding the matcher on every import scan is unnecessary; the existing `PageNameIndex` already maintains a live `StateFlow<AhoCorasickMatcher?>` that is rebuilt only when the page set changes. Import should consume the existing matcher from `PageNameIndex`, not build a new one.

**Code evidence**:
- `PageNameIndex.kt` lines 36–39: matcher is a `StateFlow` updated via `flowOn(Dispatchers.Default)` — safe to share across coroutines.
- `MarkdownEngine.kt` `extractSuggestions`: no coroutine dispatch; runs wherever it is called.

**Mitigation**:
- Import ViewModel runs the scan on `Dispatchers.Default`.
- Reuse the `PageNameIndex.matcher` `StateFlow` rather than constructing a new `AhoCorasickMatcher`.
- Add a configurable `maxSuggestions: Int` ceiling (default 100) — truncate the list before emitting to the UI state.
- For very large pastes (>100 KB), show a loading indicator while the scan runs.

---

### FM-3: URL Fetch Failure Modes

**What can go wrong**:

| Sub-mode | Root cause | Symptom |
|---|---|---|
| Network timeout | Slow server, high latency | App appears frozen or shows generic error |
| HTTP 4xx/5xx | Paywall, removed page, server error | No content returned |
| TLS/cert errors | Expired cert, HSTS mismatch | Crash or silent failure |
| Redirect loop | Server misconfiguration | Infinite loop or timeout |
| JS-rendered page | SPA — HTML body is nearly empty | Empty or useless content imported |
| Wrong encoding | ISO-8859-1 or Windows-1252 page | Garbled non-ASCII characters |
| Binary/PDF response | URL points to a file download | Garbage text |
| Very large response | Multi-MB HTML page | OOM or timeout |

**Code evidence**: No URL fetching code exists yet. The requirements state ktor-client is the preferred library. `[TRAINING_ONLY — verify]` Ktor-client on KMP does not automatically handle charset negotiation; the charset must be read from the `Content-Type` header and applied manually when decoding the response body to a string.

**Mitigation**:
- Use a sealed result type: `sealed class FetchResult { data class Success(val text: String, val title: String?) : FetchResult(); data class Failure(val reason: FetchFailureReason) : FetchResult() }` where `FetchFailureReason` covers `Timeout`, `HttpError(code: Int)`, `NetworkUnavailable`, `ParseError`, `TooLarge`.
- Set a hard response body limit (e.g. 2 MB) — truncate or reject beyond that.
- Set a connect+read timeout of 10 seconds.
- Read charset from `Content-Type` header; transcode to UTF-8 if necessary. `[TRAINING_ONLY — verify]` Ktor-client `HttpResponse.bodyAsText()` should honor the charset declared in the response.
- If HTML content is < 500 bytes after tag stripping, warn user the page may require JavaScript.
- Fallback: if URL fetch fails for any reason, pre-fill the import form with the URL in the title field and an empty body so the user can paste manually.

---

### FM-4: Encoding Edge Cases

**What can go wrong**:
- A page title containing `é`, `ü`, `ñ`, or CJK characters in NFC vs. NFD form creates two distinct strings that look identical to the user but do not match `getPageByName`.
- `AhoCorasickMatcher` lowercases with Kotlin `String.lowercase()`, which is locale-sensitive on some JVMs (`[TRAINING_ONLY — verify]` Kotlin/JVM `lowercase()` delegates to `String.toLowerCase(Locale.ROOT)` in recent versions, but `Locale.getDefault()` may behave differently on some Android OEMs).
- Non-ASCII page names passed through `FileUtils.sanitizeFileName` are not percent-encoded unless they match the explicitly enumerated special characters. A page named "Réunion" writes a file named `Réunion.md` — this is fine on most filesystems but fragile on some older FAT-derived Android storage.
- Pasted text from Windows applications may contain CRLF line endings. `Validation.validateContent` does not normalize line endings; the resulting block content will contain `\r\n`, which may break line-count heuristics in `GraphWriter.savePageInternal` (which counts `- ` lines).

**Code evidence**:
- `Validation.validateContent` in `Models.kt` calls `validateString(..., allowWhitespace = true)` which permits `\n`, `\r`, `\t` but does not normalize them.
- `FileUtils.sanitizeFileName` does not call `.normalize(NFC)`.
- `AhoCorasickMatcher.findAll` uses `text.lowercase()` (line 88) — no explicit locale.

**Mitigation**:
- Apply `java.text.Normalizer.normalize(name, NFC)` (or `kotlin.text.normalize` equivalent on KMP) to page titles both at creation time and at lookup time. `[TRAINING_ONLY — verify]` KMP `commonMain` does not have a built-in NFC normalizer; a JVM `expect`/`actual` is needed.
- Normalize `\r\n` → `\n` in the paste pre-processing step before saving blocks.
- Use `Locale.ROOT` explicitly in `AhoCorasickMatcher` lowercase (confirm Kotlin/JVM default is already ROOT).

---

### FM-5: Duplicate Page Creation

**What can go wrong**: The user imports a URL on Monday, creating "My Article". On Thursday they import the same URL again. `GraphWriter` will write a new file `My Article.md`, overwriting the existing one, or (if the auto-generated title differs slightly) create a second page "My Article (from web)". There is no deduplication guard anywhere in the current `GraphWriter` or `PageRepository.savePage` path.

**Code evidence**:
- `GraphWriter.savePageInternal` checks for large deletions but does not check whether a page with the same name already exists.
- `PageRepository.getPageByName` exists (declared in `GraphRepository.kt` line 188) and returns `Flow<Result<Page?>>`. It can be used as a pre-save existence check.
- `findDuplicateBlocks` exists in `BlockRepository` (line 175) but operates on block-level content hashes, not page-level.

**Mitigation**:
- Before creating a new page, call `pageRepository.getPageByName(importedTitle).first()`. If a page is returned, show a dialog: "A page named X already exists. Replace / Merge / Rename?"
- For URL imports, store the source URL as a page property (`source:: <url>`). On subsequent imports of the same URL, detect the existing page and offer to update it rather than create a duplicate.

---

### FM-6: Title Collision (Auto-Generated Title Conflicts with Existing Page)

**What can go wrong**: The import UI derives a page title from:
- The `<title>` tag of the fetched HTML, OR
- The first line of pasted text, OR
- A fallback like "Import YYYY-MM-DD".

If the derived title exactly matches an existing page, `GraphWriter.savePage` will overwrite the existing markdown file without warning. This is particularly dangerous for common titles like "Notes", "TODO", or "Inbox".

**Code evidence**:
- `GraphWriter.getPageFilePath` (line 268): constructs the path from the page name without checking for existing files beyond the large-deletion safety check.
- `fileSystem.writeFile(filePath, content)` is a clobber write — no O_EXCL equivalent.

**Mitigation**:
- Always present the user with an editable title field before saving (never save silently on the first pass).
- Run `pageRepository.getPageByName(title)` check when the user confirms the title; if a page exists, highlight the field with an error and suggest alternatives.
- Consider: if title ends up being "Untitled" (the `FileUtils.sanitizeFileName` fallback for empty strings), auto-append a timestamp to ensure uniqueness.

---

## Migration and Adoption Cost

N/A — not applicable

---

## Operational Concerns

- **Trie memory footprint**: `AhoCorasickMatcher` stores per-node `HashMap<Char, Int>` entries. For a graph of 1 000 pages with average name length 12, this is roughly 12 000 nodes × ~48 bytes/node = ~576 KB. Acceptable on Desktop and Android; no change needed for import.
- **Index staleness window**: `PageNameIndex` rebuilds the matcher asynchronously after each `PageRepository.getAllPages()` emission. If the user imports a page and immediately triggers another import before the index has rebuilt, the second import's scan will not include the first import's newly created page in the matcher. This is a minor consistency edge case, not a data-loss risk.
- **Concurrent imports**: If the user triggers two simultaneous URL imports, both may attempt to create a page with the same title. The `GraphWriter.saveMutex` serializes file writes, but the name-existence check and the write are not atomic. A time-of-check/time-of-use (TOCTOU) window exists. For a solo user this is extremely unlikely; add a UI guard (disable the import button while an import is in flight) rather than a database transaction.

---

## Prior Art and Lessons Learned

- **Obsidian — Paste and Import**: Obsidian does not auto-link on paste. The user must explicitly run "Find unlinked mentions" afterwards. This reduces false positives at the cost of discoverability. `[TRAINING_ONLY — verify]`
- **Logseq — Import from text**: Logseq's paste-to-page converts the clipboard text into outline blocks but does not auto-link. The "Linked references" / "Unlinked references" panels are presented separately after the page is created. `[TRAINING_ONLY — verify]`
- **Roam Research**: Roam applies `[[page name]]` detection during block editing in real time but does not auto-apply links on paste. Suggestions require explicit `[[` trigger. `[TRAINING_ONLY — verify]`
- **Lesson from multi-word-term-highlighting ADR-002** (in `project_plans/multi-word-term-highlighting/decisions/`): The stale-guard pattern (compare `capturedContent` to live block content before write) was added specifically because suggestions can become invalid between scan time and accept time. Import must follow the same pattern: scan results must be invalidated if the graph changes before the user confirms.
- **Lesson from render-all-markdown pitfalls**: The existing `pitfalls.md` for render-all-markdown noted that inline parser correctness under pathological Markdown inputs (unclosed brackets, deeply nested emphasis) causes silent parse failures. The `extractSuggestions` function depends on `InlineParser` output — the same parser fragility applies to the import scan step.

---

## Open Questions

1. **Should wiki-link suggestions be applied automatically during import (with a confirmation step) or only surfaced in the Unlinked References panel after the page is created?** The latter is safer and consistent with how existing unlinked references work, but adds friction.

2. **What is the right `maxSuggestions` cap for v1?** 50? 100? Needs a UX decision — too few and legitimate suggestions are missed; too many and the review step is unusable.

3. **Is there a platform-compatible NFC normalization API in KMP `commonMain`?** If not, an `expect`/`actual` must be added for JVM, Android, and iOS. `[TRAINING_ONLY — verify]`

4. **Does `ktor-client`'s `bodyAsText()` correctly honor charset from `Content-Type` on both JVM and Android targets?** Needs a targeted test against an ISO-8859-1 response. `[TRAINING_ONLY — verify]`

5. **Should the stopword list be user-configurable?** Power users with a page named "The Art of X" would want "the" to NOT be a stopword. A configurable list stored in graph settings is the right long-term answer but likely out of scope for v1.

6. **What happens when `Validation.validateName` rejects the auto-generated title** (e.g. a web page title that contains a backslash or control character)? The import should sanitize or truncate rather than throw, and then show the sanitized title in the editable field.

---

## Recommendation

Prioritized mitigation list for v1 implementation:

1. **[P0] Never auto-apply wiki links.** All matches from the import scan must go through the accept/reject suggestion UI (reuse `UnlinkedRefEntry` pattern). Zero auto-mutation of imported content.

2. **[P0] Pre-save name collision check.** Call `pageRepository.getPageByName(title)` before `GraphWriter.savePage`. Block save and surface a conflict dialog if the page already exists.

3. **[P1] Stopword filter in `PageNameIndex`.** Add a conservative ~50-word English function-word stoplist as the default. Make it an optional parameter so it can be extended or disabled per graph. This is the single most effective false-positive reduction at the lowest code cost.

4. **[P1] Async scan + suggestion cap.** Run the Aho-Corasick pass on `Dispatchers.Default`. Cap suggestions at 100. Show a progress indicator for large inputs.

5. **[P1] Typed URL fetch error model.** Return `sealed class FetchResult` from the URL fetcher. Handle each error variant with a targeted UI message. Fallback to paste mode on any failure.

6. **[P2] URL-based deduplication.** Store `source:: <url>` as a page property on URL imports. Check for existing pages with the same source URL before creating a new one.

7. **[P2] CRLF normalization + NFC title normalization.** Normalize line endings in pasted content before block creation. Apply NFC to page titles at creation and lookup time.

8. **[P3] Response body size limit for URL imports.** Reject or truncate responses >2 MB before attempting to parse HTML.

---

## Pending Web Searches

The following searches were not performed (no web search tool available). They should be run before finalizing the architecture plan:

1. `ktor-client KMP bodyAsText charset encoding iOS Android` — verify charset handling across targets.
2. `kotlin multiplatform NFC unicode normalization commonMain` — confirm whether a pure-KMP NFC normalizer exists or whether `expect`/`actual` is required.
3. `Obsidian paste import auto-link behavior 2024` — verify the claim that Obsidian does not auto-link on paste.
4. `Logseq import page auto-tag wikilink 2024` — verify Logseq's current paste-to-page behavior.
5. `ktor-client html to text extraction KMP` — identify the recommended approach for stripping HTML to plain text in KMP `commonMain`.
6. `kotlin string lowercase locale root JVM Android` — confirm that `String.lowercase()` in Kotlin/JVM uses `Locale.ROOT` by default rather than the system locale.

## Web Search Results

**Query 2 — KMP NFC unicode normalization** (2026-04-14):
- **CONFIRMED library exists**: `com.doist.x:normalize:1.2.0` — KMP library extending `String` with `.normalize(Form.NFC)`. Supports JVM, Android, JS. Linux native requires `libunistring` (not a concern for Desktop JVM target). This removes the `[TRAINING_ONLY]` flag: an `expect`/`actual` is NOT needed; use the Doist library in `commonMain`. Source: https://github.com/Doist/doistx-normalize

**Query 6 — Kotlin `String.lowercase()` locale behavior** (2026-04-14):
- **CONFIRMED**: `String.lowercase()` (Kotlin 1.5+) is implemented as `.toLowerCase(Locale.ROOT)` on JVM — safe for page title comparison in all locales including Turkish. The deprecated `.toLowerCase()` used the default locale. Source: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html

**Query 4 — Logseq import auto-tag behavior** (2026-04-14):
- **CONFIRMED no native auto-link on import**: Community plugin `logseq-autolink-autotag` provides this feature, confirming it is not built-in. Source: https://github.com/braladin/logseq-autolink-autotag

**Query 3 — Obsidian paste import auto-link behavior** (2026-04-14):
- **CONFIRMED no auto-link on paste**: Official Obsidian Web Clipper (released late 2024) supports AI summarization and templates but no auto-linking to existing vault notes. Source: https://obsidian.md/clipper
