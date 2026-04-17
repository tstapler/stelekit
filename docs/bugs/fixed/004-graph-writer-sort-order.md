## 🐛 BUG-004: GraphWriter Corrupts Block Order on Save [SEVERITY: Critical]

**Status**: ✅ Fixed (Jan 23, 2026)
**Discovered**: Jan 23, 2026 during Code Analysis
**Fixed**: Jan 23, 2026 - Commit `cbb837380`
**Impact**: Data corruption. Saving a page destroys the block hierarchy and order.

**Reproduction**:
1. Create a page with nested blocks:
   - Block A (pos 0)
     - Block A1 (pos 0)
   - Block B (pos 1)
2. Save the page.
3. `GraphWriter.savePageInternal` sorts all blocks by `position`.
4. Resulting order in file:
   - Block A (pos 0)
   - Block A1 (pos 0)
   - Block B (pos 1)
5. Block A1 is now a sibling of A, or interleaved incorrectly.

**Root Cause**:
`GraphWriter.savePageInternal` sorts the flat list of blocks by `position`:
```kotlin
val sortedBlocks = blocks.sortedBy { it.position }
```
The `position` field appears to be the sibling index (relative to parent), not a global page index. Sorting the entire page's blocks by sibling index interleaves blocks from different branches.

**Files Affected**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphWriter.kt` - Incorrect sorting logic.
- `kmp/src/jvmMain/kotlin/com/logseq/kmp/repository/InMemoryBlockRepository.kt` - `getBlocksForPage` also sorts by position.

**Fix Approach**:
1. **Reconstruct Tree**: `GraphWriter` must reconstruct the tree structure using `parentId`.
2. **DFS Traversal**: Traverse the tree in Depth-First Search (Pre-order) to generate the markdown content.
3. **Sort Siblings Only**: Only sort blocks sharing the same `parentId` by their `position`.

**Verification**:
1. Create a unit test with a nested structure.
2. Save to a mock file system.
3. Verify the output string maintains the correct nesting and order.
