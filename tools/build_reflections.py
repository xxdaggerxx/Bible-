"""Builds Reflections: short devotional reflections on Bible passages, written by AI for Ink & Word and grounded
only in existing devotionals (requirements, section 39).

Stages, each resumable:
  catalog   Index the devotionals by Bible reference, without AI. The public-domain classics (Spurgeon's
            Morning and Evening and Faith's Checkbook, F. B. Meyer's Our Daily Homily) are downloaded in full
            and split into days; each day's key verse is found by matching its words against the KJV, which
            also corrects the scans' misread numbers.
  passages  Group the key verses into passages: near-identical ones merge, each devotional goes to one passage.
  write     For each passage: find modern devotionals on it (search limited to devotional sites), then
            write the reflection from the classic devotionals' text and the modern pages, as JSON.
            The script drops any source it can't check: a classic must be assigned to the passage, a web
            page must have come up in the search.
  pack      Turn the finished reflections into assets/reflections.db.xz.

Usage:
  python3 build_reflections.py catalog <dir>
  python3 build_reflections.py passages <dir> [John ...]
  OPENROUTER_API_KEY=... python3 build_reflections.py write <dir> all|<passage id> ...
  python3 build_reflections.py pack <dir> app/src/main/assets/reflections.db.xz

<dir> (tools/reflections) holds sources/ (the classic texts), catalog.json, passages.json, out/ (finished
reflections, one JSON per passage), research/ and usage.json.
"""
import json
import os
import re
import sqlite3
import sys
import time
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

CLASSICS = {
    "morneve": ("Charles Spurgeon", "Morning and Evening", "https://www.ccel.org/ccel/s/spurgeon/morneve/cache/morneve.txt",
                "https://www.ccel.org/ccel/spurgeon/morneve"),
    "checkbook": ("Charles Spurgeon", "Faith's Checkbook", "https://www.ccel.org/ccel/s/spurgeon/checkbook/cache/checkbook.txt",
                  "https://www.ccel.org/ccel/spurgeon/checkbook"),
}
for _i in range(1, 6):
    CLASSICS[f"homily{_i}"] = ("F. B. Meyer", "Our Daily Homily",
                               f"https://archive.org/download/ourdailyhomily0{_i}meye/ourdailyhomily0{_i}meye_djvu.txt",
                               f"https://archive.org/details/ourdailyhomily0{_i}meye")


# ---------- Bible text and references ----------

def bible(version):
    return sqlite3.connect(os.path.join(ASSETS, "bibles", f"{version}.db"))


ABBREV = {  # the classics' abbreviations, beyond the full names and OSIS ids
    "gen": 1, "ex": 2, "exod": 2, "lev": 3, "num": 4, "deut": 5, "josh": 6, "judg": 7, "sam": 9, "kgs": 11, "kings": 11,
    "chron": 13, "chr": 13, "neh": 16, "esth": 17, "est": 17, "psa": 19, "ps": 19, "psalm": 19, "prov": 20, "pro": 20,
    "eccl": 21, "eccles": 21, "ecc": 21, "song": 22, "cant": 22, "canticles": 22, "isa": 23, "is": 23, "jer": 24,
    "lam": 25, "ezek": 26, "eze": 26, "dan": 27, "hos": 28, "obad": 31, "mic": 33, "nah": 34, "hab": 35, "zeph": 36,
    "hag": 37, "zech": 38, "mal": 39, "matt": 40, "mat": 40, "mk": 41, "lk": 42, "jn": 43, "rom": 45, "cor": 46,
    "gal": 48, "eph": 49, "phil": 50, "col": 51, "thess": 52, "thes": 52, "tim": 54, "tit": 56, "philem": 57,
    "heb": 58, "jas": 59, "pet": 60, "rev": 66, "revelation": 66, "apoc": 66,
}
NUMBERED = {9, 11, 13, 46, 52, 54, 60, 62}  # first of each numbered pair: "2 Sam" is 9 + 1


def book_ids():
    ids, names = dict(ABBREV), {}
    for i, name, osis in bible("kjv").execute("SELECT id, name, osis FROM books"):
        names[i] = name
        base = re.sub(r"^\d\s*", "", name.lower())
        if not name[0].isdigit():
            ids[base] = ids[osis.lower()] = i
        elif name[0] == "1":
            ids[base] = i
    return ids, names


def roman(s):
    vals = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100}
    s = s.lower()
    if not s or any(ch not in vals for ch in s):
        return None
    n = 0
    for a, b in zip(s, s[1:] + " "):
        n += -vals[a] if b in vals and vals[b] > vals[a] else vals[a]
    return n


WORD = re.compile(r"[a-z]+")
STOP = set("the and of to a in that is he i it for his be with unto shall not they all thou thy thee them as but my me "
           "was ye which are this o have him from by will hath on so you your we our us".split())


def words(t):
    return [w for w in WORD.findall(t.lower().replace("’", "'")) if len(w) > 1]


class Verses:
    """The KJV, for finding the verse a devotional quotes."""

    def __init__(self):
        self.rows = bible("kjv").execute("SELECT id, book, chapter, verse, text FROM verses ORDER BY id").fetchall()
        self.sets = [set(words(r[4])) for r in self.rows]
        self.vocab = set().union(*self.sets)
        self.by_book = {}
        for i, r in enumerate(self.rows):
            self.by_book.setdefault(r[1], []).append(i)

    def find(self, quote, book=None, chapter=None, verse_hint=""):
        """(first verse id, last verse id, score) of the verse or verses that best match quote: in the given chapter
        if it matches well there, else anywhere in the book, else anywhere. Words the scans garbled (not in the KJV)
        are ignored; verse_hint (the scanned verse number, letters standing for misread digits) breaks ties."""
        q = [w for w in words(quote) if w in self.vocab]
        q = [w for w in q if w not in STOP] or q
        if not q:
            return None
        qs = set(q)

        def fits(i):
            v = str(self.rows[i][3])
            h = re.sub(r"[^0-9a-z]", "", verse_hint.lower())
            return len(h) == len(v) and all(c == d or not c.isdigit() for c, d in zip(h, v))

        def best(indices):
            indices = list(indices)
            scores = [(len(qs & self.sets[i]) / len(qs), fits(i), -n, i) for n, i in enumerate(indices)]
            if not scores:
                return 0, None, None
            s, _, _, i = max(scores)
            top = (s, i, i)
            for i in indices:  # quotes spanning two verses
                if i + 1 < len(self.rows) and self.rows[i + 1][1] == self.rows[i][1] \
                        and qs & self.sets[i] and qs & self.sets[i + 1]:
                    s2 = len(qs & (self.sets[i] | self.sets[i + 1])) / len(qs)
                    if s2 > top[0] + 0.15:
                        top = (s2, i, i + 1)
            return top

        tries = []
        if book and chapter:
            tries.append([i for i in self.by_book.get(book, []) if self.rows[i][2] == chapter])
        if book:
            tries.append(self.by_book.get(book, []))
        tries.append(range(len(self.rows)))
        for n, indices in enumerate(tries):
            s, a, b = best(indices)
            if a is not None and s >= (0.6 if n < len(tries) - 1 else 0.8) and (len(qs) >= 2 or n == 0):
                return self.rows[a][0], self.rows[b][0], round(s, 2)
        return None


def ref_text(a, b, names):
    bk, ch, v = a // 1000000, a // 1000 % 1000, a % 1000
    if a == b:
        return f"{names[bk]} {ch}:{v}"
    if b // 1000 == a // 1000:
        return f"{names[bk]} {ch}:{v}-{b % 1000}"
    return f"{names[bk]} {ch}:{v}-{b // 1000 % 1000}:{b % 1000}"


# ---------- catalog ----------

def fetch(url, path):
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Ink & Word catalog; personal use)"})
        data = urllib.request.urlopen(req, timeout=120).read()
        with open(path, "wb") as f:
            f.write(data)
        time.sleep(2)
    return open(path, encoding="utf-8", errors="replace").read()


REF = re.compile(r"((?:[123]|I{1,3})\s*)?([A-Z][a-z]+)\.?\s+([0-9]+):\s*([0-9]+)")


def parse_spurgeon(text, src):
    """Morning and Evening: "Morning, January 1" then the quoted verse and its reference.
    Faith's Checkbook: "January 1" headings, a title, the quoted verse and its reference."""
    days = re.split(r"\n[ \t]*(?=(?:(?:Morning|Evening), )?(?:Jan|Feb|Mar|Apr|May|June?|July?|Aug|Sept?|Oct|Nov|Dec)"
                    r"[a-z]*\.? \d{1,2}\s*\n)", text)
    out = []
    for d in days[1:]:
        head, _, rest = d.partition("\n")
        body = re.sub(r"\[\d+\]Go To (?:Morning|Evening) Reading", "", rest)
        body = body.split("_______")[0].strip()
        m = REF.search(body[:600])
        if not m or len(body) < 300:
            continue
        before = body[:m.start()]
        quote = " ".join(re.findall(r"\"([^\"]+)\"", before)) or before
        out.append({"date": head.strip(), "quote": re.sub(r"\s+", " ", quote).strip(), "ref": m.group(0).strip(),
                    "text": re.sub(r"[ \t]+", " ", body).strip()})
    return out


def parse_homily(text):
    """Our Daily Homily (scanned): each day starts with a quoted verse and its reference (roman chapter, verse),
    then a paragraph opening with a word in capitals. Page numbers and running heads are dropped."""
    lines = [l.rstrip() for l in text.splitlines()]
    starts = []
    for i, l in enumerate(lines):
        if re.match(r"^[A-Z][A-Z'’]{1,}[,;:!?.]?\s+[a-z]", l.strip()):
            # the heading: the reference block just above, and the quoted verse above that if it is a block of its own
            j, blocks, cur = i - 1, [], []
            while j >= 0 and len(blocks) < 2:
                s = lines[j].strip()
                if re.fullmatch(r"\d{1,3}", s) or not s:
                    if cur:
                        blocks.append(cur)
                        cur = []
                        if len(blocks) == 1 and not re.match(r"^\W*[\w(]*\.?\s*[ivxlcIVXLC]{1,8}\s*[.,]", " ".join(blocks[0])) \
                                and len(" ".join(blocks[0])) > 25:
                            break  # the quote and its reference in one block
                    j -= 1
                    continue
                cur.insert(0, s)
                j -= 1
                if len(cur) > 5:
                    break
            if cur and len(blocks) < 2:
                blocks.append(cur)
            head = [l for blk in reversed(blocks) for l in blk]
            joined = " ".join(head)
            if head and re.search(r"\b[ivxlc]+\s*[.,]\s*[\dgjiIlsoOS]", joined, re.I) and len(joined) < 400:
                starts.append((i, joined))
    out = []
    for n, (i, head) in enumerate(starts):
        end = starts[n + 1][0] - len(starts[n + 1][1].split()) // 8 - 1 if n + 1 < len(starts) else len(lines)
        body = "\n".join(lines[i:end])
        body = re.sub(r"\n\s*\d{1,3}\s*\n", "\n", body)
        body = re.sub(r"-\s*\n\s*", "", body)  # words split over lines
        body = re.sub(r"\s+", " ", body).strip()
        out.append({"date": None, "head": head, "text": body})
    return out


def homily_ref(head, ids):
    """Book and chapter from a heading like "As God had commanded. Genesis vii. 9." (scanned, so approximate)."""
    m = list(re.finditer(r"(?:\b([123]|I{1,3})\s+)?([A-Z][a-z]{1,12})\.?\s+([ivxlcIVXLC]{1,8})\s*[.,]", head))
    for x in reversed(m):
        name = x.group(2).lower()
        book = ids.get(name)
        if not book:
            continue
        if book in NUMBERED and x.group(1):
            book += {"1": 0, "i": 0, "2": 1, "ii": 1, "3": 2, "iii": 2}.get(x.group(1).lower(), 0)
        quote = head[:x.start()]
        hint = re.match(r"\s*([0-9a-zA-Z]{1,3})", head[x.end():])
        return book, roman(x.group(3)), quote, hint.group(1) if hint else ""
    return None, None, head, ""


def cmd_catalog(d):
    os.makedirs(os.path.join(d, "sources"), exist_ok=True)
    ids, names = book_ids()
    kjv = Verses()
    records, misses = [], []
    for sid, (author, title, url, page) in CLASSICS.items():
        text = fetch(url, os.path.join(d, "sources", f"{sid}.txt"))
        n0 = len(records)
        if sid.startswith("homily"):
            for k, e in enumerate(parse_homily(text)):
                book, ch, quote, hint = homily_ref(e["head"], ids)
                hit = kjv.find(quote, book, ch, hint)
                if not hit or len(e["text"]) < 400:
                    misses.append((sid, e["head"][:120]))
                    continue
                records.append({"id": f"{sid}-{k + 1:03d}", "source": sid, "author": author, "title": title,
                                "date": None, "url": page, "heading": e["head"], "start": hit[0], "end": hit[1],
                                "match": hit[2], "ref": ref_text(hit[0], hit[1], names), "text": e["text"]})
        else:
            for e in parse_spurgeon(text, sid):
                m = REF.search(e["ref"])
                num, name = (m.group(1) or "").strip(), m.group(2).lower()
                book = ids.get(name)
                if book in NUMBERED and num:
                    book += {"1": 0, "I": 0, "2": 1, "II": 1, "3": 2, "III": 2}.get(num, 0)
                hit = kjv.find(e["quote"], book, int(m.group(3)), m.group(4))
                if not hit:
                    misses.append((sid, e["date"] + " " + e["ref"]))
                    continue
                key = sid + "-" + re.sub(r"\W+", "-", e["date"].lower())
                records.append({"id": key, "source": sid, "author": author, "title": title, "date": e["date"],
                                "url": page, "heading": e["quote"], "start": hit[0], "end": hit[1], "match": hit[2],
                                "ref": ref_text(hit[0], hit[1], names), "text": e["text"]})
        print(f"{sid}: {len(records) - n0} devotionals")
    with open(os.path.join(d, "catalog.json"), "w") as f:
        json.dump(records, f, indent=0, ensure_ascii=False)
    with open(os.path.join(d, "catalog_misses.txt"), "w") as f:
        f.writelines(f"{s}\t{h}\n" for s, h in misses)
    print(f"{len(records)} devotionals; {len(misses)} headings not matched to a verse (catalog_misses.txt)")


# ---------- passages ----------

def parse_refs(text, ids):
    """[(first id, last id)] from a heading's references, e.g. "(Matthew 14:13–21; Mark 6:30–44)"."""
    out = []
    for m in re.finditer(r"([123]?\s?[A-Z][a-z]+(?: of [A-Z][a-z]+)?)\s+(\d+):(\d+)(?:[–-](\d+)(?::(\d+))?)?", text):
        name = m.group(1).strip().lower()
        book = ids.get(name) or ids.get(re.sub(r"^[123]\s?", "", name))
        if name[0] in "123" and book in NUMBERED:
            book += int(name[0]) - 1
        if not book:
            continue
        ch, v = int(m.group(2)), int(m.group(3))
        if m.group(5):
            end = (book, int(m.group(4)), int(m.group(5)))
        else:
            end = (book, ch, int(m.group(4) or v))
        out.append((book * 1000000 + ch * 1000 + v, end[0] * 1000000 + end[1] * 1000 + end[2]))
    return out


def sections():
    """The BSB's sections as (first verse id, last verse id, title, parallel refs), in KJV verse ids."""
    ids, _ = book_ids()
    verses = [r[0] for r in bible("kjv").execute("SELECT id FROM verses ORDER BY id")]
    heads = bible("bsb").execute("SELECT verse_id, text, refs FROM headings WHERE level = 1 ORDER BY verse_id").fetchall()
    starts = {}
    for vid, title, refs in heads:
        starts.setdefault(vid, (title, refs))
    out, cur = [], None
    for vid in verses:
        new_book = cur is None or vid // 1000000 != cur[0] // 1000000
        if vid in starts or new_book:
            if cur:
                out.append(cur)
            title, refs = starts.get(vid, ("", ""))
            cur = [vid, vid, title, parse_refs(refs, ids)]
        else:
            cur[1] = vid
    out.append(cur)
    return [tuple(x) for x in out]


GOSPELS = range(40, 44)
# Gospel sections the traditional reading takes as separate events from the synoptic sections listed with them:
# the first meeting of Andrew and Peter (John 1), the temple cleansing at the start of Jesus' ministry (John 2),
# and "Ask in my name" (John 16), which only shares a theme with Matthew 18:19-20.
SEPARATE = {43001035, 43002012, 43016023}


def cmd_passages(d):
    """Units of work: each BSB section, with parallel Gospel sections grouped (mutual references), and the classic
    devotionals whose key verse falls in it."""
    _, names = book_ids()
    secs = sections()
    where = {}
    for n, (a, b, _, _) in enumerate(secs):
        for v in range(a, b + 1):
            where[v] = n

    def sec_of(v):
        return where.get(v)

    parent = list(range(len(secs)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for n, (a, b, _, refs) in enumerate(secs):
        if a // 1000000 not in GOSPELS:
            continue
        for ra, _ in refs:
            m = sec_of(ra)
            if m is None or m == n or ra // 1000000 not in GOSPELS or ra // 1000000 == a // 1000000:
                continue
            same_title = secs[m][2] == secs[n][2]
            mutual = any(sec_of(x) == n for x, _ in secs[m][3])
            john = 43 in (a // 1000000, ra // 1000000)
            if (same_title if john else mutual or same_title) and not {a, secs[m][0]} & SEPARATE:
                gn, gm = find(n), find(m)
                books_n = {secs[k][0] // 1000000 for k in range(len(secs)) if find(k) == gn}
                books_m = {secs[k][0] // 1000000 for k in range(len(secs)) if find(k) == gm}
                if gn != gm and not books_n & books_m:  # never two sections of one book
                    parent[gn] = gm
    groups = {}
    for n in range(len(secs)):
        groups.setdefault(find(n), []).append(n)
    cat = json.load(open(os.path.join(d, "catalog.json")))
    units = []
    for members in sorted(groups.values()):
        rng = [(secs[n][0], secs[n][1]) for n in members]
        title = secs[members[0]][2]
        uid = f"{rng[0][0] // 1000000:02d}-{rng[0][0] // 1000 % 1000:03d}-{rng[0][0] % 1000:03d}"
        classics = [r["id"] for r in cat if any(a <= r["start"] <= b for a, b in rng)]
        units.append({"id": uid, "title": title, "ranges": rng, "refs": [ref_text(a, b, names) for a, b in rng],
                      "classics": classics})
    units.sort(key=lambda u: u["ranges"][0][0])
    with open(os.path.join(d, "passages.json"), "w") as f:
        json.dump(units, f, indent=1, ensure_ascii=False)
    par = [u for u in units if len(u["ranges"]) > 1]
    print(f"{len(secs)} sections -> {len(units)} passages ({len(par)} groups of parallel accounts); "
          f"{sum(1 for u in units if u['classics'])} have a classic devotional")
    for u in par[:8]:
        print("  parallel:", u["title"], "|", "; ".join(u["refs"]))


# ---------- write ----------

SITES = {  # modern devotionals: (site name, domains)
    "Desiring God": ["desiringgod.org"], "Our Daily Bread": ["odb.org"], "Ligonier Ministries": ["ligonier.org"],
    "In Touch Ministries": ["intouch.org"], "Billy Graham Evangelistic Association": ["billygraham.org"],
    "Grace to You": ["gty.org"], "The Gospel Coalition": ["thegospelcoalition.org"], "Insight for Living": ["insight.org"],
    "Crosswalk": ["crosswalk.com"], "The Upper Room": ["upperroom.org"], "Turning Point": ["davidjeremiah.org"],
    "BibleGateway": ["biblegateway.com"], "My Utmost for His Highest": ["utmost.org"],
}
DOMAINS = [x for ds in SITES.values() for x in ds]
RESEARCH_MODEL = WRITE_MODEL = CHECK_MODEL = "google/gemini-3.8-flash"
PROMPT_VERSION = "reflections-1"

RESEARCH_TASK = """Find modern devotionals on {refs} ("{title}"): short devotional readings (daily devotions or devotional
articles by pastors and Bible teachers) whose main Bible text is in this passage. Not sermons, commentaries or Q&A pages.

{text}

Search first (the fetch tool can only open pages that came up in your searches), then open the best ones and read them.
Choose up to {n} devotionals from different authors, preferring ones whose main text is a single verse or saying in the
passage when there are several. Then write, for each one, exactly this block:

### <title>
Author: <author, or the ministry if none is named>
Site: <site name>
URL: <the page's address, exactly as found>
Passage: <its main Bible text, written like "John 15:5" or "John 15:1-11">
Points:
- <its main points, in your own words: 3 to 6 short lines. No quotations longer than 10 words.>

Only devotionals you actually opened and read. If you find none on this passage, write just: NONE"""

STYLE = """How to write (readers are ordinary church members, some young in faith):
- Plain, warm English at about a 12-year-old's reading level. Short sentences. Explain church words in a few words
  ("grace, God's kindness we don't deserve").
- Speak to the reader as "you" and "we". Never preachy, sentimental or guilt-tripping; no exclamation marks.
- Traditional, mainstream Protestant teaching. Where the devotionals differ, keep to what they share."""

WRITE_SYSTEM = f"""You write short devotional reflections on Bible passages for the Ink & Word Bible app, grounded only in
existing devotionals that are given to you. You are a careful summariser, not a preacher with your own ideas.

Rules:
- Every point in Reflect must come from the devotionals assigned to that reflection; give each paragraph the ids of the
  devotionals it draws on. Add nothing from your own knowledge or opinions beyond what the Bible passage itself says.
- Use your own words. You may quote at most one short phrase (under 15 words) from a devotional in a reflection, in
  quotation marks, naming its author.
- Questions and prayer follow from the reflection's own points; they add no new teaching.
- Reflections on the same passage must not repeat each other: each makes its own points, asks its own questions and
  prays its own prayer. The passage reflection covers the passage as a whole and leaves the sayings to theirs.

{STYLE}"""

WRITE_TASK = """Passage: {refs} ("{title}")

{text}

Devotionals:
{devotionals}

Write these reflections:
{plan}

For each: a short title of at most 8 words (for a single saying, the heart of the saying in quotation marks, e.g.
"Jesus wept" or "I am the vine"); its key verse
(one verse, like "John 15:5"); Reflect in 2-4 short paragraphs (150-250 words in all), each with the ids of the
devotionals it draws on; one or two questions to ask yourself; and a prayer of 2-4 sentences."""

SCHEMA = {
    "type": "object", "additionalProperties": False, "required": ["reflections"],
    "properties": {"reflections": {"type": "array", "items": {
        "type": "object", "additionalProperties": False,
        "required": ["key", "title", "key_verse", "reflect", "questions", "prayer"],
        "properties": {
            "key": {"type": "string", "description": "R1, R2, ... as in the plan"},
            "title": {"type": "string"}, "key_verse": {"type": "string"},
            "reflect": {"type": "array", "items": {"type": "object", "additionalProperties": False,
                                                   "required": ["text", "sources"],
                                                   "properties": {"text": {"type": "string"},
                                                                  "sources": {"type": "array", "items": {"type": "string"}}}}},
            "questions": {"type": "array", "items": {"type": "string"}},
            "prayer": {"type": "string"}}}}},
}

CHECK_TASK = """Below are devotionals and a reflection written from them. For each paragraph of the reflection, say
whether everything it teaches can be found in the devotionals it names (or in the Bible passage itself). Fine and not
to be flagged: different wording, plain explanations of words ("grace, God's kindness we don't deserve"; "pruning,
cutting back"), retelling what the passage says, and gentle application of the devotionals' own points to the reader.
Flag only teaching that is in none of them, or that the devotionals contradict.

Passage: {refs}
{text}

Devotionals:
{devotionals}

Reflection:
{reflection}"""

CHECK_SCHEMA = {"type": "object", "additionalProperties": False, "required": ["paragraphs"],
                "properties": {"paragraphs": {"type": "array", "items": {
                    "type": "object", "additionalProperties": False, "required": ["number", "supported", "problem"],
                    "properties": {"number": {"type": "integer"}, "supported": {"type": "boolean"},
                                   "problem": {"type": "string"}}}}}}


def text_of(a, b):
    """The verses in the BSB; verses the BSB leaves out (as later additions) come from the KJV, marked."""
    bsb = dict(bible("bsb").execute("SELECT id, text FROM verses WHERE id BETWEEN ? AND ?", (a, b)))
    kjv = bible("kjv").execute("SELECT id, verse, text FROM verses WHERE id BETWEEN ? AND ?", (a, b)).fetchall()
    return "\n".join(f"{v} {bsb[i]}" if i in bsb else f"{v} [KJV only] {t}" for i, v, t in kjv)


def unit_text(u):
    return "\n\n".join(f"{r}:\n{text_of(a, b)}" for r, (a, b) in zip(u["refs"], u["ranges"]))


def openrouter(body):
    import urllib.error
    err = None
    for attempt in range(5):
        req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
                                              "Content-Type": "application/json"})
        try:
            r = json.load(urllib.request.urlopen(req, timeout=900))
            if "choices" in r:
                return r
            err = r.get("error")
        except urllib.error.HTTPError as e:
            if e.code in (400, 401, 402, 403):
                raise RuntimeError(f"OpenRouter {e.code}: {e.read()[:300]}")
            err = e.code
        except Exception as e:  # network trouble: try again
            err = e
        time.sleep(10 * 2 ** attempt)
    raise RuntimeError(f"OpenRouter failed: {err}")


def research(u):
    n = 2 if all(b - a < 12 for a, b in u["ranges"]) else 4
    r = openrouter({
        "model": RESEARCH_MODEL, "max_tokens": 12000, "usage": {"include": True},
        "messages": [{"role": "user", "content": RESEARCH_TASK.format(refs="; ".join(u["refs"]), title=u["title"],
                                                                      text=unit_text(u), n=n)}],
        "tools": [{"type": "openrouter:web_search", "parameters": {"engine": "exa", "allowed_domains": DOMAINS,
                                                                   "max_uses": 2, "max_results": 6}},
                  {"type": "openrouter:web_fetch", "parameters": {"allowed_domains": DOMAINS, "max_uses": n + 1,
                                                                  "max_content_tokens": 5000}}],
    })
    m = r["choices"][0]["message"]
    urls = sorted({a["url_citation"]["url"] for a in m.get("annotations") or [] if a.get("type") == "url_citation"})
    return {"notes": (m.get("content") or "").strip(), "urls": urls, "cost": r["usage"].get("cost", 0),
            "model": RESEARCH_MODEL}


def norm_url(x):
    return re.sub(r"^https?://(www\.)?", "", x.strip().rstrip("/").split("#")[0].split("?")[0]).lower()


def modern_devotionals(res, u, ids):
    """The devotionals in the research notes whose page came up in the search and whose text is in the passage."""
    found = {norm_url(x) for x in res["urls"]}
    out = []
    for block in re.split(r"\n(?=###\s)", "\n" + res["notes"])[1:]:
        f = dict(re.findall(r"^(Author|Site|URL|Passage):\s*(.+)$", block, re.M))
        title = block.split("\n", 1)[0].lstrip("# ").strip()
        url = (f.get("URL") or "").strip("<> ")
        points = block.split("Points:", 1)[1].strip() if "Points:" in block else ""
        if not url or norm_url(url) not in found or not points or not any(x in url for x in DOMAINS):
            continue
        refs = parse_refs(f.get("Passage", ""), ids)
        span = next(((a, b) for a, b in refs if any(ra <= a <= rb or a <= ra <= b for ra, rb in u["ranges"])), None)
        if refs and not span:
            continue  # its text is elsewhere
        site = next((name for name, ds in SITES.items() if any(x in url for x in ds)), f.get("Site", ""))
        out.append({"author": f.get("Author", "").strip(), "title": title, "site": site, "url": url,
                    "start": span[0] if span else None, "end": span[1] if span else None, "points": points})
    return out


def plan(u, devs, names):
    """Single sayings with two or more devotionals on them get a reflection of their own (written first, so the
    passage's reflection leaves them to it); every other devotional goes to the passage's reflection."""
    length = sum(b - a + 1 for a, b in u["ranges"])
    spans = [(d["start"], d["end"], k) for k, d in devs.items() if d.get("start") and d["end"] - d["start"] <= 2]
    clusters = []
    for a, b, k in sorted(spans):
        if clusters and a <= clusters[-1][1]:
            clusters[-1][1] = max(clusters[-1][1], b)
            clusters[-1][2].append(k)
        else:
            clusters.append([a, b, [k]])
    reflections, used = [], set()
    if length >= 6:
        for a, b, ks in clusters:
            if len(ks) >= 2 and b - a <= 2:
                reflections.append({"key": f"R{len(reflections) + 1}", "kind": "saying", "start": a, "end": b,
                                    "ref": ref_text(a, b, names), "devotionals": ks})
                used |= set(ks)
    rest = [k for k in devs if k not in used]
    if rest:
        reflections.append({"key": f"R{len(reflections) + 1}", "kind": "passage", "start": u["ranges"][0][0],
                            "end": u["ranges"][0][1], "ref": "; ".join(u["refs"]), "devotionals": rest})
    return reflections


def classic_entry(r):
    return {"author": r["author"], "title": r["title"], "date": r["date"], "url": r["url"], "start": r["start"],
            "end": r["end"], "heading": r["heading"], "text": r["text"][:3500]}


def describe(k, d, names):
    where = f" — on {ref_text(d['start'], d['end'], names)}" if d.get("start") else ""
    if "text" in d:
        when = f", {d['date']}" if d.get("date") else ""
        return f"[{k}] {d['author']}, {d['title']}{when}{where} (public domain)\n{d['text']}"
    return f"[{k}] {d['author']}, \"{d['title']}\" ({d['site']}){where} — main points:\n{d['points']}"


def source_line(d, names):
    if "text" in d:
        return {"author": d["author"], "work": d["title"], "date": d.get("date"), "on": ref_text(d["start"], d["end"], names),
                "url": d["url"]}
    return {"author": d["author"], "work": d["title"], "site": d["site"], "url": d["url"]}


def write_unit(u, d, names, ids, cat):
    rpath = os.path.join(d, "research", u["id"] + ".json")
    cost = 0
    if os.path.exists(rpath):
        res = json.load(open(rpath))
    else:
        res = research(u)
        cost += res["cost"]
        with open(rpath, "w") as f:
            json.dump(res, f, indent=1, ensure_ascii=False)
    devs = {}
    for cid in u["classics"]:
        devs[f"D{len(devs) + 1}"] = classic_entry(cat[cid])
    for m in modern_devotionals(res, u, ids):
        if not any(norm_url(m["url"]) == norm_url(x.get("url", "")) for x in devs.values()):
            devs[f"D{len(devs) + 1}"] = m
    if not devs:
        return {"id": u["id"], "reflections": [], "note": "no devotionals found"}, cost
    todo = plan(u, devs, names)
    plan_text = "\n".join(
        f"{p['key']}: " + (f"the single saying {p['ref']}" if p["kind"] == "saying" else f"the whole passage {p['ref']}")
        + f", from {', '.join(p['devotionals'])}" for p in todo)
    if any(p["kind"] == "saying" for p in todo):
        plan_text += "\n(Write the sayings first; the passage reflection must not repeat their points.)"
    material = "\n\n".join(describe(k, v, names) for k, v in devs.items())
    task = WRITE_TASK.format(refs="; ".join(u["refs"]), title=u["title"], text=unit_text(u), devotionals=material,
                             plan=plan_text)
    for attempt in range(3):
        r = openrouter({"model": WRITE_MODEL, "max_tokens": 16000, "usage": {"include": True},
                        "messages": [{"role": "system", "content": WRITE_SYSTEM}, {"role": "user", "content": task}],
                        "response_format": {"type": "json_schema",
                                            "json_schema": {"name": "reflections", "strict": True, "schema": SCHEMA}}})
        cost += r["usage"].get("cost", 0)
        try:
            written = {x["key"]: x for x in json.loads(r["choices"][0]["message"]["content"])["reflections"]}
        except (json.JSONDecodeError, KeyError, TypeError):
            continue
        out, problems = [], []
        for p in todo:
            w = written.get(p["key"])
            if not w:
                problems.append(f"{p['key']} missing")
                continue
            ok = set(p["devotionals"])
            paras = [{"text": x["text"].strip(), "sources": [s for s in x["sources"] if s in ok]} for x in w["reflect"]]
            dropped = [x for x in paras if not x["sources"]]
            paras = [x for x in paras if x["sources"]]
            kv = parse_refs(w["key_verse"], ids)
            key = kv[0] if kv and p["start"] <= kv[0][0] <= max(p["end"], *(b for a, b in u["ranges"])) else (p["start"], p["start"])
            long_quotes = [q for x in paras for q in re.findall(r"[\"“]([^\"”]+)[\"”]", x["text"]) if len(q.split()) > 15]
            if not paras or long_quotes:
                problems.append(f"{p['key']}: " + ("long quotation" if long_quotes else "no sourced paragraph"))
                continue
            used = sorted({s for x in paras for s in x["sources"]}, key=lambda s: int(s[1:]))
            out.append({"kind": p["kind"], "start": p["start"], "end": p["end"], "ref": p["ref"],
                        "ranges": u["ranges"] if p["kind"] == "passage" else [[p["start"], p["end"]]],
                        "title": w["title"].strip(), "key_verse": list(key), "reflect": paras,
                        "questions": [q.strip() for q in w["questions"]][:2], "prayer": w["prayer"].strip(),
                        "sources": {s: source_line(devs[s], names) for s in used}, "dropped": len(dropped)})
        if not problems:
            break
    else:
        return {"id": u["id"], "reflections": [], "note": "; ".join(problems)}, cost
    # grounding check: a second pass flags paragraphs the devotionals don't support; those are removed
    for x in out:
        ref_devs = "\n\n".join(describe(k, devs[k], names) for k in x["sources"])
        text = "\n".join(f"{i + 1}. [{', '.join(p['sources'])}] {p['text']}" for i, p in enumerate(x["reflect"]))
        r = openrouter({"model": CHECK_MODEL, "max_tokens": 4000, "usage": {"include": True},
                        "messages": [{"role": "user", "content": CHECK_TASK.format(
                            refs=x["ref"], text=unit_text(u), devotionals=ref_devs, reflection=text)}],
                        "response_format": {"type": "json_schema",
                                            "json_schema": {"name": "check", "strict": True, "schema": CHECK_SCHEMA}}})
        cost += r["usage"].get("cost", 0)
        try:
            flags = {c["number"]: c["problem"] for c in json.loads(r["choices"][0]["message"]["content"])["paragraphs"]
                     if not c["supported"]}
        except (json.JSONDecodeError, KeyError, TypeError):
            flags = {}
        x["unsupported"] = [{"text": p["text"], "problem": flags[i + 1]} for i, p in enumerate(x["reflect"]) if i + 1 in flags]
        keep = [p for i, p in enumerate(x["reflect"]) if i + 1 not in flags]
        x["reflect"] = keep or x["reflect"]
        x["checked"] = bool(keep)
    return {"id": u["id"], "title": u["title"], "refs": u["refs"], "reflections": out, "model": WRITE_MODEL,
            "research_model": RESEARCH_MODEL, "prompt": PROMPT_VERSION, "date": time.strftime("%Y-%m-%d")}, cost


def cmd_write(d, which, workers=8):
    import concurrent.futures
    import threading
    _, names = book_ids()
    ids, _ = book_ids()
    units = json.load(open(os.path.join(d, "passages.json")))
    cat = {r["id"]: r for r in json.load(open(os.path.join(d, "catalog.json")))}
    if which != ["all"]:
        units = [u for u in units if u["id"] in which or any(w in u["refs"] for w in which)]
    for sub in ("out", "research"):
        os.makedirs(os.path.join(d, sub), exist_ok=True)
    todo = [u for u in units if not os.path.exists(os.path.join(d, "out", u["id"] + ".json"))]
    upath = os.path.join(d, "usage.json")
    usage = json.load(open(upath)) if os.path.exists(upath) else {"cost": 0, "units": 0}
    lock = threading.Lock()
    print(f"{len(todo)} of {len(units)} passages to write", flush=True)

    def one(u):
        res, cost = write_unit(u, d, names, ids, cat)
        with lock:
            with open(os.path.join(d, "out", u["id"] + ".json"), "w") as f:
                json.dump(res, f, indent=1, ensure_ascii=False)
            usage["cost"] += cost
            usage["units"] += 1
            with open(upath, "w") as f:
                json.dump(usage, f)
            print(f"{u['id']} {u['title']}: {len(res['reflections'])} reflections, ${cost:.3f} "
                  f"(total ${usage['cost']:.2f}) {res.get('note', '')}", flush=True)

    with concurrent.futures.ThreadPoolExecutor(workers) as ex:
        for fut in concurrent.futures.as_completed([ex.submit(one, u) for u in todo]):
            try:
                fut.result()
            except RuntimeError as e:
                print("stopped:", e, flush=True)
                if "402" in str(e) or "401" in str(e):
                    ex.shutdown(cancel_futures=True)
                    sys.exit(1)


# ---------- pack ----------

def link(a, b, label):
    return "[[%d-%d|%s]]" % (a, b, label.replace("]", ")").replace("|", "/"))


def short_url(url):
    return re.sub(r"^https?://(www\.)?", "", url).rstrip("/")


def source_text(x):
    if x.get("site"):
        return f"{x['author']}, “{x['work']}” ({x['site']}, {short_url(x['url'])})"
    when = x.get("date") or ("on " + x["on"] if x.get("on") else "")
    return f"{x['author']}, {x['work']}" + (f" ({when})" if when else "")


def body_of(r, others, names, also):
    a, b = r["start"], r["end"]
    k = r["key_verse"]
    read = f"Read: {link(a, b, ref_text(a, b, names))}"
    if k and a <= k[0] <= b and not (k[0] == a and k[1] == b):
        read += f" · key verse {link(k[0], k[1], ref_text(k[0], k[1], names))}"
    parts = [r["title"] + "\n" + read]
    if also:
        parts[0] += "\nAlso in " + "; ".join(link(x, y, ref_text(x, y, names)) for x, y in also)
    parts += [p["text"] for p in r["reflect"]]
    parts.append("Ask yourself\n" + "\n".join("• " + q for q in r["questions"]))
    parts.append("Pray\n" + r["prayer"])
    parts.append("Written by AI from: " + "; ".join(source_text(x) for x in r["sources"].values()) + ".")
    if others:
        parts.append("See also: " + "; ".join(link(o["start"], o["end"], f"{o['title']} · {ref_text(o['start'], o['end'], names)}")
                                              for o in others))
    return "\n\n".join(parts)


def cmd_pack(d, target):
    import lzma
    _, names = book_ids()
    rows, count, units = [], 0, 0
    for f in sorted(os.listdir(os.path.join(d, "out"))):
        u = json.load(open(os.path.join(d, "out", f)))
        refl = u.get("reflections") or []
        units += bool(refl)
        for r in refl:
            others = [o for o in refl if o is not r]
            count += 1
            ranges = [tuple(x) for x in r["ranges"]]
            for a, b in ranges:
                also = [x for x in ranges if x != (a, b)]
                if r["kind"] == "passage" and len(ranges) > 1:
                    rr = dict(r, start=a, end=b, key_verse=r["key_verse"] if a <= r["key_verse"][0] <= b else None)
                else:
                    rr = r
                rows.append((a, b, body_of(rr, others, names, also)))
    tmp = target + ".tmp.db"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.execute("CREATE TABLE entries(start INTEGER NOT NULL, end INTEGER NOT NULL, body TEXT NOT NULL)")
    db.execute("CREATE INDEX entries_start ON entries(start)")
    # Within a chapter the app lists them by start; a saying inside a passage comes after the passage's start.
    db.executemany("INSERT INTO entries VALUES (?, ?, ?)", sorted(rows, key=lambda x: (x[0], -x[1])))
    db.commit()
    db.execute("VACUUM")
    db.close()
    with open(tmp, "rb") as f, lzma.open(target, "wb", preset=9 | lzma.PRESET_EXTREME) as out:
        out.write(f.read())
    os.remove(tmp)
    print(f"{count} reflections on {units} passages ({len(rows)} rows) -> {target} ({os.path.getsize(target) / 1e6:.2f} MB)")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "catalog":
        cmd_catalog(args[0])
    elif cmd == "passages":
        cmd_passages(args[0])
    elif cmd == "pack":
        cmd_pack(args[0], args[1])
    elif cmd == "write":
        cmd_write(args[0], args[1:] or ["all"])
    else:
        sys.exit(__doc__)
