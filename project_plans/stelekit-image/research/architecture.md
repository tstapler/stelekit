# FR-2 Research: Extending SteleKitAssetMapper for uploads/ paths

## Source files

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetFetcher.kt` — mapper implementation
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetMapperTest.kt` — existing test suite
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/ImageStoragePathResolver.kt` — on-disk path resolver (not involved in FR-2)

---

## 1. Current path-traversal guard logic

`SteleKitAssetMapper.map()` (lines 31–39 of `SteleKitAssetFetcher.kt`):

```kotlin
override fun map(data: String, options: Options): Uri? {
    if (!data.startsWith("../assets/")) return null
    val filename = data.removePrefix("../assets/")
    // Guard against path traversal: reject backslashes and any ".." path component
    if (filename.contains('\\') || filename.split('/').any { it == ".." }) {
        return null
    }
    return "file://$graphRoot/assets/$filename".toUri()
}
```

The guard:
1. Strips the known prefix (`../assets/`) to get the remaining filename (which may include subdirectory segments, e.g. `images/2026-01-02-abc.jpg`).
2. Rejects any remaining path segment equal to `..` (checked after splitting on `/`).
3. Rejects any backslash character.

This logic is already subdirectory-safe: `../assets/images/file.jpg` correctly maps to `file://<root>/assets/images/file.jpg`.

---

## 2. Logseq uploads/ path format

Per the requirements document (RC-2): Logseq stores images as:

```
![alt](../uploads/filename.png)
```

The path uses the same `../` relative prefix convention as `../assets/`. There are no further subdirectory levels documented in the requirements — uploads land directly under `<graphRoot>/uploads/`.

No Kotlin source files in the codebase currently reference the literal `uploads/` path (only a ViewModel comment and a Google Drive export test use the word "uploads" in different contexts).

---

## 3. Existing tests that must be extended

`SteleKitAssetMapperTest` (9 tests) covers:
- Happy path: `../assets/photo.jpg` → `file:///…/assets/photo.jpg`
- Null for https, unrelated strings, empty string
- Nested asset subdirectory: `../assets/subdir/image.png` → correct nested URI
- Path traversal via `..` component — returns null
- Path traversal via backslash — returns null
- Trailing slash normalisation on graphRoot (single and multiple slashes)

Tests to add for FR-2:
- `../uploads/filename.png` → `file:///<root>/uploads/filename.png`
- `../uploads/` with `..` component → null (traversal guard)
- `../uploads/` with backslash → null (traversal guard)

---

## 4. Minimal code change

The mapper currently has a single early-return branch for `../assets/`. The cleanest extension is to extract the prefix-detection into a when/if-else chain so each prefix maps to its own target directory, sharing the same guard:

```kotlin
override fun map(data: String, options: Options): Uri? {
    val (prefix, targetDir) = when {
        data.startsWith("../assets/") -> Pair("../assets/", "assets")
        data.startsWith("../uploads/") -> Pair("../uploads/", "uploads")
        else -> return null
    }
    val filename = data.removePrefix(prefix)
    if (filename.contains('\\') || filename.split('/').any { it == ".." }) {
        return null
    }
    return "file://$graphRoot/$targetDir/$filename".toUri()
}
```

This change:
- Adds `../uploads/` support without duplicating the guard.
- Leaves `../assets/` behaviour byte-for-byte identical (existing tests still pass).
- Does not touch `ImageStoragePathResolver` — new images continue to be written to `assets/images/`; only reading from `uploads/` is added.

---

## 5. ImageStoragePathResolver and subdirectory handling

`ImageStoragePathResolver.resolvePath()` returns a path of the form:

```
<graphPath>/assets/images/<yyyy-MM-dd>-<uuidPrefix>.jpg
```

The markdown inserted for new captures therefore uses `../assets/images/…` (a nested path). The existing guard strips `../assets/` leaving `images/<file>` — the `images` segment is an ordinary path component, not `..`, so it passes the guard. `../assets/images/` paths already work correctly with the current mapper. No change needed for this case.

---

## Summary

- The guard is already subdirectory-safe; only the prefix-detection `if` needs extending.
- `uploads/` paths use the `../uploads/<filename>` format (flat, same prefix convention as `../assets/`).
- The test file at `kmp/src/jvmTest/.../SteleKitAssetMapperTest.kt` needs three new test cases (happy path + two traversal-guard cases for the `uploads/` prefix).
