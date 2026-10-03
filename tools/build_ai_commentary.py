"""Builds the AI commentary: a short, plain-English note on every verse, grounded in sources.

Each chapter takes two steps:
  1. research: Claude searches the trusted sites below (and only those) for what pastors and teachers
     say about the chapter, where Protestant traditions differ, and other views. It writes notes
     that name the page for every point.
  2. write: Claude writes the verse notes from the bundled commentaries and the research notes,
     as JSON. The script then drops any point whose source it can't check: a bundled commentary
     must have a note on that verse, and a web page must have come up in step 1.

Each note has three parts (see CLAUDE.md):
  meaning  the traditional reading, for lay readers (always);
  differ   where Protestant traditions differ, one line each, labelled (only where they do);
  other    other views: modern scholarship, Catholic and Orthodox, popular modern teaching (only
           where one is well known).

Usage:
  ANTHROPIC_API_KEY=... python3 build_ai_commentary.py chapter <work folder> <out folder> "Acts 2" ...
  python3 build_ai_commentary.py pack <out folder> <assets/commentaries/ai.db.xz>

The work folder holds the bundled commentaries unpacked (made the first time). The out folder gets
<book>-<chapter>.json (the notes, with sources) and <book>-<chapter>.research.md for each chapter.
pack turns the JSON files into the commentary database the app reads: entries(start, end, body).
"""
import json
import lzma
import os
import re
import sqlite3
import sys
import time

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
MODEL = "claude-opus-5-5"

# Bundled commentaries: id -> name as cited.
BUNDLED = {
    "mhcc": "Matthew Henry (Concise)", "mhc": "Matthew Henry", "jfb": "Jamieson-Fausset-Brown",
    "wesley": "Wesley", "geneva": "Geneva Notes", "barnes": "Barnes", "clarke": "Adam Clarke",
    "kd": "Keil & Delitzsch", "rwp": "Robertson", "calvin": "Calvin", "tdavid": "Spurgeon (Treasury of David)",
}

# The only sites the research step may search or read, by group.
SITES = {
    "Reformed and Presbyterian": ["ligonier.org", "monergism.com", "thegospelcoalition.org"],
    "Baptist": ["spurgeon.org", "desiringgod.org", "gty.org"],
    "Methodist, Wesleyan and Holiness": ["wesley.nnu.edu", "seedbed.com"],
    "Pentecostal and charismatic": ["enduringword.com", "pneumareview.com", "samstorms.org", "ag.org"],
    "Lutheran": ["lcms.org", "1517.org"],
    "Older commentaries (Gill, Ellicott, Pulpit, Ryle and others)": ["biblehub.com", "studylight.org", "blueletterbible.org", "ccel.org"],
    "Evangelical": ["bible.org", "gotquestions.org", "preceptaustin.org"],
    "Other views: modern scholarship": ["bibleodyssey.org", "netbible.org"],
    "Other views: Catholic and Orthodox": ["newadvent.org", "catholic.com", "oca.org"],
}
DOMAINS = [d for ds in SITES.values() for d in ds]

# Labels for "Where Christians differ": names lay readers know.
TRADITIONS = ["Baptist", "Methodist", "Pentecostal", "Charismatic", "Reformed and Presbyterian", "Lutheran", "Anglican",
              "Holiness", "Many evangelicals", "Some evangelicals"]

STYLE = """How to write (the readers are ordinary church members, not scholars):
- Plain, everyday English. Short sentences. About the reading level of a 12-year-old.
- Explain any church word (like "atonement" or "justified") in a few words the first time.
- No Hebrew or Greek unless it really helps; then give the meaning simply.
- Warm and clear, never preachy. Don't start with "This verse"."""

RESEARCH_SYSTEM = f"""You research Bible chapters for a verse-by-verse commentary for lay readers.
You may only use these sites (the search tool is limited to them):
{chr(10).join(f"- {group}: {', '.join(ds)}" for group, ds in SITES.items())}

Rules:
- Only report what the pages you read actually say. Never fill a gap from your own knowledge.
- Give the page URL for every point.
- Summarise in your own words; no long quotes."""

RESEARCH_TASK = """Research {ref} for a verse-by-verse commentary. The text (BSB):

{text}

Find and write notes on:
1. What trusted pastors and teachers say each verse means (the traditional reading). Read verse-by-verse
   sources such as enduringword.com, biblehub.com or studylight.org (Gill, Ellicott, Pulpit Commentary),
   preceptaustin.org.
2. Verses where Protestant traditions really differ (Baptist, Methodist, Pentecostal, Reformed, Lutheran,
   Anglican...): what each says, from that tradition's own teachers.
3. Other views, only where well known: modern scholarship (bibleodyssey.org, netbible.org notes),
   Catholic and Orthodox readings (newadvent.org, catholic.com, oca.org), and debated popular modern
   teaching (such as prosperity, word of faith or hyper-grace), described from what the sites say.

Write the notes verse by verse ("v. 4: ..."), each point followed by its URL. Leave out what you couldn't find."""

WRITE_SYSTEM = f"""You write a verse-by-verse Bible commentary for lay readers, grounded only in the sources given.

{STYLE}

Each verse gets:
- meaning: 2-4 short sentences giving the traditional reading, as the sources give it. Every verse gets one.
- meaning_sources: the sources it rests on (at least one).
- differ: only where Protestant traditions really differ on this verse; one short line per tradition, in
  that tradition's own terms, fairly, without saying who is right. Usually empty.
- other: only where the sources give a well-known other view (modern scholarship, Catholic or Orthodox,
  popular modern teaching); one or two short sentences each, fairly, without a verdict. Usually empty.
- Label views by the church tradition readers know, never by technical terms (not "cessationist",
  "continuationist", "paedobaptist") or by a teacher's name. If needed, explain the view in plain words:
  "Many evangelicals believe the gift of tongues ended with the apostles".

Sources: name a bundled commentary exactly as given in its heading (e.g. "Matthew Henry"), or give the
URL of a web page from the research notes. Only cite a source that says what you wrote. Never use your
own knowledge for anything a source doesn't support. The main note always gives the traditional view;
never mix other views into it.
You may combine 2-3 verses into one note when they form one sentence or list (set "end")."""

SCHEMA = {
    "type": "object",
    "properties": {"notes": {"type": "array", "items": {
        "type": "object",
        "properties": {
            "verse": {"type": "integer"},
            "end": {"type": "integer"},
            "meaning": {"type": "string"},
            "meaning_sources": {"type": "array", "items": {"type": "string"}},
            "differ": {"type": "array", "items": {
                "type": "object",
                "properties": {"tradition": {"type": "string", "enum": TRADITIONS}, "view": {"type": "string"},
                               "sources": {"type": "array", "items": {"type": "string"}}},
                "required": ["tradition", "view", "sources"], "additionalProperties": False}},
            "other": {"type": "array", "items": {
                "type": "object",
                "properties": {"label": {"type": "string", "enum": ["Modern scholarship", "Catholic", "Orthodox", "Catholic and Orthodox", "Popular modern teaching"]},
                               "view": {"type": "string"},
                               "sources": {"type": "array", "items": {"type": "string"}}},
                "required": ["label", "view", "sources"], "additionalProperties": False}},
        },
        "required": ["verse", "end", "meaning", "meaning_sources", "differ", "other"],
        "additionalProperties": False}}},
    "required": ["notes"], "additionalProperties": False,
}

# ---------- Bible text and bundled commentaries ----------

def bible(version):
    return sqlite3.connect(os.path.join(ASSETS, "bibles", f"{version}.db"))


def book_ids():
    db = bible("kjv")
    ids = {}
    for i, name, osis in db.execute("SELECT id, name, osis FROM books"):
        ids[name.lower()] = i
        ids[osis.lower()] = i
    names = {i: n for i, n in db.execute("SELECT id, name FROM books")}
    return ids, names


def parse_ref(ref, ids):
    m = re.fullmatch(r"\s*(.+?)\s+(\d+)\s*", ref)
    if not m or m.group(1).lower() not in ids:
        sys.exit(f"Can't read {ref!r}: write it like \"Acts 2\"")
    return ids[m.group(1).lower()], int(m.group(2))


def verses(book, chapter, version="bsb"):
    return bible(version).execute(
        "SELECT verse, text FROM verses WHERE book = ? AND chapter = ? ORDER BY verse", (book, chapter)).fetchall()


def commentary_dbs(work):
    """Opens every bundled commentary, unpacking the packed ones into [work] the first time."""
    os.makedirs(work, exist_ok=True)
    dbs = {"mhcc": (sqlite3.connect(os.path.join(ASSETS, "study", "study.db")), "commentary")}
    for cid in BUNDLED:
        if cid == "mhcc":
            continue
        path = os.path.join(work, f"{cid}.db")
        if not os.path.exists(path):
            with lzma.open(os.path.join(ASSETS, "commentaries", f"{cid}.db.xz")) as src, open(path + ".part", "wb") as dst:
                dst.write(src.read())
            os.rename(path + ".part", path)
        dbs[cid] = (sqlite3.connect(path), "entries")
    return dbs


def plain(body):
    """Commentary text with [[id-id|label]] links reduced to their labels."""
    return re.sub(r"\[\[[^|\]]*\|([^\]]*)\]\]", r"\1", body)


def chapter_notes(dbs, book, chapter):
    """{commentary id: [(start verse, end verse, text)]} for the notes touching the chapter."""
    lo, hi = book * 1_000_000 + chapter * 1000, book * 1_000_000 + chapter * 1000 + 999
    out = {}
    for cid, (db, table) in dbs.items():
        rows = db.execute(f"SELECT start, end, body FROM {table} WHERE start <= ? AND end >= ? ORDER BY start", (hi, lo)).fetchall()
        notes = []
        for s, e, body in rows:
            sv = s % 1000 if s >= lo else 0
            ev = e % 1000 if e <= hi else 999
            notes.append((sv, ev, plain(body)))
        if notes:
            out[cid] = notes
    return out


def covered(notes, cid, verse, end):
    """Whether bundled commentary [cid] has a note on any of the verses [verse]..[end]."""
    return any(s <= end and e >= verse for s, e, _ in notes.get(cid, []))

# ---------- Claude ----------

def client():
    import anthropic
    return anthropic.Anthropic(max_retries=4, timeout=1800)


USAGE = {"input": 0, "output": 0, "cache_read": 0, "searches": 0, "fetches": 0}
PRICE = {"input": 4.0, "output": 20.0, "cache_read": 0.2}  # $ per million tokens (Claude Opus 5.5)


def count(usage):
    USAGE["input"] += usage.input_tokens + (usage.cache_creation_input_tokens or 0)
    USAGE["output"] += usage.output_tokens
    USAGE["cache_read"] += usage.cache_read_input_tokens or 0
    stu = getattr(usage, "server_tool_use", None)
    if stu:
        USAGE["searches"] += getattr(stu, "web_search_requests", 0) or 0
        USAGE["fetches"] += getattr(stu, "web_fetch_requests", 0) or 0


def cost():
    tokens = sum(USAGE[k] * PRICE[k] for k in PRICE) / 1e6
    return tokens + USAGE["searches"] * 0.01


def call(c, **params):
    """One streamed request with refusal fallback; returns the final message."""
    with c.beta.messages.stream(
        model=MODEL, betas=["server-side-fallback-2026-07-01"], extra_body={"fallbacks": "default"}, **params,
    ) as stream:
        msg = stream.get_final_message()
    count(msg.usage)
    if msg.stop_reason == "refusal":
        raise RuntimeError(f"Declined: {msg.stop_details}")
    return msg


def research(c, ref, text):
    """Step 1: research notes, and the URLs of every page that came up in search or was read."""
    tools = [
        {"type": "web_search_20260209", "name": "web_search", "allowed_domains": DOMAINS, "max_uses": 8},
        {"type": "web_fetch_20260209", "name": "web_fetch", "allowed_domains": DOMAINS, "max_uses": 6,
         "max_content_tokens": 8000},
    ]
    messages = [{"role": "user", "content": RESEARCH_TASK.format(ref=ref, text=text)}]
    urls, notes = set(), []
    for _ in range(6):  # continue paused turns
        msg = call(c, max_tokens=32000, system=RESEARCH_SYSTEM, tools=tools, messages=messages,
                   output_config={"effort": "medium"}, cache_control={"type": "ephemeral"})
        for b in msg.content:
            if b.type == "web_search_tool_result" and isinstance(b.content, list):
                urls.update(r.url for r in b.content if getattr(r, "url", None))
            elif b.type == "web_fetch_tool_result" and getattr(b.content, "url", None):
                urls.add(b.content.url)
            elif b.type == "text":
                notes.append(b.text)
                for cit in getattr(b, "citations", None) or []:
                    if getattr(cit, "url", None):
                        urls.add(cit.url)
        if msg.stop_reason != "pause_turn":
            break
        messages.append({"role": "assistant", "content": msg.content})
    return "".join(notes).strip(), urls


def write(c, ref, text, bundled, research_notes):
    """Step 2: the verse notes as JSON."""
    parts = [f"# {ref} (BSB)\n\n{text}\n\n# Bundled commentaries"]
    for cid, notes in bundled.items():
        parts.append(f"\n## {BUNDLED[cid]}\n")
        for s, e, body in notes:
            span = "introduction" if s == 0 else f"v. {s}" if s == e else f"v. {s}-{min(e, 999)}"
            parts.append(f"[{span}]\n{body}\n")
    parts.append(f"\n# Research notes from trusted websites\n\n{research_notes}")
    msg = call(c, max_tokens=64000, system=WRITE_SYSTEM,
               messages=[{"role": "user", "content": [{"type": "text", "text": "\n".join(parts)},
                                                      {"type": "text", "text": f"Write the notes for every verse of {ref}."}]}],
               output_config={"effort": "high", "format": {"type": "json_schema", "schema": SCHEMA}})
    return json.loads(next(b.text for b in msg.content if b.type == "text"))["notes"]

# ---------- checking ----------

NAME_TO_ID = {v.lower(): k for k, v in BUNDLED.items()}


def check_sources(sources, verse, end, bundled, urls, dropped):
    """The sources that check out; the others are recorded in [dropped]."""
    good = []
    for s in sources:
        s = s.strip()
        if s.startswith("http"):
            ok = s.rstrip("/") in {u.rstrip("/") for u in urls}
        else:
            cid = NAME_TO_ID.get(s.lower())
            ok = cid is not None and covered(bundled, cid, verse, end)
        (good if ok else dropped).append(s)
    return good


def check(notes, bundled, urls):
    """Drops unsupported points. Returns (checked notes, list of problems)."""
    problems, out = [], []
    for n in notes:
        v, e = n["verse"], max(n["end"], n["verse"])
        dropped = []
        n["meaning_sources"] = check_sources(n["meaning_sources"], v, e, bundled, urls, dropped)
        if not n["meaning_sources"]:
            problems.append(f"v. {v}: main note has no checkable source ({dropped}); kept but flagged")
            n["unchecked"] = True
        for key in ("differ", "other"):
            kept = []
            for p in n[key]:
                p["sources"] = check_sources(p["sources"], v, e, bundled, urls, dropped)
                if p["sources"]:
                    kept.append(p)
                else:
                    problems.append(f"v. {v}: dropped {key} point ({p.get('tradition') or p.get('label')}): no checkable source")
            n[key] = kept
        if dropped:
            problems.append(f"v. {v}: removed sources {dropped}")
        out.append(n)
    return out, problems

# ---------- commands ----------

def cmd_chapter(work, out, refs):
    ids, names = book_ids()
    dbs = commentary_dbs(work)
    os.makedirs(out, exist_ok=True)
    c = client()
    for ref in refs:
        book, ch = parse_ref(ref, ids)
        ref = f"{names[book]} {ch}"
        text = "\n".join(f"{v} {t}" for v, t in verses(book, ch))
        bundled = chapter_notes(dbs, book, ch)
        t0 = time.time()
        print(f"{ref}: researching...", flush=True)
        notes_md, urls = research(c, ref, text)
        print(f"{ref}: {len(urls)} pages found (research ${cost():.2f}); writing...", flush=True)
        notes = write(c, ref, text, bundled, notes_md)
        notes, problems = check(notes, bundled, urls)
        stem = os.path.join(out, f"{book:02d}-{ch:03d}")
        with open(stem + ".research.md", "w") as f:
            f.write(f"# Research: {ref}\n\n{notes_md}\n\n## Pages found\n\n" + "\n".join(sorted(urls)) + "\n")
        with open(stem + ".json", "w") as f:
            json.dump({"book": book, "chapter": ch, "ref": ref, "notes": notes, "problems": problems}, f, indent=1, ensure_ascii=False)
        missing = sorted({v for v, _ in verses(book, ch)} - {x for n in notes for x in range(n["verse"], max(n["end"], n["verse"]) + 1)})
        print(f"{ref}: {len(notes)} notes, {len(problems)} problems, missing verses {missing or 'none'}, "
              f"{time.time() - t0:.0f}s; running cost ${cost():.2f} {USAGE}", flush=True)


def body(n):
    """A note as the plain text the app shows."""
    def cite(sources):
        return "; ".join(s if not s.startswith("http") else re.sub(r"^https?://(www\.)?([^/]+).*", r"\2", s) for s in sources)
    parts = [f"{n['meaning']}\n(Sources: {cite(n['meaning_sources'])})"]
    if n["differ"]:
        parts.append("Where Christians differ:\n" + "\n".join(f"• {p['tradition']}: {p['view']} ({cite(p['sources'])})" for p in n["differ"]))
    if n["other"]:
        parts.append("Other views:\n" + "\n".join(f"• {p['label']}: {p['view']} ({cite(p['sources'])})" for p in n["other"]))
    return "\n\n".join(parts)


def cmd_pack(out, target):
    path = target[:-3] if target.endswith(".xz") else target
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE entries(start INTEGER NOT NULL, end INTEGER NOT NULL, body TEXT NOT NULL)")
    for name in sorted(os.listdir(out)):
        if not name.endswith(".json"):
            continue
        data = json.load(open(os.path.join(out, name)))
        base = data["book"] * 1_000_000 + data["chapter"] * 1000
        for n in data["notes"]:
            db.execute("INSERT INTO entries VALUES (?, ?, ?)", (base + n["verse"], base + max(n["end"], n["verse"]), body(n)))
    db.execute("CREATE INDEX entries_start ON entries(start)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    if target.endswith(".xz"):
        with open(path, "rb") as src, lzma.open(target, "wb", preset=9 | lzma.PRESET_EXTREME) as dst:
            dst.write(src.read())
        os.remove(path)


if __name__ == "__main__":
    if len(sys.argv) >= 5 and sys.argv[1] == "chapter":
        cmd_chapter(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif len(sys.argv) == 4 and sys.argv[1] == "pack":
        cmd_pack(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
