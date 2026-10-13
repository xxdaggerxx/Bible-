"""Builds the data for smart search (SRCH-10 to SRCH-44): app/src/main/assets/study/search.db.

Smart search needs no AI and no internet: it is built from what the app already ships.

  wordgroup   Each English word form in the KJV, BSB and WEB, with the forms searched along with it
              ("righteous" -> righteous, righteousness, righteously; "pray" -> pray, prayed, prayer,
              praying...): a word and the forms made from it by a plain ending such as -s, -ed, -ing,
              -er, -ness or -ly, never by a longer one, so "test" doesn't bring in "testimony" and
              "pray" doesn't bring in "praise".
  family      The Hebrew and Greek words each English word group translates (TOP-1), worked out from
              how the three tagged versions translate them: a word counts when the group translates
              it at least 3 times and for at least 30% of its uses ("righteous" -> tsaddiq, tsedeq,
              tsedaqah, dikaios, dikaiosune...).
  lemma       Every verse that uses each Hebrew or Greek word, in any of the three versions.
  nave_sub    Nave's subtopics with their references, in order (TOP-6, PPL-1).
  weight      How many cross-reference votes point at each verse (TOP-5), from OpenBible.info.
  notes       The app's own study content, one row per verse range, with a full-text index
              (SRCH-40 to SRCH-42): the Ink & Word Commentary's main notes (without sources or the
              "Where Christians differ" and "Other views" sections), Chapter at a glance, Bible aids,
              Points to Christ and Reflections.

Reads only files in the repository; needs nothing beyond Python 3.

Usage: python3 tools/build_search_db.py
"""
import collections
import lzma
import os
import re
import sqlite3
import sys


ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "study", "search.db")
SCHEMA = 1

VERSIONS = [("KJV", "kjv"), ("BSB", "bsb"), ("WEB", "web")]
# The same word split as StudyRepository.words in the app, so tags line up with words.
WORD = re.compile(r"[\w’']+", re.U)
NT_START = 40_000_000
LINK = re.compile(r"\[\[(\d+)-(\d+)\|[^\]]*\]\]")
# Endings that make another form of the same word; longer ones (-ful, -ly, -imony) make new words.
ENDINGS = ("s", "es", "ed", "er", "ers", "ing", "ings", "eth", "est", "ness", "nesses", "ly")
MIN_SHARE, MIN_USES = 0.3, 3


def words(text):
    return [m.group(0).lower().replace("’", "'") for m in WORD.finditer(text)]


def unpacked(path):
    """A packed commentary database (.db.xz) as a temporary plain file."""
    tmp = os.path.join(ROOT, "tools", ".search_tmp_" + os.path.basename(path)[:-3])
    with lzma.open(path) as src, open(tmp, "wb") as dst:
        dst.write(src.read())
    return tmp


def load_bibles():
    texts = {}
    for code, name in VERSIONS:
        db = sqlite3.connect(os.path.join(ASSETS, "bibles", name + ".db"))
        texts[code] = dict(db.execute("SELECT id, text FROM verses"))
    return texts


def variants(b):
    """Forms made from base word [b] by a plain English ending."""
    if b.endswith("e"):
        # save -> saves, saved, saver, saving; never see -> seed or seer.
        out = {b + e for e in ENDINGS if not e.startswith("e")}
        if not b.endswith("ee"):
            out |= {b + e for e in ("d", "r", "rs", "st", "th")}
            out |= {b[:-1] + e for e in ("ing", "ings")}
        else:
            out |= {b + e for e in ("ing", "th", "st")}
    else:
        out = {b + e for e in ENDINGS}
    if b.endswith("y") and len(b) > 2 and b[-2] not in "aeiou":
        out |= {b[:-1] + e for e in ("ies", "ied", "ier", "iest", "ieth", "iness", "ily")}
    if len(b) >= 3 and b[-1] not in "aeiouwxy" and b[-2] in "aeiou" and b[-3] not in "aeiou":
        out |= {b + b[-1] + e for e in ("ing", "ed", "er", "est", "eth")}  # sin -> sinned, sinning
    return out


def word_groups(vocab):
    """form -> set of forms searched with it: the form, its base word, and both their endings."""
    made = collections.defaultdict(set)  # form -> base words it's made from
    for b in vocab:
        if len(b) < 3:
            continue
        for v in variants(b):
            if v in vocab and v != b:
                made[v].add(b)
    groups = {}
    for f in vocab:
        bases = {f} | made.get(f, set())
        alts = set()
        for b in bases:
            alts.add(b)
            alts |= {v for v in variants(b) if v in vocab}
        groups[f] = alts
    return groups


def main():
    study = sqlite3.connect(os.path.join(ASSETS, "study", "study.db"))
    texts = load_bibles()

    # How each version translates each Hebrew and Greek word, and where each is used.
    rend = collections.defaultdict(collections.Counter)  # strong -> english form -> count
    total = collections.Counter()
    lemma = collections.defaultdict(set)
    vocab = set()
    for code, _ in VERSIONS:
        for vid, tags in study.execute("SELECT id, words FROM tags WHERE version = ?", (code,)):
            text = texts[code].get(vid)
            if not text:
                continue
            nums = tags[1:-1].split(" ")
            toks = words(text)
            prefix = "G" if vid >= NT_START else "H"
            for n, tok in zip(nums, toks):
                if not n:
                    continue
                s = n if n[0] in "HG" else prefix + n.lstrip("0")
                rend[s][tok] += 1
                total[s] += 1
                lemma[s].add(vid)
        for t in texts[code].values():
            vocab.update(w for w in words(t) if w.isalpha() and len(w) >= 2)

    groups = word_groups(vocab)

    if os.path.exists(OUT):
        os.remove(OUT)
    out = sqlite3.connect(OUT)
    out.executescript(
        """
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE wordgroup(form TEXT NOT NULL, alt TEXT NOT NULL, PRIMARY KEY(form, alt)) WITHOUT ROWID;
        CREATE TABLE family(form TEXT NOT NULL, strong TEXT NOT NULL, uses INTEGER NOT NULL, share REAL NOT NULL,
            PRIMARY KEY(form, strong)) WITHOUT ROWID;
        CREATE TABLE lemma(strong TEXT PRIMARY KEY, ids TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE nave_sub(topic INTEGER NOT NULL, ord INTEGER NOT NULL, label TEXT NOT NULL,
            refs TEXT NOT NULL, PRIMARY KEY(topic, ord)) WITHOUT ROWID;
        CREATE TABLE weight(id INTEGER PRIMARY KEY, votes INTEGER NOT NULL);
        CREATE TABLE notes(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, ranges TEXT NOT NULL,
            title TEXT NOT NULL, body TEXT NOT NULL);
        CREATE VIRTUAL TABLE notes_fts USING fts4(content="", title, body);
        """
    )
    out.execute("INSERT INTO meta VALUES ('schema', ?)", (str(SCHEMA),))

    out.executemany("INSERT INTO wordgroup VALUES (?, ?)", ((f, a) for f, alts in groups.items() for a in alts))

    # Word families: for each group, the Hebrew/Greek words it translates often enough.
    by_form = collections.defaultdict(collections.Counter)  # english form -> strong -> count
    for s, c in rend.items():
        for w, n in c.items():
            by_form[w][s] += n
    fam_rows = 0
    for f, alts in groups.items():
        counts = collections.Counter()
        for a in alts:
            counts.update(by_form.get(a, {}))
        for s, k in counts.most_common(40):
            share = k / total[s]
            if k >= MIN_USES and share >= MIN_SHARE:
                out.execute("INSERT INTO family VALUES (?, ?, ?, ?)", (f, s, k, round(share, 3)))
                fam_rows += 1

    out.executemany("INSERT INTO lemma VALUES (?, ?)",
                    ((s, ",".join(map(str, sorted(ids)))) for s, ids in lemma.items()))

    # Nave's subtopics: each paragraph of an entry, its label and references in order.
    subs = 0
    for tid, body in study.execute("SELECT id, body FROM topics"):
        ord_ = 0
        for line in body.split("\n"):
            line = line.strip()
            if not line or not LINK.search(line):
                continue
            label = re.sub(r"\[\[.*", "", line).lstrip("–•-— ").rstrip(" ;,:")
            refs = [f"{m.group(1)}-{m.group(2)}" for m in LINK.finditer(line)]
            out.execute("INSERT INTO nave_sub VALUES (?, ?, ?, ?)", (tid, ord_, label, ",".join(refs)))
            subs += len(refs)
            ord_ += 1

    # Cross-reference votes pointing at each verse.
    votes = collections.Counter()
    kjv = sqlite3.connect(os.path.join(ASSETS, "bibles", "kjv.db"))
    for to_start, v in kjv.execute("SELECT to_start, votes FROM xrefs WHERE votes > 0"):
        votes[to_start] += v
    out.executemany("INSERT INTO weight VALUES (?, ?)", votes.items())

    # The app's own study content.
    notes = []
    ai = unpacked(os.path.join(ASSETS, "commentaries", "ai.db.xz"))
    try:
        for s, e, body in sqlite3.connect(ai).execute("SELECT start, end, body FROM entries"):
            main_note = re.split(r"\n\s*\(Sources:|\n\s*Where Christians differ:|\n\s*Other views:", body)[0].strip()
            notes.append(("commentary", f"{s}-{e}", "", main_note))
    finally:
        os.remove(ai)
    refl = unpacked(os.path.join(ASSETS, "commentaries", "reflections.db.xz"))
    try:
        for s, e, body in sqlite3.connect(refl).execute("SELECT start, end, body FROM entries"):
            lines = body.split("\n")
            title = lines[0].strip().strip('"“”')
            text = "\n".join(l for l in lines[1:] if not l.startswith(("Read:", "Summarised from", "Written by AI", "See also", "Also in")))
            notes.append(("reflection", f"{s}-{e}", title, text.strip()))
    finally:
        os.remove(refl)
    with open(os.path.join(ASSETS, "study", "glance.tsv"), encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 5 or not p[0].isdigit():
                continue
            book, ch = int(p[0]), int(p[1])
            base = book * 1_000_000 + ch * 1000
            notes.append(("glance", f"{base + 1}-{base + 999}", p[3], p[4]))
    with open(os.path.join(ASSETS, "study", "aids.tsv"), encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 10 or not p[5]:
                continue
            notes.append(("aid", p[5], p[2], p[-1]))
    with open(os.path.join(ASSETS, "study", "christ.tsv"), encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 6:
                continue
            notes.append(("christ", p[2], p[1], p[-1]))
    # The index covers every note. The commentary's and reflections' text isn't kept here: the app
    # shows it from their own databases.
    out.executemany("INSERT INTO notes(kind, ranges, title, body) VALUES (?, ?, ?, ?)",
                    ((k, r, t, "" if k in ("commentary", "reflection") else b) for k, r, t, b in notes))
    out.executemany("INSERT INTO notes_fts(docid, title, body) VALUES (?, ?, ?)",
                    ((i + 1, t, b) for i, (k, r, t, b) in enumerate(notes)))
    out.execute("INSERT INTO notes_fts(notes_fts) VALUES ('optimize')")

    out.commit()
    out.execute("VACUUM")
    out.close()
    kinds = collections.Counter(n[0] for n in notes)
    print(f"{OUT}: {len(groups)} word forms, {fam_rows} family rows, {len(lemma)} Hebrew/Greek words, "
          f"{subs} Nave's references, {len(votes)} weighted verses, notes {dict(kinds)}, "
          f"{os.path.getsize(OUT) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
