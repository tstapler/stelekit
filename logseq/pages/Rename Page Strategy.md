# Rename Page Strategy

Strategy for implementing robust page renaming in a local-first, hybrid-database architecture.

## Core Principles

1.  **File System as Source of Truth**: The Markdown file on disk is the master record.
2.  **Eventual Consistency**: If the DB falls out of sync with the file system, a re-index operation restores truth.
3.  **Hybrid Processing**: Leverage SQL for coarse filtering and In-Memory (Kotlin) for precise text manipulation.

## Implementation Strategy

### 1. Consistency Protocol
To handle the "Dual Write" problem (File + DB):
- **Step 1**: Rename File. If fail -> Abort.
- **Step 2**: Rename DB Entity. If fail -> Log error, trigger background re-index.
- **Step 3**: Update Backlinks. If fail -> Log error, user might see broken links until manual fix or re-index.

### 2. Backlink Update Algorithm
Efficiently updating `[[Old Name]]` -> `[[New Name]]`:
- **Filter**: `SELECT * FROM blocks WHERE content LIKE '%[[Old Name]]%'` (SQLDelight)
- **Process**: Iterate results in memory, apply Regex replacement.
- **Persist**: Save updated blocks to DB and File.

### 3. Case Sensitivity
Handling "Page" -> "page" on Windows/macOS:
- **Detection**: `oldName.equals(newName, ignoreCase = true)`
- **Action**: Use intermediate temp file: `A` -> `A_tmp` -> `a`.

## Architecture Components

- **PageService**: Orchestrator. Manages the transaction script.
- **GraphWriter**: Handles File System operations (including the 2-step rename).
- **PageRepository**: Handles DB entity renaming.
- **BlockRepository**: Handles finding and updating referencing blocks.

## References
- [[Knowledge Synthesis - 2026-01-21]] - Initial research and synthesis.

## Tags
#[[Architecture]] #[[Feature Specification]] #[[KMP]]
