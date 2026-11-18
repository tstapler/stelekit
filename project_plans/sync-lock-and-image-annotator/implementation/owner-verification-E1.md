# Owner verification checklist: Epic 1 (sync single-flight and safe lock recovery)

Task 1.8c. Every item below is a **remaining wall-clock blocker** on Tyler's phone; none was run by an agent and none can be closed by CI. E1 may merge CI-green without them. Until item 2 and 3 are ticked, every unreadable liveness probe stays `OwnerUnknown` (fail closed), so the unverified Android `/proc` behaviour can only produce the amber "Sync paused" state, never a delete on a guess.

Where to read evidence: in-app **Logs** screen (filter on `SyncSingleFlight`, `AndroidGitRepository`, `GitSyncService`). On the next launch after a crash or force-stop the previous session's tail is replayed there too (`PreviousSessionLog`); the files behind it are `filesDir/logs/current.log` and `previous.log`. Do not use `adb logcat`.

Log lines this epic writes (tag `SyncSingleFlight`):

- `sync origin=<Origin> decision=started key=<hash>` (a run took the gate and the claim passed)
- `sync origin=<Origin> decision=coalesced key=<hash> heldBy=<Origin|null> followUpQueued=<true|false>` (a press was folded into a running sync)
- `sync origin=<Origin> decision=skipped reason=<StaleLockFile|config-unreadable>` (claim refused or config unreadable)
- `lock verdict=<NoLock|ClearedOwn|ClearedDeadOwner|OwnerAlive|OwnerUnknown(reason=...)|NoRecord>` (one line per claim)

## 1. Triple-press during a slow sync (story 1.4)

- [ ] On a graph with a slow sync (large repo or throttled network), tap **Sync now** three times within a second.
- Expect: one `decision=started`, then `decision=coalesced ... followUpQueued=true` once (the second and third presses add nothing), the row shows "One more sync will run after this", and exactly one follow-up run starts (`rerun started key=...`) after the first finishes.
- Expect no red error and no `index.lock` error text anywhere. `adb shell run-as` is not needed: check that `.git/index.lock` does not exist in the shadow git dir after the runs finish (Logs screen shows no `StaleLockFile`).

## 2. Force-stop mid-sync, relaunch, sync again (stories 1.2, 1.5, 1.7)

- [ ] Start a sync on a large change set, then Android Settings > Apps > SteleKit > **Force stop** while the status row says Syncing.
- Relaunch and press **Sync now**.
- Expect one of: `lock verdict=ClearedDeadOwner` followed by `decision=started` and a normal sync (best case, an automatic clear), or amber "Sync paused - retrying automatically" with `lock verdict=OwnerUnknown(reason=...)` and then, after the two automatic retries (about 30 s and 2 min), the red "Couldn't sync. Your notes are saved on this device." with **Sync now**. Never an unexplained red state and never a deleted lock on an `OwnerUnknown` line.
- Record which branch happened and the `reason=` value (`LivenessUnknown` means item 3 failed; `ForeignLockSuspected` means the lock was newer than the record).
- Confirm no data loss: the notes edited before the force-stop are present after the next successful sync.

## 3. `/proc/<pid>` readability for a dead same-uid pid on this Android version (API 33+) (plan "Unresolved questions")

- [ ] After item 2, the claim logs `lock verdict=ClearedDeadOwner` only if `/proc/<pid>/stat` for the dead pid returned ENOENT (Absent) and the start-tick comparison ran. Record the Android version and outcome:
  - `ClearedDeadOwner`: ENOENT is readable as Dead on this device (closes the question; `ProcfsProbe.probe` Absent path confirmed).
  - `OwnerUnknown(reason=LivenessUnknown)` on every force-stop: the probe returned Unreadable (EACCES or parse failure), which is the fail-closed outcome; file a follow-up to add a different liveness source.
- Also record whether `pidNs` matched: a `OwnerUnknown(reason=DifferentHost)` or an absent pid in a different pid namespace is expected to stay Unknown.

## 4. `/proc/sys/kernel/random/boot_id` readable on this Android version (plan "Unresolved questions")

- [ ] Reboot test (story 1.2 reboot step): start a sync, power the phone off or reboot mid-run, relaunch, press **Sync now**.
- Expect `lock verdict=ClearedDeadOwner` by boot id with no amber step. If the claim instead goes through the pid/start-tick probe (or the amber state), `boot_id` was unreadable and `Settings.Global.BOOT_COUNT` was used or null; note which.
- To read the file directly on a debuggable build or a rooted/adb-shell session: `adb shell cat /proc/sys/kernel/random/boot_id` (a UUID means readable; "Permission denied" means the app falls back to BOOT_COUNT).

## 5. `FileChannel.tryLock` on the owner's Android filesystem (ADR-001 decision 12, the recovery lock)

- [ ] Any deleting verdict (`ClearedDeadOwner` in item 2 or 4) exercises `.git/stelekit-sync.recover` via `FileChannel.tryLock`. If the filesystem rejects it, the claim logs `lock verdict=OwnerUnknown(reason=RecoveryLockUnsupported)` and deletes nothing.
- Record: did a force-stop recovery ever show `RecoveryLockUnsupported`? If yes, the shadow git dir sits on a filesystem without advisory locks and recovery stays manual on this device; file a follow-up.

## 6. Second press produces zero `index.lock` errors

- [ ] During a running sync press **Sync now** again 5 times, a few seconds apart.
- Expect only `decision=coalesced` lines; no `index.lock`, `StaleLockFile` or red state is produced by the extra presses, and the lock file under `.git` is removed when the run ends.

## 7. Wired Retry now and paused states on the device (stories 1.7a-1.7c)

- [ ] Reach the amber state (item 2 when the verdict is `OwnerUnknown`, or leave a stray `index.lock` with no owner record to get `NoRecord`).
- Expect: row text "Sync paused - retrying automatically", sheet body without the words "another git operation" or "running", Details "try 1 of 2" with a countdown, a 48 dp "Retry now" button, and a single polite TalkBack announcement "Sync paused. Retrying automatically." per entry.
- Press **Retry now** five times quickly: at most one extra attempt runs at a time, the "try n of 2" number does not move, the state does not reach Failed from presses alone.
- After both automatic tries fail: Failed row reads "Couldn't sync. Your notes are saved on this device." with **Sync now** and **Details**; **Sync now** restarts a fresh two-try cycle if the owner is still unknown.
- Verify the amber row contrast in light and dark themes by eye (the 4.5:1 check is also asserted by Robolectric).

## 8. Lock removal entry is not wired yet (known gap, no action for the owner)

`SyncStatusSheetActions.onRemoveLockFile` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusSheet.kt`) is declared and rendered, with its confirm dialog covered by `SyncStatusSheetStatesTest`, but nothing passes a non-null value in production: there is no service method that calls `GitRepository.forceRemoveLock` yet. On this build a `NoRecord` lock therefore shows no "Remove lock file..." entry; the only recovery is the automatic retry and **Sync now**. Do not report the missing entry as a bug during this checklist; it needs a follow-up task that adds a gated `GitSyncService` method and wires the action.
