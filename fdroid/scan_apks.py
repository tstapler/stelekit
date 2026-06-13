#!/usr/bin/env python3
"""Remove APKs that androguard cannot parse so fdroid update doesn't crash.

Usage:
    uv run --directory fdroid python3 fdroid/scan_apks.py [repo-dir]

Default repo-dir is 'repo' (relative to the fdroid/ project directory).
"""
import sys
from pathlib import Path


def can_parse(apk_path: str) -> bool:
    try:
        # Use fdroidserver's own get_first_signer_certificate — the exact function
        # that fdroid update calls internally. After applying the androguard 4.x
        # compatibility patch (see fdroid.yml), this should pass for all valid APKs.
        import fdroidserver.common as common  # type: ignore[import]
        common.get_first_signer_certificate(apk_path)
        return True
    except Exception as exc:
        print(f"  cannot parse: {exc}", file=sys.stderr)
        return False


def main() -> None:
    repo_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("repo")
    apks = sorted(repo_dir.glob("*.apk"))
    if not apks:
        print("No APKs found in", repo_dir)
        return

    removed = 0
    for apk in apks:
        print(f"Checking {apk.name} ...", end=" ")
        if can_parse(str(apk)):
            print("OK")
        else:
            print(f"REMOVING {apk.name}")
            apk.unlink()
            removed += 1

    print(f"\n{len(apks) - removed}/{len(apks)} APKs kept, {removed} removed")


if __name__ == "__main__":
    main()
