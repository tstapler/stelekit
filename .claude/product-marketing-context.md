---
type: open-source
project: stelekit
last_updated: 2026-04-11
---

# Stelekit — Brand & Product Marketing Context

> This file is read automatically by brand-guidelines, frontend-design, and logo-designer skills.

---

## 1. Project Overview

**One-liner:** Plant your knowledge in stone. A local-first outliner built to outlast any platform.

**What it does:**
Stelekit is a Kotlin Multiplatform personal knowledge management system — a spiritual successor to Logseq that keeps your notes as plain markdown on your disk, forever. It provides an outliner-first writing experience, bidirectional linking, journals, and a growing feature set for maintaining and curating a zettelkasten or PKM system over the long haul. One shared codebase runs natively on Desktop (JVM), Android, iOS, and Web via Compose Multiplatform, with a persistent SQLite backend that eliminates cold-start graph re-scanning.

**The core promise:** Your notes are markdown files on your disk. Stelekit is the tool that helps you think in them — not the vault that holds them hostage.

**Category:** Personal knowledge management (PKM) / outliner / zettelkasten tool

**Project type:** Cross-platform application (KMP library core, native app shells per platform)

**License:** Source-available — free to use for any purpose including commercially; forking and selling/hosting as a product prohibited.
*(License: Elastic License 2.0 / ELv2)*

---

## 2. Audience

**Primary users:**
Power users who've outgrown Logseq — people who love the outliner-first, local-first philosophy but are frustrated by Electron performance on desktop, a compromised mobile experience, and a monetization direction that increasingly leaves self-hosted personal users behind. They want their notes in markdown, on their disk, forever — with tooling that actually respects that.

**Secondary users:**
Kotlin / Compose Multiplatform developers looking for a serious, real-world KMP reference project. Note-taking adjacent developers who want to build integrations or extend their PKM in a typed, maintainable language.

**Contributors:**
KMP/Compose developers; people who love what Logseq originally stood for and want to contribute to a codebase they can actually read; outliner and PKM nerds who want to shape the product.

**Not for:**
- Users happy with Logseq who don't feel the performance or platform pain
- People who rely on Logseq's plugin ecosystem today (plugin support is early-stage)
- Anyone who wants their notes managed server-side

**Anti-persona:**
The "just works out of the box, don't make me think about files" user who wants Notion. Stelekit is for people who have an opinion about how their notes are stored.

---

## 3. Problem & Differentiation

**Core frustration:**
Logseq pioneered the local-first, outliner-based PKM category. But its technical choices (ClojureScript, Electron) create real pain — slow startup, heavy memory use, a non-native feel on every platform, and a nearly impenetrable codebase for contributors. Worse, its monetization direction is moving toward cloud sync and hosted features, quietly deprioritizing the plain-markdown, self-hosted user who made the community.

**What exists and why it falls short:**
- **Logseq** — the original, but Electron-heavy, compromised on mobile, monetization drift away from local power users
- **Obsidian** — not outliner-first; lacks the block/journal model; plugin-dependent for core features
- **Roam Research** — cloud-only, expensive, the model Logseq tried to copy; we don't copy Roam either
- **Notion** — not local, not yours
- **Dendron / Foam** — developer-focused, less PKM-complete

**Core design philosophy:**
Build on the best ideas from both Logseq and Roam — the outliner model, bidirectional links, journals, graph views — but execute them with rock-solid stability, native performance, and genuine platform-first design on every target. Don't copy; supersede.

**Key bets:**
- Markdown on disk is non-negotiable. The file format is the API.
- Native performance via KMP is worth the engineering investment.
- Long-term knowledge curation requires stability over novelty — a tool that doesn't break your workflow when it updates.
- LLM integration, better renaming/grouping, and advanced curation features belong in the tool itself, not as hacks.

**Word-of-mouth pitch:**
*"It's what Logseq should have been — your markdown files, native apps on every platform, actually fast on Android, and a codebase you can contribute to."*

---

## 4. Brand Voice & Tone

**Personality:** Precise, durable, quietly confident, principled, grounded

**Technical depth:** Expert-first — assumes users understand what a block graph, zettelkasten, or local-first tool is. No hand-holding of concepts the audience already owns.

**Writing style:** Terse and concrete. The brand voice sounds like it was carved, not typed — deliberate, no filler.

**Words and phrases to use:**
- "local-first", "your files", "markdown on disk"
- "outliner", "blocks", "graph", "journal", "backlinks"
- "carved", "permanent", "yours", "durable"
- "native", "fast", "reliable"
- "zettelkasten", "PKM", "knowledge graph"
- "stand the test of time"

**Words and phrases to avoid:**
- "seamless", "powerful", "revolutionary", "supercharge", "productivity"
- "AI-powered" (unless leading with the user benefit, not the tech)
- "in the cloud", "your data is safe with us"
- "ecosystem" (too corporate)
- Anything that implies notes are stored somewhere other than the user's disk

**Example sentences that nail the voice:**
> *"Your notes are markdown files. Stelekit is the outliner that thinks in them."*
> *"Plant a slab. It'll still be there in twenty years."*
> *"No Electron. No vendor lock-in. No cold-start tax."*

---

## 5. Visual Direction

**Concept:** The stele — an upright stone slab used across civilizations to inscribe knowledge meant to endure. Earthy, geological, material. Not digital-minimalist-white, not hacker-terminal-green. Stone.

**Default color mood:** Warm stone / dark marble — earthy browns, warm grays, off-whites, muted ochres. The Gruvbox palette already in Color.kt (warm bg #282828, warm fg #FBF1C7, stone grays) is the right energy for the default dark theme.

**Default theme palette (stone/marble — rename away from Logseq names):**

| Role | Dark Theme | Light Theme |
|------|-----------|-------------|
| Background | `#282828` (warm dark stone) | `#F5F0E8` (warm parchment) |
| Surface / sidebar | `#3C3836` (granite) | `#EDE8DC` (limestone) |
| Primary text | `#EBDBB2` (warm cream) | `#3C3836` (dark stone) |
| Secondary text / muted | `#928374` (worn stone gray) | `#7C7369` (aged stone) |
| Accent / links | `#83A598` (patina teal) | `#458588` (deep patina) |
| Bullet / guide | `#504945` (dark granite) | `#B8AFA0` (pale stone) |
| Highlight yellow | `#FABD2F` (ochre) | `#D79921` (amber) |

**Theming posture:** The stone palette is the *default* — not the only option. Stelekit should support full theme customization (like a text editor: VS Code themes, custom CSS tokens). The architecture should expose semantic color tokens so users can retheme entirely. The brand visual identity uses the stone palette; users can diverge from it completely.

**Typography feel:**
- Content/editor: monospace or monospace-adjacent (the notes are text, they should feel like text)
- UI chrome: clean geometric sans — no decorative serifs
- No fonts that read as "startup landing page"

**Aesthetic references:**
- Gruvbox (color warmth and texture)
- Helix / Zed editor (developer-tool craft, no fluff)
- Obsidian dark theme (familiar PKM context)
- Physical material: slate, sandstone, worn marble — not glass, not plastic

**Logo direction:**
Combination mark — a minimal geometric stele/slab icon (upright rectangle, slightly tapered, possibly with a single carved line suggesting an inscription) + lowercase wordmark "stelekit" in a geometric sans. Feels hewn, not rendered. No gradients. Monochrome-first so it works on any background.

---

## 6. Adoption Goals

**Primary metric:** GitHub stars and active daily users (self-reported via telemetry opt-in or community posts)

**Discovery path:**
- Logseq community (Reddit r/logseq, Logseq Discord, forums)
- KMP / Compose Multiplatform community (JetBrains showcase, KMP Reddit)
- PKM Twitter/X and community newsletters (Forte Labs, Zettelkasten.de)
- "Logseq alternatives" and "Logseq Android" search queries
- Developer blogs / indie hacker communities (people who build their own tools)

**Trust signals:**
- Logseq format compatibility (opens your existing graph correctly)
- SQLDelight persistent storage (no data loss on crash)
- Test suite passing on JVM + Android
- Transparent roadmap and honest feature gap documentation
- Fast cold start (measurable, demonstrable)

**Adoption barrier:**
Feature parity gaps — no whiteboards, no full Datalog query engine, embryonic plugin system. Users won't switch daily drivers until the core workflow is airtight. Focus on the baseline that PKM power users need daily: outliner, journals, backlinks, search.

**"Aha" moment:**
Opening their existing Logseq graph in Stelekit on Android and having it load instantly, feel native, and edit cleanly — without the Logseq mobile lag. Or: noticing their markdown files on disk are unchanged after editing in Stelekit.

---

## 7. Key Messages

**Headline:** Your knowledge, carved in stone.

**Subhead:** A local-first outliner that keeps your notes as plain markdown — forever — and runs natively on every platform you use.

**Supporting points:**
1. **Yours, permanently.** Notes are markdown files on your disk. No sync service owns them. No company going broke takes them with it.
2. **Native on every platform.** One Kotlin codebase. Desktop, Android, iOS, Web — not Electron wrapped in apology.
3. **Built to compound.** Journals, backlinks, tags, full-text search, zettelkasten patterns — with LLM curation, smarter renaming, and knowledge maintenance on the roadmap.

**CTA:** Open your graph → `./gradlew :kmp:runApp`

---

## 8. Positioning vs. Logseq

This is delicate. Stelekit respects Logseq's contributions — the outliner-first model, local-first philosophy, and the community it built are genuinely important. The divergence is not contempt; it's a different set of bets:

| | Logseq | Stelekit |
|---|---|---|
| Runtime | Electron / ClojureScript | Kotlin Multiplatform / Compose |
| Mobile | Compromised | Native-first |
| Business model | Sync SaaS (paid cloud) | Source-available, personal use free |
| Plugin model | JS plugins | Kotlin-native (roadmap) |
| Graph storage | In-memory re-scan | SQLite persistent |
| Long-term bet | Platform + hosted services | Local-first, always |

Tone when discussing Logseq: **respect, not snark.** Stelekit exists because of Logseq's ideas, not in spite of them.

---

## 9. GitHub Presence

**README purpose:** Architecture overview + quick start. The project is technically interesting — the KMP structure, SQLDelight integration, and multiplatform Compose setup deserve a narrative intro, not just command lists.

**Social proof to highlight:** KMP as a serious technical signal; SQLDelight persistence; passing CI on multiple targets; comprehensive test suite (property tests, integration tests, screenshot tests)

**Contribution posture:** Open to contributors — especially KMP developers — but core vision is opinionated. Good first issues around feature parity gaps; architecture decisions are documented.

**Recommended GitHub topics:**
`kotlin-multiplatform` · `logseq` · `pkm` · `zettelkasten` · `compose-multiplatform` · `sqldelight` · `outliner` · `knowledge-management` · `local-first` · `note-taking` · `android` · `desktop`
