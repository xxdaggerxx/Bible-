"""Builds the Bible aids (AID-1 to AID-12): Jewish customs and feasts, symbols and Bible numbers.

The entries are written by AI (Claude, in the 1.22 build session) in tools/aids/*.txt; the format
is in tools/aids/README.txt. People and places need nothing here: they come from the Names &
places data already in study.db.

This script checks the entries (kinds, forms, references, sources, length), turns references into
verse-id ranges and readable references, counts how often each entry would be marked in the KJV,
BSB and WEB (entries never marked are reported), and writes app/src/main/assets/study/aids.tsv:

    kind  id  title  forms(|)  anywhere(1/0)  verses(ranges)  not(ranges)  refs(links)  sources  text

Usage: python3 tools/build_aids.py [--check]
"""
import glob
import os
import re
import sqlite3
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "tools", "aids")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "study", "aids.tsv")
BIBLES = os.path.join(ROOT, "app", "src", "main", "assets", "bibles")
KINDS = {"feast", "worship", "life", "group", "rome", "symbol", "number"}
MAX_TEXT = 900

OSIS = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl "
        "Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke "
        "John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet "
        "1John 2John 3John Jude Rev").split()
BOOK = {c: i + 1 for i, c in enumerate(OSIS)}
WORD = re.compile(r"[\w’']+", re.UNICODE)


def vid(b, c, v):
    return b * 1_000_000 + c * 1000 + v


def parse_refs(text, where, problems):
    """'Exod 12:1-28; Lev 23; Lev 4:7,18' -> [(lo, hi)] verse-id ranges, and the parts for display."""
    ranges, shown = [], []
    for part in [p.strip() for p in text.split(";") if p.strip()]:
        m = re.fullmatch(r"(\d?[A-Za-z]+)\s+([\d:,\-\s]+)", part)
        if not m or m.group(1) not in BOOK:
            problems.append(f"{where}: can't read reference '{part}'")
            continue
        b = BOOK[m.group(1)]
        spec = m.group(2).replace(" ", "")
        first = len(ranges)
        chapter = None
        has_colon = ":" in spec
        for item in spec.split(","):
            try:
                if not has_colon:
                    a, _, z = item.partition("-")
                    ranges.append((vid(b, int(a), 0), vid(b, int(z or a), 999)))
                elif ":" in item:
                    left, _, right = item.partition("-")
                    c, v = (int(x) for x in left.split(":"))
                    chapter = c
                    if not right:
                        ranges.append((vid(b, c, v), vid(b, c, v)))
                    elif ":" in right:
                        c2, v2 = (int(x) for x in right.split(":"))
                        ranges.append((vid(b, c, v), vid(b, c2, v2)))
                        chapter = c2
                    else:
                        ranges.append((vid(b, c, v), vid(b, c, int(right))))
                else:
                    if chapter is None:
                        raise ValueError
                    a, _, z = item.partition("-")
                    ranges.append((vid(b, chapter, int(a)), vid(b, chapter, int(z or a))))
            except ValueError:
                problems.append(f"{where}: can't read '{item}' in '{part}'")
        mine = ranges[first:]
        if mine:
            shown.append((b, spec, min(r[0] for r in mine), max(r[1] for r in mine)))
    return ranges, shown


def read_entries(problems):
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
            m = re.match(r"(kind|forms|anywhere|refs|not|sources):\s*(.*)$", line)
            if m and not cur["text"]:
                cur["fields"][m.group(1)] = m.group(2).strip()
            else:
                cur["text"].append(line.strip())
    return entries


def bible(name):
    con = sqlite3.connect(os.path.join(BIBLES, name))
    return {i: t for i, t in con.execute("SELECT id, text FROM verses")}


def tokens(text):
    return [w.lower().replace("’", "'") for w in WORD.findall(text)]


def find(form_tokens, words):
    n = len(form_tokens)
    for i in range(len(words) - n + 1):
        if words[i:i + n] == form_tokens:
            return True
    return False


def main():
    problems = []
    entries = read_entries(problems)
    names = {}
    con = sqlite3.connect(os.path.join(BIBLES, "kjv.db"))
    for i, name in con.execute("SELECT id, name FROM books"):
        names[i] = name
    versions = {v: bible(v + ".db") for v in ("kjv", "bsb", "web")}
    seen_ids = {}
    rows = []
    never = []
    for e in entries:
        f, where = e["fields"], e["where"]
        kind = f.get("kind", "")
        if kind not in KINDS:
            problems.append(f"{where}: kind '{kind}'")
        forms = [x.strip() for x in f.get("forms", "").split(",") if x.strip()]
        if not forms:
            problems.append(f"{where}: no forms")
        for x in forms:
            if x != x.lower() or "\t" in x or "|" in x:
                problems.append(f"{where}: form '{x}' must be lower case, no tabs or bars")
        anywhere = f.get("anywhere", "no")
        if anywhere not in ("yes", "no") or (kind in ("symbol", "number") and anywhere == "yes"):
            problems.append(f"{where}: anywhere must be yes or no (no for symbols and numbers)")
        refs, shown = parse_refs(f.get("refs", ""), where, problems)
        if not refs:
            problems.append(f"{where}: no refs")
        nots, _ = parse_refs(f.get("not", ""), where, problems)
        sources = f.get("sources", "")
        if not sources:
            problems.append(f"{where}: no sources")
        text = " ".join(e["text"]).strip()
        if not text or len(text) > MAX_TEXT:
            problems.append(f"{where}: text must be 1-{MAX_TEXT} characters (is {len(text)})")
        slug = kind[0] + "-" + re.sub(r"[^a-z0-9]+", "-", e["title"].lower()).strip("-")
        if slug in seen_ids:
            problems.append(f"{where}: '{e['title']}' already at {seen_ids[slug]}")
        seen_ids[slug] = where
        # How often it would be marked (each verse that has a form), in the three built-in Bibles.
        form_tokens = [tokens(x) for x in forms]
        hits = 0
        for texts in versions.values():
            for i, t in texts.items():
                if any(lo <= i <= hi for lo, hi in nots):
                    continue
                if anywhere == "no" and not any(lo <= i <= hi for lo, hi in refs):
                    continue
                words = tokens(t)
                if any(find(ft, words) for ft in form_tokens):
                    hits += 1
        e["hits"] = hits
        if hits == 0:
            never.append(f"{where} {e['title']}")
        # Each reference as a link the app opens as a passage: [[first-last|Exodus 12:1-28]].
        readable = "; ".join(f"[[{lo}-{hi}|{names[b]} {spec}]]" for b, spec, lo, hi in shown)
        def pack(rs):
            return ",".join(f"{lo}-{hi}" for lo, hi in rs)
        rows.append((kind, slug, e["title"], "|".join(forms), "1" if anywhere == "yes" else "0",
                     pack(refs), pack(nots), readable, sources, text))
    for p in problems:
        print(p)
    for x in never:
        print("never marked:", x)
    by_kind = {}
    for r in rows:
        by_kind[r[0]] = by_kind.get(r[0], 0) + 1
    print(f"{len(rows)} entries {by_kind}, {len(never)} never marked, {len(problems)} problems")
    if "--check" in sys.argv or problems:
        sys.exit(1 if problems else 0)
    with open(OUT, "w", encoding="utf-8") as out:
        for r in rows:
            out.write("\t".join(r) + "\n")
    print("wrote", os.path.relpath(OUT, ROOT), os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
