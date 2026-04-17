# ADR-008: Merge Content Strategy — Append with Separator Block

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler  
**Feature**: Knowledge Graph Maintenance — Story 3 (Duplicate Page Detector and Merge Workflow)

---

## Context

When two pages are merged, the source page's blocks must be incorporated into the target page without silently dropping content. Three strategies were considered:

**Option A — Append with separator block**: all root-level blocks from the source page are appended to the end of the target page, preceded by a heading block (`## (Merged from [[SourceName]])`). The separator makes the provenance of the merged content explicit and allows the user to reorganise or delete it later.

**Option B — Interleave by creation timestamp**: blocks from both pages are merged and re-sorted by `created_at`. This produces a chronological view but breaks the logical structure of both pages. It is the most disruptive to the user's existing organization.

**Option C — Create a redirect block only**: instead of moving blocks, the source page's content is left in place and a redirect block is added to the target page: `"See also: [[SourceName]]"`. The source page is kept but marked as a redirect. This is non-destructive but does not actually merge the content — the source page still exists and the "duplicate" problem is not resolved.

**Option D — Replace source page with a redirect and archive blocks**: source blocks are moved to a hidden "Archive" namespace page; a redirect block is added to the target. Clean from a graph structure perspective, but introduces a hidden page that clutters the namespace.

---

## Decision

**Option A — Append with separator block** is adopted.

Rationale:

- Append with separator is the simplest to implement correctly. Re-parenting root blocks is a straightforward `UPDATE blocks SET page_uuid = targetUuid, position = N + i` operation.
- The separator block makes the merge auditable. Users can see exactly what came from where and decide to restructure or delete the appended section.
- The `position` and `left_uuid` chain for the appended blocks can be computed in a single pass before the DB transaction opens, avoiding complex in-transaction arithmetic.
- Option B (interleave) destroys intentional page structure for both the source and target pages — unacceptable for a knowledge management tool.
- Option C (redirect only) does not resolve the duplicate; it just adds a link. It is the right model for intentional redirects (e.g., after a rename), not for merging actual duplicate pages.
- Option D adds complexity (hidden archive namespace) without clear user benefit over Option A.

---

## Separator Block Specification

The separator block inserted before the appended content has:
- `content`: `"## (Merged from [[${sourcePage.name}]])"` — a level-2 heading with a wikilink to the source page name (which will be rewritten to `[[targetName]]` by `BacklinkRenamer` in the same transaction, so the final text reads `"## (Merged from [[TargetName]])"` — a self-referential note. Consider using the source name pre-rename as a plain string: `"## (Merged from: ${sourcePage.name})"` without a wikilink, to preserve historical context. This is the preferred approach.
- `parent_uuid`: null (root block)
- `level`: 0
- `position`: last position in target page + 1
- `left_uuid`: uuid of the last existing block in the target page
- `created_at` / `updated_at`: current timestamp
- `uuid`: generated fresh

---

## Consequences

**Positive**:
- Non-destructive — no block content is ever discarded.
- Auditable — separator block documents the merge event and source.
- Reversible at the content level — user can delete the appended section if they decide the merge was wrong (though the source page and its backlinks are already updated).
- Simple to implement and test.

**Negative / Risks**:
- The appended blocks appear at the bottom of what may be a large, well-structured target page. Users must manually re-integrate the appended content into the target's structure. This is intentional — the tool does not know the right place to insert content in the user's own organization.
- Merge is not undoable in v1 (see Known Issue Bug 4 in the main plan). The "cannot be undone" warning in the merge dialog is a mandatory mitigation.
- If the source page had child blocks nested under its root blocks, the entire subtree is appended (not just root blocks). This is correct — the full outline structure of the source is preserved.
