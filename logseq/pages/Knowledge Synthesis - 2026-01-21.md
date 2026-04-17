# Knowledge Synthesis - 2026-01-21

Daily consolidation of synthesized knowledge from external sources.

---

## Rename Page Strategy in Local-First KMP

**Context**: Developing a robust "Rename Page" feature for Logseq's Kotlin Multiplatform (KMP) migration. The system uses a hybrid architecture (Datascript in-memory + SQLite persistence) and treats Markdown files as the ultimate source of truth.

**Key Findings**:

### 1. Consistency & Failure Handling
*   **Source of Truth**: The file system (Markdown files) is authoritative.
*   **Operation Order**: Always perform **File Rename** first, then **Database Update**.
*   **Failure Scenarios**:
    *   *File Rename Fails*: Abort immediately. No DB changes. User sees error.
    *   *DB Update Fails (after File Rename)*: This creates a temporary inconsistency.
    *   *Recovery*: Rely on the **Re-index** mechanism. Since the file is renamed on disk, the next graph re-scan will correctly identify the new page and remove the old one.
    *   *Mitigation*: Log errors heavily to facilitate debugging, but accept this "eventual consistency" fallback to avoid complex Two-Phase Commit (2PC) systems.

### 2. Efficient Backlink Updates
*   **Challenge**: Updating thousands of `[[Old Name]]` references to `[[New Name]]` without loading the entire graph into memory.
*   **Hybrid Approach (ADR-002)**:
    1.  **SQL Filtering**: Use SQLite to find candidate blocks efficiently:
        ```sql
        SELECT * FROM blocks WHERE content LIKE '%[[Old Name]]%'
        ```
    2.  **Memory Processing**: Load only these candidate blocks into memory.
    3.  **Regex Replacement**: Perform precise text replacement in Kotlin/JVM code. This ensures correctness (handling edge cases) that raw SQL `REPLACE` might miss.
    4.  **Batch Save**: Write updated blocks back to DB and File.

### 3. Case Sensitivity Handling
*   **Problem**: Renaming "Page" to "page" on case-insensitive file systems (macOS, Windows) often fails or does nothing if treated as a simple move.
*   **Strategy**:
    *   **Detection**: Check if `oldName.lowercase() == newName.lowercase()`.
    *   **Two-Step Rename**: If a case-only rename is detected:
        1.  Rename `Page` -> `Page_temp_[UUID]`
        2.  Rename `Page_temp_[UUID]` -> `page`
    *   **Normalization**: Ensure the DB stores/queries names in a normalized form (e.g., lowercase) for lookups to prevent duplicate entities.

**Related Concepts**: [[Local-First Architecture]], [[Hybrid Database]], [[Data Consistency]], [[File System Operations]]

**Source**: Internal Architecture Docs (`docs/tasks/page-management.md`), `SqlDelightPageRepository.kt`

**Tags**: #[[Architecture]] #[[KMP]] #[[Database]] #[[Refactoring]]

---
