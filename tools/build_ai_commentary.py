"""Builds the AI commentary: a short, plain-English note on every verse, grounded in sources.

Each chapter (or part) takes two steps:
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

Chapters longer than 40 verses are done in parts of about 30 verses.

Usage:
  ANTHROPIC_API_KEY=... python3 build_ai_commentary.py batch <work> <out> all|"Acts 2"|Ruth ... [--wait|--collect]
      Sends the parts through the Message Batches API (half price), collects finished batches and sends
      the next step. Run it again (or with --wait) until every part is done; it carries on where it left off.
  OPENROUTER_API_KEY=... python3 build_ai_commentary.py openrouter <work> <out> all|"Acts 2"|Ruth ...
      The same two steps through OpenRouter with cheaper models (DeepSeek V4 Pro researches, Gemini 3.8 Flash
      writes), twelve parts at a time. Carries on where any earlier run left off.
  ANTHROPIC_API_KEY=... python3 build_ai_commentary.py chapter <work> <out> "Acts 2" ...
      Runs parts one at a time, straight away (full price), e.g. to redo ones the batch run couldn't finish.
  python3 build_ai_commentary.py pack <out> app/src/main/assets/commentaries/ai.db.xz
      Turns the finished notes into the commentary database the app reads: entries(start, end, body).

<work> holds the bundled commentaries unpacked. <out> holds the finished notes
(<book>-<chapter>-<first verse>.json), the research notes (research/), batches.json, usage.json and log.txt.
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
MODEL = "claude-sonnet-5-5"  # Claude Opus 5.5 wrote the first parts; Sonnet 5.5 (half the price) the rest

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
- Warm and clear, never preachy. Don't start with "This verse".
- First say what happens or what is said, and what it means; at most one short sentence of application."""

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

Search first: the fetch tool can only open pages that came up in your searches. Then read the best pages.
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

# ---------- Bible text, parts and bundled commentaries ----------

PART = 30  # chapters longer than 40 verses are done in parts of about this many verses


def bible(version):
    return sqlite3.connect(os.path.join(ASSETS, "bibles", f"{version}.db"))


def book_names():
    db = bible("kjv")
    ids, names = {}, {}
    for i, name, osis in db.execute("SELECT id, name, osis FROM books"):
        ids[name.lower()] = ids[osis.lower()] = i
        names[i] = name
    return ids, names


def all_parts():
    """Every part of the Bible as (book, chapter, first verse, last verse), in KJV versification (as the app)."""
    parts = []
    for book, ch, n in bible("kjv").execute("SELECT book, chapter, MAX(verse) FROM verses GROUP BY book, chapter ORDER BY book, chapter"):
        if n <= 40:
            parts.append((book, ch, 1, n))
        else:
            k = -(-n // PART)
            bounds = [round(i * n / k) for i in range(k + 1)]
            parts += [(book, ch, bounds[i] + 1, bounds[i + 1]) for i in range(k)]
    return parts


def part_id(p):
    return f"{p[0]:02d}-{p[1]:03d}-{p[2]:03d}"


def select(refs):
    """The parts for refs like "Acts 2", "Ruth" or "all"."""
    ids, _ = book_names()
    parts = all_parts()
    if refs == ["all"]:
        return parts
    out = []
    for ref in refs:
        m = re.fullmatch(r"\s*(.+?)(?:\s+(\d+))?\s*", ref)
        book = ids.get(m.group(1).lower())
        if not book:
            sys.exit(f"Can't read {ref!r}: write it like \"Acts 2\" or \"Ruth\"")
        out += [p for p in parts if p[0] == book and (m.group(2) is None or p[1] == int(m.group(2)))]
    return out


def ref_of(p, names):
    book, ch, a, b = p
    n = bible("kjv").execute("SELECT MAX(verse) FROM verses WHERE book = ? AND chapter = ?", (book, ch)).fetchone()[0]
    return f"{names[book]} {ch}" if (a, b) == (1, n) else f"{names[book]} {ch}:{a}-{b}"


def text_of(p):
    """The verses in the BSB; verses the BSB leaves out (as later additions) come from the KJV, marked."""
    book, ch, a, b = p
    bsb = dict(bible("bsb").execute("SELECT verse, text FROM verses WHERE book = ? AND chapter = ? AND verse BETWEEN ? AND ?", (book, ch, a, b)))
    kjv = bible("kjv").execute("SELECT verse, text FROM verses WHERE book = ? AND chapter = ? AND verse BETWEEN ? AND ?", (book, ch, a, b)).fetchall()
    return "\n".join(f"{v} {bsb[v]}" if v in bsb else f"{v} [KJV only; not in the oldest manuscripts] {t}" for v, t in kjv)


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


def part_notes(dbs, p):
    """{commentary id: [(start verse, end verse, text)]} for the notes touching the part (verse 0: introduction)."""
    book, ch, a, b = p
    base = book * 1_000_000 + ch * 1000
    lo, hi = base + (0 if a == 1 else a), base + b
    out = {}
    for cid, (db, table) in dbs.items():
        rows = db.execute(f"SELECT start, end, body FROM {table} WHERE start <= ? AND end >= ? ORDER BY start", (hi, lo)).fetchall()
        notes = [(s - base if s >= base else 0, e - base if e <= base + 999 else 999, plain(body)) for s, e, body in rows]
        if notes:
            out[cid] = notes
    return out


def covered(notes, cid, verse, end):
    """Whether bundled commentary [cid] has a note on any of the verses [verse]..[end]."""
    return any(s <= end and e >= verse for s, e, _ in notes.get(cid, []))

# ---------- requests ----------

TOOLS = [
    # The basic tool versions: the newer ones run a hidden code-execution step that ran out of calls in large batches.
    {"type": "web_search_20250305", "name": "web_search", "allowed_domains": DOMAINS, "max_uses": 5},
    {"type": "web_fetch_20250910", "name": "web_fetch", "allowed_domains": DOMAINS, "max_uses": 4, "max_content_tokens": 6000},
]


def research_params(messages):
    return dict(model=MODEL, max_tokens=32000, system=RESEARCH_SYSTEM, tools=TOOLS, messages=messages,
                output_config={"effort": "medium"}, cache_control={"type": "ephemeral"})


def research_start(p, names):
    return [{"role": "user", "content": RESEARCH_TASK.format(ref=ref_of(p, names), text=text_of(p))}]


def write_params(p, names, bundled, research_notes):
    ref = ref_of(p, names)
    parts = [f"# {ref} (BSB)\n\n{text_of(p)}\n\n# Bundled commentaries"]
    for cid, notes in bundled.items():
        parts.append(f"\n## {BUNDLED[cid]}\n")
        for s, e, body in notes:
            span = "introduction" if s == 0 else f"v. {s}" if s == e else f"v. {s}-{e}"
            parts.append(f"[{span}]\n{body}\n")
    parts.append(f"\n# Research notes from trusted websites\n\n{research_notes or '(none found)'}")
    return dict(model=MODEL, max_tokens=64000, system=WRITE_SYSTEM,
                messages=[{"role": "user", "content": [
                    {"type": "text", "text": "\n".join(parts)},
                    {"type": "text", "text": f"Write the notes for every verse of {ref} (verses {p[2]}-{p[3]}), and only those verses."}]}],
                output_config={"effort": "high", "format": {"type": "json_schema", "schema": SCHEMA}})


def read_research(content):
    """(notes text, URLs of every page found) from a research reply's content blocks (dicts)."""
    urls, notes = set(), []
    for b in content:
        t = b.get("type")
        if t == "web_search_tool_result" and isinstance(b.get("content"), list):
            urls.update(r["url"] for r in b["content"] if r.get("url"))
        elif t == "web_fetch_tool_result" and isinstance(b.get("content"), dict) and b["content"].get("url"):
            urls.add(b["content"]["url"])
        elif t == "text":
            notes.append(b["text"])
            for cit in b.get("citations") or []:
                if cit.get("url"):
                    urls.add(cit["url"])
    return "".join(notes).strip(), urls


def dump(content):
    return [b.model_dump(mode="json", exclude_none=True) for b in content]

# ---------- usage ----------

PRICES = {  # $ per million tokens
    "claude-opus-5-5": {"input": 4.0, "cache_write": 5.0, "output": 20.0, "cache_read": 0.2},
    "claude-sonnet-5-5": {"input": 2.0, "cache_write": 2.5, "output": 10.0, "cache_read": 0.2},
}
PRICE = PRICES[MODEL]


def add_usage(total, usage, batch):
    u = usage.model_dump() if hasattr(usage, "model_dump") else usage
    f = 0.5 if batch else 1.0
    total["input"] = total.get("input", 0) + u.get("input_tokens", 0)
    total["output"] = total.get("output", 0) + u.get("output_tokens", 0)
    total["cache_read"] = total.get("cache_read", 0) + (u.get("cache_read_input_tokens") or 0)
    total["cache_write"] = total.get("cache_write", 0) + (u.get("cache_creation_input_tokens") or 0)
    stu = u.get("server_tool_use") or {}
    total["searches"] = total.get("searches", 0) + (stu.get("web_search_requests") or 0)
    total["fetches"] = total.get("fetches", 0) + (stu.get("web_fetch_requests") or 0)
    total["dollars"] = total.get("dollars", 0) + f * sum((u.get(k) or 0) * PRICE[n] for k, n in (
        ("input_tokens", "input"), ("cache_creation_input_tokens", "cache_write"), ("output_tokens", "output"),
        ("cache_read_input_tokens", "cache_read"))) / 1e6 + (stu.get("web_search_requests") or 0) * 0.01

# ---------- checking ----------

NAME_TO_ID = {v.lower(): k for k, v in BUNDLED.items()}


def check_sources(sources, verse, end, bundled, urls, dropped):
    """The sources that check out; the others are recorded in [dropped]."""
    good, known = [], {u.rstrip("/") for u in urls}
    for s in sources:
        s = s.strip()
        if s.startswith("http"):
            ok = s.rstrip("/") in known
        else:
            cid = NAME_TO_ID.get(s.lower())
            ok = cid is not None and covered(bundled, cid, verse, end)
        (good if ok else dropped).append(s)
    return good


def check(notes, p, bundled, urls):
    """Keeps the notes inside the part and drops unsupported points. Returns (notes, problems)."""
    problems, out = [], []
    for n in sorted(notes, key=lambda n: n["verse"]):
        v, e = n["verse"], max(n["end"], n["verse"])
        if v < p[2] or e > p[3]:
            problems.append(f"v. {v}-{e}: outside verses {p[2]}-{p[3]}; dropped")
            continue
        dropped = []
        n["end"] = e
        n["meaning_sources"] = check_sources(n["meaning_sources"], v, e, bundled, urls, dropped)
        if not n["meaning_sources"]:
            problems.append(f"v. {v}: main note has no checkable source ({dropped}); kept but flagged")
            n["unchecked"] = True
        for key in ("differ", "other"):
            kept = []
            for q in n[key]:
                q["sources"] = check_sources(q["sources"], v, e, bundled, urls, dropped)
                if q["sources"]:
                    kept.append(q)
                else:
                    problems.append(f"v. {v}: dropped {key} point ({q.get('tradition') or q.get('label')}): no checkable source")
            n[key] = kept
        if dropped:
            problems.append(f"v. {v}: removed sources {dropped}")
        out.append(n)
    return out, problems


def missing(notes, p):
    have = {x for n in notes for x in range(n["verse"], n["end"] + 1)}
    return [v for v in range(p[2], p[3] + 1) if v not in have]

# ---------- state ----------
#
# <out>/research/<part>.json         {"notes", "urls"}, or while paused {"messages", "round"}
# <out>/<part>.json                  the finished notes
# <out>/batches.json                 every batch sent, so results can be fetched again (kept 29 days)
# <out>/usage.json                   tokens, searches and dollars so far


class State:
    def __init__(self, work, out):
        self.work, self.out = work, out
        self.rdir = os.path.join(out, "research")  # in <out> so it is kept with the notes
        os.makedirs(self.rdir, exist_ok=True)
        os.makedirs(out, exist_ok=True)
        self.batches = self._load("batches.json", [])
        self.usage = self._load("usage.json", {})

    def _load(self, name, default):
        path = os.path.join(self.out, name)
        return json.load(open(path)) if os.path.exists(path) else default

    def save(self):
        for name, data in (("batches.json", self.batches), ("usage.json", self.usage)):
            with open(os.path.join(self.out, name) + ".tmp", "w") as f:
                json.dump(data, f, indent=1)
            os.replace(os.path.join(self.out, name) + ".tmp", os.path.join(self.out, name))

    def research(self, pid):
        path = os.path.join(self.rdir, pid + ".json")
        return json.load(open(path)) if os.path.exists(path) else None

    def set_research(self, pid, data):
        with open(os.path.join(self.rdir, pid + ".json"), "w") as f:
            json.dump(data, f, ensure_ascii=False)

    def drop_research(self, pid):
        path = os.path.join(self.rdir, pid + ".json")
        if os.path.exists(path):
            os.remove(path)

    def done(self, pid):
        return os.path.exists(os.path.join(self.out, pid + ".json"))

    def finish(self, p, names, notes, problems):
        data = {"book": p[0], "chapter": p[1], "first": p[2], "last": p[3], "ref": ref_of(p, names),
                "notes": notes, "problems": problems}
        with open(os.path.join(self.out, part_id(p) + ".json"), "w") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)

    def open_ids(self):
        """custom_ids in batches not yet collected."""
        return {cid for b in self.batches if not b.get("collected") for cid in b["ids"]}

    def tries(self, cid):
        return sum(cid in b["ids"] for b in self.batches)

# ---------- batch run ----------


def submit(c, st, kind, requests):
    from anthropic.types.messages.batch_create_params import Request
    for i in range(0, len(requests), 200):  # smaller batches: big ones hit the web search rate limit
        chunk = requests[i:i + 200]
        batch = c.messages.batches.create(requests=[Request(custom_id=cid, params=params) for cid, params in chunk])
        st.batches.append({"id": batch.id, "kind": kind, "ids": [cid for cid, _ in chunk], "created": time.strftime("%Y-%m-%d %H:%M")})
        st.save()
        print(f"sent {kind} batch {batch.id}: {len(chunk)} requests", flush=True)


def collect(c, st, parts, names, dbs, log):
    by_id = {part_id(p): p for p in parts}
    for b in st.batches:
        if b.get("collected"):
            continue
        info = c.messages.batches.retrieve(b["id"])
        if info.processing_status != "ended":
            continue
        for r in c.messages.batches.results(b["id"]):
            kind, pid = r.custom_id.split("_", 1)
            p = by_id.get(pid)
            if p is None:
                continue
            if r.result.type != "succeeded":
                log(f"{pid} {kind}: {r.result.type} {getattr(r.result, 'error', '')}")
                continue
            msg = r.result.message
            add_usage(st.usage, msg.usage, batch=True)
            if msg.stop_reason == "refusal":
                log(f"{pid} {kind}: declined ({msg.stop_details})")
                continue
            if kind == "r":
                prev = st.research(pid) or {"messages": research_start(p, names), "round": 0}
                if msg.stop_reason == "pause_turn":
                    st.set_research(pid, {"messages": prev["messages"] + [{"role": "assistant", "content": dump(msg.content)}],
                                          "round": prev["round"] + 1, "content": prev.get("content", []) + dump(msg.content)})
                else:
                    notes, urls = read_research(prev.get("content", []) + dump(msg.content))
                    if urls:
                        st.set_research(pid, {"notes": notes, "urls": sorted(urls)})
                    else:  # no page was read (a tool failure): research it again
                        st.drop_research(pid)
                        log(f"{pid} research: no pages read")
            else:
                if msg.stop_reason == "max_tokens":
                    log(f"{pid} write: ran out of room")
                    continue
                res = st.research(pid)
                if not res or not res.get("urls"):
                    continue
                bundled = part_notes(dbs, p)
                notes = json.loads(next(x.text for x in msg.content if x.type == "text"))["notes"]
                notes, problems = check(notes, p, bundled, set(res["urls"]))
                gaps = missing(notes, p)
                if gaps:
                    problems.append(f"no note for verses {gaps}")
                st.finish(p, names, notes, problems)
        b["collected"] = True
        st.save()


def cmd_batch(work, out, refs, wait, collect_only=False):
    import anthropic
    c = anthropic.Anthropic(max_retries=6)
    _, names = book_names()
    dbs = commentary_dbs(work)
    parts = select(refs)
    st = State(work, out)
    logf = open(os.path.join(out, "log.txt"), "a")

    def log(line):
        print(line, flush=True)
        logf.write(time.strftime("%Y-%m-%d %H:%M ") + line + "\n")
        logf.flush()

    while True:
        collect(c, st, parts, names, dbs, log)
        if collect_only:
            log(f"{sum(st.done(part_id(p)) for p in parts)}/{len(parts)} parts done; collected only")
            return
        busy = st.open_ids()
        research, write, stuck = [], [], []
        for p in parts:
            pid = part_id(p)
            if st.done(pid):
                continue
            res = st.research(pid)
            cid = ("w_" if res and "notes" in res else "r_") + pid
            if cid in busy:
                continue
            if st.tries(cid) - (res or {}).get("round", 0) >= 5 or (res or {}).get("round", 0) >= 6:
                stuck.append(pid)
            elif res and "notes" in res:
                write.append((cid, write_params(p, names, part_notes(dbs, p), res["notes"])))
            else:
                research.append((cid, research_params(res["messages"] if res else research_start(p, names))))
        if research:
            submit(c, st, "research", research)
        if write:
            submit(c, st, "write", write)
        done = sum(st.done(part_id(p)) for p in parts)
        log(f"{done}/{len(parts)} parts done, {len(st.open_ids())} requests waiting, {len(stuck)} stuck; "
            f"cost so far ${st.usage.get('dollars', 0):.2f}")
        if not wait or not st.open_ids():
            if stuck:
                log(f"stuck (run them with the chapter command): {' '.join(stuck)}")
            return
        time.sleep(120)

# ---------- through OpenRouter (cheaper models) ----------
#
# Research with DeepSeek V4 Pro and OpenRouter's web search and fetch tools (limited to the same trusted
# sites); writing with Gemini 3.8 Flash, which keeps to the citation rules. Same state files as the batch run.

OR_RESEARCH = "deepseek/deepseek-v4-pro"
OR_WRITE = "google/gemini-3.8-flash"


def openrouter(body):
    import urllib.error
    import urllib.request
    for attempt in range(5):
        req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
                                              "Content-Type": "application/json"})
        try:
            r = json.load(urllib.request.urlopen(req, timeout=1800))
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


def or_research(p, names):
    r = openrouter({
        "model": OR_RESEARCH, "max_tokens": 20000, "usage": {"include": True},
        "messages": [{"role": "system", "content": RESEARCH_SYSTEM}] + research_start(p, names),
        "tools": [{"type": "openrouter:web_search", "parameters": {"engine": "exa", "allowed_domains": DOMAINS, "max_uses": 5, "max_results": 6}},
                  {"type": "openrouter:web_fetch", "parameters": {"allowed_domains": DOMAINS, "max_uses": 4, "max_content_tokens": 6000}}],
    })
    m = r["choices"][0]["message"]
    urls = {a["url_citation"]["url"] for a in m.get("annotations") or [] if a.get("type") == "url_citation"}
    return (m.get("content") or "").strip(), urls, r["usage"].get("cost", 0)


def or_write(p, names, bundled, research_notes):
    wp = write_params(p, names, bundled, research_notes)
    r = openrouter({
        "model": OR_WRITE, "max_tokens": 60000, "usage": {"include": True},
        "messages": [{"role": "system", "content": wp["system"]},
                     {"role": "user", "content": "\n\n".join(x["text"] for x in wp["messages"][0]["content"])}],
        "response_format": {"type": "json_schema", "json_schema": {"name": "notes", "strict": True, "schema": SCHEMA}},
    })
    return json.loads(r["choices"][0]["message"]["content"])["notes"], r["usage"].get("cost", 0)


def cmd_openrouter(work, out, refs, workers=12):
    import concurrent.futures
    import threading
    _, names = book_names()
    st = State(work, out)
    lock = threading.Lock()
    logf = open(os.path.join(out, "log.txt"), "a")
    todo = [p for p in select(refs) if not st.done(part_id(p))]
    print(f"{len(todo)} parts to do", flush=True)

    def log(line):
        with lock:
            print(line, flush=True)
            logf.write(time.strftime("%Y-%m-%d %H:%M ") + line + "\n")
            logf.flush()

    def spend(dollars):
        with lock:
            st.usage["dollars"] = st.usage.get("dollars", 0) + dollars
            st.usage["openrouter_dollars"] = st.usage.get("openrouter_dollars", 0) + dollars
            st.save()

    def one(p):
        pid = part_id(p)
        dbs = commentary_dbs(work)  # sqlite connections are per thread
        try:
            res = st.research(pid)
            for _ in range(3):
                if res and res.get("urls"):
                    break
                notes, urls, cost = or_research(p, names)
                spend(cost)
                res = {"notes": notes, "urls": sorted(urls), "model": OR_RESEARCH}
                if urls:
                    st.set_research(pid, res)
            if not res or not res.get("urls"):
                return log(f"{pid}: research read no pages; skipped")
            bundled = part_notes(dbs, p)
            for _ in range(2):
                notes, cost = or_write(p, names, bundled, res["notes"])
                spend(cost)
                notes, problems = check(notes, p, bundled, set(res["urls"]))
                gaps = missing(notes, p)
                if not gaps:
                    break
            if gaps:
                problems.append(f"no note for verses {gaps}")
            st.finish(p, names, notes, problems)
            with lock:
                path = os.path.join(out, pid + ".json")
                data = json.load(open(path))
                data["model"] = OR_WRITE
                json.dump(data, open(path, "w"), indent=1, ensure_ascii=False)
            log(f"{pid}: {len(notes)} notes, {len(problems)} problems; ${st.usage['dollars']:.2f} so far")
        except Exception as e:
            log(f"{pid}: failed: {e}")
            if "OpenRouter 402" in str(e) or "OpenRouter 401" in str(e):
                raise

    with concurrent.futures.ThreadPoolExecutor(workers) as ex:
        for f in concurrent.futures.as_completed([ex.submit(one, p) for p in todo]):
            if f.exception():
                ex.shutdown(cancel_futures=True)
                raise f.exception()
    log(f"{sum(st.done(part_id(p)) for p in select(refs))}/{len(select(refs))} parts done")

# ---------- one part at a time (for retries) ----------


def cmd_chapter(work, out, refs):
    """Runs parts directly (not batched), with the server-side fallback for declined requests."""
    import anthropic
    c = anthropic.Anthropic(max_retries=4, timeout=1800)
    _, names = book_names()
    dbs = commentary_dbs(work)
    st = State(work, out)

    def call(params):
        with c.beta.messages.stream(betas=["server-side-fallback-2026-07-01"], extra_body={"fallbacks": "default"}, **params) as s:
            msg = s.get_final_message()
        add_usage(st.usage, msg.usage, batch=False)
        st.save()
        if msg.stop_reason == "refusal":
            raise RuntimeError(f"Declined: {msg.stop_details}")
        return msg

    for p in select(refs):
        pid, t0 = part_id(p), time.time()
        res = st.research(pid)
        if not res or "notes" not in res:
            messages, content = research_start(p, names), []
            for _ in range(6):
                msg = call(research_params(messages))
                content += dump(msg.content)
                if msg.stop_reason != "pause_turn":
                    break
                messages = messages + [{"role": "assistant", "content": dump(msg.content)}]
            notes_md, urls = read_research(content)
            res = {"notes": notes_md, "urls": sorted(urls)}
            st.set_research(pid, res)
        bundled = part_notes(dbs, p)
        msg = call(write_params(p, names, bundled, res["notes"]))
        notes = json.loads(next(b.text for b in msg.content if b.type == "text"))["notes"]
        notes, problems = check(notes, p, bundled, set(res["urls"]))
        gaps = missing(notes, p)
        if gaps:
            problems.append(f"no note for verses {gaps}")
        st.finish(p, names, notes, problems)
        print(f"{ref_of(p, names)}: {len(notes)} notes, {len(problems)} problems, {time.time() - t0:.0f}s; "
              f"cost so far ${st.usage['dollars']:.2f}", flush=True)

# ---------- packing ----------


def body(n):
    """A note as the plain text the app shows."""
    def cite(sources):
        return "; ".join(s if not s.startswith("http") else re.sub(r"^https?://(www\.)?([^/]+).*", r"\2", s) for s in sources)
    parts = [n["meaning"] + (f"\n(Sources: {cite(n['meaning_sources'])})" if n["meaning_sources"] else "")]
    if n["differ"]:
        parts.append("Where Christians differ:\n" + "\n".join(f"• {q['tradition']}: {q['view']} ({cite(q['sources'])})" for q in n["differ"]))
    if n["other"]:
        parts.append("Other views:\n" + "\n".join(f"• {q['label']}: {q['view']} ({cite(q['sources'])})" for q in n["other"]))
    return "\n\n".join(parts)


def cmd_pack(out, target):
    path = target[:-3] if target.endswith(".xz") else target
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE entries(start INTEGER NOT NULL, end INTEGER NOT NULL, body TEXT NOT NULL)")
    for name in sorted(os.listdir(out)):
        if not re.fullmatch(r"\d\d-\d\d\d-\d\d\d\.json", name):
            continue
        data = json.load(open(os.path.join(out, name)))
        base = data["book"] * 1_000_000 + data["chapter"] * 1000
        for n in data["notes"]:
            db.execute("INSERT INTO entries VALUES (?, ?, ?)", (base + n["verse"], base + n["end"], body(n)))
    db.execute("CREATE INDEX entries_start ON entries(start)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    if target.endswith(".xz"):
        with open(path, "rb") as src, lzma.open(target, "wb", preset=9 | lzma.PRESET_EXTREME) as dst:
            dst.write(src.read())
        os.remove(path)


if __name__ == "__main__":
    args = sys.argv[1:]
    if len(args) >= 4 and args[0] == "batch":
        cmd_batch(args[1], args[2], [a for a in args[3:] if not a.startswith("--")], "--wait" in args, "--collect" in args)
    elif len(args) >= 4 and args[0] == "openrouter":
        cmd_openrouter(args[1], args[2], args[3:])
    elif len(args) >= 4 and args[0] == "chapter":
        cmd_chapter(args[1], args[2], args[3:])
    elif len(args) == 3 and args[0] == "pack":
        cmd_pack(args[1], args[2])
    else:
        sys.exit(__doc__)
