# BUG-006: Stuck Host-Directory Writes Never Retry [SEVERITY: Medium]

**Status**: Resolved
**Discovered**: 2026-07-27 during conflict/sync UI state investigation
**GitHub Issue**: None filed — found and fixed in the same session
**Impact**: Web (WASM) users with local folder livesync enabled could see the "N changes
not yet synced to folder" warning badge climb and never recover after a single transient
write failure (permission re-prompt, momentary disk contention), short of manually editing
the affected file again or fully disconnecting/reconnecting the host folder.

## Problem Description

`HostDirectorySync.kt`'s `hostWritePending` is a `mutableMapOf<String, DirtyEntry>` queuing
edits destined for a browser-granted local folder via the File System Access API. Entries
are added when a write is scheduled and removed only on a successful `flushHostWrite`. When
a flush attempt fails, the entry is deliberately kept queued (so the edit isn't lost) and
`_hostWriteStuckFlow` flips true, showing the "not yet synced" warning badge — but nothing
ever re-attempted the flush. The only paths back to a clean queue were the user re-editing
that exact file (triggering a fresh `scheduleHostWriteThrough` call) or a full
disconnect/reconnect of the host directory (`runHostReconciliation` is one-shot, not
periodic).

## Reproduction Steps

1. Grant local folder access in the web app and make an edit.
2. Cause a transient flush failure (e.g., revoke and immediately re-grant folder
   permission mid-write, or otherwise fail one `flushHostWrite` attempt).
3. Expected: the write eventually retries and the badge clears once it succeeds.
4. Actual: the entry sits in `hostWritePending` indefinitely; the badge count never drops
   unless the user edits that same file again.

## Root Cause

`flushHostWrite` failures had a "keep queued" branch but no corresponding retry driver.
The per-tab poll timer (`startHostDirectoryPolling`) already runs periodically for
reconciliation purposes but never touched `hostWritePending`.

## Files Affected (3 files)

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` — added
  `retryStuckHostWrites()`, called once per `startHostDirectoryPolling` tick
- `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncWriteThroughTest.kt`
  — regression test
- `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryTestFixtures.kt` —
  test fixture support for simulating a transient-then-successful flush

## Fix Approach

Added `retryStuckHostWrites()`: re-attempts `flushHostWrite` for every `hostWritePending`
entry not already owned by an in-flight `scheduleHostWriteThrough` flush (tracked via
`hostWriteInFlight`), called unconditionally once per poll tick. This piggybacks on the
existing per-tab timer rather than adding a second timer/backoff mechanism —
`effectivePollIntervalMs()` becomes the implicit retry backoff. It mirrors
`scheduleHostWriteThrough`'s own "claim ownership, loop while dirty-during-flush, release
in finally" shape so a concurrent user edit to the same path coalesces into the retry
attempt instead of racing a second flush. Snapshots `hostWritePending`'s keys before
iterating since `flushHostWrite` mutates the map on success.

## Verification

Verified via a stash-based control experiment: 4 pre-existing `HostDirectorySyncWriteThroughTest`
failures were confirmed present on the pre-fix baseline (unrelated to this change), then the
new regression test was confirmed passing against the fix with no new failures introduced:

```
retryStuckHostWrites_should_EventuallyFlushAndDequeue_When_FirstAttemptFailsTransientlyAndSecondCallSucceeds
```
PASSED — the entry flushes and dequeues on the second `retryStuckHostWrites()` call after
the first attempt fails transiently.

## Related Tasks

None.
