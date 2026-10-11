"""Builds Points to Christ (AID-13): Old Testament verses that point to Jesus Christ.

The entries are written by AI (Claude, in the 2.8 build session) in tools/christ/*.txt; the format
is in tools/christ/README.txt. This script checks them (kind, Old Testament verses that exist in the
KJV, New Testament references that exist, no verse in two entries, note length) and writes
app/src/main/assets/study/christ.tsv:

    kind  title  verses(ranges lo-hi,...)  shown(OT reference)  nt([[lo-hi|label]] links)  note

Usage: python3 tools/build_christ.py [--check]
"""
import glob
import os
import re
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_aids import BOOK, parse_refs  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "tools", "christ")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "study", "christ.tsv")
KJV = os.path.join(ROOT, "app", "src", "main", "assets", "bibles", "kjv.db")
KINDS = {"prophecy", "type"}
MAX_NOTE = 320


def read_entries():
    entries = []
    for path in sorted(glob.glob(os.path.join(SRC, "*.txt"))):
        if os.path.basename(path) == "README.txt":
            continue
        cur = None
        for n, line in enumerate(open(path, encoding="utf-8"), 1):
            line = line.rstrip("\n")
            where = f"{os.path.basename(path)}:{n}"
            if line.startswith("#") and cur is None:
                continue
            if line.startswith("== "):
                cur = {"title": line[3:].strip(), "where": where, "text": [], "fields": {}}
                entries.append(cur)
                continue
            if cur is None or not line.strip():
                continue
            m = re.match(r"(kind|refs|nt):\s*(.*)$", line)
            if m and not cur["text"]:
                cur["fields"][m.group(1)] = m.group(2).strip()
            else:
                cur["text"].append(line.strip())
    return entries


def expand(ranges, verses):
    """Every verse id in [ranges] that the KJV has."""
    out = []
    for lo, hi in ranges:
        out += [v for v in verses if lo <= v <= hi]
    return out


def main():
    problems = []
    con = sqlite3.connect(KJV)
    names = {i: n for i, n in con.execute("SELECT id, name FROM books")}
    verses = sorted(i for (i,) in con.execute("SELECT id FROM verses"))
    entries = read_entries()
    owner = {}
    rows = []
    count = 0
    for e in entries:
        f, where, title = e["fields"], e["where"], e["title"]
        kind = f.get("kind", "")
        if kind not in KINDS:
            problems.append(f"{where}: kind '{kind}'")
        ot, shown = parse_refs(f.get("refs", ""), where, problems)
        if not ot:
            problems.append(f"{where}: no verses")
        nt, nt_shown = parse_refs(f.get("nt", ""), where, problems)
        if not nt:
            problems.append(f"{where}: no New Testament references")
        for lo, hi in ot:
            if lo // 1_000_000 >= 40:
                problems.append(f"{where}: refs must be Old Testament")
        for lo, hi in nt:
            if lo // 1_000_000 < 40:
                problems.append(f"{where}: nt must be New Testament")
        for lo, hi in ot + nt:
            if not expand([(lo, hi)], verses):
                problems.append(f"{where}: no such verses {lo}-{hi}")
            for v in (lo, hi):
                if v % 1000 and v not in verses:
                    problems.append(f"{where}: no verse {v}")
        marked = expand(ot, verses)
        for v in marked:
            if v in owner:
                problems.append(f"{where}: verse {v} is also in '{owner[v]}'")
            owner[v] = title
        count += len(set(marked))
        note = " ".join(e["text"])
        if not note:
            problems.append(f"{where}: no note")
        if len(note) > MAX_NOTE:
            problems.append(f"{where}: note is {len(note)} characters (at most {MAX_NOTE})")

        def readable(parts):
            return "; ".join(f"{names[b]} {spec}" for b, spec, _, _ in parts)

        def links(parts):
            # As study-text links, [[first-last|label]], so they open the passage when tapped.
            return "; ".join(f"[[{lo}-{hi}|{names[b]} {spec}]]" for b, spec, lo, hi in parts)

        rows.append("\t".join([
            kind, title, ",".join(f"{lo}-{hi}" for lo, hi in ot), readable(shown), links(nt_shown), note,
        ]))
    if problems:
        print("\n".join(problems))
        sys.exit(1)
    print(f"{len(rows)} entries, {count} verses marked "
          f"({sum(1 for e in entries if e['fields'].get('kind') == 'prophecy')} prophecies, "
          f"{sum(1 for e in entries if e['fields'].get('kind') == 'type')} pictures)")
    if "--check" not in sys.argv:
        with open(OUT, "w", encoding="utf-8") as out:
            out.write("\n".join(rows) + "\n")
        print("wrote", OUT)


if __name__ == "__main__":
    main()
