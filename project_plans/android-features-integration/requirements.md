# Requirements: Android Features Integration

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-22

## Problem Statement

SteleKit Android users have no way to capture notes quickly without opening the full app, and no way to receive content shared from other apps into their graph. The integration addresses both directions: glanceable/interactive access from the home screen and quick settings, and capturing inbound content (URLs, text, images) from the Android share sheet.

## Success Criteria

All three Android platform features are implemented and available in a release build within 3 months:
1. Home screen widget — quick note capture (not display-only)
2. Quick Settings Tile — fast capture or navigation from the notification shade
3. Share target — SteleKit appears in the Android share sheet and receives text/URLs/images into the active graph

## Scope

### Must Have (MoSCoW)
- **Quick capture widget**: A home screen widget that lets users write a new note/block directly — not just a display widget
- **Quick Settings Tile**: A tile in the notification shade for fast capture or graph navigation
- **Share target**: SteleKit registers as an Android share target to receive text, URLs, and images from other apps into the active graph

### Out of Scope
- iOS equivalent widgets or share extensions (Android-only project)
- Wear OS tiles or Wear OS support
- Tablet/foldable layout optimization specific to widgets
- Rich media (image/video) rendering inside widgets
- Server-side push or background cloud sync to power widgets
- Widget customization UI (choosing which graph/page a widget targets — post-MVP)

## Constraints

- **Tech stack**: Android-only; implementation lives in `androidMain` — no expectation of KMP sharing with other targets. Uses Jetpack Glance for widgets, Tiles API (requires API 33+), and standard Android Intent filters for share targets.
- **Backend**: All data must come from the local graph on-device — no new cloud or backend service.
- **Timeline**: All three features shipped within 3 months.
- **Team**: Solo developer.
- **Dependencies**: Existing `GraphManager` / `RepositorySet` must be accessible from widget/tile processes (likely via a lightweight in-process binding or ContentProvider).

## Context

### Existing Work
- No prior research or implementation attempts for these features.
- The existing app has a `GraphManager` that loads and manages graphs from disk; any widget or tile must read from the same graph data without owning a full `RepositorySet` lifecycle.

### Stakeholders
- Tyler Stapler (sole developer and primary user)
- SteleKit users on Android who want platform-native integration

## Research Dimensions Needed

- [ ] Stack — evaluate Glance vs. RemoteViews for widgets; Tiles API vs. Quick Settings shortcut; share target intent filters and MIME type handling
- [ ] Features — survey comparable note-taking apps (Obsidian, Notion, Bear) for their widget/tile/share UX patterns
- [ ] Architecture — how to expose `GraphManager` data to widget/tile processes; write-back path from widget capture to graph
- [ ] Pitfalls — known failure modes: Glance process isolation, Tile API 33 minimum, share sheet MIME negotiation, widget update lifecycle
