# SteleKit Site

**Status**: Planned
**Depends on**: `docs/tasks/browser-wasm-demo.md` (wasmJs migration must be complete before Story 4 — demo page — can be fully implemented; all other stories are independent)

---

## Problem

SteleKit has no public web presence. Developers who discover the repository see a bare directory listing. Potential users cannot try the app without cloning and building it locally. The project competes with Logseq for mindshare but is invisible on the web.

This plan covers the GitHub Pages site, CI/CD pipeline, landing page, documentation, and demo page integration — everything except the wasmJs migration itself (planned in `docs/tasks/browser-wasm-demo.md`).

---

## Architecture Summary

```
stelekit/
  kmp/                                    ← Kotlin source (unchanged)
  site/                                   ← NEW: Astro + Starlight
    src/
      pages/
        index.astro                       ← Landing page
        demo.astro                        ← Demo wrapper (iframe)
      content/
        docs/
          user/                           ← User guide (5–6 pages)
          developer/                      ← Developer docs (3–4 pages)
    public/
      .nojekyll                           ← Committed static file; never generated in CI
    astro.config.mjs                      ← base: '/stelekit/'
    package.json
  .github/workflows/
    pages.yml                             ← NEW: two-job deploy workflow
```

**CI pipeline (pages.yml)**:
```
push to main
  └── build-demo (Gradle wasmJsBrowserDistribution, ~15–20 min)
        └── build-site (npm ci + astro build, ~2 min)
              └── deploy (actions/deploy-pages)
```

Site-only changes (`site/**`) skip `build-demo` via `paths` filters and reuse the last successful demo artifact.

**Key decisions** (see ADRs in `project_plans/stelekit-documentation-and-branding/decisions/`):
- ADR-001: Astro + Starlight over Docusaurus/VitePress/MkDocs
- ADR-002: Monorepo two-job CI over separate site repo
- ADR-003: GitHub Pages + `.nojekyll` + `coi-serviceworker`

---

## Dependency Visualization

```
Story 1: Site Scaffolding (Astro + Starlight)
    |
    +── Story 2: CI/CD Pipeline (pages.yml)
    |       |
    |       +── Story 3: Landing Page Content
    |       |
    |       +── Story 5: User Documentation
    |       |
    |       +── Story 6: Developer Documentation
    |
    +── Story 4: Demo Page
            |
            depends on: browser-wasm-demo.md (Phase A complete)
            degrades gracefully if artifact not yet available
```

Stories 1–2 are the critical path. Stories 3, 5, 6 can proceed in parallel once Story 1 is complete. Story 4 can be scaffolded in Story 1 and filled in after `browser-wasm-demo.md` ships.

---

## Story 1: Site Scaffolding

**Goal**: A runnable Astro + Starlight project in `site/` with correct base path configuration, `.nojekyll`, and a placeholder home page.

### Task 1.1 — Bootstrap Astro + Starlight in `site/`

**Objective**: Create the `site/` directory with a working Astro + Starlight install.

**Context boundary**: Files created or modified are entirely within `site/`. No Kotlin or Gradle files are touched.

**Prerequisites**: Node 20+, npm.

**Implementation approach**:

```bash
cd /path/to/stelekit
npm create astro@latest site -- --template starlight --no-install
cd site && npm install
```

The Starlight template creates:
```
site/
  src/
    content/
      docs/
        index.mdx          ← replace with our landing page stub
  astro.config.mjs
  package.json
  tsconfig.json
```

Starlight's default layout uses `src/content/docs/` for all documentation pages. The marketing landing page (`index.astro`) lives in `src/pages/` and overrides the default docs index route.

**Validation strategy**:
- `npm run dev` in `site/` serves the project at `localhost:4321`
- Landing page loads at `/` during dev (will be `/stelekit/` in production)
- No build errors in `npm run build`

**INVEST check**: Self-contained; no Kotlin knowledge required; deliverable is a committed `site/` directory.

---

### Task 1.2 — Configure `astro.config.mjs` for GitHub Pages subpath

**Objective**: Set `base: '/stelekit/'` and `site: 'https://tstapler.github.io'` so all asset references resolve correctly on GitHub Pages.

**Context boundary**: `site/astro.config.mjs` only.

**Prerequisites**: Task 1.1 complete.

**Implementation approach**:

```js
// site/astro.config.mjs
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://tstapler.github.io',
  base: '/stelekit',
  integrations: [
    starlight({
      title: 'SteleKit',
      description: 'A local-first outliner for Desktop and Android. Reads your Logseq markdown.',
      social: {
        github: 'https://github.com/tstapler/stelekit',
      },
      sidebar: [
        {
          label: 'User Guide',
          items: [
            { label: 'Getting Started', slug: 'user/getting-started' },
            { label: 'Outliner', slug: 'user/outliner' },
            { label: 'Journals', slug: 'user/journals' },
            { label: 'Backlinks', slug: 'user/backlinks' },
            { label: 'Search', slug: 'user/search' },
          ],
        },
        {
          label: 'Developer',
          items: [
            { label: 'Architecture', slug: 'developer/architecture' },
            { label: 'Build', slug: 'developer/build' },
            { label: 'Contributing', slug: 'developer/contributing' },
            { label: 'Module Structure', slug: 'developer/module-structure' },
          ],
        },
      ],
    }),
  ],
});
```

Note: Astro `base` must not have a trailing slash in the config; Astro adds the slash when generating URLs.

**Validation strategy**:
- `npm run build` in `site/` produces `dist/` with all paths prefixed `/stelekit/`
- `grep -r "href=\"/" site/dist/ | grep -v "/stelekit/"` returns no results (no unpatched absolute paths)
- Serve locally with `npx serve -p 3000 site/dist` and verify `http://localhost:3000/stelekit/` loads

**INVEST check**: Single file change; independently reviewable.

---

### Task 1.3 — Add `site/public/.nojekyll`

**Objective**: Commit `.nojekyll` as a static file so Jekyll is never run on the GitHub Pages deployment.

**Context boundary**: Create `site/public/.nojekyll` as an empty file.

**Prerequisites**: `site/` directory exists (Task 1.1).

**Implementation approach**: Create the file:

```bash
touch site/public/.nojekyll
```

Commit it. The file has no content. Astro copies all `public/` files verbatim to `dist/`, so `.nojekyll` will always be present at the root of every deployed artifact.

**Why committed, not generated**: If this file were created in a CI step (`touch site/public/.nojekyll`), it could be omitted in a manual deploy or a tooling change. Committing it makes it structurally impossible to forget.

**Validation strategy**: After `npm run build`, confirm `ls site/dist/.nojekyll` exists.

**INVEST check**: Trivial; wrong approach (CI-generated) has a documented failure mode that makes committed approach clearly superior.

---

### Task 1.4 — Add placeholder `src/pages/index.astro`

**Objective**: Replace the default Starlight index page with a custom `.astro` landing page stub so the marketing page route is established.

**Context boundary**: `site/src/pages/index.astro`.

**Prerequisites**: Tasks 1.1–1.2 complete.

**Implementation approach**:

```astro
---
// site/src/pages/index.astro
import BaseHead from '../components/BaseHead.astro';
---
<!doctype html>
<html lang="en">
  <head>
    <BaseHead title="SteleKit" description="A local-first outliner for Desktop and Android." />
  </head>
  <body>
    <h1>SteleKit</h1>
    <p>Your knowledge, carved in stone.</p>
    <p><a href="/stelekit/demo/">Try in Browser</a> | <a href="/stelekit/docs/">Documentation</a></p>
  </body>
</html>
```

This is a stub. Full landing page content is in Story 3.

**Validation strategy**: `npm run build` succeeds; `site/dist/index.html` exists and contains "SteleKit".

**INVEST check**: Isolated; does not touch documentation content.

---

## Story 2: CI/CD Pipeline

**Goal**: A working `pages.yml` workflow that builds the demo (Gradle) and site (Astro) and deploys to GitHub Pages on push to `main`.

### Task 2.1 — Create `.github/workflows/pages.yml`

**Objective**: Implement the two-job CI pipeline with path-filtered triggers, Gradle caching, Node caching, and `actions/deploy-pages`.

**Context boundary**: `.github/workflows/pages.yml` (new file).

**Prerequisites**: Story 1 complete; wasmJs Gradle target exists (from `browser-wasm-demo.md`). If the wasmJs target is not yet available, use a placeholder `build-demo` job that creates an empty `site/public/demo/` directory so the site pipeline can be validated independently.

**Implementation approach**:

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

concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  build-demo:
    runs-on: ubuntu-latest
    # Only rebuild Wasm when app code changes; site-only edits skip this job
    if: |
      github.event_name == 'workflow_dispatch' ||
      contains(toJSON(github.event.head_commit.modified), 'kmp/') ||
      contains(toJSON(github.event.head_commit.added), 'kmp/')
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - uses: gradle/actions/setup-gradle@v4
        with:
          cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}
      - name: Build wasmJs demo
        run: ./gradlew :kmp:wasmJsBrowserDistribution --no-daemon --build-cache -PenableJs=true
      - name: Assert expected output exists
        run: test -d kmp/build/dist/wasmJs/productionExecutable/
      - name: Assert no single file exceeds 90 MB
        run: |
          find kmp/build/dist/wasmJs/productionExecutable -type f -size +90M \
            -exec echo "FILE TOO LARGE: {}" \; | grep . && exit 1 || true
      - uses: actions/upload-artifact@v4
        with:
          name: demo-dist
          path: kmp/build/dist/wasmJs/productionExecutable/
          retention-days: 7

  build-site:
    runs-on: ubuntu-latest
    needs: build-demo
    if: always() && (needs.build-demo.result == 'success' || needs.build-demo.result == 'skipped')
    steps:
      - uses: actions/checkout@v4
      - name: Download demo artifact (if available)
        uses: actions/download-artifact@v4
        continue-on-error: true
        with:
          name: demo-dist
          path: site/public/demo/
      - uses: actions/configure-pages@v5
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: site/package-lock.json
      - run: npm ci
        working-directory: site
      - run: npm run build
        working-directory: site
        env:
          # Expose demo availability to Astro build for conditional rendering
          DEMO_AVAILABLE: ${{ steps.download-demo.outcome == 'success' }}
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

Key decisions in this implementation:
- `build-demo` only runs when `kmp/` files changed or on manual dispatch; this prevents the 15–20 min Gradle build from running on every docs edit.
- `build-site` uses `if: always() && ...needs.build-demo.result == 'skipped'` so site-only changes still deploy.
- `continue-on-error: true` on the artifact download lets the site build proceed even when no demo artifact was produced (before `browser-wasm-demo.md` ships); the demo page degrades gracefully.
- `DEMO_AVAILABLE` environment variable allows the Astro build to render a fallback screenshot on the demo page when no artifact is present.
- `concurrency: group: pages` prevents concurrent Pages deploys.

**Validation strategy**:
- Push a change to `kmp/` — verify `build-demo` runs and uploads artifact.
- Push a change to `site/src/` only — verify `build-demo` is skipped but `build-site` + `deploy` run.
- Verify deployment URL is `https://tstapler.github.io/stelekit/`.
- Verify `_astro/` assets are served (`.nojekyll` working).

**INVEST check**: Can be implemented before demo is ready; path filter strategy is testable independently of Gradle.

---

### Task 2.2 — Enable GitHub Pages in repository settings

**Objective**: Configure the `stelekit` GitHub repository to use GitHub Actions as the Pages source.

**Context boundary**: Repository settings (manual step; not automated).

**Prerequisites**: Task 2.1 committed and pushed.

**Implementation approach** (manual, one-time):
1. Navigate to `https://github.com/tstapler/stelekit/settings/pages`.
2. Under "Source", select **GitHub Actions**.
3. Do not configure a custom domain (use `tstapler.github.io/stelekit/` for now).
4. Create the `github-pages` deployment environment if it does not exist (`Settings > Environments > New environment`).

**Validation strategy**: After the first successful `pages.yml` run, `https://tstapler.github.io/stelekit/` responds with `200 OK` and serves the landing page.

**INVEST check**: One-time operation; no code changes.

---

## Story 3: Landing Page Content

**Goal**: A complete, publication-ready landing page at `site/src/pages/index.astro` that addresses all three audiences within two clicks.

### Task 3.1 — Implement landing page sections

**Objective**: Build the full landing page: hero, platform matrix, feature cards, Logseq comparison table, quick start, dual audience links.

**Context boundary**: `site/src/pages/index.astro` and any extracted component files in `site/src/components/landing/`.

**Prerequisites**: Task 1.2 (base path), Task 1.4 (placeholder page exists).

**Implementation approach**:

The landing page is a single `.astro` page. Sections in order:

**Section 1 — Hero**

```
Your knowledge, carved in stone.

A local-first outliner for Desktop and Android.
Reads your existing Logseq markdown. No install required to try.

[Try in Browser]  [Download]
```

- "Try in Browser" links to `/stelekit/demo/`
- "Download" links to the GitHub Releases page

**Section 2 — Platform matrix**

| Platform | Status |
|---|---|
| Desktop (macOS, Linux, Windows) | Working |
| Android | Working |
| Web (Browser Demo) | Demo available |
| iOS | Planned |

**Section 3 — Feature cards** (5 cards with icon + headline + 1-sentence description):
1. Outliner — Hierarchical block editing with keyboard-driven nesting
2. Journals — Daily notes auto-created at app startup
3. Bidirectional links — `[[page]]` links indexed in real time; backlinks panel always visible
4. Search — Full-text search across all pages and blocks
5. Local-first — Your files stay on your machine; plain Markdown, always

**Section 4 — Logseq comparison table**

| | SteleKit | Logseq |
|---|---|---|
| File format | Markdown (Logseq-compatible) | Markdown |
| Browser demo | Yes — no install | Server-required |
| Desktop | Yes | Yes |
| Android | Yes | Yes |
| iOS | Planned | Yes |
| Open source | Yes (Elastic 2.0) | Yes (AGPL) |
| KMP architecture | Yes | No |

**Section 5 — Quick start**

```bash
git clone https://github.com/tstapler/stelekit
./gradlew run
```

**Section 6 — Audience links** (row of three cards)
- "User Guide" → `/stelekit/docs/user/getting-started/`
- "Developer Docs" → `/stelekit/docs/developer/architecture/`
- "Contribute" → `/stelekit/docs/developer/contributing/`

**Voice rules** (from branding-readme.md):
- No adverbs ("seamlessly", "effortlessly", "simply")
- No marketing filler ("powerful", "robust", "cutting-edge")
- Sentences under 15 words
- Active voice

**Validation strategy**:
- Page loads at `http://localhost:3000/stelekit/` in local serve
- All six sections visible on desktop and mobile viewport
- "Try in Browser" and "Download" links resolve correctly
- No broken links in the audience row

**INVEST check**: Content-only; no Wasm or CI dependency; can be written and reviewed as a standalone change.

---

## Story 4: Demo Page

**Goal**: A demo page at `/demo/` that embeds the Compose Wasm canvas, degrades gracefully when the artifact is not available, and guides users on what to try.

**Depends on**: `browser-wasm-demo.md` Phase A complete (wasmJs + `CanvasBasedWindow` + demo graph seeded). Can be scaffolded before this dependency ships.

### Task 4.1 — Scaffold demo page with graceful fallback

**Objective**: Create `site/src/pages/demo.astro` that renders the demo canvas when the artifact is present and shows a screenshot gallery when it is not.

**Context boundary**: `site/src/pages/demo.astro`.

**Prerequisites**: Story 1 (site scaffolding), Task 2.1 (`DEMO_AVAILABLE` env var plumbing).

**Implementation approach**:

```astro
---
// site/src/pages/demo.astro
const demoAvailable = import.meta.env.DEMO_AVAILABLE === 'true';
---
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <title>SteleKit — Try in Browser</title>
    <style>
      body { margin: 0; background: #1a1a2e; color: #e0e0e0; font-family: system-ui, sans-serif; }
      .wrapper { max-width: 900px; margin: 0 auto; padding: 2rem; }
      .demo-frame { width: 100%; height: 80vh; border: none; border-radius: 8px; overflow: hidden; }
      .fallback { padding: 2rem; text-align: center; }
      .what-to-try { background: #16213e; border-radius: 8px; padding: 1.5rem; margin-bottom: 1.5rem; }
    </style>
  </head>
  <body>
    <div class="wrapper">
      <h1>SteleKit in Your Browser</h1>
      <div class="what-to-try">
        <h2>What to try</h2>
        <ul>
          <li>Open the journal entry and edit a block — click to focus, Enter to add below, Tab to indent</li>
          <li>Type <code>[[Getting Started]]</code> to create a wiki link</li>
          <li>Navigate to Getting Started and look for the Backlinks panel</li>
          <li>Press <kbd>Cmd/Ctrl+K</kbd> to open search</li>
        </ul>
        <p>The demo runs entirely in your browser. No data leaves your device.</p>
        <p>Requires Chrome 119+, Firefox 120+, or Safari 18.2+.</p>
      </div>
      {demoAvailable ? (
        <iframe
          class="demo-frame"
          src="/stelekit/demo/index.html"
          title="SteleKit live demo"
          allow="cross-origin-isolated"
        />
      ) : (
        <div class="fallback">
          <p>The interactive demo is coming soon.</p>
          <p>
            <a href="https://github.com/tstapler/stelekit">View on GitHub</a> or
            <a href="/stelekit/docs/user/getting-started/">read the User Guide</a>.
          </p>
          <!-- Screenshot gallery placeholder -->
        </div>
      )}
    </div>
  </body>
</html>
```

The `<iframe>` embeds the demo from `site/public/demo/index.html` (the Gradle output copied by CI). The `allow="cross-origin-isolated"` attribute is needed for the `coi-serviceworker` inside the iframe to access `SharedArrayBuffer`.

**Validation strategy**:
- With `DEMO_AVAILABLE=false`, page renders fallback with no JS errors
- With `DEMO_AVAILABLE=true` and the demo artifact present, iframe loads and Compose canvas appears
- No layout overflow on desktop or mobile viewports

**INVEST check**: Independently deliverable as a fallback page before the wasmJs target ships.

---

### Task 4.2 — Add demo nav link to Astro site header

**Objective**: Add "Try in Browser" as a primary nav item in the Astro site header so it is reachable from every page.

**Context boundary**: `astro.config.mjs` Starlight `customCss` or `navbar` config, or a custom header component.

**Prerequisites**: Task 1.2, Task 4.1.

**Implementation approach**: Add to the Starlight `navbar` config:

```js
// in astro.config.mjs, inside starlight({ ... })
navbar: [
  { label: 'Try in Browser', href: '/stelekit/demo/', attrs: { class: 'cta' } },
  { label: 'Docs', href: '/stelekit/docs/' },
  { label: 'GitHub', href: 'https://github.com/tstapler/stelekit', attrs: { target: '_blank' } },
],
```

**Validation strategy**: "Try in Browser" link visible in site header on landing page and docs pages.

**INVEST check**: Config-only change; isolated.

---

## Story 5: User Documentation

**Goal**: Five user-facing documentation pages covering core workflows: getting started, outliner, journals, backlinks, search.

### Task 5.1 — User guide pages (5 pages)

**Objective**: Write and commit the five user guide pages as Starlight MDX content.

**Context boundary**: `site/src/content/docs/user/` directory (5 files).

**Prerequisites**: Story 1 complete.

**Implementation approach** — create each file with the structure below. Content should describe concepts and workflows (durable) not specific UI element names and positions (fragile).

**`user/getting-started.mdx`**

```mdx
---
title: Getting Started
description: How to open your first graph in SteleKit
---

## Download and install

[Download SteleKit](https://github.com/tstapler/stelekit/releases) for macOS, Linux, or Windows.
Or [try it in your browser](/stelekit/demo/) — no install required.

## Open a graph

A *graph* is a directory of Markdown files. SteleKit reads the same format as Logseq.

1. Launch SteleKit
2. Click **Open graph folder** and select your Logseq directory (or any folder of `.md` files)
3. SteleKit indexes the files — this takes a few seconds on first open

## What you see

- **Left sidebar** — page list and recent pages
- **Main area** — the current page, displayed as an editable block outline
- **Journal tab** — today's daily note, auto-created at startup
```

**`user/outliner.mdx`** — covers block editing, nesting (Tab/Shift-Tab), keyboard shortcuts (Enter, Backspace at empty block, Alt+Up/Down to move blocks), block references.

**`user/journals.mdx`** — covers daily notes, how journals are stored (`journals/YYYY_MM_DD.md`), how to navigate to previous days.

**`user/backlinks.mdx`** — covers `[[wiki link]]` syntax, bidirectional indexing, the backlinks panel, how to navigate the link graph.

**`user/search.mdx`** — covers Cmd/Ctrl+K search, what is indexed (page titles, block text), navigating results.

**Voice rules**: Same as landing page. No screenshots at MVP — reference the browser demo instead.

**Validation strategy**:
- All five pages appear in the Starlight sidebar under "User Guide"
- Each page renders with no MDX parse errors
- Links between pages use relative paths (Starlight resolves them)
- `npm run build` passes

**INVEST check**: Content-only; each page is independently writable; no Wasm or CI dependency.

---

## Story 6: Developer Documentation

**Goal**: Four developer-facing documentation pages covering architecture, build, contributing, and module structure.

### Task 6.1 — Developer docs pages (4 pages)

**Objective**: Write and commit the four developer docs pages, drawing from existing `docs/architecture/` ADRs, `CLAUDE.md` module table, and build commands.

**Context boundary**: `site/src/content/docs/developer/` directory (4 files).

**Prerequisites**: Story 1 complete.

**Implementation approach**:

**`developer/architecture.mdx`** — draws from `CLAUDE.md` architecture section and `docs/architecture/` ADRs. Covers the layered architecture (UI → ViewModel → Repository → DB/Files → Parser), multi-graph support, state management, and key data flows. Reference relevant ADRs by link.

**`developer/build.mdx`** — covers prerequisite tooling (JDK 21, Android SDK, Gradle), build commands from `CLAUDE.md`, and module structure. Exact commands from the `CLAUDE.md` command table.

**`developer/contributing.mdx`** — leads with action: "Good first issues are labeled `good-first-issue` on GitHub. Build with `./gradlew jvmTest`." Then: branch naming convention, test requirements, PR process. Keep under 300 words — detail goes in architecture and build pages.

**`developer/module-structure.mdx`** — the source set table from `CLAUDE.md` expanded with a brief description of what to find in each source set. Reference key files table from `CLAUDE.md`.

**Validation strategy**:
- All four pages appear in Starlight sidebar under "Developer"
- Code blocks render with syntax highlighting
- `npm run build` passes

**INVEST check**: Each page is independently writable from existing source material.

---

## Integration Checkpoints

### Checkpoint A — Site runs locally
After Story 1 complete:
- `npm run dev` in `site/` serves the project at `localhost:4321`
- Starlight sidebar shows User Guide and Developer sections (with placeholder pages)
- No build errors

### Checkpoint B — First deploy to GitHub Pages
After Story 2 complete:
- `https://tstapler.github.io/stelekit/` serves the placeholder landing page
- `_astro/` assets load (`.nojekyll` working)
- CI workflow visible in Actions tab
- Path filters confirmed: docs-only push skips `build-demo`

### Checkpoint C — Landing page and docs published
After Stories 3, 5, 6 complete:
- Full landing page visible with all six sections
- User guide and developer docs navigable
- No broken internal links
- "Try in Browser" link in header navigates to demo page (with fallback)

### Checkpoint D — Demo live end-to-end
After Story 4 + `browser-wasm-demo.md` Phase A complete:
- Demo page embeds the Compose canvas
- `SharedArrayBuffer` available in Chrome (verified in console)
- Canvas renders SteleKit UI with demo graph
- No blank page or JS errors

---

## Context Preparation Guide

**Before implementing Story 1**:
Read: `project_plans/stelekit-documentation-and-branding/decisions/ADR-001-astro-starlight.md`, `ADR-003-github-pages-nojekyll-coi.md`. Understand that `base: '/stelekit/'` must be set before any internal links are written — fixing it retroactively requires updating every href.

**Before implementing Story 2**:
Read: `project_plans/stelekit-documentation-and-branding/decisions/ADR-002-monorepo-two-job-ci.md`. Confirm the `GRADLE_ENCRYPTION_KEY` secret exists in the repo secrets (check `.github/workflows/ci.yml` for the current pattern). Read `docs/tasks/browser-wasm-demo.md` to understand the expected output path (`kmp/build/dist/wasmJs/productionExecutable/`).

**Before implementing Story 3**:
Read: `docs/archive/tasks/branding-readme.md` for the brand voice rules, hero copy, and comparison table. The copy in Task 3.1 is derived from that document; additional polish should stay consistent with it.

**Before implementing Story 4**:
Confirm `docs/tasks/browser-wasm-demo.md` Phase A is complete. The `build-demo` Gradle task must produce output at `kmp/build/dist/wasmJs/productionExecutable/`. Check that the demo artifact includes `coi-serviceworker.min.js` and `index.html` (committed in the browser-wasm-demo task).

**Before implementing Stories 5–6**:
Read `CLAUDE.md` for the module structure table and command reference. Read the existing ADRs in `docs/architecture/` for developer doc source material.

---

## Known Issues

### Potential Bug: Base path omitted in a new `.astro` page link

**Description**: Every internal `<a href>` and Astro `<a href={}>` must include the `/stelekit/` base. Starlight's `href` props in `astro.config.mjs` sidebar items also need the full path. If a developer adds a page and forgets the base prefix, the link works in local dev (served at `/`) but 404s on GitHub Pages (served at `/stelekit/`).

**Mitigation**: Enable Astro's `trailingSlash: 'ignore'` and configure a custom 404 page that redirects to the base. Add a CI assertion that verifies the built HTML contains no `href="/"` patterns that should be `href="/stelekit/"`. Starlight's link verification plugin (if available) can catch broken internal links at build time.

**Files likely affected**: Any new `.astro` or `.mdx` page with hardcoded `href` values.

**Prevention strategy**: In code review, search all `.astro` and `.mdx` files for `href="/"` patterns that are missing the `/stelekit/` prefix. Use Astro's `getRelativeLocaleUrl` or `import.meta.env.BASE_URL` helpers to construct links programmatically rather than hardcoding the base path string.

---

### Potential Bug: `build-site` fails when no `demo-dist` artifact exists on first deploy

**Description**: On the very first `pages.yml` run (before any successful `build-demo` has uploaded a `demo-dist` artifact), the `actions/download-artifact` step in `build-site` has nothing to download. Without `continue-on-error: true`, the entire pipeline fails before the site is ever deployed.

**Mitigation**: The `pages.yml` in Task 2.1 includes `continue-on-error: true` on the download step. The demo page uses `DEMO_AVAILABLE=false` fallback. This is explicitly handled. Verify the flag propagation works with the first push by checking the Actions run log.

**Files likely affected**: `.github/workflows/pages.yml`.

**Prevention strategy**: Test the pipeline with a `workflow_dispatch` trigger before enabling the push trigger. Verify the fallback rendering path in `demo.astro` before the `browser-wasm-demo.md` dependency ships.

---

### Potential Bug: `coi-serviceworker` one-time reload confuses users

**Description**: On the first visit to the demo page, the service worker registers and the browser reloads the page once. Users who see the page flicker may think the demo is broken and navigate away before the Compose canvas loads.

**Mitigation**: The loading screen (defined in `browser-wasm-demo.md` Task 3.2) includes an explanatory comment. Additionally, the `demo.astro` page should display: "This page may refresh once on first load — this is expected." in a small note above the iframe. The reload is fast (< 1 second) but visible.

**Files likely affected**: `site/src/pages/demo.astro`, `kmp/src/wasmJsMain/resources/index.html`.

**Prevention strategy**: Test the first-visit reload behavior on an incognito window. Verify the loading screen appears before the reload (it should — the HTML loads immediately; the service worker registers and triggers reload within ~100ms).

---

### Potential Bug: Wasm demo iframe breaks site layout on mobile

**Description**: The demo iframe is sized with `height: 80vh`. On mobile viewports, the Compose canvas inside the iframe renders the desktop layout of SteleKit (which is not responsive). The iframe will scroll horizontally, breaking the site's mobile layout.

**Mitigation**: Add a mobile notice above the iframe: "The interactive demo is optimized for desktop screens. For the best experience, open on a laptop or desktop." Hide the iframe on viewports under 768px and show the notice only. Alternatively, size the iframe to a fixed desktop resolution with `width: 1024px; overflow: auto; transform: scale(0.8)` for medium mobile screens.

**Files likely affected**: `site/src/pages/demo.astro`.

**Prevention strategy**: Test the demo page on a 375px viewport in Chrome DevTools before publishing. If the Compose canvas fills the viewport incorrectly, apply the mobile-hide strategy.

---

### Potential Bug: Jekyll strips `_astro/` directories despite `.nojekyll`

**Description**: If the `.nojekyll` file is not at the root of the deployed artifact, Jekyll still runs and strips asset directories. This can happen if `actions/upload-pages-artifact` is given the wrong path, or if `.nojekyll` is added to `site/src/` instead of `site/public/`.

**Mitigation**: The `.nojekyll` file is committed to `site/public/`. Astro copies all `public/` files to `site/dist/`. The `upload-pages-artifact` action is given `path: site/dist/`. This chain ensures `.nojekyll` is always at the artifact root. Verify with `ls site/dist/.nojekyll` in the CI log.

**Files likely affected**: `site/public/.nojekyll`, `.github/workflows/pages.yml`.

**Prevention strategy**: After the first deploy, open Chrome DevTools Network tab, navigate to `https://tstapler.github.io/stelekit/`, and verify all `_astro/*.js` files return 200 (not 404).

---

### Potential Bug: Gradle `build-demo` skip logic misses some app-affecting file changes

**Description**: The `build-demo` path filter checks for changes in `kmp/`. However, changes to `gradle/`, `settings.gradle.kts`, or `gradle.properties` (e.g., toggling `enableJs=true`) also affect the Wasm build but are not under `kmp/`. If a developer changes `gradle.properties` to re-enable the JS target and the `build-demo` job is skipped, the deployed demo may be stale or broken.

**Mitigation**: Expand the path filter to include:
```yaml
if: |
  github.event_name == 'workflow_dispatch' ||
  contains(toJSON(github.event.head_commit.modified), 'kmp/') ||
  contains(toJSON(github.event.head_commit.added), 'kmp/') ||
  contains(toJSON(github.event.head_commit.modified), 'gradle') ||
  contains(toJSON(github.event.head_commit.modified), 'settings.gradle')
```

Or use a `paths` trigger on the workflow instead of the job-level `if` condition (simpler):
```yaml
on:
  push:
    branches: [main]
    paths-ignore:
      - 'site/**'
      - 'docs/**'
      - '*.md'
```

**Files likely affected**: `.github/workflows/pages.yml`.

**Prevention strategy**: Use `paths-ignore` on the workflow trigger rather than `if` conditionals on jobs. `paths-ignore` is evaluated by GitHub before any jobs run; job-level `if` conditionals require correct JSON path evaluation which is error-prone.
