# ADR-003: Recent Pages List Cap and Display Count

**Status**: Accepted  
**Date**: 2026-04-16  
**Feature**: Recent Pages  

---

## Context

Logseq shows the last ~10 pages in the "Recent" sidebar section. SteleKit's current implementation stores up to 20 UUIDs internally but there is no explicit display cap — all 20 are passed through to the sidebar. Two questions:

1. How many items should be **stored** in `PlatformSettings`?
2. How many items should be **shown** in the sidebar?

## Decision

- **Store** up to **20** recent UUIDs (existing behaviour, unchanged).
- **Display** up to **10** items in the "Recent" sidebar section.

## Rationale

- Storing 20 gives a buffer: if the user visits a page that has since been deleted (UUID resolves to null), the display list still shows 10 valid entries even after filtering.
- Showing 10 matches Logseq parity and fits within the sidebar scroll area without overwhelming the user.
- A hard display cap of 10 is cheaper than a dynamic height calculation and avoids the sidebar becoming a long scroll target.

## Consequences

- **Positive**: Logseq parity on display count (10 items).
- **Positive**: Deleted-page resilience from storing 20 but displaying 10.
- **Negative**: Users who want more than 10 recents must use "All Pages" or Search.

## Patterns Applied

- Progressive disclosure (show 10; store 20 for resilience)
