# Findings: Features — Project Site Patterns and Information Architecture

**Research question**: What do strong KMP/OSS project sites do well? What patterns, information architecture, and UX approaches should SteleKit copy?

**Date**: 2026-04-18  
**Status**: Training-knowledge draft — pending web verification

---

## Summary

Strong OSS project sites for developer tools share a consistent structure: a marketing landing page with a live demo or screenshot, split documentation (user vs. developer), and a clear contributor funnel. KMP-specific sites (Kotlin Multiplatform, Compose Multiplatform) add a platform-compatibility matrix as a top-level concern. Note-taking/PKM tools (Logseq, Obsidian) lead with workflow screenshots and "try it" CTAs rather than technical architecture. SteleKit must serve three audiences (users, KMP devs, contributors) and should mirror the dual-audience structure of Compose Multiplatform while matching the emotional register of Logseq and Obsidian for the user-facing layer.

The most actionable pattern: the landing page is a single-page marketing document; documentation lives in a separate `/docs` or `/guide` section; a live demo is a distinct high-profile entry point, not buried in docs.

---

## Options Surveyed

### 1. kotlinlang.org — Kotlin Multiplatform section

**URL**: https://kotlinlang.org/docs/multiplatform.html [TRAINING_ONLY — verify current URL structure]

**Audience split**: Primary KMP developer. No user-facing content — purely a developer reference.

**Landing page structure**:
- Hero: short value prop ("Share code between platforms") + CTA to "Get started"
- Feature cards: code sharing, native UI, gradual adoption
- Platform matrix: which targets compile to what
- Ecosystem links: tooling, samples, community

**Documentation structure**:
- Top-level: Kotlin overview → Multiplatform section (nested under main Kotlin docs)
- Tutorial-first approach: "Create your first app" before reference material
- Separate "What's new" page per release

**Demo / try-it**: No embedded browser demo; links to starter project templates on GitHub and the Kotlin Playground for language examples. KMP demos require a local build. [TRAINING_ONLY]

**Strengths for SteleKit to copy**:
- Platform matrix showing Android/Desktop/iOS/Web status at a glance
- "Get started" CTA that leads directly to a clone-and-run tutorial
- "Samples" gallery page with real projects

**Weaknesses / what to avoid**:
- Documentation is nested 3–4 levels deep before reaching actionable content
- No single-page "What is KMP and should I use it?" — assumes motivation is already present
- No live demo path for non-developers

---

### 2. Compose Multiplatform (JetBrains)

**URL**: https://www.jetbrains.com/lp/compose-multiplatform/ [TRAINING_ONLY — verify]

**Audience split**: KMP developers building UI. Secondary: evaluators comparing to Flutter/React Native.

**Landing page structure**:
- Animated hero showing the same UI running on Android, Desktop, iOS, and Web simultaneously
- Tabbed code samples: "write once, run everywhere" demonstrated with < 10 lines of Compose code
- Performance claim with benchmark link
- "Try it in the browser" link (Compose Multiplatform Web Playground) [TRAINING_ONLY — verify this exists]
- Ecosystem section: libraries, tooling, GitHub link
- Footer: JetBrains brand trust signal

**Documentation structure**:
- Hosted on kotlinlang.org under `/docs/compose-multiplatform-create-first-app.html`
- Separate "Get started" for each target platform (Desktop, Android, iOS, Web)
- No user-facing docs (not applicable — it is a framework, not an end-user app)

**Demo / try-it**: The Compose for Web Playground lets you run Compose UI code in-browser. This is the closest reference for SteleKit's WASM demo approach. [TRAINING_ONLY — verify playground URL]

**Strengths for SteleKit to copy**:
- Side-by-side platform screenshot in the hero section — visually communicates "one codebase, everywhere"
- Tabbed code snippets on landing page show the technical approach concisely
- Browser playground as a distinct URL with prominent nav placement
- Trust signal: "built by JetBrains" — SteleKit equivalent: "reads your existing Logseq markdown"

**Weaknesses / what to avoid**:
- No user-facing docs needed for a framework; SteleKit needs them
- JetBrains has design resources SteleKit does not — do not over-invest in animation

---

### 3. Logseq.com (direct competitor)

**URL**: https://logseq.com [TRAINING_ONLY — verify current design]

**Audience split**: End users (note-takers, researchers, PKM enthusiasts). No developer or contributor docs prominently surfaced.

**Landing page structure** (as of training data):
- Hero: product name + tagline + single CTA ("Download" or "Try it now")
- Feature sections with screenshots: outliner, graph view, journals, bidirectional links
- Testimonials / community size metrics
- Privacy / local-first claim
- Platform badges (macOS, Windows, Linux, iOS, Android)
- Footer: documentation link, GitHub, Discord

**Documentation structure**:
- Separate docs site (docs.logseq.com) [TRAINING_ONLY — verify]
- User guide covers: getting started, core features (blocks, pages, journals, queries), advanced topics
- No prominent developer/architecture docs on the main site
- Community-maintained documentation has quality variance

**Demo / try-it**: Logseq historically had a web demo or "try it in browser" option. Availability varies; it requires their server infrastructure. [TRAINING_ONLY — verify current state]

**Strengths for SteleKit to copy**:
- Feature-by-feature screenshot sections speak directly to user workflows
- "Privacy-first / local-first" framing is prominent and trustworthy
- Platform availability badges in the hero area
- Tight connection between landing page CTAs and download/try path

**Weaknesses / what to avoid**:
- Contributor funnel is nearly invisible — GitHub link is buried in footer
- Developer documentation is absent from the main site
- "Why not Logseq?" is never answered (they are Logseq); SteleKit must answer this directly

---

### 4. Obsidian.md (adjacent tool)

**URL**: https://obsidian.md [TRAINING_ONLY — verify current design]

**Audience split**: End users first; a developer/plugin community exists but is secondary on the main site.

**Landing page structure**:
- Hero: short headline + subhead (similar terse voice to SteleKit's brand strategy)
- Screenshot / short demo video in the hero
- Feature cards with icons: local files, links, graph view, plugins
- "Free for personal use" pricing signal early
- Trust: user count or download count
- Footer: documentation, forum, Discord, GitHub

**Documentation structure**:
- help.obsidian.md — user guide [TRAINING_ONLY — verify]
- docs.obsidian.md — developer/plugin API docs [TRAINING_ONLY — verify]
- Clean split between user help and developer reference

**Demo / try-it**: Obsidian does not offer a browser demo (requires native install). This is a meaningful gap that SteleKit can exploit.

**Strengths for SteleKit to copy**:
- Terse, concrete headline voice — matches SteleKit's brand strategy exactly
- User help / developer docs split into separate sections
- "Your files, your format" trust signal — SteleKit equivalent: "reads your existing Logseq markdown"
- Short hero description (1–2 sentences) before features

**Weaknesses / what to avoid**:
- No browser demo is a barrier for early evaluation — SteleKit's WASM demo is a genuine differentiator
- Plugin docs are buried — contributor funnel not prominent

---

### 5. Foam (OSS note-taking / PKM, VS Code extension)

**URL**: https://foambubble.github.io/foam/ [TRAINING_ONLY — verify]

**Audience split**: Developers who already use VS Code; contributors.

**Landing page structure**:
- Minimal GitHub Pages site — essentially a README rendered as a page
- Feature list, getting started, community links
- No screenshots or live demo

**Lessons**: A bare GitHub Pages README render is insufficient for a project seeking user adoption. SteleKit must go beyond this pattern.

---

### 6. Dendron (OSS PKM, VS Code)

**URL**: https://wiki.dendron.so [TRAINING_ONLY — verify; site may have shut down post-2023]

**Audience split**: Power users, developers.

**Landing page structure**:
- Feature-rich marketing page with animated demos
- Prominent "Try in browser" button (VS Code for Web)
- Documentation integrated into the same site as a wiki
- Contributor guide linked from main nav

**Demo / try-it**: Used VS Code for Web to host a live demo. This is the closest analog to SteleKit's WASM demo — a distinct page where users can run the app in a browser without installing anything.

**Strengths for SteleKit to copy**:
- "Try in browser" as a primary nav item (not buried)
- Demo page has a short explanation of what to do first — users unfamiliar with the tool need a guided starting point
- Contributor docs at the same level as user docs in nav

**Weaknesses / what to avoid**:
- Dendron's documentation density was very high — overwhelming for new users
- The VS Code for Web approach required VS Code familiarity; SteleKit's WASM demo runs standalone

---

### 7. Reference: React Native docs

**URL**: https://reactnative.dev [TRAINING_ONLY — verify]

**Audience split**: Developer reference; no end-user content.

**Landing page structure**:
- Single-page marketing site separate from docs
- Feature cards: native components, platform-specific code, fast refresh
- Three-step quick start visible on landing page
- Link to "Try it on the web" (Snack playground)

**Strengths for SteleKit to copy**:
- "Try it on the web" as a prominent CTA on the landing page, implemented as a separate playground URL
- Three-step quick start on the landing page — not hidden in docs
- Clear separation between landing page (marketing) and docs (reference)

---

## Trade-off Matrix

| Site | User docs quality | Dev docs quality | Live demo | Contributor funnel | Visual quality | Audience split clarity |
|---|---|---|---|---|---|---|
| Kotlin Multiplatform | None (framework) | Excellent | No | Good | Good | N/A |
| Compose Multiplatform | None (framework) | Excellent | Browser playground | Moderate | Excellent | N/A |
| Logseq | Good | Poor | Partial | Poor | Good | User-only |
| Obsidian | Good | Separate site | None | Poor | Excellent | Weak split |
| Foam | None | Minimal | None | Minimal | Poor | None |
| Dendron | Good | Good | Browser (VS Code) | Moderate | Good | Moderate |
| React Native | None (framework) | Excellent | Browser playground | Good | Excellent | Dev-only |

**Gap SteleKit can fill**: No direct competitor (Logseq, Obsidian) offers a self-contained browser WASM demo. Logseq has a web build requiring their server. Obsidian is native-only. SteleKit's WASM demo is a genuine differentiator and should be the most prominent CTA on the landing page.

---

## Risk and Failure Modes

### R1: Demo-first strategy backfires if WASM demo is slow or broken

Compose/Wasm bundle sizes are large (potentially 20–50 MB). [TRAINING_ONLY — verify current bundle size] If the demo takes more than 10 seconds to load, users will abandon. A broken or laggy demo creates a worse first impression than no demo at all.

**Mitigation**: Add a loading indicator with progress bar; host assets on GitHub Pages CDN with correct cache headers; gate the "Try Demo" CTA with a "loads ~X MB" disclaimer. If the demo cannot be made acceptably fast, replace the primary CTA with "Download" and demote the demo to a secondary link.

### R2: Three-audience site becomes nobody's site

Trying to serve users, KMP developers, and contributors on one landing page risks diluting the message. The hero must pick one primary CTA. Secondary audiences must find their content within one click but cannot dominate the hero section.

**Mitigation**: User audience (potential users / Logseq alternatives seekers) is the primary hero audience. KMP devs and contributors get nav-level links ("Architecture", "Contribute") that are visible but not in the hero CTA stack.

### R3: Documentation goes stale immediately

User docs covering specific workflows (journals, backlinks, search) will break as the UI evolves. A solo maintainer cannot keep docs current across three audiences.

**Mitigation**: User docs should describe concepts and workflows (durable) rather than specific UI element names and positions (fragile). Screenshot-free or screenshot-light documentation ages better. Link to the WASM demo as the "see it in action" experience rather than maintaining static screenshots.

### R4: Contributor funnel asks too much too early

Deep architecture docs before a "good first issue" entry point loses potential contributors.

**Mitigation**: Contributor section leads with a "good first issues" GitHub label link and a one-paragraph project state summary. Architecture docs are a second click, not gating.

### R5: Site tech stack becomes a maintenance burden

Complex site generators (Gatsby, Nuxt) require ongoing dependency maintenance. For a solo maintainer, this is a significant risk.

**Mitigation**: Prefer low-ceremony site generators (Astro static, VitePress, or plain HTML for the landing page). Site tech should be simpler than the app tech.

---

## Migration and Adoption Cost

**From no site to a working site**: The cost is build time, not migration. No existing content must be migrated; all content is net-new.

**Documentation content reuse**: Useful source material exists:
- `docs/architecture/` — ADRs and feature maps usable as developer doc source
- `branding-readme.md` — brand voice, positioning, comparison table — all reusable on the site
- `CLAUDE.md` — module structure table reusable as architecture overview

**Demo integration**: The WASM artifact is a build output. Integrating it requires:
1. Verifying the Wasm build produces a functional artifact (`enableJs=true` is already set)
2. Embedding the artifact in an HTML page (JetBrains provides a Compose/Wasm embedding template) [TRAINING_ONLY — verify template URL]
3. Adding the demo page to the GitHub Pages deploy

**Cost estimate** (rough, solo maintainer):
- Landing page: 1–2 days
- User docs (core workflows): 2–3 days
- Developer docs: 1–2 days (mostly reorganizing existing material)
- Demo integration: 1–2 days (assuming Wasm build works cleanly)
- CI/CD setup: 0.5 days
- Total: 6–10 days of focused work

---

## Operational Concerns

### GitHub Pages size limit

GitHub Pages has a soft limit of 1 GB repository size and recommends site sizes under 1 GB; per-deploy size limits are not strictly documented. [TRAINING_ONLY — verify current limits] The WASM bundle may count against this. Mitigation: host the WASM assets via GitHub Releases as a CDN target rather than committing them to the Pages repo.

### CI/CD build cost

Building the Wasm target on every commit is expensive in CI minutes. Mitigation: cache Gradle dependencies and only rebuild the Wasm artifact on main-branch pushes, not on PRs.

### Demo data / initial state

A blank WASM demo is disorienting — users open it and do not know what to do. Mitigation: ship the demo with a pre-loaded sample graph (a small set of markdown files bundled as demo content) and display a "Start here" journal entry that explains what to try.

---

## Prior Art and Lessons Learned

### Pattern: "Try it now" as a first-class nav item

Every project with a browser demo (Compose Web Playground, Dendron, React Native Snack) places the demo link in the primary navigation or as a hero CTA button — not in the documentation sidebar. The demo is marketing, not documentation.

**Apply to SteleKit**: "Try in Browser" is a primary nav link and a hero CTA button. It is not buried in a "Getting Started" doc page.

### Pattern: Platform matrix as trust signal

Compose Multiplatform and Kotlin Multiplatform both surface a platform-availability matrix early (Android: stable, iOS: stable, Desktop: stable, Web: alpha). This sets expectations and signals ambition.

**Apply to SteleKit**: Include a platform status table in the landing page. Be honest about current state (Desktop: working, Android: working, iOS: disabled, Web/Wasm: demo available).

### Pattern: User docs and developer docs in separate sections, not separate sites

Obsidian uses separate sites; Dendron used a unified wiki. For a small project, a single docs section with a clear top-level split (`/user-guide/` vs `/developer/`) is lower maintenance than multiple sites while still providing the same navigation clarity.

**Apply to SteleKit**: Single docs section with two clear sub-sections in the sidebar. No separate GitHub Pages repos for user vs. dev docs.

### Pattern: Logseq comparison table reduces bounce from users already using Logseq

The README spec already calls for a Logseq vs. SteleKit comparison table. This pattern is used by competing tools to intercept users who arrive from a specific alternative. It reduces bounce by immediately answering "why this instead of what I already use."

**Apply to SteleKit**: The comparison table belongs on the landing page as well as in the README.

### Pattern: Hero section answers "what is this, who is it for, why should I care" in 3 sentences

Logseq, Obsidian, and Foam all front-load their value proposition. None bury the headline inside an "About" section.

**Apply to SteleKit**: The landing page hero has: headline ("Your knowledge, carved in stone."), one-line description ("A local-first outliner for Desktop and Android. Reads your Logseq markdown. No install required to try."), two CTAs ("Try in Browser" and "Download").

### Pattern: Contributor section is concise and action-oriented

React Native and Compose Multiplatform contributor guides start with a "here is the fastest path to your first contribution" paragraph, not a manifesto. The architecture deep-dive is linked, not inline.

**Apply to SteleKit**: Contributing page opens with: "Good first issues are labeled `good-first-issue` on GitHub. Build the project with `./gradlew jvmTest`."

---

## Open Questions

1. **What is the actual Compose/Wasm bundle size for SteleKit?** This determines whether the demo strategy is viable and how the loading UX must be designed. Must be measured against the actual build output.

2. **Does the Wasm build produce a working interactive demo today?** `enableJs=true` is set, but the Wasm target may not be exercised in CI. The demo page design depends on whether this works out-of-the-box or requires significant porting work.

3. **Which static site generator is chosen?** (Covered in findings-stack.md.) The information architecture below is generator-agnostic, but Astro/VitePress/Docusaurus each have different assumptions about directory structure.

4. **Is there an existing Compose/Wasm embedding HTML template from JetBrains?** If so, the demo integration is largely copy-paste. If not, the effort is higher. [TRAINING_ONLY — verify via JetBrains docs]

5. **What is the GitHub Pages deployment URL?** (Likely `https://tstapler.github.io/stelekit/` or similar.) Some IA decisions (root vs. subpath) depend on this. Should be established before finalizing URL structure.

---

## Recommendation

### Recommended Information Architecture

```
/ (Landing page — single page, marketing)
  ↓ Hero: headline + one-liner + "Try in Browser" CTA + "Download" CTA
  ↓ Platform status table (Desktop: working, Android: working, Web: demo, iOS: planned)
  ↓ Feature cards (outliner, journals, backlinks, search, local-first)
  ↓ Logseq comparison table
  ↓ Quick start (3-line code block)
  ↓ Dual audience links: "User Guide →" | "Developer Docs →" | "Contribute →"

/demo (WASM demo — embedded Compose/Wasm, full page)
  ↓ Short "What to try" intro paragraph
  ↓ Pre-loaded sample graph with a "Start here" journal entry
  ↓ Link back to Download and User Guide

/docs/user/
  /docs/user/getting-started
  /docs/user/outliner          (blocks, nesting)
  /docs/user/journals
  /docs/user/backlinks
  /docs/user/search

/docs/developer/
  /docs/developer/architecture
  /docs/developer/build
  /docs/developer/contributing
  /docs/developer/module-structure
```

### Feature set priorities (in order)

1. **Landing page with hero + comparison table + quick start** — highest ROI; addresses all three audiences at entry point
2. **Browser demo page (`/demo`)** — differentiator vs. Logseq and Obsidian; primary CTA target
3. **User guide (5–6 pages)** — enables adoption; covers core workflows
4. **Developer docs (3–4 pages)** — enables contribution; draws on existing `docs/architecture/` content
5. **CI/CD deploy pipeline** — necessary for maintenance sustainability

### Design principles to follow

- **Demo is marketing, not docs**: place "Try in Browser" in primary nav and hero, not in the getting started guide.
- **Three-sentence hero**: headline + one descriptor sentence + platform status. No paragraph of prose above the fold.
- **Terse voice throughout**: match the brand voice from `branding-readme.md` — no filler adjectives, no banned words.
- **Honest platform status**: show what works and what does not. A table is better than a paragraph.
- **Logseq framing is an asset, not a liability**: users searching for Logseq alternatives are the primary discovery path. Lean into the comparison rather than minimizing it.
- **Mobile-readable landing page**: PKM users are often on mobile when evaluating tools. Landing page must be usable on small screens even if the demo itself is desktop-only.

---

## Pending Web Searches

1. `site:kotlinlang.org "compose multiplatform" web playground demo` — verify whether JetBrains hosts a live Compose/Wasm browser demo and what URL/embedding pattern they use
2. `logseq.com "try in browser" OR "web app" 2024 2025` — verify current state of Logseq's browser demo and whether it is live, deprecated, or self-hosted
3. `obsidian.md site structure "developer docs" OR "plugin docs" URL` — confirm current URL structure for user help vs. developer/plugin API docs
4. `compose multiplatform wasm bundle size production 2024 2025` — get concrete bundle size numbers to assess demo loading time feasibility
5. `github pages site:docs.github.com size limit OR "repository size"` — confirm current GitHub Pages size and bandwidth constraints for hosting a large WASM artifact
