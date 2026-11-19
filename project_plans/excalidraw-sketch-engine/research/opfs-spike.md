# OPFS atomic-write spike (Task 0.1.8a, Chrome headless)

Harness: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/OpfsAtomicWriteSpikeTest.kt` (throwaway, replaced by `OpfsAtomicReplaceTest` in Story 1.4.7).
Command: `./gradlew :kmp:wasmJsBrowserTest -PenableJs=true --tests '*OpfsAtomicWriteSpikeTest*'` (BUILD SUCCESSFUL in 4m 32s; Karma, ChromeHeadless).
Browser: HeadlessChrome/146.0.0.0, Linux x86_64, secure context (localhost). Tasks 0.1.8b (Firefox, Safari) and 0.1.8c are owner-run and NOT done here.

## Chrome row (VERIFIED: measured in this run, one run, one machine)

| Criterion | Result | Measured |
|---|---|---|
| OPFS root (`navigator.storage.getDirectory`) | Available | - |
| `createWritable` on `FileSystemFileHandle` | Available | - |
| (a) old bytes visible until `close()` (2 MB) | PASS: reader saw old bytes before close, new bytes after | close 1.1 ms |
| (a) `abort()` leaves old bytes | PASS (also an opened-never-written writable aborted: old bytes kept) | - |
| (b) `move(dir, name)` over an existing file | PASS: destination holds new bytes, temp gone | 0.8 ms |
| (b) `move(name)` single-argument form over existing file | PASS | - |
| (b) copy-with-verify fallback | PASS: temp verified, destination written and verified, temp deleted | 8.3 ms |
| (b) interrupted copy (abort mid-write) | PASS: destination keeps old bytes | - |
| (c) `file.slice(0, 256).arrayBuffer()` | PASS: exactly 256 bytes, equal to prefix | - |
| (d) 2 MB write and read back | PASS | write 33.4 ms, read 5.4 ms; quota 19.95 GB, usage 2.1 MB after |
| (e) `persist()` | answered `false` immediately, no prompt shown, 0.1 ms; `persisted()` false before and after. INFERRED limit: headless run, no user gesture (`userActivation.isActive` false), so this does not show what a headed click would return | recorded, never gating |
| `File.lastModified` | number, available | - |
| `createSyncAccessHandle` on the main-thread prototype | absent (it is worker-only; not planned) | - |

**Chrome verdict: `Available(Move)`** under criteria (a) to (d), for headless Chrome 146. Caveats: "interrupted" is simulated with `abort()`, not a process kill, so crash atomicity is not proven; one run, so timings are indicative only; headed Chrome and the `persist()` prompt behaviour are unverified.

## Not run (owner)
Firefox, desktop Safari, iOS Safari (Task 0.1.8b). Every browser-API fact for them stays INFERRED / UNVERIFIED. Raw result line (grep `OPFS-SPIKE-RESULT` in the test log) is reproducible by the command above.
