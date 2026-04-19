# Research Plan: SteleKit Documentation and Branding

**Input**: `project_plans/stelekit-documentation-and-branding/requirements.md`  
**Date**: 2026-04-18

## Subtopics

### 1. Stack
**File**: `findings-stack.md`  
**Question**: What static site generator and hosting approach best fits a KMP project with a Compose/Wasm demo?  
**Search cap**: 4 searches  
**Axes**: DX/setup cost, KMP/Wasm compatibility, documentation features, GitHub Pages integration, bundle handling  
**Candidates**: Astro, Docusaurus, VitePress, MkDocs, plain Compose HTML canvas embed

### 2. Features
**File**: `findings-features.md`  
**Question**: What do strong KMP/open-source project sites do well? What should the SteleKit site copy?  
**Search cap**: 4 searches  
**Axes**: Audience coverage (user/dev/contributor), demo embedding, docs structure, visual design, onboarding clarity  
**Candidates**: Kotlin Multiplatform docs site, Compose Multiplatform site, Logseq site, Obsidian site, comparable OSS outliner sites

### 3. Architecture
**File**: `findings-architecture.md`  
**Question**: How should the GitHub Pages site, Wasm demo, and documentation fit together?  
**Search cap**: 4 searches  
**Axes**: Monorepo vs separate repo, CI pipeline complexity, Wasm artifact serving, incremental build cost, deploy latency  
**Candidates**: Wasm served from same repo, Wasm as GitHub Releases artifact, separate docs repo, Gradle + site build in single CI job

### 4. Pitfalls
**File**: `findings-pitfalls.md`  
**Question**: What are the known failure modes for Compose/Wasm in browsers and GitHub Pages hosting?  
**Search cap**: 5 searches  
**Axes**: Browser compatibility, bundle size, CORS, GitHub Pages limits, Wasm load time, skiko rendering issues  
**Candidates**: Known Compose Multiplatform Wasm issues, GitHub Pages 1GB limit, Wasm MIME type configs, Safari Wasm support

## Synthesis target
`research/synthesis.md` — ADR-ready summary for architectural decisions in plan.md
