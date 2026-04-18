# Requirements: SteleKit Documentation and Branding

**Status**: Draft | **Phase**: 1 — Ideation complete  
**Created**: 2026-04-18

## Problem Statement

SteleKit has no public-facing presence. There is no GitHub Pages site, no live demo, and no documentation aimed at non-contributor audiences. Developers who discover the repo see a bare directory listing. Potential users cannot try the app without cloning and building it. The project competes with Logseq for mindshare but is invisible on the web.

Three distinct audiences are underserved:
- **Potential users** — people looking for a Logseq alternative who want to try before installing
- **KMP developers** — engineers interested in the architecture as a reference implementation
- **Open source contributors** — developers who might want to contribute

## Success Criteria

- A public GitHub Pages site is live at the repo's GitHub Pages URL
- A live browser demo runs SteleKit via the existing Compose/Wasm JS target — no install required
- User documentation covers core workflows: opening a graph, editing, journals, backlinks, search
- Developer documentation covers: architecture overview, build instructions, contribution guide, module structure
- All three audiences can find relevant content within two clicks of the landing page
- Site and demo are maintained by CI (GitHub Actions deploys on push to main)

## Scope

### Must Have (MoSCoW)

- **GitHub Pages marketing/landing site** — sells the project to all three audiences
- **Browser WASM demo** — SteleKit running live via the existing JS/Wasm target (`enableJs=true` is already set in `gradle.properties`)
- **User documentation** — how-to guides covering the core outliner UX, journals, bidirectional links, search
- **Developer documentation** — architecture, build setup, contribution guide
- **Site CI/CD** — GitHub Actions workflow to build and deploy the site automatically

### Out of Scope

- Plugin system documentation (not ready)
- iOS target documentation (iOS disabled)
- Localized documentation (English only for initial launch)
- Blog / changelog system
- Custom domain (use default github.io URL initially)
- Redesign of the in-app UI

## Constraints

- **Tech stack (app)**: Kotlin Multiplatform — the browser demo must use the existing Compose/Wasm JS target
- **Tech stack (site)**: Any web tech is acceptable for the GitHub Pages site (Astro, Hugo, Docusaurus, VitePress, plain HTML are all options)
- **Timeline**: No hard deadline; MVP first, then iterate
- **Dependencies**: WASM demo depends on `enableJs=true` Gradle build producing a working Wasm artifact — must be verified before building demo page
- **Existing branding work**: Prior tasks in `docs/archive/tasks/branding-*.md` cover app identity strings, color tokens, logo assets, and README rewrite — the site should consume and extend this work, not redo it

## Context

### Existing Work

- `gradle.properties` already has `enableJs=true` — the JS/Wasm target is enabled
- `docs/archive/tasks/branding-readme.md` — detailed spec for root README rewrite (hero copy: "Your knowledge, carved in stone.", brand voice rules, banned words list)
- `docs/archive/tasks/branding-app-name.md` — identity string rename plan (Logseq → Stelekit in UI strings)
- `docs/archive/tasks/branding-color-tokens.md`, `branding-logo-assets.md`, `branding-license.md` — parallel branding initiatives
- `docs/architecture/` — existing architectural decision records and feature maps usable as source material for developer docs
- No root `README.md` exists yet; no GitHub Pages config exists yet

### Stakeholders

- **Tyler Stapler** — sole maintainer and primary developer
- **Potential contributors** — KMP/Compose developers who discover the project
- **Potential users** — people evaluating Logseq alternatives

## Research Dimensions Needed

- [ ] Stack — evaluate static site generators and WASM demo hosting approaches (Astro vs. Docusaurus vs. VitePress vs. plain Compose HTML canvas)
- [ ] Features — survey comparable KMP/open-source project sites for documentation patterns, demo embedding, and feature showcasing
- [ ] Architecture — design how the GitHub Pages site, WASM demo, and documentation fit together (monorepo vs. separate site repo, build pipeline)
- [ ] Pitfalls — known failure modes for Compose/Wasm in browsers, GitHub Pages size limits, demo performance issues, CORS, caching
