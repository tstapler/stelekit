# ADR-001: Astro + Starlight as the Site Framework

**Status**: Accepted
**Date**: 2026-04-18
**Deciders**: Tyler Stapler

---

## Context

SteleKit needs a public GitHub Pages site serving three audiences: potential users evaluating a Logseq alternative, KMP developers interested in the architecture, and open-source contributors. The site requires a visually differentiated marketing landing page, structured user documentation (5–6 pages), and developer documentation (3–4 pages), all maintained by a single solo developer.

Candidates evaluated: Astro + Starlight, Docusaurus 3 (React), VitePress (Vue), MkDocs Material (Python), and plain HTML.

## Decision

Use **Astro** as the site generator with the **Starlight** documentation theme.

The `site/` directory in the monorepo contains the Astro project. The landing page is an `.astro` component; documentation lives in Starlight's `src/content/docs/` with a file-based sidebar. The demo page at `/demo/` is an `.astro` page that loads the Wasm canvas inside a containing `<div>`.

## Rationale

**Marketing + docs in one tool.** The primary tension is that SteleKit needs both a custom-designed landing page and structured documentation. VitePress is docs-first and requires Vue component knowledge to build a differentiated landing page. Docusaurus supports a custom landing page but forces a React runtime on every page, adding a second major framework to a pure-Kotlin project. Astro handles both naturally: `.astro` pages for the marketing layer, Starlight content collections for the docs layer.

**Zero JS by default on marketing pages.** Astro ships no framework JS unless you explicitly opt in. The landing page renders as pure HTML/CSS, loading fast before any Wasm download begins. Docusaurus unconditionally ships a React hydration bundle.

**Demo embedding is trivial.** The `wasmJs` build output is static files (`.js`, `.wasm`, `index.html`). Astro's `public/` directory passes files through without transformation. The demo page is an `.astro` file that frames the Compose canvas. No adapter, no special MIME type configuration.

**Ecosystem neutrality.** Introducing React (Docusaurus) or Vue (VitePress) as the primary site framework creates a second "real" framework dependency in an otherwise pure-Kotlin project. Astro's `.astro` template syntax is self-contained and does not require React or Vue knowledge for ongoing maintenance.

**First-class GitHub Pages CI.** Astro's `withastro/action` provides a documented, tested deploy workflow. The `base` config for subpath hosting (`/stelekit/`) is a single field in `astro.config.mjs`.

**Pagefind search without a server.** Starlight integrates Pagefind for full-text search of the docs, which works on static GitHub Pages with no Algolia account or search backend.

**Fallback path exists.** Starlight documentation is written in Markdown with a well-defined frontmatter schema. If Astro/Starlight proves too heavyweight, the content files are portable to VitePress or MkDocs with a config rewrite. No content lock-in.

## Rejected Alternatives

**Docusaurus 3**: React runtime on every page; `swizzling` pattern for customisation creates significant maintenance surface; versioned docs are not needed at MVP; overkill for a solo maintainer with a small doc set.

**VitePress**: Excellent for pure documentation but the custom landing page requires Vue component knowledge; the ecosystem leans Vue in a way that feels mismatched to a Kotlin project.

**MkDocs Material**: Python toolchain in CI adds a second runtime alongside JDK and Node. MkDocs has no first-class landing page concept. The `mkdocs gh-deploy` model is less flexible than `actions/deploy-pages`.

**Plain HTML**: No templating means duplicated nav/footer across all pages. No Markdown authoring. No search. Not maintainable at documentation scale.

## Consequences

- Solo maintainer must learn `.astro` template syntax and Starlight content collections. One-time cost; the template format is simpler than React or Vue.
- Node.js is added to the CI environment alongside JDK. The `build-site` job runs separately from the `build-demo` Gradle job, so memory constraints are not an issue.
- All internal Astro links and asset paths use `base: '/stelekit/'` to match the GitHub Pages subpath. Forgetting this on a new page causes 404s in production.
- Demo page scripts must be isolated in an `<iframe>` or scoped container to prevent the Compose canvas's global CSS from leaking into the site layout.
