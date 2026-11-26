#!/usr/bin/env bash
# Fails when //kmp:detekt would skip a Kotlin file: a glob stops silently at a nested BUILD.bazel.
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")/../.."
roots=(kmp/src/{commonMain,jvmMain,androidMain,iosMain}/kotlin)
query=$(bazel query 'labels(srcs, labels(srcs, //kmp:detekt))')
missing=$(comm -23 \
  <(git ls-files "${roots[@]}" | grep '\.kt$' | sort) \
  <(sed 's|^//||; s|:|/|' <<<"$query" | sort))
if [ -n "$missing" ]; then
  echo "Not linted by //kmp:detekt (add the package's detekt_srcs filegroup to kmp/BUILD.bazel):"
  echo "$missing"
  exit 1
fi
