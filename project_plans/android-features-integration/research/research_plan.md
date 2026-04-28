# Research Plan: Android Features Integration

**Created**: 2026-04-22
**Input**: `project_plans/android-features-integration/requirements.md`

## Subtopics

| # | Subtopic | Search Cap | Output File |
|---|----------|------------|-------------|
| 1 | Stack | 5 searches | `findings-stack.md` |
| 2 | Features | 5 searches | `findings-features.md` |
| 3 | Architecture | 5 searches | `findings-architecture.md` |
| 4 | Pitfalls | 5 searches | `findings-pitfalls.md` |

---

## Subtopic 1: Stack

**Question**: What are the best APIs and libraries for implementing Android Widgets, Quick Settings Tiles, and Share targets in a Kotlin Multiplatform project?

**Search strategy**:
- Jetpack Glance vs RemoteViews for home screen widgets
- Compose Glance widget API (Glance 1.x)
- Quick Settings Tile API (TileService, API 33+ Tile state)
- Android share target / ShareSheet (ACTION_SEND, MIME types, direct share targets)
- KMP / Android-only module structure for these features

**Trade-off axes**: API maturity, Compose compatibility, minimum API level, KMP isolation, testing support

---

## Subtopic 2: Features

**Question**: How do comparable note-taking apps implement widgets, tiles, and share targets? What UX patterns should we adopt or avoid?

**Search strategy**:
- Obsidian Android widget / share sheet implementation
- Notion Android widget / Quick Settings tile
- Bear / Drafts / Joplin Android widget patterns
- "quick capture widget android note taking" UX patterns
- Material Design guidelines for widgets and tiles

**Trade-off axes**: Capture speed (taps to note), discoverability, visual complexity, offline reliability

---

## Subtopic 3: Architecture

**Question**: How should widget/tile/share-target processes access `GraphManager` data in a KMP Android app? What's the write-back path from widget capture to the graph?

**Search strategy**:
- Android widget process isolation and data access patterns
- ContentProvider vs direct SQLite access from AppWidgetProvider
- Jetpack Glance state management and data passing
- Android WorkManager for deferred widget writes
- KMP Android module interop for widget/tile code

**Trade-off axes**: Process safety, data freshness, write latency, complexity, testability

---

## Subtopic 4: Pitfalls

**Question**: What are the most common failure modes for Android Widgets, Quick Settings Tiles, and Share targets? What do teams get wrong?

**Search strategy**:
- Glance widget crash / ANR known issues
- Quick Settings Tile API 33 compatibility issues
- Android share target MIME negotiation problems
- Widget update lifecycle pitfalls (RemoteViews, AppWidgetManager)
- "android widget production issues" developer post-mortems

**Trade-off axes**: Severity, frequency, mitigation availability, detectability
