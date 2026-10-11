"""Builds "Hard words explained" (STD-23): a short meaning for words lay readers may not know.

The glossary is written by AI (Claude, in the 1.14 build session) in tools/hardwords/*.txt:

    # comment
    forms|meaning

The first form is the headword; the others are its other spellings and forms. Words in kjv*.txt are
old English words, marked only in the King James Version; words in terms.txt are Bible words that can
be hard in any version (atonement, propitiation, Pharisee, cubit).

This script checks the glossary (lower-case single words, no word listed twice, meanings short),
finds the Bible dictionary article for each headword (for "Read more"), reports words found in no
Bible, and writes app/src/main/assets/study/hardwords.tsv:

    scope<TAB>forms<TAB>meaning<TAB>dictionary term      (scope: kjv or all)

Usage: python3 tools/build_hardwords.py [--check]
"""
import glob
import os
import re
import sqlite3
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "tools", "hardwords")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "study", "hardwords.tsv")
MAX_MEANING = 110


def bible_words(db):
    con = sqlite3.connect(os.path.join(ROOT, "app", "src", "main", "assets", "bibles", db))
    words = set()
    for (text,) in con.execute("SELECT text FROM verses"):
        words.update(w.lower() for w in re.findall(r"[A-Za-z]+", text))
    return words


def main():
    study = sqlite3.connect(os.path.join(ROOT, "app", "src", "main", "assets", "study", "study.db"))
    terms = {t.lower(): t for (t,) in study.execute("SELECT term FROM dictionary")}
    kjv = bible_words("kjv.db")
    every = kjv | bible_words("bsb.db") | bible_words("web.db")
    seen = {}
    rows = []
    problems = []
    unused = []
    for path in sorted(glob.glob(os.path.join(SRC, "*.txt"))):
        scope = "kjv" if os.path.basename(path).startswith("kjv") else "all"
        for n, line in enumerate(open(path, encoding="utf-8"), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            where = f"{os.path.basename(path)}:{n}"
            parts = line.split("|")
            if len(parts) != 2:
                problems.append(f"{where}: expected forms|meaning")
                continue
            forms = [f.strip() for f in parts[0].split(",")]
            meaning = parts[1].strip()
            for f in forms:
                if not re.fullmatch(r"[a-z]+", f):
                    problems.append(f"{where}: '{f}' is not one lower-case word")
                if f in seen:
                    problems.append(f"{where}: '{f}' already listed at {seen[f]}")
                seen[f] = where
            if not meaning or len(meaning) > MAX_MEANING:
                problems.append(f"{where}: meaning must be 1-{MAX_MEANING} characters")
            if "\t" in meaning:
                problems.append(f"{where}: tab in meaning")
            pool = kjv if scope == "kjv" else every
            if not any(f in pool for f in forms):
                unused.append(where + " " + forms[0])
            term = next((terms[f] for f in forms if f in terms), "")
            rows.append((scope, ",".join(forms), meaning, term))
    for p in problems:
        print(p)
    print(f"{len(rows)} entries, {len(seen)} words, {sum(1 for r in rows if r[3])} with a dictionary article, "
          f"{len(unused)} not found in any Bible, {len(problems)} problems")
    if unused:
        print("not found:", ", ".join(unused))
    if problems:
        sys.exit(1)
    if "--check" in sys.argv:
        return
    with open(OUT, "w", encoding="utf-8") as out:
        for r in rows:
            out.write("\t".join(r) + "\n")
    print(f"wrote {OUT} ({os.path.getsize(OUT) // 1024} KB)")


if __name__ == "__main__":
    main()
