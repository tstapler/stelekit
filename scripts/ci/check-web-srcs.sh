#!/usr/bin/env bash
# Fails when //kmp:web_dist omits a source file: Bazel would then keep a stale bundle after editing it.
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")/../.."

tracked=$(git ls-files kmp/src/commonMain kmp/src/wasmJsMain | grep -vE '/BUILD\.bazel$|^kmp/src/commonMain/bazel_stubs/' | sort)
query=$(bazel query 'labels(srcs, labels(srcs, //kmp:web_dist))')
declared=$(sed -e 's|^//||' -e 's|^:||' -e 's|:|/|' <<<"$query" | sort)
missing=$(comm -23 <(echo "$tracked") <(echo "$declared"))
if [ -n "$missing" ]; then
  echo "Not an input of //kmp:web_dist (add the package's web_srcs filegroup to kmp/BUILD.bazel):"
  echo "$missing"
  exit 1
fi
echo "//kmp:web_dist declares all $(wc -l <<<"$tracked") commonMain and wasmJsMain files."
