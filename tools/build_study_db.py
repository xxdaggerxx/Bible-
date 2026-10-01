"""Builds app/src/main/assets/study/study.db: word-study and reference data for version 0.8.

Sources (download into a working folder first, then run from there):
  Strong's-tagged texts, to line up words with Hebrew/Greek numbers (all public domain):
    https://ebible.org/Scriptures/eng-kjv2006_usfm.zip   -> eng-kjv2006/
    https://ebible.org/Scriptures/engbsb_usfm.zip        -> engbsb/
    https://ebible.org/Scriptures/engwebp_usfm.zip       -> engwebp/
  Strong's dictionaries (Strong 1890, public domain; JSON by Open Scriptures, CC BY-SA):
    https://raw.githubusercontent.com/openscriptures/strongs/master/hebrew/strongs-hebrew-dictionary.js
    https://raw.githubusercontent.com/openscriptures/strongs/master/greek/strongs-greek-dictionary.js
  Christian Classics Ethereal Library editions (public domain):
    https://www.ccel.org/ccel/e/easton/ebd2.xml   -> easton.xml   (Easton's Bible Dictionary, 1897)
    https://www.ccel.org/ccel/nave/bible.xml      -> nave.xml     (Nave's Topical Bible, 1896)
    https://www.ccel.org/ccel/henry/mhcc.xml      -> mhcc.xml     (Matthew Henry's Concise Commentary)

Usage: python3 build_study_db.py <working folder> <assets/bibles folder> <output study.db>

Verse ids are book*1000000 + chapter*1000 + verse, as in the Bible databases. Reference text in the
dictionary, topics and commentary marks each scripture reference as  [[start-end|label]]  with verse
ids, so the app can link it exactly. Paragraphs are separated by blank lines.
"""
import difflib
import glob
import html
import json
import os
import re
import sqlite3
import sys

work, bibles, out = sys.argv[1:4]

USFM = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER LAM "
        "EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP "
        "COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split()
OSIS = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl "
        "Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke "
        "John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet "
        "1John 2John 3John Jude Rev").split()
assert len(USFM) == len(OSIS) == 66
BOOK = {c: i + 1 for i, c in enumerate(USFM)}
OSIS_BOOK = {c: i + 1 for i, c in enumerate(OSIS)}

# The app splits verse text into words the same way (StudyRepository.words).
WORD = re.compile(r"[\w’']+", re.UNICODE)


def norm(w):
    return w.lower().replace("’", "'").strip("'")


def usfm_verses(folder):
    """{verse id: [(word, strong or None)]} from a Strong's-tagged USFM folder."""
    verses = {}
    tag = re.compile(r'\\\+?w ([^|\\]*)\|strong="([HG])0*(\d+)[a-z]?"\\\+?w\*')
    for path in sorted(glob.glob(os.path.join(folder, "*.usfm"))):
        text = open(path, encoding="utf-8").read()
        m = re.search(r"\\id (\w+)", text)
        if not m or m.group(1) not in BOOK:
            continue
        book = BOOK[m.group(1)]
        text = re.sub(r"\\f .*?\\f\*|\\x .*?\\x\*", " ", text, flags=re.S)
        chapter = 0
        for part in re.split(r"(\\c \d+|\\v \d+[-\d]*)", text):
            if part.startswith("\\c "):
                chapter = int(part[3:])
                cur = None
                continue
            if part.startswith("\\v "):
                v = int(re.match(r"\d+", part[3:]).group())
                cur = book * 1000000 + chapter * 1000 + v
                verses[cur] = []
                continue
            if chapter == 0 or not verses or cur is None:
                continue
            # Drop headings and titles that sit between verses (\s, \d, \r lines).
            part = re.sub(r"\\(?:s\d?|d|r|ms\d?|mr|qa|sp) [^\n]*", " ", part)
            pos = 0
            words = verses[cur]
            for t in tag.finditer(part):
                plain = re.sub(r"\\\+?[a-z]+\d?\*?", " ", part[pos:t.start()])
                words += [(w, None) for w in WORD.findall(plain)]
                strong = t.group(2) + t.group(3)
                words += [(w, strong) for w in WORD.findall(t.group(1))]
                pos = t.end()
            plain = re.sub(r"\\\+?[a-z]+\d?\*?", " ", part[pos:])
            words += [(w, None) for w in WORD.findall(plain)]
    return verses


def align(ours, tagged):
    """Strong's number (or None) for each word of our verse text, by matching word sequences."""
    a = [norm(w) for w in ours]
    b = [norm(w) for w, _ in tagged]
    result = [None] * len(a)
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal" or (op == "replace" and i2 - i1 == j2 - j1):
            for k in range(i2 - i1):
                result[i1 + k] = tagged[j1 + k][1]
    return result


def build_tags(db, code, asset, folder):
    src = sqlite3.connect(os.path.join(bibles, asset))
    tagged = usfm_verses(os.path.join(work, folder))
    rows = []
    total = hit = 0
    for vid, text in src.execute("SELECT id, text FROM verses ORDER BY id"):
        words = WORD.findall(text)
        t = tagged.get(vid)
        if not t:
            continue
        strongs = align(words, t)
        total += len(words)
        hit += sum(1 for s in strongs if s)
        # Numbers only (Hebrew in the Old Testament, Greek in the New), one per word, padded with
        # spaces so " 25 " finds a whole number; untagged words are empty.
        ot = vid < 40000000
        nums = []
        for s in strongs:
            if s and (s[0] == "H") == ot:
                nums.append(s[1:])
            elif s:
                nums.append(s)  # the rare number from the other testament keeps its letter
            else:
                nums.append("")
        rows.append((code, vid, " " + " ".join(nums) + " "))
    db.executemany("INSERT INTO tags VALUES(?,?,?)", rows)
    print(code, "verses", len(rows), "words tagged %.1f%%" % (100.0 * hit / max(1, total)))


def build_lexicon(db):
    for fn, prefix in (("strongs-hebrew-dictionary.js", "H"), ("strongs-greek-dictionary.js", "G")):
        text = open(os.path.join(work, fn), encoding="utf-8").read()
        body = text[text.index("{", text.index("var ")):text.rindex("}") + 1]
        data = json.loads(body)
        rows = []
        for k, e in data.items():
            num = int(k[1:])
            rows.append((
                prefix + str(num), e.get("lemma", ""), e.get("xlit") or e.get("translit", ""), e.get("pron", ""),
                (e.get("derivation") or "").strip(), (e.get("strongs_def") or "").strip(), (e.get("kjv_def") or "").strip(),
            ))
        db.executemany("INSERT INTO lexicon VALUES(?,?,?,?,?,?,?)", rows)
        print(prefix, "lexicon", len(rows))


def vid_of(osis_ref):
    """Bible:Exod.4.27-Exod.4.30 -> (start id, end id), or None."""
    r = osis_ref.replace("Bible:", "")
    parts = r.split("-")

    def one(p, default_end=False):
        bits = p.split(".")
        if bits[0] not in OSIS_BOOK:
            return None
        b = OSIS_BOOK[bits[0]]
        c = int(bits[1]) if len(bits) > 1 and bits[1].isdigit() else (1 if not default_end else 999)
        v = int(bits[2]) if len(bits) > 2 and bits[2].isdigit() else (0 if not default_end else 999)
        if len(bits) == 2 and not default_end:
            v = 1
        return b * 1000000 + c * 1000 + v

    try:
        s = one(parts[0])
        e = one(parts[-1], default_end=len(parts[-1].split(".")) < 3) if len(parts) > 1 else (
            one(parts[0], default_end=True) if len(parts[0].split(".")) < 3 else s)
    except ValueError:
        return None
    if s is None or e is None:
        return None
    return s, e


def to_text(fragment, refs=None):
    """ThML fragment -> plain text with [[start-end|label]] references and blank-line paragraphs."""
    def ref(m):
        attrs, label = m.group(1), m.group(2)
        o = re.search(r'osisRef="([^"]*)"', attrs)
        label = re.sub(r"<[^>]+>", "", label).strip()
        r = vid_of(o.group(1)) if o else None
        if not r:
            return label
        if refs is not None:
            refs.append(r)
        return "[[%d-%d|%s]]" % (r[0], r[1], label)

    s = re.sub(r"<scripRef([^>]*)>(.*?)</scripRef>", ref, fragment, flags=re.S)
    s = re.sub(r"<scripCom[^>]*/>", "", s)
    s = re.sub(r"<(?:p|h\d|tr|li|br)[^>]*>", "\n\n", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = html.unescape(s)
    s = re.sub(r"[ \t\r]*\n[ \t\r]*", "\n", s)
    s = re.sub(r"(?<!\n)\n(?!\n)", " ", s)  # line wraps inside a paragraph
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()


def build_easton(db):
    x = open(os.path.join(work, "easton.xml"), encoding="utf-8").read()
    rows = []
    for m in re.finditer(r"<term[^>]*>(.*?)</term>\s*<def[^>]*>(.*?)</def>", x, re.S):
        term = html.unescape(re.sub(r"<[^>]+>", "", m.group(1))).strip()
        body = to_text(m.group(2))
        if term and body:
            rows.append((term, term.lower(), body))
    db.executemany("INSERT INTO dictionary(term, key, body) VALUES(?,?,?)", rows)
    print("easton", len(rows))


def build_nave(db):
    x = open(os.path.join(work, "nave.xml"), encoding="utf-8").read()
    n = 0
    for m in re.finditer(r"<term[^>]*>(.*?)</term>\s*<def[^>]*>(.*?)</def>", x, re.S):
        term = html.unescape(re.sub(r"<[^>]+>", "", m.group(1))).strip()
        refs = []
        body = to_text(m.group(2), refs)
        body = re.sub(r"(^|\n)\.\s*", r"\1• ", body)  # sub-topic lines
        if not term or not body:
            continue
        name = term.title() if term.isupper() else term
        cur = db.execute("INSERT INTO topics(name, key, body) VALUES(?,?,?)", (name, term.lower(), body))
        tid = cur.lastrowid
        db.executemany("INSERT INTO topic_refs VALUES(?,?,?)", [(tid, s, e) for s, e in set(refs)])
        n += 1
    print("nave", n)


def build_commentary(db):
    x = open(os.path.join(work, "mhcc.xml"), encoding="utf-8").read()
    rows = []
    for m in re.finditer(r'<scripCom type="Commentary"[^>]*osisRef="([^"]*)"[^>]*/>\s*<div class="Commentary"[^>]*>(.*?)</div>', x, re.S):
        r = vid_of(m.group(1))
        if not r:
            continue
        body = to_text(m.group(2))
        # The section's own "Verses 1, 2" line is shown by the app from the range.
        body = re.sub(r"^Verses? [^\n]*\n\n", "", body)
        rows.append((r[0], r[1], body))
    # Some books (e.g. Ecclesiastes) have no section divisions: each section is a paragraph that
    # starts with its reference in bold.
    for m in re.finditer(r'<p[^>]*>\s*<b>\s*<scripRef[^>]*osisRef="([^"]*)"[^>]*>[^<]*</scripRef>\s*</b>(.*?)</p>', x, re.S):
        r = vid_of(m.group(1))
        body = to_text(m.group(2))
        if r and body:
            rows.append((r[0], r[1], body))
    db.executemany("INSERT INTO commentary VALUES(?,?,?)", rows)
    print("commentary sections", len(rows))


if os.path.exists(out):
    os.remove(out)
os.makedirs(os.path.dirname(out), exist_ok=True)
db = sqlite3.connect(out)
db.executescript("""
CREATE TABLE android_metadata (locale TEXT DEFAULT 'en_US');
INSERT INTO android_metadata VALUES ('en_US');
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE tags(version TEXT NOT NULL, id INTEGER NOT NULL, words TEXT NOT NULL, PRIMARY KEY(version, id)) WITHOUT ROWID;
CREATE TABLE lexicon(id TEXT PRIMARY KEY, lemma TEXT, xlit TEXT, pron TEXT, derivation TEXT, def TEXT, kjv TEXT) WITHOUT ROWID;
CREATE TABLE dictionary(id INTEGER PRIMARY KEY, term TEXT NOT NULL, key TEXT NOT NULL, body TEXT NOT NULL);
CREATE TABLE topics(id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL, body TEXT NOT NULL);
CREATE TABLE topic_refs(topic INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL);
CREATE TABLE commentary(start INTEGER NOT NULL, end INTEGER NOT NULL, body TEXT NOT NULL);
""")
db.execute("INSERT INTO meta VALUES('schema','1')")
build_tags(db, "KJV", "kjv.db", "eng-kjv2006")
build_tags(db, "BSB", "bsb.db", "engbsb")
build_tags(db, "WEB", "web.db", "engwebp")
build_lexicon(db)
build_easton(db)
build_nave(db)
build_commentary(db)
db.executescript("""
CREATE INDEX dictionary_key ON dictionary(key);
CREATE INDEX topics_key ON topics(key);
CREATE INDEX topic_refs_start ON topic_refs(start);
CREATE INDEX topic_refs_topic ON topic_refs(topic);
CREATE INDEX commentary_start ON commentary(start);
""")
db.commit()
db.execute("VACUUM")
db.close()
print(os.path.getsize(out) / 1e6, "MB")
