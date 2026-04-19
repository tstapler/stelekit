# Findings: Architecture — Site + Demo Pipeline Design

**Input**: `project_plans/stelekit-documentation-and-branding/requirements.md`
**Date**: 2026-04-18
**Status**: Training-knowledge draft — pending web search verification

---

## Summary

SteleKit already has a working JS/Wasm target (`js(IR)` with `binaries.executable()`) controlled by `enableJs=true` in `gradle.properties`. The existing CI web job calls `./gradlew :kmp:jsBrowserProductionWebpack`, which produces a Webpack-bundled JavaScript output (not a native Wasm binary via a `wasmJs` target — see Open Questions). The current `index.html` in `kmp/src/jsMain/resources/` is a minimal bootstrap page; the Compose Multiplatform HTML canvas is not yet wired to the entry point (`Main.kt` uses raw `document.getElementById` / `innerHTML`, not the Compose canvas renderer).

The core architectural question is where to house the marketing site, how to serve the demo, and how to wire them together in CI with a single deploy to GitHub Pages.

**Key findings:**

1. **Monorepo deploy is the lowest-friction path** for a solo maintainer. One CI pipeline builds everything; Gradle cache is reused; no artifact hand-off between repos.
2. **The Gradle JS target uses Webpack (not raw Wasm)** — the output is `kmp.js` (or similar) plus any copied `.wasm` side-files (e.g., `sql-wasm.wasm` from SQLite). GitHub Pages can serve both file types.
3. **A `.nojekyll` file is required** — without it, GitHub Pages' Jekyll processing strips leading-underscore directories (`_next/`, `_astro/`) that most SSGs emit, breaking the site silently with 404s.
4. **The `gh-pages` branch deploy model** (via `actions/deploy-pages`) is the cleanest CI pattern for generated output that should not live in the main branch history.
5. **Same-origin embedding** (the demo HTML loads the Gradle-generated JS from the same GitHub Pages domain) avoids CORS entirely and is the preferred approach.

---

## Options Surveyed

### Option 1: Monorepo, two-job CI — Gradle builds demo, SSG builds site

**Structure:**
```
stelekit/
  kmp/                        ← Kotlin source + Gradle (unchanged)
  site/                       ← SSG source (Astro / VitePress / Hugo)
    src/pages/
    public/.nojekyll
    astro.config.mjs          ← base: '/stelekit/'
  .github/workflows/
    pages.yml                 ← job 1: Gradle JS; job 2: SSG + deploy
```

**CI flow:**
1. `build-demo` job: Gradle `jsBrowserProductionWebpack` → upload artifact
2. `build-site` job: download artifact → copy to `site/public/demo/` → SSG build → upload pages artifact
3. `deploy` job: `actions/deploy-pages`

**Pros:**
- Single repo, single Gradle cache, single deploy pipeline
- SSG reads Gradle output paths reliably without cross-repo coordination
- Local preview: `./gradlew :kmp:jsBrowserProductionWebpack && npm run dev` in `site/`
- No secrets needed beyond `GITHUB_TOKEN` (built-in for `actions/deploy-pages`)
- Existing `web` CI job can be extended to upload the artifact, eliminating duplicate Gradle runs

**Cons:**
- Full pipeline takes ~15–20 min (Gradle JS compilation is the bottleneck)
- SSG node_modules cache must be configured separately from Gradle cache
- If Gradle JS build breaks, the deploy fails — site update blocked until app builds

---

### Option 2: Subdir deploy — committed `docs/` directory

**Structure:**
```
stelekit/
  docs/                        ← committed site output (including demo JS)
```

GitHub Pages configured to serve from `docs/` on `main`.

**Pros:**
- No CI deploy step needed at all
- Easy to inspect deployed state in the repo

**Cons:**
- Binary/built artifacts committed to `main` — persistent repo size inflation
- Multi-MB JS bundles accumulate in git history
- PRs must regenerate and commit site output manually — error-prone
- Breaks the "build artifacts belong outside version control" principle

**Verdict:** Acceptable as a one-day proof-of-concept first deploy only.

---

### Option 3: Separate site repo

**Structure:**
- `stelekit` — app code
- `stelekit-site` — GitHub Pages source, separate repo

`stelekit-site` fetches the JS artifact from `stelekit` releases or workflow artifacts.

**Pros:**
- Clean separation; site contributors don't need Gradle installed
- Site deploys can iterate without touching app code

**Cons:**
- Cross-repo artifact access requires published GitHub Releases or `actions/download-artifact` with cross-repo permissions — added complexity
- Two repos to maintain for one person
- Gradle cache not reused in site repo
- Local development requires running two repositories in sync

**Verdict:** Best at scale (multiple contributors, dedicated web team). Premature for a solo project.

---

### Option 4: GitHub Releases artifact — site loads JS remotely

- App releases publish the JS/Wasm bundle as a GitHub Release asset
- GitHub Pages site loads the bundle from `github.com/releases/download/...`

**Pros:**
- Demo only updates on tagged releases — predictable stability
- Site build is completely independent of Gradle

**Cons:**
- GitHub Release asset URLs are not deterministic without version string management
- Cross-origin load from `github.com` to `user.github.io`: CORS headers on GitHub Release assets are not guaranteed to be permissive [TRAINING_ONLY — verify]
- No continuous update; demo lags behind `main`

**Verdict:** Useful for distributing download archives, not for the live demo.

---

## Trade-off Matrix

| Axis | Monorepo single pipeline | Subdir commit | Separate repo | Releases artifact |
|------|--------------------------|---------------|---------------|-------------------|
| CI complexity | Low — one workflow | None | High — cross-repo | Medium |
| Build time (cold) | ~15–20 min | N/A (manual) | ~20+ min | ~15–20 min |
| Gradle cache reuse | Yes | N/A | No | No |
| Deploy latency after push to main | ~15–20 min | Immediate | ~20+ min | Only on release tag |
| Separation of concerns | Medium | Low | High | High |
| Local preview | Easy (two commands) | Easy (open file) | Hard (two repos) | Hard (remote dep) |
| Repo size over time | Clean (gitignored build output) | Grows with artifacts | Clean | Clean |
| CORS complexity | None (same-origin) | None | None | Possible |
| `.nojekyll` required | Yes | Yes | Yes | Yes |
| Wasm/JS file size risk | GitHub Pages 100 MB/file limit applies | Same | Same | GitHub CDN serves |
| Solo maintainer DX | Best | Acceptable MVP | Poor | Fragile |

---

## Risk and Failure Modes

### R1: JS target not producing a functional Compose UI yet

`kmp/src/jsMain/kotlin/.../browser/Main.kt` currently writes raw DOM `innerHTML` — it does not use Compose Multiplatform's canvas renderer. The CI web job builds `jsBrowserProductionWebpack`, but the resulting bundle renders a status page, not the actual app. The site pipeline must be designed to deploy and iterate independently of whether the demo is functionally complete.

**Mitigation:** Design the demo page with a placeholder state ("Interactive demo coming soon") gated by a feature flag or environment variable. The CI pipeline always copies the Gradle output to `site/public/demo/`; the demo page renders it when ready.

---

### R2: GitHub Pages file size limit

GitHub Pages has a **1 GB total repo size soft limit** and a **100 MB per-file hard limit**. Compose Multiplatform JS bundles for a full app can reach 30–80 MB uncompressed [TRAINING_ONLY — verify for this specific project]. GitHub Pages applies gzip compression on transfer, so users download less, but the raw file on disk must be under 100 MB.

**Mitigation:** Add a CI step that fails if any file in `kmp/build/distributions/` exceeds 90 MB:
```bash
find kmp/build/distributions -type f -size +90M -exec echo "FILE TOO LARGE: {}" \; | grep . && exit 1 || true
```

---

### R3: Gradle output path instability

For `js(IR)` with `jsBrowserProductionWebpack`, the output path is typically:
```
kmp/build/distributions/
  kmp.js
  kmp.js.map
  index.html           (from jsMain/resources)
  sql-wasm.wasm        (copied by CopyWebpackPlugin)
```
[TRAINING_ONLY — the JS file may be named `stelekit.js` or `logseq-kmp.js` depending on `moduleName` in the Gradle config; the current `index.html` imports `./logseq-kmp.js` suggesting this is the actual file name]

**Mitigation:** Pin the CI copy step to the verified output path. Add an assertion:
```bash
test -f kmp/build/distributions/logseq-kmp.js || { echo "Expected JS output missing"; exit 1; }
```

---

### R4: `wasmJs` vs `js(IR)` confusion

The project uses `js(IR)` with Compose for Web (HTML canvas). The term "Wasm" appears because `sql-wasm.wasm` (SQLite's WebAssembly binary) is part of the output. This is not Compose Multiplatform's native Wasm target (`wasmJs`). If the project later migrates to the `wasmJs` Gradle target (full Compose Wasm output), the artifact path changes to `build/dist/wasmJs/productionExecutable/` and the MIME type requirement for `application/wasm` becomes more critical.

**Mitigation:** Document this distinction in the pipeline. Keep the copy step parametric so migration to `wasmJs` requires only updating the source path constant.

---

### R5: Jekyll stripping underscore directories

Without `.nojekyll`, GitHub Pages runs Jekyll preprocessing which ignores directories beginning with `_`. Astro outputs assets to `_astro/`, Next.js to `_next/`. Missing `.nojekyll` causes silent 404s on all JS/CSS assets.

**Mitigation:** Commit `.nojekyll` to `site/public/` (so it's always in SSG output) and also add `nojekyll: true` in the deploy action configuration as a belt-and-suspenders measure.

---

### R6: Base path misconfiguration

GitHub Pages for `github.com/tstapler/stelekit` serves at `https://tstapler.github.io/stelekit/`. All SSG asset paths must use `/stelekit/` as the base. Missing or wrong `base` config causes all asset references to 404.

**Mitigation:** Configure `base: '/stelekit/'` in `astro.config.mjs` (or equivalent SSG config). Test locally by serving from a subdirectory: `npx serve -p 3000 site/dist` then navigating to `localhost:3000/stelekit/`.

---

### R7: Gradle daemon memory in CI deploy job

The Gradle JS build uses up to 4 GB heap (`-Xmx4g`). The deploy job runs `ubuntu-latest` which has 7 GB RAM. Running SSG build in the same job as Gradle could cause OOM.

**Mitigation:** Split into `build-demo` (Gradle) and `build-site` (SSG) jobs connected by artifact upload/download. This also allows cache keys to be properly scoped per tool.

---

## Migration and Adoption Cost

### Phase 0: Minimal viable deploy (1–2 days)

- Add `site/` directory with a single `index.html` (no SSG; just static HTML)
- Add `pages.yml` GitHub Actions workflow: Gradle JS → copy to site → deploy to `gh-pages`
- Add `.nojekyll` in deploy output
- Enable GitHub Pages in repo settings: source = GitHub Actions
- **Result:** Live URL, demo artifact served, CI auto-deploys on push to `main`

### Phase 1: SSG integration (3–5 days)

- Add Astro (or chosen SSG) to `site/`
- Build landing page, docs index, demo page
- Configure `base: '/stelekit/'`
- Wire SSG build to consume Gradle output from `site/public/demo/`
- **Result:** Full navigable site with landing, demo, docs

### Phase 2: Demo completion (depends on app readiness)

- Wire `Main.kt` to use Compose Multiplatform canvas renderer
- Update demo page if embedding changes (iframe vs direct load)
- **Result:** Fully interactive live demo

---

## Operational Concerns

### Caching strategy

| Tool | Cache mechanism | Key |
|------|----------------|-----|
| Gradle | `gradle/actions/setup-gradle` + `GRADLE_ENCRYPTION_KEY` | Already configured |
| Node.js (SSG) | `actions/setup-node` with `cache: npm` | `site/package-lock.json` |

### Deploy trigger strategy

| Event | Action |
|-------|--------|
| Push to `main` | Full build + deploy |
| Pull request | Run `build-demo` only (verify Gradle JS compiles); skip deploy |
| Workflow dispatch | Full build + deploy (manual trigger for hotfixes) |

### URL structure

```
https://tstapler.github.io/stelekit/           ← landing page
https://tstapler.github.io/stelekit/demo/      ← JS demo (Gradle output)
https://tstapler.github.io/stelekit/docs/      ← user and developer documentation
```

The Gradle-produced `index.html` and `logseq-kmp.js` are served from `/stelekit/demo/`. All relative script references in `index.html` must resolve correctly from that path. Since `index.html` currently uses `import('./logseq-kmp.js')` (relative), it should work without modification when served from any subdirectory.

### What `.nojekyll` does

GitHub Pages runs Jekyll by default on the branch being served. Jekyll's convention is to ignore files/directories starting with `_` or `.` (treating them as private). A `.nojekyll` file placed at the root of the served content disables Jekyll processing entirely, so all files — including `_astro/`, `_next/`, `__sveltekit/` — are served as-is. Without `.nojekyll`, SSG builds silently fail to serve their asset bundles.

---

## Prior Art and Lessons Learned

### JetBrains Compose Multiplatform samples

JetBrains' own sample repos (e.g., `compose-multiplatform-template`) use a GitHub Actions workflow that calls `./gradlew wasmJsBrowserDistribution` and deploys the output directory to GitHub Pages via `JamesIves/github-pages-deploy-action`. Their output path is `composeApp/build/dist/wasmJs/productionExecutable/`. They include `.nojekyll` in the deployed folder. [TRAINING_ONLY — verify exact workflow]

### Kotlin/JS IR webpack output

For projects using `js(IR)` with `jsBrowserProductionWebpack`, the output directory is `build/distributions/` relative to the Gradle module. The JS file name matches the Gradle project/module name or the `moduleName` override. The existing `index.html` references `./logseq-kmp.js`, suggesting the module is named `logseq-kmp` in Webpack configuration.

### GitHub Actions `actions/deploy-pages`

This is the official GitHub-maintained action for GitHub Pages deployment. It works with `actions/upload-pages-artifact` (which adds a `tar.gz` artifact named `github-pages`) and respects the repository's Pages configuration. It requires `permissions: pages: write` and `id-token: write` in the workflow. It handles the `.nojekyll` behavior automatically if the uploaded artifact contains the file.

---

## Open Questions

- [ ] Is the current `js(IR)` target producing a functional Compose UI canvas? `Main.kt` uses raw DOM manipulation. The demo pipeline should be designed to deploy regardless of demo completeness. — blocks: demo page design
- [ ] What is the exact JS file name in `kmp/build/distributions/`? The current `index.html` imports `./logseq-kmp.js` — needs local build verification. [TRAINING_ONLY] — blocks: CI copy step
- [ ] Does GitHub Pages serve `.wasm` files with `Content-Type: application/wasm`? If not, the browser will refuse to instantiate the `sql-wasm.wasm` module. [TRAINING_ONLY] — blocks: demo correctness
- [ ] Should the deploy job reuse the `web` CI job artifact or run Gradle independently? — blocks: CI workflow design
- [ ] What GitHub username/org owns the `stelekit` repo? Determines the exact Pages URL and `base` path for SSG. — blocks: SSG configuration

---

## Recommendation

**Recommended architecture: Monorepo, `gh-pages` branch deploy, two-job CI**

**Repository layout changes required:**
```
stelekit/
  kmp/                               ← unchanged
  site/                              ← NEW: SSG source
    src/pages/
      index.astro                    ← landing page
      demo.astro                     ← demo wrapper
      docs/                          ← documentation pages
    public/
      .nojekyll                      ← committed static file, disables Jekyll
    astro.config.mjs                 ← base: '/stelekit/'
    package.json
  .github/workflows/
    ci.yml                           ← add: upload-artifact to web job
    pages.yml                        ← NEW: deploy workflow
```

**`pages.yml` workflow skeleton:**

```yaml
name: Deploy to GitHub Pages

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

jobs:
  build-demo:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - uses: gradle/actions/setup-gradle@v4
        with:
          cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}
      - name: Build JS demo
        run: ./gradlew :kmp:jsBrowserProductionWebpack --no-daemon --build-cache -PenableJs=true
      - name: Assert expected output exists
        run: test -f kmp/build/distributions/logseq-kmp.js
      - uses: actions/upload-artifact@v4
        with:
          name: demo-dist
          path: kmp/build/distributions/

  build-site:
    runs-on: ubuntu-latest
    needs: build-demo
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with:
          name: demo-dist
          path: site/public/demo/
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: site/package-lock.json
      - run: npm ci
        working-directory: site
      - run: npm run build
        working-directory: site
      - uses: actions/upload-pages-artifact@v3
        with:
          path: site/dist/

  deploy:
    runs-on: ubuntu-latest
    needs: build-site
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v4
```

**Key design decisions:**

- `site/public/.nojekyll` is committed to the repo (static file), not generated in CI — it can never be forgotten
- Gradle output is downloaded into `site/public/demo/` before the SSG build runs, so Astro copies it into `dist/demo/` as static assets
- The `base: '/stelekit/'` in Astro config makes all internal links and asset references use the correct subpath
- The demo page at `/demo/` uses an `<iframe src="/stelekit/demo/index.html">` to load the Compose canvas in isolation, or directly embeds the script — both approaches work same-origin
- `deploy` job requires `github-pages` environment (configure in repo Settings > Environments)

---

## Pending Web Searches

1. `site:github.com/JetBrains "compose-multiplatform" "wasmJs" OR "jsBrowserProductionWebpack" "deploy-pages" workflow` — verify canonical JetBrains Wasm/JS GitHub Pages deploy pattern and exact output directory path
2. `"github pages" ".wasm" "content-type" "application/wasm" served 2024 2025` — confirm whether GitHub Pages serves `.wasm` files with correct MIME type or if a workaround is needed
3. `"jsBrowserProductionWebpack" output "build/distributions" "moduleName" kotlin js ir` — confirm Gradle JS IR webpack output directory and file naming conventions
4. `"compose multiplatform" "wasmJs" OR "wasm" bundle size megabytes 2024 2025 github pages limit` — realistic bundle size data to evaluate against 100 MB per-file limit
5. `astro "base" subpath github pages "actions/deploy-pages" ".nojekyll" configuration 2024` — verify Astro base path + `.nojekyll` + `actions/deploy-pages` setup pattern
