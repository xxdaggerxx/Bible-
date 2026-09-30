# AI Search and Study — Requirements

Status: draft · Applies to: Bible Study app (Android tablet, offline-first) · Target releases: 0.5 onward

## 1. Purpose

Make the Bible easier to study by adding:

1. **Search by meaning.** "What does the Bible say about righteousness" returns *all* the relevant passages, organised so they can be read.
2. **Word studies** in the original Hebrew and Greek.
3. **An AI-built study index** of people, places, events, speakers, kinds of text, links between passages and book outlines.
4. **AI-written study syntheses**: topic guides, passage guides, character studies and study questions, every claim tied to verses.
5. **Search that understands the kind of question.** "Paul's travels" gives a timeline and map, "Abraham's trials" gives his life in order, and "seed" gives its different meanings, using ready-made result pages built by an AI agent and an optional live agent online.

## 2. Guiding principles

| # | Principle |
|---|---|
| P1 | **Offline first, AI agent optional.** Every search works with no internet connection and no online AI. The online agent (§12) only adds to results that have already been shown; it is never needed to get an answer. See §5.8. |
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

More examples from the same databases:

| Phrase | KJV | BSB | WEB | Note |
|---|---|---|---|---|
| "seed(s)" | 256 | 90 | 67 | The BSB says "offspring/descendants" (479 verses). The KJV has "seed" in 192 verses where the BSB doesn't. |
| "word of the LORD" | 255 | 236 | 18 | The WEB says "the word of Yahweh". |
| "word of God" | 48 | 43 | 41 | 52 verses in total, mostly in the New Testament. John 1:1 and 2 Tim 3:16 don't contain the phrase. |
| Gen 22:1 | "God did **tempt** Abraham" | "God **tested** Abraham" | "God **tested** Abraham" | Same Hebrew verb, *nasah* H5254. |

## 4. Architecture overview

```
BUILD TIME (desktop, tools/)                         APP (tablet, offline)
────────────────────────────                         ─────────────────────
KJV/BSB/WEB text ─┐                                  query
STEPBible tags ───┼─► section records (AI) ──┐          │
Lexicons ─────────┤   topic guides (AI)      ├─► study.db    ├─► ready-made page? ─► query parser (search plan)
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
| S2 | A query parser turns the query into a **search plan**: its search shape (§5.5), the people, places, topics and words it names, and how the results should be shown. It strips question framing ("What does the Bible say about…", "What is…"). See §5.7. | Must |
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
| T1 | For a topic with a Hebrew/Greek word rule (T8), results include **100%** of the verses matching the rule, whatever the English translation. | Must |
| T2 | Results also include passages about the topic that do not use its words (concept layer, §5.4). | Must |
| T3 | Results include strongly voted OpenBible cross-references of the core verses, ranked lower. | Should |
| T4 | The **first screen** shows **key passages**, ranked by word-family density, number of cross-references pointing to it, Nave's listing, and concept score. | Must |
| T5 | The full result set is grouped into **subtopics** (clusters assigned at build time). Example for righteousness: God's righteousness; righteousness by faith; living righteously; the righteous and the wicked; justice for the poor; Christ the Righteous One; self-righteousness. | Must |
| T6 | "All N verses" opens a complete list, filterable by book, testament, lemma, and subtopic. | Must |
| T7 | Topic results link to the matching topic study guide (§8.1) and word studies (§6). | Should |
| T8 | **Word rules** define which verses belong to a topic. A rule is either a **word family** (any lemma in a set) or a **word combination** (a lemma together with another lemma or a speaker/owner within the same phrase). Combinations are needed when the words are common: *dabar* (≈1,400 uses, mostly "thing, matter") and *logos* (≈330) only mean "the word of God" together with God / the LORD. | Must |
| T9 | **Topics with several meanings.** A topic can have named meanings, each with its own word rules, concept tags and key passages. Results are grouped by meaning first, then by subtopic. Examples: "the word of God" (Christ the Word; Scripture; God's creating word; the prophetic word; the gospel preached; hearing and doing the word), "seed" (the promised Seed; offspring; the word as seed; sowing and reaping; mustard-seed faith; literal agriculture). | Must |
| T10 | **Relationships between topics.** A query joining two topics with a relationship ("righteousness *in* Jesus Christ") returns passages where the relationship holds, not the union or plain overlap of the two topics. Uses the concept layer plus AI-tagged relationships in section records. | Must |
| T11 | Meanings or subtopics that are very large and repetitive (e.g. "The word of the LORD came to…", ≈260 verses) are collapsed with a count and a few examples. | Should |

**Word rules** are held in tables (`topic_rule`, `topic_meaning`). Righteousness, for example (word family):
- Hebrew: H6662 *tsaddiq*, H6663 *tsadaq*, H6664 *tsedeq*, H6666 *tsedaqah*
- Greek: G1342 *dikaios*, G1343 *dikaiosynē*, G1344 *dikaioō*, G1345 *dikaiōma*, G1347 *dikaiōsis*

The word of God, for example (word combination):
- H1697 *dabar* or H565 *imrah* **with** H3068 YHWH or H430 *Elohim* in the same phrase
- G3056 *logos* or G4487 *rhēma* **with** G2316 *theos* or G2962 *kyrios* in the same phrase
- Plus concept tags for passages without the phrase (John 1:1–14, 2 Tim 3:16, Ps 119)

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

### 5.5 Search shapes and how results are shown

Different searches need different indexes and a different way of showing results.

| Shape | Example | Indexes used | Shown as |
|---|---|---|---|
| Reference | `Rom 3:21` | text | opens the passage |
| Exact phrase | `"the just shall live by faith"` | keyword | verse list |
| Word | `hesed`, `H2617` | lemma, lexicon | word study page (§6) |
| Topic | righteousness | word rule, concept, topic tags, cross-references | key passages + subtopics (§5.3) |
| Topic with several meanings | word of God; seed | word rules, concept, topic tags | groups by meaning, then subtopics |
| Relationship between topics | righteousness in Jesus Christ | concept, topic tags, word rules, relationships | key passages + views where traditions differ |
| Person + theme | Abraham's trials | people, events, concept, lemma | the person's life as a timeline, then NT commentary |
| Person + events + places | Paul's travels | people, events, places | timeline + map, by journey |
| Person | David | people, events | life timeline, family, key passages |
| Place | Bethel | places, events | map + events there, in order |
| Event | the Exodus | events, places, people | ordered passages + parallel accounts |
| General | anything else | hybrid search (§5.2) | ranked passages |

| ID | Requirement | Priority |
|---|---|---|
| SH1 | Every search is assigned one of the shapes above. The shape decides the indexes used and how results are shown. | Must |
| SH2 | Results show the search plan in one line, e.g. "Abraham → events tagged *testing* + *nasah* (H5254) + Heb 11, Rom 4, Jas 2". Tapping it lets the user change the shape or remove a part. | Must |
| SH3 | When the shape is uncertain, the app shows the most likely shape and offers the next one ("Did you mean: word study *zera*?"). | Should |
| SH4 | Timelines order events by the event index. Where the order is uncertain or disputed, the app says so. | Should |

### 5.6 Ready-made result pages

| ID | Requirement | Priority |
|---|---|---|
| RP1 | At build time, an AI search agent (B8) runs over a list of likely searches: every topic in the topic list, every major person, place and event, every multi-meaning word, and the acceptance searches (§15). | Must |
| RP2 | For each one it plans the search, runs it against the same indexes the app uses, checks the results and saves a **ready-made result page** (groups, order, key passages, plan line) in `study.db`. | Must |
| RP3 | A query that matches a ready-made page (after the parser normalises it) shows that page instantly and offline. | Must |
| RP4 | Ready-made pages contain only verse references and labels from the indexes. Every reference is checked (Q3). | Must |
| RP5 | Queries with no ready-made page fall back to the query parser (§5.7) and live hybrid search. | Must |

### 5.7 Query parser (on the tablet, offline)

| ID | Requirement | Priority |
|---|---|---|
| QP1 | The parser turns a query into a structured **search plan**, e.g. `{"shape": "person_theme", "person": "Abraham", "theme": "testing", "present": "timeline"}`. | Must |
| QP2 | The parser matches against the index vocabulary first: names of people and places (with alternative spellings), topics and their synonyms, Strong's glosses and transliterations, book names. | Must |
| QP3 | Where vocabulary matching is not enough, a small on-device model may be used, if testing (E1) shows it helps. | Could |
| QP4 | The parser returns a plan in under 100 ms. | Must |

### 5.8 Fallback chain: search without the online agent

Every search is answered by the first step below that can handle it. All steps except the last are offline, and the last one is optional.

| Step | Handles | Needs | If it can't answer |
|---|---|---|---|
| 1. **Ready-made page** | Topics, people, places, events and words prepared at build time (§5.6) | `study.db` | go to step 2 |
| 2. **Query parser + shape handler** | Any query the parser can give a plan with reasonable confidence (§5.5, §5.7) | `study.db`, `lexicon.db` | go to step 3 |
| 3. **Hybrid search** | Any query: keyword + lemma + meaning + tags, merged (§5.2) | query encoder, `study.db`, `lexicon.db` | drop the parts that are unavailable, still show results |
| 4. **Keyword search** | Any query: the existing full-text search across KJV, BSB and WEB | Bible databases only | shows "No results" with suggestions (spelling, fewer words, browse topics) |
| 5. **Online agent** *(optional)* | Open questions, after results from steps 1–4 are already on screen | internet, API key, user opt-in | nothing changes; the offline results stay |

| ID | Requirement | Priority |
|---|---|---|
| F1 | Every search shows results from steps 1–4 first, within the NF1 time limits, whether or not the online agent is available, enabled or configured. | Must |
| F2 | The online agent never replaces the offline results. It runs only when the user asks ("Search deeper with AI") and its results are shown as an addition that can be dismissed. | Must |
| F3 | If the device is offline, no API key is set, the agent is switched off, the request fails, or it takes longer than 30 s, the offline results stay on screen and a short note explains why (e.g. "AI search needs internet"). There is never a blank or error-only screen. | Must |
| F4 | Each offline part fails on its own: if the query encoder can't load (e.g. low memory), step 3 runs without meaning-based search; if `study.db` or `lexicon.db` is missing or damaged, steps 1–2 are skipped and step 3 uses what remains; step 4 always works because it only needs the Bible databases. | Must |
| F5 | The results screen shows which step answered ("Prepared study page", "Matched: person + theme", "Keyword results only") so the user knows how complete the results are. | Should |
| F6 | When a step is skipped because a part is unavailable, the app logs it locally and offers to repair it (e.g. reinstall the study data), without blocking search. | Should |
| F7 | Nothing in steps 1–4 calls the network, including analytics or model downloads. All models and data ship in the APK or its bundled data. | Must |
| F8 | All acceptance searches (E7, E8) pass in airplane mode with the online agent switched off. | Must |

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

### 8.4 Sermons (later stage)

**Scheduled for a later stage (phase 6), after the Bible study features in phases 1–5 are complete.** Nothing in phases 1–5 depends on sermons. The requirements below describe the intended design; the "Must" priorities apply once this stage starts.

Topic pages, passages and word studies show sermons on the same topic or text, with an AI summary of each and a link to the original. Sermons come from two kinds of source, handled differently.

| | **Historical sermons** | **Modern pastors' sermons** |
|---|---|---|
| Examples | Spurgeon, Wesley, Edwards, Whitefield, Ryle, M'Cheyne, Moody; Luther and Calvin (19th-century translations); Augustine, Chrysostom | Living or recent pastors whose sermons are copyrighted |
| Copyright | Public domain (verify each source edition) | Copyrighted |
| Full text | May be shipped, or offered as an optional offline download | **Never stored or shipped.** Read only to write the summary |
| Summary | AI summary, checked (SR4), shipped in `study.db` | Short AI summary in own words, checked (SR4) |
| Quotes | 1–3 key quotes, word for word | At most one short quote (≤ 25 words), or none |
| Link | To the full text (offline copy or source) | **Always** to the sermon on the ministry's own website or official channel |
| Offline | Everything works offline | Summary shown offline; the link needs internet |

| ID | Requirement | Priority |
|---|---|---|
| SR1 | Each sermon record holds: preacher, tradition, era, date, title, main Bible text, other verses cited, topics, summary, key quote(s), source link, source licence or permission status, and generation metadata (Q6). | Must |
| SR2 | Sermons are linked to the Bible through their main text and the verses they cite, and to topics through their tags. They appear on topic pages (§5.3), in passage guides (§8.2) and from a verse tap ("Sermons on this passage"). | Must |
| SR3 | On topic pages, sermons appear in a separate, labelled panel ("Sermons") below the Bible passages, never mixed into them. The panel can be hidden with the AI setting (Q1). | Must |
| SR4 | **Accuracy about real people.** Summaries describe *what this sermon says*, never "what [pastor] believes". Every quote is checked word for word against the source at build time. Every summary is checked against the sermon (automatic check plus human spot check, Q5). A summary that can't be checked is not shipped. | Must |
| SR5 | **Balance.** For a topic, the panel shows sermons from different traditions and eras where they exist, each labelled (e.g. "Methodist · 18th century"). It never presents one preacher's view as the answer. | Must |
| SR6 | **Modern sermons: link, don't copy.** For copyrighted sermons, the app ships only metadata, a short summary in its own words, and the link. Transcripts and audio are not stored in the repo, the build output or the app. The card shows "Summary by AI · Listen/read the full sermon at [ministry]" with the link prominent. | Must |
| SR7 | **Respect sources.** Sermons are collected only through official APIs, feeds or pages that the source's terms of use and robots.txt allow. No bulk scraping against terms. Each source's terms are recorded in `licenses/` before it is used. | Must |
| SR8 | **Opt-out and permission.** Any ministry that asks is removed in the next data update. Where a ministry grants permission in writing, its sermons can move to fuller treatment (longer quotes, stored text) within the permission's terms. | Must |
| SR9 | **Personal-use scope.** Summaries of copyrighted sermons are for the personal, non-commercial app. Distributing the app more widely or commercially requires permission from each ministry first. | Must |
| SR10 | "Summarise this sermon now" (optional, online): for a sermon the user opens at its source, the online agent can write a summary on request for that user only, stored on the device. | Could |
| SR11 | Offline, modern sermon cards show the summary and say "Full sermon available online". Missing sermon data never blocks search (§5.8). | Must |

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
| B8 | `tools/ai_search_agent.py` runs the search agent at build time over the list of likely searches (RP1), using the same tools as §12 against the built databases, and writes the ready-made result pages. | Must |
| B9 | `tools/eval/run_eval.py` runs the evaluation set (§15) against the built databases and fails the build on a regression. | Must |
| B7 | Expected one-time cost: tens of US dollars for about 2,500 section records, about 500 topic guides and a few thousand ready-made result pages. | Info |

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

## 12. Optional online AI search agent

For open questions that have no ready-made page and that the parser can't handle well, an optional live agent plans and runs the search.

| ID | Requirement | Priority |
|---|---|---|
| A0 | The agent is optional and additive. The app is complete without it, and every rule in §5.8 applies. | Must |
| A1 | The agent uses the Claude API with **tools that run on the tablet** against the local indexes (below). It chooses tools, reads results, refines, and returns grouped, ordered results plus a short cited overview. | Could |
| A2 | Uses the user's own API key, stored in encrypted app storage. Off by default. | Must (if A1) |
| A3 | The agent can only cite verses its tools returned. The app checks every reference before display and drops any that fail. | Must (if A1) |
| A4 | The results are always Bible passages. The overview is labelled as AI, follows Q1–Q2, and can be hidden. | Must (if A1) |
| A5 | The agent's plan is shown (SH2), so the user can see and adjust what was searched. | Must (if A1) |
| A6 | Only the question, tool calls and Bible passages are sent. Notes, ink and bookmarks are never sent. | Must (if A1) |
| A7 | Target: results within 5–20 s; the app shows progress ("Searching people: Abraham…"). | Should |
| A8 | A useful agent result can be saved on the device as a personal result page. | Could |

**Agent tools**

| Tool | What it does |
|---|---|
| `search_keyword` | Exact words and phrases in any translation |
| `search_lemma` | Verses by Strong's number, word family or word combination |
| `search_concept` | Meaning-based search over sections and summaries |
| `get_topic` | A topic's results, meanings and subtopics |
| `get_person` | A person, their family and events |
| `get_events` | Events by person, place, theme or range, in order |
| `get_place` | A place, its coordinates and events |
| `get_xrefs` | Cross-references of a verse or passage |
| `read_passage` | Bible text of a passage in a chosen translation |

## 13. Non-functional requirements

| ID | Requirement |
|---|---|
| NF1 | Search returns its first screen within 500 ms on a Galaxy Tab S9 (warm start). The complete set of topic results loads within 1.5 s. |
| NF2 | Everything in §5–§11 works offline. Only §12 uses the network, and only when the user asks for it. |
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
| Historical sermons | Public-domain editions (e.g. Spurgeon's *Metropolitan Tabernacle Pulpit*, Wesley's *Sermons on Several Occasions*, Edwards, NPNF translations of Chrysostom and Augustine) | Public domain | **Check each edition**; some hosting sites (e.g. CCEL) have terms on their own editions |
| Modern sermons | Ministry websites, official feeds and APIs | Copyrighted | Summaries + links only (SR6); **terms checked per source** (SR7) |
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
| E7 | **Acceptance queries** that must pass before release: *righteousness, grace, forgiveness, faith, love, anger, prayer, suffering, the Holy Spirit, the kingdom of God*, plus the searches in E8. |
| E8 | **Search-shape acceptance set.** Each search must get the right shape, and its first screen must show the expected groups and key passages (table below). |
| E9 | **Parser accuracy:** at least 90% of the evaluation queries get the correct shape. |
| E10 | **Offline test:** the whole evaluation set runs in airplane mode with the agent off; the scores in E2–E9 must be met without it. |
| E12 | **Sermon checks:** 100% of shipped quotes match their source word for word; at least 95% of spot-checked summaries are judged accurate; no transcript of a copyrighted sermon is found anywhere in the repo, build output or APK (automated scan). |
| E11 | **Failure tests:** automated tests remove or damage each part in turn (network, API key, query encoder, `study.db`, `lexicon.db`) and check that search still returns results from the next step in the chain (F4), with no crash and no blank screen. |

**E8 acceptance searches**

| Search | Expected shape | Expected groups / order | Must include on first screen |
|---|---|---|---|
| what is the word of God | topic with several meanings | Christ the Word; Scripture; God's creating word; the prophetic word; the gospel preached; hearing and doing | John 1:1–14, Rev 19:13, 2 Tim 3:16–17, Heb 4:12, Ps 119:105, Isa 55:10–11, Luke 8:11 |
| seed | topic with several meanings | the promised Seed; offspring; the word as seed; sowing and reaping; mustard-seed faith; literal (collapsed) | Gen 3:15, Gen 22:18, Gal 3:16, Luke 8:11, 1 Pet 1:23, Gal 6:7 |
| righteousness in Jesus Christ | relationship between topics | key passages; views where traditions differ | Rom 3:21–26, Rom 5:17–19, Rom 10:4, 1 Cor 1:30, 2 Cor 5:21, Phil 3:9, Jer 23:6 |
| Paul's travels | person + events + places | Damascus and Arabia; first journey; second journey; third journey; voyage to Rome; plans in the letters | Gal 1:17, Acts 13–14, Acts 15:36–18:22, Acts 18:23–21:17, Acts 27–28, Rom 15:24 |
| Abraham's trials | person + theme | his life in order, then NT commentary | Gen 12, Gen 16, Gen 22, Heb 11:8–19, Rom 4:18–21, Jas 2:21–23 |
| what does the Bible say about righteousness | topic | key passages; subtopics (§5.3 T5) | Gen 15:6, Ps 1, Mic 6:8, Matt 5:20, Rom 3:21–26, 2 Cor 5:21, Phil 3:9 |

## 16. Phases

| Phase | Release | Contents | Depends on |
|---|---|---|---|
| 1 | 0.5 | `lexicon.db`; tap-a-word; word study page (W1, W3, W8); lemma search; T1 | STEPBible data |
| 2 | 0.6 | Sections; section records; concept layer; hybrid search; topic results with meanings and subtopics (S, T, C); query parser (QP); evaluation set | Phase 1 |
| 3 | 0.7 | People, places, events; speakers; kinds of text; lists; outlines (I); all search shapes with timelines and maps (SH); build-time search agent and ready-made result pages (RP, B8) | Phase 2 records |
| 4 | 0.8 | Topic guides, passage guides, study questions (G); notes in search (N) | Phases 2–3 |
| 5 | later | Sense groups (W6), Septuagint links, character studies, reading plans, optional online search agent (A) | — |
| 6 | later stage | **Sermons (§8.4):** sermon search and summaries. First historical (public-domain) sermons, then modern pastors' summaries with links (SR6–SR9), starting with ministries that have open sharing policies | Phases 2–4 (topics, sections, guides) |

**First prototype:** run the section-record pass on Micah (7 chapters) and Romans (16 chapters), build a small `study.db`, and run the "righteousness" acceptance query on the desktop before starting the Android work.

## 17. Open questions

1. Which embedding model: size vs quality on the evaluation set, and licence for redistribution.
2. Should the Theographic CC BY-SA data live in a separate database so share-alike doesn't apply to the rest of `study.db`?
3. Who does the human spot checks (Q5), and which theological reviewers, if any?
4. Can the BSB alignment data be used offline, or do word taps go through the KJV alignment only?
5. Should topic guides be written for a general audience or configurable by study depth?
6. Is a small on-device model needed for the query parser (QP3), or is vocabulary matching enough? Decide from E9.
7. How are timelines shown where the order of events is disputed (e.g. Paul's visits to Jerusalem in Galatians vs Acts)?
8. Which modern pastors and ministries to include first, and which have terms or sharing policies that allow summaries and links?
9. Get legal advice before shipping summaries of copyrighted sermons beyond personal use (SR9)?
