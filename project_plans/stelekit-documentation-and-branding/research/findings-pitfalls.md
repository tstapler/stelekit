# Findings: Pitfalls — Compose/Wasm Browser Issues and GitHub Pages Limits

**Project**: SteleKit Documentation and Branding
**Date**: 2026-04-18
**Status**: Training-knowledge draft — pending web search verification

---

## Summary

Building a browser-runnable SteleKit demo on GitHub Pages faces six overlapping failure domains. The most severe are: (1) the current `js(IR)` target does **not** use Compose Multiplatform's Skia/Canvas path — migrating to `wasmJs` is a prerequisite, not a given; (2) `SharedArrayBuffer`, required by Kotlin/Wasm's threading model, is blocked by browsers unless `COOP`/`COEP` headers are set, and GitHub Pages cannot set custom HTTP headers; (3) Compose Multiplatform Wasm bundles are large (15–40 MB uncompressed), making cold load time a first-impression problem; and (4) Safari's `wasm-gc` support arrived late (Safari 18.2, ~late 2024), meaning older Safari users will see a broken demo.

None of these are permanent blockers, but each requires an explicit mitigation strategy before the demo page is built. The most critical single issue is the COOP/COEP header problem on GitHub Pages — it has no server-side workaround without a CDN or a service worker shim.

**Recommendation**: Before writing a line of demo HTML, complete three validation spikes: (1) confirm the `wasmJs` Compose Multiplatform target compiles and renders in Chrome, (2) confirm `coi-serviceworker` re-enables `SharedArrayBuffer` on a test GitHub Pages deployment, and (3) measure the actual gzipped bundle size and plan a loading screen if the transfer exceeds 8 MB.

---

## Options Surveyed

The demo page has three plausible web target strategies:

1. **Keep `js(IR)` DOM target** — The current state: plain JavaScript with DOM manipulation (`jsMain/Main.kt` writes raw `innerHTML`). No Skia canvas, no Compose UI in the browser. Zero browser compatibility risk, fast load, but does not demonstrate the actual app.
2. **`wasmJs` Compose Multiplatform (Skiko canvas)** — Full Compose UI rendered via Skia on a `<canvas>` element. Closest to the real app. Requires Wasm + `wasm-gc` + threading support. The requirements doc explicitly targets this path.
3. **`js(IR)` + Compose HTML (not Skiko)** — Compose for Web HTML DSL renders to real DOM nodes. No Skia. Smaller bundle than Wasm. Looks different from the desktop/mobile app; does not showcase Compose Multiplatform's cross-platform UI story.

The requirements scope this to option 2 (Wasm/Skiko). Options 1 and 3 are noted because the current codebase is on option 1; migration to option 2 is a prerequisite task that is not yet done.

---

## Trade-off Matrix

| Failure Domain | Likelihood | Severity | GitHub Pages Workaround | Notes |
|----------------|-----------|----------|------------------------|-------|
| COOP/COEP headers — SharedArrayBuffer blocked | High | Critical | Partial — `coi-serviceworker` shim | Blocks threading; Kotlin/Wasm requires this |
| `js(IR)` → `wasmJs` migration not done | Confirmed | Critical | N/A — prerequisite work | Current `Main.kt` writes raw DOM; no Compose canvas |
| Safari `wasm-gc` support gaps | Medium | High | Feature-detect; show fallback message | Safari 18.2 ships wasm-gc; older versions fail |
| Bundle size / cold load time | High | High | Loading screen, preload hints | 15–40 MB raw; 4–9 MB gzipped [TRAINING_ONLY — verify] |
| Skiko GPU fallback / rendering glitches | Medium | Medium | Software renderer fallback exists | Skiko falls back to software; slower but functional |
| Jekyll eating `_` directories | Low | Medium | Add `.nojekyll` file | Trivial fix; easy to forget |
| SQLDelight `web-worker-driver` wasmJs compat | Unknown | Medium | May need alternate driver | Current driver is in `jsMain`; wasmJs compat unverified |
| Wasm MIME type misconfiguration | Low | High | Not applicable | GitHub Pages serves `application/wasm` correctly [TRAINING_ONLY — verify] |
| GitHub Pages file size limit (100 MB/file) | Low | Low | Bundle splitting | Wasm bundles unlikely to hit single-file limit |
| Firefox Wasm quirks | Low | Low | Test and document | Firefox has good Wasm support as of 2024 |

---

## Risk and Failure Modes

### 1. Critical Gap: Current Target Is `js(IR)`, Not `wasmJs`

**Condition**: This is a prerequisite gap, not a browser compatibility failure. The current `build.gradle.kts` configures `js(IR)` with plain DOM manipulation. `kmp/src/jsMain/kotlin/dev/stapler/stelekit/browser/Main.kt` writes raw `innerHTML` to a `<div>`. There is no `wasmJs` target, no Skiko Wasm binary, and no Compose canvas.

**What breaks**: The requirements assume a working Compose/Wasm demo. The current build produces a JS bundle that shows a static HTML checklist, not the actual Compose app UI.

**Likelihood**: Confirmed — identified from inspecting the source.

**What needs to happen before building the demo page**:
1. Add `wasmJs` target to `build.gradle.kts` (alongside or replacing the current `js(IR)` target).
2. Add a `wasmJsMain` source set with a `main()` function using `CanvasBasedWindow` or `ComposeViewport`.
3. Verify the Skiko Wasm binary is included in the build output (`./gradlew wasmJsBrowserDistribution`).
4. Confirm the Wasm module initializes in Chrome without errors.

This is 1–2 days of work and is the hard prerequisite for everything else.

---

### 2. COOP/COEP Headers — SharedArrayBuffer Blocked

**Condition**: Kotlin/Wasm's multi-threaded runtime requires `SharedArrayBuffer`. Browsers blocked `SharedArrayBuffer` after the Spectre disclosure and re-enabled it only in "cross-origin isolated" contexts — pages served with both `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp` response headers.

**What breaks**: Without these headers, `SharedArrayBuffer` is `undefined` at runtime; the Wasm module fails to initialize. The Compose canvas never renders. The page appears blank or throws an unhandled exception.

**GitHub Pages limitation**: GitHub Pages serves static files from its CDN with no mechanism to inject custom HTTP response headers. COOP/COEP cannot be set at the server level.

**Likelihood**: High — Kotlin/Wasm explicitly depends on `SharedArrayBuffer` for the worker-based coroutines dispatcher. [TRAINING_ONLY — verify whether a single-threaded mode is available in Compose Multiplatform 1.7+]

**Mitigations (in order of preference)**:
- **Service worker shim (`coi-serviceworker`)**: A small service worker (~1 KB) intercepts fetch events and adds COOP/COEP headers to responses in the browser's virtual header layer. Add `<script src="coi-serviceworker.min.js">` as the first script in `<head>`. On first load, the page reloads once after the service worker registers — this is expected behavior.
- **Use a CDN proxy (Cloudflare Pages, Netlify, Vercel)**: Serve the demo from a platform that allows custom headers via a `_headers` file. Adds operational complexity but is the cleanest long-term solution.
- **Single-threaded Wasm mode**: If Compose Multiplatform 1.7+ supports a compiler flag to avoid workers, it would eliminate the `SharedArrayBuffer` requirement entirely. [TRAINING_ONLY — verify]

**Evidence** [TRAINING_ONLY — verify]: JetBrains' official Compose Multiplatform Wasm sample apps include `coi-serviceworker.min.js` in their web app directory.

---

### 3. Safari `wasm-gc` Support Gaps

**Condition**: Apple's WebKit/Safari has historically lagged Chromium and Firefox on shipping new WebAssembly proposals. `wasm-gc` (garbage collection intrinsics) is required by Kotlin/Wasm K2 — the Wasm module will not parse at all in browsers without it.

**What breaks**: In Safari versions before `wasm-gc` support, the Wasm module fails to parse; the page shows an unhandled JavaScript exception. iOS users (who must use WebKit even in third-party browsers) on older iOS versions will see a broken demo.

**Likelihood**: Medium. `wasm-gc` shipped in Chrome 119 (Nov 2023), Firefox 120 (Nov 2023), and Safari 18.2 (~late 2024) [TRAINING_ONLY — verify exact Safari version and date].

**Mitigations**:
- **Feature-detect before loading**: Use a `WebAssembly.validate` call with a small `wasm-gc` test vector before attempting to load the full demo. If it fails, show a clear message: "SteleKit demo requires Chrome 119+, Firefox 120+, or Safari 18.2+."
- **Do not break the marketing page**: The site shell must work on all browsers. The Wasm demo is a progressive enhancement, not the whole page.
- **Add screenshot gallery or video walkthrough** for users on unsupported browsers.

---

### 4. Bundle Size and Cold Load Time

**Condition**: A Compose Multiplatform Wasm application includes Kotlin stdlib (Wasm), Compose runtime + foundation + material3, Skiko Wasm binary (Skia C++ compiled to Wasm), SQLDelight, and application code. SteleKit also depends on `material-icons-extended` — a very large icon set that should be excluded from the Wasm build.

**What breaks**: User experience degrades severely. A 30+ MB uncompressed bundle on a median US connection (50 Mbps) takes ~5 seconds gzipped. On a mobile connection (10 Mbps), 24+ seconds. Without a loading indicator, users bounce before the canvas appears.

**Typical sizes** [TRAINING_ONLY — verify with actual SteleKit build output]:
- `skiko.wasm`: 10–20 MB uncompressed, 3–6 MB gzipped
- Kotlin/Wasm app + stdlib: 3–8 MB uncompressed, 1–3 MB gzipped
- Total gzipped transfer: approximately 4–9 MB for a typical CMP Wasm app

**Mitigations**:
- **Show a loading screen immediately**: Render a static HTML/CSS skeleton with the app name and a progress indicator before the Wasm module loads.
- **Lazy-load the Wasm module**: Use `<script defer>` or dynamic `import()` so the page shell paints first.
- **Exclude `material-icons-extended`** from the `wasmJsMain` source set.
- **Pre-warm the Wasm download**: Add `<link rel="preload" href="skiko.wasm" as="fetch" crossorigin>` in the HTML `<head>`.
- **Content-hash the Wasm artifact URL** to enable long-lived browser caching on repeat visits.

---

### 5. Skiko Rendering Issues and GPU Fallbacks

**Condition**: Skiko renders via WebGL for GPU acceleration. On machines where WebGL is unavailable or reclaimed by the browser, Skiko falls back to a software renderer.

**What breaks**: GPU-accelerated rendering is fast (~60 fps). Software rendering is significantly slower (~10–20 fps for complex UIs). WebGL context loss may cause the canvas to go blank.

**Known Skiko Wasm issues** [TRAINING_ONLY — verify against current Skiko 0.9.x release notes]:
- Canvas context loss: browser may reclaim WebGL context; Skiko recovery behavior is inconsistent.
- IME input: Input method editor integration for CJK languages is incomplete in the Wasm target.
- Scrolling feel: Skia momentum scrolling differs from native browser scroll physics.
- Touch events: Pinch-zoom and long-press may not be correctly mapped in all browsers.

**Mitigations**:
- Detect WebGL availability before loading Skiko; show "use a desktop browser" notice if unavailable.
- Keep the demo graph small to avoid slow rendering paths.
- Test explicitly on: Chrome (primary target), Firefox, Safari macOS, Safari iOS.

---

### 6. GitHub Pages Operational Limitations

#### 6a. Jekyll Interference with Underscore Directories

Without `.nojekyll`, GitHub Pages runs Jekyll preprocessing which ignores directories prefixed with `_`. SSG builds that emit `_astro/` or `_next/` directories will silently 404 for all assets.

**Mitigation**: Add a `.nojekyll` file in the deployment root. Trivial fix; must not be forgotten.

#### 6b. No Custom HTTP Headers

GitHub Pages has no `_headers` file support. All custom header injection must go through the `coi-serviceworker` shim described in failure mode #2.

#### 6c. Git History Bloat from Committed Wasm Binaries

If the CI workflow commits built Wasm artifacts to the repository, large binary files accumulate in git history and will push the repository toward GitHub's 1 GB soft limit.

**Mitigation**: Use `actions/upload-pages-artifact` + `actions/deploy-pages`. Built files never enter git history.

---

## Migration and Adoption Cost

| Task | Effort | Prerequisite For |
|------|--------|-----------------|
| Add `wasmJs` target + `CanvasBasedWindow` entry point | 1–2 days | Everything |
| Validate SQLDelight `web-worker-driver` on `wasmJs` | 0.5–1 day | Demo graph functionality |
| Add `coi-serviceworker` shim to demo HTML | 0.5 hours | SharedArrayBuffer in Chrome |
| Add browser compatibility detection + fallback UI | 0.5 days | Graceful Safari/old-browser handling |
| Build loading screen / progress indicator | 0.5 days | User experience during load |
| Add `.nojekyll` file | 5 minutes | Jekyll not suppressing underscore dirs |
| Configure artifact-based GitHub Pages deploy in CI | 2–4 hours | Not committing Wasm binaries to git |
| Test on Chrome, Firefox, Safari macOS, Safari iOS | 0.5–1 day | Confidence before launch |

**Total estimated effort**: 4–6 days for a developer unfamiliar with CMP Wasm; 2–3 days for someone with prior experience.

---

## Operational Concerns

1. **Demo maintenance overhead**: Every Compose Multiplatform version bump may require re-validating the Wasm target. The Wasm target is less stable than JVM; breaking changes appear more frequently.
2. **CI build time**: Building `wasmJs` adds 5–10 minutes per run. Consider building the Wasm target only on `main` or on tags, not every PR branch.
3. **Demo graph must work without a filesystem**: The demo cannot open a local graph folder. It must use an in-memory demo graph (planned in `robust-demo-graph` project). Confirm that implementation is `wasmJs`-compatible before building the demo UI.
4. **Production debugging**: Source maps for Kotlin/Wasm are available but debugging is less ergonomic than JVM. Add explicit error handling around Wasm initialization.

---

## Prior Art and Lessons Learned

### JetBrains Compose Multiplatform Wasm Demos

JetBrains publishes official CMP Wasm example apps (`minesweeper`, `imageviewer`, `kotlinconf-app`). These samples consistently include:
- `coi-serviceworker.min.js` in the web app directory.
- A loading spinner in plain HTML/CSS displayed before the Wasm canvas appears.
- Feature detection for `wasm-gc` before attempting module load. [TRAINING_ONLY — verify current sample structure]

**Lesson**: Copy the JetBrains sample HTML and service worker setup rather than inventing a new one.

### Kotlin/Wasm GitHub Pages Community Projects

Multiple community projects use `coi-serviceworker` for Kotlin/Wasm on GitHub Pages. Projects that skip the shim report "blank page in Chrome" because Chrome 92+ requires cross-origin isolation for `SharedArrayBuffer`. This pattern is well-established and low-risk.

---

## Open Questions

- [ ] Does Compose Multiplatform 1.7.x `wasmJs` support a single-threaded mode that does not require `SharedArrayBuffer`? — blocks: whether COOP/COEP mitigation is mandatory
- [ ] Does the SQLDelight `web-worker-driver` work in `wasmJs`, or is a different driver needed? — blocks: demo graph persistence
- [ ] What is the actual gzipped bundle size of a `wasmJs` CMP build for SteleKit? — blocks: loading screen design decision
- [ ] Does the `robust-demo-graph` in-memory graph work in the `wasmJs` source set? — blocks: demo content
- [ ] Which exact Safari version first shipped `wasm-gc`, and what percentage of Safari users are on that version as of early 2026? — blocks: browser compatibility messaging

---

## Recommendation

**Before writing a line of demo HTML, complete three validation spikes in order:**

**Spike 1 — Wasm target compiles and renders (1–2 days)**:
Add the `wasmJs` target to `build.gradle.kts`. Create a minimal `wasmJsMain/Main.kt` with `CanvasBasedWindow { Text("Hello SteleKit") }`. Run `./gradlew wasmJsBrowserDevelopmentRun`. Confirm the canvas renders in Chrome. This is the single most important validation.

**Spike 2 — SharedArrayBuffer works on GitHub Pages (2 hours)**:
Create a test GitHub Pages deployment. Serve the minimal Wasm app with `coi-serviceworker.min.js`. Verify the canvas renders with no errors. Test on Chrome and Firefox.

**Spike 3 — Bundle size is acceptable (1 hour)**:
Run `./gradlew wasmJsBrowserDistribution`. Measure `skiko.wasm` and all `.js` chunks. If gzipped total exceeds 8 MB, plan a loading screen with a progress indicator before proceeding to the full demo page build.

**Mitigations that must be in place before demo launch**:

| Item | Action |
|------|--------|
| COOP/COEP | Add `coi-serviceworker.min.js` to the demo HTML `<head>` |
| Jekyll | Add `.nojekyll` to the Pages deployment root |
| Safari | Add `wasm-gc` feature detection; show "use Chrome/Firefox" message if unsupported |
| Git bloat | Use `actions/upload-pages-artifact` in CI; never commit Wasm binaries to git history |
| Load time | Show a static HTML loading screen with a spinner before canvas hydration |
| Error handling | Catch Wasm init errors; show a human-readable failure message with screenshot fallback |
| Demo graph | Confirm a read-only in-memory demo graph is available before building demo UI |

---

## Pending Web Searches

1. `"compose multiplatform wasmJs SharedArrayBuffer coi-serviceworker github pages 2024 2025"` — confirms JetBrains recommendation and whether newer CMP versions avoid the dependency entirely
2. `"kotlin wasm-gc safari 18 version ship date wasm-gc browser support table"` — determines minimum Safari version and adoption percentage
3. `"compose multiplatform wasm bundle size gzipped skiko.wasm 2024 2025 production"` — real-world bundle size measurements
4. `"sqldelight web-worker-driver wasmJs kotlin multiplatform compatibility 2024"` — confirms whether the existing `web-worker-driver` works in `wasmJs`
5. `"compose multiplatform wasmJs single-threaded no workers SharedArrayBuffer optional 2024"` — checks if a single-threaded mode is available that skips COOP/COEP requirement
