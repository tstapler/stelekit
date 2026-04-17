# KMP Migration Master Plan: Logseq Architecture Evolution

## 1. Executive Summary
This document outlines the comprehensive strategy for migrating the Logseq application from its current ClojureScript (CLJS) architecture to a Kotlin Multiplatform (KMP) architecture. The goal is to unify business logic across platforms (Desktop, Mobile, Web), improve performance through native execution, and leverage the modern Android/JVM ecosystem while maintaining the flexibility of the graph-based knowledge management system.

## 2. Architectural Vision

### Current State (Legacy)
- **Language**: ClojureScript (targeting JavaScript).
- **UI Framework**: React (Reagent/Rum).
- **State Management**: Datascript (In-memory Datalog DB) + Reactive Atoms.
- **Persistence**: File-system based (Markdown/Org-mode) with direct file watchers.
- **Platform**: Electron (Desktop), Capacitor (Mobile), Web.

### Target State (KMP)
- **Language**: Kotlin (Common code for logic, Platform-specific for drivers).
- **UI Framework**: Compose Multiplatform (Desktop/Android/iOS/Web).
- **State Management**: Unidirectional Data Flow (MVI/MVVM) backed by Coroutines/Flow.
- **Persistence**: Hybrid Architecture.
    - **Primary**: SQLite (via Room/SQLDelight) for indexing and fast queries.
    - **Session**: In-memory graph cache (optimized).
    - **Source of Truth**: Markdown/Org-mode files (synced via KMP File System logic).
- **Interop**: Minimal. New KMP components replace legacy CLJS components entirely.

## 3. Migration Strategy: Component Replacement
We will adopt a "Re-implement and Delete" strategy. We will not maintain a complex long-term bridge between CLJS and KMP. Instead, we will build complete vertical slices in KMP, verify them, and then delete the corresponding CLJS code.

### Phases
1.  **Phase 1: Foundation & Infrastructure**
    - Establish KMP module structure.
    - Port core data models (Block, Page, Graph).
    - Implement the SQLite persistence layer.
    - *Goal*: A headless KMP engine that can read/write Logseq graphs.

2.  **Phase 2: Core Domain Logic (Headless)**
    - Port "Search & Query" logic.
    - Port "Parser & Markdown" handling.
    - Port "Graph" algorithms.
    - *Verification*: Run KMP logic in parallel (shadow mode) or via test harness to ensure output matches CLJS.

3.  **Phase 3: UI Components (Vertical Slices)**
    - Re-implement "Core Editor" in Compose.
    - Re-implement "Graph View" in Compose.
    - *Strategy*: Build the new UI. Once ready, swap the entry point to the new KMP component and **DELETE** the old CLJS component.

4.  **Phase 4: Cleanup**
    - Remove all CLJS dependencies and build tooling.
    - Switch fully to KMP entry points.

## 4. Feature Decomposition (Sub-Epics)

The migration is broken down into the following core feature sets. Each has a dedicated migration plan.

| Feature | Priority | Complexity | Status | Plan Link |
| :--- | :--- | :--- | :--- | :--- |
| **Core Editor & Blocks** | Critical | High | Planning | [View Plan](migration_core_editor.md) |
| **Pages & Journals** | Critical | Medium | Planning | [View Plan](migration_pages_journals.md) |
| **Search & Queries** | High | High | Planning | [View Plan](migration_search_queries.md) |
| **Graph Visualization** | Medium | High | Planning | [View Plan](migration_graph_visualization.md) |
| **Asset Management** | Medium | Low | Pending | [View Plan](migration_assets.md) |
| **Extensions/Plugins** | Low | High | Pending | [View Plan](migration_plugins.md) |
| **Integrations (PDF/Zotero)**| Low | Medium | Pending | [View Plan](migration_pdf.md) |
| **Flashcards/SRS** | Low | Medium | Pending | [View Plan](migration_flashcards.md) |
| **Git/Version Control** | Medium | High | Pending | [View Plan](migration_git_sync.md) |

## 5. Risk Assessment & Mitigation

### 🔴 Critical Risks
1.  **Feature Parity Gaps**:
    - *Risk*: Subtle behaviors in the CLJS editor (e.g., specific keybindings, cursor movements) are lost during re-implementation.
    - *Mitigation*: Automated "Golden Master" tests. Record user sessions in CLJS and replay against KMP logic. Do not delete CLJS code until parity is confirmed.

2.  **Data Corruption during Sync**:
    - *Risk*: If both systems try to write to the file system during the transition period.
    - *Mitigation*: Ensure strict ownership. When KMP takes over a feature (e.g., Editor), it becomes the *sole* writer.

3.  **Migration Fatigue**:
    - *Risk*: The "Re-implement" approach takes time before user value is seen.
    - *Mitigation*: Focus on high-impact components first (Editor, Search). Release internal alpha builds frequently.

## 6. Quality Standards for Migration
- **Logic**: 100% Unit Test coverage for ported domain logic.
- **UI**: Screenshot testing for Compose components.
- **Architecture**: Adherence to Clean Architecture (Domain, Data, Presentation layers).
- **Documentation**: Every ported module must have KDoc and an updated Architecture Decision Record (ADR).
- **Cleanup**: Legacy code MUST be deleted after verification.
