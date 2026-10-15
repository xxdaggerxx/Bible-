"""Builds the commentaries added in 2.12 that don't come from CrossWire's SWORD modules, into
app/src/main/assets/commentaries/<id>.db.xz (the same entries(start, end, body) format as build_commentaries.py).

  Gill, Benson, Ellicott: public domain, from Bible Hub's chapter pages
      (https://biblehub.com/commentaries/<name>/<book>/<chapter>.htm), downloaded slowly and kept in <work>.
  Tyndale Open Study Notes: CC BY-SA 4.0, from tyndale_open-studynotes.zip
      (https://tyndaleopenresources.com), kept unmodified apart from the format.

Usage:
  python3 build_extra_commentaries.py download <work> gill benson ellicott
  python3 build_extra_commentaries.py biblehub <work> <out folder> gill benson ellicott
  python3 build_extra_commentaries.py tyndale <tyndale_open-studynotes.zip> <out folder>
"""
import html
import lzma
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.request
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
KJV = sqlite3.connect(os.path.join(ROOT, "app", "src", "main", "assets", "bibles", "kjv.db"), check_same_thread=False)
CHAPTERS = {b: n for b, n in KJV.execute("SELECT id, chapters FROM books")}
NAMES = {i: n for i, n in KJV.execute("SELECT id, name FROM books")}
SLUG = {i: n.lower().replace(" ", "_") for i, n in NAMES.items()}
SLUG[19] = "psalms"
SLUG[22] = "songs"
BY_SLUG = {s: i for i, s in SLUG.items()}
OSIS = {o.lower(): i for i, o in KJV.execute("SELECT id, osis FROM books")}
TYNDALE_BOOKS = {  # Tyndale's book names that aren't the app's OSIS names
    "isam": 9, "iisam": 10, "ikgs": 11, "iikgs": 12, "ichr": 13, "iichr": 14, "pr": 20, "jon": 32, "hagg": 37,
    "icor": 46, "iicor": 47, "ithes": 52, "iithes": 53, "itim": 54, "iitim": 55, "ipet": 60, "iipet": 61,
    "ijn": 62, "iijn": 63, "iiijn": 64,
}


def vid(b, c, v):
    return b * 1000000 + c * 1000 + v


def pack(rows, target):
    rows.sort()
    tmp = target + ".tmp.db"
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
    with lzma.open(target, "wb", preset=9 | lzma.PRESET_EXTREME) as f:
        f.write(data)
    os.remove(tmp)
    links = sum(b.count("[[") for _, _, b in rows)
    print("%-12s %6d notes %7d links  %6.1f MB  -> %5.1f MB xz" % (os.path.basename(target), len(rows), links,
                                                                    len(data) / 1e6, os.path.getsize(target) / 1e6))


def clean(s):
    s = re.sub(r"<(?:p|div|br|blockquote)\b[^>]*>", "\n\n", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = html.unescape(s).replace("\r", "").replace("\xa0", " ")
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r" *\n *", "\n", s)
    return re.sub(r"\n{3,}", "\n\n", s).strip()


# ---------- Bible Hub ----------

def download(work, names):
    """Four threads per commentary (by groups of books), each a page every two seconds; pages already saved are skipped."""
    def one(name, books):
        folder = os.path.join(work, name)
        os.makedirs(folder, exist_ok=True)
        for b in books:
            for ch in range(1, CHAPTERS[b] + 1):
                path = os.path.join(folder, f"{b:02d}-{ch:03d}.htm")
                if os.path.exists(path):
                    continue
                url = f"https://biblehub.com/commentaries/{name}/{SLUG[b]}/{ch}.htm"
                for attempt in range(4):
                    try:
                        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Ink & Word; personal use)"})
                        data = urllib.request.urlopen(req, timeout=60).read()
                        open(path, "wb").write(data)
                        break
                    except urllib.error.HTTPError as e:
                        if e.code == 404:
                            open(path, "wb").write(b"")  # the commentary has no notes on this chapter
                            break
                        time.sleep(10 * (attempt + 1))
                    except Exception:
                        time.sleep(10 * (attempt + 1))
                time.sleep(2)
            print(name, NAMES[b], flush=True)

    groups = [range(1, 19), range(19, 24), range(24, 44), range(44, 67)]  # about the same number of chapters each
    threads = [threading.Thread(target=one, args=(n, g)) for n in names for g in groups]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


VERSE = re.compile(r'<div class="versenum"><a href="/([a-z0-9_]+)/(\d+)-(\d+)\.htm">[^<]*</a></div><div class="verse">.*?</div>', re.S)
LINK = re.compile(r'<a href="(?:https?:)?//biblehub\.com/([a-z0-9_]+)/(\d+)-(\d+)\.htm">(.*?)</a>', re.S)


def biblehub_rows(work, name):
    rows = []
    for f in sorted(os.listdir(os.path.join(work, name))):
        b, ch = int(f[:2]), int(f[3:6])
        page = open(os.path.join(work, name, f), encoding="utf-8", errors="replace").read()
        if not page:
            continue
        start = page.find('<div class="chap">')
        stop = min([i for i in (page.find('<div id="botmenu"', start), page.find('<div id="right', start),
                                page.find('<div class="cmt"', start), page.find("<iframe", start)) if i > 0] or [len(page)])
        body = page[start:stop]
        marks = list(VERSE.finditer(body))
        intro = body[len('<div class="chap">'):marks[0].start()] if marks else ""
        if clean(intro) and len(clean(intro)) > 40:
            rows.append((vid(b, ch, 0), vid(b, ch, 0), clean(links(intro))))
        for i, m in enumerate(marks):
            v = int(m.group(3))
            text = body[m.end():marks[i + 1].start() if i + 1 < len(marks) else len(body)]
            text = clean(links(text))
            if len(text) > 3:
                rows.append((vid(b, ch, v), vid(b, ch, v), text))
    return rows


def links(s):
    def one(m):
        b = BY_SLUG.get(m.group(1))
        label = clean(m.group(4))
        if not b or not label:
            return m.group(4)
        v = vid(b, int(m.group(2)), int(m.group(3)))
        return "[[%d-%d|%s]]" % (v, v, label.replace("]", ")").replace("|", "/"))
    return LINK.sub(one, s)


# ---------- Tyndale Open Study Notes ----------

def tyndale_ref(r):
    """'Gen.1.1-2.3', 'Gen.1.22-25', 'John.1.1' -> (start, end)."""
    m = re.match(r"([1-3I]*[A-Za-z]+)\.(\d+)(?:\.(\d+))?(?:-(?:(\d+)\.)?(\d+))?$", r.strip())
    if not m:
        return None
    k = m.group(1).lower()
    b = TYNDALE_BOOKS.get(k) or OSIS.get(k)
    if not b:
        return None
    c, v = int(m.group(2)), int(m.group(3) or 0)
    if m.group(5):
        ec = int(m.group(4)) if m.group(4) else c
        ev = int(m.group(5))
        if not m.group(3):  # whole chapters: Gen.1-2
            ec, ev = int(m.group(5)), 999
    else:
        ec, ev = c, (v if m.group(3) else 999)
    return vid(b, c, v), vid(b, ec, ev)


def tyndale(zpath, out):
    z = zipfile.ZipFile(zpath)
    xml = z.read("Tyndale Open Study Notes/StudyNotes.xml").decode("utf-8")
    intros = z.read("Tyndale Open Study Notes/BookIntros.xml").decode("utf-8")
    rows = []

    def text(body):
        def link(m):
            r = tyndale_ref(m.group(1))
            label = clean(m.group(2))
            return "[[%d-%d|%s]]" % (r[0], r[1], label) if r and label else label
        body = re.sub(r'<a href="\?bref=([^"]+)">(.*?)</a>', link, body, flags=re.S)
        body = re.sub(r'<span class="sn-ref">.*?</span>\s*', "", body, count=1, flags=re.S)
        return re.sub(r"\s*\u2022\s*", "\n\n", clean(body))

    for name, body in re.findall(r'<item name="([^"]+)"[^>]*>.*?<body>(.*?)</body>', xml, re.S):
        r = tyndale_ref(name)
        if r:
            rows.append((r[0], r[1], text(body)))
    for name, body in re.findall(r'<item name="([^"]+)"[^>]*>.*?<body>(.*?)</body>', intros, re.S):
        k = name.removesuffix("Intro").lower()
        b = TYNDALE_BOOKS.get(k) or OSIS.get(k)
        if b:
            rows.append((vid(b, 0, 0), vid(b, 0, 0), text(body)))
    pack(rows, os.path.join(out, "tyndale.db.xz"))


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "download":
        download(args[0], args[1:])
    elif cmd == "biblehub":
        for n in args[2:]:
            pack(biblehub_rows(args[0], n), os.path.join(args[1], n + ".db.xz"))
    elif cmd == "tyndale":
        tyndale(args[0], args[1])
    else:
        sys.exit(__doc__)
