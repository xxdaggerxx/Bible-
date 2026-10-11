"""Builds "Chapter at a glance" (STD-22): a short card at the top of every chapter.

The cards are written by AI (Claude, in the 1.14 build session) in tools/glance/NN.txt, one file
per book, in plain English and the traditional view:

    # Genesis
    fits 1-11: Beginnings, before Abraham
    fits 12-50: The patriarchs, about 2000-1800 BC
    1|27|What's happening in two short sentences.

  fits A-B: where chapters A to B sit in the Bible's story (with a traditional date where known)
  C|K|text: chapter C, its key verse K, and what's happening

Who and where come from the app's Names & places data at run time, so every name links.

This script checks every book (all chapters present once, key verses inside their chapter, every
chapter covered by one "fits" range) and writes app/src/main/assets/study/glance.tsv:

    book<TAB>chapter<TAB>key verse<TAB>fits<TAB>what

Usage: python3 tools/build_glance.py [--check]
"""
import glob
import os
import re
import sqlite3
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "tools", "glance")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "study", "glance.tsv")
KJV = os.path.join(ROOT, "app", "src", "main", "assets", "bibles", "kjv.db")


def load_book(path, book, chapters, verses):
    fits, cards, errors = [], {}, []
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"fits (\d+)-(\d+):\s*(.+)$", line)
        if m:
            fits.append((int(m.group(1)), int(m.group(2)), m.group(3).strip()))
            continue
        m = re.match(r"(\d+)\|(\d+)\|(.+)$", line)
        if not m:
            errors.append(f"{path}:{n}: can't read: {line[:60]}")
            continue
        c, k, what = int(m.group(1)), int(m.group(2)), m.group(3).strip()
        if c in cards:
            errors.append(f"{path}:{n}: chapter {c} twice")
        if not 1 <= c <= chapters:
            errors.append(f"{path}:{n}: no chapter {c}")
        elif not 1 <= k <= verses.get(c, 0):
            errors.append(f"{path}:{n}: chapter {c} has no verse {k}")
        if "\t" in what or len(what) < 30 or len(what) > 420:
            errors.append(f"{path}:{n}: chapter {c}: the text is {len(what)} characters")
        cards[c] = (k, what)
    for c in range(1, chapters + 1):
        if c not in cards:
            errors.append(f"{path}: chapter {c} is missing")
        cover = [f for f in fits if f[0] <= c <= f[1]]
        if len(cover) != 1:
            errors.append(f"{path}: chapter {c} is in {len(cover)} 'fits' ranges")
    rows = []
    for c in sorted(cards):
        fit = next((f[2] for f in fits if f[0] <= c <= f[1]), "")
        rows.append((book, c, cards[c][0], fit, cards[c][1]))
    return rows, errors


def main():
    db = sqlite3.connect(KJV)
    books = {b: (name, ch) for b, name, ch in db.execute("SELECT id, name, chapters FROM books")}
    counts = {}
    for b, c, v in db.execute("SELECT book, chapter, MAX(verse) FROM verses GROUP BY book, chapter"):
        counts.setdefault(b, {})[c] = v
    rows, errors, done = [], [], []
    for path in sorted(glob.glob(os.path.join(SRC, "*.txt"))):
        b = int(os.path.basename(path)[:2])
        r, e = load_book(path, b, books[b][1], counts[b])
        rows += r
        errors += e
        done.append(b)
    missing = [books[b][0] for b in books if b not in done]
    for e in errors:
        print(e)
    print(f"{len(done)} books, {len(rows)} chapters; {len(errors)} problems; missing: {', '.join(missing) or 'none'}")
    if errors or "--check" in sys.argv:
        sys.exit(1 if errors else 0)
    if missing:
        print("Not written: some books are missing")
        sys.exit(1)
    with open(OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    print(f"Wrote {OUT} ({os.path.getsize(OUT) // 1024} KB)")


if __name__ == "__main__":
    main()
