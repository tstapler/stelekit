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

readme = readme_path.read_text()
install = install_path.read_text().rstrip('\n')

begin = '<!-- BEGIN_INSTALL -->'
end   = '<!-- END_INSTALL -->'

start = readme.find(begin)
stop  = readme.find(end)

if start == -1 or stop == -1:
    print("error: markers not found in README.md", file=sys.stderr)
    sys.exit(1)

new_readme = (
    readme[:start + len(begin)]
    + '\n'
    + install
    + '\n'
    + readme[stop:]
)

readme_path.write_text(new_readme)
print("README.md updated.")
PY
