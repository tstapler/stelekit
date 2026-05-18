# Findings: Image & File Attachment Conventions in Markdown Note-Taking Apps

## Summary

Logseq stores all assets in a flat `assets/` folder at the graph root and references them
with a relative `../assets/<name>` path from each page file. Pasted images get a timestamp
suffix (`_image_<unix_ms>_0.png`) while user-dropped files with an existing name also
receive the timestamp to ensure uniqueness. Obsidian uses wikilink embed syntax
(`![[filename.png]]`) by default but also accepts standard markdown `![](path)` with
configurable attachment location and relative-path linking. Both apps treat an image-only
block as a first-class render target (full-width inline image) rather than falling back to
link text. Slash-command image insertion is a well-established pattern pioneered by Notion
and adopted by every major block-based editor: `/image` opens a picker immediately.
SteleKit should match Logseq's conventions exactly for compatibility.

---

## Q1: Logseq Asset Convention

### Folder location
The `assets/` folder sits **at the graph root**, alongside `pages/` and `journals/`.
Example graph layout:

```
my-graph/
  assets/
    photo_1658000515517_0.jpg
    diagram.png
  journals/
    2026_05_15.md
  pages/
    Project Notes.md
  logseq/
    config.edn
```

### Markdown path format
Pages reference assets with a **path relative to the page file**, not the graph root.
Because pages live one level deep (`pages/` or `journals/`), the path is always:

```
../assets/<filename>
```

Full example in markdown:
```markdown
![Project diagram](../assets/diagram_1658000515517_0.png)
[Spec.pdf](../assets/spec_1702000000000_0.pdf)
```

This is confirmed by the Logseq community documentation and the forum thread:
"Understanding the proper way to handle attachements (assets)"
(https://discuss.logseq.com/t/understanding-the-proper-way-to-handle-attachements-assets/8910).

### Image-only block rendering
Logseq renders a block whose **sole content** is an image markdown node as a full-width
inline image, not as link text. This is the expected visual for `![alt](../assets/...)`.

---

## Q2: Duplicate Filename Handling

### Logseq naming convention
When a file is pasted or dropped, Logseq generates a new filename:

```
<original-stem>_<unix-timestamp-ms>_<counter>.<ext>
```

For screenshots/clipboard pastes the stem is `_image`:

```
_image_1657174168318_0.png
```

For user-dropped/uploaded files the original stem is preserved:

```
photo_1658000515517_0.jpg
diagram_1702000000001_0.png
```

The `_0` counter increments if two assets are saved within the same millisecond; in
practice this is almost always `_0`. The timestamp guarantees global uniqueness without
checking for existing files.

**Source**: GitHub issue #5956 "Is there a way to choose image naming convention?"
(https://github.com/logseq/logseq/issues/5956) and forum post
https://discuss.logseq.com/t/optional-image-naming-convention/8606

### Requirements doc convention
The SteleKit requirements.md specifies a simpler suffix approach:
`photo-1.jpg`, `photo-2.jpg`, etc. This is a pragmatic alternative — human-readable,
no clock dependency — and is sufficient for the stated success criteria.
The Logseq timestamp approach has the disadvantage of being opaque and
fragmented across searches. The suffix counter approach is preferable for SteleKit
unless Logseq binary compatibility of filenames (not just paths) is required.

### Current Logseq behavior: no deduplication by content
Pasting the same image twice creates two files with different timestamps, even if content
is identical. Issue #7169 proposed file-fingerprinting to fix this; it was closed as a
feature request. SteleKit does not need to replicate this deficiency.

---

## Q3: Obsidian Attachment Handling

### Default embed syntax
Obsidian defaults to **wikilink embed syntax**:
```
![[image.png]]
![[image.png|250]]          # width hint
![[image.png|100x145]]      # width×height
```

Standard markdown images (`![alt](path)`) are also accepted, and the path format is
configurable: shortest-path, relative, or absolute vault-root.

### Attachment folder
Obsidian allows choosing: vault root, a fixed folder anywhere in the vault, or a
subfolder under the current note's folder. Default is vault root. Users commonly
configure an `/Attachments` or `/assets` folder. There is **no fixed convention** —
it is user-configurable via Settings → Files & Links → "Default location for new
attachments."

### Duplicate filename handling
Obsidian appends a numeric suffix before the extension when a file with the same name
exists in the target folder: `image.png` → `image 1.png` → `image 2.png` (space
before number, not hyphen). This is OS-style deduplication.

### Relevance to SteleKit
Obsidian's approach is irrelevant for Logseq compatibility. SteleKit must use Logseq's
`../assets/<filename>` convention, not Obsidian's wikilinks.

---

## Q4: Image-Only Block Detection

### The pattern in outliners
Both Logseq and Obsidian render a block (or paragraph) whose **entire content** is a
single image node as a full-width inline image. Mixed content (text + image) falls back
to inline rendering within the text flow.

Detection logic: after AST parsing, if a block's top-level content is a single `ImageNode`
with no sibling inline nodes, dispatch to `ImageBlock` instead of `MarkdownTextBlock`.

This matches what the existing `ImageBlock.kt` composable is built for. The requirements
doc's reference to `BlockItem.kt` line ~388 confirms this dispatch point.

### Logseq specifics
Logseq's CLJS block renderer checks if a block's content parsed to a single image token
and switches to its `<Image>` component. If there is any other text content in the block,
it renders inline within the paragraph as a smaller image alongside the text.

### Obsidian specifics
Obsidian treats `![[image.png]]` on its own line as a block embed that renders full-width.
A bare `![](url)` on its own paragraph also renders full-width in preview mode.

---

## Q5: Slash Command UX Patterns

### Industry standard
Every major block-based editor (Notion, Roam Research, Craft, Coda, Linear) implements
`/` to trigger a command palette. Typing `/image` or `/photo` filters to an image
insertion option. The UX is:

1. User presses `/` while cursor is in a block.
2. A floating palette appears above or below the cursor.
3. User types `image` to filter.
4. Selecting "Image" immediately opens the platform file picker / camera sheet.
5. After picking, the file is copied to `assets/` and the markdown is inserted.

### Notion's approach
`/image` opens an inline embed UI with three tabs: Upload, Embed link, Unsplash. This
is more complex than needed for SteleKit.

### Recommended UX for SteleKit
- `/image` in the slash command triggers the same action as the toolbar attachment button.
- On Android: bottom sheet with "Camera" and "Gallery" options.
- On Desktop: native JFileChooser / compose-multiplatform file picker.
- After pick: copy file to `<graph_root>/assets/<stem>-<counter>.<ext>`, insert
  `![<filename>](../assets/<filename>)` at the cursor position.

---

## Trade-off Matrix: Path Conventions

| Convention | Logseq-compatible | Human-readable | No clock dep | Works offline |
|------------|:-----------------:|:--------------:|:------------:|:-------------:|
| `../assets/<stem>_<ms>_0.<ext>` (Logseq) | Yes | Low | No | Yes |
| `../assets/<stem>-<n>.<ext>` (requirements) | Partial* | High | Yes | Yes |
| `../assets/<stem>.<ext>` (no dedup) | Yes | High | Yes | Yes (risk: silent overwrite) |
| Obsidian wikilinks `![[file]]` | No | High | Yes | Yes |

*Logseq can open files named `photo-1.jpg` fine; it just doesn't generate them itself.

**Recommendation**: Use `<stem>-<n>.<ext>` suffix counter as specified in requirements.md.
It is compatible enough (Logseq reads any path in `../assets/`), human-readable, and
avoids the timestamp opaqueness. Check for file existence before writing; increment `n`
from 1 until a free name is found.

---

## Risk and Failure Modes

| Risk | Condition | Mitigation |
|------|-----------|------------|
| Silent overwrite | No collision check, same-named file pasted twice | Always check `File.exists()` before copy; use suffix counter |
| Broken paths after graph move | Absolute paths stored in markdown | Always use relative `../assets/` |
| iOS: `../assets/` path traversal blocked | Some file systems reject `..` in URLs | Resolve to absolute path before passing to Coil fetcher |
| Assets folder not created | First-ever attachment in graph | Create `assets/` directory with `mkdirs()` if absent |
| Large file copied on main thread | No dispatcher guard | Always copy on `PlatformDispatcher.IO` |

---

## Recommendation

**Match Logseq's path convention exactly**: store files at `<graph_root>/assets/<filename>`
and write `../assets/<filename>` in the markdown. Use the suffix-counter dedup strategy
(`photo-1.jpg`, `photo-2.jpg`) as specified in requirements.md — it is simpler than
Logseq's timestamp approach and equally correct for compatibility.

Implement `SteleKitAssetFetcher` per platform (JVM, Android, iOS) as a Coil `Fetcher`
that resolves `../assets/<filename>` by reading `LocalGraphRootPath`, navigating one
directory up from the pages folder, then appending `assets/<filename>`.

For the slash command, follow the Notion/Roam model: `/image` → immediate file picker
(no intermediate dialog). Post-pick: copy → update block content → dismiss.

---

## Sources

- Logseq community thread on attachments:
  https://discuss.logseq.com/t/understanding-the-proper-way-to-handle-attachements-assets/8910
- Logseq GitHub issue #5956 (image naming convention):
  https://github.com/logseq/logseq/issues/5956
- Logseq feature request (optional image naming):
  https://discuss.logseq.com/t/optional-image-naming-convention/8606
- Logseq issue #7169 (file fingerprint dedup):
  https://github.com/logseq/logseq/issues/7169
- Logseq issue #5636 (drag-drop duplicate asset):
  https://github.com/logseq/logseq/issues/5636
- Obsidian embed syntax documentation:
  https://obsidian.md/help/embeds
- Obsidian forum — markdown images vs wikilinks:
  https://forum.obsidian.md/t/markdown-for-images-wikilinks-for-links/71718
- Notion slash command guide:
  https://www.notion.com/help/guides/using-slash-commands
- CKEditor 5 slash command UX:
  https://ckeditor.com/blog/slash-commands/
