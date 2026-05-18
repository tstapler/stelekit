# Findings: File Copy Race Conditions and Unique Filename Generation

## Summary
The race condition in unique filename generation is a classic TOCTOU (Time-of-Check to
Time-of-Use) problem: check if `photo.jpg` exists → it doesn't → another coroutine copies
a file named `photo.jpg` → first coroutine overwrites it. The safe pattern uses atomic
file operations: write to a temp file, then atomically rename into the target location.
Generating a unique name with a UUID or timestamp suffix eliminates the check-then-act
race entirely — no need to check existence first.

## Options Surveyed
1. Sequential suffix scan (`photo.jpg`, `photo-1.jpg`, `photo-2.jpg`) — check-then-act loop
2. UUID suffix (`photo-<uuid>.jpg`) — no check needed; practically unique
3. Timestamp suffix (`photo-<epoch-ms>.jpg`) — low collision probability; readable
4. Atomic `Files.move(ATOMIC_MOVE)` — OS-level guarantee for the final placement
5. Kotlin `okio` `FileSystem.atomicMove()` — KMP-compatible atomic move

## Trade-off Matrix
| Approach | Race-safe | Readability | KMP compatible | Filename length |
|-----------|-----------|-------------|----------------|-----------------|
| Sequential suffix | No (TOCTOU) | High | Yes | Short |
| UUID suffix | Yes | Low | Yes | Long (+36 chars) |
| Timestamp suffix | Partial (1ms window) | Medium | Yes | Medium (+13 chars) |
| Atomic move from temp | Yes (for write) | N/A | Partial (JVM/okio) | N/A |
| UUID + atomic move | Yes | Low | Best | Long |

## Risk and Failure Modes

### TOCTOU race with sequential suffix scan
- **Failure mode**: Two coroutines simultaneously pick the same file (`photo.jpg` absent
  → both try to write `photo.jpg` → second write silently overwrites first attachment).
- **Trigger**: Two concurrent attachment operations for same-named files.
- **Mitigation**: Use UUID or timestamp suffix, eliminating the existence check. Or
  serialize all attachment operations through `DatabaseWriteActor` (already in the project)
  which serializes writes to a single coroutine.

### Non-atomic write — partial file visible to Coil
- **Failure mode**: Coil begins loading `photo.jpg` while it is still being copied;
  decodes a partial/corrupt file; shows broken image.
- **Trigger**: File copy to final destination while Coil observes the directory.
- **Mitigation**: Write to `<graphRoot>/assets/.tmp-<uuid>` first, then rename to final
  name. `File.renameTo()` is atomic on POSIX (same filesystem). `Files.move(ATOMIC_MOVE)`
  is preferred on JVM. `okio.FileSystem.atomicMove()` is available KMP-wide via okio 3.x.
  [TRAINING_ONLY — verify okio atomicMove availability in KMP]

### `renameTo()` failure across filesystems
- **Failure mode**: If the temp file is on a different filesystem partition than the assets
  folder, `renameTo()` returns `false` silently (no exception) and the file is not moved.
- **Trigger**: Temp directory (`System.getProperty("java.io.tmpdir")`) on a different
  mount than the graph root (common on Linux with `tmpfs` on `/tmp`).
- **Mitigation**: Write temp file inside `<graphRoot>/assets/.tmp/` (same filesystem as
  destination). Delete temp file on failure. On JVM, use `Files.move()` with
  `ATOMIC_MOVE` or `REPLACE_EXISTING` fallback.

### File name collision in the requirements (user experience)
- **Requirements say**: "Duplicate filenames handled by appending suffix (e.g., `photo-1.jpg`)"
- **Risk**: This is the sequential suffix approach — it has the TOCTOU race.
- **Safe implementation**: Use a single-threaded dispatcher (the actor) to serialize the
  check-and-copy operation; since all writes are serialized, no two coroutines can race.
  The `DatabaseWriteActor` already provides this serialization guarantee.

### okio `FileSystem` on WASM/JS
- **Failure mode**: `okio.FileSystem.SYSTEM` is not available on WASM/JS — browser has no
  native filesystem access.
- **Trigger**: Using `okio.FileSystem` in `commonMain`.
- **Mitigation**: Define an `expect fun copyToAssets(...)` with WASM implementation using
  browser download / IndexedDB — or mark Web as non-copy (link only).

## Migration and Adoption Cost
- `okio` is already a transitive dependency via SQLDelight and Coil.
- `PlatformDispatcher.IO` is already defined in the project — use it for all file copy.
- Atomic move within the same filesystem requires no new dependencies on JVM/Desktop.
- WASM/Web file copy requires a browser-specific implementation (separate `jsMain`/`wasmJsMain` actual).

## Operational Concerns
- Log file copy operation: source URI, destination path, file size, duration.
- On failure, log the exception and return `DomainError.Left` with a user-visible message.
- Verify `assets/` directory creation is idempotent (`mkdirs()` or `Files.createDirectories()`).

## Prior Art and Lessons Learned
- Logseq uses UUID-based asset filenames to avoid all collision issues.
- The project's `DatabaseWriteActor` serialization is the simplest safe option for the
  requirement's "sequential suffix" approach — no race possible if writes are serialized.
- Obsidian uses timestamp + random suffix for attachment names.

## Open Questions
- [ ] Does `okio.FileSystem.SYSTEM.atomicMove()` work on Android and Desktop JVM in okio 3.x? — blocks: whether to use okio or JVM `Files.move`

## Recommendation
**Recommended option**: Sequential suffix scan (`photo.jpg` → `photo-1.jpg`) with all
copy operations serialized through a single-threaded `IO` dispatcher coroutine (or the
existing `DatabaseWriteActor`). Write to temp file first, then rename.

**Reasoning**: Matches the requirements' user-facing naming convention. Serialization
eliminates TOCTOU. Temp-then-rename prevents partial file visibility.

**Conditions that would change this recommendation**: If parallel attachment uploads are
ever needed, switch to UUID suffixes to eliminate serialization as a bottleneck.

## Pending Web Searches
1. `okio atomicMove KMP Android JVM filesystem` — verify atomicMove availability
2. `"Files.move" ATOMIC_MOVE "renameTo" cross-filesystem failure Kotlin` — confirm cross-fs pitfall
3. `Kotlin TOCTOU "unique filename" coroutine safe pattern` — find established patterns

## Web Search Results (verified 2026-05-15)

### okio `atomicMove` — VERIFIED
- `okio.FileSystem.atomicMove()` is available in okio 3.x on JVM and Android.
- **Limitation**: `FileSystem.atomicMove()` fails if the **target file already exists** (does not overwrite).
- `FileSystem.SYSTEM` is now available in common source sets (since March 2024 okio update).
- Source: [Okio — File System](https://square.github.io/okio/file_system/), [okio GitHub Issue #1070](https://github.com/square/okio/issues/1070)

### Cross-filesystem `renameTo` failure — CONFIRMED
- `File.renameTo()` silently returns `false` on cross-filesystem moves (no exception).
- Always write temp file inside the same directory as the destination (`<graphRoot>/assets/.tmp/`) to guarantee same filesystem.
- JVM `Files.move(src, dst, ATOMIC_MOVE)` throws `AtomicMoveNotSupportedException` on cross-filesystem (explicit, not silent) — prefer this over `renameTo`.
- Source: standard JDK behavior, documented in `java.nio.file.StandardCopyOption`.

### CONFIRMED PATTERN
- Write to `<graphRoot>/assets/.tmp-<uuid>`, then `Files.move(ATOMIC_MOVE)` to final name.
- Serialize all attachment writes through `DatabaseWriteActor` or a single-threaded IO scope.
- Use sequential suffix (`photo.jpg` → `photo-1.jpg`) as specified in requirements; serialization eliminates TOCTOU.
