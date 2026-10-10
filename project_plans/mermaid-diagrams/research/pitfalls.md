# Research: Pitfalls — Mermaid Diagram Rendering

Agent 4 (Pitfalls). Research question: what commonly goes wrong with this class of
feature (WebView/JS-engine-hosted diagram rendering inside a KMP Compose block list),
and what should be explicitly designed against?

Repo facts verified for this research:
- `grep -rli "webview\|jcef\|wkwebview" kmp/src kmp/build.gradle.kts` → no hits (only
  unrelated Google OAuth files matched `google` substring, not WebView). Confirms the
  requirements doc's claim: zero existing WebView/JS-bridge infrastructure.
- `gradle.properties:20` → `enableJs=true`, so the `jsMain` target is already built —
  a real deployment surface, not hypothetical.
- CI: `.github/workflows/ci.yml:38-42` installs Xvfb and runs
  `xvfb-run --auto-servernum ./gradlew :kmp:jvmTest` unconditionally for all JVM tests
  (not opt-in). Roborazzi (`kmp/build.gradle.kts:15,145,255-257`) drives screenshot
  tests; a dedicated Gradle task excludes them from the parallel run and serializes
  them separately (`kmp/build.gradle.kts:464-489`, comment: "screenshot tests require
  AWT and must serialize").

## 1. WebView-in-Compose pitfalls

**Scroll/gesture conflict with the surrounding block LazyColumn.** This is the single
most-reported issue against `compose-webview-multiplatform` (the most likely KMP
WebView library candidate): WebView captures touch/scroll gestures and does not
propagate them to the parent scrollable container.
- [iOS: webview with scroll disabled doesn't pass scroll events up to LazyColumn — #333](https://github.com/KevinnZou/compose-webview-multiplatform/issues/333)
- [Scrolling doesn't work as it should (iOS) — #228](https://github.com/KevinnZou/compose-webview-multiplatform/issues/228)
- [WebView not scroll in Android — #279](https://github.com/KevinnZou/compose-webview-multiplatform/issues/279)
- [Breaks scroll of parent, only iOS — #155](https://github.com/KevinnZou/compose-webview-multiplatform/issues/155)

SteleKit's block viewer is exactly this shape — a `LazyColumn` of blocks where a
mermaid `CodeFenceBlock` would sit inline. A diagram block that swallows vertical
drag gestures would make the page unscrollable at that point. **Design against:**
disable WebView-internal scrolling entirely for inline diagram blocks (diagrams
should not need independent scroll — they should size to content or be
pannable only in an explicit full-screen/zoom mode, not inline in the list).

**Per-item WebView lifecycle in a recycled list.** `LazyColumn` does not preserve
composable state across scroll-away/scroll-back (it discards and recreates
composables, unlike `RecyclerView`'s view recycling) — confirmed by community
reporting that "webviews in Compose don't save state in a LazyColumn... every time a
user scrolls away and back, it has to completely reload." Creating a full WebView
engine instance (JCEF process, Android `WebView`, `WKWebView`) per visible mermaid
block, then discarding it on scroll-out and recreating on scroll-back, is expensive
(cold engine start) and, if disposal isn't explicit, leaky — WebView/WKWebView
instances are documented to be heavy and require explicit lifecycle teardown, not
GC-only cleanup: "one of the heaviest actions you can take in an iOS app is to create
a new WKWebView... limit the number of WKWebViews," and "memory leaking problem:
WKWebView doesn't free memory" ([Telerik #192](https://github.com/Telerik-Verified-Plugins/WKWebView/issues/192), [embrace.io: Why Is WKWebView So Heavy](https://embrace.io/blog/wkwebview-memory-leaks/)).
**Design against:** cache rendered output (SVG/bitmap) keyed on diagram source text
— the requirements doc's own open question — rather than re-rendering through a live
WebView on every scroll-back; treat the WebView/engine as a transient renderer, not a
persistent per-block widget. This is doubly important on Desktop, where JCEF is a
whole embedded Chromium process (not a lightweight OS-provided WebView), so N
concurrent JCEF-backed blocks is not analogous to N Android/iOS system WebViews.

**JCEF-specific Compose Desktop bug: resize freeze.** If JCEF is initialized *before*
the Compose window is created/shown, the window becomes unresizable and the app
crashes on resize attempts; it must be initialized *after* the Compose window exists.
[Application freezes on resize if JCEF is initialized before Compose — #2939](https://github.com/JetBrains/compose-multiplatform/issues/2939).
**Design against:** if JCEF is chosen for Desktop, gate its initialization on
first-use (lazy, after the main window is up), not at app startup.

**Desktop distributable size.** JCEF bundles a full embedded Chromium runtime;
community reporting on `packageDistributionForCurrentOS`-style native packaging shows
JDK-module inclusion alone adds hundreds of KB, and JCEF's own binaries are
dramatically larger than that ([hydraulic.dev: Deploying apps with JCEF](https://hydraulic.dev/blog/13-deploying-apps-with-jcef.html) discusses the
packaging burden directly). **Design against:** treat JCEF as an opt-in heavyweight
dependency decision requiring explicit sign-off in the ADR (per-platform size
regression should be measured before/after, not assumed negligible) — this is
squarely why ADR-001 (LaTeX) rejected a shared WebView approach in the first place,
and the same tradeoff recurs here with no pure-JVM alternative to fall back to.

## 2. Mermaid.js-specific pitfalls

**This is an untrusted-input rendering surface, and Mermaid's default is unsafe for
that.** Mermaid's `securityLevel` config has three relevant values: `loose` (default in
many embeddings) allows raw HTML in labels and enables `click` callback bindings —
this is a genuine stored-XSS vector, not theoretical. Confirmed real-world CVEs in
apps that embedded Mermaid without hardening it:
- [Stored XSS via Mermaid diagrams — gogs GHSA-26gq-grmh-6xm6](https://github.com/gogs/gogs/security/advisories/GHSA-26gq-grmh-6xm6)
- [Cross-Site Scripting (XSS) in Mermaid integration — docmost GHSA-r4hj-mc62-jmwj](https://github.com/docmost/docmost/security/advisories/GHSA-r4hj-mc62-jmwj)
- [Improper sanitization of `classDef` in state diagrams leads to HTML injection — mermaid-js GHSA-ghcm-xqfw-q4vr](https://github.com/mermaid-js/mermaid/security/advisories/GHSA-ghcm-xqfw-q4vr)
- Background: [Snyk Labs — More than flowcharts: exploiting diagram renderers](https://labs.snyk.io/resources/exploiting-diagram-renderers/)

SteleKit's threat model here is real: a mermaid fence's *source text is exactly the
user's own markdown content*, but graphs are commonly shared/imported (the
requirements doc's own framing — "users who bring in existing Logseq/Obsidian
graphs"), so the rendering input is not guaranteed to be authored by the person
viewing it. Per-diagram directives can also override `securityLevel` and re-enable
`htmlLabels` from inside the diagram source itself, meaning a page-level safe default
is not sufficient on its own if per-diagram overrides are honored.
**Design against:** set `securityLevel: 'strict'` (HTML-escapes label text, disables
click/callback bindings) or `'sandbox'` (renders inside a sandboxed iframe, blocking
JS execution in that context) explicitly, and verify per-diagram directive overrides
of `securityLevel` are disabled/ignored — do not rely on Mermaid's library default.
This determines whether an Android `addJavascriptInterface` bridge (see §3) is even a
safe design, since an XSS-capable render context combined with a JS↔native bridge is
a privilege-escalation path, not just a web-only concern.

**Unbounded diagram size can hang the render thread.** Mermaid is documented to hang
the entire page on sufficiently large input, and there's a known infinite-loop
failure mode when diagram text contains certain date-like tokens:
- [Mermaid hangs up current page on large inputs — #1216](https://github.com/mermaid-js/mermaid/issues/1216)
- [Date might cause infinite loop — #1060](https://github.com/mermaid-js/mermaid/issues/1060)
- [UI hangs when rendering mermaid diagrams — open-webui #4535](https://github.com/open-webui/open-webui/issues/4535)
- Mermaid itself caps diagram text at 50,000 characters and shows a placeholder past
  that ([websequencediagrams.com summary](https://www.websequencediagrams.com/blog/how-to-fix-maximum-text-size-diagram-exceeded-in-mermaidjs)), but that cap doesn't
  prevent pathological-but-short inputs (e.g. the date/infinite-loop case) from
  hanging regardless of length.
**Design against:** a render timeout independent of Mermaid's own size cap (kill/
fallback-to-raw-code after N seconds), since Mermaid's built-in guard is
character-count-based, not time-based, and does not catch algorithmic worst cases.
This directly satisfies acceptance criterion 2 ("invalid syntax falls back... instead
of crashing or showing a blank/broken view") — a hang is a blank/broken view by
another name and needs the same fallback path, triggered by a watchdog rather than a
caught exception.

**Version/syntax churn.** Mermaid v11 changed label line-break/markdown-formatting
behavior in a way that broke existing plain-text labels (later reverted in v11.13.0),
and v10→v12 upgrades are non-trivial ("needs browser-verified migration" per
[markcraft-dev/markcraft#6](https://github.com/markcraft-dev/markcraft/issues/6); v12
changes the default layout engine to ELK). Mermaid v10+ is ESM-only, which
constrains how it can be loaded/bundled from a JVM JS engine (GraalJS) vs. a browser
`<script>` tag vs. `jsMain`'s native JS runtime — these are three different module
resolution environments and a single loading strategy may not work identically across
all three. **Design against:** pin an exact Mermaid version per platform and add a
smoke-test fixture (one diagram per supported diagram type) that would catch a
version-bump rendering regression, since Mermaid's own release history shows
visual/behavioral breaks are not rare.

## 3. KMP/Compose-specific pitfalls (per this repo's own CLAUDE.md rules)

**`rememberCoroutineScope` escaping into a long-lived WebView bridge.** This repo's
CLAUDE.md already codifies this exact bug class ("Coroutine scope ownership —
`rememberCoroutineScope` must not escape composition"). A WebView-JS bridge object
(needed to receive render-complete/render-error callbacks from JS back into Kotlin)
is precisely the kind of object that outlives a single composition if it's cached
across recompositions (e.g., held in `remember { }` alongside the WebView instance) —
if it's constructed with a caller-supplied `rememberCoroutineScope()` rather than
owning its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, it will throw
`ForgottenCoroutineScopeException` the first time a render callback fires after the
composable briefly leaves composition (e.g., during the exact LazyColumn
scroll-recycling behavior described in §1). **Design against:** any diagram-render
bridge/controller class must own its scope internally per the repo's documented
pattern, not accept one from the composable.

**Catch `Throwable`, not `Exception`, for native engine load failures on Android.**
CLAUDE.md already flags this for `Application.onCreate()` generally
(`UnsatisfiedLinkError`/`NoClassDefFoundError` are `Error`, not `Exception`). It
applies with extra force here: Android `WebView` itself can fail to initialize on a
device with a disabled/uninstalled/outdated WebView provider (a known device-
fragmentation issue independent of this feature), and if JCEF or a GraalJS native
component is used on Desktop, native library load failure is the exact `Error`
subclass this rule exists for. **Design against:** wrap diagram-renderer
initialization (not just `Application.onCreate`) in `catch (e: Throwable)` so a
missing/broken WebView provider degrades to the raw-code fallback (acceptance
criterion 2/5) instead of crashing the block list.

**Main-thread blocking during synchronous JS render calls.** The repo's dispatcher
matrix (CLAUDE.md) is scoped to DB work and explicitly does not cover UI/rendering —
this is a documented gap this feature must fill in, not violate. A synchronous
`evaluateJavascript`/GraalJS `eval` call that blocks until Mermaid finishes rendering,
invoked directly from a Compose recomposition or the main/UI thread, will janks or
freezes the frame — this is the direct mechanism behind [UI hangs when rendering
mermaid diagrams — open-webui #4535](https://github.com/open-webui/open-webui/issues/4535) and the general
Mermaid-hang issues in §2. **Design against:** all render invocations must be
dispatched off the UI thread with an explicit non-DB dispatcher (`PlatformDispatcher.IO`
or a new dedicated dispatcher — not `PlatformDispatcher.DB`, which this repo reserves
for SQL work per its own table), with the result delivered back via
`Flow`/`StateFlow`/callback rather than awaited synchronously on the calling thread.
Note also `evaluateJavascript` itself has a documented WebKit-side leak when called
repeatedly ([WebKit bug 215729 — Memory leak issue for WKWebView:evaluateJavaScript](https://bugs.webkit.org/show_bug.cgi?id=215729)), reinforcing the render-and-cache-then-dispose
pattern from §1 over a long-lived bridge that calls `evaluateJavascript` on every
recomposition.

**Android `addJavascriptInterface` bridge risk compounds with §2's XSS finding.**
If the chosen design uses `addJavascriptInterface` to receive render-complete/error
callbacks from Mermaid's JS back into Kotlin, this is a documented RCE-class surface
if the WebView ever executes attacker-controlled script (only `@JavascriptInterface`-
annotated methods are exposed post-API-17, "but does not remove the risk: any exposed
method is still callable by whatever script runs" — [WithSecure Labs: WebView addJavascriptInterface RCE](https://labs.withsecure.com/publications/webview-addjavascriptinterface-remote-code-execution), [HackTricks: Webview Attacks](https://book.hacktricks.wiki/en/mobile-pentesting/android-app-pentesting/webview-attacks.html)). Combined with §2's untrusted-diagram-source
finding, this means the Android bridge's exposed method surface must be minimal
(e.g., a single `onRenderResult(json: String)` callback, not a general-purpose
eval/exec bridge) and the WebView must never load remote/attacker-reachable URLs —
only a local asset bundling Mermaid + the diagram source as a data/string payload.

## 4. CI/build pitfalls

**Existing screenshot-test infrastructure is static-Compose-oriented and already
isolated for a reason.** `kmp/build.gradle.kts:464-489` defines a separate task that
excludes `**/*Screenshot*`, `**/*Roborazzi*`, `**/screenshots/**` from the main
parallel `jvmTest` run specifically because "screenshot tests require AWT and must
serialize" — Roborazzi screenshot tests are already a known source of run-to-run
sensitivity (hence forced serialization) even for pure Compose rendering with no
external engine involved. `ci.yml:38-42` runs `xvfb-run --auto-servernum ./gradlew
:kmp:jvmTest` for *all* JVM tests unconditionally, including these serialized
screenshot tests — there's no separate opt-in gate today.

**A WebView/JCEF-backed diagram block adds a materially new source of
non-determinism to that already-fragile pipeline.** A Roborazzi screenshot test that
happens to capture a page containing a mermaid block would now depend on: (1) JCEF's
own async paint/composite timing inside Xvfb's virtual framebuffer (JCEF is a
separate Chromium process compositing into an off-screen buffer — a fundamentally
different rendering path than Skia-native Compose text, which Roborazzi was built to
snapshot deterministically), and (2) whether the JS engine has finished the
Mermaid layout+render pass by the time the snapshot is taken, an async boundary that
doesn't exist for static text/code-block rendering. **Design against:** exclude pages
containing mermaid blocks from Roborazzi screenshot fixtures entirely (render the
raw-code fallback path in screenshot tests, not the live-diagram path), or add an
explicit render-complete synchronization barrier (await the callback in §3, not a
fixed sleep) before any screenshot test captures a mermaid-containing page. A fixed
`Thread.sleep`-based wait is exactly the kind of flake source `xvfb-run` in headless
CI would surface intermittently (CI runner CPU contention affecting engine
warm-up/first-paint timing in a way a local dev machine wouldn't).

**No CI job currently accounts for a native browser-engine dependency at all.**
`ci.yml`, `android-benchmark.yml`, `benchmark.yml`, `ci-ios.yml`, `release.yml`, and
`fdroid.yml` were all authored assuming pure-JVM/Kotlin dependencies. JCEF ships
native binaries per-OS/arch; if it's pulled in as a Gradle dependency, CI runners need
matching native libraries available (or the dependency needs conditional
compilation/packaging per target), and F-Droid (`fdroid.yml`) in particular has its
own reproducible-build and proprietary-blob constraints that a bundled Chromium-based
engine (JCEF is Desktop-only, but any Android WebView customization or bundled
Chromium component would collide with F-Droid's build policy) should be checked
against explicitly before assuming Android WebView (system-provided, not bundled) is
the only Android-side dependency needed. **Design against:** confirm in the ADR that
Android uses the system `android.webkit.WebView` (no bundled engine, no
F-Droid/reproducibility conflict) rather than any embedded/bundled browser component,
since that is the only Android option consistent with the existing `fdroid.yml`
pipeline without further investigation.

## Summary of design constraints this research implies

1. Do not create one live WebView/JCEF instance per visible diagram block with no
   disposal story — cache rendered SVG/bitmap output keyed on source text (per the
   requirements doc's own open question) and treat the WebView as a transient
   renderer.
2. Disable WebView-internal scroll for inline diagram blocks; don't let a diagram
   block swallow the parent `LazyColumn`'s scroll gesture.
3. Set Mermaid `securityLevel: 'strict'` or `'sandbox'` explicitly (never rely on the
   library default of `'loose'`) and disable per-diagram security-level directive
   overrides — mermaid fence content is markdown-portable, hence potentially
   untrusted, text.
4. Add a time-based render watchdog independent of Mermaid's own character-count
   cap, wired to the same raw-code fallback path as syntax-error handling.
5. Dispatch all render calls off the main/UI thread on a non-`PlatformDispatcher.DB`
   dispatcher; never call a synchronous JS-eval render function directly from
   composition.
6. Any render-bridge/controller object must own its own `CoroutineScope` internally,
   not accept a `rememberCoroutineScope()` value, per this repo's existing rule.
7. Wrap diagram-engine initialization in `catch (e: Throwable)`, not `Exception`, so
   missing/broken native WebView/engine components fall back to raw code instead of
   crashing.
8. Keep any Android JS↔native bridge method surface minimal (a single typed
   callback, not a general eval/exec bridge) given the untrusted-input XSS finding.
9. Exclude live-diagram rendering from Roborazzi screenshot fixtures (or gate on an
   explicit render-complete signal, never a fixed sleep) to avoid introducing async,
   non-deterministic flake into the currently-static screenshot-test pipeline.
10. Confirm Android uses the system `WebView` only (no bundled/embedded browser
    engine) to stay compatible with the existing F-Droid CI pipeline.
