# ADR-004: Remote Cache via BuildBuddy Free Tier

## Status
Accepted

## Date
2026-05-17

## Context

Bazel's value proposition in CI depends heavily on a shared remote cache. Without it,
every CI job starts cold and rebuilds all targets from scratch, negating incremental-build
benefits.

Two remote-cache options are commonly evaluated for open or small-team projects:

### GitHub Actions Cache

GitHub Actions provides a built-in cache (`actions/cache`) with these properties:
- **10 GB total limit** shared across all branches and workflows in a repository.
- **HTTP REST API only** — not gRPC. Bazel's native remote cache protocol is gRPC; using
  it with GitHub's REST cache requires wrapping with a local proxy (e.g., `bazel-remote`
  running as a sidecar in the workflow) or a compatibility layer.
- **No build event visibility** — GitHub's cache is opaque; there is no UI to inspect what
  was cached, hit rates, or per-target timing.
- **Branch scoping** — cache entries from a branch are not visible to other branches unless
  the base branch was cached first. This means PR builds often miss the cache entirely on
  the first run.
- **Cache eviction is unpredictable** — GitHub evicts entries after 7 days of inactivity
  and when the 10 GB limit is reached, with no control over which entries are evicted.

### BuildBuddy Free Tier

BuildBuddy (buildbuddy.io) is a cloud Bazel build platform with a free tier offering:
- **Native gRPC remote cache** — Bazel connects directly via `--remote_cache=grpcs://...`
  with no proxy required.
- **Build Event Protocol (BEP) viewer** — a web UI showing per-action timing, cache hit
  rates, test results, and build logs, accessible at `app.buildbuddy.io`.
- **No branch scoping** — all CI jobs share the same cache namespace, so PR builds
  immediately benefit from cache entries populated by `main` branch builds.
- **Larger effective cache** — the free tier does not publish a hard cache size limit
  comparable to GitHub's 10 GB; in practice, BuildBuddy's cache is substantially larger
  for typical project sizes.
- **Authentication via API key** — a single `BUILDBUDDY_API_KEY` GitHub secret is required.

The free tier has no SLA and no guaranteed uptime, but cache misses are graceful: Bazel
falls back to a full local build if the remote cache is unavailable. This is an acceptable
failure mode for CI.

## Decision

SteleKit's Bazel CI will use **BuildBuddy free tier** for remote caching.

The `.bazelrc` will include:

```
# Remote cache (BuildBuddy)
build:ci --remote_cache=grpcs://remote.buildbuddy.io
build:ci --remote_header=x-buildbuddy-api-key=${BUILDBUDDY_API_KEY}
build:ci --remote_upload_local_results=true
build:ci --bes_backend=grpcs://remote.buildbuddy.io
build:ci --bes_results_url=https://app.buildbuddy.io/invocation/
```

CI workflows will pass `--config=ci` when running `bazel build` or `bazel test`.

A `BUILDBUDDY_API_KEY` secret must be configured in the GitHub repository settings
(Settings → Secrets → Actions). The key is obtained from the BuildBuddy free-tier
dashboard at `app.buildbuddy.io`.

Local developer builds do **not** use the remote cache by default (no `--config=ci` in
the local `.bazelrc.user` template), preventing accidental cache poisoning from
non-hermetic local environments.

## Consequences

**Positive:**
- Native gRPC protocol — no proxy sidecar, no extra CI step, no compatibility workarounds.
- Build Event Protocol viewer gives immediate visibility into cache hit rates and per-target
  timing, making it easy to identify BUILD file changes that break caching.
- Cross-branch cache sharing means PR builds benefit from cache entries built on `main`.
- Zero cost for the project's scale; free tier is sufficient for a single-team project.

**Negative:**
- **No SLA on the free tier.** If BuildBuddy's free cache is unavailable, CI falls back to
  a full cold build (slower but not broken). Teams should not depend on the free tier for
  latency-sensitive release pipelines.
- **`BUILDBUDDY_API_KEY` GitHub secret is required.** New repository forks and contributor
  CI runs on forks will not have the secret and will build without the remote cache (cold
  builds). This is acceptable for external contributors but means their CI runs will be
  slower.
- **Dependency on a third-party service.** If BuildBuddy discontinues the free tier,
  migration to an alternative (self-hosted `bazel-remote`, GitHub Actions cache with proxy,
  or a paid BuildBuddy/EngFlow plan) is required. The `.bazelrc` configuration is
  standardized Bazel remote-cache flags, so migration is low-friction.

## Alternatives Considered

**GitHub Actions cache with `bazel-remote` sidecar**: Viable but adds operational
complexity (running a sidecar service in the workflow, managing its lifecycle and port).
The 10 GB limit is likely insufficient once Bazel's action cache is populated with JVM
and Android build artifacts. The lack of a build event UI makes cache performance opaque.
Rejected in favor of BuildBuddy's simpler integration.

**Self-hosted `bazel-remote` on a VPS**: Full control over cache size and no third-party
dependency, but requires infrastructure provisioning, maintenance, and cost. Appropriate
for larger teams; overkill for the current project scale. Rejected for Phase 1.

**No remote cache**: Simpler setup but defeats a primary motivation for adopting Bazel in
CI. Cold builds of the JVM + Android targets are expected to take 8–15 minutes; a warm
remote cache should reduce this to 2–4 minutes. Rejected.

**BuildBuddy paid tier / EngFlow**: Provides SLAs, remote execution, and enterprise
features. Appropriate if the project grows or if remote execution (not just caching) is
needed. Revisit in Phase 2.
