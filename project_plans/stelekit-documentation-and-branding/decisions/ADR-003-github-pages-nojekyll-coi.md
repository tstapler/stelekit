# ADR-003: GitHub Pages over Netlify/Vercel (and the `.nojekyll` + coi-serviceworker Implications)

**Status**: Accepted
**Date**: 2026-04-18
**Deciders**: Tyler Stapler

---

## Context

The site must be publicly hosted and auto-deployed from the `stelekit` GitHub repository. The demo page requires `SharedArrayBuffer` for Kotlin/Wasm's threading model, which browsers enforce through the `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp` HTTP response headers (together called COOP/COEP). GitHub Pages cannot set custom HTTP response headers. Candidates evaluated: GitHub Pages, Netlify, Vercel.

Two sub-problems must be solved regardless of host: (1) Jekyll preprocessing strips `_astro/` asset directories unless disabled; (2) `SharedArrayBuffer` is blocked without COOP/COEP headers.

## Decision

Host on **GitHub Pages** using the `actions/deploy-pages` workflow model. Mitigate the two sub-problems as follows:

**Sub-problem 1 — Jekyll stripping `_astro/` directories**: Commit a `.nojekyll` file at `site/public/.nojekyll`. Because Astro copies the `public/` directory verbatim to `site/dist/`, and `actions/upload-pages-artifact` uploads `site/dist/`, the `.nojekyll` file is always present at the root of the deployed Pages artifact. This disables Jekyll preprocessing. The file is committed to the repo and never generated in CI; it cannot be forgotten.

**Sub-problem 2 — COOP/COEP headers for `SharedArrayBuffer`**: Include `coi-serviceworker.min.js` in the demo HTML (`kmp/src/wasmJsMain/resources/`) as planned in `docs/tasks/browser-wasm-demo.md`. The service worker intercepts fetch events and injects COOP/COEP headers into responses in the browser's virtual layer, enabling `SharedArrayBuffer` without requiring server-side header configuration. The service worker causes a single page reload on first visit — expected behavior that is documented in a comment in the loading screen HTML.

The GitHub Pages URL is `https://tstapler.github.io/stelekit/`. The Astro `base` config is `'/stelekit/'`.

## Rationale

**Zero operational cost.** GitHub Pages is free for public repositories and is already integrated with the `stelekit` repository. No account setup, DNS configuration, or billing is required. Netlify and Vercel both offer free tiers, but they require account creation and webhook configuration — overhead not justified for a solo project that already has a GitHub account.

**Custom HTTP headers are not actually needed.** The COOP/COEP requirement can be fully satisfied by `coi-serviceworker` in the browser. This is the documented, widely-used approach for Kotlin/Wasm demos on GitHub Pages (confirmed across multiple community KMP Wasm deploy guides). The service worker approach is more portable than server-side headers: if the hosting platform changes, the service worker travels with the HTML file.

**`actions/deploy-pages` is first-class Pages integration.** The GitHub-maintained action handles the Pages staging area, branch deployment, and environment URL output in a single step. No separate `JamesIves/github-pages-deploy-action` or `gh-pages` branch is needed. It requires only `permissions: pages: write` and `id-token: write` in the workflow.

**Netlify rejected.** Netlify's `_headers` file could set COOP/COEP natively, which is cleaner than the service worker shim. However, this benefit is outweighed by: a third-party service dependency, a second deployment pipeline to maintain alongside GitHub Actions, and an additional account to manage. The service worker shim works reliably on GitHub Pages.

**Vercel rejected.** Same rationale as Netlify. Vercel's edge functions could set response headers, but the operational complexity is unwarranted for a solo project.

## Consequences

**`.nojekyll` must be a committed static file, not generated in CI.** The only safe location is `site/public/.nojekyll`. If it were generated in a CI step (e.g., `touch site/public/.nojekyll` in the workflow), it could be omitted when a developer builds locally and copies the artifact manually, silently breaking the site. Committing it to the repository makes it impossible to forget.

**`coi-serviceworker` causes a one-time reload.** On first visit to the demo page, the browser installs the service worker and reloads the page. The loading screen HTML should include a comment: "This page may refresh once on first load — this is expected." Subsequent visits do not reload.

**All Astro asset paths must use `base: '/stelekit/'`.** The GitHub Pages URL is a subpath, not a root domain. Without `base: '/stelekit/'` in `astro.config.mjs`, all `<link>`, `<script>`, and `<img>` references resolve against the root and 404. This must be verified in the first deploy. Local preview must serve from a subpath: `npx serve -p 3000 site/dist && open http://localhost:3000/stelekit/`.

**The demo iframe must also handle the subpath.** The `<iframe src="/stelekit/demo/index.html">` reference in the demo page uses an absolute path with the base prefix. If the base path changes, both `astro.config.mjs` and the iframe `src` must be updated.

**GitHub Pages file size limits apply.** The 100 MB per-file hard limit is unlikely to be hit by any individual wasmJs output file, but the total site should be monitored. Built artifacts must never be committed to git history — `actions/upload-pages-artifact` handles this correctly.
