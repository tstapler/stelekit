# Findings: Stack — Static Site Generator and Wasm Hosting

**Date**: 2026-04-18  
**Status**: Draft — training-knowledge baseline; pending web-search verification  
**Input**: `requirements.md`, `kmp/build.gradle.kts`, `kmp/gradle.properties`, `kmp/src/jsMain/kotlin/.../browser/Main.kt`

---

## Summary

SteleKit needs a GitHub Pages site serving three audiences (users, KMP developers, contributors), a live browser demo of the app, user docs, and developer docs — all maintained by CI. The existing JS target uses `js(IR)` with `org.jetbrains.compose.experimental.jscanvas.enabled=true`, meaning Skiko renders to an HTML `<canvas>` element (not the newer `wasmJs` target). The demo embed is therefore an HTML file + Kotlin JS bundle served alongside the site, not a standalone `.wasm` binary.

**Bottom line**: Astro is the recommended SSG. It ships zero JS by default (fast marketing pages), has a mature content collections API for docs, handles static asset pass-through for the Compose JS bundle trivially, and has strong GitHub Pages + GitHub Actions support. Its learning curve is lower than Docusaurus for a solo maintainer and it is not opinionated about a specific documentation theme.

---

## Options Surveyed

### 1. Astro

Astro is a content-focused SSG that ships HTML by default. Components can be authored in any framework (React, Vue, Svelte, vanilla HTML) or in Astro's own `.astro` template syntax. Content Collections provide typed, schema-validated Markdown/MDX authoring.

**Compose/JS integration**: The Kotlin JS bundle (`stelekit.js` + supporting files, plus any sql.js worker files) are static assets. Astro's `public/` directory passes files through verbatim. The demo page is an `.astro` page that drops in an `<iframe>` or `<canvas>`-targeting `<script>` tag pointing to the JS bundle. No framework-specific adapter is required for GitHub Pages (use the static adapter).

**GitHub Pages**: First-class. The official Astro docs show a ready-made `deploy.yml` GitHub Actions workflow for Pages. [TRAINING_ONLY — verify workflow still current in 2026]

**DX**: `npm create astro@latest` bootstraps a project in under 5 minutes. File-based routing. TypeScript by default. Built-in image optimisation. MDX support via optional integration.

**Documentation features**: No built-in docs theme in the core package, but the `Starlight` theme (maintained by Astro core team) provides sidebar navigation, versioning, search (Pagefind), i18n, and dark mode out of the box. Starlight is widely used for OSS documentation. [TRAINING_ONLY — verify Starlight feature set and maturity]

**Bundle/asset handling**: Vite under the hood. The Kotlin JS webpack output can be placed in `public/demo/` and served at `/demo/`. No special configuration needed for canvas-based apps — they are just HTML + JS.

**Community/ecosystem**: Large and growing. [TRAINING_ONLY — verify current star count and adoption]

---

### 2. Docusaurus

Meta's React-based documentation framework. Provides docs, blog, and landing page sections out of the box. Heavily used by large OSS projects (Jest, React Native, Redux).

**Compose/JS integration**: Same as Astro — the Kotlin JS bundle is static and placed in the `static/` directory. An MDX page can embed `<iframe src="/demo/index.html">` or a React component that injects a `<script>` tag. No technical barriers.

**GitHub Pages**: First-class. `docusaurus.config.js` has `organizationName`/`projectName` fields and a `gh-pages` deployment script. GitHub Actions workflow is documented.

**DX**: Higher setup cost than Astro. React knowledge required for customisation. `npx create-docusaurus@latest` scaffolds quickly but the config file is verbose. Docusaurus 3 (current) requires Node 18+. [TRAINING_ONLY — verify Node version requirement]

**Documentation features**: Excellent — versioned docs, search (Algolia or local), i18n, OpenAPI docs plugin, blog, auto-generated sidebar from file tree, MDX everywhere. This is the strongest documentation feature set of all candidates.

**Bundle/asset handling**: Webpack under the hood (Docusaurus 3.x). Build output is a static bundle deployable anywhere. The Kotlin JS demo artifacts drop into `static/demo/`.

**Community/ecosystem**: Very large. Backed by Meta. Stable long-term support.

**Downsides for SteleKit**: React is overkill for a one-maintainer project. The `docusaurus.config.js` / `sidebars.js` / `swizzling` pattern adds ongoing maintenance surface. Versioned docs are not needed at MVP.

---

### 3. VitePress

VitePress is Vue-powered and purpose-built for technical documentation. It is the official documentation framework used by Vue.js, Vite, Vitest, and many projects in that ecosystem.

**Compose/JS integration**: Same static-asset approach as above. VitePress's `public/` directory passes files through. A Vue component or raw HTML can embed the demo. VitePress also supports custom layout slots where raw HTML can be injected, making it straightforward to embed the canvas demo.

**GitHub Pages**: Well supported. The official VitePress docs include a `deploy.yml` snippet for GitHub Pages. [TRAINING_ONLY — verify snippet still accurate]

**DX**: Very low setup cost. `npx vitepress init` produces a minimal project. Configuration is a single TypeScript file. Markdown with Vue components (`.md` files can contain `<template>` blocks). No knowledge of Vue required for pure documentation — Vue is used only if you need custom components.

**Documentation features**: Strong defaults — sidebar, nav, search (built-in local search or Algolia), dark mode, code highlighting, custom themes. Less extensible than Docusaurus but covers 90% of documentation needs.

**Bundle/asset handling**: Vite-native. Fast builds. The Kotlin JS artifacts are static pass-throughs.

**Community/ecosystem**: Smaller than Docusaurus but healthy. Actively maintained by the Vite core team.

**Downsides for SteleKit**: The ecosystem leans Vue; for a marketing landing page with significant design differentiation from the default VitePress theme, customisation requires Vue component knowledge or heavy CSS overriding.

---

### 4. MkDocs / Material for MkDocs

MkDocs is a Python-based SSG for documentation. Material for MkDocs is the dominant theme, adding search, navigation tabs, versioning (mike), social cards, and a polished design system.

**Compose/JS integration**: HTML can be injected via custom `overrides/` templates. The Kotlin JS bundle can be included in `docs/demo/` and referenced from a documentation page with a raw HTML block. Technically possible but not idiomatic — MkDocs is built around Markdown pages, not landing pages with embedded apps.

**GitHub Pages**: Built-in `mkdocs gh-deploy` command. GitHub Actions support is straightforward.

**DX**: Lowest barrier if you know Python; `pip install mkdocs-material` and a `mkdocs.yml` is all you need. No Node.js required for the docs site itself (though the Kotlin build still needs Node for JS bundling). However, the Python toolchain adds a second runtime dependency to CI.

**Documentation features**: Excellent for pure reference docs. Material adds: versioned docs (`mike`), full-text search, tags, social cards, content tabs, admonitions, code annotations, mermaid diagrams.

**Bundle/asset handling**: Static files placed in `docs/` are copied as-is. The Kotlin JS bundle works fine there. However, there is no first-class concept of a non-docs landing page.

**Community/ecosystem**: Very large in the Python/data science/backend world. Less common in the Kotlin/JVM ecosystem.

**Downsides for SteleKit**: Python dependency in an otherwise pure Kotlin/JVM project. No first-class marketing landing page concept. Mismatched ecosystem.

---

### 5. Plain HTML (no SSG)

Write raw HTML/CSS/JS files, check them into the repo, and serve them from GitHub Pages directly.

**Compose/JS integration**: Maximum flexibility. The demo is a first-class citizen — no adapter or pass-through configuration needed.

**GitHub Pages**: Trivially supported. No CI required — pushing the files is enough.

**DX**: Zero framework overhead. But: no templating means duplication across pages (repeated nav/footer HTML), no Markdown authoring, manual syntax highlighting, and no search.

**Documentation features**: None built-in. Must hand-roll or pull in libraries (Prism.js, lunr.js, etc.).

**Downsides for SteleKit**: The requirements include user docs and developer docs across multiple pages. Maintaining those in raw HTML is not sustainable for a solo maintainer. This option only makes sense if the site is a single-page landing page with no prose documentation.

---

## Trade-off Matrix

| Axis | Astro + Starlight | Docusaurus | VitePress | MkDocs Material | Plain HTML |
|---|---|---|---|---|---|
| **Setup time** | Low | Medium | Low | Low (Python) | Trivial |
| **Doc feature richness** | High (Starlight) | Highest | High | Highest | None |
| **Landing page DX** | Excellent | Good | Good (requires Vue) | Poor | Excellent |
| **Compose JS embed** | Easy (public/) | Easy (static/) | Easy (public/) | Easy (docs/) | Trivial |
| **GitHub Pages CI** | First-class | First-class | First-class | First-class (gh-deploy) | Trivial |
| **Ecosystem match** | Agnostic / TS | React / TS | Vue / TS | Python | None |
| **Ongoing maintenance** | Low | Medium | Low | Low-Medium | High (raw HTML) |
| **Search (built-in)** | Pagefind (Starlight) | Local / Algolia | Built-in local | Built-in | Manual |
| **Non-Node toolchain risk** | None | None | None | Python in CI | None |
| **Demo page flexibility** | High | Medium | Medium | Low | Highest |

---

## Risk and Failure Modes

### R1 — jscanvas vs wasmJs confusion
The requirements use the term "Compose/Wasm" but the actual target is `js(IR)` with `org.jetbrains.compose.experimental.jscanvas.enabled=true`. This is Skiko rendering to an HTML `<canvas>` on the JS/IR target, not the newer `wasmJs` target. The demo will be a `.js` webpack bundle, not a `.wasm` binary. SSG choice is unaffected, but the demo page setup differs: instead of needing a `application/wasm` MIME type configuration, it just needs the JS bundle loaded correctly. **Risk: low for SSG selection; medium for demo page authorship** — the current `Main.kt` does not use Compose canvas at all (it writes innerHTML directly); actual Compose canvas entry point may not exist yet.

### R2 — sql.js worker same-origin restriction
The build includes `npm("sql.js", "1.10.3")` and `npm("@cashapp/sqldelight-sqljs-worker", "2.3.2")`. sql.js loads a Wasm binary internally via a Web Worker. The worker `.js` file must be served from the same origin as the page (browser same-origin worker restriction). GitHub Pages serves all files from `username.github.io`, so CORS is not an issue as long as all assets stay in the same repo/Pages deployment. **Risk rises** if the SSG moves assets to an external CDN. **Mitigation**: keep the worker file in the same repository, not on a CDN.

### R3 — Webpack output path assumptions
The Kotlin Gradle JS build emits output to `kmp/build/distributions/` by default (browser targets). The CI pipeline must copy this directory into the SSG's static asset directory before deploying. If paths are hardcoded in the demo HTML, they will break if the build output path changes. **Mitigation**: use relative paths in the demo HTML, or a Gradle task that copies artifacts to a known location (e.g., `site/public/demo/`).

### R4 — GitHub Pages 1 GB size limit
The JS bundle for a Compose + SQLDelight app with sql.js is potentially large (estimated 15–40 MB). [TRAINING_ONLY — verify actual bundle size for this project.] GitHub Pages imposes a soft 1 GB site size limit. This is unlikely to be hit, but if build artifacts are committed to the repo history over time, it can grow. **Mitigation**: never commit build outputs to git; always build and deploy via CI without committing the artifacts.

### R5 — Experimental jscanvas stability
`org.jetbrains.compose.experimental.jscanvas` is marked experimental. It may have rendering issues with text selection, scrolling, and accessibility. The demo should carry a clear disclaimer. [TRAINING_ONLY — verify current stability status in Compose Multiplatform 1.7.x]

### R6 — Demo not yet functional
`kmp/src/jsMain/kotlin/.../browser/Main.kt` currently renders a static HTML string, not a Compose UI. A working Compose canvas demo must be built before the demo page can be designed. This is a dependency for Phase 5 (implementation), not a blocker for SSG selection.

---

## Migration and Adoption Cost

| Option | Initial setup estimate | Migration path if wrong |
|---|---|---|
| Astro + Starlight | 2–4 hours | Markdown content is portable; switching to VitePress or MkDocs is a config rewrite, not a content rewrite |
| Docusaurus | 4–8 hours | Markdown portable; MDX React components require rewrite |
| VitePress | 2–4 hours | Markdown portable; Vue components require rewrite |
| MkDocs Material | 2–3 hours | Markdown portable; Python toolchain must be removed from CI |
| Plain HTML | 1–2 hours | Full rewrite to adopt any SSG |

All Markdown-based SSGs share the same content portability. The highest lock-in is Docusaurus (React MDX components) and plain HTML.

---

## Operational Concerns

**CI/CD pipeline shape**: The GitHub Actions workflow will need to:
1. Checkout the repo
2. Set up JDK + Node (Gradle + Kotlin JS)
3. Run `./gradlew jsBrowserDistribution` to produce the JS bundle
4. Set up Node for the SSG
5. Copy the JS bundle into the SSG's static asset directory
6. Run the SSG build
7. Deploy to GitHub Pages via `actions/upload-pages-artifact` + `actions/deploy-pages`

This two-stage build (Kotlin first, site second) works identically regardless of SSG choice.

**Build caching**: Gradle caches should be restored between CI runs. The SSG node_modules cache should also be saved. Without caching, full builds will take 5–15 minutes. [TRAINING_ONLY — verify typical Compose JS build time for this project]

**GitHub Pages deployment method**: The modern approach uses `actions/upload-pages-artifact` + `actions/deploy-pages` rather than the older `JamesIves/github-pages-deploy-action` gh-pages branch approach. All modern SSGs support this. `permissions: pages: write` is required on the workflow.

**Branch protection**: Deployments should trigger only on pushes to `main`.

---

## Prior Art and Lessons Learned

- **KMM-adjacent OSS projects** (SQLDelight, Koin, Ktor) commonly use MkDocs Material or Docusaurus for documentation. [TRAINING_ONLY — verify current framework choices for SQLDelight, Koin]
- **Compose/canvas browser demos**: Skia-based browser demos (Flutter web, Compose for Web experiments) commonly embed the canvas in an `<iframe>` on the landing page to isolate the app's global CSS and JS from the site's own styles. The iframe pattern is strongly recommended for SteleKit — it prevents the Compose app's fullscreen canvas from interfering with the site's layout.
- **Astro + Starlight** has been adopted by Biome, Tauri v2, and several large OSS projects. [TRAINING_ONLY — verify current Starlight adopter list]
- **JetBrains documentation** (Compose Multiplatform, Ktor) uses JetBrains Writerside — a proprietary toolchain not applicable here.
- **The sql.js Web Worker pattern** is well-established for SQLite in the browser. The `@cashapp/sqldelight-sqljs-worker` package is designed specifically for this deployment scenario.

---

## Open Questions

1. **Is the jscanvas demo buildable today?** `Main.kt` renders innerHTML, not a Compose canvas. A Compose canvas entry point (e.g., `CanvasBasedWindow { App() }`) may need to be written before the demo page is designable. This must be resolved in Phase 5.

2. **What is the actual JS bundle size?** Knowing the size determines whether to lazy-load the demo behind a button or embed it directly on the landing page.

3. **Do the branding color tokens already exist in a consumable format?** Requirements reference `docs/archive/tasks/branding-color-tokens.md`. The site theme should reuse these tokens (as CSS custom properties) rather than introduce new values.

4. **Is Starlight the right tier, or is plain VitePress sufficient?** If developer docs are a small set of Markdown pages, VitePress defaults may be enough without Starlight's Pagefind search and sidebar configuration overhead.

5. **Will the project ever need versioned documentation?** At MVP the answer is no. If SteleKit stabilises and ships major API changes, the SSG choice (Astro/Starlight supports versioning; VitePress does not natively) may matter. This should be a low-weight factor at decision time.

---

## Recommendation

**Use Astro with the Starlight documentation theme, deployed to GitHub Pages via GitHub Actions.**

Reasons:

1. **Landing page + docs in one tool**: Astro handles both a visually differentiated marketing landing page and structured documentation (via Starlight) in a single project. VitePress and MkDocs require more hacking to produce a custom-designed landing page alongside documentation.

2. **Zero JS by default on marketing pages**: The landing page loads fast before the Compose JS bundle is lazy-loaded. Docusaurus ships a React runtime on every page unconditionally.

3. **Demo embed is trivial**: The Kotlin `jsBrowserDistribution` output drops into `public/demo/`. The demo page is a single `.astro` file with an `<iframe src="/demo/">`. No framework adapter needed.

4. **Low ongoing maintenance**: For a solo maintainer, Astro + Starlight's conventions are stable and well-documented. Markdown content is portable if the choice needs to change.

5. **GitHub Pages CI is first-class**: The Astro docs provide a reference `deploy.yml`. The two-stage pipeline (Gradle JS build then Astro build then deploy) is straightforward to implement.

6. **Ecosystem neutrality**: Introducing React (Docusaurus) or Vue (VitePress) as the site's primary framework creates a second "real" framework in what is otherwise a pure Kotlin project. Astro's `.astro` template syntax is minimal and does not require React/Vue knowledge for maintenance.

**Fallback**: If Starlight proves too heavyweight for a small docs set, drop to plain VitePress — the Markdown content files are fully portable between the two.

**Do not use MkDocs** — the Python toolchain is a poor fit for a Kotlin/JVM CI pipeline.

**Do not use plain HTML** — the documentation surface is too large to maintain without templating or Markdown support.

---

## Pending Web Searches

The following exact queries should be verified before finalising this recommendation:

1. `astro starlight github pages deploy workflow 2025` — confirm the current official Actions workflow and that Starlight's Pagefind search works on GitHub Pages.

2. `compose multiplatform jscanvas experimental browser canvas 2025 stability` — confirm current stability of the `org.jetbrains.compose.experimental.jscanvas` target in Compose Multiplatform 1.7.x and what a working entry point looks like.

3. `"sql.js" web worker same-origin github pages` — verify that sql.js workers function correctly on GitHub Pages without additional CORS headers.

4. `kotlin multiplatform jsBrowserDistribution bundle size skiko compose 2024` — find real-world bundle size measurements for a Compose + SQLDelight JS browser target comparable to SteleKit.

5. `docusaurus vs astro starlight 2025 documentation site comparison` — check for recent community comparisons that may update the trade-off matrix above.
