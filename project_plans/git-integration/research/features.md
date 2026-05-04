# Feature Research — Git-Backed Note-Taking Sync UX

_Research date: 2025-05-02_

---

## 1. Working Copy (iOS Git Client)

**App:** [Working Copy](https://workingcopy.app/) — premium iOS/iPadOS git client  
**Developer:** Anders Borum  
**Relevance:** The gold standard for git UX on mobile; many note-taking apps (iA Writer, Ulysses) use it as their git backend via iOS File Provider extension.

### Conflict Resolution UX

Working Copy implements a **three-panel merge editor** for text file conflicts:

- **Center panel:** Content both versions agree on
- **Left panel ("ours"):** Local changes
- **Right panel ("theirs"):** Incoming remote changes

**Interaction model:** Users swipe individual conflict hunks left or right toward the center panel to accept them into the merged result. Unresolved chunks remain at the border until the user makes a decision. A progress indicator shows how many chunks remain unresolved.

**Quick resolution shortcut:** Tapping the branch name header (labeled `HEAD` or the merge source) accepts all chunks from that version at once — useful for "just take all of theirs" scenarios.

**Binary files:** A simple two-option selector (our version vs. their version) with a thick border indicating the selected choice.

**Manual fallback:** The Content tab exposes the raw conflicted file with `<<<<<<<`/`=======`/`>>>>>>>` markers. A "Resolve" button marks the file resolved once the user manually removes the markers.

### Sync Workflow UX

- Fetch/push always requires explicit user action (no auto-sync)
- Status badges on repos/branches show ahead/behind commit counts
- SSH and HTTPS both supported; SSH keys managed via iOS Files app
- The app integrates with iOS Shortcuts for automation

### Key Design Lessons for SteleKit

1. **Chunk-level resolution** (not file-level) is the right granularity for text conflicts
2. **Progressive disclosure:** Show only unresolved chunks; resolved ones collapse
3. **Quick accept-all** is essential — most conflicts in personal wikis should be trivially resolved
4. **Never block the user mid-resolution:** persist partial resolution state if the app is backgrounded

---

## 2. Obsidian Git Plugin

**Plugin:** [obsidian-git](https://github.com/Vinzent03/obsidian-git) by Vinzent03  
**Stars:** ~5000+ (actively maintained as of 2024)

### Auto-Commit and Sync

The plugin implements a **commit-and-sync** loop:
1. Stage all changes
2. Commit with auto-generated message (timestamp-based or custom template)
3. Pull (merge or rebase strategy, configurable)
4. Push

**Schedule options:**
- "Auto commit-and-sync interval" (e.g., 10 minutes)
- "Auto commit-and-sync after stopping file edits" — debounced trigger after the user stops typing

This matches SteleKit's FR-3.4 (commit-per-session / debounced commit model) almost exactly.

### Conflict Resolution

**Critical finding: Obsidian Git has NO built-in conflict resolution UI as of 2024.**

There is an open feature request ([Issue #803](https://github.com/Vinzent03/obsidian-git/issues/803)) requesting built-in conflict handling. Currently when a merge conflict occurs:
- Git conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) are injected into the Markdown file
- The user must manually edit the raw file to resolve them
- There is no visual diff, no chunk-by-chunk workflow

This is the primary UX failure mode that SteleKit should avoid. SteleKit's FR-4 (conflict resolution screen with side-by-side diff) would represent a significant improvement over the current state of the art.

### UI Components

- **Source Control View:** Lists staged/unstaged files with commit/stage/discard actions
- **History View:** Commit log with diff viewing per commit
- **Diff View:** Shows per-file changes as a unified diff (desktop only)
- **Line-by-line editor gutter indicators:** Added/modified/deleted lines highlighted inline

### Mobile Limitations

The plugin documents mobile as **"highly unstable"**:
- No SSH authentication (mobile only works with HTTPS)
- Memory constraints on large repos cause crashes during clone/pull
- No rebase merge strategy on mobile
- No submodule support on mobile

### Key Design Lessons for SteleKit

1. **Auto-commit after edit stop** (debounce) is the right trigger model — matches user mental model of "it just saves"
2. **The gap: no conflict UI** — this is the biggest pain point and SteleKit's opportunity
3. **Mobile HTTPS-only is acceptable** for the majority of users (SSH is a power user feature)
4. **Separate commit from sync** — commit can be automatic; sync (push/pull) should be separable

---

## 3. GitJournal (Flutter, Open Source)

**App:** [GitJournal](https://gitjournal.io/) — mobile-first Markdown notes integrated with Git  
**GitHub:** https://github.com/GitJournal/GitJournal  
**Language:** Dart (~95% of codebase)

### Architecture for Git Sync

GitJournal uses a **pure-Dart git implementation** called `gitdart` — a git library written entirely in Dart (no native binaries, no JGit). This avoids the need for native library distribution. The organization also maintains Dart bindings for go-git as an alternative.

**Git operation model:**
- Full git operations in-app: clone, fetch, merge, commit, push
- SSH authentication using standard SSH keys
- Works with any Git hosting provider (GitHub, GitLab, custom)

### Conflict Handling

GitJournal's approach to merge conflicts is **merge-strategy-based** rather than UI-based:
- For journal files (one entry per file, daily), conflicts are rare because each day is a separate file
- When conflicts do occur, the app shows an error notification and requires manual resolution outside the app
- The file-per-note model is the primary conflict avoidance strategy

**Key insight:** The one-file-per-note + one-commit-per-sync model dramatically reduces conflict frequency. SteleKit's wiki is organized similarly (one Markdown file per page), so this design principle applies directly.

### Background Sync Architecture

GitJournal does not implement persistent background sync (no WorkManager equivalent). Sync is triggered:
- On app launch
- Manually by the user
- Via a "sync on save" option

### Key Design Lessons for SteleKit

1. **One file per note** is the best conflict-avoidance strategy — already true in SteleKit
2. **Conflict frequency can be minimized** through commit-per-session model (only commit changes, not re-write unchanged files)
3. **Pure-library git (no binary dependency)** is the ideal — kgit2/libgit2 for SteleKit approximates this
4. **SSH support on mobile is achievable** — GitJournal does it in Flutter

---

## 4. iA Writer

**App:** [iA Writer](https://ia.net/writer) — focused writing app for macOS, iOS, Windows, Android

### Git Integration Approach

iA Writer does **not have native git integration** as of 2024. The standard workflow for iA Writer users who want git sync is:

1. Store iA Writer documents in a folder managed by Working Copy (iOS)
2. Working Copy handles git operations; iA Writer reads/writes the same folder
3. On macOS: standard git tools or GitHub Desktop

iA Writer explicitly chose **not to build git integration** in-app, delegating to Working Copy on iOS through the File Provider extension mechanism. This is a deliberate design choice to keep the app focused on writing.

**Blog post:** "Word Export and GitHub on iOS" at https://ia.net/topics/word-and-github discusses their GitHub integration approach (primarily for publishing, not sync).

### Key Design Lesson for SteleKit

iA Writer's choice reveals the complexity: building good git UX in a note-taking app is hard enough that a well-resourced team chose to delegate it. SteleKit must accept this complexity as a core feature.

---

## 5. Common UX Failure Modes in Git-Embedded Note Apps

Based on research across the above apps, community discussions, and user reports:

### Failure Mode 1: Conflict markers in notes
**What happens:** Merge conflict markers (`<<<<<<<`, `=======`) appear in the rendered note.  
**Impact:** User sees garbled content; may save/export corrupted document.  
**Mitigation (SteleKit):** Never let a file with conflict markers reach the block editor. Intercept conflicts before GraphLoader processes files; route to conflict resolution screen first.

### Failure Mode 2: Silent data loss via force-push or wrong merge strategy
**What happens:** Auto-sync uses `--theirs` or force-push, silently discarding local edits.  
**Impact:** User's notes are permanently lost.  
**Mitigation (SteleKit):** FR-3 (never auto-apply remote changes to open files); always stage+commit local changes before merge; never use force strategies.

### Failure Mode 3: Sync while actively editing
**What happens:** Background sync triggers a merge/rebase while a block is being typed. The file is rewritten on disk by git; the in-memory view diverges from disk.  
**Impact:** User's pending edits are lost or cause a second conflict on next save.  
**Mitigation (SteleKit):** Implement an editing lock (FR-3.3): if any block is in edit mode, defer merge until editing stops.

### Failure Mode 4: Large vault clone hangs the UI
**What happens:** A large git clone or fetch blocks the main thread (if git operations run synchronously).  
**Impact:** App freezes; user force-quits, possibly corrupting in-progress operations.  
**Mitigation (SteleKit):** All git operations on `PlatformDispatcher.IO`; progress indicator in UI; cancellation support.

### Failure Mode 5: SSH auth failure with no clear error
**What happens:** SSH key authentication fails silently (wrong key path, unsupported algorithm, fingerprint mismatch).  
**Impact:** Sync appears to hang or fails with a cryptic error message.  
**Mitigation (SteleKit):** Map all SSH exceptions to `DomainError.GitError.AuthFailed` with human-readable messages; test with GitHub's modern key requirements.

### Failure Mode 6: Diverged history requires rebase, not merge
**What happens:** App always uses `git merge` but remote has been force-pushed; merge creates a "merge commit soup."  
**Impact:** Git log becomes unreadable; future merges have excessive conflicts.  
**Mitigation (SteleKit):** Support both merge and rebase strategies; default to merge for safety; expose strategy in settings.

### Failure Mode 7: Partial resolution state lost
**What happens:** User resolves 3 of 5 conflicts, backgrounds the app, iOS kills it. On next launch, the merge is in an inconsistent state.  
**Impact:** Either the incomplete merge is abandoned (losing conflict decisions) or git is left in conflicted state.  
**Mitigation (SteleKit):** Persist conflict resolution state to database (FR-4.5); on launch, detect in-progress merge and restore resolution screen.

---

## 6. Best Practices for "Safe Sync"

### Principle 1: Commit before sync
Always commit any local changes before fetching/merging remote changes. This ensures: (a) local work is never silently overwritten, and (b) conflicts are surfaced as proper merge conflicts rather than "local changes would be overwritten" errors.

### Principle 2: Detect active editing before sync
Maintain an application-level "editing active" flag. When a block's `BlockEditor` is in focus or `BlockStateManager` has unsaved changes, set this flag. Defer any merge until the flag clears.

### Principle 3: Atomic sync operation
The sequence `fetch → check for conflicts → merge → push` must be presented to the user as a single operation. Partial completion (e.g., fetched but not merged) must be surfaced visibly, not silently.

### Principle 4: Conflict-first, not conflict-later
Don't attempt the merge and then show the conflict screen. Before merging, detect potential conflicts with `git merge --no-commit --no-ff` or by analyzing the diff between `FETCH_HEAD` and `HEAD`. Show the conflict screen pre-emptively if the user has local edits to the same files that have remote changes.

### Principle 5: Notifications, not interruptions
When background polling detects remote changes, show a badge or notification — never auto-merge. The user must initiate sync. This matches Working Copy's model and FR-3.1.

### Principle 6: Preserve journal entry temporal integrity
For note apps with daily journal files, each edit session should produce one commit with a meaningful message (e.g., "Journal entry 2024-05-02" or "Edited: Home, TODO"). This makes the git log a meaningful audit trail.

---

## Open Questions / Unresolved Items

- **UNRESOLVED:** Working Copy's internal implementation for conflict persistence on iOS backgrounding — not publicly documented.
- **UNRESOLVED:** How GitJournal handles the case where two devices create the same daily journal file simultaneously (same date, same path) — likely relies on auto-merge of append-only files.
- **UNRESOLVED:** Whether NotePlan's git sync (https://help.noteplan.co/article/102-sync-with-git) has better conflict handling than Obsidian Git — their documentation is sparse.

---

## Sources

- [Working Copy User Guide](https://workingcopyapp.com/users-guide)
- [obsidian-git GitHub](https://github.com/Vinzent03/obsidian-git)
- [obsidian-git Issue #803: Conflict Handling](https://github.com/Vinzent03/obsidian-git/issues/803)
- [GitJournal GitHub](https://github.com/GitJournal/GitJournal)
- [GitJournal Website](https://gitjournal.io/)
- [iA Writer — Word Export and GitHub on iOS](https://ia.net/topics/word-and-github)
- [GitJournal Hacker News Discussion](https://news.ycombinator.com/item?id=31914003)
- [NotePlan Git Sync](https://help.noteplan.co/article/102-sync-with-git)
