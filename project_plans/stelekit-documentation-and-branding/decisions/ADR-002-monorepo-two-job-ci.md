# ADR-002: Monorepo Two-Job CI over Separate Site Repo

**Status**: Accepted
**Date**: 2026-04-18
**Deciders**: Tyler Stapler

---

## Context

The GitHub Pages site requires a Compose Multiplatform Wasm demo artifact (from Gradle) embedded inside an Astro-built site. These are two distinct build systems that must produce a single Pages deployment. The architectural question is whether to keep both build systems in the same repository and coordinate them in CI, or to separate them.

Candidates evaluated: (1) monorepo, two-job CI workflow; (2) committed `docs/` directory; (3) separate `stelekit-site` repository; (4) demo loaded from GitHub Releases asset.

## Decision

Keep everything in the same repository. Use a single **`pages.yml`** GitHub Actions workflow with two sequential build jobs:

1. **`build-demo`** — runs on `ubuntu-latest`, sets up JDK 21 + Gradle cache, executes `./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true`, and uploads the output directory (`kmp/build/dist/wasmJs/productionExecutable/`) as a workflow artifact named `demo-dist`.
2. **`build-site`** — runs on `ubuntu-latest`, downloads the `demo-dist` artifact into `site/public/demo/`, sets up Node 20 with `npm` cache keyed to `site/package-lock.json`, runs `npm ci && npm run build` in `site/`, and uploads the Astro output (`site/dist/`) via `actions/upload-pages-artifact`.
3. **`deploy`** — calls `actions/deploy-pages` against the uploaded artifact.

The workflow triggers on `push` to `main`, `paths` filter applied so site-only edits do not trigger the full Gradle build (see consequences), and `workflow_dispatch` for manual deploys.

## Rationale

**Solo maintainer DX.** The separate-repo option requires cross-repo artifact coordination: the site repo must download the demo artifact from another repo's workflow, which requires either a published GitHub Release or a cross-repo `actions/download-artifact` with token-scoped permissions. For a solo developer this is operational overhead with no benefit.

**Gradle cache reuse.** The monorepo keeps the Gradle dependency cache in the same `ubuntu-latest` job that has access to the project root. A separate site repo would require publishing the demo as a release asset on every commit to `main`, then downloading it in the site repo. Gradle caches would not be shared.

**No secrets beyond `GITHUB_TOKEN`.** The `actions/deploy-pages` action is built into GitHub Actions and requires only `permissions: pages: write` and `id-token: write` in the workflow file. No additional tokens or deploy keys are needed.

**Committed `docs/` model rejected.** Committing built output to `main` inflates git history with multi-megabyte binary artifacts on every push. The wasmJs bundle alone is several megabytes. Over dozens of commits, the repository approaches GitHub's 1 GB soft limit. `actions/upload-pages-artifact` + `actions/deploy-pages` routes built files through the GitHub Pages staging area without entering git history at all.

**GitHub Releases artifact model rejected.** The demo would only update on tagged releases rather than on every push to `main`. The site would lag behind the app. CORS behavior for GitHub Release asset URLs is not guaranteed to be permissive for cross-origin `fetch()` requests.

## Consequences

**Path filters prevent unnecessary Gradle runs.** A change to `site/src/` only (updating a docs page) should not trigger the 15–20 minute Gradle wasmJs build. The `pages.yml` workflow uses a `paths` filter split:

- Changes under `kmp/`, `gradle/`, `*.gradle.kts`, `.github/workflows/` trigger both jobs.
- Changes only under `site/` skip `build-demo` and use a cached or pre-built demo artifact. Implementation: the `build-demo` job uses `if: contains(github.event.head_commit.modified, 'kmp/') || github.event_name == 'workflow_dispatch'` (or a GitHub Actions paths filter on the workflow trigger). When skipped, `build-site` downloads the most recent `demo-dist` artifact from the last successful workflow run.

**Two separate cache scopes.** The Gradle cache (keyed to Gradle wrapper and dependency lock files) and the Node `node_modules` cache (keyed to `site/package-lock.json`) must be configured independently. Mixing them in one job would produce incorrect cache invalidation.

**Wasm build time is the pipeline bottleneck.** Cold `wasmJsBrowserDistribution` takes 15–20 minutes. Site-only changes must not pay this cost. The path filter strategy above addresses this, but the implementation requires careful job dependency setup: `build-site` must be able to run without waiting for a fresh `build-demo` if no app code changed.

**`build-demo` failure blocks deploy.** If the Gradle build fails, the whole pipeline halts. This is acceptable: a broken Wasm artifact should not be deployed. The demo page must degrade gracefully if the artifact does not load (show screenshots), but that is a runtime concern, not a CI concern.
