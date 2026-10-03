"""Builds app/src/main/assets/study/original.db: the Hebrew and Greek text word by word (STD-4, BIB-9).

Source: STEPBible "Translators Amalgamated" Hebrew OT and Greek NT (Tyndale House, Cambridge; CC BY 4.0),
from github.com/STEPBible/STEPBible-Data, folder "Translators Amalgamated OT+NT":
  TAHOT Gen-Deu / Jos-Est / Job-Sng / Isa-Mal - Translators Amalgamated Hebrew OT - STEPBible.org CC BY.txt
  TAGNT Mat-Jhn / Act-Rev - Translators Amalgamated Greek NT - STEPBible.org CC-BY.txt
Changes made for this app: only the columns shown are kept; Hebrew words that come only from the
Septuagint (type X) are left out; Greek words found in neither the main modern editions (NA) nor the
KJV's text (TR) are left out. Verses use English numbering (the files give it first).

Usage: python3 build_original_db.py <folder with the six files> <output original.db>

One row per verse. `words` holds one line per word, fields separated by tabs:
  original  transliteration  English  Strong's  grammar  edition
where grammar is "H:Ncfsa" / "A:..." (Hebrew / Aramaic, OSHB codes) or "G:N-GSF" (Robinson codes), and
edition is "" (all texts), "m" (modern editions only, not the KJV's) or "k" (the KJV's text only).
"""
import glob
import os
import re
import sqlite3
import sys

folder, out = sys.argv[1:3]
STEP = ("Gen Exo Lev Num Deu Jos Jdg Rut 1Sa 2Sa 1Ki 2Ki 1Ch 2Ch Ezr Neh Est Job Psa Pro Ecc Sng Isa Jer Lam "
        "Ezk Dan Hos Jol Amo Oba Jon Mic Nam Hab Zep Hag Zec Mal Mat Mrk Luk Jhn Act Rom 1Co 2Co Gal Eph Php "
        "Col 1Th 2Th 1Ti 2Ti Tit Phm Heb Jas 1Pe 2Pe 1Jn 2Jn 3Jn Jud Rev").split()
BOOK = {c: i + 1 for i, c in enumerate(STEP)}
REF = re.compile(r"^([1-3]?[A-Z][a-z]{1,2})\.(\d+)\.(\d+)(?:\([^)]*\))?#\d+=(\S+)$")


def strong(s):
    m = re.match(r"([HG])0*(\d+)", s)
    return m.group(1) + m.group(2) if m else ""


def clean(s):
    return s.replace("/", "").replace("\\", "").strip()


verses = {}
for path in sorted(glob.glob(os.path.join(folder, "TAHOT*.txt"))):
    for line in open(path, encoding="utf-8-sig"):
        f = line.rstrip("\n").split("\t")
        m = REF.match(f[0])
        if not m or len(f) < 6 or m.group(4).startswith("X"):
            continue
        vid = BOOK[m.group(1)] * 1000000 + int(m.group(2)) * 1000 + int(m.group(3))
        parts = f[4].split("\\")[0].split("/")
        main = next((i for i, p in enumerate(parts) if p.startswith("{")), len(parts) - 1)
        gram = f[5].split("/")
        lang = gram[0][:1] if gram and gram[0] else "H"
        g = gram[min(main, len(gram) - 1)] if gram else ""
        if g.startswith(("H", "A")) and g is gram[0]:
            g = g[1:]
        verses.setdefault(vid, []).append(
            (clean(f[1]), f[2].replace("/", ""), " ".join(f[3].replace("/", " ").split()),
             strong(parts[main].strip("{}")), lang + ":" + g, ""))
for path in sorted(glob.glob(os.path.join(folder, "TAGNT*.txt"))):
    for line in open(path, encoding="utf-8-sig"):
        f = line.rstrip("\n").split("\t")
        m = REF.match(f[0])
        if not m or len(f) < 4:
            continue
        kind = m.group(4)
        n, k = "N" in kind, "K" in kind
        if not (n or k):
            continue
        vid = BOOK[m.group(1)] * 1000000 + int(m.group(2)) * 1000 + int(m.group(3))
        gm = re.match(r"(\S+) \((.*)\)", f[1])
        word, xlit = (gm.group(1), gm.group(2)) if gm else (f[1], "")
        st, _, gram = f[3].partition("=")
        verses.setdefault(vid, []).append(
            (word, xlit, f[2].strip(), strong(st), "G:" + gram, "" if n and k else ("m" if n else "k")))

if os.path.exists(out):
    os.remove(out)
db = sqlite3.connect(out)
db.executescript("""
CREATE TABLE android_metadata (locale TEXT DEFAULT 'en_US');
INSERT INTO android_metadata VALUES ('en_US');
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE original(id INTEGER PRIMARY KEY, words TEXT NOT NULL);
""")
db.execute("INSERT INTO meta VALUES('schema','1')")
db.executemany("INSERT INTO original VALUES(?,?)",
               ((v, "\n".join("\t".join(w) for w in ws)) for v, ws in sorted(verses.items())))
db.commit()
db.execute("VACUUM")
db.close()
print(len(verses), "verses", sum(len(w) for w in verses.values()), "words", os.path.getsize(out) / 1e6, "MB")
