"""Builds app/src/main/assets/commentaries/<id>.db.xz: the public-domain commentaries for version 1.3.

Sources: CrossWire SWORD modules (zCom / zCom4, KJV versification), downloaded and unzipped into a
working folder, one folder per module:
    https://www.crosswire.org/ftpmirror/pub/sword/packages/rawzip/<Module>.zip
Modules: MHC, JFB, Wesley, Geneva, Barnes, Clarke, KD, RWP, CalvinCommentaries, TDavid.

Usage: python3 build_commentaries.py <working folder> <assets/bibles/kjv.db> <output folder>

Each commentary becomes a small SQLite database, compressed with xz (the app unpacks it the first time
the commentary is opened):
    entries(start, end, body)
      start, end: verse ids (book*1000000 + chapter*1000 + verse) of the verses the note covers.
      A chapter's introduction has verse 0; a book's introduction has chapter 0 and verse 0.
      body: plain text; paragraphs separated by blank lines; scripture references written as
      [[start-end|label]] so the app can link them (as in study.db).
"""
import html
import lzma
import os
import re
import sqlite3
import struct
import sys
import zlib
import glob

work, kjv, out = sys.argv[1:4]

MODULES = ["MHC", "JFB", "Wesley", "Geneva", "Barnes", "Clarke", "KD", "RWP", "CalvinCommentaries", "TDavid"]
IDS = {"MHC": "mhc", "JFB": "jfb", "Wesley": "wesley", "Geneva": "geneva", "Barnes": "barnes", "Clarke": "clarke",
       "KD": "kd", "RWP": "rwp", "CalvinCommentaries": "calvin", "TDavid": "tdavid"}

db = sqlite3.connect(kjv)
VC = {}
for b, ch, n in db.execute("SELECT book, chapter, MAX(verse) FROM verses GROUP BY book, chapter"):
    VC.setdefault(b, {})[ch] = n
NAMES = {i: n for i, n in db.execute("SELECT id, name FROM books")}
OSIS = {o: i for i, o in db.execute("SELECT id, osis FROM books")}

# ---------- book names and abbreviations ----------

def key(s):
    return re.sub(r"[^0-9a-z]", "", s.lower())

ABBR = {}
for i, n in NAMES.items():
    ABBR[key(n)] = i
for o, i in OSIS.items():
    ABBR[key(o)] = i
EXTRA = {
    "ge": 1, "gn": 1, "ex": 2, "exo": 2, "le": 3, "lv": 3, "nu": 4, "nm": 4, "de": 5, "dt": 5, "jos": 6, "jud": 7,
    "jdg": 7, "jg": 7, "ru": 8, "rt": 8, "1sa": 9, "2sa": 10, "1ki": 11, "1kgs": 11, "2ki": 12, "2kgs": 12, "1ch": 13,
    "2ch": 14, "ezr": 15, "ne": 16, "es": 17, "est": 17, "jb": 18, "ps": 19, "psa": 19, "psalm": 19, "pr": 20,
    "pro": 20, "prov": 20, "ec": 21, "ecc": 21, "eccles": 21, "so": 22, "song": 22, "ca": 22, "cant": 22, "sos": 22,
    "is": 23, "isa": 23, "je": 24, "jer": 24, "la": 25, "lam": 25, "eze": 26, "ezek": 26, "eze": 26, "da": 27,
    "dan": 27, "ho": 28, "hos": 28, "joe": 29, "jl": 29, "am": 30, "ob": 31, "obad": 31, "jon": 32, "jnh": 32,
    "mic": 33, "mi": 33, "na": 34, "nah": 34, "hab": 35, "zep": 36, "zeph": 36, "hag": 37, "zec": 38, "zech": 38,
    "mal": 39, "mt": 40, "mat": 40, "matt": 40, "mr": 41, "mk": 41, "mar": 41, "lu": 42, "lk": 42, "luk": 42,
    "joh": 43, "jn": 43, "jhn": 43, "ac": 44, "act": 44, "ro": 45, "rom": 45, "1co": 46, "1cor": 46, "2co": 47,
    "2cor": 47, "ga": 48, "gal": 48, "eph": 49, "php": 50, "phil": 50, "col": 51, "1th": 52, "1thess": 52,
    "2th": 53, "2thess": 53, "1ti": 54, "1tim": 54, "2ti": 55, "2tim": 55, "tit": 56, "phm": 57, "phile": 57,
    "philem": 57, "heb": 58, "jas": 59, "jam": 59, "1pe": 60, "1pet": 60, "2pe": 61, "2pet": 61, "1jo": 62,
    "1jn": 62, "1joh": 62, "2jo": 63, "2jn": 63, "2joh": 63, "3jo": 64, "3jn": 64, "3joh": 64, "jude": 65,
    "re": 66, "rev": 66, "apoc": 66,
}
ABBR.update(EXTRA)

ROMAN = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100}

def number(s):
    s = s.strip().lower().rstrip(".")
    if s.isdigit():
        return int(s)
    if s and all(ch in ROMAN for ch in s):
        total = 0
        for a, b in zip(s, s[1:] + " "):
            v = ROMAN[a]
            total += -v if b in ROMAN and ROMAN[b] > v else v
        return total
    return None

def book_of(name):
    k = key(name)
    if k in ABBR:
        return ABBR[k]
    hits = [i for kk, i in ABBR.items() if kk.startswith(k)] if len(k) >= 3 else []
    return hits[0] if len(set(hits)) == 1 else None

def vid(b, c, v):
    return b * 1000000 + c * 1000 + v

def osis_range(ref):
    """'Bible:Gen.1.2-Gen.1.5' or 'Joh.3.8' -> (start, end) or None."""
    r = ref.replace("Bible:", "").split(" ")[0]
    parts = r.split("-")

    def one(p, end=False):
        bits = p.split(".")
        b = OSIS.get(bits[0]) or book_of(bits[0])
        if not b:
            return None
        try:
            c = int(bits[1]) if len(bits) > 1 else (999 if end else 1)
            v = int(bits[2]) if len(bits) > 2 else (999 if end else 1)
        except ValueError:
            return None
        return vid(b, c, v)

    s = one(parts[0])
    if s is None:
        return None
    if len(parts) == 1:
        e = one(parts[0], end=len(parts[0].split(".")) < 3)
    else:
        last = parts[-1]
        if "." not in last and last.isdigit():  # Gen.1.2-5
            e = s - s % 1000 + int(last)
        else:
            e = one(last, end=len(last.split(".")) < 3)
    if e is None:
        return None
    return (s, max(s, e))

PLAIN = re.compile(r"^\s*((?:[123]|I{1,3})?\s*[A-Za-z][A-Za-z.]*)\s*([0-9ivxlc]+)[.:,\s]\s*([0-9]+)(?:\s*[-–]\s*([0-9]+))?", re.I)

def plain_range(text):
    """'Eph 4:24', 'Ge 1:10', 'Gen 1:2-5', 'John iii. 21' -> (start, end) or None."""
    m = PLAIN.match(text)
    if not m:
        return None
    name = re.sub(r"^III?\s*|^I\s+", lambda x: {"I ": "1", "II": "2", "III": "3"}.get(x.group(0).strip()[:3], ""), m.group(1))
    b = book_of(name)
    c = number(m.group(2))
    if not b or not c:
        return None
    v = int(m.group(3))
    e = int(m.group(4)) if m.group(4) else v
    return (vid(b, c, v), vid(b, c, max(v, e)))

# ---------- markup to text ----------

def to_text(s):
    def ref(m):
        attrs, label = m.group(2), m.group(3)
        label = re.sub(r"<[^>]+>", "", label)
        o = re.search(r'osisRef="([^"]*)"', attrs) or re.search(r'passage="([^"]*)"', attrs)
        r = None
        if o:
            r = osis_range(o.group(1)) if "." in o.group(1) and ":" not in o.group(1) else plain_range(o.group(1))
        if not r:
            r = plain_range(html.unescape(label))
        clean = html.unescape(label).strip()
        if not r or not clean:
            return label
        return "[[%d-%d|%s]]" % (r[0], r[1], clean.replace("]", ")").replace("|", "/"))

    s = re.sub(r"<(reference|scripRef)([^>]*)>(.*?)</\1>", ref, s, flags=re.S)
    s = re.sub(r'<div[^>]*type="x-milestone"[^>]*/>', "", s)
    s = re.sub(r"<(?:milestone|chapter|verse)[^>]*/?>", "", s)
    s = re.sub(r"<title[^>]*>", "\n\n", s)
    s = re.sub(r"</title>", "\n\n", s)
    s = re.sub(r'<div[^>]*(?:sID|eID)[^>]*/>', "\n\n", s)
    s = re.sub(r"<(?:p|div|blockquote|list|item|row|lg|table)\b[^>]*>", "\n\n", s)
    s = re.sub(r"</(?:p|div|blockquote|item|row|lg|table|list)>", "\n\n", s)
    s = re.sub(r"<(?:br|lb)\s*/?>", "\n", s)
    s = re.sub(r"</l>", "\n", s)
    s = re.sub(r"</cell>\s*<cell[^>]*>", " · ", s)
    s = re.sub(r"<note[^>]*>", " (", s)
    s = re.sub(r"</note>", ")", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = html.unescape(s)
    s = s.replace("\r", "")
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r" *\n *", "\n", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()

# ---------- SWORD zCom reader ----------

def index_map(testament):
    m = {}
    i = 2  # 0: module heading, 1: testament heading
    for b in (range(1, 40) if testament == "ot" else range(40, 67)):
        m[i] = (b, 0, 0); i += 1
        for ch in sorted(VC[b]):
            m[i] = (b, ch, 0); i += 1
            for v in range(1, VC[b][ch] + 1):
                m[i] = (b, ch, v); i += 1
    return m

def read(folder):
    path = glob.glob(os.path.join(folder, "modules/comments/*/*/"))[0]
    conf = open(glob.glob(os.path.join(folder, "mods.d/*.conf"))[0], encoding="utf-8", errors="replace").read()
    four = "ModDrv=zCom4" in conf
    keys, texts = {}, {}
    for t in ("ot", "nt"):
        x = "b" if os.path.exists(path + t + ".bzv") else "c"
        if not os.path.exists(path + t + "." + x + "zv"):
            continue
        bzs = open(path + t + "." + x + "zs", "rb").read()
        bzv = open(path + t + "." + x + "zv", "rb").read()
        bzz = open(path + t + "." + x + "zz", "rb").read()
        blocks = {}

        def block(n):
            if n not in blocks:
                off, size, _ = struct.unpack("<III", bzs[n * 12:n * 12 + 12])
                blocks[n] = zlib.decompress(bzz[off:off + size])
            return blocks[n]

        rec, fmt = (12, "<III") if four else (10, "<IIH")
        m = index_map(t)
        for i in range(len(bzv) // rec):
            bn, off, size = struct.unpack(fmt, bzv[i * rec:i * rec + rec])
            if size == 0 or i not in m:
                continue
            k = (t, bn, off, size)
            keys.setdefault(k, []).append(m[i])
            if k not in texts:
                texts[k] = block(bn)[off:off + size].decode("utf-8", "replace")
    return [(ks, texts[k]) for k, ks in keys.items()]

# ---------- build ----------

os.makedirs(out, exist_ok=True)
for mod in MODULES:
    rows = []
    for ks, raw in read(os.path.join(work, mod)):
        body = to_text(raw)
        if not body:
            continue
        ids = sorted(vid(*k) for k in ks)
        rows.append((ids[0], ids[-1], body))
    rows.sort()
    tmp = os.path.join(out, IDS[mod] + ".db")
    if os.path.exists(tmp):
        os.remove(tmp)
    c = sqlite3.connect(tmp)
    c.execute("CREATE TABLE entries(start INTEGER NOT NULL, end INTEGER NOT NULL, body TEXT NOT NULL)")
    c.executemany("INSERT INTO entries VALUES(?,?,?)", rows)
    c.execute("CREATE INDEX entries_start ON entries(start)")
    c.commit()
    c.execute("VACUUM")
    c.close()
    data = open(tmp, "rb").read()
    with lzma.open(tmp + ".xz", "wb", preset=9 | lzma.PRESET_EXTREME) as f:
        f.write(data)
    os.remove(tmp)
    refs = sum(b.count("[[") for _, _, b in rows)
    print("%-20s %6d notes %7d links  %6.1f MB  -> %5.1f MB xz" % (mod, len(rows), refs, len(data) / 1e6, os.path.getsize(tmp + ".xz") / 1e6))
