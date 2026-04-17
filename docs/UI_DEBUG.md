# UI Debugging Guide

We have temporarily disabled the "Hidden Block" check in `BlockRenderer` to diagnose the missing children issue.

### What to check in the UI:

1.  **Do the missing children appear?**
    *   Look for "Rediscovering Paper" under "Listening to...".
    *   Look for other indented blocks.

2.  **Check the Debug Label (L0, L1, etc.)**
    *   **If you see `L1` or `L2` (in red):** The data is CORRECT. The issue was purely the visibility logic hiding them improperly.
    *   **If you see `L0` (in red) for indented blocks:** The data is FLAT. `MarkdownParser` or `GraphLoader` failed to capture the hierarchy.

### Next Steps based on finding:

*   **Result: L1/L2 visible** -> I will revert the visibility check removal and fix the `getDescendantIds` / `collapsedBlocks` logic.
*   **Result: L0 visible** -> I will investigate `MarkdownParser` AST generation and `MarkdownPreprocessor` normalization.
