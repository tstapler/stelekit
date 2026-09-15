# ADR-002: Use MODULE.bazel (Bzlmod) Over WORKSPACE

## Status
Accepted

## Date
2026-05-17

## Context

Bazel supports two external-dependency management systems:

- **WORKSPACE** (legacy): a single file at the repository root that calls repository rules
  (`http_archive`, `maven_install`, etc.) to fetch external dependencies. Transitive
  dependencies must be fetched manually or via `*_repositories()` macro calls, which are
  opaque and conflict-prone.

- **MODULE.bazel / Bzlmod** (current): a module-based system introduced in Bazel 5.2 and
  stabilized in Bazel 6. Each Bazel module declares direct dependencies; Bazel resolves the
  full transitive graph automatically via the Bazel Central Registry (BCR).

The version trajectory is unambiguous:

| Bazel version | WORKSPACE status | MODULE.bazel status |
|---|---|---|
| 7.x | Enabled by default (with flag) | Recommended |
| 8.x (Dec 2024 LTS) | **Disabled by default** | Default |
| 9.x (planned) | **Removed** | Only supported mode |

All rulesets needed for Phase 1 are available on the BCR under Bzlmod:
- `rules_kotlin` v2.3.20
- `rules_android` v0.6.6
- `rules_jvm_external` v6.6+ (includes `maven.from_toml` for Gradle version catalog import)
- `bazel_skylib` v1.7.1
- `robolectric` v4.14.1

Starting with WORKSPACE would require migrating to Bzlmod before Bazel 9 is released
anyway, making it a one-time cost that should be paid upfront.

## Decision

SteleKit's Bazel integration uses **`MODULE.bazel` (Bzlmod) from the first commit**.
No `WORKSPACE` file will be created. The `.bazelrc` will not set
`--noenable_bzlmod` or any flag that re-enables WORKSPACE semantics.

The `MODULE.bazel` will declare:
```python
module(name = "stelekit", version = "0.0.0")

bazel_dep(name = "rules_kotlin",      version = "2.3.20")
bazel_dep(name = "rules_android",     version = "0.6.6")
bazel_dep(name = "rules_jvm_external", version = "6.6")
bazel_dep(name = "bazel_skylib",       version = "1.7.1")
bazel_dep(name = "robolectric",        version = "4.14.1")
```

Maven dependencies will be declared via the `maven` extension from `rules_jvm_external`,
using `maven.from_toml` to import from the existing `gradle/libs.versions.toml` version
catalog where coordinates align, supplemented by inline `maven.install` calls for
Bazel-specific artifact overrides (e.g., explicit `-jvm` classifier variants for KMP
libraries).

## Consequences

**Positive:**
- The project is forward-compatible with Bazel 8 and 9 without any migration work.
- Transitive dependency version resolution is automatic; no manual `*_repositories()` macro
  chains to maintain.
- The BCR provides checksum verification for all declared modules, improving supply-chain
  security.
- `maven.from_toml` keeps Maven artifact versions in a single source of truth
  (`gradle/libs.versions.toml`), shared between Gradle (Gradle builds) and Bazel (JVM/Android builds).

**Negative:**
- Any third-party Bazel rule not yet published to the BCR cannot be referenced without a
  manual `archive_override` or `git_override`, which partially reintroduces WORKSPACE-style
  complexity for those rules.
- Bzlmod's stricter module graph rules (a module can only see its direct deps' repos)
  occasionally require `use_repo` declarations that were implicit under WORKSPACE. This
  adds verbosity when integrating less-mature rulesets.
- Team members unfamiliar with Bzlmod will need to learn its concepts (module extensions,
  `use_repo`, `inject_repo`) rather than the older, more widely documented WORKSPACE
  patterns.

## Alternatives Considered

**Start with WORKSPACE, migrate later**: Avoids Bzlmod learning curve now but guarantees a
forced migration before Bazel 9. Given that the project is starting fresh, paying the
migration cost upfront avoids a second disruption later. Rejected.

**Use Bazel 7.x to retain WORKSPACE as default**: Pins the project to an older LTS that
will lose support before Bazel 9 ships. Creates technical debt from day one. Rejected.
