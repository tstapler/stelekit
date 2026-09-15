# Graph Sections — Feature Requirements

**Status**: Draft  
**Author**: Tyler Stapler  
**Date**: 2026-06-29  
**Related**: ADR-013 (WASM section sync), BUG-005 (WASM git stubs)

---

## Problem Statement

A SteleKit user maintains a single graph across multiple devices and multiple life
contexts (e.g., job at Acme Corp, consulting for Beta LLC, personal notes). Today, every
device receives the entire graph on sync. This causes two problems:

1. **Privacy leakage** — work notes from one employer land on a device shared with a
   second employer; personal notes land on a work laptop subject to IT inspection.
2. **Irrelevance noise** — a phone used for quick personal captures downloads 6,000 work
   pages that are never opened on that device.

Users work around this today by maintaining separate graphs per context. This breaks
cross-context linking (you cannot link a personal journal entry to a work project page)
and multiplies storage costs.

---

## Target User

Knowledge worker using SteleKit on 2+ devices with distinct contexts — for example:

- **Work laptop** (Acme Corp, company-managed) — should see Acme work notes only
- **Work laptop** (Beta LLC, personal consulting) — should see Beta notes + personal notes
- **Personal phone** — should see personal notes only; no work content

---

## Goals

| # | Goal |
|---|------|
| G-1 | User can define named sections and assign pages to them |
| G-2 | Each device declares which sections it subscribes to |
| G-3 | Sync only delivers pages in subscribed sections to each device |
| G-4 | Pages without a section are "global" and always synced everywhere |
| G-5 | Section assignment is stored in the graph and propagates across devices |
| G-6 | The feature is additive — untagged graphs behave exactly as today |

---

## Non-Goals

The following are explicitly out of scope for this feature:

- **Per-section encryption** — covered by the paranoid-mode feature (separate ADR)
- **Multi-user access control** — sections are single-user; no sharing or ACL between users
- **Block-level section assignment** — assignment is at the page level (blocks inherit their page's section)
- **Retroactive bulk re-assignment** — no tooling to move all historical blocks of a topic into a section after the fact
- **Offline-first write-back for WASM** — the WASM platform can read (via ADR-013 REST + OPFS fetch path); write-back to git from a browser is deferred to a separate ADR

---

## Functional Requirements

### FR-1 — Section definition

The user can create, rename, and delete named sections via a settings UI. Each section has:

- `id` — stable slug (e.g., `acme-work`, generated at creation, never renamed)
- `displayName` — human-readable label (e.g., "Work – Acme Corp")
- `color` — optional accent color for the UI badge

Sections are stored in `.stele-sections` at the graph root (see FR-6 for schema).

### FR-2 — Page assignment

A page can be assigned to exactly one named section. Assignment is stored as a
property in the page's frontmatter:

```
stele-section:: acme-work
```

Assignment can be changed at any time. Pages with no `stele-section::` property belong
to the **global** section and are visible on all devices.

### FR-3 — Hashtag shortcut (stretch goal)

If a page's first block contains a hashtag matching a section id (e.g., `#acme-work`),
the page is automatically assigned to that section. This is advisory — the explicit
`stele-section::` property takes precedence if both are present.

### FR-4 — Device subscription

Each device stores its section subscriptions locally (not synced). The user can
configure which sections this device receives:

- **Subscribe** to one or more sections
- **Unsubscribe** to stop receiving a section's pages on this device
- Subscribing to a section that is new (no OPFS cache) triggers an initial sync

Subscriptions are stored in `PlatformSettings` under key `subscribedSections` as a
JSON array of section ids.

### FR-5 — Sync filtering

During graph load:

- **Desktop (JVM/Android/iOS)**: `GraphLoader` skips files whose frontmatter
  `stele-section::` property is not in the device's subscribed sections. Global
  (untagged) pages are always loaded.
- **WASM**: `WasmSectionSyncService` (ADR-013) fetches only the subscribed sections'
  file trees from the remote. Files outside subscribed sections are never downloaded.

### FR-6 — `.stele-sections` manifest schema

The graph root contains a `.stele-sections` file (no extension, plain JSON) that
defines all sections. Devices read this file to know what sections exist before
selecting subscriptions.

```json
{
  "version": 1,
  "sections": [
    {
      "id": "acme-work",
      "displayName": "Work – Acme Corp",
      "color": "#4A90D9",
      "pathPrefix": "sections/acme-work"
    },
    {
      "id": "personal",
      "displayName": "Personal",
      "color": "#7CB87C",
      "pathPrefix": "sections/personal"
    }
  ]
}
```

`pathPrefix` is relative to the graph root and defines the directory where that
section's pages live on disk. This allows the WASM fetch path (ADR-013) to filter the
git tree listing by path prefix without reading every file's frontmatter.

### FR-7 — Journal entries and section assignment

Journal pages (files in `journals/`) belong to the **global** section by default. A
journal entry can be explicitly assigned to a section via the `stele-section::` property
in its frontmatter. Auto-assignment based on block hashtags (FR-3) does **not** apply
to journal pages — journals are personal by nature and should not be auto-routed by
content tags.

### FR-8 — Section UI surface

- A **section badge** appears in the page header showing the page's current section.
  Tapping it opens an assignment picker.
- The sidebar **Settings → Sections** panel allows managing sections and device
  subscriptions.
- A new **onboarding step** (shown once, after first remote configured) prompts the
  user to set up sections if they have multiple devices.

### FR-9 — No-section graphs (backward compat)

If `.stele-sections` is absent, the entire graph behaves as before: all pages are
global, all pages are synced to all devices. No migration required.

---

## Non-Functional Requirements

| # | Requirement |
|---|-------------|
| NFR-1 | Initial WASM sync of a 500-page section on 10 Mbps: ≤ 10 seconds (from ADR-013) |
| NFR-2 | Desktop graph load with section filtering: ≤ 200 ms overhead vs. unfiltered load |
| NFR-3 | Section assignment change (frontmatter write + local re-index): ≤ 500 ms |
| NFR-4 | `.stele-sections` manifest is valid JSON; parser must reject unknown `version` values > 1 |
| NFR-5 | Removing a section does not delete pages; they become global (untagged) |

---

## Success Metrics

| Metric | Target |
|--------|--------|
| 1000-page graph, 200 pages in "Work" section | Device subscribed only to "Work" receives exactly 200 pages + global pages (0 unsubscribed pages downloaded) |
| Switching device subscription | Local operation, no network, completes < 5 s |
| User retention on multi-device setups | Baseline to be established in first 90 days post-launch |

---

## Open Questions

These must be resolved before implementation begins:

| # | Question | Owner | Impact |
|---|----------|-------|--------|
| OQ-1 | **Journal auto-assignment**: If a journal entry block contains `#acme-work`, should the page be assigned to that section? FR-7 says no — but is this the right call? | PM + Tyler | FR-7 definition |
| OQ-2 | **Multi-section pages**: Can a page belong to two sections? Current spec: no (one section per page). This simplifies sync but may frustrate users who want a page visible in both work and personal contexts. | PM | FR-2, manifest schema |
| OQ-3 | **Section UI placement**: Tag-palette in page header vs. dedicated sidebar panel vs. command palette only? | UX | FR-8 |
| OQ-4 | **Onboarding trigger**: Show section setup flow on first "configure remote" or on first "add second device"? | PM + UX | FR-8 |
| OQ-5 | **WASM write-back**: ADR-013 covers fetch only. What is the strategy for pushing page changes back to git from the browser? This must be decided before the full WASM section sync is implemented. | Engineering | FR-5 (WASM) |

---

## Dependencies

| Dependency | Status |
|-----------|--------|
| ADR-013 (WASM REST + OPFS section sync — fetch path) | Accepted |
| WASM write-back ADR (browser → git push strategy) | **Not written** (OQ-5) |
| BUG-005 fix (WASM git stubs fail visibly) | In progress (Phase 1 done) |
| `stele-section::` property parser in `OutlinerPipeline` | Not started |
| `.stele-sections` manifest parser | Not started |
| `SectionSyncService` interface + JVM no-op + WASM impl | Not started |

---

## Phasing

### Phase 1 — Manifest + assignment (no sync filtering)

- Define `.stele-sections` schema (FR-6)
- Parse `stele-section::` frontmatter property
- Section assignment UI badge (FR-8 partial)
- `.stele-sections` file written/updated when sections change

This phase has no sync behavior — it establishes the data model.

### Phase 2 — Desktop sync filtering

- `GraphLoader` skips pages outside subscribed sections
- Device subscription UI in Settings (FR-4)
- `NFR-2` verification (load-time overhead)

### Phase 3 — WASM fetch filtering

- `WasmSectionSyncService` (ADR-013) filters git tree by `pathPrefix`
- Requires WASM write-back ADR to be accepted first (OQ-5)

---

## Appendix: Why page-level (not block-level) assignment

Block-level assignment would allow fine-grained routing: one block in a journal
entry could belong to "Work" while another belongs to "Personal". This sounds
appealing but creates significant complexity:

1. **Sync atomicity**: git syncs whole files. Splitting one `.md` file across two
   sections means splitting the file itself — a significant disruption to the
   current file-per-page model.
2. **UI confusion**: a page whose blocks belong to different sections is
   unpredictable to navigate. The user cannot know at a glance "will this page
   appear on my work laptop?"
3. **Parser complexity**: the outliner pipeline would need to be section-aware,
   emitting different block subsets per device.

Page-level assignment is the minimal, understandable model. Block-level can be
revisited in a future version once page-level proves useful in practice.
