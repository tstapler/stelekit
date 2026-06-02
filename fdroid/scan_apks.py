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
        # androguard 4.x moved APK from core.bytecodes.apk to core.apk
        from androguard.core.apk import APK  # type: ignore[import]
        apk = APK(apk_path)
        # fdroid exercises two distinct heavy-parsing code paths; test both:
        # 1. get_android_resources() — ARSC resource table parser
        apk.get_android_resources()
        # 2. get_certificates_der_v3() — APK v2/v3 signing block parser
        #    (NoOverwriteDict.append regression in certain old APKs)
        apk.get_certificates_der_v3()
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
