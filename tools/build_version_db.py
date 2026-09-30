"""Builds a text-only Bible database (same schema as kjv.db, without cross-references).

Cross-references live only in kjv.db; verse ids (book * 1_000_000 + chapter * 1_000 + verse)
are shared by every version, so the app looks them up there for any version.

Usage:
  python3 build_version_db.py BSB BSB.json out/bsb.db [bsb_usfm_dir]   # scrollmapper/bible_databases JSON
  python3 build_version_db.py WEB engwebp_vpl.txt out/web.db           # eBible.org verse-per-line text

The optional USFM folder adds section headings (\s1, \s2, \ms1, with \r / \mr references) to a
`headings` table. The app shows the BSB's headings in every version (they are public domain).

Sources:
  BSB: https://raw.githubusercontent.com/scrollmapper/bible_databases/master/formats/json/BSB.json
       https://ebible.org/Scriptures/engbsb_usfm.zip  (headings)
  WEB: https://ebible.org/Scriptures/engwebp_vpl.zip  (engwebp_vpl.txt)
"""
import json
import os
import re
import sqlite3
import sys

NAMES = ["Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth", "1 Samuel", "2 Samuel",
         "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther", "Job", "Psalms", "Proverbs",
         "Ecclesiastes", "Song of Solomon", "Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel",
         "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah", "Malachi",
         "Matthew", "Mark", "Luke", "John", "Acts", "Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians",
         "Philippians", "Colossians", "1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon",
         "Hebrews", "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"]
OSIS = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl Song Isa "
        "Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke John Acts Rom 1Cor 2Cor "
        "Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet 1John 2John 3John Jude Rev").split()
# eBible.org VPL book codes, in canonical order
# Standard USFM book codes, in canonical order
USFM = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER LAM EZK DAN "
        "HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP COL 1TH 2TH 1TI 2TI "
        "TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split()
VPL = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SOL ISA JER LAM EZE DAN "
       "HOS JOE AMO OBA JON MIC NAH HAB ZEP HAG ZEC MAL MAT MAR LUK JOH ACT ROM 1CO 2CO GAL EPH PHI COL 1TH 2TH 1TI 2TI "
       "TIT PHM HEB JAM 1PE 2PE 1JO 2JO 3JO JUD REV").split()

META = {
    "BSB": ("Berean Standard Bible",
            "The Holy Bible, Berean Standard Bible (BSB). Dedicated to the public domain by Bible Hub and the Berean "
            "Bible translation committee, 2023."),
    "WEB": ("World English Bible",
            "World English Bible (WEB). Public domain. “World English Bible” is a trademark of eBible.org."),
}


def read_scrollmapper_json(path):
    d = json.load(open(path, encoding="utf-8"))
    assert len(d["books"]) == 66
    for bi, bk in enumerate(d["books"], 1):
        for ch in bk["chapters"]:
            for v in ch["verses"]:
                yield bi, ch["chapter"], v["verse"], v["text"]


def read_vpl(path):
    idx = {code: i + 1 for i, code in enumerate(VPL)}
    line_re = re.compile(r"^(\S+) (\d+):(\d+) ?(.*)$")
    for line in open(path, encoding="utf-8-sig"):
        m = line_re.match(line.rstrip("\n"))
        if not m:
            continue
        yield idx[m.group(1)], int(m.group(2)), int(m.group(3)), m.group(4)


HEADING_LEVELS = {"ms1": 0, "s1": 1, "s": 1, "s2": 2}


def clean_usfm(text):
    text = re.sub(r"\\(f|x)\b.*?\\\1\*", "", text)          # footnotes and cross-references
    text = re.sub(r"\\\+?w\s+([^|\\]*)(\|[^\\]*)?\\\+?w\*", r"\1", text)  # \w word|strong="..."\w*
    text = re.sub(r"\\\+?[a-z]+[0-9]*\*?", "", text)          # any other markers
    return re.sub(r"\s+", " ", text).strip()


def read_headings(usfm_dir):
    """(verse_id, level, text, refs) for every section heading, attached to the verse that follows it."""
    idx = {code: i + 1 for i, code in enumerate(USFM)}
    out = []
    for name in sorted(os.listdir(usfm_dir)):
        if not name.endswith(".usfm"):
            continue
        book, chapter, pending = None, 0, []
        for line in open(os.path.join(usfm_dir, name), encoding="utf-8-sig"):
            m = re.match(r"\\(\w+)\s*(.*)", line.strip())
            if not m:
                continue
            tag, rest = m.group(1), m.group(2)
            if tag == "id":
                book = idx.get(rest.split()[0])
            elif book is None:
                continue
            elif tag == "c":
                chapter = int(rest.split()[0])
            elif tag in HEADING_LEVELS:
                pending.append([HEADING_LEVELS[tag], clean_usfm(rest), ""])
            elif tag in ("r", "mr") and pending:
                pending[-1][2] = clean_usfm(rest)
            elif tag == "v" and pending:
                verse = int(re.match(r"\d+", rest).group(0))
                vid = book * 1_000_000 + chapter * 1_000 + verse
                out.extend((vid, level, text, refs) for level, text, refs in pending if text)
                pending = []
    return out


def build(code, src, out, usfm_dir=None):
    reader = read_scrollmapper_json if src.endswith(".json") else read_vpl
    rows, seen, chapters = [], set(), {}
    for b, c, v, t in reader(src):
        t = re.sub(r"\s+", " ", t).strip()
        vid = b * 1_000_000 + c * 1_000 + v
        if not t or vid in seen:
            continue  # verses a translation leaves out, or duplicates
        seen.add(vid)
        rows.append((vid, b, c, v, t))
        chapters[b] = max(chapters.get(b, 0), c)
    assert len(chapters) == 66, len(chapters)

    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
    CREATE TABLE android_metadata (locale TEXT DEFAULT 'en_US');
    INSERT INTO android_metadata VALUES ('en_US');
    CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    CREATE TABLE books(id INTEGER PRIMARY KEY, name TEXT NOT NULL, osis TEXT NOT NULL, chapters INTEGER NOT NULL);
    CREATE TABLE verses(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, text TEXT NOT NULL);
    CREATE INDEX verses_bc ON verses(book, chapter);
    CREATE VIRTUAL TABLE verses_fts USING fts4(text, content="verses");
    CREATE TABLE xrefs(from_id INTEGER NOT NULL, to_start INTEGER NOT NULL, to_end INTEGER NOT NULL, votes INTEGER NOT NULL);
    CREATE TABLE headings(verse_id INTEGER NOT NULL, level INTEGER NOT NULL, text TEXT NOT NULL, refs TEXT NOT NULL);
    """)
    name, copyright_ = META[code]
    db.executemany("INSERT INTO meta VALUES(?,?)",
                   [("code", code), ("name", name), ("copyright", copyright_), ("schema", "1")])
    db.executemany("INSERT INTO books VALUES(?,?,?,?)",
                   [(i + 1, NAMES[i], OSIS[i], chapters[i + 1]) for i in range(66)])
    db.executemany("INSERT INTO verses VALUES(?,?,?,?,?)", rows)
    db.execute("INSERT INTO verses_fts(verses_fts) VALUES('rebuild')")
    heads = read_headings(usfm_dir) if usfm_dir else []
    db.executemany("INSERT INTO headings VALUES(?,?,?,?)", heads)
    db.execute("CREATE INDEX headings_verse ON headings(verse_id)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(code, len(rows), "verses,", len(heads), "headings,", round(os.path.getsize(out) / 1e6, 1), "MB")


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None)
