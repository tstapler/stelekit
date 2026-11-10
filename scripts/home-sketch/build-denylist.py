#!/usr/bin/env python3
"""Build the LOCAL private-string denylist (never committed).

Reads the owner's wiki page names (and, optionally, a private constants file) and writes one
case-insensitive phrase per line to $STELEKIT_PRIVATE_DENYLIST (default
~/.cache/stelekit-private/denylist.txt). Phrases shorter than 6 characters or on the stoplist are
dropped. Numeric constants from the private constants file are included unless the existing
denylist header records `# OD-H1=yes`. The output is written mode 0600 and never printed.
"""
import argparse
import os
import re
import subprocess
import sys
import urllib.parse

MIN_LEN = 6
STOPLIST = {
    "journal", "journals", "contents", "readme", "default", "project", "projects", "inbox", "archive",
    "templates", "template", "settings", "overview", "summary", "meeting", "notes", "ideas", "reading",
}
CONSTANT = re.compile(r"\d+(?:\.\d+)?(?:\s+\d+/\d+)?\s*(?:\"|in\b|inch(?:es)?\b)|\d+/\d+\s*\"")


def wiki_pages_dir(arg):
    if arg:
        return arg
    root = os.environ.get("WIKI_PATH") or subprocess.run(["wiki-path"], capture_output=True, text=True).stdout.strip()
    return os.path.join(root, "logseq", "pages") if root else ""


def public_words(repo_root):
    """Lowercase words already in the public repo (docs, sources, build files); a single word that is
    already public cannot be a new leak and only adds false positives."""
    words = set()
    listing = subprocess.run(["git", "-C", repo_root, "ls-files", "*.md", "*.kt", "*.kts", "*.yml", "*.py"],
                             capture_output=True, text=True).stdout.split("\n")
    for rel in listing:
        if not rel or rel.startswith("project_plans/home-project-sketching/"):
            continue
        try:
            with open(os.path.join(repo_root, rel), encoding="utf-8") as f:
                words.update(re.findall(r"[a-z0-9]+", f.read().lower()))
        except (OSError, UnicodeDecodeError):
            continue
    return words


def title_phrases(pages_dir, public):
    phrases = set()
    for name in os.listdir(pages_dir):
        stem, ext = os.path.splitext(name)
        if ext != ".md":
            continue
        title = urllib.parse.unquote(stem.replace("___", "/")).strip()
        for cand in {title, stem}:
            low = cand.lower()
            if len(low) < MIN_LEN or low in STOPLIST:
                continue
            if re.fullmatch(r"[a-z0-9]+", low) and low in public:
                continue
            phrases.add(low)
    return phrases


def constant_phrases(path):
    if not path or not os.path.isfile(path):
        return set()
    with open(path, encoding="utf-8") as f:
        return {m.group(0).strip().lower() for m in CONSTANT.finditer(f.read())}


def existing_od_h1_yes(out):
    if not os.path.isfile(out):
        return False
    with open(out, encoding="utf-8") as f:
        return any(re.match(r"#\s*OD-H1\s*=\s*yes\b", ln) for ln in f)


def main():
    default_out = os.environ.get("STELEKIT_PRIVATE_DENYLIST",
                                 os.path.expanduser("~/.cache/stelekit-private/denylist.txt"))
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--wiki-pages", help="wiki pages directory (default: $(wiki-path)/logseq/pages)")
    ap.add_argument("--constants", default=os.path.expanduser("~/.cache/stelekit-private/closet-constants.md"))
    ap.add_argument("--extra", help="file of extra phrases (household names, streets, other projects), one per line")
    ap.add_argument("--repo-root", default=os.getcwd(), help="repo whose public vocabulary is exempt (default: cwd)")
    ap.add_argument("--out", default=default_out)
    args = ap.parse_args()

    pages = wiki_pages_dir(args.wiki_pages)
    if not os.path.isdir(pages):
        print("build-denylist: wiki pages directory not found; pass --wiki-pages", file=sys.stderr)
        return 2
    od_h1_yes = existing_od_h1_yes(args.out)
    phrases = title_phrases(pages, public_words(args.repo_root))
    if not od_h1_yes:
        phrases |= constant_phrases(args.constants)
    if args.extra and os.path.isfile(args.extra):
        with open(args.extra, encoding="utf-8") as f:
            phrases |= {ln.strip().lower() for ln in f if ln.strip() and not ln.startswith("#")}

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write("# Local private-string denylist. NEVER commit. Regenerate with scripts/home-sketch/build-denylist.py\n")
        if od_h1_yes:
            f.write("# OD-H1=yes\n")
        f.write("\n".join(sorted(phrases)) + "\n")
    print(f"build-denylist: wrote {len(phrases)} phrases to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
