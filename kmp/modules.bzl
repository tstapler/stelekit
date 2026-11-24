"""
kmp_modules — the Bazel module split of kmp/src/{commonMain,jvmCommonMain,jvmMain} (project_plans/rules-kotlin-wasm-kmp, W9).

`modules_manifest.bzl` assigns every JVM source file to exactly one module and declares each module's direct module
dependencies; `ModuleManifestGuardTest` (businessTest) keeps that assignment complete and acyclic. `kmp_jvm_modules()`
(called from //kmp/modules) turns the manifest into one kt_jvm_library per module. Each module compiles its own
commonMain files together with the jvm actuals of its expect declarations, so expect/actual resolve inside one unit.
//kmp/src/jvmMain/kotlin:jvm_main_lib stays as an umbrella that re-exports every module, so a leaf edit rebuilds only
that module and its dependents instead of one 1700-file library.

Android is not split: android_main still compiles all of commonMain (see androidMain/kotlin/BUILD.bazel).
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")
load("//kmp:module_tests_manifest.bzl", "KMP_MODULE_BUSINESS_TESTS", "KMP_MODULE_JVM_TESTS", "KMP_MODULE_TESTS")
load("//kmp:modules_manifest.bzl", "KMP_MODULES")

# Modules that already have their own Bazel target (their files are listed in the manifest only for the partition check).
KMP_PREBUILT_MODULES = {
    "mermaid": "//kmp/src/jvmMain/kotlin:mermaid_lib",
    "stats": "//kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats",
}

# Files that exist only for Gradle (JMH benchmarks, the real Compose-resources SketchFontResources.kt that
# //kmp/src/commonMain/bazel_stubs replaces); listed in the manifest so the partition check sees them, never compiled here.
KMP_GRADLE_ONLY_MODULE = "gradle_only"

# The umbrella's own sources: the application layer nothing else depends on (desktop entry point, CLI, MCP server).
KMP_APP_MODULE = "app"

# jvmCommonMain files that declare `expect` (resolved by jvmMain actuals) — they, like every commonMain file, are common_srcs.
KMP_JVM_EXPECT_SRCS = [
    "jvmCommonMain/kotlin/dev/stapler/stelekit/db/libsql/LibsqlNativeLoader.kt",
]

# Third-party and generated dependencies shared by every module (and the umbrella); unused ones cost nothing.
KMP_JVM_EXTERNAL_DEPS = [
    # SQLDelight generated code + runtime
    "//kmp/src/generated/sqldelight:sqldelight_generated_jvm",
    "//kmp/src/generated/sqldelight-telemetry:sqldelight_generated_telemetry_jvm",
    "@maven//:app_cash_sqldelight_runtime_jvm",
    "@maven//:app_cash_sqldelight_coroutines_extensions_jvm",
    "@maven//:app_cash_sqldelight_async_extensions_jvm",
    "@maven//:app_cash_sqldelight_sqlite_driver",

    # Arrow
    "@maven//:io_arrow_kt_arrow_core_jvm",
    "@maven//:io_arrow_kt_arrow_optics_jvm",
    "@maven//:io_arrow_kt_arrow_fx_coroutines_jvm",
    "@maven//:io_arrow_kt_arrow_resilience_jvm",

    # Kotlinx
    "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
    "@maven//:org_jetbrains_kotlinx_kotlinx_datetime_jvm",
    "@maven//:org_jetbrains_kotlinx_kotlinx_serialization_json_jvm",

    # Markdown
    "@maven//:org_jetbrains_markdown_jvm",

    # Compose Desktop
    "@maven//:org_jetbrains_compose_runtime_runtime_desktop",
    "@maven//:org_jetbrains_compose_foundation_foundation_desktop",
    "@maven//:org_jetbrains_compose_material3_material3_desktop",
    "@maven//:org_jetbrains_compose_material_material_icons_extended_desktop",
    "@maven//:org_jetbrains_compose_components_components_resources_desktop",
    "@maven//:org_jetbrains_compose_desktop_desktop_jvm_linux_x64",

    # Lifecycle
    "@maven//:org_jetbrains_androidx_lifecycle_lifecycle_runtime_compose",
    "@maven//:org_jetbrains_androidx_lifecycle_lifecycle_viewmodel_compose",

    # Coil
    "@maven//:io_coil_kt_coil3_coil_compose_jvm",
    "@maven//:io_coil_kt_coil3_coil_network_ktor3_jvm",

    # Ktor
    "@maven//:io_ktor_ktor_client_core_jvm",
    "@maven//:io_ktor_ktor_client_content_negotiation_jvm",
    "@maven//:io_ktor_ktor_serialization_kotlinx_json_jvm",
    "@maven//:io_ktor_ktor_client_okhttp_jvm",

    # MCP server SDK (graph-mcp-server)
    "@maven//:io_modelcontextprotocol_kotlin_sdk_server_jvm",
    "@maven//:io_ktor_ktor_server_core_jvm",
    "@maven//:io_ktor_ktor_server_sse_jvm",

    # ktoml — TOML parsing for .stele-sections manifest
    "@maven//:com_akuleshov7_ktoml_core_jvm",

    # Ksoup
    "@maven//:com_fleeksoft_ksoup_ksoup_jvm",

    # Kotlin Multiplatform Diff
    "@maven//:io_github_petertrr_kotlin_multiplatform_diff_jvm",

    # OpenTelemetry API + SDK
    "@maven//:io_opentelemetry_opentelemetry_api",
    "@maven//:io_opentelemetry_opentelemetry_sdk",
    "@maven//:io_opentelemetry_opentelemetry_exporter_logging",

    # Kotlinx Swing dispatcher
    "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_swing",

    # JGit 7.x
    "@maven//:org_eclipse_jgit_org_eclipse_jgit",
    "@maven//:org_eclipse_jgit_org_eclipse_jgit_ssh_apache",

    # BouncyCastle
    "@maven//:org_bouncycastle_bcprov_jdk18on",

    # PDFBox — PDF text extraction for the asset OCR pipeline
    "@maven//:org_apache_pdfbox_pdfbox",

    # GraalJS + Apache Batik — MermaidJvmEngine's renderer
    "@maven//:org_graalvm_polyglot_polyglot",
    "@maven//:org_graalvm_polyglot_js_community",
    "@maven//:org_apache_xmlgraphics_batik_bridge",
    "@maven//:org_apache_xmlgraphics_batik_anim",

    # ZXing — QR encode/decode (commonMain expect / jvmMain actual)
    "@maven//:com_google_zxing_core",

    # jkeymaster — desktop-only global hotkey for quick-capture
    "@maven//:com_github_tulskiy_jkeymaster",

    # Kotlin stdlib
    "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
]

def _src_label(path):
    # "commonMain/kotlin/dev/..." -> "//kmp/src/commonMain/kotlin:dev/..."; full labels (bazel_stubs filegroup) pass through.
    if path.startswith("//"):
        return path
    parts = path.split("/", 2)
    return "//kmp/src/%s/%s:%s" % (parts[0], parts[1], parts[2])

def _module_label(name):
    if name in KMP_PREBUILT_MODULES:
        return KMP_PREBUILT_MODULES[name]
    return "//kmp/modules:" + name

def kmp_module_labels():
    """Labels of every generated module target (the umbrella and the tests' `associates` use this)."""
    return [_module_label(m) for m in KMP_MODULES if m not in KMP_PREBUILT_MODULES and m not in (KMP_GRADLE_ONLY_MODULE, KMP_APP_MODULE)]

def _closure():
    # {module: [every module it depends on, transitively]}; Starlark has no recursion, so iterate to a fixed point.
    reach = {m: list(spec["deps"]) for m, spec in KMP_MODULES.items()}
    for _ in range(len(KMP_MODULES)):
        for m in reach:
            for d in list(reach[m]):
                for e in reach[d]:
                    if e not in reach[m]:
                        reach[m].append(e)
    return reach

def kmp_jvm_modules():
    """Defines one kt_jvm_library per generated module.

    A module is associated with (gets friend access to the `internal` members of) every module below it, which keeps the
    pre-split semantics where all of commonMain shared one Kotlin module. The prebuilt modules (stats, mermaid) stay plain deps.
    """
    reach = _closure()
    for name, spec in KMP_MODULES.items():
        if name in KMP_PREBUILT_MODULES or name == KMP_GRADLE_ONLY_MODULE or name == KMP_APP_MODULE:
            continue
        srcs = [_src_label(s) for s in spec["srcs"]]
        common = [_src_label(s) for s in spec["srcs"] if s.startswith("commonMain/") or s.startswith("//") or s in KMP_JVM_EXPECT_SRCS]
        friends = [_module_label(m) for m in sorted(reach[name]) if m not in KMP_PREBUILT_MODULES]
        deps = [_module_label(m) for m in sorted(reach[name]) if m in KMP_PREBUILT_MODULES]
        kt_jvm_library(
            name = name,
            srcs = srcs,
            common_srcs = common,
            kotlinc_opts = "//:kmp_jvm_kotlinc_opts",
            plugins = [
                "//:compose_compiler_plugin",
                "//:serialization_compiler_plugin",
            ],
            associates = friends,
            deps = deps + KMP_JVM_EXTERNAL_DEPS,
            visibility = ["//kmp:__subpackages__"],
        )

def kmp_umbrella_library(name, extra_deps = [], visibility = ["//kmp:__subpackages__"]):
    """The umbrella `jvm_main_lib`: compiles the application layer (`app` module) and re-exports every other module.

    Consumers that need friend access to `internal` members list `kmp_module_labels()` in `associates` next to it.
    """
    app = KMP_MODULES[KMP_APP_MODULE]
    modules = kmp_module_labels()
    prebuilt = [KMP_PREBUILT_MODULES[m] for m in sorted(KMP_PREBUILT_MODULES)]
    kt_jvm_library(
        name = name,
        srcs = [_src_label(s) for s in app["srcs"]],
        kotlinc_opts = "//:kmp_jvm_kotlinc_opts",
        plugins = [
            "//:compose_compiler_plugin",
            "//:serialization_compiler_plugin",
        ],
        associates = modules,
        exports = modules + prebuilt,
        deps = prebuilt + extra_deps + KMP_JVM_EXTERNAL_DEPS,
        visibility = visibility,
    )


def kmp_module_tests():
    """One kt_jvm_test per module of //kmp:module_tests_manifest.bzl (generated by scripts/kmp-module-tests-sync.py).

    Each target associates only with its own module, so Bazel reruns it only when that module or a module it depends on
    changes, instead of every time anything under jvm_main_lib changes.
    """
    reach = _closure()
    for module, spec in KMP_MODULE_TESTS.items():
        kt_jvm_test(
            name = module + "_tests",
            srcs = [spec["suite"] + ".kt"] + ["//kmp/src/commonTest/kotlin:" + s for s in spec["srcs"]],
            associates = [":" + module] + [_module_label(m) for m in sorted(reach[module]) if m not in KMP_PREBUILT_MODULES],
            kotlinc_opts = "//:kmp_jvm_kotlinc_opts",
            test_class = "dev.stapler.stelekit.modules." + spec["suite"],
            deps = [
                "@maven//:io_arrow_kt_arrow_core_jvm",
                "@maven//:io_kotest_kotest_property_jvm",
                "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
                "@maven//:org_jetbrains_kotlin_kotlin_test_junit",
                "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
                "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test_jvm",
            ],
        )

def kmp_module_business_tests():
    """One kt_jvm_test per module of KMP_MODULE_BUSINESS_TESTS: businessTest classes moved out of //kmp:business_tests.

    Like kmp_module_tests, each target associates only with its module closure (not jvm_main_lib), so an edit reruns only
    the targets of the edited module's dependents. Helper files travel as sources (the generator's fixture closure).
    """
    reach = _closure()
    for module, spec in KMP_MODULE_BUSINESS_TESTS.items():
        kt_jvm_test(
            name = module + "_business_tests",
            srcs = [spec["suite"] + ".kt"] + [_src_label(s) for s in spec["srcs"]],
            associates = [":" + module] + [_module_label(m) for m in sorted(reach[module]) if m not in KMP_PREBUILT_MODULES],
            kotlinc_opts = "//:kmp_jvm_kotlinc_opts",
            test_class = "dev.stapler.stelekit.modules." + spec["suite"],
            # Same as //kmp:business_tests: DriverFactory resolves its data dir from user.home, read-only in the sandbox.
            jvm_flags = ["-Duser.home=$$TEST_TMPDIR", "-Xmx4g"],  # -Xmx4g: as the jvm_tests cap; ~18 uncapped JVMs would otherwise run at once
            deps = [_module_label(m) for m in sorted(reach[module]) if m in KMP_PREBUILT_MODULES] + KMP_JVM_EXTERNAL_DEPS + [
                "@maven//:com_squareup_okio_okio_fakefilesystem_jvm",
                "@maven//:io_kotest_kotest_property_jvm",
                "@maven//:io_ktor_ktor_client_mock_jvm",
                "@maven//:org_jetbrains_kotlin_kotlin_test_junit",
                "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test_jvm",
            ],
        )

def kmp_module_jvm_tests():
    """One kt_jvm_test per module of KMP_MODULE_JVM_TESTS: jvmTest classes moved out of //kmp/src/jvmTest/kotlin:jvm_tests.

    The generator moves only classes that need no display, but the JVM flags, env and plugins match jvm_tests so a moved
    class runs as it did there. Tagged `jvm_module_tests` so the business CI job (//kmp/modules:all) can skip what the JVM
    job already runs through //kmp:jvm_tests.
    """
    reach = _closure()
    for module, spec in KMP_MODULE_JVM_TESTS.items():
        kt_jvm_test(
            name = module + "_jvm_tests",
            srcs = [spec["suite"] + ".kt"] + [_src_label(s) for s in spec["srcs"]],
            associates = [":" + module] + [_module_label(m) for m in sorted(reach[module]) if m not in KMP_PREBUILT_MODULES],
            env = {"LIBGL_ALWAYS_SOFTWARE": "1"},
            env_inherit = ["DISPLAY", "XAUTHORITY"],
            jvm_flags = [
                "-Xmx4g",
                "-XX:+ExitOnOutOfMemoryError",
                "-Djdk.attach.allowAttachSelf=true",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Djava.awt.headless=false",
            ],
            kotlinc_opts = "//:kmp_jvm_kotlinc_opts",
            plugins = [
                "//:compose_compiler_plugin",
                "//:serialization_compiler_plugin",
            ],
            tags = ["jvm_module_tests"],
            test_class = "dev.stapler.stelekit.modules." + spec["suite"],
            timeout = "long",
            deps = [_module_label(m) for m in sorted(reach[module]) if m in KMP_PREBUILT_MODULES] + KMP_JVM_EXTERNAL_DEPS + [
                "@maven//:com_squareup_okio_okio_fakefilesystem_jvm",
                "@maven//:io_kotest_kotest_property_jvm",
                "@maven//:io_ktor_ktor_client_mock_jvm",
                "@maven//:io_modelcontextprotocol_kotlin_sdk_testing_jvm",
                "@maven//:io_projectreactor_tools_blockhound",
                "@maven//:org_jetbrains_kotlin_kotlin_reflect",
                "@maven//:org_jetbrains_kotlin_kotlin_test_junit",
                "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test_jvm",
            ],
        )
