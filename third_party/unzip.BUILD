"""
BUILD rules for InfoZip unzip 6.0 — built from source by Bazel.
This file is the build_file for the @unzip_src http_archive in MODULE.bazel.
"""

load("@rules_cc//cc:defs.bzl", "cc_binary")

cc_binary(
    name = "unzip",
    srcs = [
        # Core sources (from unix/Makefile OBJS1-3 + unix platform module)
        "unzip.c",
        "crc32.c",
        "crypt.c",
        "envargs.c",
        "explode.c",
        "extract.c",
        "fileio.c",
        "globals.c",
        "inflate.c",
        "list.c",
        "match.c",
        "process.c",
        "ttyio.c",
        "ubz2err.c",
        "unreduce.c",
        "unshrink.c",
        "zipinfo.c",
        "unix/unix.c",
    ] + glob(["*.h", "unix/*.h"]),  # all headers needed by the above sources
    copts = [
        "-DUNIX",
        "-DUSE_ZLIB",
        "-DNO_LCHMOD",
        # GCC 14+ promotes several pointer-compatibility checks to hard errors.
        # Downgrade them to warnings before -w suppresses all warnings.
        "-Wno-error=incompatible-pointer-types",
        "-Wno-error=implicit-function-declaration",
        "-Wno-error=implicit-int",
        "-w",  # suppress all warnings from third-party C code
    ],
    linkopts = ["-lz"],
    visibility = ["//visibility:public"],
)
