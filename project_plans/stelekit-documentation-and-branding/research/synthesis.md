# Research Synthesis: SteleKit Documentation and Branding

**Date**: 2026-04-18
**Sources**: findings-stack.md, findings-features.md, findings-architecture.md, findings-pitfalls.md + web search verification

---

## Decision Required

Choose the technology stack, information architecture, and CI/CD pipeline for a GitHub Pages site with a live browser demo, user docs, and developer docs — then determine the prerequisite work needed before the demo can ship.

---

## Context

SteleKit has no public web presence. The branch `stelekit-documentation-and-branding` is dedicated to establishing branding, a GitHub Pages site, a live Compose/Wasm browser demo, and documentation. The project currently uses a `js(IR)` Compose for Web target with an experimental jscanvas flag — it does **not** yet have a `wasmJs` Gradle target, which is the prerequisite for a full Compose Multiplatform browser demo.

Key constraints:
- Solo maintainer (Tyler Stapler)
- No hard deadline; ship MVP first
- Site tech is unconstrained (any SSG); app demo must use KMP/Wasm target
- Existing branding artifacts in `docs/archive/tasks/branding-*.md` provide copy, voice, and positioning

---

## Critical Prerequisite: wasmJs Target Does Not Exist Yet

**This is the most important finding from research.** The current codebase has:
- `gradle.properties`: `enableJs=true`
- `gradle.properties`: `org.jetbrains.compose.experimental.jscanvas.enabled=true`
- `kmp/src/jsMain/kotlin/.../browser/Main.kt`: writes raw `innerHTML` — no Compose canvas

The requirements assume a working Compose/Wasm demo. The current JS target produces a status page, not the actual app UI. The `wasmJs` Gradle target does not exist. **Phase 5 implementation must add the `wasmJs` target before any demo page work begins.**

JetBrains' official template (`Kotlin/kotlin-wasm-compose-template`) uses `./gradlew wasmJsBrowserDistribution` and outputs to `build/dist/wasmJs/productionExecutable/`. The `CanvasBasedWindow` composable is the correct entry point.

**Compose Multiplatform 1.9.0 announced Web as Beta (Sept 2025)**. The current project is on 1.7.3. Upgrading to 1.9.x before starting Wasm work is strongly recommended to get a more stable target.

---

## Options Considered

### Site Technology Stack

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| Astro + Starlight | Marketing landing page + documentation in one tool; minimal JS on marketing pages | Requires `.astro` template learning; largest SSG to set up |
| VitePress | Excellent Vue-based docs SSG; minimal config for doc sites | Docs-first; custom landing page requires hacking defaults |
| Docusaurus | React-based; best for large doc sets with versioning | Ships React runtime unconditionally; overkill for small docs |
| MkDocs (Material) | Python-based; excellent for pure-docs sites | Wrong toolchain for a Kotlin/JVM CI pipeline |
| Plain HTML | No framework overhead; immediate | Unmaintainable at documentation scale |

### CI/CD Pipeline Architecture

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| Monorepo, two-job CI | Gradle builds demo, SSG builds site, single deploy | Best DX for solo maintainer; 15–20 min pipeline |
| Committed `docs/` dir | No CI; output committed to repo | Grows git history with binary artifacts; not sustainable |
| Separate site repo | Site in a dedicated repo | Cross-repo artifact access; too complex for solo project |
| GitHub Releases artifact | Demo loaded from release asset | Demo only updates on tagged releases; CORS risk |

### Demo Target

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| `wasmJs` + CanvasBasedWindow | Full Compose UI in browser via Skia canvas | Requires `wasmJs` Gradle target (not yet implemented); 4–9 MB gzipped |
| `js(IR)` current state | Existing JS target producing status page | Not a real demo; undermines the site's main CTA |
| `js(IR)` + Compose HTML | Compose DOM renderer; different look from native app | Doesn't showcase KMP's cross-platform UI story |

---

## Dominant Trade-off

**Site maintenance burden vs. feature richness.** A more powerful SSG (Docusaurus) provides features out of the box but adds a dependency surface. A simpler SSG (VitePress) is easier to maintain but requires more work for a custom landing page. For a solo maintainer, the equilibrium is **Astro + Starlight**: the landing page and docs live in one tool, the Astro action provides first-class GitHub Pages integration, and the `.astro` template format is simpler than React.

**Demo shipping risk vs. first impression.** A slow or broken Wasm demo is worse than no demo. SteleKit's browser demo is genuinely differentiated (Logseq's demo requires local folder access; Obsidian has no browser demo), but it requires prerequisite engineering work before it can ship.

---

## Recommendation

### Choose: Astro + Starlight for the site, monorepo two-job CI, `wasmJs` target for the demo

**Because**:
- Astro's official `withastro/action` provides a documented, first-class GitHub Pages deploy workflow. The `base` config for subpath hosting (`/stelekit/`) is well-supported.
- Starlight handles both structured docs and a custom marketing landing page in one tool — no second framework needed.
- The monorepo pipeline is the only option with good DX for a solo maintainer: Gradle cache is reused, no cross-repo artifact coordination, no CI secrets beyond `GITHUB_TOKEN`.
- The `wasmJs` target with `CanvasBasedWindow` is the only option that demonstrates the real app. It is a prerequisite task, not a blocker for the site itself.

**Accept these costs**:
- Astro + Starlight has a learning curve; the first deploy will take longer than a plain HTML page.
- The full pipeline takes ~15–20 min on cold CI (Gradle JS/Wasm build is the bottleneck).
- The demo page ships with a "loading" skeleton and may need a few rounds of performance tuning.

**Reject these alternatives**:
- **Docusaurus**: React runtime on every page; second framework in a pure-Kotlin project; overkill for a small docs set.
- **Separate site repo**: Cross-repo artifact management adds operational complexity without benefit for a solo maintainer.
- **Current `js(IR)` as the demo**: A status page is not a demo. Ships a false impression. Must not be used as the primary "Try in Browser" destination.

---

## Phased Implementation Plan

### Phase 0: Foundation (before demo) — 1–2 days
- [ ] Upgrade CMP to 1.9.x (Web Beta) in `kmp/build.gradle.kts`
- [ ] Add `wasmJs` target with minimal `CanvasBasedWindow { Text("Hello SteleKit") }` entry point in `wasmJsMain`
- [ ] Verify `./gradlew :kmp:wasmJsBrowserDistribution` produces output at `build/dist/wasmJs/productionExecutable/`
- [ ] Confirm Wasm renders in Chrome with no console errors
- [ ] Measure actual bundle size (compare against 100 MB/file limit and 8 MB gzip target)

### Phase 1: Site + CI/CD — 2–3 days
- [ ] Create `site/` directory with Astro + Starlight
- [ ] Configure `base: '/stelekit/'` in `astro.config.mjs`
- [ ] Add `site/public/.nojekyll` as a committed static file
- [ ] Create `pages.yml` GitHub Actions workflow:
  - `build-demo` job: `wasmJsBrowserDistribution` → upload artifact
  - `build-site` job: download artifact → copy to `site/public/demo/` → Astro build → upload pages artifact
  - `deploy` job: `actions/deploy-pages`
- [ ] Enable GitHub Pages in repo settings (source: GitHub Actions)
- [ ] Verify deploy succeeds and landing page is live

### Phase 2: Demo hardening — 1–2 days
- [ ] Add `coi-serviceworker.min.js` to demo HTML `<head>` (fixes SharedArrayBuffer for Chrome)
- [ ] Add `wasm-gc` browser feature detection; show styled fallback message if unsupported
- [ ] Add HTML/CSS loading screen (renders before Wasm canvas)
- [ ] Add error handler for Wasm init failures with screenshot fallback
- [ ] Connect `robust-demo-graph` in-memory graph to `wasmJsMain` entry point
- [ ] Test on Chrome, Firefox, Safari macOS, Safari iOS

### Phase 3: Content — 3–5 days
- [ ] Landing page: hero ("Your knowledge, carved in stone."), platform matrix, feature cards, Logseq comparison table, quick start, dual audience links
- [ ] User guide (5–6 pages): getting-started, outliner, journals, backlinks, search
- [ ] Developer docs (3–4 pages): architecture, build, contributing, module-structure — draw from `docs/architecture/` and existing ADRs
- [ ] Demo page: "What to try" intro + embedded Wasm canvas

---

## Key Verified Facts (from web searches)

| Claim | Status | Source |
|-------|--------|--------|
| wasm-gc is Baseline in all major browsers | Verified | Safari 18.2 (Dec 2024) = last holdout |
| `coi-serviceworker` is the standard GitHub Pages + SharedArrayBuffer fix | Verified | Multiple KMP Wasm deploy guides |
| Live GitHub Pages serves `.wasm` with correct MIME type | Verified | Community reports; jekyll-serve only has the issue locally |
| `wasmJs` output path: `build/dist/wasmJs/productionExecutable/` | Verified | JetBrains template + community guides |
| Astro `withastro/action` for GitHub Pages is first-class | Verified | Official Astro docs |
| Logseq demo requires local folder access; no pure browser demo | Verified | demo.logseq.com requires folder grant |
| GitHub Pages: 1 GB site size, 100 GB/month bandwidth | Verified | GitHub Docs |
| CMP 1.9.0 announced Web as Beta (released ~Sept 2025) | Verified | JetBrains Kotlin Blog |

---

## Open Questions Before Committing to Implementation

- [ ] What is the actual gzipped bundle size of a `wasmJs` CMP 1.9.x build for SteleKit? — must measure with `./gradlew wasmJsBrowserDistribution` after upgrade. If >15 MB gzipped, need loading screen with progress indicator; if >50 MB raw, investigate tree-shaking.
- [ ] Does the SQLDelight `web-worker-driver` work in `wasmJs`, or does a different driver/approach apply? — SQLDelight docs warn against in-browser SQL. The `robust-demo-graph` uses in-memory backend; confirm `IN_MEMORY` backend compiles to `wasmJs`.
- [ ] Does the existing `robust-demo-graph` in-memory graph work in the `wasmJs` source set? — necessary before the demo can render actual content rather than a placeholder.
- [ ] Is the `org.jetbrains.compose.experimental.jscanvas` target being deprecated in favor of `wasmJs`? — affects whether to keep or remove the existing `js(IR)` target after migration.

If all four questions are answerable with "yes" after Phase 0 spikes, proceed to Phase 1 immediately. If any produce an unknown, scope and resolve before planning further.

---

## Sources

- `project_plans/stelekit-documentation-and-branding/research/findings-stack.md`
- `project_plans/stelekit-documentation-and-branding/research/findings-features.md`
- `project_plans/stelekit-documentation-and-branding/research/findings-architecture.md`
- `project_plans/stelekit-documentation-and-branding/research/findings-pitfalls.md`
- [Kotlin/kotlin-wasm-compose-template](https://github.com/Kotlin/kotlin-wasm-compose-template)
- [Deploy Kotlin Multiplatform WasmJS to GitHub Pages — Scott Lanoue](https://medium.com/@schott12521/deploy-kotlin-mutliplatform-wasmjs-to-github-pages-fe295d8b420f)
- [WebKit Features in Safari 18.2 — WasmGC](https://webkit.org/blog/16301/webkit-features-in-safari-18-2/)
- [Deploy your Astro Site to GitHub Pages — Astro Docs](https://docs.astro.build/en/guides/deploy/github/)
- [GitHub Pages limits — GitHub Docs](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits)
- [Compose Multiplatform 1.9.0 — Web Beta](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/)
