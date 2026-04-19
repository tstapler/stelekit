# F-Droid Self-Hosted Repository Setup

**Feature**: Self-hosted F-Droid repository via GitHub Pages
**Status**: Planning
**Last Updated**: 2026-04-18

---

## Context

Rather than submitting to the main F-Droid repository (which requires OSI-approved licensing and bans JitPack dependencies), SteleKit will host its own F-Droid-compatible repository. Users add a custom repo URL to their F-Droid client and get automatic update notifications, just like the main store.

**How it works**:
1. GitHub Actions builds and signs the APK (existing pipeline already does this)
2. `fdroidserver` generates a signed repo index (`index-v2.json`) listing the APK
3. The index + APKs are deployed to GitHub Pages
4. Users add `https://tstapler.github.io/stelekit/fdroid/repo` to F-Droid

**Advantages over main F-Droid submission**:
- No license restrictions (ELv2 is fine)
- No dependency restrictions (JitPack allowed)
- You control the signing key (users install *your* signed APK, not a re-signed one)
- No reviewer queue — updates go live immediately on release

**Tradeoff**: Users must manually add the custom repo URL once.

---

## Current State

| Requirement | Current State | Gap |
|---|---|---|
| Signed APK produced by CI | Yes — `release.yml` builds `app-release.apk` | None |
| `versionCode` increments per release | Hardcoded `versionCode = 1` | Medium — F-Droid update detection broken |
| App metadata (title, description) | Missing `fastlane/metadata/android/` | Low — needed for F-Droid store listing |
| fdroid repo index generation | Not configured | Medium — new CI job needed |
| GitHub Pages hosting | Not configured | Low — enable in repo settings |

---

## Stories and Atomic Tasks

### Story 1: Fix versionCode automation [1 day]

F-Droid uses `versionCode` (integer) to detect updates. The hardcoded `versionCode = 1` means every release looks identical.

#### Task 1.1 — Derive `versionCode` from `appVersion` semver

**Files**: `androidApp/build.gradle.kts`

**Implementation**: Replace `versionCode = 1` with a semver derivation:

```kotlin
val rawVersion = (findProperty("appVersion") as? String ?: "0.1.0")
val cleanVersion = rawVersion.substringBefore("-") // strip pre-release suffixes
val vparts = cleanVersion.split(".").map { it.toIntOrNull() ?: 0 }
versionCode = vparts.getOrElse(0) { 0 } * 10000 +
              vparts.getOrElse(1) { 0 } * 100  +
              vparts.getOrElse(2) { 0 }
versionName = rawVersion
```

For `appVersion=0.3.0` → `versionCode=300`. Supports up to 99 minor / 99 patch before overflow.

**Acceptance criteria**:
- `./gradlew :androidApp:assembleRelease -PappVersion=0.3.0` → APK has `versionCode=300`
- `./gradlew :androidApp:assembleRelease -PappVersion=0.4.0` → APK has `versionCode=400`
- Release CI already passes `-PappVersion` so no workflow changes needed

---

### Story 2: App metadata [half day]

F-Droid reads the app's store listing from a `fastlane/metadata/android/` directory in the repo.

#### Task 2.1 — Create Fastlane metadata structure

**Files to create**:
```
fastlane/metadata/android/en-US/title.txt            (30 chars max)
fastlane/metadata/android/en-US/short_description.txt (80 chars max)
fastlane/metadata/android/en-US/full_description.txt  (4000 chars max)
fastlane/metadata/android/en-US/changelogs/300.txt    (500 chars, for v0.3.0)
```

**Content for `title.txt`**:
```
SteleKit
```

**Content for `short_description.txt`**:
```
Local-first Markdown outliner — a Logseq-compatible Android client.
```

**Content for `full_description.txt`**:
```
SteleKit is a local-first outliner and note-taking app built on the Logseq
file format. Notes are stored as plain Markdown files on your device — no
account, no cloud sync, no telemetry.

Features:
• Block-based outlining with nested bullet hierarchies
• Markdown rendering with [[wiki-link]] navigation
• Journal view for daily notes
• Graph-wide full-text search
• Multi-graph support
• Storage Access Framework: open any folder on device or SD card
• Material You dynamic color theming

SteleKit reads and writes the same .md files as Logseq Desktop.
```

**Acceptance criteria**:
- Files exist and are within character limits
- `fdroid update` parses metadata without errors

#### Task 2.2 — Add per-release changelog process

For each release, create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

Add a step to the release checklist: before tagging, create the changelog file with plain-text release notes (max 500 chars, mirrors GitHub release notes summary).

---

### Story 3: fdroid repo generation and hosting [1 day]

#### Task 3.1 — Create fdroid repo configuration

**Files to create**: `fdroid/config.yml`

`fdroidserver` reads this to know what to sign and where to put the output.

```yaml
repo_url: https://tstapler.github.io/stelekit/fdroid/repo
repo_name: SteleKit
repo_description: SteleKit — self-hosted F-Droid repository
repo_icon: icon.png

archive_older: 3   # keep last 3 versions in the main repo; older go to archive

# Keystore is provided via CI environment variables (see below)
```

The repo signing key is separate from the APK signing key. `fdroid init` generates it. Store the keystore as a GitHub Actions secret.

**Directory structure** (committed to repo):
```
fdroid/
  config.yml         ← fdroidserver config
  repo/              ← generated index (committed or CI artifact)
    index-v2.json    ← generated by fdroid update
    *.apk            ← copied APKs
  archive/           ← older versions (generated)
```

**Note**: The `fdroid/repo/` directory should be committed to the repo (or deployed as a GitHub Pages artifact). The APKs themselves can be symlinked from GitHub Releases rather than committed to git.

#### Task 3.2 — GitHub Actions: generate and deploy fdroid repo

**File**: `.github/workflows/fdroid.yml`

This workflow runs after a successful release build (triggered by `release` event or tag push), downloads the signed APK from GitHub Releases, runs `fdroid update` to regenerate the index, and deploys to GitHub Pages.

```yaml
name: F-Droid Repo Update

on:
  release:
    types: [published]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

jobs:
  update-fdroid:
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - uses: actions/checkout@v4

      - name: Install fdroidserver
        run: |
          sudo add-apt-repository ppa:fdroid/fdroidserver
          sudo apt-get update
          sudo apt-get install -y fdroidserver

      - name: Restore fdroid keystore
        env:
          FDROID_KEYSTORE_BASE64: ${{ secrets.FDROID_KEYSTORE_BASE64 }}
        run: |
          echo "$FDROID_KEYSTORE_BASE64" | base64 -d > fdroid/keystore.jks

      - name: Write fdroid secrets to config
        env:
          FDROID_KEY_ALIAS: ${{ secrets.FDROID_KEY_ALIAS }}
          FDROID_KEY_PASS: ${{ secrets.FDROID_KEY_PASS }}
          FDROID_STORE_PASS: ${{ secrets.FDROID_STORE_PASS }}
        run: |
          cat >> fdroid/config.yml <<EOF
          keystore: keystore.jks
          repo_keyalias: $FDROID_KEY_ALIAS
          keystorepass: $FDROID_STORE_PASS
          keypass: $FDROID_KEY_PASS
          EOF

      - name: Download APK from release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          VERSION="${{ github.event.release.tag_name }}"
          VERSION="${VERSION#v}"  # strip leading 'v'
          gh release download "${{ github.event.release.tag_name }}" \
            --pattern "*.apk" \
            --dir fdroid/repo/

      - name: Generate fdroid index
        working-directory: fdroid
        run: |
          fdroid update --create-metadata --verbose

      - name: Upload Pages artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: fdroid/

      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v4
```

**GitHub Actions secrets required**:
- `FDROID_KEYSTORE_BASE64` — `base64 -w0 fdroid-keystore.jks`
- `FDROID_KEY_ALIAS` — alias for the fdroid repo signing key
- `FDROID_KEY_PASS` — key password
- `FDROID_STORE_PASS` — keystore password

**GitHub Pages setup** (one-time, manual):
- Repo Settings → Pages → Source: GitHub Actions

#### Task 3.3 — Generate the fdroid signing key (one-time local setup)

Run locally, commit the public cert, store the keystore as a secret:

```bash
cd fdroid/
fdroid init
# This generates keystore.jks and updates config.yml with the fingerprint

# Export keystore for CI
base64 -w0 keystore.jks > keystore.jks.b64
# → paste keystore.jks.b64 contents into FDROID_KEYSTORE_BASE64 secret
# → delete keystore.jks.b64

# Commit only the public cert (NOT the keystore):
git add config.yml repo/
git add -N keystore.jks  # add to .gitignore instead
```

Add `fdroid/keystore.jks` to `.gitignore` — it must never be committed.

**Acceptance criteria**:
- `fdroid/config.yml` contains `repo_pubkey` fingerprint
- `fdroid/keystore.jks` is in `.gitignore`
- Keystore base64 is stored in GitHub Actions secrets

---

### Story 4: User-facing documentation [half day]

#### Task 4.1 — Add install instructions to README

Add a section to `README.md` (or create one if absent) explaining how to add the custom repo:

```
## Install via F-Droid

1. Install [F-Droid](https://f-droid.org/)
2. In F-Droid → Settings → Repositories → add:
   https://tstapler.github.io/stelekit/fdroid/repo
3. Search for "SteleKit" and install.

Or scan the QR code: [link to generated QR]
```

The F-Droid repo index page (auto-generated by `fdroidserver`) also provides a QR code and "Add to F-Droid" button.

---

## Dependency Visualization

```
Task 1.1 (versionCode fix)
        |
        v
Task 2.1 (metadata)  ←── Task 2.2 (changelog process)
        |
        v
Task 3.3 (generate signing key)  [one-time, local]
        |
        v
Task 3.1 (fdroid/config.yml)
        |
        v
Task 3.2 (GitHub Actions workflow)
        |
        v
Task 4.1 (README install instructions)
```

---

## Integration Checkpoints

**After Story 1**: `./gradlew :androidApp:assembleRelease -PappVersion=0.3.0` produces APK with `versionCode=300`.

**After Story 2**: `fdroid update` (run locally) parses metadata without errors; store listing looks correct.

**After Story 3**: Pushing a release tag triggers the workflow; `https://tstapler.github.io/stelekit/fdroid/repo` returns a valid `index-v2.json`; F-Droid client can add the repo and see SteleKit.

**After Story 4**: README has clear install instructions with repo URL.

---

## Known Issues

### versionCode collision on pre-release tags

If a tag like `v0.3.0-beta.1` is used, the `-PappVersion` value contains non-numeric characters. The `substringBefore("-")` strip in Task 1.1 handles this — `0.3.0-beta.1` → `0.3.0` → `versionCode=300`. Pre-releases would share a `versionCode` with the stable release; use a separate beta track or skip `versionCode` increments for pre-releases.

### APK filename must match what fdroid expects

`fdroid update` matches APK filenames to app IDs. Ensure the release APK is named `dev.stapler.stelekit_<versionCode>.apk` or let `fdroid update --create-metadata` rename it. Check the actual filename produced by `assembleRelease`.

### GitHub Pages size limits

GitHub Pages has a 1 GB soft limit per repo. Each APK is ~20-50 MB. Keeping `archive_older: 3` in `config.yml` bounds storage growth. Older APKs in the archive can be removed periodically.

### fdroidserver version on Ubuntu runners

The PPA (`ppa:fdroid/fdroidserver`) provides a recent version of fdroidserver. Pin a specific version if the build becomes sensitive to fdroidserver API changes.

---

## Success Criteria

- [ ] `versionCode` increments automatically with each semver release tag
- [ ] Fastlane metadata directory populated with title, description, and changelogs
- [ ] `fdroid/config.yml` committed, `fdroid/keystore.jks` gitignored
- [ ] GitHub Actions workflow deploys to GitHub Pages on each release
- [ ] `https://tstapler.github.io/stelekit/fdroid/repo/index-v2.json` returns valid JSON
- [ ] F-Droid client can add the repo URL and install SteleKit
- [ ] README documents the custom repo URL
