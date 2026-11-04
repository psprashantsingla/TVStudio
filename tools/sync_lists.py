#!/usr/bin/env python3
"""Copy the per-model debloat lists from CleanYourTV (MIT) into the app's assets.

    python -I tools/sync_lists.py

Lists are data from a third party that end up on phones, so this reads a pinned commit: bump
UPSTREAM_COMMIT on purpose, then review the diff. Only debloat.txt and tv.json are read from the
archive, and nothing from it is executed.
"""
import io
import json
import re
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

UPSTREAM_REPO = "Gathin23/cleanyourtv"
UPSTREAM_COMMIT = "04aec4b7c763ed642d3994d25e5b89dd57014a6d"

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
LISTS = ASSETS / "lists"
SLUG = re.compile(r"[a-z0-9][a-z0-9._-]*\Z")
MEMBER = re.compile(r"[^/]+/examples/([^/]+)/(debloat\.txt|tv\.json)\Z")
MARKDOWN_LINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")


def clean(value):
    """One line of plain text: the index is tab-separated, and links would point nowhere in the app."""
    text = MARKDOWN_LINK.sub(r"\1", str(value or ""))
    return " ".join(text.split())


def read_upstream():
    url = f"https://codeload.github.com/{UPSTREAM_REPO}/tar.gz/{UPSTREAM_COMMIT}"
    with urllib.request.urlopen(url, timeout=300) as response:
        archive = io.BytesIO(response.read())
    found = {}
    with tarfile.open(fileobj=archive, mode="r:gz") as tar:
        for member in tar:
            match = MEMBER.match(member.name)
            if not match or not member.isfile():
                continue
            slug, name = match.groups()
            if not SLUG.match(slug):
                sys.exit(f"Refusing an unexpected folder name: {slug!r}")
            found.setdefault(slug, {})[name] = tar.extractfile(member).read()
    return found


def main():
    upstream = read_upstream()
    if LISTS.exists():
        shutil.rmtree(LISTS)
    LISTS.mkdir(parents=True)

    rows = ["slug\ttv\tbrand\tos\tnotes"]
    skipped = []
    for slug in sorted(upstream):
        files = upstream[slug]
        if "debloat.txt" not in files or "tv.json" not in files:
            skipped.append(slug)
            continue
        info = json.loads(files["tv.json"].decode("utf-8-sig"))
        folder = LISTS / slug
        folder.mkdir()
        (folder / "debloat.txt").write_bytes(files["debloat.txt"])
        rows.append("\t".join(clean(v) for v in (slug, info.get("tv"), info.get("brand"), info.get("os"), info.get("notes"))))

    (LISTS / "index.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    (LISTS / "SOURCE.txt").write_text(
        f"https://github.com/{UPSTREAM_REPO}/tree/{UPSTREAM_COMMIT}/examples\n", encoding="utf-8", newline="\n"
    )
    shutil.copyfile(ROOT / "THIRD_PARTY_NOTICES.md", ASSETS / "THIRD_PARTY_NOTICES.md")
    print(f"Wrote {len(rows) - 1} model lists from {UPSTREAM_COMMIT[:7]}")
    if skipped:
        print(f"Skipped {len(skipped)} folders missing a list or tv.json: {', '.join(skipped)}")


if __name__ == "__main__":
    main()
