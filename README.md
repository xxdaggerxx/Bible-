# Ink & Word — version 1.21.1

**Ink & Word** (called *Bible Study* until version 1.1.3) is a personal, fully offline Bible study app for Android tablets, designed around the Samsung Galaxy Tab S9 and S Pen. It works on any Android 10+ tablet.

## Install on your tablet (about 30 minutes the first time)

1. **Install Android Studio** on a Windows, Mac or Linux computer: https://developer.android.com/studio. Accept the defaults; it installs the Android SDK for you.
2. **Open this project.** Unzip `BibleStudy.zip`, start Android Studio, choose **Open**, and select the `BibleStudy` folder. The first sync downloads Gradle and the libraries, which takes 5–10 minutes. If Android Studio offers to upgrade the Android Gradle Plugin, you can say *Not now*. The project builds as it is.
3. **Turn on developer mode on the Tab S9.**
   - Go to *Settings → About tablet → Software information* and tap **Build number** 7 times.
   - Then go to *Settings → Developer options* and turn on **USB debugging**.
4. **Connect the tablet** with a USB-C cable and tap **Allow** on the tablet's "Allow USB debugging?" prompt.
5. In Android Studio, pick your tablet in the device dropdown at the top and press the green **Run ▶** button. The app installs and opens. It stays installed like any other app.

**Build an APK file instead.** Use *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The file appears in `app/build/outputs/apk/debug/`. Copy it to the tablet and open it; Android will ask you to allow installing from that source.

## Building and updating the app (signing key)

Android only installs an update over an installed app if both are signed with the **same key**. This project ships a test signing key in the repository, so every build from any computer or cloud session is signed the same way and installs over the previous one, keeping your notes.

| File | What it is |
| --- | --- |
| `signing/biblestudy-release.jks` | The signing key (alias `biblestudy`) |
| `keystore.properties` | Its location and password, read automatically by the build |

> **This key is public.** It is committed to a public repository on purpose, because the app is only for testing. Anyone could sign an app that Android accepts as an update to yours. Don't use this key for anything you rely on. Before real use, make a new private key (see the last section) and reinstall once.

### Build a signed APK

On a computer with Android Studio or the Android SDK and JDK 17+:

```
./gradlew assembleRelease
```

The signed file appears at `app/build/outputs/apk/release/app-release.apk` (about 38 MB; it runs on any Android 10+ tablet). Nothing else to set up: Gradle finds `keystore.properties` on its own. If the build prints "No release signing key found", that file is missing and the APK is signed with a throwaway debug key instead. Don't install that one over your real copy.

In Android Studio you can also use *Build → Generate Signed App Bundle / APK → APK*, choose *Choose existing…*, and pick `signing/biblestudy-release.jks` with the password from `keystore.properties`.

### Install or update on the tablet

1. Copy the APK to the tablet (USB cable, cloud drive or email) and tap it in *My Files*.
2. If Android warns about unknown sources, allow it for the app you opened the file from, go back, and tap **Install**.
3. To update, build a new APK the same way, with a higher `versionCode` in `app/build.gradle.kts`, and install it over the old one. Your notes stay.

If Android says the app conflicts with an existing package, the installed copy was signed with a different key. Back up first (*⋮ → Back up my notes…*), uninstall, install the new APK, then restore.

### Check which key an APK uses

```
apksigner verify --print-certs app-release.apk
```

For this key the line should read `CN=Bible Study, O=Personal`, with SHA-256 `70f5af93762f26149c355e7a39cd61f653afa5c17a2f7405ad475fea749efc32`. `CN=Android Debug` means the wrong key was used.

### Cloud builds without the files

If you ever remove the key files from the repository, set these environment variables instead: `BIBLESTUDY_KEYSTORE_BASE64` (the `.jks` file as base64, from `base64 -w0 signing/biblestudy-release.jks`), `BIBLESTUDY_KEYSTORE_PASSWORD`, and optionally `BIBLESTUDY_KEY_ALIAS` (default `biblestudy`).

### Switching to a private key later

```
keytool -genkeypair -storetype PKCS12 -keystore signing/biblestudy-release.jks \
  -alias biblestudy -keyalg RSA -keysize 4096 -validity 36500
```

Then update `keystore.properties` with the new password, add `keystore.properties`, `signing/` and `*.jks` back to `.gitignore`, and remove the old key from git. Because this changes the signing key, you must back up, uninstall and reinstall once. Keep a copy of the new key and its password somewhere safe; if you lose them you can't update the app without uninstalling it.

## New in version 1.21.1

- **Fixed:** margin sketches came apart when changing versions. Each stroke was tied to the verse where the pen touched down, and verses sit at different heights in each version, so the strokes of one drawing drifted apart. Now a margin stroke drawn on or within a few millimetres of a drawing, text box or picture in the margin joins it: tied to the same verse, so the drawing moves as one.
  - Drawings already apart: draw round them with the lasso and move them a little. Everything moved together in a margin is tied to one verse (the topmost), staying where it was.

## New in version 1.21.0

- **One cache for every online Bible.** YouVersion's Bibles, the ESV and the NLT are now kept exactly the same way: each chapter is kept once read, for as long as *Keep downloaded chapters* says, searches are kept, and *Clear downloaded text…* clears them all.
  - *Save for offline* now works for the ESV and NLT too, paced to each service's limits (the ESV about 75 minutes, as Crossway allows 60 requests a minute).
  - The ESV and NLT's old 500-verse code is removed; caches from earlier versions carry over.
- **Fixed:** cross-references showed the King James words while reading an online Bible, for verses whose chapter wasn't on the tablet yet. They now show that Bible's own words: "…" while the chapter downloads (once, then kept; up to 40 chapters a verse), then the text.
- Known gap: Crossway's ESV API terms allow an app to store at most 500 verses (or half a book); keeping more is at each key holder's risk unless Crossway agrees.

## New in version 1.20.0

- **The ESV and NLT are kept on the tablet like the other online Bibles.** Each chapter is kept once read, for as long as *Keep downloaded chapters* says (Always by default), so going back to a chapter no longer downloads it again. The 500-verse window is gone, now that users bring their own keys. Their searches are kept as well.
  - The 500-verse limit is still in the code behind one switch (`OnlineBible.limitPublishers`), off.
  - Verses kept for verse cards under the old limit become ordinary text: their chapters download whole when read.
- Known gaps:
  - **Licence:** Crossway's ESV API terms allow an app to store no more than 500 verses (or half a book). Each user's own key is bound by those terms; keeping more is at the key holder's risk, unless Crossway agrees otherwise. Tyndale's NLT API terms are for non-commercial use, 5,000 requests a day.
  - The ESV and NLT can't be saved for offline yet: a whole Bible is about 1,200 requests, which would run into Crossway's per-minute limits.

## New in version 1.19.0

- **Online Bibles' cache, under your control** (*Settings → Bibles*):
  - **Keep downloaded chapters:** 7, 30, 90 or 365 days, or *Always* (the default). Each chapter remembers when it was last read; chapters not read for that long are removed when the app starts and download again when read. Bibles saved for offline and verse cards' verses always stay.
  - **Clear downloaded text…** removes every online Bible's text (with a confirmation), and gives the space back.
- **Fewer trips to the servers:** YouVersion searches are kept too, so asking the same search again doesn't go online (kept as long as chapters are). ESV and NLT searches aren't kept: their results carry verse text, which would count toward the publishers' 500-verse limit.
- A Bible with every chapter on the tablet counts as saved for offline, so one saved before this version stays too.

## New in version 1.18.2

- **Verse cards keep their verses (ESV and NLT).** These versions keep only 500 verses on the tablet, letting older chapters go as you read. Now the verses of your verse cards are kept for good: they stay when their chapter is let go, so cards keep working, even offline.
  - A card downloads only its own verses, not its whole chapter, so it uses little of the 500.
  - Card verses count toward the 500. Up to 300 are kept, and less than half of any book, so there's always room to read. Past that, a card shows the verses saved with it.
  - When a card is deleted, its verses are let go.
- Known gap: a card's verses are found by the King James verse numbers, so a verse the ESV or NLT numbers differently (rare) may not be kept.

## New in version 1.18.1

- **Fixed:** some NLT (and ESV) verse cards showed only their reference. Only 500 verses of these versions are kept on the tablet, so a card's chapter could have been let go; the card waited for it and wasn't redrawn when it came. Cards now show the verses saved with them meanwhile, and turn live again when the chapter arrives. Each chapter is asked for once, so cards whose chapters push each other out no longer download them over and over.

## New in version 1.18.0

- **Link button in the header:** with two or more Bible panels, the link button now always shows in each panel's header. Tap it to link the panels (it turns coloured) and again to unlink them. It's no longer in the ⋮ menu.
- **Fit width removed:** the *Fit width* button is gone from the panel menu and from sketch pages. Double-tap with a finger still makes the page fill the panel's width. The ⋮ menu now only appears on narrow panels, for *About this book*.

## New in version 1.17.1

- **Fixed: verse cards in the wrong version.** Reported: after switching to the NIV and back, a new verse card sometimes came out in the earlier version. A sketch page opened beside the text (or in its own tab) kept the version its panel had when it was opened, and cards added on it used that. A sketch page now uses the version of the Bible panel beside it, or, on its own, the version last chosen for a Bible panel (remembered between sessions). The same applies to search and the verse pop-up started from a sketch page.

## New in version 1.17

- **The NLT**, from Tyndale's NLT API (api.nlt.to), as YouVersion doesn't offer it. In *Settings → Bibles → Add an online Bible…*, working like the ESV: each verse from the reply's `verse_export` elements, paragraphs and poetry lines, the words of Jesus, "LORD" in capitals, footnotes, titles and subheadings left out; word studies, red letters, hard words, Tyndale's search (with text) for chapters not on the tablet, and the NLT copyright notice under every chapter.
- **Tyndale's terms:** non-commercial use, at most 500 verses a request and 5,000 requests a day. They say nothing about storage, but the NLT copyright statement allows quoting 500 verses, so the NLT keeps the same 500-verse rolling window as the ESV, with no *Save for offline*. If Tyndale confirms the whole text may be kept, this can be relaxed.
- **The key** comes from `youversion.properties` (`nlt=…`, not committed) or `NLT_KEY`; a build without it shows an NLT key field in Settings. The released APK has it built in.
- **Fixed in the repository:** the 1.16.0 release commit left out the ESV source code (the APK had it); it was committed separately.

## New in version 1.16

- **The ESV**, from Crossway's ESV API (api.esv.org), as YouVersion doesn't offer it to this key. It's in *Settings → Bibles → Add an online Bible…* and works like the YouVersion Bibles: verses, paragraphs, poetry lines and the words of Christ from Crossway's HTML; word studies, red letters, hard words, search (Crossway's search, with its text, for chapters not on the tablet) and the standard ESV copyright notice under every chapter.
- **Crossway's limits are kept:** non-commercial use, and no more than 500 verses or half of any book (whichever is less) stored on the tablet. The ESV keeps a rolling window of the chapters read last; older ones (with their search entries, word tags and red letters) are dropped and download again when reopened. Only the next chapter is prefetched, and *Save for offline* isn't offered for it. A one-chapter book (Jude, Philemon) is kept whole while it's being read.
- **The key** comes, like the YouVersion key, from `youversion.properties` (`esv=…`, not committed) or the `ESV_KEY` environment variable; a build without it shows an ESV key field in *Settings → Bibles*. The released APK has it built in.
- Fixed: online Bibles' "read last" order now uses a counter rather than the clock.

## New in version 1.15

- **Online Bibles from YouVersion** (BIB-12): versions that can't be built in, such as the NIV, NASB 1995 and 2020, Amplified, NIrV, NIV (Anglicised), LSV, Geneva and more, read through the [YouVersion Platform](https://platform.youversion.com/) Bible API. *Settings → Bibles → Add an online Bible…* lists what the app key may read.
  - **Every feature works with them.** Chapters come as HTML and are turned into USFM for the same reader as imported Bibles, so verses, paragraphs and poetry lines, and the words of Jesus come through. Each chapter gets word tags (for word studies), red letters and paragraphs as it arrives, the way imported Bibles do. Ink, highlights, notes, hard words, search, compare versions and the verse pop-up then work as for the built-in versions.
  - **Caching:** each chapter is stored on the tablet the first time it's read, in a database like an imported Bible's (`files/bibles/yv-<id>.db`), and read from there afterwards, also offline. The next two chapters download in the background. *Save for offline* downloads the whole Bible, with progress and *Stop*. With no internet, a chapter not saved yet shows a message and appears by itself when the tablet is back online. Busy replies (429) wait and try again.
  - **Search** covers the saved chapters, plus YouVersion's verse search for the rest when online.
  - **Copyright:** each chapter ends with the version's copyright line from YouVersion; it's also in the Bibles list.
  - Backups keep the list of online Bibles but not their text, which downloads again.
- **The app key** is not in the repository (it is public). Builds read it from `youversion.properties` in the project root (`key=…`, ignored by git) or the `YOUVERSION_KEY` environment variable, into `BuildConfig`. A build without it asks for a key in *Settings → Bibles*. The released APK has the key built in, as a YouVersion app key is meant to be.
- Cross-reference previews in an online Bible use saved chapters (else the KJV), so tapping a verse doesn't download twenty chapters.
- Known gaps: English Bibles only; the section headings shown are still the BSB's; footnotes are left out; *Save for offline* fetches about 1,189 chapters one by one, which takes several minutes; check each version's licence terms before sharing exported pages.

## New in version 1.14

- **Chapter at a glance** (STD-22): a slim bar under each Bible panel's header says in one line what's happening in the chapter. Tap it for *Who* and *Where* (from Names & places, tap to read about them), *Where it fits* in the Bible's story, and the *Key verse*. Written by AI for all 1,189 chapters (`tools/glance/`, built by `tools/build_glance.py`; about 180 KB), traditional view only. Off in *Settings → Reading*.
- **Hard words explained** (STD-23): a faint dotted line under hard words, the first time each comes in a chapter. Bible words (*propitiation*, *Pharisee*, *cubit*) are marked in every version; old English words (*wist*, *froward*, *corn*) in the KJV only. Tapping one opens the verse pop-up with the meaning in one line at the top and *Read more* to its Bible dictionary article. About 1,500 words in 940 entries, written by AI (`tools/hardwords/`, built by `tools/build_hardwords.py`; 41 KB). Off in *Settings → Reading*.
- **Renamed:** the AI commentary is now the *Ink & Word AI Commentary*.
- **Margin or words:** a stroke is now kept where most of it lies. One that starts on the words but runs mostly into the margin is margin ink (every version), and the other way round. A shape snapped by holding the pen stays where it was started, so arrows from margin notes still reach across the words.
- **Tidier book picker:** the ⓘ buttons are gone from the *Choose a book* grid. Book introductions open from ⓘ in the panel header, or from *About this book* at the top of a book's chapter list.
- **Fixed:** a linked commentary followed a verse tapped earlier instead of the page (at Matthew 25:1 it stayed on verse 29). It now follows the page once you scroll.
- **Size:** the APK is 100.6 MB, about 4.2 MB under GitHub's 100 MiB limit.
- Known gaps: the glossary only explains single words, not phrases like *gird up your loins*; a few glossary words (*trinity*, *incarnation*) aren't in any of the three Bibles, so they are never marked; *Rabbi* and similar words are marked in every version, even where the version explains them in the text.

## New in version 1.13

- **Imported Bibles work with every feature.** *Import a Bible…* now gives the version:
  - **Word studies.** The files publishers give out have no Strong's numbers, so the app works them out when importing: each English word is matched to a Hebrew or Greek word of the same verse, using the same word in the KJV, BSB and WEB, how those three translate each original word across the Bible (a table in `study.db`, made by `tools/build_glosses.py`), and the original word's English meaning. It takes a few seconds. Small words like "the" are only tagged when the match is a usual one. Tested on the BSB and WEB (each tagged from the other two), about 9 in 10 content words are tagged and about 9 in 10 of those agree with the published tags. In the ESV and NLT test passages, almost every important word is tagged (John 3:16 "loved" → G25 agapaō, Psalm 23:1 "shepherd" → H7462).
  - **Words of Jesus in red**, from the file's `\wj` (USFM) or `<q who="Jesus">` (OSIS) marks; for a file without them, Jesus' quotations are found where the WEB marks him speaking.
  - **Paragraphs and poetry lines**, from the file's paragraph markers (or the BSB's when it has none).
  - Verses printed together ("\v 1-2") are kept under the first verse and tagged against both.
  - Bibles imported before this get all of it in the background the next time the app starts, and when restored from an older backup.
- The import tests use short ESV and NLT passages (31 verses each, within the publishers' quotation allowances).
- **Size:** the word-matching table adds about 0.6 MB to the APK, now 100.5 MB, about 4.3 MB under GitHub's 100 MiB (104.9 MB) limit for a file in the repository (see 1.11).
- **Fixed:** screens that load in the background (word studies, reading stats, the family tree, the Hebrew and Greek view, the AI chat) now always hand their results to the screen on the main thread, so a window can't be left showing *Loading…*.

## New in version 1.12

- **The AI Commentary comes first,** to help readers understand the Bible easily. It's first in every commentary menu and opens to start with, in commentary panels and in the verse pop-up (everyone is switched to it once, on updating; choose another any time).
- **Tapping a verse opens on its commentary.** The pop-up's *Commentary* tab is now first and open by default; *Cross-references* is beside it. The app still remembers the tab you used last.
- **More room for the commentary:** a verse with no typed note shows a small *Add a note* button instead of an empty box. Notes you've written still show straight away.
- The AI Commentary's credit line in the pop-up now says it was written by AI, instead of "public domain".

## New in version 1.11

- **Jump back several steps:** hold the toolbar's ← (or →) for a list of the screens it goes to, nearest first, such as *Psalms 23 and Commentary* or *Romans 8 · 2 tabs*. Tap one to go straight there.
- **Start fresh:** *Panels → Start fresh* leaves one tab with one Bible panel, at the passage you're reading. ← undoes it.
- **Simpler panel headers:** *Fit width* and *Link panels* moved into the panel's ⋮ menu. While two panels are linked, a link button shows in the header to unlink them.
- **Size warning:** the APK is 99.9 MB, about 5 MB under GitHub's 100 MiB (104.9 MB) limit for a file in the repository. It's still in `releases/`: these build sessions can't create GitHub Releases, so the next release that adds much data needs the APK uploaded to a release by hand, or the app made smaller.

## New in version 1.10

- **AI Commentary: a plain-English note on every verse,** the twelfth choice in the commentary menu. It was written by AI when the app was built, so it works offline like the other commentaries.
  - **Grounded in sources.** Each note draws on the eleven bundled commentaries and on a fixed list of trusted websites from many Protestant traditions: Reformed, Baptist, Methodist and Wesleyan, Pentecostal and charismatic (Enduring Word, Assemblies of God, Pneuma Review, Sam Storms), Lutheran, Anglican and evangelical. Every point names its sources. A check removed any source that couldn't be confirmed: a bundled commentary had to have a note on that verse, and a web page had to have come up in the research.
  - **The traditional reading first,** in short, simple sentences for lay readers.
  - **Where Christians differ:** on verses where churches disagree (baptism, spiritual gifts, election and so on), each tradition's view in a line, fairly and without taking sides.
  - **Other views:** where well known, modern scholarship, Catholic and Orthodox readings, and debated popular teaching, labelled and kept apart from the main note.
  - It shows in the verse pop-up's *Commentary* tab too, when chosen there.
- **How it's made:** `tools/build_ai_commentary.py` researches each chapter (long chapters in parts) with web search limited to the trusted sites, writes the notes, checks the sources and packs them into `assets/commentaries/ai.db.xz` (about 2.6 MB). The notes and research are kept in `tools/ai_commentary/`. The first part (about 5,250 verses) was written by Claude Opus 5.5 through the Claude Batch API; the rest by Gemini 3.8 Flash, with DeepSeek V4 Pro doing the research, through OpenRouter, which cost far less. Each part's file names the model that wrote it.
- The APK is 99.9 MB, just under GitHub's 100 MiB limit for one file (see 1.11).
- Known gaps: the sources are shown as names and website names, not links; ten notes have no source that passed the check and show none; notes were checked by script and spot-read, not read in full by a person.

## New in version 1.9

- **Back and Forward for the whole screen.** One pair of arrows, at the far left of the toolbar, replaces the arrows in each panel's header. Back undoes the last change to what's on screen: a jump to a passage, a panel opened, closed or changed to another view, a tab opened, switched or closed. Forward redoes it. The tablet's Back gesture is the same as Back.
- **Fixed:** switching back to a tab showed the top of its chapter. Each tab's panels now come back exactly where they were scrolled to.

## New in version 1.8

- **Bookmarks are back.** Tap a verse, then *Bookmark*: a red ribbon shows beside the verse. Tap the chapter name, then *Bookmarks*, to see them all, newest first, with the start of each verse. Tap one to go there, or × to take it off. (Bookmarks from before 0.9 became highlights tagged "bookmark" then, and stay highlights.)
- **Recently read.** Tap the chapter name, then *Recently read*: the chapters you've read lately, newest first, each with the verse you were at and when. Tap one to pick up where you left off. A chapter is added once it's been in front of you for a few seconds; the list keeps 50 and stays on this tablet.
- **Fixed:** *New tab* in a link's pop-up opened at the wrong place after scrolling a linked commentary (for example Matthew 25 instead of Matthew 13:12). It now opens at the passage.

## New in version 1.7.3

- **Try again in the AI chat.** When a question fails (no internet, the service busy, a bad moment) or finds nothing, a *Try again* button under the reply asks it once more, with the same passages, and replaces the reply. It's also under the last answer for a fresh one, and under a question left unanswered if the app was closed while waiting.

## New in version 1.7.2

- **The AI chat has its own little window.** The chat bubble opens a small chat window over the text, above the bubble, instead of taking over a panel. Keep reading and tapping verses while it's open; close it with × or the bubble. The panel button at its top moves it beside the text, for anyone who prefers that.

## New in version 1.7.1

- **Commentary in the verse pop-up.** Next to *Cross-references* there's a *Commentary* tab: what Matthew Henry (or whichever commentary you choose there) says on the verse you tapped. *Whole chapter beside the text* opens the full commentary panel at that verse. The tab and commentary you used last are remembered.
- Where a commentary's note runs on past its heading (Matthew Henry's Concise has a few, such as John 3:1–8 also covering 9–21), the pop-up shows the full range.

## New in version 1.7

- **Real writing sounds.** The pen sounds like a pencil, the highlighter like a marker, the eraser like soft drawing: recordings from Pixabay (freesound_community), made into seamless loops by `tools/build_sounds.py`. They still follow the pen: louder and a little faster when you write fast or press hard, silent when you stop. Each stroke starts at a different point in the loop. Adds about 0.8 MB.
- **Tapping a verse opens the pop-up again,** as before 1.5: simpler for everyday use. The Verse details panel is still there: turn on *Settings → Verse details in a panel*, or choose *Verse details* from a panel's menu.
- **The panel menu in groups:** *Reading*, *This verse*, *Study*, and *Notes, search and AI*, instead of one long list. The panels button in the toolbar uses the same headings.
- Next: offline smart search (all versions at once, Hebrew and Greek word families, Nave's topics).

## New in version 1.6.2

- **AI chat finds answers again.** Many questions came back with no answer. The new web search tool filtered results through a code step first, so answers came back without the citations the app requires, and were dropped. The chat now searches directly, so every statement can cite its page.
- **Searches the whole web,** like a search summary, with your trusted sites searched first. *Settings → AI chat → Only search my sites* limits it to your list, as before.
- **Answers read like a search overview:** a short overview first, then the main points, then the key verses.
- **When nothing is found,** the chat shows what it searched for.

## New in version 1.6.1

- **Fixed:** the AI chat failed on the tablet with "JsonMissing cannot be serialized". Shrinking the release build (R8) removed parts of the Claude API library it needs. New rules in `app/proguard-rules.pro` keep them, and `tools/r8check/check.sh` checks the shrunk library against the unshrunk one.
- **Copy** any chat message (with its sources), or hold a finger on the words to select part of it.
- **Edit** an earlier question, with its passages, and send it again; it replaces that question and the answers after it.
- **Every answer names its verses**, listed under *Verses* as links. If the AI leaves them out, the app asks it once more for the verses its sources give. Answers without citations are still never shown.

## New in version 1.6

- **AI chat (online):** a chat bubble at the bottom right opens a chat beside the text. Ask a question and the AI (Claude, with web search) searches only a list of trusted sites you choose and sums up what they say, with numbered sources you can open. It may not answer from its own knowledge: if your sites have nothing on it, it says so. Bible references in answers are links.
- **Ask AI** sends selected words, a highlight, or a verse from *Verse details* into the chat, to ask about them.
- **Settings → AI chat (online):** your Claude API key (kept on the tablet only, not in backups), the sites to search, and a switch that turns the chat off so the app never goes online.
- **This is the app's first online feature since 1.1.2.** Everything else still works offline. Each question costs a few cents on your Claude account.
- Known gaps: answers can't be written on yet; the bubble sits over the bottom-right corner of the page.

## New in version 1.5

- **Verse details in a panel:** tapping a verse opens its details (the verse with its words to study, *Compare versions*, *Hebrew/Greek*, people and places, your typed note and cross-references) in a panel beside the text instead of a pop-up window. The panel follows each verse you tap. A word study opened from it shows in the same panel, with *Back to the verse*.
- When both panels are already in use, the details open in the window as before. *Settings → Verse details in a panel* turns the panel off.
- Known gap: the panel menu now lists 14 views and scrolls on smaller screens.

## New in version 1.4

- **About the book as a panel view:** the book's introduction beside the text, following the book you're reading. You can write on it.
- **Lasso on study views:** draw round writing on an article or commentary, then *Colour*, *Layer* or *Delete*. Writing sounds now play on study views too.
- **New tab from anywhere:** *New tab* in every passage pop-over (links, cross-references, references in notes), and holding a finger on a search result opens it in a new tab.
- **Drag tabs** along the strip to reorder them (hold, then drag). Holding and letting go still opens the tab's menu.

## New in version 1.3

- **Eleven commentaries.** Besides Matthew Henry's Concise: Matthew Henry's Complete, Jamieson-Fausset-Brown, Wesley's Notes, the Geneva Bible notes, Barnes' Notes (New Testament), Adam Clarke, Keil & Delitzsch (Old Testament), Robertson's Word Pictures (New Testament), Calvin's Commentaries and Spurgeon's Treasury of David (Psalms). Choose one from the menu at the top of the commentary panel. Each is unpacked the first time it's opened (a few seconds).
- **About this commentary:** the ⓘ gives the author, dates, background, what kind of commentary it is and what it's best for.
- **Commentary linked to the Bible, both ways:** scroll the Bible and the commentary keeps the note on the verse at the top in view; scroll the commentary and the Bible follows. The link button turns it off.
- **Two commentaries side by side,** each panel with its own.
- **Three more panel views:** *Compare versions*, *Hebrew/Greek* and *Word study*. They follow the verse you tap; a Word study panel shows the word you tap.

## New in version 1.2

- **Tabs, like a browser.** *Panels → New tab*, or *Open in new tab* from a panel's menu. Once there are two tabs, a strip under the toolbar shows them: tap to switch, **+** for another, hold a finger on one to rename, move or close it. Each tab keeps its own panels, passages and arrangement, and tabs are kept when the app closes.
- **Any panel shows anything.** A tab has one or two panels, side by side or top and bottom. The button at the top left of each panel (or a study view's name) picks what it shows: the Bible, or Search, Cross-references, My notes, Dictionary, Topics, Commentary, Names & places or Sketch pages. The same menu adds a panel beside, switches *Top and bottom* / *Side by side*, opens the panel in a new tab, or closes it. Double-tap the divider to make the panels equal.
  - Study views follow the Bible panel beside them. *Keep on this passage* pins one where it is.
  - Two study views can share a tab (e.g. the dictionary above topics); they stay on the passage you were reading.
  - Two Bible panels can still be linked to scroll together.
- **Write on study views.** The pen, highlighter and eraser work on dictionary articles, topics, the commentary, names & places and cross-references, over the text (no margins). Writing belongs to the article, comes back wherever it's opened, and moves with its words when a panel is resized. The highlighter snaps to whole words. Undo, layers and backups include it.
- **Saved layouts keep every tab.** Opening one replaces your tabs. Layouts saved before 1.2 open as tabs of up to two panels.
- **Your painted icon:** the open Bible with a quill now on a blue-to-gold background, replacing the drawn icon from 1.1.3. It fits round, square and rounded launcher shapes.
- **Two panels per tab at most.** If you had three panels, or two panels and the study pane, the extra one moves to a second tab when you update, so nothing is lost.

## New in version 1.1.3

- **A new name: Ink & Word.** The app was called *Bible Study*. The new name shows on the home screen, in *Settings → About* and in the Credits window, and exported pages now say "Exported from Ink & Word". Backups you save yourself are named `ink-and-word-backup-…zip`.
- **A new icon:** an open Bible with a quill writing on its right page, in cream and gold on deep ink blue.
- Nothing else changes. It installs over 1.1.2 and keeps your notes, and old backups (including automatic ones) still restore.

## New in version 1.1.2

- **Handwriting reading is removed.** *Read my handwriting* (in Settings) and *Convert to text* (in the lasso bar) are gone, and *Search → My notes* searches typed notes and text boxes only. Your handwriting itself is untouched. Without Google's handwriting library the app is one 38 MB download that runs on any Android 10+ tablet, and it no longer asks for internet access at all.
- **Fixed:** the app could occasionally lose count of chapters still loading, which stopped margin notes from getting extra room below their verse (expand to fit).

## New in version 1.1.1

- **Sketch pages have no edges.** A page grows with what's on it, keeping at least a page's width of room to the right and a page's height below. Move around with a finger, and pinch out until the whole page is in view. *More space below* is gone, since the page grows by itself. Paper lines, grids and dots are drawn only where you're looking, so big pages stay quick.

## New in version 1.1

- **Writing sounds.** A soft pen-on-paper sound follows your writing. It gets louder and brighter when you write fast or press hard, and stops when the pen rests or lifts. Each tool has its own sound:
  - the pen, a fine scratch;
  - the highlighter, a felt-tip swish;
  - the eraser, a rubbing.

  The sound is made on the tablet as you write, not from recordings. *Settings → Pen & ink → Writing sounds* turns it off and sets the volume. It plays through media volume.
- **Pen hover.** With the S Pen just above the screen, a small mark shows where it will touch: a dot the size of the pen, a bar for the highlighter, a circle for the eraser. Tilt shading isn't included, because the Tab S9's S Pen doesn't report tilt.
- **Verse cards work like the Bible page.**
  - Tap a verse on a card for the verse window: word study, *Compare versions*, *Hebrew/Greek*, notes and cross-references.
  - Tap its reference for the passage.
  - Words of Jesus show in red.
  - Hold a finger on a card for its bar: switch version, *Copy*, *Share*, size, background, delete.
  - A highlight on a card is the Bible's own highlight on that verse. It shows on the card, in the Bible and in every version, and erasing it anywhere erases it everywhere.
  - Existing cards, including those on the ready-made pages, work this way already.
- **Highlights snap in text boxes.** The highlighter snaps to the words of a text box; the eraser takes them out.
- **Your handwriting can be searched** (removed again in 1.1.2). Switch on *Settings → Pen & ink → Read my handwriting*. The first time, it downloads Google's handwriting model once (about 20 MB, Wi-Fi). After that it reads on the tablet and nothing is sent anywhere. This is the only time the app uses the internet.
  - *Search → My notes* then finds handwritten words in the margins and on sketch pages.
  - Lasso some handwriting and tap *Convert to text* to make it a text box. Undo brings the ink back.
- **Full-screen margin notes.** Tap a verse, then *Write full screen*, for a whole page about that verse. It shows shrunk to fit beside the verse; tap it to open it again.
- **Five more ready-made pages:**
  - *Paul's missionary journeys* and *The Exodus and the wilderness*, drawn on the offline map;
  - *The life of Christ*, with the four Gospels side by side;
  - *The twelve tribes*;
  - *Solomon's and Herod's temples*.

  If you already have the first four, only the new ones are added.

Your notes database is upgraded the first time 1.1 opens.

## New in version 1.0.2

- **Any sketch page beside the text.** The study pane has a new choice, *Sketch pages*. Open it from the panels button (*Beside the text: Sketch pages*) or the pane's menu. It lists your pages and the ready-made ones. Tap one and it opens in a panel beside the Bible, so you can read and draw side by side. Tapping another swaps it into the same panel.
- **Erasing a highlight erases it in every version.** Rub out a highlight in one version and it's gone from all of them, wherever it was made. With the *Partial* eraser, only the verse you erase over goes. One undo brings it back everywhere.

## New in version 1.0.1

- **The ready-made sketch pages come with the app.** *The feasts of Israel*, *The tabernacle*, *The kings of Israel and Judah* and *From Adam to Jesus* are already in *My notes → Sketch pages → Ready-made pages* when the app is installed, or on the first start after updating. You don't make them yourself any more. They're ordinary pages you can write on, and *Put back deleted ready-made pages* restores any you delete.
- **Sketch pages can stand on their own.** A sketch page no longer has to belong to a verse. Turn off *Link to …* when making one, or use *Unlink* in its ⋮ menu. *Link to a passage…* links it again. Pages on their own open from *My notes → Sketch pages*.
- *New sketch page* no longer has *Start from*; the ready-made pages are already there.

## New in version 1.0

Version 1.0 finishes the planned features. It adds Hebrew and Greek word by word, ready-made sketch pages, family trees and a Help guide.

- **Help.** *⋮ → Help* explains every feature in plain words, one topic at a time, with a search box. The guide lives in `app/src/main/assets/help/guide.md` and is updated with every change. A test fails if a menu item or feature is missing from it.
- **Hebrew and Greek word by word.** In a verse's window, tap *Hebrew* (Old Testament) or *Greek* (New Testament). Each word card shows:
  - the original word, how it sounds and what it means in this verse;
  - what kind of word it is.

  Tap a card for its full grammar in plain words and a *Word study*. Hebrew runs right to left. Greek words found in only some manuscripts are paler, with a note on whether the KJV's text has them. The data is STEPBible's TAHOT and TAGNT, adding about 6 MB to the app.
- **Words of Jesus in red.** *Settings → Reading → Words of Jesus in red*:
  - The KJV and WEB use their own red-letter markings.
  - The BSB has none, so its quotations are coloured where the WEB marks Jesus speaking. Other speakers in the same verse stay black.
- **Ready-made sketch pages** (in 1.0.1 these come with the app, in *My notes → Sketch pages*):
  - *The feasts of Israel*: the seven feasts of Leviticus 23 plus Purim and Hanukkah, on a year line, each with how it points to Christ.
  - *The tabernacle*: a scale plan with numbered furniture, what each piece means, and verse cards.
  - *The kings of Israel and Judah*: a timeline from 931 to 586 BC with Edwin Thiele's dates, good and evil kings coloured, the prophets of the time, and a link to each king's story.
  - *From Adam to Jesus*: the line of descent through Genesis, Ruth and Matthew.

  They're built from ordinary ink, text boxes and verse cards, so everything can be written on, moved or changed.
- **Family trees.** *Family tree* on a person in Names & places shows:
  - grandparents and parents;
  - brothers and sisters;
  - whom they married, and their children.

  Tap anyone to see their family. *Copy to sketch page* draws the tree on a sketch page.
- **Word differences.**
  - *Compare versions* in the verse window lightly marks the words that differ from the version you're reading.
  - *Settings → Reading → Mark word differences* does the same for two panels showing different versions. It's off by default because KJV and modern wording differ almost everywhere.
- **Layer views.** In Layers, show the layers you want and tap *Save what’s shown…* to name the view, e.g. *Sermon prep*. Tap its name later to switch back with one tap.
- **Export one layer.** A layer's ⋮ menu can export this chapter with only that layer's notes, as a PDF.
- **Settings search.** Type in the box at the top of Settings to find an option.
- **Backups include imported Bibles**, and restoring brings them back.
- **Sketch pages can be re-linked:** the page's ⋮ menu has *Link to another passage…*.

## New in version 0.9

- **Sketch pages.** Use *Insert → Sketch page…* to make a full page (blank, lined, grid or dotted) for timelines, diagrams, maps and sketch notes.
  - Every pen tool works on it, plus shapes, the lasso, layers, undo, pictures and text boxes.
  - Each page is linked to the passage you were reading and opens from a small badge in that passage's margin.
  - *My notes → Sketch pages* lists them all.
  - The page's ⋮ menu changes the paper, adds more space below, renames it or deletes it.
- **Verse cards and person or place cards.** *Insert → Verse card…*: type a reference like "John 3:16-18" and the verses land as a card whose reference is a link. *Person or place card…* does the same for anyone or anywhere in the Bible. Cards work in the margins too.
- **Names and places.** A new study pane choice, *Names & places*, covers about 4,000 people and places.
  - **People:** who they were, their family (parents, brothers and sisters, spouse, children; each one opens) and every verse that mentions them.
  - **Places:** a description, the region and a dot on a small offline map you can zoom and drag.
  - **Where to open it:** the verse window lists the people and places in a verse, and a word study on a name offers *About …*.
- **Reading stats** (*⋮ → Reading stats*):
  - how much of the Bible you've read, overall, by Testament and by book;
  - time this week and in all, and days in a row;
  - your last 30 days;
  - your most-read chapters and books;
  - a grid of every chapter, shaded by how often you've read it.

  Read chapters are also tinted in the book picker. A chapter counts as read after a minute in it, scrolled through most of the way. Time pauses after two minutes without a touch. It's all kept on the tablet, and Settings can turn counting off or clear it.
- **Reading layout.** *Settings → Reading* now offers *Paragraphs* (instead of one verse per line) and *Verse numbers* on or off. Ink and highlights stay on their words.
- **Margin options.**
  - *Expand to fit* opens space under a verse when its margin notes are taller than it.
  - *Margins in every panel* can be turned off so only the first Bible panel has margins.
- **Bibles.** *Settings → Bibles* lists each version with its size and copyright.
  - *Import a Bible…* adds a version from USFM files (or a .zip of them), OSIS XML, or this app's own database.
  - Imported versions work everywhere the built-in ones do (except word studies) and can be removed.
  - This is how NIV or NLT files could be added once permission is granted. Only import versions you have the right to use.
- **Exports** now carry the version's copyright line on every page.
- **Bookmarks are replaced by highlights.**
  - The *Bookmark* button, the *Bookmarks* tab and the red ribbons are gone.
  - Each bookmark you had becomes a yellow highlight over its whole verse in the KJV, tagged "bookmark" and with its folder's name as a tag. Filter by those tags in *My notes → Highlights*.
  - The Highlights list now shows each highlight's whole verse, with the highlighted words marked in its colour.
  - The study pane's *My notes* lists the chapter's highlighted verses.

Your notes database is upgraded the first time 0.9 opens. Backups include sketch pages and reading stats. (From 1.0, backups also include imported Bibles.)

## New in version 0.8

Version 0.8 adds offline study tools: Hebrew and Greek word studies, a Bible dictionary, a topical index and a commentary.

- **Word studies.** Tap a word with your finger, then *Word study* in the verse window. You can also tap any word in the verse window's text. The word study shows:
  - the Hebrew or Greek word and how it's pronounced;
  - its meaning (Strong's) and how the KJV translates it;
  - every verse that uses it in the version you're reading, counted by book.

  Tap a verse in the list to go there. Works in the KJV, BSB and WEB.
- **Search by Strong's number.** Type a number like `G26` (agapē, love) or `H2617` (chesed, lovingkindness) in Search to find every verse using that word.
- **One menu for the study pane.** The study pane's choices are now one menu at its top:
  - Search, Cross-references and My notes, as before;
  - **Dictionary** (Easton's): it suggests the people and places in the chapter you're reading;
  - **Topics** (Nave's): it suggests the topics for the verse you're on;
  - **Commentary** (Matthew Henry's Concise): it follows the chapter and scrolls to the verse.

  References in all three are links.
- **Related passages.** Under a verse's cross-references:
  - its topics;
  - parallel accounts (other Gospels, Kings and Chronicles);
  - passages listed under the same topics.
- **Layers:** each layer's ⋮ menu now sets its colour and opacity (100%, 75%, 50% or 25%). Moving the layer up or down, renaming it and deleting it moved into the same menu to keep the rows short.
- **Fixes to 0.7's rough edges:**
  - On narrow portrait screens the margin drawer now slides over the text instead of pushing the page aside, and you can write in it there.
  - The crop window shows a turned picture the way it's turned.
  - A lasso selection can be rotated: drag the round handle above it. It settles on 15° steps, and pictures turn when it's a quarter turn.

The app is about 10 MB bigger because of the study library. Your notes database is upgraded the first time 0.8 opens.

## New in version 0.7

- **Faster ink.** Pen strokes go straight to the screen through a front-buffered layer, as in Samsung Notes. Turn it off in *Settings → Pen & ink → Fast ink* if anything looks wrong.
- **Settings.** *⋮ → Settings* holds every option in groups: Reading, Pen & ink, Highlights, Margins & panels, Verse window, Backup and About, plus *Reset settings to defaults*. The toolbar keeps only what you use while writing. Each tool's colours and sizes open from one button.
- **Text boxes in the margins.** Use *Insert → Text box*, then type. Bible references you type, like "Rom 5:8" or "John 3:16-18", become links as you go. Tap a box to move it, change its size or colour, edit it or delete it.
- **Resize with the lasso.** Drag the corner handle of a lasso selection to make ink, pictures and text boxes bigger or smaller.
- **Read mode.** The lock button at the left of the toolbar stops the pen marking the page. The pen then scrolls and taps like a finger.
- **Underline.** The highlighter's menu has *Highlight* and *Underline*. An underline snaps to the words like a highlight does.
- **Shapes.** Draw a line, arrow, box or circle and hold the pen still at the end. It snaps to a clean shape. Ink can cross from the margin over the text.
- **Colour meanings and tags.** Give each highlight colour a meaning (*Settings → Highlights → Colour meanings*, e.g. yellow = promises). Notes and highlights can carry tags. *My notes* (the notes button) lists all notes, highlights and bookmarks, and filters by tag or meaning.
- **Cross-reference pop-overs.** Tapping a cross-reference in a verse's window or the study pane shows the passage in a pop-over instead of leaving your place.
- **Turn and crop pictures.** Select a margin picture with the Select tool, then *Turn* or *Crop*.
- **Saved layouts.** *Panels → Save this layout…* keeps the open panels, their passages and versions, and the study pane. Open it again from the same menu. (From 1.2 it keeps every tab.)
- **Automatic backups.** *Settings → Backup → Automatic backup* makes a backup daily or weekly when you leave the app. It keeps the newest 5. Backups go to app storage, or to a folder you choose, such as a synced Google Drive or OneDrive folder.
- **Export a chapter.** *⋮ → Export chapter as PDF…* or *as picture…* saves the chapter with your ink, highlights, pictures and text boxes, to share or print.

Your notes database is upgraded the first time 0.7 opens. Back up first if you like (*⋮ → Back up my notes…*).

## New in version 0.6

- **Highlights list.** Next to Bookmarks there's now a *Highlights* tab. It lists every highlight in Bible order with its words, colour, version and layer. You can filter by colour or layer, tap one to go there, or remove it.
- **Edit a highlight.** Hold a finger on highlighted words to select the whole highlight. The bar then offers other colours and *Remove highlight* instead of *Highlight*. Both can be undone.
- **Highlights in every version.** A highlight shows in the other translations too, over the whole verses it covers and a shade lighter. Holding a finger on it there changes the original. Turn it off in *More → Highlights in every version*.
- **Compare versions.** In a verse's window (tap a verse), *Compare versions* shows it in the KJV, BSB and WEB stacked together. Tap one to read in it.
- **Book introductions.** Every book has a study introduction covering:
  - author, date, place, first readers and type of writing
  - historical background, purpose and themes
  - an outline you can tap to jump to a section
  - key people and places
  - key verses and connections, as links

  Open one from the ⓘ on each book in *Choose a book*, from *About this book* on the chapter screen, or from the ⓘ in the reader's header. Authorship and dates follow the traditional view.
- **Bookmark folders.** In *Bookmarks*, make folders and move bookmarks into them with the folder button. You can also rename a folder, or delete it (its bookmarks are kept).
- **Notes on several verses.** In a verse's window, the − and + beside "Note on …" make the note cover a range such as John 3:16–18. A line beside the verses shows its extent.
- **Better search.**
  - `-word` leaves out verses with that word, for example `love -world`.
  - Results are grouped by book with counts, and you can tap a book to see just its verses.
  - Each result has *Open beside*.
  - *Keep results beside the text* moves the results into a side pane.
- **Study pane.** The panels button in the toolbar opens a pane beside the text. It can show:
  - **Search** results that stay while you read.
  - **Cross-references**, following the verse at the top of the page, or a verse you tapped.
  - **My notes**: the notes and bookmarks in the chapter you're reading.
- **Up to three Bible panels** on large screens in landscape. Add them from the panels button and drag the dividers to resize. Narrow panels move Back/Forward, *About this book* and *Fit width* into a ⋮ menu.
- **More ways to add images.** The image button offers the gallery, the camera, files, or a picture on the clipboard.
- **Margin drawers on narrow screens.** On small tablets in portrait, the text fills the width. Tabs at the edges slide the margins into view.
- **Text font.** Under *More*, choose Gentium Book, Serif or Sans-serif. Ink on the words moves with its words when the font changes.
- Tested on small (8") and large (14.6") tablet sizes as well as the Tab S9.

Your notes database is upgraded automatically on first launch; existing notes, bookmarks and ink are kept.

## New in version 0.5

- **Linked split view.** In split view, tap the link button in either panel's header.
  - Scrolling one panel keeps the other on the **same verse**, even when they show different versions whose lines wrap differently. When one panel scrolls into the next chapter, the other follows.
  - Jumps (book picker, search, cross-references, bookmarks, Back/Forward) move both panels.
  - Each panel keeps its own zoom.
  - The panel you're using leads and the other follows. Tap the button again to unlink.
- **Bible hyperlinks.** References become links that open a small pop-over with the passage, in the version you're reading, without leaving the page. *Go to* jumps there; *Open beside* shows it in the other panel (opening split view if needed), which is ideal for reading parallel accounts side by side.
  - **Parallel passages:** the references under section headings, such as "(Mark 1:9–11; Luke 3:21–22; John 1:29–34)" beside Matthew's account of Jesus' baptism, are underlined links.
  - **References in your notes:** type "Rom 8:28", "1 Cor 13:4-7" or "Psalm 23" in a verse's note, and each shows as a link chip under the note.

## New in version 0.4.1

- **See where your notes are.** Tap the chapter name to open the picker, which now goes book → chapter → verse.
  - Books, chapters and verses with your ink, highlights or images show a small dot for each layer they're on, in that layer's colour.
  - Only layers that are switched on count, so hiding a layer in *Layers* hides its dots.
  - Ink on the words counts for the version you're reading; margin notes count in every version.
  - Typed notes show a note icon and bookmarks a red ribbon. These aren't on layers, so they always show.

## New in version 0.4

- **Section headings** such as "Jesus and Nicodemus" above John 3:1, with the related passages listed under them. They come from the Berean Standard Bible (3,102 headings, public domain) and show in every version. Turn them off in *⋮ → Section headings*.
- **Line spacing.** *⋮ → Line spacing* offers Normal, Wide or Extra wide, for more room to write between lines.
- **Ink stays on its words.** Each stroke on the text is anchored to its line and moves with it when headings or line spacing change. Underlines stay under their words and circles keep their shape. Ink from earlier versions is converted automatically the first time a chapter opens.
- **Back and forward.** Arrows at the left of each panel's header return to where you were before a jump (book picker, search, cross-reference or bookmark). The tablet's Back gesture steps back too. The chapter arrows and scrolling don't add to history.
- **Zoom is remembered** for each panel, separately in landscape and portrait. **Double-tap** with a finger to switch between fit-width and your last zoom.
- **Partial eraser.** With *Eraser* picked, choose *Partial* to erase only what the eraser touches: strokes are cut, and highlights lose just the word under the eraser. *Whole strokes* works as before.
- **Select text with a long press.** Hold a finger on a word, then drag to extend. The bar that appears lets you *Copy* or *Share* the words with their reference, *Highlight* them in the current highlighter colour, or add a *Note* to the verse.
- **Search your notes.** In Search, pick *My notes* to find typed notes containing all the words you enter.
- **About the translations.** The version menu shows what each translation is like, and *About these versions…* explains how they differ.

## New in version 0.3

- **Three Bible versions, all offline:**
  - **KJV**: King James Version.
  - **BSB**: Berean Standard Bible, a modern translation that reads much like the NIV.
  - **WEB**: World English Bible, a modern translation in everyday English.

  All three are public domain. They stand in for the NIV and NLT until licensing is sorted out (see below).
- **One-tap version switch.** Tap the version name (e.g. *KJV ▾*) next to the chapter name at the top of a panel. The panel stays on the same verse. Each panel remembers its version, so split view can show two versions side by side.
- **Ink follows the rules in the requirements.** Ink and highlights drawn on the words stay with the version you drew them on. Margin notes, typed notes and bookmarks show in every version.
- **Search any version.** Search has KJV / BSB / WEB chips, starting on the version you're reading. The verse popup and bookmark list also show the verse in that version.
- Where a modern translation leaves out a verse (for example Matthew 17:21), margin notes for it sit beside the nearest verse before it.

### NIV and NLT

The NIV (Biblica) and NLT (Tyndale House) are copyrighted, so they can't be included without permission. There are two ways to add them later:
- **Offline, with permission:** ask Biblica and Tyndale for personal, non-commercial offline use. With their files, a database can be built the same way as the BSB and WEB ones (`tools/build_version_db.py`), and nothing else in the app changes.
- **Online, through [API.Bible](https://api.bible/):** its free Starter plan includes up to three copyrighted Bibles, NIV and NLT among them, for non-commercial use. You sign up and put your own API key in the app. The drawbacks: NIV and NLT would need an internet connection, only recently read chapters could be kept for offline use, and the app would need to report anonymous usage to API.Bible.

## New in version 0.2

- **Continuous scrolling.** Keep scrolling past the end of a chapter into the next one (or back into the previous one). The chapter name at the top follows along. A flick keeps the page gliding. The arrows and the book picker still jump straight to the start of a chapter.
- **Lasso.** Pick *Lasso* and draw a loop around ink, highlights or images to select them. Then:
  - drag inside the dashed box with the pen to move the selection;
  - use the bar at the top of the page to recolour, *Copy*, *Move to layer* or *Delete*;
  - tap *Done* or touch the pen outside the box to finish.
  Highlights can be recoloured, moved to a layer or deleted, but they stay on their words. Everything can be undone.
- **S Pen side button.** Hold the button while the pen touches the screen to erase, whatever tool is picked. In *⋮ → Pen button* you can make it the lasso instead, or turn it off.
- **Resizable margins.** Drag the small grip on the line between the text and a margin to make the margin wider or narrower. Widths are remembered separately for landscape and portrait.
- **Smoother pen.** The stroke you are drawing is drawn on its own layer, so each new pen point no longer redraws the whole chapter underneath.

## What's in version 0.1

**Bible and navigation**
- The King James Version is built in, and the app works with no internet connection.
- Move with the previous and next chapter arrows, or tap the book name to open a book and chapter picker.

**Study Layout**
- The text is always laid out the same way. Zooming (pinch, or *Fit width*) only changes the size, so ink never slides off the words it was drawn on.

**Drawing and highlighting**
- The S Pen draws.
- Fingers scroll, pinch to zoom, and tap a verse.
- Palm rejection ignores touches right after the pen is used and lets the pen take over if your palm is already resting on the screen.
- Pen, highlighter and eraser tools, each with colours and three sizes.
- *Snap to words* turns a highlighter swipe into a clean highlight over whole words. Turn it off to highlight freehand.

**Margins**
- The right margin is on by default. The left margin can be switched on.
- Anything written or drawn in a margin is attached to its verse and appears in every Bible version.
- Ink drawn on the words belongs to the version you drew it on.

**Images**
- Put a picture from your gallery into a margin.
- Use the *Select* tool to move or resize it, or to delete it.

**Layers**
- Layers are global: showing, hiding or locking a layer applies on every page.
- You can add, rename, reorder and delete layers.
- New ink goes on the selected layer.

**Other features**
- Undo and redo.
- Autosave: every stroke is saved the moment you lift the pen.
- Tapping a verse with your finger opens a typed note (shared across versions), a bookmark button, and cross-references from OpenBible.info. Tapping a cross-reference jumps to it; with split view on, you can open it in the other panel.
- Search:
  - Several words match verses containing all of them.
  - Put text in quotes to search for an exact phrase.
  - Use OR to match either word, and `lov*` to match word beginnings.
  - Filter by whole Bible, Old Testament, New Testament, or the current book.
  - Typing a reference such as `jn 3:16` or `1 cor 13` offers a "Go to" button.
- Split view shows two passages at once. Panels sit side by side in landscape and stacked in portrait, and the divider can be dragged.
- Light, sepia and dark page themes.
- Back up everything (ink, highlights, images, notes, layers, bookmarks) to a single .zip file, and restore it on this tablet or a new one.

## Known limits

- Behind the scenes the app keeps its old internal name (`com.biblestudy.app`, signing key alias `biblestudy`, automatic backups named `bible-study-auto-…`). Changing those would stop updates installing over your copy, so only the name you see has changed.
- NIV and NLT are waiting on licensing (see above). Cross-references come from the KJV numbering, which the BSB and WEB share.
- Rotating a lasso selection turns ink freely, but pictures only turn in quarter turns and text boxes stay upright.
- Strong's numbers come from the tagged texts at eBible.org. In the KJV some small words ("the", "unto") have none, so they can't be tapped for a word study.
- Matthew Henry's Concise Commentary skips some chapters (mostly lists and genealogies).
- John Gill's Exposition isn't included: there's no clean public-domain digital edition to build from. Calvin's Commentaries and Spurgeon's Treasury of David were added instead. Barnes' Notes here cover the New Testament only.
- Spurgeon's Treasury of David has one long note per psalm, and Matthew Henry comments on paragraphs, so linking moves them a psalm or a paragraph at a time.
- Robertson's Word Pictures is marked by CrossWire as free for non-commercial use (its last two volumes' copyright has since expired); fine for this personal app.
- Words of Jesus in the BSB are inferred from the WEB's markings and the BSB's quotation marks, so a few dialogue verses may be coloured slightly differently from a printed red-letter BSB.
- The Hebrew and Greek view uses the Hebrew text (Leningrad Codex) and the amalgamated Greek editions; it doesn't follow an imported Bible's wording.
- The performance pass for very long chapters (Psalm 119) and very large sketch pages still needs checking on the tablet itself.
- Writing sounds and pen hover can only be judged on the tablet: the automated tests have no speaker or hovering pen. Tell me if the sounds are too scratchy, soft or loud.
- A shrunk full-screen note shows its ink and text boxes; pictures on it show only when it's opened.
- Imported Bibles are matched verse by verse to the KJV's numbering, so a version that numbers verses differently may line up a verse off in places.
- Word tags in imported Bibles are worked out, not published ones: roughly 1 in 10 tagged words may point to a neighbouring Hebrew or Greek word, and loosely paraphrased words (common in the NLT, e.g. "meadows", "unfailing") have none. Footnotes in imported files are left out. Verses printed together ("1–2") show under the first verse number.
- The map is a simple outline: coasts, lakes and rivers, with no roads or modern borders.
- Fast ink can only be judged on the tablet itself. If strokes flicker or vanish, switch it off in Settings.
- On study views the lasso can recolour, move to a layer or delete writing, but not drag it to a new place; fast ink isn't used there. A line drawn across two lines of an article stretches to follow its words when the panel's width changes.

## Next milestones

- **Next: offline smart search:** all versions at once, every verse with the same Hebrew or Greek word, grouped by Nave's topics. No AI and no internet needed.
- **On hold:** the AI search and study plan (section 29 of the requirements), replaced for now by the online AI chat (1.6) and offline smart search.
- **Waiting on permission:** NIV and NLT. Once you have the files, *Settings → Bibles → Import a Bible…* adds them.

## Credits

- King James Version (1769): public domain. Text from the scrollmapper/bible_databases project (MIT).
- Berean Standard Bible (BSB): dedicated to the public domain (2023). Text from scrollmapper/bible_databases; section headings from eBible.org.
- World English Bible (WEB): public domain; "World English Bible" is a trademark of eBible.org. Text from eBible.org.
- Cross-references: OpenBible.info, CC BY 4.0.
- Strong's Hebrew and Greek dictionaries (1890, public domain), JSON edition by Open Scriptures, CC BY-SA.
- Strong's numbers for each word: the Strong's-tagged KJV, BSB and WEB USFM files from eBible.org (public domain).
- Hebrew and Greek word by word: STEPBible.org TAHOT (Translators Amalgamated Hebrew OT) and TAGNT (Translators Amalgamated Greek NT), Tyndale House Cambridge, CC BY 4.0. Only the columns shown are kept, and Greek words in neither the modern editions nor the KJV's text are left out; `tools/build_original_db.py` makes `assets/study/original.db`.
- Words of Jesus: the red-letter markings in eBible.org's KJV and WEB USFM files (public domain).
- People and places: STEPBible.org TIPNR (Translators Individualised Proper Names), CC BY 4.0. Map outline, lakes and rivers: Natural Earth (public domain); `tools/build_map.py` makes `assets/map/lands.bin`.
- Commentaries (public domain): Matthew Henry's Complete, Jamieson-Fausset-Brown, Wesley, Geneva notes, Barnes, Clarke, Keil & Delitzsch, Robertson's Word Pictures, Calvin and Spurgeon's Treasury of David, from the CrossWire Bible Society's SWORD library (crosswire.org). `tools/build_commentaries.py` rebuilds them.
- Easton's Bible Dictionary (1897), Nave's Topical Bible (1896) and Matthew Henry's Concise Commentary: public domain, from the Christian Classics Ethereal Library (ccel.org). `tools/build_study_db.py` rebuilds `study.db` from these.
- Font: Gentium Book Plus © SIL International, SIL Open Font License 1.1 (see `licenses/Gentium-OFL.txt`).
