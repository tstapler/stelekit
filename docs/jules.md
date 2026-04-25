# Setting up Jules for SteleKit

[Jules](https://jules.google) is Google's async coding agent. This guide walks
through configuring it for SteleKit's Kotlin Multiplatform build so that Jules
sessions can run `:kmp:jvmTest`, `:kmp:testDebugUnitTest`, `:kmp:detekt`, and
`:androidApp:assembleDebug` (the same checks `./gradlew ciCheck` runs locally).

The bootstrap is captured in [`scripts/jules-setup.sh`](../scripts/jules-setup.sh)
so it stays in source control and can be reproduced locally.

## Weekend plan

Budget ~30 minutes — most of the time is the Gradle warm-up running in the
Jules container.

### Saturday — wire it up

1. **Connect the repo.** Sign in at <https://jules.google.com>, click *Add
   Repository*, and authorize the GitHub app on `tstapler/stelekit`.
2. **Open the repo's Configuration panel** in the Jules sidebar.
3. **Paste the setup script.** Copy the contents of
   [`scripts/jules-setup.sh`](../scripts/jules-setup.sh) into the
   *Initial Setup* window. (Alternatively, paste a single line that fetches
   and runs it: `bash scripts/jules-setup.sh`.)
4. **Run and Snapshot.** Click *Run and Snapshot*. The first run takes 10–15
   minutes because it downloads the Android SDK, JDK 21, Xvfb, and warms up
   the full Gradle dependency cache. Subsequent tasks reuse the snapshot.
5. **Confirm the snapshot.** The script's smoke tests must finish green;
   otherwise the snapshot is discarded.

### Sunday — verify with a real task

1. **File a trivial task** in Jules — e.g. *"Add a TODO comment above
   `StelekitViewModel.navigateTo` and run jvmTest"*. This proves the snapshot
   reproduces correctly.
2. **Inspect the diff and CI run.** Confirm Jules opens a PR against
   `main`, that the PR triggers `.github/workflows/ci.yml`, and that the
   in-Jules `:kmp:jvmTest` output matches what CI reports.
3. **Tune `AGENTS.md` if needed.** Jules reads `AGENTS.md` (and `README.md`)
   for behavioural hints. The repo currently symlinks `AGENTS.md` to a
   personal Claude config; replace it with project-specific guidance for
   Jules if you want different defaults from Claude Code.

## What the setup script does

| Step | Why |
|------|-----|
| Install JDK 21 (Temurin) | `kmp/build.gradle.kts` targets JVM 21; CI uses Temurin 21 |
| Install Xvfb | Compose Desktop tests render through Skia + AWT and need a display |
| Install Android `cmdline-tools`, `platforms;android-35`, `build-tools;35.0.0` | Required by `:androidApp:assembleDebug` and `:kmp:testDebugUnitTest` |
| `./gradlew help` | Pre-fetches the multiplatform/Compose/Android dependency graph |
| `./gradlew :kmp:detekt :kmp:jvmTest :kmp:testDebugUnitTest :androidApp:assembleDebug` under `xvfb-run` | Mirrors `ciCheck` so the snapshot is validated before being saved |

## Troubleshooting

- **`SDK location not found`** — the script exports `ANDROID_HOME`; if Jules
  runs commands in a fresh shell that misses the export, add
  `org.gradle.java.installations.fromEnv=JAVA_HOME` and an `ANDROID_HOME`
  entry to `~/.gradle/gradle.properties` inside the snapshot.
- **`No X11 DISPLAY variable was set`** — a test bypassed `xvfb-run`. Wrap
  the Gradle invocation: `xvfb-run --auto-servernum ./gradlew …`.
- **Snapshot is too large / slow** — drop `:androidApp:assembleDebug` from
  the smoke section of `scripts/jules-setup.sh`; Jules will still install
  the SDK but skip the full APK build.
- **Jules can't find the script** — paste the script contents inline into
  the *Initial Setup* panel rather than relying on `bash scripts/…`; the
  snapshot is built before any repo checkout step you might add.

## Related docs

- `CLAUDE.md` — build commands and architecture (also useful as a reference
  for Jules tasks).
- `kmp/TESTING_README.md` — testing infrastructure details.
- [Jules environment docs](https://jules.google/docs/environment/).
