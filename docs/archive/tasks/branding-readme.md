# Branding: README Rewrite

**Epic**: Create a compelling root README.md and update kmp/README.md for the Stelekit brand  
**Status**: Planned

---

## Background

The project currently has:
- No `README.md` at the repository root
- `kmp/README.md` — a generic technical document titled "Logseq Kotlin Multiplatform Module" that describes a migration strategy from ClojureScript to Kotlin. This framing is obsolete; Stelekit is no longer a migration aid but a standalone project.

GitHub surfaces the root README as the project's public face. The absence of one means the repository currently shows only the directory listing to anyone who discovers it.

The brand strategy defines the headline, tone, and key messages clearly. The README is the primary place to execute them.

---

## Goals

- Create `README.md` at the repository root that works as the project's front page on GitHub
- Update `kmp/README.md` to reflect the Stelekit name and current architecture
- Apply brand voice throughout: terse, concrete, no filler ("seamless", "powerful", "supercharge" are banned)
- Position honestly relative to Logseq: respect, not contempt

---

## Content Specification: Root README.md

### Required sections (in order)

1. **Hero section**
   - Headline: `Your knowledge, carved in stone.`
   - One-sentence description: local-first outliner, plain markdown on disk, native on every platform
   - Quick start badge area (build status, license badge)

2. **What is Stelekit**
   - 2–3 short paragraphs
   - Explain the outliner-first model, bidirectional linking, journals
   - Position relative to Logseq: Logseq pioneered the ideas; Stelekit makes different technical bets (KMP instead of Electron, persistent SQLite instead of in-memory re-scan)
   - Use the positioning table from the brand strategy (Logseq vs. Stelekit)
   - Tone: "Stelekit exists because of Logseq's ideas, not in spite of them."

3. **Quick Start**
   ```
   git clone <repo>
   cd stelekit
   ./gradlew :kmp:runApp
   ```
   One sentence: "Point it at your existing Logseq graph directory. It reads the same markdown files."

4. **Architecture Overview**
   - KMP targets: Desktop (JVM), Android, iOS (planned), Web (Compose/Wasm)
   - Persistence: SQLDelight + SQLite — no cold-start graph re-scan
   - UI: Compose Multiplatform — single shared UI layer
   - File format: Logseq-compatible markdown (your files are unchanged)
   - Brief diagram or table is appropriate here

5. **Project Status**
   - Honest: core outliner, journals, backlinks, search are working on Desktop and Android; iOS disabled; plugin system early-stage
   - Link to `TODO.md` for the full roadmap and known issues

6. **Contributing**
   - This is a source-available project (PolyForm Noncommercial 1.0.0)
   - Contributions welcome, especially from KMP/Compose developers
   - Good first issues: feature parity gaps, test coverage, platform polishing

7. **GitHub Topics**
   - List at the bottom as a note for repo metadata:
   `kotlin-multiplatform` · `logseq` · `pkm` · `zettelkasten` · `compose-multiplatform` · `sqldelight` · `outliner` · `knowledge-management` · `local-first` · `note-taking` · `android` · `desktop`

### Voice Checklist for Author
Before finalizing, verify:
- [ ] No use of "seamless", "powerful", "revolutionary", "supercharge", "productivity"
- [ ] No "ecosystem"
- [ ] No "AI-powered" without leading with the user benefit
- [ ] "Source-available" not "open source"
- [ ] Logseq is mentioned with respect, not contempt
- [ ] The quick start command actually works

---

## Content Specification: kmp/README.md Update

The existing file describes a "gradual migration from ClojureScript to Kotlin." That framing is obsolete. The file should be updated to:

1. **Title**: "Stelekit KMP Module"
2. **One-liner**: The core Kotlin Multiplatform library for Stelekit — shared business logic, data layer, and Compose UI
3. **Keep**: Architecture section (KMP targets), Key Technologies section, Building section with commands
4. **Remove**: "Integration with Existing Logseq" section (ClojureScript interop framing)
5. **Remove**: "Next Steps" section (now tracked in TODO.md)
6. **Update**: Project Structure tree — current paths are `dev.stapler.stelekit.*`, not `com.logseq.kmp.*` as the old tree shows
7. **Update**: Technology versions (Kotlin 2.1.20, Compose Multiplatform 1.7.3, SQLDelight 2.3.2 per settings.gradle.kts)

---

## Implementation Plan

### Story 1: Create Root README.md

#### Task 1.1 — Draft Root README Content
- Files: `README.md` (new, at root)
- Write per the content specification above
- Use markdown tables for the Logseq vs. Stelekit comparison and for platform status
- Target length: ~150–200 lines; not a wall of text
- Effort: 2–3 hours

### Story 2: Update kmp/README.md

#### Task 2.1 — Rewrite kmp/README.md
- Files: `kmp/README.md`
- Update title, remove obsolete sections, fix project structure tree and version numbers
- Effort: 1 hour

### Story 3: Add GitHub Repository Metadata

#### Task 3.1 — Set GitHub Topics (Manual Step)
- This is a manual action in the GitHub web UI (or via `gh repo edit --add-topic`)
- Topics to set: `kotlin-multiplatform logseq pkm zettelkasten compose-multiplatform sqldelight outliner knowledge-management local-first note-taking android desktop`
- Not a code change; document as a post-merge checklist item
- Effort: 10 minutes

---

## File Change Summary

| File | Change Type |
|---|---|
| `README.md` | New file (root) |
| `kmp/README.md` | Substantial update |

---

## Known Issues

### Risk: README Becomes Stale Quickly
Feature status sections ("what works, what doesn't") become outdated as development continues. The README should point to `TODO.md` for the living status rather than duplicating it.

**Mitigation**: The "Project Status" section links to `TODO.md` rather than listing specific issues inline.

### Risk: Quick Start Command Accuracy
`./gradlew :kmp:runApp` must actually work when someone reads the README. If the build has regressions or requires setup steps (Android SDK, JDK version), the README must document them.

**Mitigation**: Test the quick start command on a clean clone before merging. Document prerequisites (JDK 17+) explicitly.

### Consideration: No Logo/Screenshot Yet
The README would benefit from a logo or screenshot but neither exists yet (see `branding-logo-assets.md`). Write the README without image assets; add an `<!-- TODO: add logo here -->` placeholder. Do not block README merge on logo completion.

---

## Success Criteria

- `README.md` exists at the repository root
- The headline "Your knowledge, carved in stone." appears in the first 10 lines
- The quick start command (`./gradlew :kmp:runApp`) is present and accurate
- Logseq vs. Stelekit comparison is present and respectful in tone
- `kmp/README.md` no longer references ClojureScript migration or `com.logseq.kmp`
- No banned brand voice words appear in either file
