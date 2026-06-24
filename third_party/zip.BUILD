"""
BUILD rules for InfoZip zip 3.0 — built from source by Bazel.
This file is the build_file for the @zip_src http_archive in MODULE.bazel.
"""

load("@rules_cc//cc:defs.bzl", "cc_binary")
load("@bazel_skylib//rules:native_binary.bzl", "native_binary")

cc_binary(
    name = "zip",
    srcs = [
        "zip.c",
        "zipfile.c",
        "zipup.c",
        "fileio.c",
        "util.c",
        "globals.c",
        "crypt.c",
        "ttyio.c",
        "zbz2err.c",
        "crc32.c",
        "deflate.c",
        "trees.c",
        "unix/unix.c",
    ] + glob(["*.h", "unix/*.h"]),
    copts = [
        "-DUNIX",
        "-DUSE_ZLIB",
        "-DNO_ASM",
        # Silence K&R / implicit-declaration warnings from third-party C code.
        "-Wno-error=incompatible-pointer-types",
        "-Wno-error=implicit-function-declaration",
        "-Wno-error=implicit-int",
        "-w",
    ],
    linkopts = ["-lz"],
    visibility = ["//visibility:public"],
)
