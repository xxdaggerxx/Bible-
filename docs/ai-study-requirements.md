# AI Search and Study — Requirements

Status: draft · Applies to: Bible Study app (Android tablet, offline-first) · Target releases: 0.5 onward

## 1. Purpose

Make the Bible easier to study by adding:

1. **Search by meaning.** "What does the Bible say about righteousness" returns *all* the relevant passages, organised so they can be read.
2. **Word studies** in the original Hebrew and Greek.
3. **An AI-built study index** of people, places, events, speakers, kinds of text, links between passages and book outlines.
4. **AI-written study syntheses**: topic guides, passage guides, character studies and study questions, every claim tied to verses.

## 2. Guiding principles

| # | Principle |
|---|---|
| P1 | **Offline first.** Everything except the optional features in §12 works with no internet connection. |
| P2 | **Build once, ship data.** All AI summarising, tagging and writing runs once on a desktop. The results ship in databases. The tablet only encodes the search query. |
| P3 | **The Bible text is the authority.** AI output helps readers *find* and *organise* passages. It is never shown in place of the text and is always visually separate from it. |
| P4 | **Every claim cites verses**, and the build checks the citations automatically. |
| P5 | **Describe, don't decide.** Where Christian traditions differ, present the main views without picking one. |
| P6 | **Measured quality.** Every layer has an evaluation set, and the build fails if quality regresses. |
| P7 | **Reproducible.** Every database is built by a script in `tools/`, like `build_version_db.py`. |
| P8 | **Licences respected.** Every data source is credited in the app and in `licenses/`. |

## 3. Why original-language embeddings are not the main approach

- Users search in English. Matching an English query to Biblical Hebrew or Koine Greek is the hardest kind of cross-language search, and general embedding models have very little training data in either language.
- The three bundled translations (KJV, BSB, WEB) already carry the meaning. Embedding all three gives most of the benefit.
- Hebrew and Greek are used where they are strongest: as **exact lemma tags** (Strong's numbers) for completeness and word studies (§6), not as vectors.

**Evidence from the bundled databases.** Verses containing "righteous…":

| | KJV | BSB | WEB |
|---|---|---|---|
| Verses matching | 510 | 538 | 572 |
| Missed, out of the 613 verses where any of the three matches | 103 | 75 | 41 |

Example: Gen 30:33 is "righteousness" in the KJV and "honesty" in the BSB. Searching "just" is mostly noise: the BSB has 289 verses with "just as". Keyword search in one translation therefore cannot find "all" passages on a topic.

## 4. Architecture overview

```
BUILD TIME (desktop, tools/)                         APP (tablet, offline)
────────────────────────────                         ─────────────────────
KJV/BSB/WEB text ─┐                                  query
STEPBible tags ───┼─► section records (AI) ──┐          │
Lexicons ─────────┤   topic guides (AI)      ├─► study.db    ├─► query router (topic / word / keyword / reference)
Theographic data ─┤   embeddings ────────────┤   lexicon.db  ├─► keyword (FTS) ─┐
Cross-references ─┘   checks & evaluation ───┘   vectors     ├─► lemma ─────────┼─► merge (RRF) ─► group ─► results
                                                             ├─► vectors ────────┤
                                                             └─► tags/xrefs ─────┘
```

**Shipped databases**

| File | Contents | Estimated size |
|---|---|---|
| `kjv.db`, `bsb.db`, `web.db` | Text + full-text search (existing) | existing |
| `lexicon.db` | Tagged original-language words, lexicons, English↔Strong's alignment | 25–40 MB |
| `study.db` | Sections, section records, topics, people, places, events, guides | 20–40 MB |
| `vectors` (in `study.db`) | int8 embeddings for sections, summaries, lexicon entries | 10–25 MB |
| Query encoder | Small embedding model (ONNX Runtime Mobile) | 25–120 MB |

## 5. Search

### 5.1 Query handling

| ID | Requirement | Priority |
|---|---|---|
| S1 | The search box accepts plain English questions, keywords, quoted phrases, references (`Rom 3:21`), Strong's numbers (`H6666`), transliterations (`tsedaqah`), and Hebrew or Greek script. | Must |
| S2 | A query router classifies the query as **reference**, **exact phrase**, **word study**, **topic**, or **general**, and strips question framing ("What does the Bible say about…"). | Must |
| S3 | Existing exact-phrase search (quoted text) keeps its current behaviour. | Must |
| S4 | When a query matches a lexicon word or a topic, the app offers shortcuts: "Word study: *tsedaqah*" and "Topic: Righteousness". | Should |

### 5.2 Hybrid search

| ID | Requirement | Priority |
|---|---|---|
| S10 | Four sources are searched in parallel: **keyword** (FTS, BM25), **lemma** (Strong's), **vector** (meaning), and **tags/cross-references**. | Must |
| S11 | Results are merged with Reciprocal Rank Fusion. A reranker may be added later if evaluation shows it helps. | Must |
| S12 | Keyword search runs across all three translations, and a verse matches if any translation matches. | Must |
| S13 | Each result shows why it matched: word (e.g. "*tsedaqah* — 'honesty' in BSB"), meaning, topic tag, or cross-reference. | Must |
| S14 | Results can be filtered by testament, book range, genre, speaker and kind of text. | Should |
| S15 | Neighbouring matching verses are merged into one passage result. | Must |
| S16 | "Find similar" from any verse or passage runs the vector search starting from that passage. | Should |

### 5.3 Topic search: "all the relevant passages"

| ID | Requirement | Priority |
|---|---|---|
| T1 | For a topic with a Hebrew/Greek word family, results include **100%** of the verses containing any lemma in that family, whatever the English translation. | Must |
| T2 | Results also include passages about the topic that do not use its words (concept layer, §5.4). | Must |
| T3 | Results include strongly voted OpenBible cross-references of the core verses, ranked lower. | Should |
| T4 | The **first screen** shows **key passages**, ranked by word-family density, number of cross-references pointing to it, Nave's listing, and concept score. | Must |
| T5 | The full result set is grouped into **subtopics** (clusters assigned at build time). Example for righteousness: God's righteousness; righteousness by faith; living righteously; the righteous and the wicked; justice for the poor; Christ the Righteous One; self-righteousness. | Must |
| T6 | "All N verses" opens a complete list, filterable by book, testament, lemma, and subtopic. | Must |
| T7 | Topic results link to the matching topic study guide (§8.1) and word studies (§6). | Should |

**Word families** are held in a table (`topic_lemma`). Righteousness, for example:
- Hebrew: H6662 *tsaddiq*, H6663 *tsadaq*, H6664 *tsedeq*, H6666 *tsedaqah*
- Greek: G1342 *dikaios*, G1343 *dikaiosynē*, G1344 *dikaioō*, G1345 *dikaiōma*, G1347 *dikaiōsis*

A topic can also list related families (e.g. *mishpat*, "justice") as optional expansions.

### 5.4 Concept layer

| ID | Requirement | Priority |
|---|---|---|
| C1 | The Bible is split into **sections** of about 5–15 verses, one topic each, using the BSB section headings, with sliding windows of 3–5 verses to fill gaps. Expect about 2,000–3,000 sections. | Must |
| C2 | Each section gets an AI-written **summary** (1–2 sentences) and **theme tags** chosen from a fixed topic list (starting from Nave's Topical Bible's topics) plus free themes. | Must |
| C3 | Embeddings are stored for (a) the section text in each translation and (b) the summary with its themes. At query time the best score across these is used. | Must |
| C4 | Theme tags can be searched directly, without going through vectors, so tagged sections are always found. | Must |
| C5 | Vector matches are kept if their similarity is above a threshold calibrated on the evaluation set, not a fixed top-k. | Must |
| C6 | Summaries are used only to find and rank passages. The results list always shows Bible text, never the summary alone. | Must |
| C7 | The search runs as brute-force cosine similarity over int8 vectors in under 50 ms on the Tab S9. No approximate index is needed at this size. | Must |

## 6. Word studies

| ID | Requirement | Priority |
|---|---|---|
| W1 | Tapping a word in KJV or BSB text shows the original word, transliteration, Strong's number, grammar (morphology), short gloss, and lexicon entry. | Must |
| W2 | In WEB, a tap goes through the matching KJV/BSB verse if no WEB alignment exists, and says so. | Should |
| W3 | A **word study page** shows: every occurrence (a concordance); counts by book with a chart; how each translation renders it, with counts; the word family (root and related words); and synonyms and opposites. | Must |
| W4 | Concordance results can be filtered by grammatical form (e.g. *dikaioō* passive only), book, and translation rendering. | Should |
| W5 | Hebrew–Greek links: an Old Testament lemma shows its usual Septuagint Greek equivalent, and the other way round (subject to licence, see §14). | Could |
| W6 | **Sense groups:** the occurrences of each lemma are grouped by meaning, each group with a one-line description (built by AI, labelled as such, checked against the lexicon). | Should |
| W7 | Lexicon entries are embedded, so English phrases ("steadfast love", "loyalty") find the right lemma (*hesed*, H2617). | Should |
| W8 | Searching by Strong's number, transliteration, Hebrew or Greek script, or English gloss opens the same word study. | Must |

## 7. Study index (AI-extracted, facts tagged onto the text)

### 7.1 People, places, events

| ID | Requirement | Priority |
|---|---|---|
| I1 | **People index.** Each person is told apart from others with the same name, with their family, verses and life events. | Must |
| I2 | Pronoun resolution: where a verse refers to a person by pronoun, the index records who (e.g. 2 Sam 12:13 "he" → David). | Could |
| I3 | **Places index** with map coordinates (OpenBible.info geocoding) and every verse mentioning each place. | Should |
| I4 | **Events and timeline.** Events link the passages that describe them (Kings/Chronicles/prophets), with approximate dates where there is consensus and ranges where there isn't. | Should |
| I5 | Existing curated data (Theographic Bible Metadata) is used first. AI fills gaps, and AI-added entries are marked as such. | Must |

### 7.2 Speakers and kinds of text

| ID | Requirement | Priority |
|---|---|---|
| I10 | Every verse (or part of a verse) gets a **speaker** tag: God, Jesus, a named person, a group, or the narrator. | Should |
| I11 | Each section gets **kind-of-text** tags from a fixed list: promise, command, prophecy, fulfilment, prayer, song, lament, parable, miracle, covenant, genealogy, blessing, curse, confession, sermon, question from God. | Must |
| I12 | Lists built from these tags can be browsed ("All prayers", "Promises to Abraham", "Miracles of Elisha") and filtered by book and person. | Should |
| I13 | Each section gets a **genre**: narrative, law, poetry, wisdom, prophecy, apocalyptic, gospel or letter. | Must |

### 7.3 Links between passages

| ID | Requirement | Priority |
|---|---|---|
| I20 | **Old Testament quotes and allusions in the New Testament**, each linked to its source and marked *quotation* or *allusion*. AI suggestions are checked against existing public lists. | Should |
| I21 | **Parallel passages** (Synoptic Gospels, Samuel/Kings–Chronicles, duplicate Psalms) can be viewed side by side. | Should |
| I22 | **Prophecy and fulfilment** links, labelled "traditionally read as fulfilled in…" rather than stated as fact. | Could |
| I23 | Cross-references (existing OpenBible data) gain an AI-written one-line reason for the link. | Could |

### 7.4 Structure

| ID | Requirement | Priority |
|---|---|---|
| I30 | A multi-level **outline of every book**, open-able from the chapter header. | Should |
| I31 | **Book introductions**: author, date, audience, setting and purpose, giving the main views where scholars disagree. | Should |

## 8. Study syntheses (AI-written)

### 8.1 Topic study guides

| ID | Requirement | Priority |
|---|---|---|
| G1 | A study guide for each of the top ~500 topics, tracing how the topic develops through the Bible (Law → History → Wisdom → Prophets → Gospels → Letters → Revelation). | Should |
| G2 | Every statement in a guide cites one or more verses. Tapping a citation opens the verse. | Must |
| G3 | Where traditions disagree, the guide gives the main views side by side, each with its key passages. | Must |
| G4 | Guides link to their topic's search results, word studies and related topics. | Should |

### 8.2 Passage guides

| ID | Requirement | Priority |
|---|---|---|
| G10 | For each section: context (what comes before and after), key words (linked to word studies), cultural and historical background, and cross-references with the reason each is related. | Should |
| G11 | **Study questions** for each section in three groups: observation, interpretation, application. They can be answered in the margin with the pen or as typed notes. | Should |

### 8.3 Character studies and reading plans

| ID | Requirement | Priority |
|---|---|---|
| G20 | Character studies for major figures (e.g. "The life of Joseph"), with every event cited and in order. | Could |
| G21 | Thematic reading plans (e.g. "30 days on grace") built from the topic index. | Could |

## 9. Personal notes

| ID | Requirement | Priority |
|---|---|---|
| N1 | Typed notes are embedded on the device when saved, and included in search as a separate "Your notes" group. | Should |
| N2 | Once handwriting recognition exists (0.5 roadmap), recognised handwritten notes are included the same way. | Could |
| N3 | Topic results and study guides show "Your notes on this topic". | Could |
| N4 | Notes never leave the device except through the existing backup feature. | Must |

## 10. Rules for AI content

| ID | Requirement | Priority |
|---|---|---|
| Q1 | All AI-generated content is labelled, visually different from the Bible text, and can be hidden with a single setting ("Show AI study aids"). | Must |
| Q2 | AI prompts ask for descriptive, text-based content, not doctrinal verdicts. | Must |
| Q3 | Automatic check: every cited reference exists. Any quoted wording matches the cited translation. Anything failing is regenerated or dropped. | Must |
| Q4 | Automatic check: tags come only from the fixed lists. Names and places match the index. | Must |
| Q5 | Human spot check before each release: at least 5% of new section records, and every topic guide in the top 50 topics. The checks are recorded in the repo. | Must |
| Q6 | Every generated item stores the model, prompt version and date it was generated with, so it can be regenerated. | Must |
| Q7 | Users can flag an AI item as wrong. Flags are stored locally and can be exported for review. | Could |

## 11. Build pipeline

| ID | Requirement | Priority |
|---|---|---|
| B1 | `tools/build_lexicon_db.py` imports STEPBible TAHOT/TAGNT, TBESH/TBESG, public-domain lexicons and Strong's alignments for KJV/BSB into `lexicon.db`. | Must |
| B2 | `tools/build_sections.py` splits the Bible into sections (C1). | Must |
| B3 | `tools/ai_annotate.py` produces one JSON **section record** per section (schema below), using the Claude API with prompt caching and batch processing. It resumes after interruptions and caches results so reruns only process changed sections. | Must |
| B4 | `tools/ai_guides.py` produces topic guides, passage guides and character studies from the section records and the indexes. | Should |
| B5 | `tools/build_study_db.py` checks all records (Q3, Q4), computes embeddings, clusters topic results into subtopics and writes `study.db`. | Must |
| B6 | Generated JSON is committed to the repo (`data/ai/`), so the app can be rebuilt without calling the API again. | Must |
| B7 | Expected one-time cost: tens of US dollars for about 2,500 section records and about 500 topic guides. | Info |

**Section record schema (draft)**

```json
{
  "section": "MIC.6.6-8",
  "summary": "The prophet asks what offering would please God; the answer is a life of justice, faithful love and humble walking with Him.",
  "themes": ["righteousness", "justice", "mercy", "humility", "true worship"],
  "genre": "prophecy",
  "types": ["question", "command"],
  "speakers": [{"verses": "6-7", "who": "worshipper"}, {"verses": "8", "who": "prophet"}],
  "people": [],
  "places": [],
  "keywords": ["H4941", "H2617"],
  "allusions": [{"ref": "DEU.10.12", "kind": "allusion"}],
  "questions": {"observe": ["…"], "interpret": ["…"], "apply": ["…"]},
  "meta": {"model": "…", "prompt_version": "1", "generated": "2026-10-01"}
}
```

## 12. Optional online AI

| ID | Requirement | Priority |
|---|---|---|
| A1 | "Ask a question" sends the question plus the top passages found on the device to the Claude API and returns an answer with verse citations. Only the retrieved passages are used as sources. | Could |
| A2 | Uses the user's own API key, stored in encrypted app storage. Off by default. | Must (if A1) |
| A3 | Answers follow Q1–Q3 (labelled, descriptive, citations checked on the device). | Must (if A1) |
| A4 | Only the question and Bible passages are sent. Notes, ink and bookmarks are never sent. | Must (if A1) |

## 13. Non-functional requirements

| ID | Requirement |
|---|---|
| NF1 | Search returns its first screen within 500 ms on a Galaxy Tab S9 (warm start). The complete set of topic results loads within 1.5 s. |
| NF2 | Everything in §5–§11 works offline. |
| NF3 | The AI features add no more than 250 MB to the installed app. |
| NF4 | The query encoder loads lazily. The first search after launch may take up to 2 s. |
| NF5 | New databases are read-only and versioned. A data update never affects user notes, ink or bookmarks. |
| NF6 | Accessibility: word studies and results work with TalkBack. Hebrew is shown right to left, using a font with vowel points. |

## 14. Data sources and licences

| Data | Source | Licence | Status |
|---|---|---|---|
| Tagged Hebrew OT / Greek NT | STEPBible TAHOT, TAGNT | CC BY 4.0 | OK |
| Short lexicons | STEPBible TBESH, TBESG | CC BY 4.0 | OK |
| Strong's, abridged BDB, Thayer | Various | Public domain | OK |
| KJV with Strong's alignment | e.g. OpenScriptures / KJV2006 | Public domain | Check source |
| BSB interlinear / alignment | Berean Bible | Check terms | **To verify** |
| WEB Strong's alignment | Unknown | — | **To find**, otherwise W2 |
| Semantic domains | MACULA Hebrew/Greek, SDBH | CC BY / CC BY-SA | **To verify** |
| Louw–Nida | UBS | Copyrighted | Not used |
| Septuagint alignment (W5) | Various | Varies | **To verify** |
| People, places, events | Theographic Bible Metadata | CC BY-SA 4.0 | Share-alike applies to `study.db` |
| Geocoding | OpenBible.info | CC BY 4.0 | OK |
| Cross-references | OpenBible.info | CC BY 4.0 | Already used |
| Topic list and key verses | Nave's Topical Bible | Public domain | OK |
| Embedding model | TBD (MiniLM / bge-small / e5-small class) | Must allow redistribution | **To choose** |

## 15. Evaluation and acceptance

| ID | Requirement |
|---|---|
| E1 | **Evaluation set:** at least 200 queries with expected verses, taken from Nave's topics, Treasury of Scripture Knowledge and hand-written queries. Stored in `tools/eval/`. |
| E2 | **Word-family recall** (T1): 100% for every topic that has a word family. The build fails otherwise. |
| E3 | **Concept precision** (T2, C5): at least 80% of included passages without the topic's words are judged relevant, checked on a sample against the Nave's entry. |
| E4 | **Key passages on the first screen** (T4): at least 90% of Nave's key verses for the topic appear on the first screen. |
| E5 | **Overall search:** recall of expected verses in the top 10 and ranking quality (nDCG@10) are reported per build. A drop of more than 3 points fails the build. |
| E6 | **Checks** (Q3/Q4): 0 invalid citations or tags in shipped data. |
| E7 | **Acceptance queries** that must pass before release: *righteousness, grace, forgiveness, faith, love, anger, prayer, suffering, the Holy Spirit, the kingdom of God*. |

## 16. Phases

| Phase | Release | Contents | Depends on |
|---|---|---|---|
| 1 | 0.5 | `lexicon.db`; tap-a-word; word study page (W1, W3, W8); lemma search; T1 | STEPBible data |
| 2 | 0.6 | Sections; section records; concept layer; hybrid search; topic results with subtopics (S, T, C); evaluation set | Phase 1 |
| 3 | 0.7 | People, places, events; speakers; kinds of text; lists; outlines (I) | Phase 2 records |
| 4 | 0.8 | Topic guides, passage guides, study questions (G); notes in search (N) | Phases 2–3 |
| 5 | later | Sense groups (W6), Septuagint links, character studies, reading plans, optional online "Ask" (A) | — |

**First prototype:** run the section-record pass on Micah (7 chapters) and Romans (16 chapters), build a small `study.db`, and run the "righteousness" acceptance query on the desktop before starting the Android work.

## 17. Open questions

1. Which embedding model: size vs quality on the evaluation set, and licence for redistribution.
2. Should the Theographic CC BY-SA data live in a separate database so share-alike doesn't apply to the rest of `study.db`?
3. Who does the human spot checks (Q5), and which theological reviewers, if any?
4. Can the BSB alignment data be used offline, or do word taps go through the KJV alignment only?
5. Should topic guides be written for a general audience or configurable by study depth?
