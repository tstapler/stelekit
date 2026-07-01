# Graph Sections with Lazy Sync — Feature Requirements

**Status**: Draft (v2 — device profile model)
**Author**: Tyler Stapler
**Date**: 2026-06-29
**Related**: ADR-013 (WASM REST+OPFS), ADR-014 (.stele-sections schema), ADR-015 (WASM write-back), BUG-005

---

## Problem Statement

Tyler has a single SteleKit graph with 8 000+ pages spanning personal notes, work notes
for multiple employers, and journal entries. Three problems today:

1. **Privacy leakage** — a work machine managed by Employer A can see personal notes and
   notes from Employer B. These must never land on a company device.
2. **Irrelevance noise** — syncing to a phone or a new laptop downloads the entire graph,
   including thousands of pages that will never be opened on that device.
3. **Cold-start cost** — opening the graph for the first time at a new job means cloning
   8 000 pages before doing any work. Only 200–300 pages are relevant to this job.

---

## Target Users

| Persona | Device | Needs |
|---------|--------|-------|
| Tyler (new job at Acme) | Acme-issued work laptop | Clone down only Acme work notes + today's work journal. Personal notes must not exist on this machine. New pages auto-land in Acme section. |
| Tyler (personal) | Personal laptop | Full graph: personal + Acme + Beta notes, all journals. Can temporarily hide any section. |
| Tyler (phone) | Personal phone | Personal notes + journals only. Work sections hidden or removed. |

---

## Goals

| # | Goal |
|---|------|
| G-1 | Define named sections; assign pages and journals to them |
| G-2 | Work device downloads ONLY its active sections — personal content never lands |
| G-3 | Personal device can see all sections, toggle any section Active/Hidden/Removed |
| G-4 | New-job onboarding: clone a section slice in < 30 s on a 10 Mbps connection |
| G-5 | Graph index (titles, metadata) loads instantly; page body fetched only on navigation |
| G-6 | Journal entries follow the device's default section; a calendar day can have both work and personal journals |
| G-7 | Graphs with no `.stele-sections` file behave exactly as before (backward compat) |
| G-8 | Each device configures its own defaults once; no per-page tagging decisions required |

---

## Non-Goals (this iteration)

- Per-section encryption enforcement (schema field reserved now; enforcement is v2)
- Multi-user ACL (single-user only)
- Block-level section assignment (page-level only; see Appendix)
- Retroactive bulk re-assignment tooling
- iOS implementation (deferred until Bazel KMP iOS support lands)
- Ghost-link UI showing page name + section tooltip (descoped; see FR-14)

---

## Functional Requirements

### FR-1 — Section definition

User creates, renames, and deletes named sections in Settings → Sections. Each section has:
- `id` — stable slug, generated at creation, never changed (e.g., `acme-work`)
- `displayName` — human-readable (e.g., "Work – Acme Corp")
- `color` — optional hex accent color
- `pagePathPrefix` — directory under graph root for this section's pages (e.g., `pages/acme-work/`)
- `journalPathPrefix` — directory for this section's journal entries (e.g., `journals/acme-work/`)
- `sensitivity` — `normal` (default) or `sensitive` (reserved for v2 PIN/biometric gate)

### FR-2 — Page auto-assignment from device profile

When the user creates a new page, it is automatically assigned to the device's
`defaultSection` (see FR-12). No manual tagging required.

- The new file is created under the section's `pagePathPrefix` directory
- The section badge in the page header shows the auto-assigned section and can be changed
- Changing section reassigns the file to the new section's directory

For pages that exist in the graph without a section path prefix, they are **global** —
synced to all devices with any Active sections.

### FR-3 — Split journal entries

Journal entries follow the device's `defaultSection` (see FR-12).

Pressing "Today" on a work device opens `journals/acme-work/<today>.md`.
Pressing "Today" on a personal device opens `journals/<today>.md` (global).

A calendar day can produce two files:
- `journals/2026-06-29.md` — global journal
- `journals/acme-work/2026-06-29.md` — Acme work journal for the same day

The journal view shows only the entries for Active sections on that device.
A work device with `acme-work` Active (and `personal` Removed) sees only the work
journal entry for each day; the global personal entry is not visible.

Journal pages are **never** auto-assigned by hashtag content.

### FR-4 — Three-state section subscriptions

Each section on each device is in one of three states, stored in `PlatformSettings`:

| State | Visible in UI | Content on device | Toggle cost |
|-------|--------------|-------------------|-------------|
| **Active** | Yes | Yes (synced) | Instant |
| **Hidden** | No | Yes (on device) | Instant (no network) |
| **Removed** | No | No (not downloaded) | Requires sync/download |

```json
{
  "acme-work": "active",
  "personal": "removed",
  "health": "hidden"
}
```

- **Active → Hidden**: instantly hides content from UI, no deletion, no network
- **Hidden → Active**: instantly shows content, no network
- **Active/Hidden → Removed**: deletes local files; requires explicit confirmation
- **Removed → Active/Hidden**: downloads content from remote; shows progress

**Chinese firewall**: the work machine has personal/health sections in `removed` state.
Re-enabling a section on a work machine shows: "This will download [Section Name] content
to this device. Are you sure?"

### FR-5 — Lazy / on-demand page content

The graph loads in two stages:

**Stage 1 (startup, always)**: Index load — page titles, UUIDs, section assignments,
and backlink metadata. No page body is read. Completes in < 2 s for 8 000 pages.

**Stage 2 (on navigation)**: Content fetch — when the user opens a page, its Markdown
body is fetched from disk (Desktop — blobs materialized by `git` on first access via
`--filter=blob:none` sparse clone) or from the GitHub raw content API (WASM) and parsed.
Result cached in OPFS (WASM) or left on-disk after git materialization (Desktop).

Pages that have been opened before load from cache instantly. Pages never opened on
this device show a "Loading…" spinner for the network round-trip.

### FR-6 — First-time clone of a section slice

When a user sets up a new device with a remote configured and sections selected:

**Desktop (JVM/Android)**:
1. `git clone --filter=blob:none --sparse <remote>` — tree+commit objects only, no blobs
2. `git sparse-checkout set <section-pagePathPrefix> <section-journalPathPrefix>` — Active sections only
3. Blobs for Active pages are fetched on first file read (git lazy materialization)
4. Subscription change = `git sparse-checkout add/remove <prefix>` — no re-clone required

**WASM (ADR-013)**:
1. Fetch `.stele-sections` manifest via GitHub REST API (or GraphQL for faster tree walk)
2. Fetch tree listing for Active section path prefixes only — seeds page index rows in SQLite
3. Page body fetched via GitHub raw content API on first navigation, cached in OPFS

Total setup time for 300-page section on 10 Mbps: ≤ 30 s on both platforms.

### FR-7 — `.stele-sections` manifest schema

File at graph root, no extension, plain TOML (per ADR-011 / ADR-014):

```toml
version = 1

[[section]]
id = "acme-work"
displayName = "Work – Acme Corp"
color = "#4A90D9"
pagePathPrefix = "pages/acme-work"
journalPathPrefix = "journals/acme-work"
sensitivity = "normal"

[[section]]
id = "personal"
displayName = "Personal"
color = "#7CB87C"
pagePathPrefix = "pages/personal"
journalPathPrefix = "journals/personal"
sensitivity = "normal"

[[section]]
id = "health"
displayName = "Health & Medical"
color = "#E84040"
pagePathPrefix = "pages/health"
journalPathPrefix = "journals/health"
sensitivity = "sensitive"   # v2: requires PIN/biometric on any device
```

`sensitivity = "sensitive"` is parsed and stored now; enforcement (PIN gate) ships in v2.
Absence of this file means the graph has no sections (all pages global).

### FR-8 — Sync filtering by section state

Only **Active** sections contribute to sync and local storage:

**Desktop (JVM/Android)**:
- `GraphLoader.loadDirectory()` receives only Active section path prefixes
- `git sparse-checkout` cone contains only Active section directories
- Changing a section to Removed: `git sparse-checkout remove <prefix>` + delete local files

**WASM (ADR-013 fetch path)**:
- `WasmSectionSyncService` requests tree listings only for Active section path prefixes
- Hidden sections retain their OPFS cache but are not shown in UI
- Removed sections have no OPFS cache

### FR-9 — Section badge UI

- Page header shows a section badge ("Work – Acme Corp" with color dot)
- Tapping the badge opens a section picker (only Active sections shown)
- Journal header shows the section display name for section-specific entries
- Settings → Sections panel: manage sections and per-device subscription states

### FR-10 — New journal entry follows device default

The "Today" journal button always opens the journal for the device's `defaultSection`.
No secondary action required; no command palette lookup needed.

A separate "Switch journal context" action (command palette) allows opening the global
journal or any Active section's journal explicitly.

### FR-11 — Backward compatibility

- Graphs without `.stele-sections` load and behave identically to today
- A device with no profile configured (or migrating from an older version) defaults to
  all sections Active — identical to pre-sections behavior
- Removing a section does not delete pages; they become global (no `stele-section::`)

### FR-12 — Device profile and first-launch wizard

Each device has a **device profile** stored in `PlatformSettings`:
- `defaultSection: String` — section id for new pages and journal entries (`""` = global)
- `sectionStates: Map<String, SectionState>` — Active / Hidden / Removed per section

**First-launch wizard** (shown once, after remote is configured and `.stele-sections` exists):

```
What kind of device is this?
  ○ Work device     → prompts for which section; sets defaultSection + others Removed
  ○ Personal device → all sections Active; defaultSection = "" (global)
  ○ Custom          → manual selection
```

The wizard sets both `defaultSection` and `sectionStates` in one step. The user can
change either at any time in Settings → This Device.

### FR-13 — Ambient context indicator

A persistent, always-visible element in the sidebar header shows the current device
default section:
- Name and color dot of the `defaultSection` (e.g., "● Acme Work")
- If `defaultSection = ""` (global/personal): shows "All sections" or is hidden
- Tapping it opens the section quick-toggle panel:
  - Toggle any section Active ↔ Hidden (instant, no network)
  - "Manage sections" → Settings → Sections for Removed/download actions

### FR-14 — Cross-section backlink policy

When a page in an Active section contains a link to a page in a Removed section:
- The link renders as plain text (page name only)
- On hover/tap: shows "Content not available on this device" — **no section name, no
  path information** (prevents leaking which section the target belongs to)
- No ghost-link UI with section tooltip (that would leak metadata)

---

## Non-Functional Requirements

| # | Requirement |
|---|-------------|
| NFR-1 | Graph index (8 000 pages, metadata only) loads in ≤ 2 s cold, ≤ 500 ms warm |
| NFR-2 | Page body fetch + parse on WASM (single page via GitHub REST): ≤ 3 s on 10 Mbps |
| NFR-3 | Desktop graph load with section filtering: ≤ 200 ms overhead vs. unfiltered load |
| NFR-4 | Section assignment change (badge picker): ≤ 500 ms including file move |
| NFR-5 | Zero page bodies from Removed sections downloaded to any device |
| NFR-6 | Hidden → Active toggle: instant (≤ 100 ms, no network) |
| NFR-7 | Removed → Active download: shows progress; does not block app use |
| NFR-8 | All operations handle graphs with 0 sections and graphs with 10+ sections identically |

---

## Success Metrics

| Metric | Target |
|--------|--------|
| New-job setup (300-page section, 10 Mbps) | ≤ 30 s from "configure remote" to first page visible |
| Personal device receives work-only pages | 0 personal pages on a device with personal section Removed |
| Page body load on WASM (cache miss) | ≤ 3 s |
| Page body load on WASM (cache hit) | ≤ 200 ms |
| Section toggle Active ↔ Hidden | ≤ 100 ms |
| First-launch wizard completion | User reaches first page in < 2 min from fresh install |

---

## Open Questions

| # | Question | Status | Impact |
|---|----------|--------|--------|
| OQ-1 | Journal view on work device when same day has both global + work entry | **Resolved**: show only Active section's entry | FR-3 |
| OQ-2 | **Multi-section pages**: Can a page belong to two sections? | Open: current spec says no | FR-2 |
| OQ-3 | **Index format**: SQLite metadata-only rows (recommended) vs `.stele-index.json` | **Resolved**: SQLite `is_content_loaded=false` rows with `section_id` | FR-5 |
| OQ-4 | WASM calendar: show journal dates before content is fetched? | **Resolved**: yes — tree listing seeds dates; content loads on tap | FR-5, FR-3 |
| OQ-5 | WASM write-back credentials: re-enter PAT each session? | **Resolved**: yes, in-memory only for v1 | FR-8 (WASM) |
| OQ-6 | **Hidden section sync**: when a section is Hidden, does its content still sync in the background? | Open: likely yes (stay current) vs no (freeze at hide time) | FR-4, FR-8 |
| OQ-7 | **Section removal confirmation UX**: one confirmation dialog, or a typed-name confirmation for sensitive sections? | Open | FR-4 |
| OQ-8 | **Default section on personal device for new pages**: should the user be able to set a non-global default (e.g., default new pages to Personal section, not global)? | Open | FR-12 |

---

## Dependencies

| Dependency | Status |
|-----------|--------|
| ADR-013 (WASM REST + OPFS fetch path) | Accepted |
| ADR-014 (.stele-sections TOML manifest schema) | Accepted — needs `sensitivity` field added |
| ADR-015 (WASM write-back via GitHub Git Data API) | Accepted |
| BUG-005 Phase 1 (WASM git stubs fail visibly) | Done (this branch) |
| `stele-section::` frontmatter parser | Not started |
| `.stele-sections` TOML parser (ktoml, per ADR-011) | Not started |
| Split journal file-naming convention | Not started |
| Lazy content fetch in `GraphLoader` | Not started |
| WASM `WasmSectionSyncService` (tree filter + lazy fetch) | Not started |
| Section badge + section picker UI | Not started |
| Device profile + first-launch wizard | Not started |
| Ambient context indicator (sidebar) | Not started |
| Settings: section management + three-state subscription UI | Not started |
| `git sparse-checkout add/remove` integration in `GitSyncService` | Not started |

---

## Phasing

### Phase 1 — Data model (no sync, no lazy fetch)

- `.stele-sections` TOML parser (with `sensitivity` field)
- `stele-section::` frontmatter property parsed into `Page.sectionId`
- `section_id TEXT NOT NULL DEFAULT ''` column in `pages` table + migration
- Split journal directory convention
- Device profile model in `PlatformSettings` (defaultSection + sectionStates)
- `.stele-sections` written/updated when sections change in Settings

### Phase 2 — Desktop sync filtering + lazy content + first-launch wizard

- `GraphLoader` filters by Active section path prefixes (cold-start + warm reconcile)
- Two-stage load: index-only on startup, body on navigation
- `git sparse-checkout` integration for Active/Removed state changes
- Device profile first-launch wizard
- Ambient context indicator + section quick-toggle panel

### Phase 3 — WASM fetch filtering + lazy fetch

- `WasmSectionSyncService` filters GitHub tree by Active section path prefixes (GraphQL)
- WASM lazy fetch: page body fetched from GitHub REST API on navigation, cached in OPFS
- Three-state subscription UI for WASM (Hidden retains OPFS cache; Removed clears it)

---

## Appendix A: Why page-level, not block-level, assignment

Block-level assignment would let one journal entry contain both work and personal blocks.
It sounds appealing but:
1. **git syncs whole files** — splitting one `.md` across two sections means physically
   splitting the file, breaking the current file-per-page model.
2. **Unpredictable UX** — a page whose blocks belong to different sections cannot be
   shown/hidden reliably.
3. **Parser complexity** — the outliner pipeline would need to emit different block
   subsets per device.

Page-level is the minimal, understandable model. Block-level can be revisited once
page-level is proven.

---

## Appendix B: Why `sensitivity` is schema-only in v1

Health and financial data need more than sync filtering — the data must be unreadable
even if the device is compromised (stolen, malware). This requires per-section encryption
keys derived from a user-provided PIN or biometric, analogous to Android's work profile
separate encryption key.

The `sensitivity` field is added to the manifest schema now because retrofitting it later
requires a breaking schema migration on all existing `.stele-sections` files. The
enforcement (PIN prompt, key derivation, encrypted-at-rest storage) ships as part of the
paranoid-mode v2 integration.

---

## Appendix C: Three-state model rationale

Binary subscribe/unsubscribe forces a painful choice: if you want to temporarily ignore
work notes while on vacation, you must either (a) leave them visible (distracting) or
(b) remove them (and re-download 300 files when you return). Hidden solves this: content
stays on device, instantly recoverable, but invisible in the UI. This maps to how iOS
Focus Modes work — apps are hidden, not uninstalled.
