# Research: Build vs. Buy — Mermaid Diagram Rendering

Agent 6 research for `project_plans/mermaid-diagrams/`. All library data (stars, license,
last-push date) pulled live via `curl https://api.github.com/repos/<owner>/<repo>` on
2026-09-15; version/status claims via WebSearch on the same date — see inline citations.

## Constraint recap

No WebView, JS-engine, or JS-bridge infrastructure exists anywhere in `kmp/src` today
(confirmed by requirements.md's `grep -ri mermaid kmp/src` / `find ... -iname "*Latex*"`
checks). Mermaid.js has no maintained pure-JVM port, so unlike the LaTeX ADR-001 precedent,
at least one of {WebView, embedded JS engine, pre-rendered image} is unavoidable on
JVM/Desktop and Android.

---

## 1. Existing OSS library or framework

### 1a. `compose-webview-multiplatform` (KevinnZou) — full WebView wrapper

- **Maturity/maintenance**: 1,009 GitHub stars, last push 2025-12-23, 142 open issues,
  Apache-2.0. Actively developed — desktop backend migrated JavaFX WebView (≤1.2.0) → Java
  CEF Browser (1.3.0+) → Kotlin CEF Browser (1.7.0+) for better performance
  ([GitHub](https://github.com/KevinnZou/compose-webview-multiplatform)). Current version
  2.0.3.
- **Platform coverage**: Android (native `android.webkit.WebView`), iOS (`WKWebView`),
  Desktop (bundles JCEF under the hood — see 1b for what that drags in). **No jsMain/Wasm
  support** — confirmed by both the KevinnZou repo itself and a May-2026 Medium walkthrough
  titled "Adding a WebView to Compose Multiplatform: Android, iOS, and Desktop" (Web is
  conspicuously absent from that title).
- **What glue is still needed**: this only gets you a WebView composable. You still need
  to (a) bundle `mermaid.js` as an asset, load it into an `about:blank`/data-URL page with a
  `<div class="mermaid">` container, (b) bridge the rendered SVG or an error back out via
  JS-evaluation/console-message hooks (there's no built-in "give me the resulting SVG"
  API), (c) build the `CodeFenceBlock` → this-renderer dispatch and the parse-failure
  fallback yourself, (d) size/measure the WebView correctly inside a scrolling block list
  (WebViews inside `LazyColumn`-style scrolling content are a known Compose pain point —
  reflow/recomposition-cost concerns are exactly what ADR-001 cited to avoid WebView for
  LaTeX).
- **Fit**: covers 3 of 4 targets (not Web) with one dependency, at the cost of pulling in a
  full Chromium (via JCEF) on desktop just to render diagrams.
- **Verdict: Viable** for Android + iOS specifically. For Desktop, prefer JCEF directly (1b)
  since KevinnZou's desktop backend *is* JCEF one layer down — going direct avoids an extra
  abstraction layer and open-issue surface for a JVM-only need.

### 1b. JCEF directly (`me.friwi:jcefmaven` / `org.cef`)

- **Maturity/maintenance**: `chromiumembedded/java-cef` — 884 stars, last push
  2026-09-09, 94 open issues, actively tracks upstream Chromium (`jcefmaven` at Maven
  Central is on release 146.0.10, i.e. still being cut against current Chromium versions
  ([Maven Repository](https://mvnrepository.com/artifact/me.friwi/jcefmaven/146.0.10))).
  License: CEF itself is BSD; `java-cef`'s GitHub license field reports `NOASSERTION`
  (worth a manual LICENSE-file check before shipping, not a hard blocker — CEF's upstream
  license is permissive BSD).
- **What it buys you**: a real, spec-complete Chromium renderer — mermaid.js "just works,"
  including edge-case diagram types (Gantt, C4, sankey) the JS library supports but that a
  from-scratch renderer would not.
- **Cost**: bundles a full embedded Chromium runtime, which is widely known (from
  JetBrains IDEs' JCEF-based embedded browser and community deployment writeups like
  [hydraulic.dev's "Deploying apps with JCEF"](https://hydraulic.dev/blog/13-deploying-apps-with-jcef.html))
  to add on the order of 100+ MB to a desktop distributable and multi-hundred-MB of
  runtime memory once a browser process is spun up — a heavy cost for rendering
  occasional diagrams in a notes app, and exactly the "WebView cold-start cost" ADR-001
  ruled out for LaTeX.
- **Glue needed**: same JS-bridge/SVG-extraction/fallback work as 1a, minus the
  cross-platform WebView abstraction (you're targeting JVM only).
- **Verdict: Viable** — the most standards-complete desktop option, but the size/memory
  cost is real and should be weighed against 1d (embedded JS engine) below before
  committing.

### 1c. GraalJS running mermaid.js headlessly (no browser, no WebView)

- Mermaid.js itself cannot run outside a DOM: it calls `document.createElement` and
  `SVGTextElement.getBBox()` for text layout, so *any* non-browser path must either embed
  a browser or provide a DOM-compatible shim
  ([GitHub discussion, mermaid-js/mermaid#2485 and #7085](https://github.com/mermaid-js/mermaid/issues/2485);
  [HN discussion](https://news.ycombinator.com/item?id=31275754)).
- **`aresstack/mermaid-java`** does exactly this: pure Java, no browser/Node required,
  uses GraalJS to execute the real mermaid.js library and Apache Batik for SVG text
  measurement, bundles the JS engine in the JAR
  (Maven: `com.aresstack:mermaid-java`, [GitHub](https://github.com/aresstack/mermaid-java)).
  Repo data: 6 stars, last push 2026-03-25, 2 open issues, MIT license. A parallel fork
  (`esamson/mermaid-java`, 0 stars, pushed 2026-09-06) exists with the identical
  description, suggesting active-but-tiny community interest rather than a single
  abandoned experiment.
- **Maturity assessment**: this is a **young, low-adoption library** (single-digit stars,
  beta version `0.2.0-beta.1`) — not battle-tested, but architecturally the most
  interesting option because it sidesteps the WebView-cost problem entirely while still
  running real mermaid.js (not a reimplementation). Dependencies (GraalJS: Universal
  Permissive License v1.0; Apache Batik: Apache-2.0) are both permissive.
- **Platform fit**: JVM/Desktop only in practice. GraalJS on Android is unproven —
  GraalJS ships as a JVM bytecode-interpreting engine; Android's ART runtime and the lack
  of official Android build/test coverage in `oracle/graaljs` (only a legacy
  `graal-nodejs/android-configure` script, no current CI matrix entry for Android) means
  this should be treated as **not viable for Android** without a dedicated spike. No
  jsMain/iOS relevance at all (jsMain has the real browser already; iOS has no JVM).
- **Verdict: Viable, but immature** — worth a time-boxed spike for the JVM/Desktop target
  specifically as a way to avoid the JCEF/WebView bundle-size tax, with JCEF (1b) as the
  fallback if the spike reveals stability or diagram-fidelity problems in a 6-star beta
  library.

### 1d. Server-side/CLI pre-render (`@mermaid-js/mermaid-cli`) run locally, not as SaaS

- `mermaid-js/mermaid-cli`: 5,010 stars, last push 2026-09-14 (yesterday), 89 open issues,
  MIT license — the most mature, actively-maintained option evaluated. Uses Puppeteer to
  drive a real headless Chromium
  ([GitHub](https://github.com/mermaid-js/mermaid-cli)).
- Could be invoked as a local subprocess (bundled Node + Puppeteer + Chromium, or a
  Docker-in-app approach) to render Mermaid source to SVG at edit-time/cache-time, fully
  offline, with no data ever leaving the machine.
- **Cost**: this is *worse* than JCEF for bundle size — it requires Node.js plus a
  Puppeteer-managed Chromium download, on every platform independently, invoked via
  process exec rather than in-process. Wrong shape for a KMP app expecting an in-process
  renderer; there is no Kotlin API, only a CLI/npm package.
- **Verdict: Not recommended** as an embedded dependency — the CLI's real value here is
  as a *reference implementation to compare rendering fidelity against* during manual
  testing, not as shipped app infrastructure.

### 1e. jsMain (Web/Wasm target) — direct mermaid.js DOM call

- No embedding problem exists here at all: jsMain already executes inside a real browser
  DOM. `mermaid.initialize()` + `mermaid.render()` (official, MIT-licensed, 90,254-star,
  actively maintained — pushed 2026-09-15 — [mermaid-js/mermaid](https://github.com/mermaid-js/mermaid))
  can be called directly via Kotlin/JS interop, identical in shape to ADR-001's KaTeX/JS
  path for LaTeX on this same target.
- **Verdict: Recommended** — trivial relative to every other platform; npm-wrapper
  research is unnecessary since Kotlin/JS can call the global `mermaid` object directly
  once the script tag is loaded.

---

## 2. SaaS/managed API — mermaid.ink (and mermaid.ink-alikes)

- `jihchi/mermaid.ink` — open-source (MIT), Node/Koa/Puppeteer stack, self-hostable via
  `docker run ghcr.io/jihchi/mermaid.ink` or `pnpm start`
  ([GitHub](https://github.com/jihchi/mermaid.ink)). The **public hosted instance**
  (`mermaid.ink`) is a free, unauthenticated image-URL API: `GET /img/<base64-encoded-mermaid-source>`.
- **Cost**: free tier with no published SLA or documented rate limits found in this
  research — treat uptime/throughput as best-effort, not contractual.
- **Privacy/offline — the core tension for this app**: SteleKit is explicitly a
  local-first, markdown-file-based notes app (per project CLAUDE.md and Logseq-parity
  goals). Sending a mermaid code block's contents to a third-party public HTTP endpoint
  to render it means:
  - **Every mermaid diagram's text content leaves the device** on every render, to a
    server operated by an unaffiliated third party, with no data-processing agreement,
    each time the block is displayed (unless cached — see requirements.md's caching
    question, which becomes load-bearing for privacy here, not just performance).
  - **Breaks offline use** — a core value prop shared with Logseq/Obsidian. A user on a
    plane or with no network sees diagrams silently fail to render (or hit the same
    fallback path as a syntax error, which is misleading — "no network" and "invalid
    Mermaid" are different failure modes users will want to distinguish).
  - Self-hosting mermaid.ink (Docker) removes the third-party-privacy concern but not the
    offline concern, and reintroduces the same Puppeteer/Chromium bundling cost as 1d —
    now as a service the app would need to spawn and manage, which is strictly more
    operational complexity than embedding JCEF/GraalJS in-process.
- **Verdict: Not recommended** as the primary or only rendering path for a local-first
  personal-notes app. **Viable only as an explicit, opt-in, clearly-labeled fallback**
  (e.g., "render via mermaid.ink online" button shown only when local rendering fails or
  is unavailable on a platform), never as a silent default, and never without disclosing
  that diagram text will be sent off-device.

---

## 3. LLM-generated / hand-rolled implementation vs. adopting Mermaid.js as-is

- Mermaid's own grammar is large and actively evolving: 20+ diagram types (flowchart,
  sequence, class, state, ER, Gantt, pie, C4, sankey, mindmap, timeline, etc.), a
  Jison-based parser, and continuous upstream changes (mermaid-js/mermaid pushed as
  recently as 2026-09-15, the day of this research). A bespoke Kotlin
  parser+renderer for even a useful subset (e.g., flowchart-only) means:
  - Reimplementing layout algorithms (dagre-style graph layout for flowcharts) that
    took the Mermaid project years to stabilize — this is a correctness-risk-heavy,
    high-maintenance-cost undertaking, not a weekend port.
  - A permanent compatibility gap: any graph a user brings in from Logseq/Obsidian/GitHub
    using a diagram type or syntax feature outside the hand-rolled subset renders
    incorrectly or not at all — directly undermining the stated goal ("renders the same
    everywhere" parity, requirements.md problem statement).
  - Ongoing maintenance burden tracking upstream Mermaid syntax changes indefinitely,
    borne entirely by this project instead of the upstream Mermaid community.
- **Evidence this is already being attempted in the wild, badly**: the "fork or adapt"
  search (section 4) surfaced four brand-new (all pushed within the last 1-2 days of this
  research, 0-3 stars each) Kotlin/Compose "native Mermaid renderer, no WebView, no
  JavaScript" projects. Their newness and lack of any adoption signal, combined with how
  hard real mermaid.js is known to be to reimplement (large Jison parser + dagre layout,
  actively evolving upstream — see above), makes them best read as a **rediscovery of
  this exact hand-rolled-subset temptation**, not as validated production tools worth
  building on. Zero of them show evidence of test coverage, CI, or third-party usage as
  of 2026-09-15.
- **Verdict**: adopting real mermaid.js (via WebView/JCEF/GraalJS/browser-native jsMain)
  is the correct call for anything claiming general Mermaid compatibility. A hand-rolled
  Kotlin Canvas renderer would be **reckless as the primary/only renderer** given the
  parity goal explicitly stated in requirements.md. It could be *reasonably* scoped as a
  deliberate, clearly-labeled "fast-path flowchart renderer" purely as a performance/size
  optimization layered on top of a real-mermaid.js fallback for everything else — but
  that is a v2+ optimization, not a v1 substitute, and was not asked for in this
  project's non-goals section (which explicitly excludes visual/interactive scope
  creep).

---

## 4. Fork or adapt — existing Compose/KMP mermaid projects

Searched and pulled live GitHub metadata for every KMP-native mermaid project found:

| Repo | Stars | Last push | Open issues | Notes |
|---|---|---|---|---|
| `swithun-liu/cmp-mermaid` | 2 | 2026-09-15 (today) | 0 | Claims Mermaid 12 support, Android/iOS/Desktop/Web via Compose Canvas SceneGraph |
| `botiverse/mermaid-native` | 2 | 2026-09-13 | 1 | "Mermaid-compatible" native parser, no WebView/JS |
| `AgentStart1/mermaid-rust-kmp` | 0 | 2026-09-14 | 0 | No description; Rust-via-JNI/Wasm approach |
| `hggz/Mermaid-Android` | 3 | 2026-02-14 | 0 | Android-only, Canvas-based, no WebView/JS |

All four are **single-digit-star, days-to-weeks-old projects with no CI evidence, no
releases, and no third-party adoption signal** as of this research date. None is a
plausible fork target for a v1 shipped feature — adopting one would mean inheriting an
unvetted, unmaintained-by-history parser/renderer with the exact correctness risk
flagged in section 3, while gaining none of the community battle-testing that makes
"adopt an existing library" attractive in the first place. `compose-webview-multiplatform`
(section 1a, 1,009 stars) is the only KMP-ecosystem project in this space with a real
track record, and it's a WebView wrapper, not a mermaid-specific renderer.

**Verdict: Not recommended.** Nothing worth forking exists yet; re-check in 6-12 months
if `cmp-mermaid` or similar gains real adoption/maintenance history.

---

## Recommendation by platform

| Platform | Recommended approach | Verdict |
|---|---|---|
| **jsMain (Web/Wasm)** | Direct `mermaid.js` DOM call via Kotlin/JS interop (script tag + `mermaid.render()`) | **Recommended** — no embedding problem, mirrors ADR-001's KaTeX precedent exactly |
| **JVM/Desktop** | Time-boxed spike on GraalJS + `aresstack/mermaid-java`-style in-process rendering (1c) first (avoids Chromium bundle weight); fall back to JCEF direct (1b, not the KevinnZou wrapper) if the spike shows fidelity/stability gaps | **Viable** (both sub-options); pick after the spike, not before |
| **Android** | `android.webkit.WebView` directly (native platform WebView, zero extra bundle weight — Android already ships one) hosting the same `mermaid.js` HTML shim as Desktop's fallback path; GraalJS is **not viable** here (unproven on ART) | **Viable** |
| **iOS** | Defer to raw code-block fallback in v1, per ADR-001's own iOS-deferral precedent and this project's non-goals framing | **Recommended to defer** |
| **mermaid.ink / SaaS (any platform)** | Do not use as default or silent fallback anywhere. Only acceptable as an explicit, opt-in, disclosed fallback path a user can decline | **Not recommended as primary; viable only opt-in** |
| **Hand-rolled Kotlin Canvas Mermaid subset** | Not for v1; real mermaid.js (via one of the above) is required to meet the stated parity goal | **Not recommended for v1** |
| **Forking an existing KMP mermaid project** | None found is mature enough to fork | **Not recommended** |

## Offline/privacy tension (explicit)

SteleKit's core value proposition — like Logseq/Obsidian — is that it's a local,
file-based, offline-capable notes tool: no note content should have a hard dependency on
network access or a third-party service to render correctly. Every SaaS option evaluated
(mermaid.ink, and by extension any "render via hosted API" shortcut) trades implementation
simplicity for exactly the two things this class of app is supposed to guarantee:
**offline usability** and **data locality**. Any decision to use mermaid.ink (or similar)
even as a fallback should be an explicit, disclosed, user-facing choice — not a
default silently wired in because it's the easiest path to "diagrams render" on a
platform where local rendering is harder to build (e.g., iOS). If local rendering isn't
ready for a given platform in v1, the raw-code-block fallback specified in
requirements.md's acceptance criteria #5 is the correct default — not a network call.
