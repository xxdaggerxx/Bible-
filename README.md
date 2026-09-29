# Bible Study — version 0.1

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

## Known limits in 0.1

- Only the KJV is included. NIV and NLT are waiting on licensing.
- Chapters are shown one at a time; there is no continuous scrolling between chapters yet.
- The eraser removes whole strokes; there is no partial erasing.
- There is no lasso selection for ink yet.
- The S Pen side button isn't used yet.
- Pen rendering is standard Android drawing, not the low-latency ink engine. It should feel good, but it isn't yet as instant as Samsung Notes.

## Next milestones

- **0.2:** Low-latency ink (Jetpack Ink), a lasso to move and recolour ink, the S Pen side button as an eraser, continuous scrolling, and drag-to-resize margins.
- **0.3:** Import NIV and NLT once licensing is sorted, and link the scrolling of split panels.
- **0.4:** Strong's numbers and a lexicon (STEPBible data), handwriting search, and sketch pages.

## Credits

- King James Version (1769): public domain. Text from the scrollmapper/bible_databases project (MIT).
- Cross-references: OpenBible.info, CC BY 4.0.
- Font: Gentium Book Plus © SIL International, SIL Open Font License 1.1 (see `licenses/Gentium-OFL.txt`).
