# F-Droid Distribution Setup

**Feature**: F-Droid Repository Distribution
**Status**: Planning
**Last Updated**: 2026-04-18

---

## Context

SteleKit currently distributes Android APKs as GitHub Release artifacts (signed with a developer keystore). F-Droid is a free/open-source Android app store that builds APKs from source, signs them with its own key, and hosts them at `f-droid.org`. Getting listed there gives SteleKit access to millions of privacy-conscious users who avoid Google Play.

F-Droid has strict build requirements that differ meaningfully from the current GitHub Actions release pipeline:

- F-Droid builds APKs from source on their own infrastructure, so the submission is a build recipe (`fdroiddata` YAML), not a binary.
- F-Droid signs the APK with the F-Droid signing key. The developer signing key is irrelevant (and must not be required at build time).
- All dependencies must be buildable from source with no proprietary SDKs. JitPack dependencies are **not** allowed because they pull pre-built JARs; Clojars is similarly disallowed unless the source is also in an approved repo.
- The build environment is a clean Debian container with a fixed JDK, Gradle, and Android SDK. Build scripts must be entirely self-contained.
- An `fdroid update` reproducibility check compares the F-Droid-built APK against a developer-provided reference APK when `Binaries:` is declared in the build recipe. Reproducible builds are optional but strongly recommended.
- App metadata (short description, full description, changelogs) is stored in `fastlane/metadata/android/en-US/` within this repo and consumed by `fdroid update`.

### Current State Assessment

| Requirement | Current State | Gap |
|---|---|---|
| No signing required for release build | Conditional: skips signing if env vars absent | Low — already works unsigned |
| No proprietary SDKs | No Firebase, no Google Play Services | Low — clean |
| JitPack dependency | `com.github.requery:sqlite-android` via JitPack | **Critical** — blocks F-Droid |
| Clojars dependency | Listed in `settings.gradle.kts` (unused at compile time) | Low — remove unused repo |
| `versionCode` increment automation | Hardcoded `versionCode = 1` | Medium — needs automation |
| App metadata directory | Missing `fastlane/metadata/android/` | Medium |
| Build recipe | No `fdroiddata` YAML | Medium |
| Reproducible build declaration | Not configured | Low (optional) |

### Critical Blocker: JitPack / sqlite-android

`kmp/build.gradle.kts` declares:
```
implementation("com.github.requery:sqlite-android:3.49.0")
```
This resolves via JitPack (`https://jitpack.io`) because `sqlite-android` is a GitHub repo, not published to Maven Central. F-Droid's build server does not have internet access during the build (after a `gradle dependencies` prefetch step) and explicitly rejects JitPack as a source.

The replacement is the `androidx.sqlite` bundled SQLite or the official `org.xerial:sqlite-jdbc` (for JVM), but on Android the correct replacement for a custom SQLite version is to pin the version via `app.cash.sqldelight:android-driver` and remove the `sqlite-android` override entirely, or migrate to the requery artifact published directly to Maven Central (`io.requery:sqlite-android`). Verification is required to confirm which artifact is available on Maven Central at the required version.

---

## Epic

**FDROID-0**: As a privacy-conscious Android user, I want to install SteleKit directly from F-Droid so that I receive automatic updates without needing Google Play or sideloading APKs from GitHub.

---

## Stories and Atomic Tasks

### Phase 1: Eliminate Ineligible Dependencies [Blocking]

**FDROID-1**: As a maintainer, I need all Android dependencies to resolve from Maven Central or Google's Maven repository so that F-Droid's build server can compile the app without network policy violations.

#### FDROID-1.1 — Audit and replace `sqlite-android` JitPack dependency

**Source set**: `androidMain`

**Files to modify**:
- `kmp/build.gradle.kts`
- `settings.gradle.kts` (remove JitPack repo if no longer needed)

**Investigation steps** (run before coding):
1. Check whether `io.requery:sqlite-android:3.49.0` exists on Maven Central:
   ```
   https://search.maven.org/artifact/io.requery/sqlite-android
   ```
2. If it does, replace `com.github.requery:sqlite-android` with `io.requery:sqlite-android` at the same version.
3. If it does not, evaluate whether the custom SQLite version is strictly required. The `app.cash.sqldelight:android-driver` bundles its own SQLite via the Android framework — the `sqlite-android` dependency may be an override for WAL or FTS support. Check `DriverFactory.android.kt` to understand the actual usage.
4. Run `./gradlew :androidApp:assembleDebug` to confirm the replacement compiles.
5. Run Android unit tests: `./gradlew testDebugUnitTest`

**Acceptance criteria**:
- `settings.gradle.kts` no longer references `https://jitpack.io` (unless another dependency requires it).
- `./gradlew :androidApp:assembleRelease` succeeds without the JitPack resolver.
- All existing Android tests pass.

#### FDROID-1.2 — Remove unused repository declarations

**Files to modify**:
- `settings.gradle.kts`

**What to remove**:
- `maven("https://repo.clojars.org/")` — no production dependency resolves from Clojars; it was used during early KMP evaluation and is now dead weight. Its presence raises F-Droid reviewer questions.
- Confirm `maven("https://oss.sonatype.org/content/repositories/snapshots/")` is required; if only used for a snapshot that has since been released, remove it.

**Acceptance criteria**:
- `./gradlew dependencies` produces no resolution from Clojars or JitPack.
- Build still compiles cleanly.

---

### Phase 2: Version Code Automation [Required]

**FDROID-2**: As a maintainer, I need `versionCode` to increment monotonically with each release so that F-Droid's update detection works correctly.

F-Droid uses `versionCode` (integer) to determine whether an update is available. The current hardcoded `versionCode = 1` in `androidApp/build.gradle.kts` means every release looks like the same version to the store.

#### FDROID-2.1 — Derive `versionCode` from `appVersion`

**Files to modify**:
- `androidApp/build.gradle.kts`

**Implementation**:

Replace the hardcoded `versionCode = 1` with a derivation from `appVersion`. A standard approach for semver `MAJOR.MINOR.PATCH` is:

```
versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
```

For `appVersion = "0.3.0"` this yields `versionCode = 300`. This scheme supports up to 99 minor versions and 99 patch versions before overflowing the minor slot, which is sufficient for this project's current trajectory.

```kotlin
defaultConfig {
    applicationId = "dev.stapler.stelekit"
    minSdk = 24
    targetSdk = 36

    val rawVersion = (findProperty("appVersion") as? String ?: "0.1.0")
    val vparts = rawVersion.split(".").map { it.toIntOrNull() ?: 0 }
    versionCode = vparts.getOrElse(0) { 0 } * 10000 +
                  vparts.getOrElse(1) { 0 } * 100 +
                  vparts.getOrElse(2) { 0 }
    versionName = rawVersion
}
```

**Acceptance criteria**:
- `./gradlew :androidApp:assembleRelease -PappVersion=0.3.0` produces an APK with `versionCode=300` and `versionName=0.3.0`.
- `./gradlew :androidApp:assembleRelease -PappVersion=0.4.0` produces `versionCode=400`.
- The release CI workflow (`release.yml`) already passes `-PappVersion` so no workflow changes are needed.

---

### Phase 3: App Metadata [Required for Submission]

**FDROID-3**: As a maintainer, I need standardized app metadata in the `fastlane/metadata/android/` directory so that F-Droid can display the app's description, screenshots, and changelogs.

F-Droid reads metadata from either `fastlane/metadata/android/` (Fastlane supply format) or `metadata/` in the `fdroiddata` repo. The in-repo Fastlane format is preferred because it keeps metadata close to the code and is automatically picked up by `fdroid update`.

#### FDROID-3.1 — Create Fastlane metadata directory structure

**Files to create**:
```
fastlane/metadata/android/en-US/
fastlane/metadata/android/en-US/title.txt
fastlane/metadata/android/en-US/short_description.txt
fastlane/metadata/android/en-US/full_description.txt
fastlane/metadata/android/en-US/changelogs/
```

**Content for `title.txt`** (30 chars max):
```
SteleKit
```

**Content for `short_description.txt`** (80 chars max):
```
Markdown-based outliner and note-taking app — a Logseq-compatible client.
```

**Content for `full_description.txt`** (4000 chars max, plain text or limited HTML):
```
SteleKit is a local-first, privacy-respecting outliner and note-taking app built on the Logseq file format. Your notes are stored as plain Markdown files on your device — no account, no cloud sync, no telemetry.

Features:
- Block-based outlining with nested bullet hierarchies
- Markdown rendering with wiki-link navigation ([[Page Name]])
- Journal view for daily notes
- Graph-wide full-text search
- Multi-graph support for separate workspaces
- Storage Access Framework integration: open any folder from your device or SD card
- Material You dynamic color theming

SteleKit reads and writes the same .md files as Logseq Desktop, making it compatible with existing Logseq graphs. It is built with Kotlin Multiplatform and Jetpack Compose.

Source code: https://github.com/tstapler/stelekit
License: Elastic License 2.0
```

**Acceptance criteria**:
- All four text files exist and are within their character limits.
- F-Droid's `fdroid checkupdates` does not produce metadata validation errors.

#### FDROID-3.2 — Add per-release changelogs

**Pattern**: For each release `versionCode`, create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with a plain-text summary of changes for that release. Maximum 500 characters.

**Seed file** for the current release (versionCode 300 → v0.3.0):
```
fastlane/metadata/android/en-US/changelogs/300.txt
```

**Process to document**: Add changelog file creation to the release checklist. The content should mirror the top section of the GitHub release notes.

**Acceptance criteria**:
- At least one changelog file exists for the most recent `versionCode`.
- F-Droid `fdroid update` parses the changelog without errors.

#### FDROID-3.3 — Add screenshots (optional but recommended)

**Files to create**:
```
fastlane/metadata/android/en-US/images/phoneScreenshots/01.png
fastlane/metadata/android/en-US/images/phoneScreenshots/02.png
fastlane/metadata/android/en-US/images/phoneScreenshots/03.png
```

Screenshots must be taken from an actual Android device or emulator. Recommended resolution: 1080x1920 (portrait). Capture:
1. The journal view with several blocks of content.
2. The page navigator / all-pages list.
3. The full-text search dialog with results.

**Acceptance criteria**:
- At least two screenshots are present.
- Images are < 2 MB each (PNG or JPEG).

---

### Phase 4: F-Droid Build Recipe [Required for Submission]

**FDROID-4**: As a maintainer, I need an accurate F-Droid build recipe that reproduces the APK from source so that F-Droid's build server can compile and verify SteleKit.

F-Droid submissions go to the `fdroid/fdroiddata` repository on GitLab as a YAML file. The file lives at:
```
metadata/dev.stapler.stelekit.yml
```
within `fdroiddata`, but a copy should be kept in this repo under `fdroid/` for reference and local validation.

#### FDROID-4.1 — Create `fdroid/dev.stapler.stelekit.yml` reference recipe

**File to create**: `fdroid/dev.stapler.stelekit.yml`

**Template** (adapt version numbers before each submission):

```yaml
Categories:
  - Office
  - Writing

License: Elastic-2.0

WebSite: https://github.com/tstapler/stelekit
SourceCode: https://github.com/tstapler/stelekit
IssueTracker: https://github.com/tstapler/stelekit/issues

AutoName: SteleKit

RepoType: git
Repo: https://github.com/tstapler/stelekit

Builds:
  - versionName: 0.3.0
    versionCode: 300
    commit: v0.3.0
    subdir: androidApp
    gradle:
      - release
    prebuild:
      - echo sdk.dir=$ANDROID_HOME > local.properties
    build:
      - ./gradlew :androidApp:assembleRelease
          -PappVersion=$$VERSION$$
          --no-daemon
          --no-build-cache
    output: androidApp/build/outputs/apk/release/app-release-unsigned.apk

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.3.0
CurrentVersionCode: 300
```

**Key recipe fields explained**:
- `subdir: androidApp` — F-Droid runs Gradle from the `androidApp/` subdirectory. Since `settings.gradle.kts` is at the repo root (not in `androidApp/`), this field should be omitted and the `build:` command should invoke the task with the full module path (`:androidApp:assembleRelease`).
- `gradle: [release]` — tells F-Droid to use the `release` build variant.
- `output:` — the path to the unsigned APK relative to the repo root.
- `prebuild:` — writes `local.properties` so Gradle can locate the Android SDK.
- `$$VERSION$$` — F-Droid substitutes the `versionName` value here.

**Note on `subdir`**: Because the root `settings.gradle.kts` includes both `:kmp` and `:androidApp`, F-Droid must be invoked from the repo root (not from `androidApp/`). Remove `subdir` and update `output` path accordingly if F-Droid validation fails.

**Acceptance criteria**:
- `fdroid build dev.stapler.stelekit` completes successfully in a local F-Droid build environment (using `fdroidserver`).
- The generated APK installs and launches on a physical Android device.

#### FDROID-4.2 — Verify unsigned release build works

The current `androidApp/build.gradle.kts` conditionally applies signing only when `ANDROID_KEYSTORE_PATH` env var is set:
```kotlin
if (releaseSigningConfig.storeFile != null) {
    signingConfig = releaseSigningConfig
}
```

This already produces an unsigned APK when env vars are absent, which is what F-Droid needs. Verify this explicitly:

```bash
./gradlew :androidApp:assembleRelease -PappVersion=0.3.0
# Must produce: androidApp/build/outputs/apk/release/app-release-unsigned.apk
```

**Acceptance criteria**:
- `assembleRelease` without any `ANDROID_KEYSTORE_*` env vars produces `app-release-unsigned.apk`.
- The APK can be installed (with `adb install`) and the app launches.

---

### Phase 5: Submission to fdroiddata [Deployment]

**FDROID-5**: As a maintainer, I need to submit the build recipe to the `fdroid/fdroiddata` repository so that F-Droid's servers include SteleKit in the next index update.

#### FDROID-5.1 — Fork and submit to `fdroid/fdroiddata`

**Steps** (manual, one-time):
1. Fork `https://gitlab.com/fdroid/fdroiddata` on GitLab.
2. Create a branch: `add-dev.stapler.stelekit`.
3. Copy `fdroid/dev.stapler.stelekit.yml` (from this repo) to `metadata/dev.stapler.stelekit.yml` in the fork.
4. Run local validation:
   ```bash
   fdroid readmeta  # validates YAML structure
   fdroid checkupdates dev.stapler.stelekit  # checks version detection
   fdroid build dev.stapler.stelekit  # full build test
   ```
5. Open a merge request against `fdroid/fdroiddata` with:
   - Title: `New app: SteleKit (dev.stapler.stelekit)`
   - Body: brief description, confirm no proprietary dependencies, link to source code.
6. Respond to reviewer comments. Common feedback categories:
   - License field verification (Elastic-2.0 is an allowed license on F-Droid).
   - Build reproducibility questions.
   - `AntiFeatures` flags if any feature uses non-free network services.

**Acceptance criteria**:
- MR is opened on `fdroid/fdroiddata`.
- F-Droid CI pipeline on the MR passes (`fdroid build` succeeds).
- MR is merged by an F-Droid maintainer.

#### FDROID-5.2 — Update release process for ongoing maintenance

Once accepted, every new release requires updating the build recipe in `fdroiddata`. Document this in the release checklist:

**Files to modify**:
- `.github/workflows/release.yml` (add a note/step for F-Droid)
- `CONTRIBUTING.md` or equivalent (add F-Droid update instructions)

**Per-release process**:
1. Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with release notes.
2. Open a PR to `fdroid/fdroiddata` adding a new entry under `Builds:` in `metadata/dev.stapler.stelekit.yml`.
3. Update `CurrentVersion` and `CurrentVersionCode` in the recipe.

Alternatively, F-Droid's `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` can be configured so F-Droid automatically detects new tags and queues builds without a manual MR per release. Verify that `versionCode` derivation from the git tag is consistent for this to work.

---

## Known Issues

### Potential Blocker: Elastic License 2.0 Eligibility

**Description**: F-Droid requires apps to use a Free and Open Source Software (FOSS) license. Elastic License 2.0 (ELv2) is **not** approved by the Open Source Initiative (OSI) and is **not** considered a FOSS license. F-Droid's policy is to only include apps under OSI-approved or FSF-approved licenses.

**Impact**: This is a potential hard blocker for F-Droid inclusion. F-Droid reviewers will reject the MR if the license is ELv2-only.

**Mitigation options**:
1. Re-license SteleKit under an OSI-approved license (MIT, Apache 2.0, GPL-3.0). This is the cleanest path but requires a licensing decision from the project owner.
2. Dual-license: offer the app under GPL-3.0 for F-Droid distribution, ELv2 for commercial use. This is legally complex.
3. Accept that F-Droid distribution may not be possible under the current license and explore alternatives (self-hosted F-Droid repo, IzzyOnDroid, Obtainium).

**Recommendation**: Resolve license eligibility before investing time in the technical tasks above. Open a discussion with the F-Droid team (`#fdroid` on Matrix/IRC) to confirm whether ELv2 is accepted before submitting the MR.

**Affected files**: `LICENSE`, `kmp/build.gradle.kts` (SPDX header), `androidApp/build.gradle.kts` (SPDX header), all source files with `SPDX-License-Identifier: Elastic-2.0`.

---

### Potential Bug: `sqlite-android` Replacement Breaks WAL or FTS

**Description**: `sqlite-android` provides a newer SQLite version than the one bundled in the Android OS (which can be as old as 3.7 on API 24 devices). If SQLDelight queries use features from SQLite 3.35+ (e.g., `RETURNING`, window functions, or specific FTS5 capabilities), removing `sqlite-android` may cause runtime failures on older Android versions.

**Mitigation**:
- Audit `SteleDatabase.sq` for features not supported by Android's bundled SQLite at API 24.
- Run the Android unit test suite with `org.robolectric:robolectric` configured to use the platform SQLite (not sqlite-android) to catch regressions before physical device testing.
- If FTS5 or WAL2 is required, use `io.requery:sqlite-android` from Maven Central (same library, different artifact coordinates) rather than the JitPack source.

---

### Potential Bug: `versionCode` Collision on Non-Semver Tags

**Description**: The proposed `versionCode` formula (`MAJOR*10000 + MINOR*100 + PATCH`) assumes all release tags are semver. If a tag like `v0.3.0-beta.1` or `v0.3.1-rc1` is used, the `-PappVersion` value will contain non-numeric characters and the `toIntOrNull()` fallback will produce `versionCode = 0`, which is invalid.

**Mitigation**:
- Strip pre-release suffixes in the `versionCode` calculation:
  ```kotlin
  val cleanVersion = rawVersion.substringBefore("-")
  val vparts = cleanVersion.split(".").map { it.toIntOrNull() ?: 0 }
  ```
- Add a Gradle task that validates `versionCode > 0` before `assembleRelease`.

---

### Integration Risk: F-Droid Build Environment Java Version

**Description**: F-Droid's build server runs a specific JDK version (currently JDK 17 for most recipes, with JDK 21 available via `ndk:` or `build:` flags). SteleKit requires `jvmToolchain(21)` in both the KMP module and `androidApp`. If the F-Droid build server defaults to JDK 17, the build will fail.

**Mitigation**:
- Add `forceverion: 21` (or the equivalent F-Droid YAML key, which varies by fdroidserver version) to the build recipe to request JDK 21.
- Monitor the `fdroid/fdroiddata` repository for the correct syntax by examining other apps that require JDK 21 (e.g., apps using AGP 8.x).

---

## Dependency Visualization

```
Phase 1: Dependency Audit
  FDROID-1.1 (replace sqlite-android)
  FDROID-1.2 (remove unused repos)
          |
          v
Phase 2: Version Automation
  FDROID-2.1 (versionCode derivation)
          |
          v
Phase 3: Metadata
  FDROID-3.1 (text metadata)
  FDROID-3.2 (changelogs)
  FDROID-3.3 (screenshots)
          |
          v
Phase 4: Build Recipe
  FDROID-4.1 (write YAML recipe)
  FDROID-4.2 (verify unsigned build)
          |
          v
Phase 5: Submission
  FDROID-5.1 (open MR to fdroiddata)
  FDROID-5.2 (ongoing release process)
```

---

## Context Preparation Guide

**Files relevant to implementation**:
- `/home/tstapler/Programming/stelekit/androidApp/build.gradle.kts` — versionCode fix, signing config review
- `/home/tstapler/Programming/stelekit/kmp/build.gradle.kts` — sqlite-android replacement in androidMain dependencies
- `/home/tstapler/Programming/stelekit/settings.gradle.kts` — repository declarations to audit
- `/home/tstapler/Programming/stelekit/androidApp/src/main/AndroidManifest.xml` — permissions audit (INTERNET permission is present; no proprietary service permissions detected)
- `/home/tstapler/Programming/stelekit/.github/workflows/release.yml` — reference for how `assembleRelease` is invoked
- `/home/tstapler/Programming/stelekit/kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt` — understand sqlite-android usage before replacing

**External resources**:
- F-Droid inclusion policy: `https://f-droid.org/en/docs/Inclusion_Policy/`
- F-Droid build metadata reference: `https://f-droid.org/en/docs/Build_Metadata_Reference/`
- fdroidserver local testing: `https://f-droid.org/en/docs/Installing_the_Server_and_Repo_Tools/`
- fdroiddata submission guide: `https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md`
- IzzyOnDroid (alternative repo, less strict): `https://apt.izzysoft.de/fdroid/`
