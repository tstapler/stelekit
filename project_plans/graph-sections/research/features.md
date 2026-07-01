# Features Research — Graph Sections with Lazy Sync

**Status**: Research
**Date**: 2026-06-29
**Covers**: git partial clone, Obsidian Sync, Roam/Notion, virtual filesystems, split journals

---

## 1. Git Partial Clone / Sparse Checkout

### How it works

`git clone --filter=blob:none --sparse` performs a **blobless clone**: git downloads all
commit objects and tree (directory) metadata but defers every file's contents (blobs)
until they are actually checked out. Combined with `git sparse-checkout`, only blobs in
the declared cone of interest are materialised on disk.

```bash
git clone --filter=blob:none --sparse <remote>
cd repo
git sparse-checkout set pages/acme-work journals/acme-work
```

GitHub, GitLab, and Gitea all support the Git Wire Protocol v2 object filter needed for
`--filter=blob:none`. Server-side support is required; self-hosted Gitea >= 1.17 and
GitLab CE >= 13.1 have it.

### Performance at 8 000-file scale

For an 8 000-page Markdown graph the blobs are small (average ~5 KB each ≈ 40 MB total).
A blobless clone reduces initial transfer to metadata only — typically a few hundred KB of
tree objects — then fetches only the blobs for the cone. In practice this means:

- Cold clone of a 300-page section ≈ 1.5 MB of blobs + tree metadata → well under 30 s
  on 10 Mbps
- Subsequent `git fetch` only transfers new/changed blobs for subscribed paths
- Pages outside the cone are **never downloaded** as long as nothing touches their paths

Real-world reports (2024–2025) show end-to-end clone time dropping from 30 s (shallow)
to < 5 s (blobless + sparse) for Go monorepos of similar file counts. The savings are
roughly proportional to how small the subscribed cone is relative to the total.

### Failure modes relevant to SteleKit

| Failure mode | Relevance |
|---|---|
| Out-of-cone blob fetches during merge/rebase | Low: SteleKit's sync is file-write-then-push, no complex merges on the work device |
| Diffstat after pull can fetch unexpected blobs | Medium: any `git diff` across section boundaries could pull non-subscribed blobs; SteleKit should never call git diff across the full tree |
| Non-cone pattern matching is O(N×M) | Low: SteleKit uses directory prefixes, which is the native "cone mode" — O(1) matching |
| `git commit -a` silently skips non-sparse files | Medium: if SteleKit ever invokes `git add -A`, files outside the cone will be ignored without error |
| `git sparse-checkout` was experimental until recently | Low risk: cone mode stabilised in git 2.37 (mid-2022); widely available |

### Key takeaway for SteleKit

Using `git clone --filter=blob:none` per section is a proven, server-supported mechanism.
The `pagePathPrefix` and `journalPathPrefix` values in `.stele-sections` map directly to
sparse-checkout cone paths. **SteleKit does not need to implement lazy blob fetching
itself for the Desktop/git path** — git already provides it. The WASM path (GitHub REST
API) needs its own fetch-on-demand logic because WASM cannot run a git client.

---

## 2. Obsidian Sync — Selective Sync

### What Obsidian Sync actually does

Obsidian Sync provides **folder-level exclusion** only. In Settings → Sync → Excluded
folders, you select folders you do not want synced to a given device. The exclusion list
is per-device (each device stores its own list). There is no concept of named "sections"
or subscriptions.

Key properties:
- Excluded folders are stored in the local `<vault>/.obsidian/app.json`, not in git
- Files beginning with `.` are automatically excluded except the `.obsidian` config dir
- Syncing a folder to fewer devices requires manually configuring exclusions on each device

### What Obsidian Sync does NOT do

- No section-level metadata — you cannot say "work pages" vs "personal pages"; you must
  organise files into separate folders yourself
- No lazy content fetch — when a folder is included, Obsidian downloads the full contents
  of all files in that folder eagerly before the vault opens
- No selective journal sync — Obsidian has a single `Daily notes` folder; there is no
  notion of separate work/personal daily notes for the same date
- No manifest committed to the repo — the sync config lives in `.obsidian/`, which is
  excluded from sharing by default

### Lesson for SteleKit

Obsidian's folder-exclusion model validates SteleKit's `pagePathPrefix` /
`journalPathPrefix` directory-partitioning approach. Where Obsidian falls short:
1. It requires users to pre-organise files into folders before sync works — no frontmatter
   assignment
2. Its eager sync means cold-start on a new device still downloads everything in included
   folders — there is no lazy body fetch
3. Split journals for the same date are not supported; users resort to two separate vaults

SteleKit's `stele-section::` frontmatter + lazy content fetch (FR-2, FR-5) directly
address all three gaps.

---

## 3. Roam Research and Notion — Large Workspace Patterns

### Roam Research

Roam is a pure cloud app (no local files). Sync is all-or-nothing — the entire graph is
loaded into the browser on startup. For graphs beyond ~5 000 nodes, users report
noticeable load lag. Roam has no partial sync or section concept.

Failure modes at scale that are relevant to SteleKit's design:
- Roam's whole-graph load pattern is exactly the "O(graph)" anti-pattern documented in
  SteleKit's architecture notes (GraphManager: "standing collector of an unbounded query")
- Users experiencing performance degradation typically abandon Roam or split into multiple
  separate graphs (no cross-linking)

### Notion

Notion uses a progressive page-loading model:
- The sidebar loads page titles and hierarchy eagerly (metadata-first)
- Page body content is fetched on navigation (lazy body fetch)
- Offline access requires pages to have been pre-loaded — it does not download everything

This is architecturally close to SteleKit's FR-5 two-stage load. Notion's pain points:
- Workspaces of 10 000+ pages show load lag on database views with complex filters
- Very large databases (50 000+ rows) still benefit from manual workspace splits
- Offline pages that haven't been pre-visited are unavailable ("pages must be pre-loaded
  for full offline access")

### Lesson for SteleKit

Notion's metadata-first / lazy-body pattern is the right model and maps directly to
SteleKit's Stage 1 (index) / Stage 2 (content) design. The key gap in Notion's
implementation: it fetches all metadata eagerly rather than filtering by a subscription,
so 10 000 page titles still load. SteleKit's section filter (FR-8) cuts Stage 1 to only
the subscribed page set, which Notion does not do.

---

## 4. Virtual Filesystems — CitC and Apple File Provider

### Google CitC (Clients in the Cloud)

CitC is a FUSE-based virtual filesystem that sits on top of Google's Piper monorepo
(9 million files, 86 TB as of 2016). Key design:

- The full file namespace is **always browseable** — `ls` and tab-completion work on any
  path without downloading content
- File contents are downloaded on first read (lazy materialisation)
- Only modified files are stored locally; the average CitC workspace is < 10 local files
- All writes are captured as snapshots, making workspace sharing and restore trivial
- Over 80 % of Piper users use CitC

The mental model: **namespace = fully visible, content = fetched on access**.

This is precisely the model FR-5 describes for WASM: the page index (titles, UUIDs,
section assignments) is fully visible at startup; the Markdown body is fetched from
GitHub REST only when the user navigates to that page.

### Apple iCloud / File Provider Framework

Since macOS High Sierra, iCloud Drive uses `fileproviderd` (File Provider framework) with
**placeholder files**:

- Files appear in Finder with a cloud download icon immediately (full namespace visible)
- The file's actual bytes are "dataless" until the user opens the file (lazy
  materialisation via APFS sparse file support)
- Materialisation is handled by the OS; apps see a normal file path
- APFS supports "sparse files" natively, which underpins the placeholder model

The relevant implementation detail for SteleKit WASM: OPFS (Origin Private File System)
does not support sparse files or placeholder metadata — it stores either the full content
or nothing. SteleKit must implement its own placeholder model in SQLite (a metadata-only
row with `body_cached = false`) to replicate the iCloud placeholder behaviour.

### Lesson for SteleKit

Both CitC and iCloud File Provider confirm the two-layer design:
1. **Namespace layer** — all page titles and metadata, always present, minimal data
2. **Content layer** — Markdown body, fetched lazily on demand, cached after first access

For the Desktop path, git sparse checkout provides the content layer for free. For the
WASM path, the GitHub REST API + OPFS cache is the equivalent. The key open question
(OQ-3) — where to store the index — maps directly to CitC's "full tree metadata always
in memory" vs APFS's "placeholder file in local filesystem".

**Recommendation**: For WASM, store the page index as SQLite metadata-only rows (OQ-3
option 3) rather than a committed `.stele-index.json`, because this avoids a separate
parse pass and integrates with the existing `PlatformDispatcher.DB` + `catchDbError()`
patterns already in the codebase.

---

## 5. Split Journals in PKM Tools

### Current state: no tool does this natively

None of the major PKM tools (Logseq, Obsidian, Roam) support **multiple journals for the
same calendar date** from a single unified graph:

| Tool | Journal model | Work/personal separation |
|---|---|---|
| Logseq | Single `journals/YYYY_MM_DD.md` per day | Requires separate graphs; no cross-linking |
| Obsidian | Single Daily Notes folder; one file per date | Requires separate vaults; no date collision support |
| Roam | Single daily page per date in one graph | Workaround: separate databases; no cross-linking |

### User workarounds in Logseq

The Logseq community has discussed namespace-based journal organisation
(`journals/work/2026-06-29`, `journals/personal/2026-06-29`) as a feature request, but
it is not implemented. Users currently work around the limitation by:

1. **Separate graphs**: switching between a work graph and personal graph — high friction,
   no cross-linking between pages
2. **Single journal with sections**: using heading or tag-based separation within one
   journal file — everything lands on the same device
3. **Inbox blocks**: adding distinct `Work Inbox` / `Personal Inbox` blocks in a single
   daily file — does not help with sync privacy

### SteleKit's approach is novel

FR-3 (section-scoped journals under `journals/<section-id>/YYYY-MM-DD.md`) and FR-10
("New work journal entry" action) implement what no current PKM tool does: **multiple
journal tracks for the same date, from a single graph, with sync filtering per track**.

The journal view design choice (OQ-1 — show one or both journal entries for a date) is
similarly uncharted in existing tools. The safest default: on a device subscribed to
`acme-work`, show only `journals/acme-work/2026-06-29.md` (never the global personal
entry) — aligned with the privacy-first goal of G-2.

---

## Summary

| Research area | Key finding | SteleKit implication |
|---|---|---|
| Git partial clone | `--filter=blob:none` + cone-mode sparse checkout reduces 8 000-page clone to section-only blobs in < 5 s | Desktop path gets lazy content fetch for free via git; WASM needs own fetch-on-demand |
| Obsidian Sync | Folder-exclusion only, eager body sync, no split journals | SteleKit's frontmatter assignment + lazy fetch + split journals fill all three gaps |
| Roam/Notion | Notion's metadata-first / lazy-body model validates FR-5; Roam's whole-graph load = anti-pattern | SteleKit's section filter makes Stage 1 even cheaper than Notion by limiting the index too |
| CitC / iCloud | "Full namespace, lazy content" is the correct VFS pattern; iCloud uses placeholders in FS, CitC in memory | WASM: use SQLite metadata-only rows as placeholders (OQ-3 answer); Desktop: git sparse-checkout |
| Split journals | No PKM tool today supports multi-track journals per date from one graph | FR-3 + FR-10 are genuinely novel; privacy-first default = show only subscribed track |
