"""
stelekit_kt_common_library — shared Starlark macro for per-package commonMain Bazel targets.

Wraps kt_jvm_library / kt_android_library (from rules_kotlin) so a per-package
BUILD.bazel extracted out of the commonMain monolith is ~10 lines instead of
duplicating the ~50-line pattern seen in android_main / jvm_main_lib.

Extensibility point: `extra_plugins` (a label_list spliced directly into the
underlying rule's `plugins`) is the macro's real open/closed extensibility
point — a caller needing a compiler plugin this macro doesn't already know
about passes its label directly via `extra_plugins`, no macro edit required.
`uses_compose` / `uses_serialization` are optional sugar over `extra_plugins`
for the two plugins this migration's packages actually need today (Compose,
kotlinx.serialization) — see plan.md Story 1.1.1's design correction
(architecture review concern #1).

See project_plans/commonmain-bazel-target-split/implementation/plan.md
Epic 1.1 (Story 1.1.1) for the full acceptance criteria this macro implements.
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")
load("@rules_kotlin//kotlin:android.bzl", "kt_android_library")

# Well-known compiler plugin labels, defined in the repo-root BUILD.bazel.
_COMPOSE_COMPILER_PLUGIN = "//:compose_compiler_plugin"
_SERIALIZATION_COMPILER_PLUGIN = "//:serialization_compiler_plugin"

# Default kotlinc_opts labels per platform, matching android_main / jvm_main_lib.
_DEFAULT_KOTLINC_OPTS = {
    "jvm": "//:kmp_jvm_kotlinc_opts",
    "android": "//:kmp_android_kotlinc_opts",
}

def stelekit_kt_common_library(
        name,
        srcs,
        platform,
        deps = [],
        associates = [],
        extra_plugins = [],
        uses_compose = False,
        uses_serialization = False,
        visibility = ["//kmp:__subpackages__"],
        **kwargs):
    """Defines a kt_jvm_library or kt_android_library for one extracted commonMain package.

    Args:
        name: target name.
        srcs: list of source files/filegroups (typically glob(["*.kt"])).
        platform: "jvm" or "android" — selects kt_jvm_library vs kt_android_library
            and the matching default kotlinc_opts.
        deps: normal Bazel deps.
        associates: rules_kotlin friend-visibility targets (grants access to
            `internal` members). A target must never appear in both `deps` and
            `associates` — see the guard below.
        extra_plugins: label_list of compiler-plugin targets spliced directly
            into the underlying rule's `plugins`. This is the macro's real
            extensibility point for any future compiler plugin — no macro edit
            required to add a new one via a call-site argument.
        uses_compose: sugar — appends compose_compiler_plugin to the effective
            plugins list.
        uses_serialization: sugar — appends serialization_compiler_plugin to
            the effective plugins list.
        visibility: target visibility, defaults to //kmp:__subpackages__.
        **kwargs: forwarded to the underlying kt_jvm_library/kt_android_library
            (e.g. kotlinc_opts override, tags, testonly).
    """
    if platform not in ("jvm", "android"):
        fail("stelekit_kt_common_library: platform must be 'jvm' or 'android', got %r" % platform)

    # deps/associates overlap guard — mirrors the common_test_fixtures precedent's
    # documented rules_kotlin constraint (a target cannot be both a plain dep and
    # a friend-visibility associate at the same time).
    overlap = [t for t in associates if t in deps]
    if overlap:
        fail(
            "stelekit_kt_common_library(name = %r): target(s) %s appear in both " %
            (name, overlap) +
            "`deps` and `associates` — a target must be exactly one of the two.",
        )

    effective_plugins = list(extra_plugins)
    if uses_compose:
        effective_plugins.append(_COMPOSE_COMPILER_PLUGIN)
    if uses_serialization:
        effective_plugins.append(_SERIALIZATION_COMPILER_PLUGIN)

    kotlinc_opts = kwargs.pop("kotlinc_opts", _DEFAULT_KOTLINC_OPTS[platform])

    rule = kt_jvm_library if platform == "jvm" else kt_android_library

    rule(
        name = name,
        srcs = srcs,
        deps = deps,
        associates = associates,
        plugins = effective_plugins,
        kotlinc_opts = kotlinc_opts,
        visibility = visibility,
        **kwargs
    )
