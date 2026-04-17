# Task: Fix GraphWriter Hierarchy Corruption (BUG-004)

## 1. Overview

### User Value
Ensures that saving a page does not destroy the block hierarchy. This is critical for data integrity and is a prerequisite for any editing functionality.

### Success Metrics
- **Data Integrity**: Saving a page with nested blocks preserves the parent-child relationships and sibling order.
- **Verification**: Unit tests confirm that the output markdown matches the expected structure.

### Scope
- **In Scope**:
    - Modify `GraphWriter.savePageInternal` to correctly traverse the block tree.
    - Ensure sibling sorting is local to the parent.
- **Out of Scope**:
    - Performance optimizations (unless critical).
    - Other GraphWriter features.

## 2. Atomic Task Decomposition

### Task 1: Fix GraphWriter Sorting Logic [2h]

**Objective**: Rewrite the block serialization logic to use a DFS traversal instead of a global sort.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphWriter.kt` (modify)
- `kmp/src/commonTest/kotlin/com/logseq/kmp/db/GraphWriterTest.kt` (create/modify)

**Context**:
- The current implementation sorts all blocks by `position` globally, which interleaves blocks from different branches.
- We need to build a tree structure in memory (or traverse it if it exists) and then write it out.
- Since `GraphWriter` receives a flat list of blocks, we first need to group them by `parentId`.

**Implementation Plan**:
1.  Group blocks by `parentId`.
2.  Find root blocks (parentId is null or matches pageId - need to check schema).
3.  Recursively visit children:
    -   Sort children by `position`.
    -   Append block content to the output.
    -   Recurse.

**Code Sketch**:
```kotlin
private fun buildMarkdown(blocks: List<Block>): String {
    val childrenMap = blocks.groupBy { it.parentId }
    val rootBlocks = childrenMap[null] ?: emptyList() // Or however roots are identified
    val sortedRoots = rootBlocks.sortedBy { it.position }
    
    val sb = StringBuilder()
    // ... append frontmatter ...
    
    fun traverse(block: Block, level: Int) {
        // append block with indentation
        val children = childrenMap[block.id]?.sortedBy { it.position } ?: emptyList()
        children.forEach { traverse(it, level + 1) }
    }
    
    sortedRoots.forEach { traverse(it, 0) }
    return sb.toString()
}
```

**Success Criteria**:
-   Unit test with:
    -   Root A (pos 0)
        -   Child A1 (pos 0)
    -   Root B (pos 1)
-   Output should be:
    ```markdown
    - Root A
      - Child A1
    - Root B
    ```
-   Current buggy output (likely):
    ```markdown
    - Root A
    - Child A1
    - Root B
    ```

**Dependencies**: None.

**Status**: ⏳ Pending
