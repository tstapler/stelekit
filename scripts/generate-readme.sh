#!/usr/bin/env bash
# Regenerates README.md by inlining docs/install.md between the
# <!-- BEGIN_INSTALL --> and <!-- END_INSTALL --> markers.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
README="$ROOT/README.md"
INSTALL="$ROOT/docs/install.md"

if [[ ! -f "$INSTALL" ]]; then
  echo "error: $INSTALL not found" >&2
  exit 1
fi

python3 - "$README" "$INSTALL" <<'PY'
import sys, pathlib

readme_path = pathlib.Path(sys.argv[1])
install_path = pathlib.Path(sys.argv[2])

readme = readme_path.read_text(encoding="utf-8")
install = install_path.read_text(encoding="utf-8").rstrip('\n')

begin = '<!-- BEGIN_INSTALL -->'
end   = '<!-- END_INSTALL -->'

start = readme.find(begin)
if start == -1:
    print("error: BEGIN_INSTALL marker not found in README.md", file=sys.stderr)
    sys.exit(1)

stop = readme.find(end, start)
if stop == -1:
    print("error: END_INSTALL marker not found after BEGIN_INSTALL in README.md", file=sys.stderr)
    sys.exit(1)

new_readme = (
    readme[:start + len(begin)]
    + '\n'
    + install
    + '\n'
    + readme[stop:]
)

readme_path.write_text(new_readme, encoding="utf-8")
print("README.md updated.")
PY
