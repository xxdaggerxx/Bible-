# Bible Study — version 0.4

A personal, fully offline Bible study app for Android tablets, designed around the Samsung Galaxy Tab S9 and S Pen. It works on any Android 10+ tablet.

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

The signed file appears at `app/build/outputs/apk/release/app-release.apk`. Nothing else to set up: Gradle finds `keystore.properties` on its own. If the build prints "No release signing key found", that file is missing and the APK is signed with a throwaway debug key instead. Don't install that one over your real copy.

In Android Studio you can also use *Build → Generate Signed App Bundle / APK → APK*, choose *Choose existing…*, and pick `signing/biblestudy-release.jks` with the password from `keystore.properties`.

### Install or update on the tablet

1. Copy `app-release.apk` to the tablet (USB cable, cloud drive or email) and tap it in *My Files*.
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

## Known limits in 0.4

- NIV and NLT are waiting on licensing (see above). Cross-references come from the KJV numbering, which the BSB and WEB share.
- The eraser removes whole strokes; there is no partial erasing.
- The lasso moves and recolours but can't resize or rotate a selection yet.
- Pen rendering still uses standard Android drawing rather than the front-buffered ink engine Samsung Notes uses, so it can lag the pen tip slightly.

## Next milestones

- **0.5:** Split view: linked scrolling, parallel view of one verse in every version, panels for search results and notes, and up to three panels on large tablets. Also images from the camera, files or clipboard; a slide-over margin on narrow screens; bookmark folders; and notes on a range of verses.
- **0.6:** NIV and NLT (once permission is granted), Strong's word studies (the BSB data includes Strong's numbers), front-buffered (lowest-latency) ink, lasso resize, and sketch pages.

## Credits

- King James Version (1769): public domain. Text from the scrollmapper/bible_databases project (MIT).
- Berean Standard Bible (BSB): dedicated to the public domain (2023). Text from scrollmapper/bible_databases; section headings from eBible.org.
- World English Bible (WEB): public domain; "World English Bible" is a trademark of eBible.org. Text from eBible.org.
- Cross-references: OpenBible.info, CC BY 4.0.
- Font: Gentium Book Plus © SIL International, SIL Open Font License 1.1 (see `licenses/Gentium-OFL.txt`).
