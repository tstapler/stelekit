# Validation: fix-github-device-auth-browser-button

## Checklist

### Completeness
- [x] Root cause identified and confirmed: `Desktop.isDesktopSupported()` returns `false`
  on Linux/Wayland; even when `true`, `BROWSE` action is silently unsupported.
- [x] All affected platforms enumerated: JVM (Linux fix), macOS/Windows (no change),
  Android/iOS/wasmJs (already working).
- [x] Single call site: `GitSetupScreenDialogs.kt:36` (`openInBrowser(url)`) — no other
  callers of the `expect` function exist in the codebase.
- [x] No state model, ViewModel, or dialog UI changes required.

### Design soundness
- [x] `xdg-open` is the correct Linux mechanism: XDG standard, present on all desktop
  distros, works under Wayland and X11.
- [x] The daemon-thread approach (preferred in plan) avoids importing coroutine
  dependencies into a pure-platform file with a one-liner `ProcessBuilder.start()`.
- [x] `runCatching` swallows `IOException` when `xdg-open` is absent — silent fallback
  is acceptable; user can use "Copy code" button.
- [x] macOS and Windows paths are unchanged by the OS branch guard.
- [x] The `internal` `openInBrowserWithOs` helper makes the OS-dispatch path unit-testable
  without needing a real process or mock framework.

### Risks
- **Low**: `xdg-open` absent on a headless/server Linux install — silent failure, same UX
  as before the fix. No regression.
- **Low**: `os.name` contains "linux" on Android — Android actuals are resolved at compile
  time by KMP, so the JVM actual is never compiled into the Android APK. No risk.
- **None**: iOS and wasmJs actuals are untouched.

### Test coverage
- [x] New `jvmTest` verifies Linux dispatch calls `xdg-open`.
- [x] New `jvmTest` verifies non-Linux dispatch does not call `xdg-open`.
- [ ] No device test planned (out of scope for this item) — manual verification on
  Manjaro Linux is the acceptance signal.

### Estimated CI impact
- `bazel test //kmp:jvm_tests` will run the new `OpenInBrowserJvmTest`; requires a display
  for UI tests (use `scripts/jvm-display-check.sh`) but `OpenInBrowserJvmTest` is pure
  unit logic with no Compose — it will pass headless.
- No Android, iOS, or wasmJs build changes.

## Manual Verification Steps

1. Build and launch desktop app: `bazel run //kmp:desktop_app`
2. Open Git Setup for a repository.
3. Select "GitHub OAuth" auth type and click "Connect GitHub Account".
4. Dialog shows `ShowCode` state with user code and "Open GitHub" button.
5. Click "Open GitHub" — default browser opens `https://github.com/login/device`.
6. Verify the user code is pre-filled or can be pasted to complete authorization.

## Open Questions

- Should a URL scheme guard (`url.startsWith("https://")`) be added to the Linux branch
  to mirror the wasmJs guard? Low priority given the URL is from a trusted API, but
  aligns with defence-in-depth.
- Should `PlatformFileOpener.jvm.kt` receive the same Linux fallback (using `xdg-open`
  for file opening)? Deferred to a follow-up — file opening is less critical than the
  auth browser-open.
