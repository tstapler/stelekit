# ADR-002: Register ContentObserver on Subdirectory Children URIs, Not Tree Root

## Status
Accepted

## Context

Android's Storage Access Framework (SAF) surfaces file-change notifications through `ContentResolver.notifyChange(uri, observer)`. When `ExternalStorageProvider` detects a file change inside `pages/Foo.md`, it calls `notifyChange` with the children URI of the `pages/` document node:

```
content://com.android.externalstorage.documents/tree/primary%3ANotes/children/primary%3ANotes%2Fpages
```

`ContentResolver.registerContentObserver(uri, notifyForDescendants, observer)` delivers notifications to an observer when the notification URI equals the registered URI, or — when `notifyForDescendants = true` — when the notification URI has the registered URI as a **string prefix**.

The original implementation registered on the root children URI:
```
content://com.android.externalstorage.documents/tree/primary%3ANotes/children/primary%3ANotes
```

The pages subdirectory children URI (`…/children/primary%3ANotes%2Fpages`) does not start with the root children URI (`…/children/primary%3ANotes`). The encoded slash `%2F` in the document ID segment of the pages URI means the prefix test fails: `primary%3ANotes%2Fpages` does not begin with `primary%3ANotes/`. As a result, no notifications from `pages/` or `journals/` were ever delivered to the registered observer.

This was confirmed by comparing the URI shapes: `ExternalStorageProvider` uses `{volumeId}:{relativePath}` document IDs, so the children URI for a subdirectory is built from a document ID that includes the full relative path (with `/` encoded as `%2F`), not from the root document ID with a path appended.

## Decision

`SafChangeDetector.startContentObserversAndPoller` registers one `ContentObserver` per subdirectory:

```kotlin
val pagesDocId = "$treeDocId/pages"
val pagesChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pagesDocId)
contentResolver.registerContentObserver(pagesChildrenUri, /* notifyForDescendants= */ true, observer)

val journalsDocId = "$treeDocId/journals"
val journalsChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, journalsDocId)
contentResolver.registerContentObserver(journalsChildrenUri, /* notifyForDescendants= */ true, observer)
```

`notifyForDescendants = true` ensures that notifications for individual file URIs within `pages/` (which are children of the registered `pages/` children URI) are delivered to the observer.

Registration failures are caught and logged rather than thrown, because some SAF providers may reject the registration URI if it references a non-existent or unrecognised subdirectory document.

## Alternatives Considered

**Root children URI registration with `notifyForDescendants = true`**: The original approach. Broken because the root children URI is not a string prefix of subdirectory children URIs due to URL encoding. Confirmed non-functional via URI analysis.

**Polling only (no ContentObserver)**: The 30-second SAF polling loop is already present as a correctness backstop. Relying on polling alone would mean up to 30 seconds of latency for all change detection on SAF paths. ContentObserver is the fast path; polling is the fallback.

**Registering on the tree root URI with `notifyForDescendants = true`**: Not attempted. The `buildChildDocumentsUriUsingTree` contract applies to children URIs, not tree URIs. Tree root registration does not receive subdirectory change notifications from `ExternalStorageProvider` in practice.

## Consequences

- `SafChangeDetector` registers two `ContentObserver` instances (one per subdirectory) instead of one at the root.
- Registration may silently no-op on SAF providers that use non-path-structured document IDs (e.g., MicroG / LineageOS custom providers, some OEM removable storage providers). The 30-second poller remains the correctness guarantee for these devices.
- On Samsung One UI 3.x, `notifyChange` calls have been observed with ~2-second batching delay; this does not affect correctness, only latency.
- Android < 10 (API < 29): `ExternalStorageProvider` does not consistently call `notifyChange` on subdirectory children URIs. The poller is essential on these devices.
- Each `ContentObserver` must be unregistered in `stop()` via `contentResolver.unregisterContentObserver(observer)` to avoid leaking the registration.
