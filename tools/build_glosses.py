"""How the KJV, BSB and WEB translate each Strong's number, for tagging imported Bibles (BIB-4).

Adds two tables to study.db:
  glosses(stem, strong, n)  how often an English word (by stem) translates a Strong's number
  stems(stem, n)            how often the stem appears at all
The app's WordTagger reads them when a Bible is imported, instead of learning them on the tablet.
The stem and word splitting must match WordTagger.stem and StudyRepository.words exactly.

Run on its own to add the tables to an existing study.db:
  python3 tools/build_glosses.py app/src/main/assets/study/study.db app/src/main/assets/bibles
build_study_db.py also calls build_glosses() when it builds study.db from scratch.
"""
import os
import re
import sqlite3
import sys

WORD = re.compile(r"[\w’']+", re.UNICODE)
SUFFIXES = ["ing", "edst", "eth", "est", "ies", "ied", "ed", "es", "s", "ly"]


def norm(w):
    return w.lower().replace("’", "'").strip("'")


def stem(w):
    s = norm(w)
    if s.endswith("'s"):
        s = s[:-2]
    for suf in SUFFIXES:
        if s.endswith(suf) and len(s) - len(suf) >= 3:
            return s[: -len(suf)]
    return s


def build_glosses(db, bibles):
    pairs, counts = {}, {}
    for code, asset in (("KJV", "kjv.db"), ("BSB", "bsb.db"), ("WEB", "web.db")):
        texts = dict(sqlite3.connect(os.path.join(bibles, asset)).execute("SELECT id, text FROM verses"))
        for vid, raw in db.execute("SELECT id, words FROM tags WHERE version = ?", (code,)):
            prefix = "H" if vid < 40000000 else "G"
            strongs = [None if not n else (n if n[0].isalpha() else prefix + n) for n in raw[1:-1].split(" ")]
            words = WORD.findall(texts.get(vid, ""))
            if len(words) != len(strongs):
                continue
            for w, s in zip(words, strongs):
                k = stem(w)
                counts[k] = counts.get(k, 0) + 1
                if s:
                    pairs[(k, s)] = pairs.get((k, s), 0) + 1
    db.executescript("""
DROP TABLE IF EXISTS glosses;
DROP TABLE IF EXISTS stems;
CREATE TABLE glosses(stem TEXT NOT NULL, strong TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(stem, strong)) WITHOUT ROWID;
CREATE TABLE stems(stem TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID;
""")
    db.executemany("INSERT INTO glosses VALUES(?,?,?)", [(k, s, n) for (k, s), n in pairs.items()])
    db.executemany("INSERT INTO stems VALUES(?,?)", list(counts.items()))
    print("glosses", len(pairs), "stems", len(counts))


if __name__ == "__main__":
    study, bibles = sys.argv[1], sys.argv[2]
    db = sqlite3.connect(study)
    build_glosses(db, bibles)
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(os.path.getsize(study) / 1e6, "MB")
