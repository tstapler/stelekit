# PENDING OWNER ACTIONS - agents cannot run these

Story 5.1.3 of [plan.md](plan.md). Nothing in this file has been run. Doze, standby buckets, OEM battery managers, the Android Keystore and screen readers cannot be reproduced in CI or by an agent. Every box below is unchecked until the owner runs the step and pastes the result into the PR.

Gate: none of these block merging. M-1 to M-4 (and M-6 to M-9) block promoting the release-please PR. Also see the two checkpoints that sit outside the M list: the early Keystore run (item K, plan Task 2.2.2h) and the Bazel run (item B).

Source of the M-n numbering: [validation.md](validation.md) lines for M-1..M-9; UX wording in [../design/ux.md](../design/ux.md). Package id: `dev.stapler.stelekit` (`androidApp/build.gradle.kts`).

## Rules for every Android run

- Do not use `adb logcat` (it crashes the owner's Linux machine, see `CLAUDE.md` "Android debugging"). Read what the app persists: the in-app Logs screen, and `filesDir/logs/current.log`, `previous.log`, `last-crash.txt`.
- Pull logs with `run-as` (debug build only): `adb exec-out run-as dev.stapler.stelekit cat files/logs/current.log > /tmp/current.log`, and the same for `previous.log`. On a release build use the Logs screen instead.
- Scheduled runs appear as `TaskRun` lines and as rows "Git sync / <graph name> / <result>" on the Logs screen.
- Emulator setup: `export ANDROID_HOME=$HOME/Android/Sdk` (worktrees have no `local.properties`), then `~/Android/Sdk/emulator/emulator -avd Pixel_Fold -no-window -no-audio -no-snapshot`. Stop it afterwards with `adb emu kill`.

## K. Early Keystore cold-worker checkpoint (plan Task 2.2.2h; final form is M-4)

Why: Spike 0.1.2 only proved the plain-prefs fallback under Robolectric, which has no Keystore. This is the only check that exercises `EncryptedSharedPreferences` on a cold worker.

Device: a real device (an emulator with a working Keystore is acceptable but note which one).

1. Install a build from this branch. Open a git-backed graph; in Settings, Background tasks, set Git sync to on, every 15 min, "Unmetered networks only".
2. Press Home, then kill the process: `adb shell am force-stop dev.stapler.stelekit`. Do not reopen the app.
3. Find the job id: `adb shell dumpsys jobscheduler | grep -B2 -A6 dev.stapler.stelekit` (note the numeric id on the SteleKit WorkManager job).
4. Run it cold: `adb shell cmd jobscheduler run -f dev.stapler.stelekit <jobId>`. Wait 30 s.
5. Open the app. Open Logs.

Expected: a `TaskRun` row for Git sync for this graph (success, or `skipped: metered` if on mobile data), and no "Can't read settings" line on the Sync status. Note the run-log write latency if the log line carries it.

Fail looks like: no row, "Can't read settings. Background sync may be paused.", or a `settings-unreadable` skip in `current.log`. A failure re-opens the fail-closed read design (plan Story 2.2.2), so report it before more work builds on it.

- [ ] Pass   - [ ] Fail   Device / API level: ________   Latency: ________   Notes: ________

## M-1. Pixel_Fold emulator: standby bucket and Doze (REQ-16)

Setup: emulator running per the rules above, build installed, git graph enabled at 15 min, policy "Unmetered networks only" (the emulator network counts as unmetered unless you set it otherwise).

1. Force a run: get the job id as in K step 3, then `adb shell cmd jobscheduler run -f dev.stapler.stelekit <jobId>`.
2. Put the app in a restricted bucket: `adb shell am set-standby-bucket dev.stapler.stelekit rare`. Confirm with `adb shell am get-standby-bucket dev.stapler.stelekit` (expect `RARE` / 40).
3. Optionally force Doze: `adb shell dumpsys deviceidle force-idle`, then later `adb shell dumpsys deviceidle unforce`.
4. Leave the emulator running for at least twice the interval (30 min or more) with the app closed. Advance the emulator clock if needed; note that it was advanced.
5. Reopen the app. Open the Git sync status line and the Logs screen. Pull `current.log` and `previous.log` as above.

Expected: Logs shows `TaskRun` rows with scheduled-versus-actual start times; after last success exceeds 2x the interval the status line reads "Hasn't run in N min/h" (Late) with a restricted-background hint for `StandbyRestricted` ("Open battery settings" button) or, if the probe reports `None`, "Some phones stop background apps. Check battery settings." Late must not appear while the app was open and syncing in the foreground.

- [ ] Pass   - [ ] Fail   Rows seen: ____   Late appeared after: ____   Hint text: ____

## M-2. Unmetered holds on mobile data (REQ-2, REQ-16)

Device: physical, on mobile data (metered), policy "Unmetered networks only".

1. Force a run as in K steps 3-4 (or wait for the scheduled run).
2. Open Logs.
3. Check the remote: access log or last-fetch time for the repository. Compare with the run time.

Expected: the row reads "Git sync / <graph> / skipped: metered", and the remote saw no request from this device at that time.

- [ ] Pass   - [ ] Fail   Remote evidence: ________

## M-3. OEM device battery restriction run (REQ-16)

Device: physical Xiaomi or Samsung class phone with default battery management, git sync every 15 min, "Unmetered networks only", on Wi-Fi.

1. Install, enable the graph as above, open the app once, then close it from recents. Do not exempt the app from battery optimisation.
2. Leave the phone idle (screen off, not charging) for 24 h.
3. Open the app. Count `TaskRun` rows in Logs for the 24 h window (expected without throttling: up to 96 at 15 min; record the real count). Note whether "Late" and the restricted-background hint appeared and what text they showed.
4. Optional: exempt the app from battery optimisation, repeat, and compare counts.

Expected: the owner records the numbers; there is no pass threshold, this is field evidence for the "Late" state (plan Wall-clock blocker 3).

- [ ] Recorded   Device / OS: ________   Runs in 24 h: ____   Late shown: Y / N   Hint shown: Y / N

## M-4. Keystore-backed settings on a cold worker, release build (REQ-5, REQ-12)

Same procedure and expected result as K, run against the release build before promoting the release-please PR. Confirms the config is read, the run record persists and is visible after relaunch.

- [ ] Pass   - [ ] Fail   Build: ________   Latency: ________

## M-6. Esc closes the settings dialog and returns focus (UX-29)

Desktop build (`bazel run //kmp:desktop_app`).

1. Open the settings dialog from a control (use the keyboard to focus the control that opens it).
2. Press Esc.

Expected: the dialog closes and keyboard focus is on the control that opened it (a following Enter reopens the dialog).

- [ ] Pass   - [ ] Fail   Notes: ________

## M-7. TalkBack: graph row and disabled-control wording (UX-25, UX-35)

Device with TalkBack on. Open Settings, Background tasks, drill to a graph.

1. Swipe to a graph row. Expected single announcement: "Git sync, Work notes, on, about every hour, unmetered networks only" (substitute your graph name and values).
2. Swipe to a disabled control (for example a graph without a remote, or an option the platform does not support). Expected: "<control>, disabled, <reason>".

- [ ] Pass   - [ ] Fail   Heard instead: ________

## M-8. Contrast and icon-plus-text pairing, light and dark (UX-28)

Device (or emulator) in each theme. View a Late status line, a Paused status line, and the restricted-background hint.

Expected: each pairs an icon with text (state is never colour alone) and the text reads clearly, at least 4.5:1 against its background. Measure with a contrast checker on a screenshot if in doubt. Paste the screenshots into the PR.

- [ ] Light pass   - [ ] Dark pass   - [ ] Fail   Notes: ________

## M-9. TalkBack: focus and announcement on drill-in, back and search (UX-33, UX-34)

TalkBack on. Drill Background tasks, then a kind, then a graph, and press Back at each step. Use the search field with text that matches nothing.

Expected: after each drill-in, focus lands on the screen title; after Back, focus returns to the control that opened it; a search with no match announces "No matches".

- [ ] Pass   - [ ] Fail   Notes: ________

## Not on the M list but owner-only

- [ ] M-5 dead-end sweep (validation.md): inject each S1-S6 failure (settings read, save, launcher, run-log read, download failure) and confirm each shows Retry, Cancel, a fix action or Back.
- [ ] Real-OS flex window (ADR-002 Spike outcomes point 7): the Spike's `TestDriver` showed a point gap (60 min), not the range `[interval - flex, interval + flex]` the Settings copy describes. During M-1 or M-3 with a nonzero spread, compare consecutive `TaskRun` start times against that range and record the observed gaps: ________
- [ ] Spike 0.1.2 gap: the Keystore path is verified only by K and M-4; until one of them passes, ADR-003 must not be read as proving it.
- [ ] Android 14/15 foreground-service limit numbers (plan Task 4.1.2d): confirm against current Android docs before any number appears in UI copy. Current copy contains none; this has not been checked.
- [ ] Owner confirmation of bundled product changes (plan Unresolved Questions): model-download default `Unmetered`, "Download now anyway", LFS fail-closed on unknown network (Android), graph-removal callback in `GraphManager`, migration `enabled = legacyFlag && hasRemote`, Desktop git staying on `PeriodicSyncDriver`, default spread of 6 min, Desktop radio arrow-key navigation. Each is its own commit and can be dropped at PR review.

## B. Bazel verification (blocked on Maven 429)

Not run. Run once the Maven Central 429 rate limit clears, from the repo root, wrapped in `timeout` so a starved action cannot run for hours:

1. `timeout 30m bazel test //kmp:business_tests`
2. `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY` (a bare run of jvm_tests is unverified; the script reports whether a real display was used)
3. `timeout 30m bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` (covers only classes registered in `AllAndroidUnitTests`)
4. `timeout 60m bazel test //... --config=ci`
5. `./gradlew :kmp:testDebugUnitTest` (covers every Robolectric/Compose test; CI runs this) and `./gradlew detekt`
6. `bazel shutdown` when finished.

Expected: all green; `AllBusinessTestsCompletenessTest` and `WiringCompletenessTest` pass; `JobsScheduleImportDirectionTest` reports a nonzero scanned-file count.

- [ ] Pass   - [ ] Fail   Failing targets: ________
