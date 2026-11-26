#!/usr/bin/env bash
# Fails when //kmp:detekt would skip a Kotlin file: a glob stops silently at a nested BUILD.bazel.
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")/../.."
# Source roots come from the Gradle `detekt { source.setFrom(...) }` block, so a root added there is checked too.
roots=$(sed -n '/^detekt {/,/^}/p' kmp/build.gradle.kts | grep -oE '"src/[A-Za-z]+/kotlin"' | tr -d '"' | sed 's|^|kmp/|')
[ -n "$roots" ] || { echo "No detekt source roots found in kmp/build.gradle.kts"; exit 1; }
# Assigned first: set -e does not see failures inside <(...).
tracked=$(git ls-files $roots | grep '\.kt$' | sort)
query=$(bazel query 'labels(srcs, labels(srcs, //kmp:detekt))')
missing=$(comm -23 <(echo "$tracked") <(sed 's|^//||; s|:|/|' <<<"$query" | sort))
if [ -n "$missing" ]; then
  echo "Not linted by //kmp:detekt (add the package's detekt_srcs filegroup to kmp/BUILD.bazel):"
  echo "$missing"
  exit 1
fi
