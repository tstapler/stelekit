# Logseq-KMP Feature Gap Analysis

## Executive Summary
This document outlines the architectural and feature disparities between the Logseq Core (ClojureScript) and the current Logseq-KMP (Kotlin Multiplatform) implementation. The KMP codebase is in active development, with a focus on core data models, basic graph operations, and editor/UI components. Significant gaps remain in advanced features like queries, whiteboards, and the plugin ecosystem.

## 1. Architectural Overview
The KMP implementation is structured into clear functional domains within `kmp/src/commonMain/kotlin/com/logseq/kmp`:

*   **Core Domain Models**: Located in `model/`, defining fundamental entities like `Block`, `Page`, and cursor state.
*   **Data Persistence**: Managed by `db/` and `repository/`, handling graph loading and writing operations.
*   **Editor Logic**: The `editor/` and `outliner/` modules encapsulate the core editing experience and outliner behavior.
*   **Parsing Engine**: `parser/` and `parsing/` provide the logic for processing Markdown and Org-mode syntax.
*   **User Interface**: `ui/` contains the Compose Multiplatform components for rendering the application.

## 2. Feature Gaps

### 2.1 Missing Functional Features
The following features, central to the Logseq Core experience, are currently missing or significantly under-implemented in KMP:

*   **Whiteboards / Spatial Canvas**:
    *   *Core Status*: Mature feature with infinite canvas, shapes, connectors, and block embedding.
    *   *KMP Status*: **Missing**. No dedicated whiteboard logic, models, or UI components were found.

*   **Advanced Queries**:
    *   *Core Status*: Powerful Datalog engine and DSL for complex property-based retrieval.
    *   *KMP Status*: **Partial/Basic**. While basic search functionality exists, the full query engine and advanced filtering capabilities are not yet ported.

*   **Templates & Macros**:
    *   *Core Status*: robust system for text expansion and dynamic content generation (`template.cljs`).
    *   *KMP Status*: **Missing/Partial**. Logic for template expansion and macro execution is not fully implemented.

*   **Task Management (Scheduled/Deadline)**:
    *   *Core Status*: Deeply integrated date-based task tracking with repeated tasks and calendar views.
    *   *KMP Status*: **Partial**. Complex logic for recurring tasks and date calculations is less visible.

### 2.2 Structural & Platform Gaps

*   **Plugin System**:
    *   *Core Status*: Extensive hook system in `extensions/` allowing third-party JavaScript execution and UI modification.
    *   *KMP Status*: **Embryonic**. `PluginMetadata.kt` exists, but the comprehensive hook architecture and sandbox environment are absent.

*   **Sync & Encryption**:
    *   *Core Status*: specialized handlers for Logseq Sync (CRDT-based) and end-to-end encryption.
    *   *KMP Status*: **In Progress**. Full mirroring of the `rtc` and `data` encryption modules is not yet complete.

*   **Org-mode Parity**:
    *   *Core Status*: First-class support for Org-mode syntax and workflows.
    *   *KMP Status*: **In Progress**. Parser hooks exist, but full feature parity (e.g., complex drawers, priorities, agenda integration) is still being developed.

## 3. Recommendations
1.  **Prioritize Query Engine**: Porting the query logic is critical for feature parity as many user workflows depend on it.
2.  **Define Plugin Strategy**: Decide whether KMP will support existing JS plugins or introduce a new Kotlin/Wasm-based plugin architecture.
3.  **Unified Sync**: Ensure the Sync implementation in KMP is binary-compatible with the existing Core sync infrastructure.
